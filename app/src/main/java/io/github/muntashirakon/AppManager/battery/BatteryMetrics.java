// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.battery;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import io.github.muntashirakon.AppManager.R;

/**
 * Fork: display names and units for the open-ended extra counters.
 *
 * <p>The parser stores whatever a device offered, keyed by a stable string.
 * This class turns a key into something readable <b>without needing to know it
 * in advance</b>: known keys get a translated label and the right unit, and
 * anything unrecognised — a counter a future Android release starts printing —
 * still renders, as a de-underscored key with a plain number. Nothing is
 * dropped for being unfamiliar, which is the whole point of running this on
 * several different phones.
 */
public final class BatteryMetrics {
    private BatteryMetrics() {}

    private static final int UNIT_MS = 0;
    private static final int UNIT_COUNT = 1;
    private static final int UNIT_MAH = 2;

    private static final Map<String, int[]> KNOWN = new HashMap<>();

    private static void put(@NonNull String key, int labelRes, int unit) {
        KNOWN.put(key, new int[]{labelRes, unit});
    }

    static {
        put("foreground_activity_ms", R.string.battery_metric_foreground_activity, UNIT_MS);
        put("top_ms", R.string.battery_metric_top, UNIT_MS);
        put("fg_service_state_ms", R.string.battery_metric_fg_service_state, UNIT_MS);
        put("background_ms", R.string.battery_metric_background, UNIT_MS);
        put("cached_ms", R.string.battery_metric_cached, UNIT_MS);
        put("wifi_running_ms", R.string.battery_metric_wifi_running, UNIT_MS);
        put("wifi_full_lock_ms", R.string.battery_metric_wifi_lock, UNIT_MS);
        put("wifi_scan_ms", R.string.battery_metric_wifi_scan, UNIT_MS);
        put("bluetooth_scan_ms", R.string.battery_metric_bluetooth_scan, UNIT_MS);
        put("audio_ms", R.string.battery_metric_audio, UNIT_MS);
        put("video_ms", R.string.battery_metric_video, UNIT_MS);
        put("camera_ms", R.string.battery_metric_camera, UNIT_MS);
        put("flashlight_ms", R.string.battery_metric_flashlight, UNIT_MS);
        put("vibrator_ms", R.string.battery_metric_vibrator, UNIT_MS);
        put("job_ms", R.string.battery_metric_job_time, UNIT_MS);
        put("job_count", R.string.battery_metric_job_count, UNIT_COUNT);
        put("sync_ms", R.string.battery_metric_sync_time, UNIT_MS);
        put("sync_count", R.string.battery_metric_sync_count, UNIT_COUNT);
        put("radio_active_count", R.string.battery_metric_radio_count, UNIT_COUNT);
        put("profile_capacity_mah", R.string.battery_metric_profile_capacity, UNIT_MAH);
        put("computed_drain_mah", R.string.battery_metric_computed_drain, UNIT_MAH);
    }

    /** Human label for a stored key. Per-component mAh keys are derived, not listed. */
    @NonNull
    public static String label(@NonNull Context context, @NonNull String key) {
        int[] known = KNOWN.get(key);
        if (known != null) return context.getString(known[0]);
        if (key.startsWith("mah.")) {
            // "mah.mobile_radio" → "mAh · mobile radio". The component set
            // differs per Android release, so it is never enumerated here.
            return context.getString(R.string.battery_metric_mah_component,
                    key.substring(4).replace('_', ' '));
        }
        return key.replace('_', ' ');
    }

    /** Formats a value in whatever unit its key implies. */
    @NonNull
    public static String format(@NonNull Context context, @NonNull String key, double value) {
        int[] known = KNOWN.get(key);
        int unit = known != null ? known[1] : (key.startsWith("mah.") ? UNIT_MAH
                : key.endsWith("_ms") ? UNIT_MS : key.endsWith("_count") ? UNIT_COUNT : -1);
        switch (unit) {
            case UNIT_MS:
                return BatteryUsageAdapter.formatDuration(Math.round(value));
            case UNIT_COUNT:
                return String.format(Locale.getDefault(), "%.0f", value);
            case UNIT_MAH:
                return context.getString(R.string.battery_mah, value);
            default:
                return value == Math.rint(value)
                        ? String.format(Locale.getDefault(), "%.0f", value)
                        : String.format(Locale.getDefault(), "%.3f", value);
        }
    }
}
