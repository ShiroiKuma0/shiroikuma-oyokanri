// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.db.dao;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

import io.github.muntashirakon.AppManager.db.entity.BatterySample;

/**
 * Fork: storage for the battery-history sampler. Buckets are deltas, so every
 * query over a window is a plain SUM — no reset handling at read time.
 */
@Dao
public interface BatterySampleDao {
    @Insert
    void insert(List<BatterySample> samples);

    /** Per-uid totals over a window, heaviest first is left to the caller. */
    @Query("SELECT uid, package_name AS packageName, SUM(duration) AS duration,"
            + " SUM(wifi_bytes) AS wifiBytes, SUM(wifi_packets) AS wifiPackets,"
            + " SUM(mobile_bytes) AS mobileBytes, SUM(mobile_packets) AS mobilePackets,"
            + " SUM(radio_active_ms) AS radioActiveMs, SUM(wakelock_ms) AS wakelockMs,"
            + " SUM(wakeup_count) AS wakeupCount, SUM(cpu_ms) AS cpuMs,"
            + " SUM(fg_service_ms) AS fgServiceMs, SUM(sensor_ms) AS sensorMs,"
            + " SUM(power_mah) AS powerMah, MAX(power_model_usable) AS powerModelUsable"
            + " FROM battery_sample WHERE ts > :since AND uid >= 0"
            + " GROUP BY uid HAVING SUM(wifi_packets) + SUM(mobile_packets) + SUM(wakelock_ms)"
            + " + SUM(cpu_ms) + SUM(radio_active_ms) + SUM(sensor_ms) + SUM(fg_service_ms)"
            + " + SUM(power_mah) > 0")
    List<BatteryAggregate> aggregate(long since);

    /**
     * As {@link #aggregate(long)} but counting only buckets in which the screen
     * was never on.
     *
     * <p>Drain while you are using the phone is mostly the screen; the number
     * that matters is what happens in your pocket. A bucket's screen state
     * lives on the device row, and every row written in one sampling pass
     * shares a timestamp, so the device row for the same {@code ts} is the
     * authority for all of them.
     */
    @Query("SELECT uid, package_name AS packageName, SUM(duration) AS duration,"
            + " SUM(wifi_bytes) AS wifiBytes, SUM(wifi_packets) AS wifiPackets,"
            + " SUM(mobile_bytes) AS mobileBytes, SUM(mobile_packets) AS mobilePackets,"
            + " SUM(radio_active_ms) AS radioActiveMs, SUM(wakelock_ms) AS wakelockMs,"
            + " SUM(wakeup_count) AS wakeupCount, SUM(cpu_ms) AS cpuMs,"
            + " SUM(fg_service_ms) AS fgServiceMs, SUM(sensor_ms) AS sensorMs,"
            + " SUM(power_mah) AS powerMah, MAX(power_model_usable) AS powerModelUsable"
            + " FROM battery_sample WHERE ts > :since AND uid >= 0"
            + " AND ts IN (SELECT ts FROM battery_sample WHERE uid = -1 AND screen_on_ms = 0)"
            + " GROUP BY uid HAVING SUM(wifi_packets) + SUM(mobile_packets) + SUM(wakelock_ms)"
            + " + SUM(cpu_ms) + SUM(radio_active_ms) + SUM(sensor_ms) + SUM(fg_service_ms)"
            + " + SUM(power_mah) > 0")
    List<BatteryAggregate> aggregateScreenOff(long since);

    /**
     * One uid over an explicit range — the before/after comparison. Returns a
     * row of zeroes rather than nothing when the window is empty, so the caller
     * can tell "no activity" from "no data" by checking {@code duration}.
     */
    @Query("SELECT uid, package_name AS packageName, SUM(duration) AS duration,"
            + " SUM(wifi_bytes) AS wifiBytes, SUM(wifi_packets) AS wifiPackets,"
            + " SUM(mobile_bytes) AS mobileBytes, SUM(mobile_packets) AS mobilePackets,"
            + " SUM(radio_active_ms) AS radioActiveMs, SUM(wakelock_ms) AS wakelockMs,"
            + " SUM(wakeup_count) AS wakeupCount, SUM(cpu_ms) AS cpuMs,"
            + " SUM(fg_service_ms) AS fgServiceMs, SUM(sensor_ms) AS sensorMs,"
            + " SUM(power_mah) AS powerMah, MAX(power_model_usable) AS powerModelUsable"
            + " FROM battery_sample WHERE uid = :uid AND ts > :from AND ts <= :to")
    @Nullable
    BatteryAggregate aggregateForUidRange(int uid, long from, long to);

    /** Every uid active in a range — the alert sweep. */
    @Query("SELECT uid, package_name AS packageName, SUM(duration) AS duration,"
            + " SUM(wifi_bytes) AS wifiBytes, SUM(wifi_packets) AS wifiPackets,"
            + " SUM(mobile_bytes) AS mobileBytes, SUM(mobile_packets) AS mobilePackets,"
            + " SUM(radio_active_ms) AS radioActiveMs, SUM(wakelock_ms) AS wakelockMs,"
            + " SUM(wakeup_count) AS wakeupCount, SUM(cpu_ms) AS cpuMs,"
            + " SUM(fg_service_ms) AS fgServiceMs, SUM(sensor_ms) AS sensorMs,"
            + " SUM(power_mah) AS powerMah, MAX(power_model_usable) AS powerModelUsable"
            + " FROM battery_sample WHERE ts > :from AND ts <= :to AND uid >= 0 GROUP BY uid")
    List<BatteryAggregate> aggregateRange(long from, long to);

    /** Raw extras blobs for one uid over a window — summed in Java, not SQL. */
    @Query("SELECT extras FROM battery_sample WHERE uid = :uid AND ts > :since AND extras IS NOT NULL")
    List<String> extrasForUid(int uid, long since);

    /** True when any bucket in the window came from a device with a real power profile. */
    @Query("SELECT MAX(power_model_usable) FROM battery_sample WHERE ts > :since")
    @Nullable
    Integer powerModelUsable(long since);

    /** The raw buckets for one uid, oldest first — the per-app timeline. */
    @Query("SELECT * FROM battery_sample WHERE uid = :uid AND ts > :since ORDER BY ts ASC")
    List<BatterySample> forUid(int uid, long since);

    /** Device-level buckets, oldest first. */
    @Query("SELECT * FROM battery_sample WHERE uid = -1 AND ts > :since ORDER BY ts ASC")
    List<BatterySample> deviceSamples(long since);

    @Query("SELECT MIN(ts) FROM battery_sample")
    @Nullable
    Long oldestTimestamp();

    @Query("SELECT COUNT(*) FROM battery_sample")
    int count();

    @Query("DELETE FROM battery_sample WHERE ts < :before")
    void prune(long before);

    @Query("DELETE FROM battery_sample")
    void clear();

    /** Projection for {@link #aggregate(long)}. */
    class BatteryAggregate {
        public int uid;
        @Nullable
        public String packageName;
        public long duration;
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
        public double powerMah;
        public boolean powerModelUsable;

        public long totalPackets() {
            return wifiPackets + mobilePackets;
        }

        public long totalBytes() {
            return wifiBytes + mobileBytes;
        }

        /**
         * A deliberately crude ranking heuristic, in "milliseconds of implied
         * wakefulness". It is <b>not</b> mAh and must never be labelled as
         * such: this device reports {@code Capacity: 5.00} and
         * {@code Computed drain: 0}, so the platform's own mAh estimate is
         * unusable here and a number pretending otherwise would be a lie.
         *
         * <p>Weights encode what actually keeps a phone awake: a held wakelock
         * and radio-active time cost their full duration, CPU somewhat more per
         * ms, and each packet is charged a couple of ms because a steady packet
         * rate is what prevents deep Doze from ever engaging.
         */
        public long impactScore() {
            return wakelockMs
                    + radioActiveMs
                    + (long) (cpuMs * 1.5)
                    + (long) (sensorMs * 0.5)
                    + totalPackets() * 2
                    + (long) (fgServiceMs * 0.1);
        }
    }
}
