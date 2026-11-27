// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.appstartup;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.appdata.AppDataContract;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;

/**
 * Fork (白い熊, 2026-11-07): ask 白い熊 自由作業盤 through the {@link AppLaunchContract} to open
 * EMUI's 起動マネージャー at one app, by a provider call ({@code run_task}). See
 * {@link AppLaunchContract} for why 応用管理 cannot open the screen itself.
 *
 * <p>Fire-and-forget by design — the real confirmation is the screen appearing. The only things
 * worth saying are a failure the caller can see before anything happens (自由作業盤 absent) and a
 * failure 自由作業盤 reports back (its task missing, or the caller not yet trusted); the success
 * path and a silent EMUI are left to the screen itself, so a lost reply never looks like a fault.
 */
public final class AppLaunchDelegate {
    private AppLaunchDelegate() {
    }

    private static final String TAG = AppLaunchDelegate.class.getSimpleName();

    public static void open(@NonNull Context context, @NonNull String packageName,
                            @Nullable CharSequence label) {
        Context app = context.getApplicationContext();
        if (!isJiyuPresent(app)) {
            UIUtils.displayLongToast(R.string.app_launch_jiyu_missing);
            return;
        }
        String target = label != null ? label.toString() : packageName;

        // Fork (白い熊, +073): bring the Apps & services page to the FRONT ourselves, just before the
        // request. We are the foreground app when 白い熊 taps the pill, so our start comes forward;
        // 自由作業盤's own start is a background start that EMUI opened
        // BEHIND us — and with Settings behind us, the task's AccessibilityService searched every
        // on-screen window for the label 起動マネージャー and pressed OUR own same-named pill, which was
        // the phantom "second send". Best-effort: on any refusal we still send the request, and the
        // task opens the page itself as a fallback. The BroadcastOptions BAL-token alternative is not
        // open to us — setBackgroundActivityStartsAllowed is a @SystemApi needing
        // START_ACTIVITIES_FROM_BACKGROUND, which 応用管理 does not hold.
        try {
            context.startActivity(new Intent()
                    .setClassName("com.android.settings",
                            "com.android.settings.Settings$AppAndNotificationDashboardActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignore) {
            // No such activity on this build, or a start refusal — 自由作業盤 opens the page itself.
        }

        UIUtils.displayShortToast(R.string.app_launch_opening, target);
        // The provider call is synchronous and can take the whole 6 s 自由作業盤 waits for the run,
        // so it never rides the main thread.
        ThreadUtils.postOnBackgroundThread(() -> runTask(app, packageName, target));
    }

    /**
     * Fork (白い熊, 2026-11-24, audit V-06): ask through 自由作業盤's provider, where the system names
     * the caller. This is the only path (白い熊, 2026-11-27, +130): the older broadcast, which
     * proved identity with a PendingIntent, was closed on the 自由作業盤 side in its +024 and is
     * gone here too. A failure is reported and never retried — the run may already have started.
     */
    @WorkerThread
    private static void runTask(@NonNull Context app, @NonNull String packageName,
                                @NonNull String target) {
        Bundle extras = new Bundle();
        extras.putString(AppLaunchContract.CALL_TASK, AppLaunchContract.TASK_OPEN_APP_LAUNCH);
        extras.putString(AppLaunchContract.CALL_PROJECT, AppLaunchContract.PROJECT_APP_LAUNCH);
        extras.putString(AppLaunchContract.CALL_TARGET_PACKAGE, packageName);
        extras.putString(AppLaunchContract.CALL_TARGET_LABEL, target);
        Uri uri = Uri.parse("content://"
                + AppDataContract.authorityFor(AppLaunchContract.JIYU_PACKAGE));
        String result;
        try {
            Bundle out = app.getContentResolver().call(uri, AppLaunchContract.METHOD_RUN_TASK,
                    null, extras);
            // A provider that does not implement call() at all answers null: older still.
            result = out != null ? out.getString(AppLaunchContract.CALL_RESULT) : null;
        } catch (IllegalArgumentException e) {
            // "Unknown authority": this 自由作業盤 has no data door at all.
            result = null;
        } catch (Throwable th) {
            Log.w(TAG, "run_task failed", th);
            String why = th.getMessage() != null ? th.getMessage() : th.getClass().getSimpleName();
            ThreadUtils.postOnMainThread(() ->
                    UIUtils.displayLongToast(R.string.app_launch_error, why));
            return;
        }
        if (result == null || result.startsWith(AppLaunchContract.CALL_UNKNOWN_METHOD)) {
            // A 自由作業盤 older than its +023. There is no other way in any more: say so.
            ThreadUtils.postOnMainThread(() -> UIUtils.displayLongToast(R.string.app_launch_jiyu_too_old));
            return;
        }
        if (result.startsWith("ERROR:")) {
            String why = result.substring("ERROR:".length()).trim();
            ThreadUtils.postOnMainThread(() ->
                    UIUtils.displayLongToast(R.string.app_launch_error, why));
        }
        // OK:… is silent — the confirmation is the screen appearing.
    }

    private static boolean isJiyuPresent(@NonNull Context app) {
        try {
            app.getPackageManager().getApplicationInfo(AppLaunchContract.JIYU_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
