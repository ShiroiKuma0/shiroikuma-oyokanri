// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.battery;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Fork: settings and sampler state for the battery-history screen.
 *
 * <p>Two prefs files on purpose. {@link #PREFS_NAME} holds <i>decisions</i> —
 * whether to sample, how often, how long to keep — and rides along in settings
 * export/import. {@link #STATE_PREF_FILE} holds the previous raw snapshot, a
 * device-local fact about this phone's counters at one instant; carrying it to
 * another phone would produce a garbage first delta, so it is listed in
 * {@code SettingsBackupManager.EXCLUDED_PREFS}.
 */
public final class BatteryPrefs {
    private BatteryPrefs() {}

    private static final String PREFS_NAME = "shiroikuma_battery";
    /** Device-local sampler state — excluded from settings export/import. */
    public static final String STATE_PREF_FILE = "shiroikuma_battery_state";

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_INTERVAL_MIN = "interval_min";
    private static final String KEY_RETENTION_DAYS = "retention_days";
    private static final String KEY_WINDOW_HOURS = "window_hours";
    private static final String KEY_SORT = "sort";
    private static final String KEY_LAST_SNAPSHOT = "last_snapshot";
    private static final String KEY_LAST_SAMPLE_AT = "last_sample_at";
    private static final String KEY_SCREEN_OFF_ONLY = "screen_off_only";
    private static final String KEY_DRAINER_WINDOW_MIN = "drainer_window_min";
    private static final String KEY_ALERTS_ENABLED = "alerts_enabled";
    private static final String KEY_ALERT_PACKETS = "alert_packets_per_sec";
    private static final String KEY_ALERT_WAKELOCK_PCT = "alert_wakelock_pct";
    private static final String KEY_ALERT_CPU_PCT = "alert_cpu_pct";
    private static final String KEY_ALERT_IGNORED = "alert_ignored";
    private static final String KEY_ALERT_LAST_FIRED = "alert_last_fired";

    /** Alert defaults — chosen so a normal messenger stays quiet. */
    public static final int DEFAULT_ALERT_PACKETS = 20;
    public static final int DEFAULT_ALERT_WAKELOCK_PCT = 25;
    public static final int DEFAULT_ALERT_CPU_PCT = 10;
    /** How long the trailing window an alert judges is, and its re-fire cooldown. */
    public static final long ALERT_WINDOW_MS = 60 * 60 * 1000L;
    public static final long ALERT_COOLDOWN_MS = 6 * 60 * 60 * 1000L;

    public static final int DEFAULT_INTERVAL_MIN = 15;
    public static final int MIN_INTERVAL_MIN = 15; // JobScheduler's own floor for periodic jobs
    public static final int MAX_INTERVAL_MIN = 120;
    public static final int DEFAULT_RETENTION_DAYS = 14;
    public static final int MIN_RETENTION_DAYS = 1;
    public static final int MAX_RETENTION_DAYS = 60;
    public static final int DEFAULT_WINDOW_HOURS = 24;

    // Sort modes for the ranked list.
    public static final int SORT_IMPACT = 0;
    public static final int SORT_PACKETS = 1;
    public static final int SORT_BYTES = 2;
    public static final int SORT_WAKELOCK = 3;
    public static final int SORT_CPU = 4;
    /** Only offered where the device's power profile is real — see BatterySnapshot. */
    public static final int SORT_MAH = 5;

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @NonNull
    private static SharedPreferences state(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(STATE_PREF_FILE, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static int getIntervalMinutes(@NonNull Context context) {
        int v = prefs(context).getInt(KEY_INTERVAL_MIN, DEFAULT_INTERVAL_MIN);
        return Math.max(MIN_INTERVAL_MIN, Math.min(MAX_INTERVAL_MIN, v));
    }

    public static void setIntervalMinutes(@NonNull Context context, int minutes) {
        prefs(context).edit().putInt(KEY_INTERVAL_MIN, minutes).apply();
    }

    public static int getRetentionDays(@NonNull Context context) {
        int v = prefs(context).getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS);
        return Math.max(MIN_RETENTION_DAYS, Math.min(MAX_RETENTION_DAYS, v));
    }

    public static void setRetentionDays(@NonNull Context context, int days) {
        prefs(context).edit().putInt(KEY_RETENTION_DAYS, days).apply();
    }

    /** Whole hours, for callers that cannot express minutes (at least 1). */
    public static int getWindowHours(@NonNull Context context) {
        return Math.max(1, getWindowMinutes(context) / 60);
    }

    public static int getSort(@NonNull Context context) {
        return prefs(context).getInt(KEY_SORT, SORT_IMPACT);
    }

    public static void setSort(@NonNull Context context, int sort) {
        prefs(context).edit().putInt(KEY_SORT, sort).apply();
    }

    public static boolean isScreenOffOnly(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_SCREEN_OFF_ONLY, false);
    }

    public static void setScreenOffOnly(@NonNull Context context, boolean screenOffOnly) {
        prefs(context).edit().putBoolean(KEY_SCREEN_OFF_ONLY, screenOffOnly).apply();
    }

    /**
     * The <b>one</b> window the battery screen works in — drainer columns and
     * per-app rows alike. They used to be separate, which meant the pill said
     * "last 6 hours" while every row underneath was quietly showing a day; two
     * numbers for one screen is a bug, not a feature.
     */
    public static int getWindowMinutes(@NonNull Context context) {
        return prefs(context).getInt(KEY_DRAINER_WINDOW_MIN,
                prefs(context).getInt(KEY_WINDOW_HOURS, DEFAULT_WINDOW_HOURS) * 60);
    }

    public static void setWindowMinutes(@NonNull Context context, int minutes) {
        prefs(context).edit().putInt(KEY_DRAINER_WINDOW_MIN, minutes).apply();
    }

    public static boolean areAlertsEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_ALERTS_ENABLED, false);
    }

    public static void setAlertsEnabled(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ALERTS_ENABLED, enabled).apply();
    }

    public static int getAlertPacketsPerSec(@NonNull Context context) {
        return prefs(context).getInt(KEY_ALERT_PACKETS, DEFAULT_ALERT_PACKETS);
    }

    public static void setAlertPacketsPerSec(@NonNull Context context, int value) {
        prefs(context).edit().putInt(KEY_ALERT_PACKETS, value).apply();
    }

    public static int getAlertWakelockPercent(@NonNull Context context) {
        return prefs(context).getInt(KEY_ALERT_WAKELOCK_PCT, DEFAULT_ALERT_WAKELOCK_PCT);
    }

    public static void setAlertWakelockPercent(@NonNull Context context, int value) {
        prefs(context).edit().putInt(KEY_ALERT_WAKELOCK_PCT, value).apply();
    }

    public static int getAlertCpuPercent(@NonNull Context context) {
        return prefs(context).getInt(KEY_ALERT_CPU_PCT, DEFAULT_ALERT_CPU_PCT);
    }

    public static void setAlertCpuPercent(@NonNull Context context, int value) {
        prefs(context).edit().putInt(KEY_ALERT_CPU_PCT, value).apply();
    }

    /** Packages you have accepted as heavy — a music player, a navigation app. */
    @NonNull
    public static Set<String> getIgnoredPackages(@NonNull Context context) {
        return new HashSet<>(prefs(context).getStringSet(KEY_ALERT_IGNORED, Collections.emptySet()));
    }

    public static void setIgnored(@NonNull Context context, @NonNull String packageName, boolean ignored) {
        Set<String> set = getIgnoredPackages(context);
        if (ignored) set.add(packageName);
        else set.remove(packageName);
        prefs(context).edit().putStringSet(KEY_ALERT_IGNORED, set).apply();
    }

    public static boolean isIgnored(@NonNull Context context, @Nullable String packageName) {
        return packageName != null && getIgnoredPackages(context).contains(packageName);
    }

    /**
     * Alert cooldown, kept in the <b>state</b> file rather than the settings
     * file: "we already warned about this uid" is a device-local fact, and
     * carrying it in an export would silently mute the alert on another phone.
     */
    public static long getAlertLastFired(@NonNull Context context, int uid) {
        return state(context).getLong(KEY_ALERT_LAST_FIRED + "." + uid, 0);
    }

    public static void setAlertLastFired(@NonNull Context context, int uid, long when) {
        state(context).edit().putLong(KEY_ALERT_LAST_FIRED + "." + uid, when).apply();
    }

    @Nullable
    public static BatterySnapshot readLastSnapshot(@NonNull Context context) {
        return BatterySnapshot.fromJson(state(context).getString(KEY_LAST_SNAPSHOT, null));
    }

    public static void writeLastSnapshot(@NonNull Context context, @NonNull BatterySnapshot snapshot) {
        try {
            state(context).edit()
                    .putString(KEY_LAST_SNAPSHOT, snapshot.toJson())
                    .putLong(KEY_LAST_SAMPLE_AT, snapshot.takenAt)
                    .apply();
        } catch (Exception ignore) {
            // A snapshot we cannot serialise simply means the next run treats
            // itself as the first one — never a crash in a background job.
        }
    }

    public static long getLastSampleAt(@NonNull Context context) {
        return state(context).getLong(KEY_LAST_SAMPLE_AT, 0);
    }

    public static void clearState(@NonNull Context context) {
        state(context).edit().remove(KEY_LAST_SNAPSHOT).remove(KEY_LAST_SAMPLE_AT).apply();
    }
}
