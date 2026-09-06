// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.settings;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;

/**
 * Fork: the 保存復元 state-export automation contract — the headless entry
 * point 白い熊's automation app (白い熊 自由作業盤, {@code shiroikuma.jiyusagyoban})
 * uses to back up every sister app in one run.
 * <p>
 * Two gated actions ({@link AutomationAuth} is the gate — the master switch is <b>on</b> by
 * default and a token is asked for only when 白い熊 has said to ask for one, +151):
 * <ul>
 * <li>{@code <pkg>.action.LIST_CATEGORIES} — instant; replies {@code OK:}
 * followed by one {@code id<TAB>label} line per exportable category. This
 * app's categories are flat, so the optional third (parent-id) field is
 * always omitted.</li>
 * <li>{@code <pkg>.action.EXPORT_STATE} — runs the very same export the
 * Export/Import panel runs ({@link SettingsBackupManager#writeExport}), writing
 * exactly ONE zip, and replies with its absolute path and real byte size.</li>
 * </ul>
 * <p>
 * <b>Hard-won wire constraints — do not "improve" these</b> (verified on
 * 白い熊's Mate XT, 2026-07-23):
 * <ul>
 * <li>The reply is a <b>fresh broadcast</b>. No {@code ResultReceiver}, no
 * {@code PendingIntent}, no {@code Messenger}: EMUI will not reliably carry a
 * live Binder into another app's manifest receiver and may drop the broadcast
 * carrying one outright.</li>
 * <li>The ordered-broadcast result is never the only reply — EMUI severs that
 * channel between third-party apps. We still set it (correct AOSP behaviour),
 * but the broadcast is what actually arrives.</li>
 * <li>{@code FLAG_INCLUDE_STOPPED_PACKAGES} is mandatory, or a backgrounded /
 * stopped caller never hears us.</li>
 * </ul>
 * Exactly one terminal reply is sent per request, guarded by an
 * {@link AtomicBoolean} so an async success and a synchronous error can never
 * both fire.
 */
public class StateExportReceiver extends BroadcastReceiver {
    public static final String TAG = StateExportReceiver.class.getSimpleName();

    public static final String ACTION_EXPORT_STATE = BuildConfig.APPLICATION_ID + ".action.EXPORT_STATE";
    public static final String ACTION_LIST_CATEGORIES = BuildConfig.APPLICATION_ID + ".action.LIST_CATEGORIES";
    /**
     * Stop the export that is running. Fire-and-forget: it passes the same gate
     * as the others but <b>sends no reply of its own</b>, and arriving
     * when nothing is running — or after the run already finished — is a silent
     * no-op, never an error.
     */
    public static final String ACTION_CANCEL_EXPORT = BuildConfig.APPLICATION_ID + ".action.CANCEL_EXPORT";

    /**
     * Set by {@link #ACTION_CANCEL_EXPORT}, read between entries by the write
     * loop. One flag suffices because two concurrent exports are forbidden by
     * the contract; it is cleared as each run starts, so a cancel that arrives
     * after a run has ended cannot stop the next one.
     */
    private static final AtomicBoolean sCancelRequested = new AtomicBoolean(false);

    private static final String EXTRA_TOKEN = "token";
    private static final String EXTRA_PATH = "path";
    private static final String EXTRA_ITEMS = "items";
    private static final String EXTRA_PROGRESS_ACTION = "progress_action";
    private static final String EXTRA_REPLY_ACTION = "reply_action";
    private static final String EXTRA_REPLY_PACKAGE = "reply_package";
    private static final String EXTRA_REPLY_ID = "reply_id";

    /** 白い熊's requirement: real counts, never a percentage. */
    private static final String PROGRESS_UNIT = "区分";
    private static final long PROGRESS_THROTTLE_MS = 500L;

    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
        final String action = intent.getAction();
        if (action == null) return;
        Log.d(TAG, "received %s", action);
        final Context appContext = context.getApplicationContext();
        final String replyAction = intent.getStringExtra(EXTRA_REPLY_ACTION);
        final String replyPackage = intent.getStringExtra(EXTRA_REPLY_PACKAGE);
        final String replyId = intent.getStringExtra(EXTRA_REPLY_ID);
        if (replyAction == null || replyPackage == null || replyId == null) {
            // Without all three we have no channel to answer on at all; there
            // is nothing useful to do but drop the request.
            return;
        }
        final String progressAction = intent.getStringExtra(EXTRA_PROGRESS_ACTION);
        final String token = intent.getStringExtra(EXTRA_TOKEN);
        final String pathExtra = intent.getStringExtra(EXTRA_PATH);
        final String itemsExtra = intent.getStringExtra(EXTRA_ITEMS);

        final PendingResult pendingResult = goAsync();
        final AtomicBoolean replied = new AtomicBoolean(false);
        ThreadUtils.postOnBackgroundThread(() -> {
            String result;
            try {
                // One gate, one place (+151): the master switch, then the token only when this
                // app has been told to ask for one. A token sent to an app that does not
                // require one is ignored rather than refused.
                String refused = AutomationAuth.refuse(appContext, token);
                if (refused != null) {
                    result = refused;
                } else if (ACTION_LIST_CATEGORIES.equals(action)) {
                    result = listCategories(appContext);
                } else if (ACTION_EXPORT_STATE.equals(action)) {
                    result = doExport(appContext, pathExtra, itemsExtra, progressAction,
                            replyPackage, replyId);
                } else if (ACTION_CANCEL_EXPORT.equals(action)) {
                    // Raise the flag and say nothing. The running export sends
                    // ERROR:cancelled for its OWN request through the normal
                    // channel; answering the cancel too would be a second reply
                    // for a request nobody made.
                    sCancelRequested.set(true);
                    Log.d(TAG, "cancel requested by %s", replyPackage);
                    pendingResult.finish();
                    return;
                } else {
                    result = "ERROR:unknown action " + action;
                }
            } catch (Throwable th) {
                result = "ERROR:" + oneLine(String.valueOf(th.getMessage()));
            }
            reply(appContext, pendingResult, replied, replyAction, replyPackage, replyId, result);
        });
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /**
     * {@code OK:} + one {@code id<TAB>label<TAB>parent<TAB>on|off} line per
     * category.
     *
     * <p>The category list here is flat, so the third field is always empty —
     * but it is still <b>sent</b>, because the fields are positional and the
     * fourth would otherwise be read as the parent. The fourth states whether
     * the item starts ticked, so 自由作業盤's picker takes the default from this
     * app rather than assuming one.
     *
     * <p>Public since +134: {@code AppDataTransfer} answers its own category listing with this
     * rather than broadcasting to itself past its own token gate. One renderer, so the two
     * contracts can never describe this app differently.
     */
    @NonNull
    public static String listCategories(@NonNull Context context) {
        StringBuilder sb = new StringBuilder("OK:");
        boolean first = true;
        for (SettingsBackupManager.Category cat : SettingsBackupManager.Category.values()) {
            if (!first) sb.append('\n');
            first = false;
            sb.append(cat.id).append('\t').append(context.getString(cat.labelRes))
                    .append('\t')                                   // no parent — flat list
                    .append('\t').append(cat.defaultSelected ? "on" : "off");
        }
        return sb.toString();
    }

    /**
     * Run the real export headlessly into ONE zip.
     * <p>
     * Directory precedence: the {@code path} extra → the app's configured
     * export directory → {@code ERROR:no-directory}. The app holds
     * {@code MANAGE_EXTERNAL_STORAGE} and both sources are plain absolute
     * paths, so this writes with {@code java.io.File} directly.
     */
    @NonNull
    private static String doExport(@NonNull Context context, @Nullable String pathExtra,
                                   @Nullable String itemsExtra, @Nullable String progressAction,
                                   @NonNull String replyPackage, @NonNull String replyId) {
        Set<SettingsBackupManager.Category> categories;
        try {
            categories = parseItems(itemsExtra);
        } catch (IllegalArgumentException e) {
            return "ERROR:unknown category in items: " + oneLine(String.valueOf(itemsExtra));
        }
        String dirPath = pathExtra != null && !pathExtra.trim().isEmpty()
                ? pathExtra.trim()
                : Prefs.Storage.getSettingsExportDirectory();
        if (dirPath.isEmpty()) {
            return "ERROR:no-directory";
        }
        File dir = new File(dirPath);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return "ERROR:cannot create directory " + oneLine(dirPath);
        }
        File outFile = new File(dir, SettingsBackupManager.newFileName());
        // Written under ".part" and renamed only on success, so a cancelled or
        // failed run leaves the directory exactly as it found it — no short
        // archive that looks complete, no stray partial.
        File partFile = new File(dir, outFile.getName() + ".part");
        final ProgressSender progress = new ProgressSender(context, progressAction, replyPackage, replyId);
        // A cancel that arrived before this run started must not stop it.
        sCancelRequested.set(false);
        try {
            SettingsBackupManager.writeExport(context, categories, new FileOutputStream(partFile),
                    progress::onCategory, sCancelRequested::get);
            if (!partFile.renameTo(outFile)) {
                throw new IOException("could not finalise " + outFile.getName());
            }
        } catch (SettingsBackupManager.ExportCancelledException cancelled) {
            //noinspection ResultOfMethodCallIgnored
            partFile.delete();
            Log.d(TAG, "export cancelled; %s removed", partFile.getName());
            return "ERROR:cancelled";
        } catch (Throwable th) {
            //noinspection ResultOfMethodCallIgnored
            partFile.delete();
            //noinspection ResultOfMethodCallIgnored
            outFile.delete();
            return "ERROR:export failed: " + oneLine(String.valueOf(th.getMessage()));
        } finally {
            sCancelRequested.set(false);
        }
        long bytes = outFile.length();
        String human = humanSize(bytes);
        progress.finish(categories.size(), human);
        return "OK:" + outFile.getAbsolutePath() + "|" + bytes + "|" + human + "|"
                + categories.size() + " categories";
    }

    /**
     * Parse the {@code items} extra. Absent or empty means everything.
     *
     * @throws IllegalArgumentException if any listed id is unknown.
     */
    @NonNull
    private static Set<SettingsBackupManager.Category> parseItems(@Nullable String itemsExtra) {
        if (itemsExtra == null || itemsExtra.trim().isEmpty()) {
            return SettingsBackupManager.allCategories();
        }
        Set<SettingsBackupManager.Category> categories =
                EnumSet.noneOf(SettingsBackupManager.Category.class);
        for (String raw : itemsExtra.split(",")) {
            String id = raw.trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) continue;
            SettingsBackupManager.Category cat = SettingsBackupManager.Category.byId(id);
            if (cat == null) {
                throw new IllegalArgumentException(id);
            }
            categories.add(cat);
        }
        // Only separators — same meaning as an absent extra.
        return categories.isEmpty() ? SettingsBackupManager.allCategories() : categories;
    }

    // ------------------------------------------------------------------
    // Progress broadcasts (real counts, throttled to one per 500 ms)
    // ------------------------------------------------------------------

    private static final class ProgressSender {
        private final Context mContext;
        @Nullable
        private final String mAction;
        private final String mReplyPackage;
        private final String mReplyId;
        private long mLastSent = 0L;

        ProgressSender(@NonNull Context context, @Nullable String action,
                       @NonNull String replyPackage, @NonNull String replyId) {
            mContext = context;
            mAction = action;
            mReplyPackage = replyPackage;
            mReplyId = replyId;
        }

        void onCategory(int current, int total, @NonNull String label) {
            long now = SystemClock.elapsedRealtime();
            // Always let the first one through; then at most one per 500 ms.
            if (mLastSent != 0L && now - mLastSent < PROGRESS_THROTTLE_MS) return;
            mLastSent = now;
            send(PROGRESS_UNIT + " " + current + "/" + total + " — " + label, current, total);
        }

        /** The mandatory final one at completion, never throttled. */
        void finish(int total, @NonNull String human) {
            mLastSent = SystemClock.elapsedRealtime();
            send(PROGRESS_UNIT + " " + total + "/" + total + " — " + human, total, total);
        }

        private void send(@NonNull String text, int current, int total) {
            if (mAction == null || mAction.isEmpty()) return;
            Intent intent = new Intent(mAction);
            intent.setPackage(mReplyPackage);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtra(EXTRA_REPLY_ID, mReplyId);
            intent.putExtra("app", mContext.getString(R.string.app_name));
            intent.putExtra("text", text);
            intent.putExtra("current", (long) current);
            intent.putExtra("total", (long) total);
            intent.putExtra("unit", PROGRESS_UNIT);
            mContext.sendBroadcast(intent);
        }
    }

    // ------------------------------------------------------------------
    // Reply
    // ------------------------------------------------------------------

    /** The one terminal reply. Fires at most once per request. */
    private static void reply(@NonNull Context context, @NonNull PendingResult pendingResult,
                              @NonNull AtomicBoolean replied, @NonNull String replyAction,
                              @NonNull String replyPackage, @NonNull String replyId,
                              @NonNull String result) {
        if (!replied.compareAndSet(false, true)) return;
        // The reply goes to another app, so this log line is the only thing
        // 白い熊 can watch on-device when an automation run misbehaves.
        Log.d(TAG, "reply %s → %s: %s", replyId, replyPackage, result);
        try {
            Intent intent = new Intent(replyAction);
            intent.setPackage(replyPackage);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtra(EXTRA_REPLY_ID, replyId);
            intent.putExtra("result", result);
            context.sendBroadcast(intent);
            // Correct AOSP behaviour, and harmless — but never the only reply,
            // because EMUI severs this channel between third-party apps.
            pendingResult.setResultData(result);
        } finally {
            pendingResult.finish();
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** {@code 4.6 MB}, {@code 1.20 GB} — the caller cannot stat the file. */
    @NonNull
    private static String humanSize(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024d;
        if (kb < 1024d) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024d;
        if (mb < 1024d) return String.format(Locale.ROOT, "%.1f MB", mb);
        double gb = mb / 1024d;
        if (gb < 1024d) return String.format(Locale.ROOT, "%.2f GB", gb);
        return String.format(Locale.ROOT, "%.2f TB", gb / 1024d);
    }

    /** The reply is one line — never let a message break the wire format. */
    @NonNull
    private static String oneLine(@NonNull String s) {
        return s.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
