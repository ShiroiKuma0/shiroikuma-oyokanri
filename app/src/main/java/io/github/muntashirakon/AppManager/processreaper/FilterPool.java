// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.processreaper;

import android.content.Context;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.diag.ForkDiag;
import io.github.muntashirakon.AppManager.runner.Runner;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;

/**
 * Fork (白い熊, +105): the kernel's room for filter programs — the pool whose exhaustion is what
 * "new processes cannot start" is (proven 2026-11-15: with no room left every request was
 * refused, and the moment one program was given back exactly one new one was accepted, three
 * times of three). Every app process holds one program; something else takes the rest over weeks
 * of uptime and only a restart returns it.
 *
 * <p>Three measurements, all through plain UDP sockets with a one-instruction filter
 * ({@link KernelProbe}):
 * <ul>
 * <li><b>quick</b> — at most 6, every 30 minutes: "at least six" on a healthy phone, exact when
 * it is nearly full;</li>
 * <li><b>deep</b> — at most {@link #DEEP_CAP}, once per boot after 15 minutes of uptime, then
 * every 24 hours, and on demand: exact as soon as less than that is free;</li>
 * <li><b>size</b> — up to {@link #SIZE_CAP}, only on demand: the whole free pool.</li>
 * </ul>
 * A measurement that reads below its cap emptied the pool for a moment, in which app starts
 * fail — hence the low rates and the caps. Do not raise them without a reason.
 *
 * <p>Every measurement is one {@code room} line in the {@code filterpool} recording, with the
 * figures it should be read against. Tokens are never renamed.
 */
public final class FilterPool {
    private FilterPool() {
    }

    static final int DEEP_CAP = 2048;
    static final int SIZE_CAP = 16384;
    static final int CHURN_CYCLES = 2000;
    private static final long QUICK_MS = 30 * 60_000L;
    private static final long DEEP_MS = 24 * 3_600_000L;
    private static final long BOOT_SETTLE_MS = 15 * 60_000L;

    private static final String K_FREE = "pool_free", K_EXACT = "pool_exact", K_WALL = "pool_wall",
            K_DEEP_WALL = "pool_deep_wall", K_DEEP_BOOT = "pool_deep_boot",
            K_SIZE = "pool_size", K_SIZE_WALL = "pool_size_wall", K_SIZE_EXACT = "pool_size_exact",
            K_SIZE_BOOT = "pool_size_boot";

    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static long sQuickEl;

    /** Wall-clock second the phone booted, stable to a few seconds. */
    private static long bootSec() {
        return (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 1000L;
    }

    private static boolean sameBoot(long a, long b) {
        return Math.abs(a - b) <= 90;
    }

    /** Called from the watch's tick: runs whatever is due, off the caller's thread. */
    static void maybeMeasure(@NonNull Context context) {
        final Context ctx = context.getApplicationContext();
        if (!ForkDiag.isOn(ctx, ForkDiag.KIND_FILTERPOOL)) return;
        long nowEl = SystemClock.elapsedRealtime();
        long now = System.currentTimeMillis();
        boolean deepDue = nowEl >= BOOT_SETTLE_MS
                && (!sameBoot(ForkDiag.getLong(ctx, K_DEEP_BOOT, 0), bootSec())
                || now - ForkDiag.getLong(ctx, K_DEEP_WALL, 0) >= DEEP_MS);
        boolean quickDue = sQuickEl == 0 || nowEl - sQuickEl >= QUICK_MS;
        if (!deepDue && !quickDue) return;
        if (!BUSY.compareAndSet(false, true)) return;
        sQuickEl = nowEl;
        ThreadUtils.postOnBackgroundThread(() -> {
            try {
                // No deep measurement while starts are failing: the room is 0 and the quick one says so.
                if (deepDue && !ForkStartWatch.get().isActive()) measure(ctx, "deep", DEEP_CAP);
                else quick(ctx, "quick");
            } catch (Throwable ignore) {
            } finally {
                BUSY.set(false);
            }
        });
    }

    /** The small gauge beside a failing probe: its tokens for the {@code kprobe} line, and a line of its own. */
    @NonNull
    static String gaugeForProbe(@NonNull Context ctx) {
        try {
            sQuickEl = SystemClock.elapsedRealtime();
            return quick(ctx.getApplicationContext(), "probe");
        } catch (Throwable t) {
            return " sockfree=unavailable";
        }
    }

    @NonNull
    private static String quick(@NonNull Context ctx, @NonNull String kind) {
        int[] s = KernelProbe.sockets();
        if (s == null || s.length < 5) return " sockfree=unavailable";
        String reserve;
        switch (s[3]) {
            case 0: reserve = "held"; break;
            case 1: reserve = "none"; break;
            case 2: reserve = "freed-ok@" + s[4] + "ms"; break;
            case 3: reserve = "freed-ENOMEM@" + s[4] + "ms"; break;
            default: reserve = "new"; break;
        }
        boolean exact = s[0] < s[1];
        store(ctx, s[0], exact);
        write(ctx, kind, s[0], exact, s[1], 0, " reserve=" + reserve);
        return " sockfree=" + s[0] + (exact ? "" : "+")
                + (s[2] != 0 && s[2] != 12 ? " sockErrno=" + s[2] : "") + " reserve=" + reserve;
    }

    /** One measurement up to {@code cap}; blocks for a moment. {free, exact(0/1), tookMs}, or null. */
    @Nullable
    static int[] measure(@NonNull Context ctx, @NonNull String kind, int cap) {
        int[] r = KernelProbe.room(cap);
        if (r == null || r.length < 4) return null;
        boolean exact = r[1] != 0;
        store(ctx, r[0], exact);
        long now = System.currentTimeMillis();
        if ("deep".equals(kind) || "size".equals(kind)) {
            ForkDiag.putLong(ctx, K_DEEP_WALL, now);
            ForkDiag.putLong(ctx, K_DEEP_BOOT, bootSec());
        }
        if ("size".equals(kind)) {
            ForkDiag.putLong(ctx, K_SIZE, r[0]);
            ForkDiag.putLong(ctx, K_SIZE_EXACT, exact ? 1 : 0);
            ForkDiag.putLong(ctx, K_SIZE_WALL, now);
            ForkDiag.putLong(ctx, K_SIZE_BOOT, bootSec());
        }
        write(ctx, kind, r[0], exact, cap, r[3], r[2] != 0 && r[2] != 12 ? " sockErrno=" + r[2] : "");
        return new int[]{r[0], exact ? 1 : 0, r[3]};
    }

    /** A reading that counted to the end is not repeated sooner than this. */
    private static final long REMEASURE_MS = 10 * 60_000L;
    /** An exact reading stays the shown figure this long against later "at least" ones. */
    private static final long EXACT_KEEP_MS = 26 * 3_600_000L;

    private static void store(@NonNull Context ctx, int free, boolean exact) {
        // Fork (+107): the half-hourly "at least 6" must not replace yesterday's exact 1,435 as
        // the shown figure — it says less. It does once the exact one is older than a day, or
        // when it contradicts it (it never should).
        if (!exact && ForkDiag.getLong(ctx, K_EXACT, 0) != 0
                && System.currentTimeMillis() - ForkDiag.getLong(ctx, K_WALL, 0) < EXACT_KEEP_MS
                && ForkDiag.getLong(ctx, K_FREE, 0) >= free) {
            return;
        }
        ForkDiag.putLong(ctx, K_FREE, free);
        ForkDiag.putLong(ctx, K_EXACT, exact ? 1 : 0);
        ForkDiag.putLong(ctx, K_WALL, System.currentTimeMillis());
    }

    /** The figures a reading is read against, as {@code key=value} tokens. */
    @NonNull
    private static String context() {
        StringBuilder sb = new StringBuilder();
        sb.append(" boot=").append(bootSec()).append(" uptimeS=").append(SystemClock.elapsedRealtime() / 1000L);
        try {
            Runner.Result r = Runner.runCommand("grep -E '^processes ' /proc/stat 2>/dev/null;"
                    + " grep -E '^(vmap_area|kmalloc-64|mnt_cache) ' /proc/slabinfo 2>/dev/null;"
                    + " grep -E '^(SUnreclaim|Percpu):' /proc/meminfo 2>/dev/null;"
                    + " echo \"procs $(ps -A 2>/dev/null | wc -l)\"; echo \"threads $(ps -AT 2>/dev/null | wc -l)\";"
                    + " Z=$(pidof -s zygote64 2>/dev/null); [ -n \"$Z\" ] &&"
                    + " echo \"apps $(ps -A -o PPID 2>/dev/null | grep -c -w \"$Z\")\"; true");
            if (r != null) {
                for (String line : r.getOutputAsList()) {
                    String[] f = line.trim().split("[\\s:]+");
                    if (f.length < 2) continue;
                    switch (f[0]) {
                        case "processes": sb.append(" forks=").append(f[1]); break;
                        case "vmap_area": case "kmalloc-64": case "mnt_cache":
                            sb.append(" o.").append(f[0]).append('=').append(f[1]); break;
                        case "SUnreclaim": case "Percpu":
                            sb.append(" m.").append(f[0]).append("KB=").append(f[1]); break;
                        case "procs": case "threads": case "apps":
                            sb.append(' ').append(f[0]).append('=').append(f[1]); break;
                        default: break;
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return sb.toString();
    }

    private static void write(@NonNull Context ctx, @NonNull String kind, int free, boolean exact, int cap,
                              int tookMs, @NonNull String extra) {
        if (!ForkDiag.isOn(ctx, ForkDiag.KIND_FILTERPOOL)) return;
        ForkDiag.write(ctx, ForkDiag.KIND_FILTERPOOL, "room\tfree=" + free + " exact=" + (exact ? 1 : 0)
                + " cap=" + cap + " kind=" + kind + " tookMs=" + tookMs + " failing="
                + (ForkStartWatch.get().isActive() ? 1 : 0) + extra + context());
    }

    // ---- What the monitor shows -------------------------------------------------

    /** The header pill: "Filter room 7", "Filter room 2,048+", or the not-measured text. Cheap. */
    @NonNull
    public static String pillText(@NonNull Context ctx) {
        long wall = ForkDiag.getLong(ctx, K_WALL, 0);
        if (wall == 0) return ctx.getString(R.string.monitor_pool_pill_none);
        long free = ForkDiag.getLong(ctx, K_FREE, 0);
        boolean exact = ForkDiag.getLong(ctx, K_EXACT, 0) != 0;
        return ctx.getString(R.string.monitor_pool_pill, String.format(Locale.US, "%,d", free) + (exact ? "" : "+"));
    }

    /** The bare value of the last reading: "7", "2,048+" or "—". */
    @NonNull
    public static String pillValue(@NonNull Context ctx) {
        if (ForkDiag.getLong(ctx, K_WALL, 0) == 0) return "—";
        return String.format(Locale.US, "%,d", ForkDiag.getLong(ctx, K_FREE, 0))
                + (ForkDiag.getLong(ctx, K_EXACT, 0) != 0 ? "" : "+");
    }

    /** True when the last reading was exact and small: the pill takes the warning colour. */
    public static boolean pillWarn(@NonNull Context ctx) {
        return ForkDiag.getLong(ctx, K_WALL, 0) != 0 && ForkDiag.getLong(ctx, K_EXACT, 0) != 0
                && ForkDiag.getLong(ctx, K_FREE, 0) < 64;
    }

    private static final class Row {
        long wallMs;
        Map<String, String> t;

        long num(String key) {
            try {
                String v = t.get(key);
                return v == null ? -1 : Long.parseLong(v);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    @NonNull
    private static List<Row> rows(@NonNull Context ctx, boolean thisBootOnly) {
        List<Row> out = new ArrayList<>();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        long boot = bootSec();
        for (String line : ForkDiag.readLines(ctx, ForkDiag.KIND_FILTERPOOL, 20_000)) {
            String[] parts = line.split("\t");
            if (parts.length < 3 || !"room".equals(parts[1])) continue;
            Row row = new Row();
            try {
                Date d = fmt.parse(parts[0]);
                if (d == null) continue;
                row.wallMs = d.getTime();
            } catch (Throwable t) {
                continue;
            }
            row.t = new HashMap<>();
            for (String tok : parts[2].split(" ")) {
                int eq = tok.indexOf('=');
                if (eq > 0) row.t.put(tok.substring(0, eq), tok.substring(eq + 1));
            }
            if (thisBootOnly && !sameBoot(row.num("boot"), boot)) continue;
            out.add(row);
        }
        return out;
    }

    @NonNull
    private static String n(long v) {
        return v < 0 ? "—" : String.format(Locale.US, "%,d", v);
    }

    @NonNull
    private static String span(@NonNull Context ctx, long seconds) {
        if (seconds < 3600) return ctx.getString(R.string.monitor_pool_minutes, Math.max(1, seconds / 60));
        if (seconds < 2 * 86400) return ctx.getString(R.string.monitor_pool_hours, seconds / 3600.0);
        return ctx.getString(R.string.monitor_pool_days, seconds / 86400.0);
    }

    /**
     * The panel behind the pill: the room now, the pool's size when measured, the history since
     * the restart with its trend, and the latest readings with the figures recorded beside them.
     * Reads the recording — off the main thread.
     */
    @NonNull
    public static String report(@NonNull Context ctx) {
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
        SimpleDateFormat dhm = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
        long wall = ForkDiag.getLong(ctx, K_WALL, 0);
        if (wall == 0) {
            sb.append(ctx.getString(R.string.monitor_pool_none));
        } else {
            boolean exact = ForkDiag.getLong(ctx, K_EXACT, 0) != 0;
            sb.append(ctx.getString(exact ? R.string.monitor_pool_now_exact : R.string.monitor_pool_now_atleast,
                    n(ForkDiag.getLong(ctx, K_FREE, 0)), dhm.format(new Date(wall))));
        }
        sb.append('\n');
        long sizeWall = ForkDiag.getLong(ctx, K_SIZE_WALL, 0);
        if (sizeWall != 0 && sameBoot(ForkDiag.getLong(ctx, K_SIZE_BOOT, 0), bootSec())) {
            sb.append(ctx.getString(ForkDiag.getLong(ctx, K_SIZE_EXACT, 0) != 0 ? R.string.monitor_pool_size_exact
                    : R.string.monitor_pool_size_atleast, n(ForkDiag.getLong(ctx, K_SIZE, 0)),
                    dhm.format(new Date(sizeWall))));
        } else {
            sb.append(ctx.getString(R.string.monitor_pool_size_none));
        }
        sb.append('\n').append(ctx.getString(R.string.monitor_pool_uptime,
                span(ctx, SystemClock.elapsedRealtime() / 1000L)));

        if (!ForkDiag.isOn(ctx, ForkDiag.KIND_FILTERPOOL)) {
            sb.append("\n\n").append(ctx.getString(R.string.monitor_pool_off));
            return sb.toString();
        }
        List<Row> rows = rows(ctx, true);
        sb.append("\n\n").append(ctx.getString(R.string.monitor_pool_history, rows.size()));
        List<Row> exactRows = new ArrayList<>();
        for (Row r : rows) if (r.num("exact") == 1 && r.num("failing") != 1) exactRows.add(r);
        if (!exactRows.isEmpty()) {
            Row hi = exactRows.get(0), lo = exactRows.get(0);
            for (Row r : exactRows) {
                if (r.num("free") > hi.num("free")) hi = r;
                if (r.num("free") < lo.num("free")) lo = r;
            }
            sb.append('\n').append(ctx.getString(R.string.monitor_pool_hi_lo, n(hi.num("free")),
                    dhm.format(new Date(hi.wallMs)), n(lo.num("free")), dhm.format(new Date(lo.wallMs))));
        }
        // The trend: a straight line through the exact readings, against time and against process creations.
        if (exactRows.size() >= 3
                && exactRows.get(exactRows.size() - 1).wallMs - exactRows.get(0).wallMs >= 3_600_000L) {
            double[] byTime = slope(exactRows, null);
            double perDay = byTime[0] * 86_400_000.0;
            sb.append('\n').append(ctx.getString(R.string.monitor_pool_trend, String.format(Locale.US, "%+,.1f", perDay)));
            long last = exactRows.get(exactRows.size() - 1).num("free");
            if (perDay < -0.5 && last > 0) {
                sb.append('\n').append(ctx.getString(R.string.monitor_pool_left, last / -perDay));
            }
            double[] byForks = slope(exactRows, "forks");
            if (!Double.isNaN(byForks[0])) {
                sb.append('\n').append(ctx.getString(R.string.monitor_pool_per_forks,
                        String.format(Locale.US, "%+,.2f", byForks[0] * 1_000_000.0)));
            }
        } else {
            sb.append('\n').append(ctx.getString(R.string.monitor_pool_trend_none));
        }

        sb.append("\n\n").append(ctx.getString(R.string.monitor_pool_table));
        int from = Math.max(0, rows.size() - 24);
        for (int i = rows.size() - 1; i >= from; i--) {
            Row r = rows.get(i);
            sb.append('\n').append(dhm.format(new Date(r.wallMs))).append("  ")
                    .append(n(r.num("free"))).append(r.num("exact") == 1 ? "" : "+")
                    .append("  ").append(r.t.get("kind"))
                    .append(r.num("failing") == 1 ? " !" : "")
                    .append("  ").append(ctx.getString(R.string.monitor_pool_row_ctx, n(r.num("apps")),
                            n(r.num("procs")), n(r.num("forks") < 0 ? -1 : r.num("forks") / 1000)));
        }
        if (rows.isEmpty()) sb.append('\n').append(ctx.getString(R.string.monitor_pool_table_none));
        sb.append("\n\n").append(ctx.getString(R.string.monitor_pool_explain, hm.format(new Date())));
        return sb.toString();
    }

    /** Least-squares slope of free room against time (per ms) or against a recorded counter. */
    @NonNull
    private static double[] slope(@NonNull List<Row> rows, @Nullable String key) {
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        int n = 0;
        double x0 = Double.NaN;
        for (Row r : rows) {
            double x = key == null ? r.wallMs : r.num(key);
            if (key != null && x < 0) continue;
            if (Double.isNaN(x0)) x0 = x;
            x -= x0;
            double y = r.num("free");
            sx += x;
            sy += y;
            sxx += x * x;
            sxy += x * y;
            n++;
        }
        double den = n * sxx - sx * sx;
        if (n < 3 || den == 0) return new double[]{Double.NaN};
        return new double[]{(n * sxy - sx * sy) / den};
    }

    /** "Measure now": exact below {@link #DEEP_CAP}. */
    @NonNull
    public static String measureNow(@NonNull Context ctx) {
        measure(ctx.getApplicationContext(), "deep", DEEP_CAP);
        return report(ctx);
    }

    /** "Pool size": the whole free pool, up to {@link #SIZE_CAP}. Empties it for a moment. */
    @NonNull
    public static String measureSize(@NonNull Context ctx) {
        measure(ctx.getApplicationContext(), "size", SIZE_CAP);
        return report(ctx);
    }

    /**
     * The churn test: the room, {@link #CHURN_CYCLES} programs created and released one by one,
     * the room again. A smaller room afterwards means making and releasing a program loses room.
     */
    @NonNull
    public static String churnTest(@NonNull Context context) {
        Context ctx = context.getApplicationContext();
        int[] before = measure(ctx, "churn-before", DEEP_CAP);
        SystemClock.sleep(400L);
        int[] c = KernelProbe.churn(CHURN_CYCLES);
        SystemClock.sleep(600L);
        int[] after = measure(ctx, "churn-after", DEEP_CAP);
        String head;
        if (before == null || after == null || c == null) {
            head = ctx.getString(R.string.monitor_pool_churn_failed);
        } else {
            ForkDiag.write(ctx, ForkDiag.KIND_FILTERPOOL, "churn\tcycles=" + c[0] + " refusals=" + c[1]
                    + " tookMs=" + c[2] + " before=" + before[0] + " beforeExact=" + before[1]
                    + " after=" + after[0] + " afterExact=" + after[1]);
            head = ctx.getString(R.string.monitor_pool_churn_result, n(c[0]),
                    n(before[0]) + (before[1] != 0 ? "" : "+"), n(after[0]) + (after[1] != 0 ? "" : "+"), c[1]);
        }
        return head + "\n\n" + report(ctx);
    }

    // ---- The data behind the panel and the page (+106) -----------------------------

    /** One recorded reading with the figures beside it; -1 where a figure is missing. */
    public static final class Reading {
        public long wallMs, boot, free, uptimeS, forks, apps, procs, threads, mounts, vmap, kmalloc64, sUnreclaimKB;
        public boolean exact, failing;
        public String kind = "", reserve;
    }

    /** One burst of failing starts, from the process-start recording. */
    public static final class Burst {
        public long startMs, endMs;
        public int failures = -1, probesFailed = -1, appsEnded;
        public boolean cured;
    }

    /** Everything the panel and the page show, read once off the main thread. */
    public static final class Summary {
        public boolean recording;
        public long free = -1, wallMs, sizeFree = -1, sizeWallMs, uptimeS, bootSec;
        public boolean exact, sizeExact;
        /**
         * The pool at its fullest since the restart: the highest reading that counted to the end
         * (whichever measurement took it), or -1. On a pool that only shrinks this is its size
         * less what the apps held at that moment.
         */
        public long fullest = -1, fullestWallMs;
        /** Every stored reading, oldest first, across restarts. */
        public final List<Reading> all = new ArrayList<>();
        public final List<Burst> bursts = new ArrayList<>();
        /** A result line to show on top (the churn test), or null. */
        @Nullable
        public String headline;
    }

    /** Programs per day (index 0) and per million creations (index 1) through the exact readings; NaN when too few. */
    @NonNull
    public static double[] trend(@NonNull List<Reading> readings) {
        List<Reading> ex = new ArrayList<>();
        for (Reading r : readings) if (r.exact && !r.failing) ex.add(r);
        double[] out = {Double.NaN, Double.NaN};
        if (ex.size() < 3 || ex.get(ex.size() - 1).wallMs - ex.get(0).wallMs < 3_600_000L) return out;
        out[0] = fit(ex, false) * 86_400_000.0;
        out[1] = fit(ex, true) * 1_000_000.0;
        return out;
    }

    private static double fit(@NonNull List<Reading> rows, boolean byForks) {
        double sx = 0, sy = 0, sxx = 0, sxy = 0, x0 = Double.NaN;
        int n = 0;
        for (Reading r : rows) {
            double x = byForks ? r.forks : r.wallMs;
            if (byForks && x < 0) continue;
            if (Double.isNaN(x0)) x0 = x;
            x -= x0;
            sx += x;
            sy += r.free;
            sxx += x * x;
            sxy += x * r.free;
            n++;
        }
        double den = n * sxx - sx * sx;
        return n < 3 || den == 0 ? Double.NaN : (n * sxy - sx * sy) / den;
    }

    public static boolean sameBootAs(@NonNull Summary s, @NonNull Reading r) {
        return sameBoot(r.boot, s.bootSec);
    }

    /** Reads both recordings. Off the main thread. */
    @NonNull
    public static Summary summary(@NonNull Context ctx) {
        Summary s = new Summary();
        s.recording = ForkDiag.isOn(ctx, ForkDiag.KIND_FILTERPOOL);
        s.bootSec = bootSec();
        s.uptimeS = SystemClock.elapsedRealtime() / 1000L;
        s.wallMs = ForkDiag.getLong(ctx, K_WALL, 0);
        if (s.wallMs != 0) {
            s.free = ForkDiag.getLong(ctx, K_FREE, 0);
            s.exact = ForkDiag.getLong(ctx, K_EXACT, 0) != 0;
        }
        s.sizeWallMs = ForkDiag.getLong(ctx, K_SIZE_WALL, 0);
        if (s.sizeWallMs != 0 && sameBoot(ForkDiag.getLong(ctx, K_SIZE_BOOT, 0), s.bootSec)) {
            s.sizeFree = ForkDiag.getLong(ctx, K_SIZE, 0);
            s.sizeExact = ForkDiag.getLong(ctx, K_SIZE_EXACT, 0) != 0;
        } else {
            s.sizeWallMs = 0;
        }
        for (Row row : rows(ctx, false)) {
            Reading r = new Reading();
            r.wallMs = row.wallMs;
            r.boot = row.num("boot");
            r.free = row.num("free");
            r.exact = row.num("exact") == 1;
            r.failing = row.num("failing") == 1;
            r.uptimeS = row.num("uptimeS");
            r.forks = row.num("forks");
            r.apps = row.num("apps");
            r.procs = row.num("procs");
            r.threads = row.num("threads");
            r.mounts = row.num("o.mnt_cache");
            r.vmap = row.num("o.vmap_area");
            r.kmalloc64 = row.num("o.kmalloc-64");
            r.sUnreclaimKB = row.num("m.SUnreclaimKB");
            String k = row.t.get("kind");
            if (k != null) r.kind = k;
            r.reserve = row.t.get("reserve");
            if (r.free >= 0) s.all.add(r);
        }
        for (Reading r : s.all) {
            if (r.exact && !r.failing && sameBoot(r.boot, s.bootSec) && r.free > s.fullest) {
                s.fullest = r.free;
                s.fullestWallMs = r.wallMs;
            }
        }
        if (s.sizeFree > s.fullest) {
            s.fullest = s.sizeFree;
            s.fullestWallMs = s.sizeWallMs;
        }
        // Bursts: episode-start … episode-end in the process-start recording, with the fixes between.
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        Burst open = null;
        for (String line : ForkDiag.readLines(ctx, ForkDiag.KIND_FORKSTART, 60_000)) {
            String[] parts = line.split("\t");
            if (parts.length < 2) continue;
            String kind = parts[1];
            if (!"episode-start".equals(kind) && !"episode-end".equals(kind) && !"fix".equals(kind)) continue;
            long wall;
            try {
                Date d = fmt.parse(parts[0]);
                if (d == null) continue;
                wall = d.getTime();
            } catch (Throwable t) {
                continue;
            }
            if ("episode-start".equals(kind)) {
                open = new Burst();
                open.startMs = wall;
                s.bursts.add(open);
            } else if (open != null && parts.length >= 3) {
                Map<String, String> t = new HashMap<>();
                for (String tok : parts[2].split(" ")) {
                    int eq = tok.indexOf('=');
                    if (eq > 0) t.put(tok.substring(0, eq), tok.substring(eq + 1));
                }
                try {
                    if ("fix".equals(kind)) {
                        if (t.get("apps") != null) open.appsEnded += Integer.parseInt(t.get("apps"));
                        if ("ok".equals(t.get("probe"))) open.cured = true;
                    } else {
                        open.endMs = wall;
                        if (t.get("logFailures") != null) open.failures = Integer.parseInt(t.get("logFailures"));
                        if (t.get("probesFailed") != null) open.probesFailed = Integer.parseInt(t.get("probesFailed"));
                        open = null;
                    }
                } catch (NumberFormatException ignore) {
                }
            }
        }
        // An episode that ended without a single failed probe was one stray failure, not a burst.
        for (int i = s.bursts.size() - 1; i >= 0; i--) {
            Burst b = s.bursts.get(i);
            if (b.endMs != 0 && b.probesFailed == 0 && b.endMs - b.startMs < 60_000L) s.bursts.remove(i);
        }
        return s;
    }

    /** 0 = just read, 1 = measure now, 2 = pool size, 3 = churn test; then the summary. */
    @NonNull
    public static Summary run(@NonNull Context context, int what) {
        Context ctx = context.getApplicationContext();
        String headline = null;
        if (what == 1) {
            // Counting to the end empties the pool for a moment: not twice within ten minutes.
            long age = System.currentTimeMillis() - ForkDiag.getLong(ctx, K_WALL, 0);
            if (ForkDiag.getLong(ctx, K_EXACT, 0) != 0 && age >= 0 && age < REMEASURE_MS) {
                headline = ctx.getString(R.string.pool_recent, Math.max(1, age / 60_000L));
            } else {
                measure(ctx, "deep", DEEP_CAP);
            }
        } else if (what == 2) measure(ctx, "size", SIZE_CAP);
        else if (what == 3) {
            String text = churnTest(ctx);
            int cut = text.indexOf("\n\n");
            headline = cut > 0 ? text.substring(0, cut) : text;
        }
        Summary s = summary(ctx);
        s.headline = headline;
        return s;
    }
}
