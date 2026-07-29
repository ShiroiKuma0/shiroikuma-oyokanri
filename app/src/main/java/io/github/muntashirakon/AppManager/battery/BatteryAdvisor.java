// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.battery;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.db.dao.BatterySampleDao;

/**
 * Fork: turns "this app is high in the ranking" into "here is the lever that
 * addresses <em>why</em>".
 *
 * <p>A ranked list on its own makes you guess which control to reach for, and
 * the wrong guess is expensive: freezing a map app because it used GPS is a
 * blunt answer to a narrow problem. So each suggestion is derived from the
 * counter that actually dominates this app's drain — packets point at the
 * network lever, held wakelocks at background execution, sensor time at
 * location, and so on.
 *
 * <p>Thresholds are <b>rates</b>, not totals: an app measured over four minutes
 * and one measured over four days must be judged the same way.
 */
public final class BatteryAdvisor {
    private BatteryAdvisor() {}

    // Action ids — also the switch keys in BatteryAppDetailActivity.
    public static final String ACTION_NETWORK = "network";
    public static final String ACTION_RUN_ANY_BACKGROUND = "run_any_background";
    public static final String ACTION_WAKE_LOCK = "wake_lock";
    public static final String ACTION_START_FOREGROUND = "start_foreground";
    public static final String ACTION_LOCATION = "location";
    public static final String ACTION_BATTERY_EXEMPTION = "battery_exemption";
    public static final String ACTION_FREEZE = "freeze";

    public static final int SEVERITY_NOTE = 0;
    public static final int SEVERITY_WARN = 1;
    public static final int SEVERITY_HIGH = 2;

    /** One recommended lever, with the measurement that justifies it. */
    public static class Suggestion {
        @NonNull
        public final String actionId;
        @StringRes
        public final int titleRes;
        @NonNull
        public final String reason;
        public final int severity;
        final long weight;

        Suggestion(@NonNull String actionId, @StringRes int titleRes, @NonNull String reason,
                   int severity, long weight) {
            this.actionId = actionId;
            this.titleRes = titleRes;
            this.reason = reason;
            this.severity = severity;
            this.weight = weight;
        }
    }

    /**
     * @param exemptFromDoze whether the app currently holds a battery-optimisation
     *                       exemption — an exemption on a heavy app is worth
     *                       flagging even when no single counter dominates.
     */
    @NonNull
    public static List<Suggestion> advise(@NonNull android.content.Context context,
                                          @NonNull BatterySampleDao.BatteryAggregate agg,
                                          boolean exemptFromDoze) {
        List<Suggestion> out = new ArrayList<>();
        double hours = agg.duration / 3_600_000d;
        if (hours <= 0) return out;

        double packetsPerSec = agg.totalPackets() / (agg.duration / 1000d);
        double wakelockPerHour = agg.wakelockMs / hours;
        double cpuPerHour = agg.cpuMs / hours;
        double sensorPerHour = agg.sensorMs / hours;
        double radioPerHour = agg.radioActiveMs / hours;
        double fgServicePerHour = agg.fgServiceMs / hours;

        // Network: a steady packet rate is the single most effective way to keep
        // a phone out of deep Doze, so this threshold is deliberately low.
        if (packetsPerSec >= 1) {
            int severity = packetsPerSec >= 50 ? SEVERITY_HIGH
                    : packetsPerSec >= 10 ? SEVERITY_WARN : SEVERITY_NOTE;
            out.add(new Suggestion(ACTION_NETWORK, R.string.battery_advice_network,
                    context.getString(R.string.battery_advice_network_reason, Math.round(packetsPerSec)),
                    severity, (long) (packetsPerSec * 1000)));
        } else if (radioPerHour >= 30_000) {
            out.add(new Suggestion(ACTION_NETWORK, R.string.battery_advice_network,
                    context.getString(R.string.battery_advice_radio_reason,
                            BatteryUsageAdapter.formatDuration(Math.round(radioPerHour))),
                    SEVERITY_WARN, (long) radioPerHour));
        }

        // Held wakelocks: the app is explicitly preventing sleep.
        if (wakelockPerHour >= 30_000) {
            int severity = wakelockPerHour >= 300_000 ? SEVERITY_HIGH : SEVERITY_WARN;
            out.add(new Suggestion(ACTION_WAKE_LOCK, R.string.battery_advice_wakelock,
                    context.getString(R.string.battery_advice_wakelock_reason,
                            BatteryUsageAdapter.formatDuration(Math.round(wakelockPerHour))),
                    severity, (long) wakelockPerHour * 2));
        }

        // Sustained background CPU.
        if (cpuPerHour >= 30_000) {
            int severity = cpuPerHour >= 300_000 ? SEVERITY_HIGH : SEVERITY_WARN;
            out.add(new Suggestion(ACTION_RUN_ANY_BACKGROUND, R.string.battery_advice_background,
                    context.getString(R.string.battery_advice_cpu_reason,
                            100 * cpuPerHour / 3_600_000d),
                    severity, (long) (cpuPerHour * 1.5)));
        }

        // Sensors — for a map or fitness app this is usually GPS, and location
        // is a far better-targeted lever than freezing the whole app.
        if (sensorPerHour >= 20_000) {
            int severity = sensorPerHour >= 600_000 ? SEVERITY_HIGH : SEVERITY_WARN;
            out.add(new Suggestion(ACTION_LOCATION, R.string.battery_advice_location,
                    context.getString(R.string.battery_advice_sensor_reason,
                            BatteryUsageAdapter.formatDuration(Math.round(sensorPerHour))),
                    severity, (long) sensorPerHour));
        }

        // A long-running foreground service is what lets an app ignore Doze.
        if (fgServicePerHour >= 120_000) {
            out.add(new Suggestion(ACTION_START_FOREGROUND, R.string.battery_advice_foreground,
                    context.getString(R.string.battery_advice_foreground_reason,
                            BatteryUsageAdapter.formatDuration(Math.round(fgServicePerHour))),
                    SEVERITY_WARN, (long) fgServicePerHour));
        }

        // An exemption is only worth mentioning on an app that is actually busy.
        if (exemptFromDoze && !out.isEmpty()) {
            out.add(new Suggestion(ACTION_BATTERY_EXEMPTION, R.string.battery_advice_exemption,
                    context.getString(R.string.battery_advice_exemption_reason),
                    SEVERITY_WARN, Long.MAX_VALUE / 4));
        }

        // Freezing is the blunt instrument and is offered last, deliberately —
        // only once something else has already shown the app is a real problem.
        boolean severe = false;
        for (Suggestion s : out) {
            if (s.severity == SEVERITY_HIGH) severe = true;
        }
        if (severe) {
            out.add(new Suggestion(ACTION_FREEZE, R.string.battery_advice_freeze,
                    context.getString(R.string.battery_advice_freeze_reason),
                    SEVERITY_NOTE, -1));
        }

        Collections.sort(out, (a, b) -> Long.compare(b.weight, a.weight));
        return out;
    }
}
