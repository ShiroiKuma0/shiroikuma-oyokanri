// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.battery;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.PendingIntentCompat;

import java.util.ArrayList;
import java.util.List;

import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.db.AppsDb;
import io.github.muntashirakon.AppManager.db.dao.BatterySampleDao;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.utils.NotificationUtils;

/**
 * Fork: tells you an app went rogue <b>while you were not looking</b>.
 *
 * <p>The whole point of sampling in the background is that the interesting
 * drain happens when the phone is in a pocket. A screen you have to remember to
 * open cannot report that, so the sampler evaluates each fresh bucket against a
 * few rate thresholds and posts a notification that opens straight into the
 * offending app's control panel.
 *
 * <p>Judged over a <b>trailing hour</b>, not a single bucket: one 15-minute
 * burst while you were actually using the app is not a problem, and alerting on
 * it would train you to ignore the alerts. Off by default.
 */
public final class BatteryAlerts {
    public static final String TAG = BatteryAlerts.class.getSimpleName();
    private static final String CHANNEL_ID = BuildConfig.APPLICATION_ID + ".channel.BATTERY_ALERT";

    private BatteryAlerts() {}

    /** Evaluates the trailing hour and notifies about anything newly over the line. */
    @WorkerThread
    public static void evaluate(@NonNull Context context) {
        Context app = context.getApplicationContext();
        if (!BatteryPrefs.areAlertsEnabled(app)) return;
        long now = System.currentTimeMillis();
        long from = now - BatteryPrefs.ALERT_WINDOW_MS;
        List<BatterySampleDao.BatteryAggregate> rows;
        try {
            rows = AppsDb.getInstance().batterySampleDao().aggregateRange(from, now);
        } catch (Throwable th) {
            Log.e(TAG, "Alert sweep failed", th);
            return;
        }
        int packetLimit = BatteryPrefs.getAlertPacketsPerSec(app);
        int wakelockLimit = BatteryPrefs.getAlertWakelockPercent(app);
        int cpuLimit = BatteryPrefs.getAlertCpuPercent(app);

        for (BatterySampleDao.BatteryAggregate agg : rows) {
            // Require most of the hour to be covered, or a single bucket right
            // after a long gap would be judged as if it were the whole window.
            if (agg.duration < BatteryPrefs.ALERT_WINDOW_MS / 2) continue;
            if (BatteryPrefs.isIgnored(app, agg.packageName)) continue;
            if (agg.packageName != null && BuildConfig.APPLICATION_ID.equals(agg.packageName)) continue;
            if (now - BatteryPrefs.getAlertLastFired(app, agg.uid) < BatteryPrefs.ALERT_COOLDOWN_MS) continue;

            List<String> reasons = new ArrayList<>();
            double seconds = agg.duration / 1000d;
            double packetsPerSec = seconds > 0 ? agg.totalPackets() / seconds : 0;
            double wakelockPct = 100d * agg.wakelockMs / agg.duration;
            double cpuPct = 100d * agg.cpuMs / agg.duration;
            if (packetLimit > 0 && packetsPerSec >= packetLimit) {
                reasons.add(app.getString(R.string.battery_alert_reason_packets, Math.round(packetsPerSec)));
            }
            if (wakelockLimit > 0 && wakelockPct >= wakelockLimit) {
                reasons.add(app.getString(R.string.battery_alert_reason_wakelock, wakelockPct));
            }
            if (cpuLimit > 0 && cpuPct >= cpuLimit) {
                reasons.add(app.getString(R.string.battery_alert_reason_cpu, cpuPct));
            }
            if (reasons.isEmpty()) continue;

            notify(app, agg, android.text.TextUtils.join(" · ", reasons));
            BatteryPrefs.setAlertLastFired(app, agg.uid, now);
        }
    }

    private static void notify(@NonNull Context context,
                               @NonNull BatterySampleDao.BatteryAggregate agg,
                               @NonNull String reason) {
        CharSequence label = agg.packageName != null ? agg.packageName : ("uid " + agg.uid);
        try {
            if (agg.packageName != null) {
                ApplicationInfo info = context.getPackageManager().getApplicationInfo(agg.packageName,
                        PackageManagerCompat.MATCH_UNINSTALLED_PACKAGES);
                label = info.loadLabel(context.getPackageManager());
            }
        } catch (Throwable ignore) {
        }
        // Tapping the alert must land where you can act on it, not on a list you
        // then have to search.
        Intent intent = BatteryAppDetailActivity.getIntent(context, agg.uid, agg.packageName,
                (int) (BatteryPrefs.ALERT_WINDOW_MS / 3_600_000L));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntentCompat.getActivity(context, agg.uid, intent,
                PendingIntent.FLAG_UPDATE_CURRENT, false);

        NotificationManagerCompat manager = NotificationUtils.getNewNotificationManager(context,
                CHANNEL_ID, context.getString(R.string.battery_alert_channel),
                NotificationManagerCompat.IMPORTANCE_DEFAULT);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_battery_history)
                .setContentTitle(context.getString(R.string.battery_alert_title, label))
                .setContentText(reason)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(reason))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pending);
        try {
            // POST_NOTIFICATIONS may be absent; notify() would throw rather than
            // no-op, and a background sweep must never take the job down.
            manager.notify(CHANNEL_ID, agg.uid, builder.build());
        } catch (Throwable th) {
            Log.w(TAG, "Could not post battery alert", th);
        }
    }

}
