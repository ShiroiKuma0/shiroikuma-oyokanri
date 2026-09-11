// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.fragment.app.FragmentActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.backup.BackupItems;
import io.github.muntashirakon.AppManager.backup.BackupUtils;
import io.github.muntashirakon.AppManager.db.entity.Backup;
import io.github.muntashirakon.AppManager.fm.FmProvider;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.types.UserPackagePair;
import io.github.muntashirakon.AppManager.utils.DateUtils;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;
import io.github.muntashirakon.dialog.SearchableSingleChoiceDialogBuilder;
import io.github.muntashirakon.io.Path;

/**
 * Fork (白い熊): hand a backup — the whole directory — to 白い熊 魔法絨毯 to carry to another device.
 *
 * <p>魔法絨毯 (`shiroikuma.mahojutan`, a FlyingCarpet fork) already accepts {@code ACTION_SEND} and
 * {@code ACTION_SEND_MULTIPLE} for {@code * / *}, and — since 2026-09-06 — <b>expands a shared
 * directory itself</b>, recreating the folder and its relative paths on the far side rather than
 * sending its files loose. That last part is what makes this worth doing at all: a backup is only
 * restorable inside its own directory, so sending the files flat would produce something that
 * cannot be restored.
 *
 * <p><b>Why a directory URI and not a list of files.</b> The obvious implementation — enumerate the
 * backup's members and share them — throws away the directory name, which is the timestamp that
 * distinguishes one backup of an app from another. Sharing the directory keeps it.
 *
 * <p><b>Why {@link FmProvider} and not {@code file://}.</b> A {@code file://} URI leaving the app
 * raises {@code FileUriExposedException} under StrictMode, and this app does not relax the policy.
 * The provider already exists for the file manager's own sharing, grants URI permissions, and
 * reports {@code vnd.android.document/directory} for a directory — which is one of the shapes
 * 魔法絨毯 recognises. Where it does not, 魔法絨毯 falls back to opening the descriptor and reading
 * the real path out of {@code /proc/self/fd}, which works because both apps hold all-files access.
 *
 * <p>The intent is aimed at 魔法絨毯 by package rather than offered through a chooser: 白い熊 asked
 * for this one destination, and a chooser here would put the backup one mis-tap away from being
 * handed to something else entirely.
 */
public final class ShareBackupHandler {
    public static final String TAG = ShareBackupHandler.class.getSimpleName();

    /** The carrier. Not configurable on purpose — this action names its destination. */
    public static final String CARRIER_PACKAGE = "shiroikuma.mahojutan";

    private ShareBackupHandler() {
    }

    /** One backup, with everything the picker needs to describe it. */
    private static class Item {
        final Path directory;
        final CharSequence label;

        Item(@NonNull Path directory, @NonNull CharSequence label) {
            this.directory = directory;
            this.label = label;
        }
    }

    /**
     * Ask which backup to send, then send it. Enumeration touches the database and stats every
     * backup directory, so it runs off the main thread and the picker follows on it.
     *
     * <p><b>The wait is shown.</b> 白い熊: "clicking backup seemingly doesn't do anything on click".
     * It was doing plenty — reading the backup database and stat-ing every directory behind it —
     * but a pill that produces no visible change is a pill that did not work, and the honest reading
     * of it is to tap again. The progress indicator goes up on the tap and comes down on every exit
     * from this method, including the ones that only raise a toast. This is the same treatment the
     * lenses get for the same reason (+162: "a lens can be seconds of work on its first pass, so say
     * so rather than leaving the list looking stuck").
     */
    public static void share(@NonNull MainActivity activity,
                             @NonNull List<UserPackagePair> pairs) {
        if (pairs.isEmpty()) {
            return;
        }
        if (!isCarrierInstalled(activity)) {
            UIUtils.displayLongToast(R.string.share_backup_no_carrier);
            return;
        }
        activity.showProgressIndicator(true);
        ThreadUtils.postOnBackgroundThread(() -> {
            List<Item> items = collect(activity, pairs);
            ThreadUtils.postOnMainThread(() -> {
                if (activity.isDestroyed()) {
                    return;
                }
                activity.showProgressIndicator(false);
                if (items.isEmpty()) {
                    UIUtils.displayShortToast(R.string.share_backup_none);
                    return;
                }
                showPicker(activity, items);
            });
        });
    }

    /** Told whether the enumeration is running, so a caller can show a wait of its own. */
    public interface BusyListener {
        void onBusy(boolean busy);
    }

    /**
     * Send the backup a just-finished run produced: straight to 魔法絨毯 when the run covered ONE
     * app, through the picker when it covered several.
     *
     * <p><b>The asymmetry is the point.</b> After backing up one app there is exactly one thing
     * the request can mean — the backup just written, which is the newest — and a picker there is
     * a question with one answer. After backing up forty there is no such thing, and the picker
     * is then doing real work rather than asking politely: 魔法絨毯 names each received folder by
     * its own leaf, so two backups sent together merge into one unrestorable directory (see
     * {@link #showPicker}), and a batch stamps every app in the same second.
     *
     * <p>This is deliberately NOT the behaviour of {@link #share}, which always asks. That one is
     * reached from the main list, where nothing has just happened and "the newest" names no
     * particular backup.
     */
    public static void shareFinishedBackup(@NonNull FragmentActivity activity,
                                           @NonNull List<UserPackagePair> pairs,
                                           @Nullable BusyListener busyListener) {
        if (pairs.isEmpty()) {
            return;
        }
        if (!isCarrierInstalled(activity)) {
            UIUtils.displayLongToast(R.string.share_backup_no_carrier);
            return;
        }
        boolean single = pairs.size() == 1;
        setBusy(busyListener, true);
        ThreadUtils.postOnBackgroundThread(() -> {
            List<Item> items = collect(activity, pairs);
            ThreadUtils.postOnMainThread(() -> {
                if (activity.isDestroyed()) {
                    return;
                }
                setBusy(busyListener, false);
                if (items.isEmpty()) {
                    UIUtils.displayShortToast(R.string.share_backup_none);
                    return;
                }
                if (single) {
                    // collect() sorts newest first, and right after a backup the newest IS the
                    // one that was just written.
                    send(activity, items.get(0));
                } else {
                    showPicker(activity, items);
                }
            });
        });
    }

    private static void setBusy(@Nullable BusyListener listener, boolean busy) {
        if (listener != null) {
            listener.onBusy(busy);
        }
    }

    @WorkerThread
    @NonNull
    private static List<Item> collect(@NonNull Context context,
                                      @NonNull List<UserPackagePair> pairs) {
        List<Item> items = new ArrayList<>();
        for (UserPackagePair pair : pairs) {
            List<Backup> backups = BackupUtils.getBackupMetadataFromDbNoLockValidate(pair.getPackageName());
            if (backups == null) {
                continue;
            }
            // Newest first: the one you almost always mean is then the one at the top.
            Collections.sort(backups, (a, b) -> Long.compare(b.backupTime, a.backupTime));
            for (Backup backup : backups) {
                Path directory = directoryOf(backup);
                if (directory == null) {
                    continue;
                }
                CharSequence label = (backup.label != null ? backup.label : backup.packageName)
                        + " · " + DateUtils.formatDateTime(context, backup.backupTime);
                if (backup.versionName != null) {
                    label = label + " · " + backup.versionName;
                }
                items.add(new Item(directory, label));
            }
        }
        return items;
    }

    /**
     * The directory this backup occupies. Null when the backup is recorded in the database but its
     * directory has gone — which happens, and is not worth a dialog of its own: the entry is simply
     * not offered.
     */
    @Nullable
    private static Path directoryOf(@NonNull Backup backup) {
        try {
            BackupItems.BackupItem item = backup.getItem();
            Path directory = item.getBackupPath();
            return directory.exists() ? directory : null;
        } catch (Throwable th) {
            Log.w(TAG, "Could not resolve the directory of a backup of %s.", th, backup.packageName);
            return null;
        }
    }

    private static void showPicker(@NonNull FragmentActivity activity, @NonNull List<Item> items) {
        CharSequence[] names = new CharSequence[items.size()];
        for (int i = 0; i < items.size(); ++i) {
            names[i] = items.get(i).label;
        }
        // Fork (白い熊): ONE backup per transfer, and the picker enforces it rather than trusting
        // the habit.
        //
        // 魔法絨毯 names each shared folder by its own leaf and nothing above it, so a backup
        // arrives as "2026-09-08_14-08-33/" with no package name in it. Sending two at once is
        // therefore not merely untidy: two directories with the same leaf are merged by the
        // receiver into one folder, which then looks like a backup and restores as garbage. That
        // is not hypothetical here — six packages in this backup tree share the timestamp
        // 2026-09-06_13-45-53, because a batch backup stamps them all in the same second.
        //
        // 白い熊 sends one at a time, so single choice costs nothing and removes the whole class
        // of failure. If 魔法絨毯 ever grows an extra for naming the received folder, this can
        // become a multi-select again and each backup can carry its package name across.
        new SearchableSingleChoiceDialogBuilder<>(activity, items, names)
                .setTitle(R.string.share_backup)
                .setSelection(items.get(0))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.share, (dialog, which, selected) -> {
                    if (selected != null) {
                        send(activity, selected);
                    }
                })
                .show();
    }

    private static void send(@NonNull FragmentActivity activity, @NonNull Item item) {
        sendDirectory(activity, item.directory);
    }

    /**
     * Hand ONE backup directory to 魔法絨毯. Public because the app's own backup dialog already
     * knows which backup is meant and has no need of the picker.
     */
    public static void sendDirectory(@NonNull FragmentActivity activity, @NonNull Path directory) {
        if (!isCarrierInstalled(activity)) {
            UIUtils.displayLongToast(R.string.share_backup_no_carrier);
            return;
        }
        // The grant is REQUIRED, not belt-and-braces: our URI is neither a tree URI nor a document
        // URI, so 魔法絨毯 cannot resolve it through SAF and reaches the real path by opening the
        // descriptor and reading /proc/self/fd. That open needs this permission; without it the
        // share fails with "Could not read …, ignoring it". Only the directory URI needs granting —
        // the members are re-wrapped on the far side and read through its own all-files access.
        Intent intent = new Intent(Intent.ACTION_SEND)
                .setType("*/*")
                .setPackage(CARRIER_PACKAGE)
                .putExtra(Intent.EXTRA_STREAM, FmProvider.getContentUri(directory))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
            // The share only ARMS the transfer: 魔法絨毯 still needs Hotspot or Shared Network
            // chosen and Start pressed, deliberately, because hotspot mode takes both devices off
            // their network. Say so, or the silence afterwards reads as a failure.
            UIUtils.displayLongToast(R.string.share_backup_armed);
        } catch (Throwable th) {
            Log.e(TAG, "Could not hand the backup to %s.", th, CARRIER_PACKAGE);
            UIUtils.displayLongToast(R.string.share_backup_no_carrier);
        }
    }

    private static boolean isCarrierInstalled(@NonNull Context context) {
        try {
            context.getPackageManager().getPackageInfo(CARRIER_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
