// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.processreaper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.diag.ForkDiag;
import io.github.muntashirakon.AppManager.types.ForegroundService;

/**
 * Fork (白い熊, 2026-11-15): the background process-start watch. During a burst
 * of failed process starts this app could not be opened to fix it, so the fix
 * has to live in a process that is already running: this foreground service
 * keeps ours alive, ticks the shared {@link ForkStartWatch} (once a minute while
 * all is well, every 10 s while starts fail), and raises a notification with a
 * button that ends the cached background processes — which needs no new process.
 * It is also what lets the diagnostics record how a burst <i>begins</i>.
 *
 * <p>Off by default (UI page → Diagnostics → Process monitor). No wake lock:
 * while the phone sleeps the ticks simply wait, so it costs nothing in Doze.
 * A sticky restart needs a new process, so if the system kills us in the middle
 * of a burst we are back only once starts work again.
 */
public class ForkWatchService extends Service {
    public static final String ACTION_FIX = "io.github.muntashirakon.AppManager.processreaper.FORK_FIX";

    private static final String CHANNEL = "shiroikuma.forkwatch";
    private static final String CHANNEL_ALERT = "shiroikuma.forkwatch.alert";
    private static final int ID = 0x464F524B;
    private static final int ID_ALERT = ID + 1;

    private HandlerThread mThread;
    private Handler mHandler;
    private int mLastStatus = -1;
    private boolean mFixing;

    /** Start or stop the service according to its switch. Safe from any thread. */
    public static void sync(@NonNull Context context) {
        Context ctx = context.getApplicationContext();
        try {
            Intent i = new Intent(ctx, ForkWatchService.class);
            if (ForkDiag.isWatchOn(ctx)) ContextCompat.startForegroundService(ctx, i);
            else ctx.stopService(i);
        } catch (Throwable ignore) {
            // Started from the background without leave to: the next app start tries again.
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                        getString(R.string.monitor_watch_channel), NotificationManager.IMPORTANCE_LOW));
                nm.createNotificationChannel(new NotificationChannel(CHANNEL_ALERT,
                        getString(R.string.monitor_watch_channel_alert), NotificationManager.IMPORTANCE_HIGH));
            }
            ForegroundService.start(this, ID, ongoing(getString(R.string.monitor_watch_ok), false),
                    ForegroundService.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } catch (Throwable t) {
            stopSelf();
            return;
        }
        ForkStartWatch.get().setBackground(true);
        mThread = new HandlerThread("fork-watch");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        mHandler.postDelayed(mTick, 5_000L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_FIX.equals(intent.getAction()) && mHandler != null) {
            mHandler.post(this::fix);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        ForkStartWatch.get().setBackground(false);
        if (mHandler != null) mHandler.removeCallbacksAndMessages(null);
        if (mThread != null) mThread.quitSafely();
        super.onDestroy();
    }

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (!ForkDiag.isWatchOn(ForkWatchService.this)) {
                stopSelf();
                return;
            }
            long delay = 60_000L;
            try {
                ForkStartWatch.Snapshot s = ForkStartWatch.get().tick(ForkWatchService.this, -1);
                if (s.status != mLastStatus) onStatus(s);
                mLastStatus = s.status;
                delay = s.active || s.status == ForkStartWatch.STATUS_FAILING ? 10_000L
                        : s.status == ForkStartWatch.STATUS_RECENT ? 30_000L : 60_000L;
            } catch (Throwable ignore) {
            }
            mHandler.postDelayed(this, delay);
        }
    };

    private void onStatus(@NonNull ForkStartWatch.Snapshot s) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        String hms = new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
        try {
            if (s.status == ForkStartWatch.STATUS_FAILING) {
                nm.notify(ID, ongoing(getString(R.string.monitor_watch_failing), !mFixing));
                if (!mFixing) nm.notify(ID_ALERT, alert(getString(R.string.monitor_watch_alert_text), true));
            } else {
                nm.notify(ID, ongoing(getString(s.status == ForkStartWatch.STATUS_RECENT
                        ? R.string.monitor_watch_recent : R.string.monitor_watch_ok), false));
                if (mLastStatus == ForkStartWatch.STATUS_FAILING && !mFixing) {
                    nm.notify(ID_ALERT, alert(getString(R.string.monitor_watch_cleared, hms), false));
                }
            }
        } catch (Throwable ignore) {
        }
    }

    /** The notification's button: end the cached background processes, then probe. */
    private void fix() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        mFixing = true;
        try {
            if (nm != null) {
                nm.notify(ID_ALERT, alert(getString(R.string.monitor_fork_working), false));
                nm.notify(ID, ongoing(getString(R.string.monitor_fork_working), false));
            }
            List<ForkFix.Target> targets = ForkFix.cachedTargets();
            ForkFix.Outcome o = ForkFix.run(this, targets, false, "notification");
            if (nm != null) {
                nm.notify(ID_ALERT, alert(getString(o.probe.ok ? R.string.monitor_watch_fix_ok
                        : R.string.monitor_watch_fix_still, o.stopped), !o.probe.ok));
                nm.notify(ID, ongoing(getString(o.probe.ok ? R.string.monitor_watch_recent
                        : R.string.monitor_watch_failing), !o.probe.ok));
            }
        } catch (Throwable ignore) {
        } finally {
            mFixing = false;
        }
    }

    @NonNull
    private PendingIntent openMonitor() {
        Intent i = new Intent(this, ProcessMonitorActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @NonNull
    private Notification ongoing(@NonNull String text, boolean withFix) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_default_notification)
                .setContentTitle(getString(R.string.monitor_watch_title))
                .setContentText(text)
                .setContentIntent(openMonitor())
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        // Fork (+101): the button also sits here while starts fail — the alert beside it is easy
        // to miss, and this is the notification that is always in the shade.
        if (withFix) b.addAction(0, getString(R.string.monitor_watch_fix), fixIntent());
        return b.build();
    }

    @NonNull
    private PendingIntent fixIntent() {
        Intent i = new Intent(this, ForkWatchService.class).setAction(ACTION_FIX);
        return PendingIntent.getService(this, 2, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @NonNull
    private Notification alert(@NonNull String text, boolean withFix) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_default_notification)
                .setContentTitle(getString(R.string.monitor_fork_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openMonitor())
                .setAutoCancel(!withFix)
                .setOnlyAlertOnce(!withFix)
                .setPriority(withFix ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_DEFAULT);
        if (withFix) {
            b.addAction(0, getString(R.string.monitor_watch_fix), fixIntent());
        }
        return b.build();
    }
}
