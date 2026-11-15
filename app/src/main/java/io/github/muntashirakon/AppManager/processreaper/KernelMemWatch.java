// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.processreaper;

import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.diag.ForkDiag;
import io.github.muntashirakon.AppManager.runner.Runner;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;

/**
 * Fork (白い熊, +097, 2026-11-15): the kernel memory that cannot be reclaimed —
 * 1.85 GB after 63 days of uptime (1.04 GB of large allocations, 318 MB in 5.2
 * million 64-byte objects). Nothing on a non-rooted phone can free it: only the
 * driver that allocated it can, and only a reboot resets it. What <i>can</i> be
 * done is to find what makes it grow, and two tools here serve that:
 *
 * <p><b>The growth record</b> (diagnostics kind {@code kernelmem}): every ten
 * minutes the figures, their change since the last line, the kernel caches that
 * moved most, and what the phone was doing — screen, charging, Wi-Fi, VPN,
 * mobile data, Bluetooth, car mode, extra displays, the app on top — so the
 * state it grows in can be read off afterwards.
 *
 * <p><b>The comparison</b> (monitor menu): a snapshot, the user toggles one
 * suspect, a second snapshot, and the difference. A driver that frees its
 * memory on teardown shows up as a drop.
 */
final class KernelMemWatch {
    private static final long RECORD_MS = 600_000L;
    private static final KernelMemWatch INSTANCE = new KernelMemWatch();

    @NonNull
    static KernelMemWatch get() {
        return INSTANCE;
    }

    private KernelMemWatch() {
    }

    private final AtomicBoolean mBusy = new AtomicBoolean(false);
    private volatile long mRecordEl;
    @Nullable
    private ForkStartWatch.Snap mPrev;
    private long mPrevEl;
    @Nullable
    private volatile ForkStartWatch.Snap mCompareBase;

    // ---- The growth record ------------------------------------------------------

    private static final String KEY_BTIME = "kmem_btime";
    private static final String KEY_LAST_FULL = "kmem_last_full";
    private static final long FULL_MS = 24 * 3_600_000L;

    // Screen-on time within the interval, accumulated tick by tick.
    private long mLastTickEl;
    private boolean mLastScreenOn;
    private long mScreenOnMs;

    /** What the phone did, as running totals since boot. */
    private static final class Activity {
        long forks = -1, ctxt = -1, btime = -1, netPackets = -1;
        final Map<String, Long> ifacePackets = new java.util.HashMap<>();
    }

    @Nullable
    private Activity mPrevActivity;
    private long mPrevUptime;

    /** Called from every watch tick; writes at most one line per ten minutes, on its own thread. */
    void maybeRecord(@NonNull Context ctx) {
        if (!ForkDiag.isOn(ctx, ForkDiag.KIND_KERNELMEM)) return;
        long now = SystemClock.elapsedRealtime();
        synchronized (this) {
            // A tick more than two minutes after the last one means we slept or were paused: not counted.
            if (mLastTickEl != 0 && mLastScreenOn) mScreenOnMs += Math.min(now - mLastTickEl, 120_000L);
            mLastTickEl = now;
            try {
                PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                mLastScreenOn = pm != null && pm.isInteractive();
            } catch (Throwable ignore) {
            }
        }
        if (mRecordEl != 0 && now - mRecordEl < RECORD_MS) return;
        if (!mBusy.compareAndSet(false, true)) return;
        mRecordEl = now;
        ThreadUtils.postOnBackgroundThread(() -> {
            try {
                record(ctx);
            } catch (Throwable ignore) {
            } finally {
                mBusy.set(false);
            }
        });
    }

    private static final String[] FIGURES = {"SUnreclaim", "SlabLarge", "SReclaimable", "KernelStack", "PageTables",
            "VmallocUsed", "IonTotalUsed", "GpuTotalUsed", "MemFree", "MemAvailable"};
    // The caches whose object counts are written on every line; the leak sits in the first.
    private static final String[] OBJECT_CACHES = {"kmalloc-64", "kmalloc-128", "kmalloc-192", "kmalloc-256",
            "kmalloc-512", "kmalloc-1k", "kmalloc-2k", "kmalloc-4k", "kmalloc-8k", "vmap_area", "task_struct", "pid",
            "vm_area_struct", "cred_jar", "filp", "sock_inode_cache", "anon_vma", "signal_cache"};

    @NonNull
    private static Activity readActivity() {
        Activity a = new Activity();
        try {
            Runner.Result r = Runner.runCommand("grep -E '^(processes|ctxt|btime) ' /proc/stat 2>/dev/null;"
                    + " echo @@nd; cat /proc/net/dev 2>/dev/null; true");
            if (r == null) return a;
            boolean net = false;
            long packets = 0;
            boolean anyNet = false;
            for (String line : r.getOutputAsList()) {
                if (line.startsWith("@@nd")) {
                    net = true;
                    continue;
                }
                String[] f = line.trim().split("[\\s:]+");
                try {
                    if (!net) {
                        if (f.length < 2) continue;
                        if ("processes".equals(f[0])) a.forks = Long.parseLong(f[1]);
                        else if ("ctxt".equals(f[0])) a.ctxt = Long.parseLong(f[1]);
                        else if ("btime".equals(f[0])) a.btime = Long.parseLong(f[1]);
                    } else if (f.length >= 11 && !"lo".equals(f[0])) {
                        // iface: rx bytes, rx packets, 6 more rx fields, tx bytes, tx packets, …
                        long p = Long.parseLong(f[2]) + Long.parseLong(f[10]);
                        a.ifacePackets.put(f[0], p);
                        packets += p;
                        anyNet = true;
                    }
                } catch (NumberFormatException ignore) {
                    // the two header lines of /proc/net/dev
                }
            }
            if (anyNet) a.netPackets = packets;
        } catch (Throwable ignore) {
        }
        return a;
    }

    private void record(@NonNull Context ctx) {
        ForkStartWatch.Snap now = ForkStartWatch.takeSnap();
        Activity act = readActivity();
        long nowEl = SystemClock.elapsedRealtime();
        long nowUp = SystemClock.uptimeMillis();
        long screenMs;
        synchronized (this) {
            screenMs = mScreenOnMs;
            mScreenOnMs = 0;
        }

        // A new boot: say so, and start the curves with a full snapshot.
        long btime = act.btime > 0 ? act.btime : (now.wallMs - nowEl) / 1000;
        long knownBtime = ForkDiag.getLong(ctx, KEY_BTIME, 0);
        boolean newBoot = Math.abs(btime - knownBtime) > 120;
        if (newBoot) {
            ForkDiag.write(ctx, ForkDiag.KIND_KERNELMEM, "boot\tbtime=" + btime + " at="
                    + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(btime * 1000))
                    + " uptimeS=" + nowEl / 1000);
            ForkDiag.putLong(ctx, KEY_BTIME, btime);
            mPrev = null;
            mPrevActivity = null;
        }
        if (newBoot || now.wallMs - ForkDiag.getLong(ctx, KEY_LAST_FULL, 0) >= FULL_MS) {
            writeFull(ctx, now);
            ForkDiag.putLong(ctx, KEY_LAST_FULL, now.wallMs);
        }

        StringBuilder sb = new StringBuilder("kmem\t");
        for (String k : FIGURES) sb.append("m.").append(k).append("KB=").append(get(now.meminfo, k)).append(' ');
        sb.append("m.nr_slab_unreclaimable=").append(get(now.vmstat, "nr_slab_unreclaimable")).append('\t');
        for (String k : OBJECT_CACHES) {
            if (now.slabObjects.containsKey(k)) sb.append("o.").append(k).append('=').append(get(now.slabObjects, k)).append(' ');
        }
        ForkStartWatch.Snap prev = mPrev;
        Activity prevAct = mPrevActivity;
        if (prev != null && prevAct != null) {
            sb.append('\t');
            for (String k : new String[]{"SUnreclaim", "SlabLarge", "KernelStack", "VmallocUsed"}) {
                sb.append("d.").append(k).append("KB=").append(get(now.meminfo, k) - get(prev.meminfo, k)).append(' ');
            }
            sb.append(objectChanges(prev, now)).append('\t');
            // wallS counts sleep, awakeS does not; the difference is the time spent in deep sleep.
            sb.append("a.wallS=").append((nowEl - mPrevEl) / 1000)
                    .append(" a.awakeS=").append((nowUp - mPrevUptime) / 1000)
                    .append(" a.screenS=").append(screenMs / 1000);
            if (act.forks >= 0 && prevAct.forks >= 0) sb.append(" a.forks=").append(act.forks - prevAct.forks);
            if (act.ctxt >= 0 && prevAct.ctxt >= 0) sb.append(" a.ctxt=").append(act.ctxt - prevAct.ctxt);
            if (act.netPackets >= 0 && prevAct.netPackets >= 0) {
                sb.append(" a.netpk=").append(act.netPackets - prevAct.netPackets);
                for (Map.Entry<String, Long> e : act.ifacePackets.entrySet()) {
                    Long before = prevAct.ifacePackets.get(e.getKey());
                    long d = e.getValue() - (before == null ? 0 : before);
                    if (d > 0) sb.append(" n.").append(e.getKey()).append('=').append(d);
                }
            }
        }
        sb.append('\t').append(state(ctx));
        ForkDiag.write(ctx, ForkDiag.KIND_KERNELMEM, sb.toString());
        mPrev = now;
        mPrevActivity = act;
        mPrevEl = nowEl;
        mPrevUptime = nowUp;
    }

    /** Every cache's object count and all of meminfo — once per boot and once a day, so each has an absolute curve. */
    private static void writeFull(@NonNull Context ctx, @NonNull ForkStartWatch.Snap s) {
        StringBuilder objs = new StringBuilder("full\tobjects\t");
        if (s.slabObjects.isEmpty()) objs.append("unreadable");
        for (Map.Entry<String, Long> e : s.slabObjects.entrySet()) {
            objs.append(e.getKey()).append('=').append(e.getValue()).append(' ');
        }
        ForkDiag.write(ctx, ForkDiag.KIND_KERNELMEM, objs.toString());
        StringBuilder bytes = new StringBuilder("full\tcache-kB\t");
        for (Map.Entry<String, Long> e : s.slab.entrySet()) {
            if (e.getValue() >= 64 << 10) bytes.append(e.getKey()).append('=').append(e.getValue() >> 10).append(' ');
        }
        ForkDiag.write(ctx, ForkDiag.KIND_KERNELMEM, bytes.toString());
        StringBuilder mi = new StringBuilder("full\tmeminfo-kB\t");
        for (Map.Entry<String, Long> e : s.meminfo.entrySet()) {
            mi.append(e.getKey()).append('=').append(e.getValue()).append(' ');
        }
        ForkDiag.write(ctx, ForkDiag.KIND_KERNELMEM, mi.toString());
    }

    /** "do.cache=±objects": always kmalloc-64, then the fourteen caches whose counts moved most. */
    @NonNull
    private static String objectChanges(@NonNull ForkStartWatch.Snap a, @NonNull ForkStartWatch.Snap b) {
        if (b.slabObjects.isEmpty()) return "do.unreadable=1";
        StringBuilder sb = new StringBuilder("do.kmalloc-64=")
                .append(get(b.slabObjects, "kmalloc-64") - get(a.slabObjects, "kmalloc-64"));
        List<Object[]> changes = new ArrayList<>();
        for (Map.Entry<String, Long> e : b.slabObjects.entrySet()) {
            if ("kmalloc-64".equals(e.getKey())) continue;
            long d = e.getValue() - get(a.slabObjects, e.getKey());
            if (Math.abs(d) >= 50) changes.add(new Object[]{e.getKey(), d});
        }
        Collections.sort(changes, (x, y) -> Long.compare(Math.abs((Long) y[1]), Math.abs((Long) x[1])));
        for (int i = 0; i < changes.size() && i < 14; i++) {
            sb.append(" do.").append(changes.get(i)[0]).append('=').append(changes.get(i)[1]);
        }
        return sb.toString();
    }

    private static long get(@NonNull Map<String, Long> map, @NonNull String key) {
        Long v = map.get(key);
        return v == null ? 0 : v;
    }

    @NonNull
    private static String signed(long v) {
        return (v > 0 ? "+" : "") + v;
    }

    /** "name=+KB" for the caches that moved at least {@code minBytes}, largest change first. */
    @NonNull
    private static String cacheChanges(@NonNull ForkStartWatch.Snap a, @NonNull ForkStartWatch.Snap b, long minBytes,
                                       int max, @NonNull String separator) {
        if (b.slab.isEmpty()) return "unreadable";
        List<Object[]> changes = new ArrayList<>();
        for (Map.Entry<String, Long> e : b.slab.entrySet()) {
            long d = e.getValue() - get(a.slab, e.getKey());
            if (Math.abs(d) >= minBytes) changes.add(new Object[]{e.getKey(), d});
        }
        Collections.sort(changes, (x, y) -> Long.compare(Math.abs((Long) y[1]), Math.abs((Long) x[1])));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < changes.size() && i < max; i++) {
            if (i > 0) sb.append(separator);
            sb.append(changes.get(i)[0]).append('=').append(signed((Long) changes.get(i)[1] / 1024)).append("KB");
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static final Pattern LRU_TOP = Pattern.compile("\\sTOP\\s.*?\\s\\d+:([^/\\s]+)/");
    /** The on/off states the report splits the growth by. */
    static final String[] STATES = {"screen", "charging", "usb", "usbAccessory", "wifi", "vpn", "mobile", "wifiRadio",
            "bluetooth", "btAudio", "car"};

    /** What the phone is doing, as "s.key=value" tokens. Every part is best-effort. */
    @NonNull
    private static String state(@NonNull Context ctx) {
        StringBuilder sb = new StringBuilder();
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            sb.append("s.screen=").append(pm != null && pm.isInteractive() ? 1 : 0);
        } catch (Throwable ignore) {
        }
        try {
            Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                sb.append(" s.charging=").append(plugged != 0 ? 1 : 0)
                        .append(" s.usb=").append(plugged == BatteryManager.BATTERY_PLUGGED_USB ? 1 : 0);
            }
        } catch (Throwable ignore) {
        }
        try {
            // Wired Android Auto talks to the car as a USB accessory.
            android.hardware.usb.UsbManager usb = (android.hardware.usb.UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            android.hardware.usb.UsbAccessory[] acc = usb == null ? null : usb.getAccessoryList();
            sb.append(" s.usbAccessory=").append(acc != null && acc.length > 0 ? 1 : 0);
        } catch (Throwable ignore) {
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            int wifi = 0, vpn = 0, cell = 0;
            if (cm != null) {
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities c = cm.getNetworkCapabilities(n);
                    if (c == null) continue;
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = 1;
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpn = 1;
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cell = 1;
                }
            }
            sb.append(" s.wifi=").append(wifi).append(" s.vpn=").append(vpn).append(" s.mobile=").append(cell);
        } catch (Throwable ignore) {
        }
        try {
            sb.append(" s.wifiRadio=").append(Settings.Global.getInt(ctx.getContentResolver(), "wifi_on", 0) != 0 ? 1 : 0)
                    .append(" s.bluetooth=")
                    .append(Settings.Global.getInt(ctx.getContentResolver(), "bluetooth_on", 0) != 0 ? 1 : 0);
        } catch (Throwable ignore) {
        }
        try {
            // A connected Bluetooth audio device, without the Bluetooth permission.
            android.media.AudioManager am = (android.media.AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            int bt = 0;
            if (am != null) {
                for (android.media.AudioDeviceInfo d : am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
                    int t = d.getType();
                    if (t == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                            || t == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO) bt = 1;
                }
            }
            sb.append(" s.btAudio=").append(bt);
        } catch (Throwable ignore) {
        }
        try {
            UiModeManager ui = (UiModeManager) ctx.getSystemService(Context.UI_MODE_SERVICE);
            sb.append(" s.car=").append(ui != null && ui.getCurrentModeType() == Configuration.UI_MODE_TYPE_CAR ? 1 : 0);
        } catch (Throwable ignore) {
        }
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) sb.append(" s.displays=").append(dm.getDisplays().length);
        } catch (Throwable ignore) {
        }
        try {
            Runner.Result r = Runner.runCommand("dumpsys activity lru 2>/dev/null | grep -m 1 ' TOP '; true");
            if (r != null) {
                for (String line : r.getOutputAsList()) {
                    Matcher m = LRU_TOP.matcher(line);
                    if (m.find()) sb.append(" s.top=").append(m.group(1));
                }
            }
        } catch (Throwable ignore) {
        }
        sb.append(" s.uptimeH=").append(SystemClock.elapsedRealtime() / 3_600_000L);
        return sb.toString().trim();
    }

    // ---- The report: what does the growth follow? ------------------------------------

    private static final String[] DRIVERS = {"a.forks", "a.ctxt", "a.netpk", "a.screenS", "a.awakeS", "a.wallS"};
    private static final int[] DRIVER_LABELS = {R.string.monitor_report_driver_forks, R.string.monitor_report_driver_ctxt,
            R.string.monitor_report_driver_net, R.string.monitor_report_driver_screen,
            R.string.monitor_report_driver_awake, R.string.monitor_report_driver_wall};

    @NonNull
    private static Map<String, String> tokens(@NonNull String line) {
        Map<String, String> out = new java.util.HashMap<>();
        for (String t : line.split("\\s+")) {
            int eq = t.indexOf('=');
            if (eq > 0) out.put(t.substring(0, eq), t.substring(eq + 1));
        }
        return out;
    }

    private static double num(@NonNull Map<String, String> t, @NonNull String key) {
        try {
            String v = t.get(key);
            return v == null ? Double.NaN : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** Pearson's r over the pairs where both values exist; NaN under six pairs or without variation. */
    private static double correlation(@NonNull List<Map<String, String>> rows, @NonNull String x, @NonNull String y) {
        double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
        int n = 0;
        for (Map<String, String> r : rows) {
            double a = num(r, x), b = num(r, y);
            if (Double.isNaN(a) || Double.isNaN(b)) continue;
            n++;
            sx += a;
            sy += b;
            sxx += a * a;
            syy += b * b;
            sxy += a * b;
        }
        if (n < 6) return Double.NaN;
        double vx = sxx - sx * sx / n, vy = syy - sy * sy / n;
        if (vx <= 0 || vy <= 0) return Double.NaN;
        return (sxy - sx * sy / n) / Math.sqrt(vx * vy);
    }

    /**
     * Read the growth record back and say what the growth follows: for the leak
     * itself (kmalloc-64 objects) and for the large allocations, the total since
     * the last boot marker, the rate, how closely each interval's growth tracks
     * each activity counter, and the rate with each state on and off. Reads the
     * disk — off the main thread.
     */
    @NonNull
    String report(@NonNull Context ctx) {
        List<String> lines = ForkDiag.readLines(ctx, ForkDiag.KIND_KERNELMEM, 20_000);
        List<Map<String, String>> rows = new ArrayList<>();
        String since = null, first = null, last = null;
        for (String line : lines) {
            String[] f = line.split("\t", 3);
            if (f.length < 3) continue;
            if ("boot".equals(f[1])) {
                rows.clear();
                since = f[0];
                first = null;
                continue;
            }
            if (!"kmem".equals(f[1])) continue;
            Map<String, String> t = tokens(f[2]);
            if (!t.containsKey("a.wallS")) continue;  // the first line after a start has no differences
            if (first == null) first = f[0];
            last = f[0];
            rows.add(t);
        }
        if (rows.size() < 6) return ctx.getString(R.string.monitor_report_too_little, rows.size());
        double wallS = 0;
        for (Map<String, String> r : rows) wallS += num(r, "a.wallS");
        double hours = wallS / 3600.0;
        StringBuilder sb = new StringBuilder();
        sb.append(ctx.getString(R.string.monitor_report_span, since != null ? since.substring(0, 16) : first.substring(0, 16),
                last.substring(0, 16), rows.size(), String.format(Locale.US, "%.1f", hours))).append("\n\n");
        String[] targets = {"do.kmalloc-64", "d.SlabLargeKB", "d.SUnreclaimKB"};
        int[] targetLabels = {R.string.monitor_report_target_objects, R.string.monitor_report_target_large,
                R.string.monitor_report_target_unreclaim};
        for (int i = 0; i < targets.length; i++) {
            double total = 0;
            int n = 0;
            for (Map<String, String> r : rows) {
                double v = num(r, targets[i]);
                if (!Double.isNaN(v)) {
                    total += v;
                    n++;
                }
            }
            if (n == 0) continue;
            sb.append(ctx.getString(targetLabels[i])).append("  ").append(String.format(Locale.US, "%+,.0f", total))
                    .append("  (").append(String.format(Locale.US, "%+,.0f", total / Math.max(hours, 0.01)))
                    .append(ctx.getString(R.string.monitor_report_per_hour)).append(")\n");
        }
        for (int i = 0; i < 2; i++) {
            final String target = targets[i];
            sb.append('\n').append(ctx.getString(R.string.monitor_report_follows, ctx.getString(targetLabels[i]))).append('\n');
            List<Object[]> found = new ArrayList<>();
            for (int d = 0; d < DRIVERS.length; d++) {
                double c = correlation(rows, DRIVERS[d], target);
                if (!Double.isNaN(c)) found.add(new Object[]{ctx.getString(DRIVER_LABELS[d]), c});
            }
            Collections.sort(found, (x, y) -> Double.compare((Double) y[1], (Double) x[1]));
            if (found.isEmpty()) sb.append("  ").append(ctx.getString(R.string.monitor_report_no_variation)).append('\n');
            for (Object[] o : found) {
                sb.append("  ").append(String.format(Locale.US, "%+.2f", (Double) o[1])).append("  ").append(o[0]).append('\n');
            }
            sb.append(ctx.getString(R.string.monitor_report_by_state)).append('\n');
            for (String state : STATES) {
                double onSum = 0, onS = 0, offSum = 0, offS = 0;
                for (Map<String, String> r : rows) {
                    double v = num(r, target), s = num(r, "s." + state), w = num(r, "a.wallS");
                    if (Double.isNaN(v) || Double.isNaN(s) || Double.isNaN(w)) continue;
                    if (s != 0) {
                        onSum += v;
                        onS += w;
                    } else {
                        offSum += v;
                        offS += w;
                    }
                }
                // A state that was never on, or never off, tells nothing.
                if (onS < 1200 || offS < 1200) continue;
                sb.append("  ").append(state).append("  ")
                        .append(ctx.getString(R.string.monitor_report_on_off,
                                String.format(Locale.US, "%+,.0f", onSum / (onS / 3600)),
                                String.format(Locale.US, "%+,.0f", offSum / (offS / 3600)))).append('\n');
            }
        }
        sb.append('\n').append(ctx.getString(R.string.monitor_report_note));
        return sb.toString();
    }

    // ---- The comparison ---------------------------------------------------------

    /** Wall time of the first snapshot, or 0 when there is none. */
    long compareBaseMs() {
        ForkStartWatch.Snap s = mCompareBase;
        return s == null ? 0 : s.wallMs;
    }

    /** Take the first snapshot. Off the main thread. */
    void compareStart() {
        mCompareBase = ForkStartWatch.takeSnap();
    }

    void compareClear() {
        mCompareBase = null;
    }

    /**
     * Take a second snapshot and describe what changed since the first, which is
     * kept so the comparison can be repeated. Off the main thread. Null when
     * there is no first snapshot.
     */
    @Nullable
    String compare(@NonNull Context ctx) {
        ForkStartWatch.Snap a = mCompareBase;
        if (a == null) return null;
        ForkStartWatch.Snap b = ForkStartWatch.takeSnap();
        SimpleDateFormat hms = new SimpleDateFormat("HH:mm:ss", Locale.US);
        StringBuilder sb = new StringBuilder();
        sb.append(ctx.getString(R.string.monitor_compare_span, hms.format(new Date(a.wallMs)),
                hms.format(new Date(b.wallMs)), (b.wallMs - a.wallMs) / 1000)).append("\n\n");
        for (String k : FIGURES) {
            long before = get(a.meminfo, k), after = get(b.meminfo, k);
            long d = after - before;
            sb.append(k).append("  ").append(before >> 10).append(" → ").append(after >> 10).append(" MB  (")
                    .append(Math.abs(d) >= 1024 ? signed(d / 1024) + " MB" : signed(d) + " KB").append(")\n");
        }
        sb.append('\n');
        if (b.slab.isEmpty()) {
            sb.append(ctx.getString(R.string.monitor_compare_no_slab));
        } else {
            String caches = cacheChanges(a, b, 256 << 10, 20, "\n  ");
            if ("none".equals(caches)) sb.append(ctx.getString(R.string.monitor_compare_no_caches));
            else sb.append(ctx.getString(R.string.monitor_compare_caches)).append("\n  ")
                    .append(caches.replace("=", "  ").replace("KB", " KB"));
        }
        ForkDiag.write(ctx, ForkDiag.KIND_KERNELMEM, "compare\tspan=" + (b.wallMs - a.wallMs) / 1000
                + "s SUnreclaimKB=" + signed(get(b.meminfo, "SUnreclaim") - get(a.meminfo, "SUnreclaim"))
                + " SlabLargeKB=" + signed(get(b.meminfo, "SlabLarge") - get(a.meminfo, "SlabLarge"))
                + "\tcaches " + cacheChanges(a, b, 64 << 10, 20, " ") + "\tstate " + state(ctx));
        return sb.toString();
    }
}
