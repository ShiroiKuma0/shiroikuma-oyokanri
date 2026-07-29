// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.battery;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.muntashirakon.AppManager.db.AppsDb;
import io.github.muntashirakon.AppManager.db.entity.BatterySample;
import io.github.muntashirakon.AppManager.logs.Log;

/**
 * Fork: turns consecutive {@link BatterySnapshot}s into a stored timeline.
 *
 * <p>The platform keeps ten days of daily discharge <i>rates</i> and zero days
 * of per-app attribution — every counter that could name a culprit is wiped at
 * the next full charge. This class is the fix: read the counters, subtract the
 * previous reading, store the difference, forget the absolutes.
 *
 * <h3>Reset handling</h3>
 * When {@link BatterySnapshot#continues} is false the counters restarted
 * underneath us, so the current absolutes <i>are</i> the usage since that reset
 * and get written as one bucket whose duration is
 * {@link BatterySnapshot#timeOnBattery}. The alternative — dropping the bucket
 * — would blank the hours around every charge, which are precisely the hours
 * worth looking at.
 */
public final class BatterySampler {
    public static final String TAG = BatterySampler.class.getSimpleName();

    /**
     * A bucket longer than this is almost certainly a gap (device off, sampling
     * disabled, job starved) rather than real elapsed sampling. The counters in
     * it are still true, so the bucket is kept — only its duration is capped, so
     * a per-hour rate computed from it cannot silently understate the truth.
     */
    private static final long MAX_BUCKET_MS = 6 * 60 * 60 * 1000L;

    private BatterySampler() {}

    /**
     * Takes one reading and stores the delta. Safe to call from any background
     * thread; concurrent calls are serialised so two samplers cannot both diff
     * against the same previous snapshot and double-count.
     *
     * @return true when a reading was obtained (whether or not it produced rows)
     */
    @WorkerThread
    public static synchronized boolean sample(@NonNull Context context) {
        Context app = context.getApplicationContext();
        if (!BatteryPrefs.isEnabled(app)) return false;
        String dump = BatteryStatsReader.read();
        BatterySnapshot snapshot = BatteryStatsParser.parse(dump);
        if (snapshot == null) {
            // A refused or truncated dump must never be written as "all zeroes":
            // that would read back as a quiet hour that never happened.
            Log.w(TAG, "batterystats unavailable — no privileges yet?");
            return false;
        }
        snapshot.takenAt = System.currentTimeMillis();
        snapshot.batteryLevel = BatteryStatsReader.batteryLevel(app);
        BatteryStatsReader.Power power = BatteryStatsReader.power(app);
        snapshot.voltageMv = power.voltageMv;
        snapshot.charging = power.charging;

        BatterySnapshot previous = BatteryPrefs.readLastSnapshot(app);
        List<BatterySample> rows = diff(app, previous, snapshot);
        try {
            if (!rows.isEmpty()) {
                AppsDb.getInstance().batterySampleDao().insert(rows);
            }
            long cutoff = snapshot.takenAt - BatteryPrefs.getRetentionDays(app) * 86_400_000L;
            AppsDb.getInstance().batterySampleDao().prune(cutoff);
        } catch (Throwable th) {
            Log.e(TAG, "Failed to store battery samples", th);
        }
        BatteryPrefs.writeLastSnapshot(app, snapshot);
        // Fork: the whole reason for sampling in the background is that the
        // interesting drain happens while the phone is in a pocket, so the
        // check for "something went rogue" belongs here, not on a screen the
        // user has to remember to open.
        try {
            BatteryAlerts.evaluate(app);
        } catch (Throwable th) {
            Log.w(TAG, "Alert evaluation failed", th);
        }
        return true;
    }

    /**
     * Builds the delta rows. The first ever reading yields none — it only
     * establishes the baseline the next reading subtracts from.
     */
    @NonNull
    private static List<BatterySample> diff(@NonNull Context context,
                                            @Nullable BatterySnapshot previous,
                                            @NonNull BatterySnapshot current) {
        List<BatterySample> rows = new ArrayList<>();
        boolean continues = current.continues(previous);
        if (previous == null) return rows;

        long duration;
        if (continues) {
            duration = Math.max(0, current.takenAt - previous.takenAt);
        } else {
            // Counters restarted: they now measure from the reset, not from the
            // previous reading, so the window is the platform's own.
            duration = current.timeOnBattery;
        }
        duration = Math.min(duration, MAX_BUCKET_MS);
        if (duration <= 0) return rows;

        PackageManager pm = context.getPackageManager();
        for (Map.Entry<Integer, BatterySnapshot.UidCounters> entry : current.uids.entrySet()) {
            int uid = entry.getKey();
            BatterySnapshot.UidCounters base = continues ? previous.uids.get(uid) : null;
            BatterySnapshot.UidCounters delta = entry.getValue().minus(base);
            if (delta.isEmpty()) continue;
            BatterySample row = new BatterySample();
            row.ts = current.takenAt;
            row.duration = duration;
            row.uid = uid;
            row.packageName = resolvePackage(pm, uid);
            row.wifiBytes = delta.wifiBytes;
            row.wifiPackets = delta.wifiPackets;
            row.mobileBytes = delta.mobileBytes;
            row.mobilePackets = delta.mobilePackets;
            row.radioActiveMs = delta.radioActiveMs;
            row.wakelockMs = delta.wakelockMs;
            row.wakeupCount = delta.wakeupCount;
            row.cpuMs = delta.cpuMs;
            row.fgServiceMs = delta.fgServiceMs;
            row.sensorMs = delta.sensorMs;
            row.powerMah = current.isPowerModelUsable() ? delta.powerMah : 0;
            row.powerModelUsable = current.isPowerModelUsable();
            row.extras = encodeExtras(delta.extras);
            rows.add(row);
        }

        BatterySample device = new BatterySample();
        device.ts = current.takenAt;
        device.duration = duration;
        device.uid = BatterySnapshot.UID_DEVICE;
        device.screenOnMs = continues
                ? Math.max(0, current.screenOnMs - previous.screenOnMs) : current.screenOnMs;
        device.deepIdleMs = continues
                ? Math.max(0, current.deepIdleMs - previous.deepIdleMs) : current.deepIdleMs;
        device.lightIdleMs = continues
                ? Math.max(0, current.lightIdleMs - previous.lightIdleMs) : current.lightIdleMs;
        device.batteryLevel = current.batteryLevel;
        device.voltageMv = current.voltageMv;
        device.charging = current.charging;
        device.powerModelUsable = current.isPowerModelUsable();
        // The device row carries the profile figures so the UI can explain
        // *why* mAh is missing on a phone rather than just omitting it.
        Map<String, Double> deviceExtras = new HashMap<>();
        deviceExtras.put("profile_capacity_mah", current.profileCapacityMah);
        deviceExtras.put("computed_drain_mah", current.computedDrainMah);
        device.extras = encodeExtras(deviceExtras);
        rows.add(device);
        return rows;
    }

    @Nullable
    private static String encodeExtras(@NonNull Map<String, Double> extras) {
        if (extras.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, Double> e : extras.entrySet()) o.put(e.getKey(), e.getValue());
            return o.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Sums a set of stored extras blobs. Missing keys simply do not contribute,
     * which is what "available where the device offers it" has to mean when the
     * same history can span several phones.
     */
    @NonNull
    public static Map<String, Double> mergeExtras(@NonNull List<String> blobs) {
        Map<String, Double> merged = new LinkedHashMap<>();
        for (String blob : blobs) {
            if (blob == null) continue;
            try {
                JSONObject o = new JSONObject(blob);
                for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String key = it.next();
                    double value = o.optDouble(key, 0);
                    Double old = merged.get(key);
                    merged.put(key, old == null ? value : old + value);
                }
            } catch (JSONException ignore) {
            }
        }
        return merged;
    }

    /**
     * uid → a display package. A shared uid has several; the first is taken and
     * the ranking stays keyed by uid, which is what the platform attributes to
     * anyway.
     */
    @Nullable
    private static String resolvePackage(@NonNull PackageManager pm, int uid) {
        try {
            String[] packages = pm.getPackagesForUid(uid);
            if (packages != null && packages.length > 0) return packages[0];
            String name = pm.getNameForUid(uid);
            return name != null ? name : null;
        } catch (Throwable th) {
            return null;
        }
    }
}
