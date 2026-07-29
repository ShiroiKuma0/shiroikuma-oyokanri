// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.battery;

import android.content.Context;
import android.os.BatteryManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.ServiceManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.runner.Runner;
import io.github.muntashirakon.AppManager.self.SelfPermissions;

/**
 * Fork: obtains the raw {@code dumpsys batterystats} text.
 *
 * <p><b>Two routes, and the order matters.</b> The in-process route dumps the
 * {@code batterystats} binder directly from our own process. It needs
 * {@code android.permission.BATTERY_STATS} and {@code DUMP}, both of which are
 * {@code signature|privileged|<b>development</b>} — verified on the Mate XT,
 * 2026-07-29 — so {@link SelfPermissions#init()} can grant them to us with
 * {@code pm grant}-equivalent privileges, exactly as it already does for
 * {@code DUMP}. Once granted, the grant is persisted by the platform and
 * <b>survives reboot</b>, so sampling keeps working with no shell alive at all.
 * That is the whole reason this feature can run unattended on a phone whose
 * ADB-over-TCP dies at every reboot.
 *
 * <p>The shell route is the fallback for the window before that grant lands
 * (first run, or a reinstall that dropped it).
 */
public final class BatteryStatsReader {
    public static final String TAG = BatteryStatsReader.class.getSimpleName();

    private static final String SERVICE = "batterystats";
    /** Limits the dump to the since-charged section — skips the multi-hundred-KB history. */
    private static final String[] DUMP_ARGS = new String[]{"--charged"};

    private BatteryStatsReader() {}

    /** @return the dump text, or {@code null} when neither route could produce one. */
    @Nullable
    public static String read() {
        String dump = readInProcess();
        if (dump != null && dump.contains("Statistics since last charge:")) {
            return dump;
        }
        return readViaShell();
    }

    /** True when we hold the permissions the in-process route needs. */
    public static boolean hasDirectAccess() {
        return SelfPermissions.checkSelfPermission("android.permission.BATTERY_STATS")
                && SelfPermissions.checkSelfPermission(android.Manifest.permission.DUMP);
    }

    @Nullable
    private static String readInProcess() {
        if (!hasDirectAccess()) return null;
        ParcelFileDescriptor[] pipe = null;
        try {
            IBinder binder = ServiceManager.getService(SERVICE);
            if (binder == null) return null;
            pipe = ParcelFileDescriptor.createPipe();
            final ParcelFileDescriptor readSide = pipe[0];
            final ByteArrayOutputStream sink = new ByteArrayOutputStream(256 * 1024);
            // The dump blocks once the 64 KB pipe buffer fills, so the read has
            // to run concurrently or the two sides deadlock on each other.
            Thread reader = new Thread(() -> {
                try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(readSide)) {
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) sink.write(buf, 0, n);
                } catch (Throwable th) {
                    Log.w(TAG, "In-process dump read failed", th);
                }
            }, "batterystats-dump");
            reader.start();
            try {
                binder.dump(pipe[1].getFileDescriptor(), DUMP_ARGS);
            } finally {
                pipe[1].close();
            }
            reader.join(30_000);
            return sink.toString("UTF-8");
        } catch (Throwable th) {
            Log.w(TAG, "In-process batterystats dump unavailable", th);
            return null;
        } finally {
            if (pipe != null) {
                closeQuietly(pipe[0]);
                closeQuietly(pipe[1]);
            }
        }
    }

    private static void closeQuietly(@Nullable ParcelFileDescriptor pfd) {
        if (pfd == null) return;
        try {
            pfd.close();
        } catch (Throwable ignore) {
        }
    }

    @Nullable
    private static String readViaShell() {
        try {
            Runner.Result result = Runner.runCommand(new String[]{"dumpsys", "batterystats", "--charged"});
            if (!result.isSuccessful()) return null;
            String out = android.text.TextUtils.join("\n", result.getOutputAsList());
            return out.isEmpty() ? null : out;
        } catch (Throwable th) {
            Log.w(TAG, "Shell batterystats dump failed", th);
            return null;
        }
    }

    /** Voltage (mV) and charging state, from the sticky battery broadcast. */
    public static class Power {
        public int voltageMv;
        public boolean charging;
    }

    /**
     * Reads voltage and charging state. Uses the sticky
     * {@code ACTION_BATTERY_CHANGED} rather than {@code BatteryManager}
     * properties because voltage has no {@code BATTERY_PROPERTY_*} constant,
     * and because the sticky intent needs no privileges at all.
     */
    @NonNull
    public static Power power(@NonNull Context context) {
        Power power = new Power();
        try {
            android.content.Intent intent = context.registerReceiver(null,
                    new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (intent == null) return power;
            power.voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            power.charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
        } catch (Throwable ignore) {
        }
        return power;
    }

    /** Current battery percentage, or -1 when the platform will not say. */
    public static int batteryLevel(@NonNull Context context) {
        try {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (bm == null) return -1;
            int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            return level >= 0 && level <= 100 ? level : -1;
        } catch (Throwable th) {
            return -1;
        }
    }
}
