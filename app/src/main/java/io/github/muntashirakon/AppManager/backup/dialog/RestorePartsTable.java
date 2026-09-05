// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.backup.dialog;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.graphics.ColorUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.backup.BackupFlags;
import io.github.muntashirakon.AppManager.backup.struct.BackupMetadataV5;
import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.self.imagecache.ImageLoader;
import io.github.muntashirakon.AppManager.utils.DateUtils;
import io.github.muntashirakon.AppManager.utils.ForkDialog;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;
import io.github.muntashirakon.widget.FlowLayout;

/**
 * Fork (白い熊, +132): the per-app restore table — every app in the selection, each with the
 * parts <b>its own backup holds</b> ticked.
 *
 * <p>Restoring a batch used to be one set of flags applied to all of it, chosen from a grey
 * checkbox column that could only ever describe the worst backup in the set (the "worst backup
 * flag" — the intersection). Two things were wrong with that. A part that only some backups
 * carry disappeared from the choice entirely, so the app that <em>did</em> have its data archived
 * silently did not get it back. And restoring is not uniform work: putting the APK back over an
 * app you have deliberately downgraded is a different decision from putting its data back, and
 * the answer differs per app.
 *
 * <p>So this mirrors {@link BatchBackupTableDialog} exactly — one row per app, the "every app"
 * pills at the top, the parts in their own colours — and adds the one thing backup has no need
 * of: <b>which backup</b>. An app with several is a line you can tap, and the parts re-read
 * themselves from whichever one you pick, because they are a fact about that archive.
 */
public class RestorePartsTable {
    private final Context mContext;
    private final List<BackupInfo> mInfos;
    /** The backup chosen for each app; the base one, or the newest when there is no base. */
    private final Map<String, BackupMetadataV5> mChosenBackup = new LinkedHashMap<>();
    private final Map<String, Integer> mFlags = new LinkedHashMap<>();
    private final boolean mHasUninstalled;
    private int mOptionFlags;
    @Nullable
    private LinearLayout mRowsContainer;

    public RestorePartsTable(@NonNull Context context, @NonNull List<BackupInfo> infos,
                             boolean hasUninstalled) {
        mContext = context;
        mInfos = infos;
        mHasUninstalled = hasUninstalled;
        int preferred = BackupFlags.fromPref().getFlags();
        for (BackupInfo info : infos) {
            BackupMetadataV5 backup = pickBackup(info);
            if (backup == null) {
                continue;
            }
            mChosenBackup.put(info.packageName, backup);
            mFlags.put(info.packageName, defaultFlagsFor(info, backup, preferred));
        }
    }

    /**
     * The base backup is what a batch restore has always used; when an app has none, the newest
     * is the only sensible stand-in — the alternative is to drop the app silently.
     */
    @Nullable
    private static BackupMetadataV5 pickBackup(@NonNull BackupInfo info) {
        BackupMetadataV5 newest = null;
        for (BackupMetadataV5 metadata : info.getBackupMetadataList()) {
            if (metadata.isBaseBackup()) {
                return metadata;
            }
            if (newest == null || metadata.info.backupTime > newest.info.backupTime) {
                newest = metadata;
            }
        }
        return newest;
    }

    private static int defaultFlagsFor(@NonNull BackupInfo info, @NonNull BackupMetadataV5 backup,
                                       int preferred) {
        int available = backup.info.flags.getFlags();
        int flags = available & preferred;
        if (!info.isInstalled() && (available & BackupFlags.BACKUP_APK_FILES) != 0) {
            // Nothing to restore data into otherwise.
            flags |= BackupFlags.BACKUP_APK_FILES;
        }
        return flags;
    }

    /** Per-app flags, for {@code BatchBackupOptions}. */
    @NonNull
    public Map<String, Integer> perPackageFlags() {
        Map<String, Integer> out = new LinkedHashMap<>(mFlags);
        for (Map.Entry<String, Integer> entry : out.entrySet()) {
            entry.setValue(entry.getValue() | mOptionFlags);
        }
        return out;
    }

    /** Which backup each app is restored from. */
    @NonNull
    public Map<String, String[]> perPackageRelativeDirs() {
        Map<String, String[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, BackupMetadataV5> entry : mChosenBackup.entrySet()) {
            out.put(entry.getKey(), new String[]{entry.getValue().info.getRelativeDir()});
        }
        return out;
    }

    /** What an app not named in the per-app map would get. */
    public int fallbackFlags() {
        int union = mOptionFlags;
        for (Integer value : mFlags.values()) {
            union |= value;
        }
        return union;
    }

    public boolean isEmpty() {
        return mChosenBackup.isEmpty();
    }

    /** The whole column: the "every app" pills, then one box per app. */
    @NonNull
    public View build() {
        float d = mContext.getResources().getDisplayMetrics().density;
        int ink = ForkThemeUtils.getTextColor();
        LinearLayout body = new LinearLayout(mContext);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(12 * d);
        body.setPadding(pad, Math.round(4 * d), pad, Math.round(8 * d));

        // One row of pills that sets every app at once — but only where the app's own backup
        // holds the part, so this can never tick something that is not in the archive.
        body.addView(sectionLabel(mContext.getString(R.string.restore_all_apps),
                ColorUtils.setAlphaComponent(ink, 0x99)));
        FlowLayout allFlow = flow();
        int union = 0;
        for (BackupMetadataV5 backup : mChosenBackup.values()) {
            union |= backup.info.flags.getFlags();
        }
        for (BackupParts.Part part : BackupParts.contentParts()) {
            if ((union & part.flag) == 0) {
                continue;
            }
            boolean on = allHave(part.flag);
            allFlow.addView(BackupPartRows.partPill(mContext, part, on, checked -> {
                for (Map.Entry<String, BackupMetadataV5> entry : mChosenBackup.entrySet()) {
                    if ((entry.getValue().info.flags.getFlags() & part.flag) == 0) {
                        continue;
                    }
                    Integer current = mFlags.get(entry.getKey());
                    int value = current == null ? 0 : current;
                    mFlags.put(entry.getKey(), checked ? (value | part.flag) : (value & ~part.flag));
                }
                lockApkForUninstalled();
                rebuildRows();
            }));
        }
        body.addView(allFlow);

        // Restore options that are not parts of the archive.
        FlowLayout optionFlow = flow();
        int grey = 0xFFB0BEC5;
        optionFlow.addView(BackupPartRows.plainPill(mContext,
                mContext.getString(R.string.restore_skip_signature), grey, false, on -> {
                    if (on) {
                        mOptionFlags |= BackupFlags.BACKUP_NO_SIGNATURE_CHECK;
                    } else {
                        mOptionFlags &= ~BackupFlags.BACKUP_NO_SIGNATURE_CHECK;
                    }
                }));
        body.addView(optionFlow);

        mRowsContainer = new LinearLayout(mContext);
        mRowsContainer.setOrientation(LinearLayout.VERTICAL);
        body.addView(mRowsContainer);
        rebuildRows();
        return body;
    }

    private boolean allHave(int flag) {
        for (Map.Entry<String, BackupMetadataV5> entry : mChosenBackup.entrySet()) {
            if ((entry.getValue().info.flags.getFlags() & flag) == 0) {
                continue;
            }
            Integer current = mFlags.get(entry.getKey());
            if (current == null || (current & flag) == 0) {
                return false;
            }
        }
        return true;
    }

    /** An app that is not installed cannot have its APK left out, whatever was just toggled. */
    private void lockApkForUninstalled() {
        for (BackupInfo info : mInfos) {
            if (info.isInstalled()) {
                continue;
            }
            BackupMetadataV5 backup = mChosenBackup.get(info.packageName);
            if (backup == null || (backup.info.flags.getFlags() & BackupFlags.BACKUP_APK_FILES) == 0) {
                continue;
            }
            Integer current = mFlags.get(info.packageName);
            mFlags.put(info.packageName, (current == null ? 0 : current) | BackupFlags.BACKUP_APK_FILES);
        }
    }

    private void rebuildRows() {
        if (mRowsContainer == null) {
            return;
        }
        mRowsContainer.removeAllViews();
        for (BackupInfo info : mInfos) {
            mRowsContainer.addView(appRow(info));
        }
    }

    /** One app: icon and name, which backup it comes from, and the parts that backup holds. */
    @NonNull
    private View appRow(@NonNull BackupInfo info) {
        float d = mContext.getResources().getDisplayMetrics().density;
        int ink = ForkThemeUtils.getTextColor();
        String packageName = info.packageName;
        BackupMetadataV5 backup = mChosenBackup.get(packageName);

        LinearLayout box = new LinearLayout(mContext);
        box.setOrientation(LinearLayout.VERTICAL);
        int padH = Math.round(10 * d);
        int padV = Math.round(8 * d);
        box.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxLp.bottomMargin = Math.round(8 * d);
        box.setLayoutParams(boxLp);
        BackupPartRows.applyBoxStyle(box, ink, false);

        LinearLayout header = new LinearLayout(mContext);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new AppCompatImageView(mContext);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                Math.round(28 * d), Math.round(28 * d));
        iconLp.setMarginEnd(Math.round(10 * d));
        icon.setLayoutParams(iconLp);
        ApplicationInfo appInfo = null;
        try {
            appInfo = PackageManagerCompat.getApplicationInfo(packageName,
                    PackageManagerCompat.MATCH_UNINSTALLED_PACKAGES
                            | PackageManagerCompat.MATCH_DISABLED_COMPONENTS
                            | PackageManagerCompat.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES,
                    info.userIds.isEmpty() ? 0 : info.userIds.valueAt(0));
        } catch (Throwable ignore) {
        }
        ImageLoader.getInstance().displayImage(packageName, appInfo, icon);
        header.addView(icon);
        AppCompatTextView label = new AppCompatTextView(mContext);
        CharSequence appLabel = info.getAppLabel();
        label.setText(appLabel != null ? appLabel : packageName);
        label.setTextColor(ink);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        label.setSingleLine(true);
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(label);
        box.addView(header);

        if (backup == null) {
            AppCompatTextView none = new AppCompatTextView(mContext);
            none.setText(R.string.restore_no_backup);
            none.setTextColor(ColorUtils.setAlphaComponent(ink, 0x99));
            none.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            box.addView(none);
            box.setAlpha(0.6f);
            return box;
        }

        // Which backup. Tappable only when there is a choice to make.
        List<BackupMetadataV5> all = info.getBackupMetadataList();
        AppCompatTextView from = new AppCompatTextView(mContext);
        from.setText(mContext.getString(R.string.restore_from_backup, describe(backup)));
        from.setTextColor(ColorUtils.setAlphaComponent(ink, all.size() > 1 ? 0xDD : 0x99));
        from.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        from.setPadding(0, Math.round(2 * d), 0, Math.round(4 * d));
        if (all.size() > 1) {
            from.setOnClickListener(v -> pickBackupFor(info, all));
        }
        box.addView(from);

        FlowLayout partFlow = flow();
        int available = backup.info.flags.getFlags();
        Integer chosen = mFlags.get(packageName);
        int value = chosen == null ? 0 : chosen;
        boolean apkLocked = !info.isInstalled() && (available & BackupFlags.BACKUP_APK_FILES) != 0;
        for (BackupParts.Part part : BackupParts.contentParts()) {
            if ((available & part.flag) == 0) {
                continue;
            }
            if (apkLocked && part.flag == BackupFlags.BACKUP_APK_FILES) {
                // Drawn on and inert, with the reason beside it — see BackupPartRows#partRow.
                android.widget.TextView pill = BackupPartRows.partPill(mContext, part, true, on -> {
                });
                pill.setOnClickListener(null);
                pill.setClickable(false);
                partFlow.addView(pill);
                continue;
            }
            partFlow.addView(BackupPartRows.partPill(mContext, part, (value & part.flag) != 0, on -> {
                Integer current = mFlags.get(packageName);
                int v = current == null ? 0 : current;
                mFlags.put(packageName, on ? (v | part.flag) : (v & ~part.flag));
            }));
        }
        box.addView(partFlow);
        if (apkLocked) {
            AppCompatTextView note = new AppCompatTextView(mContext);
            note.setText(R.string.restore_apk_forced);
            note.setTextColor(ColorUtils.setAlphaComponent(ink, 0xAA));
            note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            note.setPadding(0, Math.round(4 * d), 0, 0);
            box.addView(note);
        }
        return box;
    }

    private void pickBackupFor(@NonNull BackupInfo info, @NonNull List<BackupMetadataV5> all) {
        CharSequence[] labels = new CharSequence[all.size()];
        for (int i = 0; i < all.size(); ++i) {
            labels[i] = describe(all.get(i));
        }
        ForkDialog.present(ForkDialog.builder(mContext)
                .setTitle(R.string.restore_pick_backup)
                .setItems(labels, (dialog, which) -> {
                    BackupMetadataV5 picked = all.get(which);
                    mChosenBackup.put(info.packageName, picked);
                    // The parts belong to the archive, so they are re-read rather than carried
                    // across: a part the previous backup had may simply not be in this one.
                    mFlags.put(info.packageName, defaultFlagsFor(info, picked,
                            BackupFlags.fromPref().getFlags()));
                    rebuildRows();
                })
                .setNegativeButton(R.string.cancel, null));
    }

    @NonNull
    private CharSequence describe(@NonNull BackupMetadataV5 backup) {
        CharSequence when = DateUtils.formatDateTime(mContext, backup.info.backupTime);
        String name = backup.metadata.backupName;
        if (name == null || name.isEmpty()) {
            return mContext.getString(R.string.base_backup) + " · " + when;
        }
        return name + " · " + when;
    }

    @NonNull
    private View sectionLabel(@NonNull CharSequence text, int color) {
        float d = mContext.getResources().getDisplayMetrics().density;
        AppCompatTextView label = new AppCompatTextView(mContext);
        label.setText(text);
        label.setTextColor(color);
        label.setAllCaps(true);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        label.setPadding(0, Math.round(4 * d), 0, Math.round(6 * d));
        return label;
    }

    @NonNull
    private FlowLayout flow() {
        float d = mContext.getResources().getDisplayMetrics().density;
        FlowLayout flow = new FlowLayout(mContext);
        flow.setChildSpacing(Math.round(6 * d));
        flow.setRowSpacing(Math.round(5 * d));
        return flow;
    }
}
