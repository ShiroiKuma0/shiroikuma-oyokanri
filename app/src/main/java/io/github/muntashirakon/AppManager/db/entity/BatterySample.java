// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.db.entity;

import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Fork: one bucket of per-uid battery counters — a <b>difference</b> between
 * two consecutive readings, never an absolute.
 *
 * <p>The platform's own counters zero at every full charge, so absolutes cannot
 * be accumulated across days. Storing deltas makes the series survive resets:
 * the reset simply ends one bucket and starts the next.
 *
 * <p>Rows with {@code uid == } {@link io.github.muntashirakon.AppManager.battery.BatterySnapshot#UID_DEVICE}
 * carry the device-level fields ({@link #screenOnMs}, {@link #deepIdleMs},
 * {@link #lightIdleMs}, {@link #batteryLevel}) and leave the per-uid counters
 * at zero. One table rather than two, because every query wants them aligned on
 * the same bucket boundaries anyway.
 */
@Entity(tableName = "battery_sample", indices = {@Index("ts"), @Index("uid")})
public class BatterySample {
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    public long id;

    /** Bucket end, epoch ms. */
    @ColumnInfo(name = "ts")
    public long ts;

    /** Bucket length in ms — the wall-clock gap since the previous reading. */
    @ColumnInfo(name = "duration")
    public long duration;

    @ColumnInfo(name = "uid")
    public int uid;

    @ColumnInfo(name = "package_name")
    @Nullable
    public String packageName;

    @ColumnInfo(name = "wifi_bytes")
    public long wifiBytes;

    @ColumnInfo(name = "wifi_packets")
    public long wifiPackets;

    @ColumnInfo(name = "mobile_bytes")
    public long mobileBytes;

    @ColumnInfo(name = "mobile_packets")
    public long mobilePackets;

    @ColumnInfo(name = "radio_active_ms")
    public long radioActiveMs;

    @ColumnInfo(name = "wakelock_ms")
    public long wakelockMs;

    @ColumnInfo(name = "wakeup_count")
    public long wakeupCount;

    @ColumnInfo(name = "cpu_ms")
    public long cpuMs;

    @ColumnInfo(name = "fg_service_ms")
    public long fgServiceMs;

    @ColumnInfo(name = "sensor_ms")
    public long sensorMs;

    // Device-level fields; only meaningful on the UID_DEVICE row.
    @ColumnInfo(name = "screen_on_ms")
    public long screenOnMs;

    @ColumnInfo(name = "deep_idle_ms")
    public long deepIdleMs;

    @ColumnInfo(name = "light_idle_ms")
    public long lightIdleMs;

    @ColumnInfo(name = "battery_level")
    public int batteryLevel;

    /**
     * Battery voltage in millivolts at the moment of reading, or 0. Device row
     * only. Unlike the percentage this is a real measurement rather than a
     * quantised estimate, so it shows charge/discharge shape the level hides.
     */
    @ColumnInfo(name = "voltage_mv", defaultValue = "0")
    public int voltageMv;

    /**
     * Whether the device was charging over this bucket. Device row only. Stored
     * because a discharge chart that silently includes charging stretches is
     * worse than no chart — the line goes up and the reader distrusts all of it.
     */
    @ColumnInfo(name = "charging", defaultValue = "0")
    public boolean charging;

    /**
     * Estimated drain in mAh for this bucket, from the platform's own power
     * model. Zero where that model is unusable — see {@link #powerModelUsable}.
     */
    @ColumnInfo(name = "power_mah", defaultValue = "0")
    public double powerMah;

    /**
     * Whether the device's {@code power_profile.xml} was real when this bucket
     * was recorded. Stored per bucket rather than assumed globally, so an
     * archive moved between phones — or a ROM update that fixes the profile —
     * cannot make old zero-mAh rows look like genuine "used no power".
     */
    @ColumnInfo(name = "power_model_usable", defaultValue = "0")
    public boolean powerModelUsable;

    /**
     * JSON map of every other counter this platform offered — per-component
     * mAh ({@code mah.cpu}, {@code mah.wifi}, …), job/sync time and counts,
     * process-state times, Wi-Fi lock and scan time, audio/video/camera, and
     * anything a future device prints that we have not named yet.
     *
     * <p>Open-ended on purpose: this fork runs on several phones and each dumps
     * a different subset, so a fixed column list would silently discard what a
     * newer device knows. Summed in Java rather than SQL — one row per uid per
     * bucket keeps the table small, which matters more than aggregate speed.
     */
    @ColumnInfo(name = "extras")
    @Nullable
    public String extras;
}
