// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.appstartup;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.utils.UIUtils;

/**
 * Fork (白い熊, 2026-11-07): fire the {@link AppLaunchContract} at 白い熊 自由作業盤 so it opens
 * EMUI's 起動マネージャー at one app. See {@link AppLaunchContract} for why 応用管理 cannot open the
 * screen itself.
 *
 * <p>Fire-and-forget by design — the real confirmation is the screen appearing. The only things
 * worth saying are a failure the caller can see before anything happens (自由作業盤 absent) and a
 * failure 自由作業盤 reports back (its task missing, or the caller not yet trusted); the success
 * path and a silent EMUI are left to the screen itself, so a lost reply never looks like a fault.
 */
public final class AppLaunchDelegate {
    private AppLaunchDelegate() {
    }

    private static final long REPLY_TIMEOUT_MS = 8_000L;

    public static void open(@NonNull Context context, @NonNull String packageName,
                            @Nullable CharSequence label) {
        Context app = context.getApplicationContext();
        if (!isJiyuPresent(app)) {
            UIUtils.displayLongToast(R.string.app_launch_jiyu_missing);
            return;
        }
        String requestId = UUID.randomUUID().toString();
        registerReplyReceiver(app, requestId);

        Intent intent = new Intent(AppLaunchContract.ACTION_RUN_TASK_WITH_TARGET);
        intent.setPackage(AppLaunchContract.JIYU_PACKAGE);
        // Without FLAG_INCLUDE_STOPPED_PACKAGES a backgrounded 自由作業盤 never hears us on EMUI. We
        // never stop it (the never-kill rule), but the flag is the family convention and costs
        // nothing to set.
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        intent.putExtra(AppLaunchContract.EXTRA_TASK, AppLaunchContract.TASK_OPEN_APP_LAUNCH);
        intent.putExtra(AppLaunchContract.EXTRA_PROJECT, AppLaunchContract.PROJECT_APP_LAUNCH);
        intent.putExtra(AppLaunchContract.EXTRA_TARGET_PACKAGE, packageName);
        intent.putExtra(AppLaunchContract.EXTRA_TARGET_LABEL,
                label != null ? label.toString() : packageName);
        intent.putExtra(AppLaunchContract.EXTRA_SOURCE, app.getPackageName());
        intent.putExtra(AppLaunchContract.EXTRA_REPLY_PACKAGE, app.getPackageName());
        intent.putExtra(AppLaunchContract.EXTRA_REQUEST_ID, requestId);
        // Forge-proof caller identity: a broadcast has no sender uid below API 34, so 自由作業盤
        // reads this immutable PendingIntent's creatorPackage/creatorUid (which only we can have set)
        // and runs its AutomationCallers gate on them. It is never sent by 自由作業盤 — its inner
        // intent is a harmless self-targeted reply we never fire.
        intent.putExtra(AppLaunchContract.EXTRA_IDENTITY, PendingIntent.getBroadcast(app, 0,
                new Intent(AppLaunchContract.ACTION_RESULT).setPackage(app.getPackageName()),
                PendingIntent.FLAG_IMMUTABLE));

        // Fork (白い熊, +073): bring the Apps & services page to the FRONT ourselves, just before the
        // broadcast. We are the foreground app when 白い熊 taps the pill, so our start comes forward;
        // 自由作業盤's own start, from its broadcast receiver, is a background start that EMUI opened
        // BEHIND us — and with Settings behind us, the task's AccessibilityService searched every
        // on-screen window for the label 起動マネージャー and pressed OUR own same-named pill, which was
        // the phantom "second send". Best-effort: on any refusal we still fire the broadcast, and the
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

        // Plain (non-ordered) broadcast. It was ordered through +071, on the assumption 自由作業盤's
        // gate wanted isOrderedBroadcast — it does not (it reads the ordered result only when one
        // happens to be present, and the real reply always rides a fresh APP_LAUNCH_RESULT broadcast
        // back). On EMUI an ordered send was redelivered to 自由作業盤's single receiver a second time
        // ~6 s later, right after that receiver's goAsync() finished, so one press ran the task once
        // and then tripped a phantom "task already running" on the redelivery (白い熊, +072, with the
        // 自由作業盤 session). A plain broadcast does not hold the receiver open, so there is no
        // redelivery. FLAG_INCLUDE_STOPPED_PACKAGES above still applies.
        app.sendBroadcast(intent);
        UIUtils.displayShortToast(R.string.app_launch_opening,
                label != null ? label.toString() : packageName);
    }

    private static boolean isJiyuPresent(@NonNull Context app) {
        try {
            app.getPackageManager().getApplicationInfo(AppLaunchContract.JIYU_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * A one-shot receiver for the terminal reply. It surfaces only an explicit {@code ERROR:} — a
     * failure 白い熊 needs while setting the task up; {@code OK:} and a timeout are silent, because a
     * timeout on EMUI does not mean the screen failed to open.
     */
    private static void registerReplyReceiver(@NonNull Context app, @NonNull String requestId) {
        Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] done = {false};
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (done[0]) {
                    return;
                }
                if (!requestId.equals(intent.getStringExtra(AppLaunchContract.EXTRA_REQUEST_ID))) {
                    return;
                }
                done[0] = true;
                String result = intent.getStringExtra(AppLaunchContract.EXTRA_RESULT);
                if (result != null && result.startsWith("ERROR:")) {
                    UIUtils.displayLongToast(R.string.app_launch_error,
                            result.substring("ERROR:".length()).trim());
                }
                unregister(app, this);
            }
        };
        IntentFilter filter = new IntentFilter(AppLaunchContract.ACTION_RESULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            app.registerReceiver(receiver, filter);
        }
        handler.postDelayed(() -> {
            if (done[0]) {
                return;
            }
            done[0] = true;
            unregister(app, receiver);
        }, REPLY_TIMEOUT_MS);
    }

    private static void unregister(@NonNull Context app, @NonNull BroadcastReceiver receiver) {
        try {
            app.unregisterReceiver(receiver);
        } catch (Throwable ignore) {
            // Already gone.
        }
    }
}
