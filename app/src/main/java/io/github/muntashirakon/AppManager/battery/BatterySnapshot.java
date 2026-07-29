// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.battery;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fork: one reading of the platform's per-uid battery counters.
 *
 * <p>Everything here is <b>cumulative since the last full charge</b> — the
 * platform zeroes these when the battery reaches full, which is exactly why a
 * snapshot alone is useless for history and why {@link BatterySampler} stores
 * <i>differences</i> between consecutive snapshots instead.
 *
 * <p>{@link #startClock} is the reset marker: {@code dumpsys} prints it as
 * "Start clock time", and it changes on every reset. Comparing it against the
 * previous snapshot is the only reliable way to notice that the counters went
 * back to zero underneath us — {@link #timeOnBattery} moving backwards is a
 * second, weaker signal (it also moves backwards on a reboot).
 */
public class BatterySnapshot {
    /** Device-level pseudo-uid, so per-device figures share one table. */
    public static final int UID_DEVICE = -1;

    /** "Start clock time" verbatim, e.g. {@code 2026-07-29-06-59-38}. */
    @Nullable
    public String startClock;
    /** Wall-clock time this snapshot was taken (epoch ms). */
    public long takenAt;
    /** "Time on battery" — realtime ms since the counters were reset. */
    public long timeOnBattery;
    public long screenOnMs;
    /** Deep Doze. Absent from the dump entirely when it never engaged. */
    public long deepIdleMs;
    public long lightIdleMs;
    /** Battery level percentage at the time of reading, or -1. */
    public int batteryLevel = -1;
    public int voltageMv;
    public boolean charging;

    public final Map<Integer, UidCounters> uids = new HashMap<>();

    /**
     * Power-model sanity, read from the dump's own {@code Estimated power use}
     * header. No phone has a battery under 100 mAh, so a {@code Capacity} below
     * that means the device's {@code power_profile.xml} is stubbed and every
     * mAh figure derived from it is fiction. Measured on the Mate XT: 5.00.
     */
    public double profileCapacityMah;
    public double computedDrainMah;

    /** True when the platform's own mAh estimates are worth showing at all. */
    public boolean isPowerModelUsable() {
        return profileCapacityMah >= 100;
    }

    /** The per-uid counters this fork tracks. All are monotonic within a charge cycle. */
    public static class UidCounters {
        public long wifiBytes;
        public long wifiPackets;
        public long mobileBytes;
        public long mobilePackets;
        public long radioActiveMs;
        public long wakelockMs;
        public long wakeupCount;
        public long cpuMs;
        public long fgServiceMs;
        public long sensorMs;
        /** Total estimated drain in mAh, when the device's power model is usable. */
        public double powerMah;
        /**
         * Everything else the dump offered: per-component mAh
         * ({@code mah.cpu}, {@code mah.wifi}, …) plus whatever extra durations
         * and counts this platform prints. Kept open-ended on purpose — the
         * fork runs on several phones and each prints a different subset, so a
         * fixed column list would silently drop what a newer device knows.
         */
        public final Map<String, Double> extras = new HashMap<>();

        void addExtra(@NonNull String key, double value) {
            Double old = extras.get(key);
            extras.put(key, old == null ? value : old + value);
        }

        /**
         * Field-wise {@code this - other}, clamped at zero.
         *
         * <p>The clamp matters: individual counters can shrink without a full
         * reset (a uid's process dying drops some of them), and a negative
         * delta would otherwise poison the aggregate.
         */
        @NonNull
        public UidCounters minus(@Nullable UidCounters other) {
            UidCounters d = new UidCounters();
            if (other == null) {
                d.wifiBytes = wifiBytes;
                d.wifiPackets = wifiPackets;
                d.mobileBytes = mobileBytes;
                d.mobilePackets = mobilePackets;
                d.radioActiveMs = radioActiveMs;
                d.wakelockMs = wakelockMs;
                d.wakeupCount = wakeupCount;
                d.cpuMs = cpuMs;
                d.fgServiceMs = fgServiceMs;
                d.sensorMs = sensorMs;
                d.powerMah = powerMah;
                d.extras.putAll(extras);
                return d;
            }
            d.wifiBytes = Math.max(0, wifiBytes - other.wifiBytes);
            d.wifiPackets = Math.max(0, wifiPackets - other.wifiPackets);
            d.mobileBytes = Math.max(0, mobileBytes - other.mobileBytes);
            d.mobilePackets = Math.max(0, mobilePackets - other.mobilePackets);
            d.radioActiveMs = Math.max(0, radioActiveMs - other.radioActiveMs);
            d.wakelockMs = Math.max(0, wakelockMs - other.wakelockMs);
            d.wakeupCount = Math.max(0, wakeupCount - other.wakeupCount);
            d.cpuMs = Math.max(0, cpuMs - other.cpuMs);
            d.fgServiceMs = Math.max(0, fgServiceMs - other.fgServiceMs);
            d.sensorMs = Math.max(0, sensorMs - other.sensorMs);
            d.powerMah = Math.max(0, powerMah - other.powerMah);
            for (Map.Entry<String, Double> e : extras.entrySet()) {
                Double before = other.extras.get(e.getKey());
                double delta = before == null ? e.getValue() : e.getValue() - before;
                if (delta > 0) d.extras.put(e.getKey(), delta);
            }
            return d;
        }

        public boolean isEmpty() {
            return wifiBytes == 0 && wifiPackets == 0 && mobileBytes == 0 && mobilePackets == 0
                    && radioActiveMs == 0 && wakelockMs == 0 && wakeupCount == 0 && cpuMs == 0
                    && fgServiceMs == 0 && sensorMs == 0 && powerMah == 0 && extras.isEmpty();
        }

        @NonNull
        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("wb", wifiBytes);
            o.put("wp", wifiPackets);
            o.put("mb", mobileBytes);
            o.put("mp", mobilePackets);
            o.put("ra", radioActiveMs);
            o.put("wl", wakelockMs);
            o.put("wu", wakeupCount);
            o.put("cp", cpuMs);
            o.put("fs", fgServiceMs);
            o.put("se", sensorMs);
            o.put("ah", powerMah);
            if (!extras.isEmpty()) {
                JSONObject x = new JSONObject();
                for (Map.Entry<String, Double> e : extras.entrySet()) x.put(e.getKey(), e.getValue());
                o.put("xt", x);
            }
            return o;
        }

        @NonNull
        static UidCounters fromJson(@NonNull JSONObject o) {
            UidCounters c = new UidCounters();
            c.wifiBytes = o.optLong("wb");
            c.wifiPackets = o.optLong("wp");
            c.mobileBytes = o.optLong("mb");
            c.mobilePackets = o.optLong("mp");
            c.radioActiveMs = o.optLong("ra");
            c.wakelockMs = o.optLong("wl");
            c.wakeupCount = o.optLong("wu");
            c.cpuMs = o.optLong("cp");
            c.fgServiceMs = o.optLong("fs");
            c.sensorMs = o.optLong("se");
            c.powerMah = o.optDouble("ah", 0);
            JSONObject x = o.optJSONObject("xt");
            if (x != null) {
                for (java.util.Iterator<String> it = x.keys(); it.hasNext(); ) {
                    String k = it.next();
                    c.extras.put(k, x.optDouble(k, 0));
                }
            }
            return c;
        }
    }

    /**
     * True when {@code this} continues the same charge cycle as {@code prev},
     * i.e. the counters were not reset in between and a difference is meaningful.
     */
    public boolean continues(@Nullable BatterySnapshot prev) {
        if (prev == null || prev.startClock == null || startClock == null) return false;
        if (!startClock.equals(prev.startClock)) return false;
        // A reboot restarts realtime without necessarily changing the start clock.
        return timeOnBattery >= prev.timeOnBattery;
    }

    @NonNull
    public String toJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("startClock", startClock == null ? JSONObject.NULL : startClock);
        root.put("takenAt", takenAt);
        root.put("timeOnBattery", timeOnBattery);
        root.put("screenOnMs", screenOnMs);
        root.put("deepIdleMs", deepIdleMs);
        root.put("lightIdleMs", lightIdleMs);
        root.put("batteryLevel", batteryLevel);
        root.put("profileCapacityMah", profileCapacityMah);
        root.put("computedDrainMah", computedDrainMah);
        JSONObject u = new JSONObject();
        for (Map.Entry<Integer, UidCounters> e : uids.entrySet()) {
            u.put(String.valueOf(e.getKey()), e.getValue().toJson());
        }
        root.put("uids", u);
        return root.toString();
    }

    @Nullable
    public static BatterySnapshot fromJson(@Nullable String json) {
        if (json == null) return null;
        try {
            JSONObject root = new JSONObject(json);
            BatterySnapshot s = new BatterySnapshot();
            s.startClock = root.isNull("startClock") ? null : root.getString("startClock");
            s.takenAt = root.optLong("takenAt");
            s.timeOnBattery = root.optLong("timeOnBattery");
            s.screenOnMs = root.optLong("screenOnMs");
            s.deepIdleMs = root.optLong("deepIdleMs");
            s.lightIdleMs = root.optLong("lightIdleMs");
            s.batteryLevel = root.optInt("batteryLevel", -1);
            s.profileCapacityMah = root.optDouble("profileCapacityMah", 0);
            s.computedDrainMah = root.optDouble("computedDrainMah", 0);
            JSONObject u = root.optJSONObject("uids");
            if (u != null) {
                List<String> keys = new ArrayList<>();
                for (java.util.Iterator<String> it = u.keys(); it.hasNext(); ) keys.add(it.next());
                for (String k : keys) {
                    JSONObject o = u.optJSONObject(k);
                    if (o == null) continue;
                    try {
                        s.uids.put(Integer.parseInt(k), UidCounters.fromJson(o));
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
            return s;
        } catch (JSONException e) {
            return null;
        }
    }
}
