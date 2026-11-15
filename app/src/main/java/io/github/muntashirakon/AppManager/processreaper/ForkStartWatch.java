// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.processreaper;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.muntashirakon.AppManager.diag.ForkDiag;
import io.github.muntashirakon.AppManager.runner.Runner;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;

/**
 * Fork (白い熊, 2026-11-15): watches for the phone-wide "new processes cannot
 * start" failure. On the Mate XT after 62 days of uptime, freshly forked zygote
 * children aborted with
 * {@code seccomp_policy.cpp] Could not set seccomp filter of size 224: Out of memory}
 * and WebView renderers with {@code Kernel refuses to turn on BPF filters},
 * which takes the host app down with them — while ordinary memory was not
 * exhausted. It comes in bursts of minutes; killing the cached background
 * processes ended a live burst at once (11:50, 2026-11-15), but which kernel
 * allocation fails is unproven.
 *
 * <p>One instance for the process ({@link #get()}): the monitor's view model and
 * the background {@link ForkWatchService} both tick it, so the log position, the
 * episode and the pre-trigger buffer are shared. Two signals: a scan of the
 * F-level log lines (they survive EMUI's filtering; the app holds
 * {@code READ_LOGS}, so this works without the privileged server too), and a real
 * fork probe ({@link ForkProbeService}, an isolated and an ordinary process side
 * by side). The spare-process pool count is recorded but is <b>not</b> a signal:
 * seven {@code usap64} were alive in the middle of a burst.
 *
 * <p>With the {@code forkstart} diagnostics switch on it also records what is
 * needed to find the cause: a pre-trigger buffer, full kernel-counter snapshots
 * with differences, process and thread counts, memory-cgroup fail counters,
 * repeated probes, log context and kernel-thread CPU — see {@link #sample} and
 * {@link #takeSnap}.
 */
final class ForkStartWatch {
    static final int STATUS_OK = 0;
    /** Failures in the last {@link #RECENT_MS}, but starts work now. */
    static final int STATUS_RECENT = 1;
    static final int STATUS_FAILING = 2;

    /** A failure this recent keeps the episode open. */
    private static final long ACTIVE_MS = 90_000L;
    private static final long RECENT_MS = 30 * 60_000L;
    private static final long LOG_TTL_MS = 10_000L;
    /** While an episode is open, probe this often: the pass rate says whether every start fails. */
    private static final long EPISODE_PROBE_GAP_MS = 15_000L;
    /** In the background, probe this often even when the log is quiet. */
    private static final long HEARTBEAT_MS = 5 * 60_000L;
    private static final long PROBE_TIMEOUT_MS = 8_000L;
    private static final long KEEP_EVENTS_MS = 24 * 3_600_000L;
    private static final int MAX_EVENTS = 20_000;
    private static final long SAMPLE_MS = 30_000L;
    private static final long QUIET_SAMPLE_LINE_MS = 600_000L;
    private static final long PRE_KEEP_MS = 600_000L;
    private static final long BASELINE_MS = 600_000L;
    private static final int CONTEXT_MAX_LINES = 60;

    private static final ForkStartWatch INSTANCE = new ForkStartWatch();

    @NonNull
    static ForkStartWatch get() {
        return INSTANCE;
    }

    private ForkStartWatch() {
    }

    static final class Probe {
        final long wallMs;
        /** Both test processes started. */
        final boolean ok;
        /** The slower of the two, for display. */
        final long tookMs;
        /** The isolated (sandboxed) process — the path a WebView renderer takes. */
        final boolean isoOk;
        final long isoMs;
        /** An ordinary process of this app — the path any app start takes. */
        final boolean plainOk;
        final long plainMs;

        Probe(long wallMs, boolean isoOk, long isoMs, boolean plainOk, long plainMs) {
            this.wallMs = wallMs;
            this.ok = isoOk && plainOk;
            this.tookMs = Math.max(isoMs, plainMs);
            this.isoOk = isoOk;
            this.isoMs = isoMs;
            this.plainOk = plainOk;
            this.plainMs = plainMs;
        }
    }

    /** One immutable reading, for the header and the panel. */
    static final class Snapshot {
        final long wallMs;
        final int status;
        /** An episode is open: a failed start or a failed probe in the last 90 s. */
        final boolean active;
        final int usap;
        final boolean poolSeen;
        /** False until a log scan has run. */
        final boolean logScanned;
        final int failures5Min;
        final int failures1Hour;
        final int failuresTotal;
        final long firstFailureMs;
        final long lastFailureMs;
        /** "label  HH:mm:ss" of apps a renderer failure took down, newest last. */
        @NonNull
        final List<String> crashedApps;
        final long memFreeBytes;
        final long memAvailableBytes;
        final long slabUnreclaimBytes;
        /** Growth of unreclaimable slab since the watch first ran. */
        final long slabUnreclaimDeltaBytes;
        /** Size of the kmalloc-64 cache, or -1 when unreadable. */
        final long kmalloc64Bytes;
        @Nullable
        final Probe lastProbe;
        /** Processes, threads and cached processes from the last sample, or -1 when not sampled. */
        final int procs, threads, cached;

        Snapshot(long wallMs, int status, boolean active, int usap, boolean poolSeen, boolean logScanned,
                 int failures5Min, int failures1Hour, int failuresTotal, long firstFailureMs, long lastFailureMs,
                 @NonNull List<String> crashedApps, long memFreeBytes, long memAvailableBytes,
                 long slabUnreclaimBytes, long slabUnreclaimDeltaBytes, long kmalloc64Bytes,
                 @Nullable Probe lastProbe, int procs, int threads, int cached) {
            this.wallMs = wallMs;
            this.status = status;
            this.active = active;
            this.usap = usap;
            this.poolSeen = poolSeen;
            this.logScanned = logScanned;
            this.failures5Min = failures5Min;
            this.failures1Hour = failures1Hour;
            this.failuresTotal = failuresTotal;
            this.firstFailureMs = firstFailureMs;
            this.lastFailureMs = lastFailureMs;
            this.crashedApps = crashedApps;
            this.memFreeBytes = memFreeBytes;
            this.memAvailableBytes = memAvailableBytes;
            this.slabUnreclaimBytes = slabUnreclaimBytes;
            this.slabUnreclaimDeltaBytes = slabUnreclaimDeltaBytes;
            this.kmalloc64Bytes = kmalloc64Bytes;
            this.lastProbe = lastProbe;
            this.procs = procs;
            this.threads = threads;
            this.cached = cached;
        }
    }

    private final Object mLock = new Object();
    // Wall-clock times of failed process starts found in the log, oldest first.
    private final ArrayDeque<Long> mFailures = new ArrayDeque<>();
    private final LinkedHashMap<String, Long> mCrashed = new LinkedHashMap<>();
    private long mLastSeenLogMs;
    private volatile boolean mLogScanned;
    private volatile long mLogScanMs;
    private final AtomicBoolean mLogBusy = new AtomicBoolean(false);
    private volatile long mKmalloc64 = -1;

    private volatile int mUsap = -1;
    private long mSlabBaseline = -1;
    private volatile boolean mBackground;

    @Nullable
    private volatile Probe mLastProbe;
    private volatile long mLastProbeEl;
    private volatile long mLastFailedProbeWall;
    private final Object mProbeLock = new Object();
    private final AtomicBoolean mAutoProbeBusy = new AtomicBoolean(false);

    // The episode (guarded by the tick's own monitor, counters atomic because the
    // probe and the log scan run on other threads).
    private boolean mWasActive;
    private long mEpisodeStartEl;
    private final AtomicInteger mEpProbesOk = new AtomicInteger();
    private final AtomicInteger mEpProbesFailed = new AtomicInteger();
    private final AtomicInteger mEpLogFailures = new AtomicInteger();
    private final AtomicInteger mEpContextDumps = new AtomicInteger();
    private volatile long mLastContextEl;
    private volatile boolean mActive;

    // Rich samples: the pre-trigger buffer.
    private static final class Sample {
        final long wallMs;
        final String line;
        boolean written;

        Sample(long wallMs, String line) {
            this.wallMs = wallMs;
            this.line = line;
        }
    }

    private final ArrayDeque<Sample> mSamples = new ArrayDeque<>();
    private final AtomicBoolean mSampleBusy = new AtomicBoolean(false);
    private volatile long mSampleEl;
    private long mLastQuietLineEl;
    private Map<Integer, Long> mPrevTicks;
    private long mPrevTicksEl;
    private volatile int mProcs = -1, mThreads = -1, mCached = -1;

    // Kernel-counter snapshots.
    @Nullable
    private volatile Snap mBaseline;
    @Nullable
    private volatile Snap mEpisodeSnap;

    @Nullable
    Probe lastProbe() {
        return mLastProbe;
    }

    /** An episode is open: a failed start or a failed probe in the last 90 s. */
    boolean isActive() {
        return mActive;
    }

    /** The background service is ticking: probe on a slow heartbeat even while the log is quiet. */
    void setBackground(boolean background) {
        mBackground = background;
    }

    /**
     * One refresh: read the memory figures, keep the log scan, the sampling and
     * the automatic probe going on their own threads, and return the current
     * reading. {@code usapCount} is the spare-pool count when the caller already
     * has it from its own {@code /proc} batch, or -1.
     */
    @NonNull
    synchronized Snapshot tick(@NonNull Context context, int usapCount) {
        final Context ctx = context.getApplicationContext();
        long nowEl = SystemClock.elapsedRealtime();
        long nowWall = System.currentTimeMillis();
        if (usapCount >= 0) mUsap = usapCount;

        long[] mem = readMeminfo();  // {free, available, sunreclaim} bytes
        if (mSlabBaseline < 0 && mem[2] > 0) mSlabBaseline = mem[2];

        maybeScanLog(ctx);
        boolean diagOn = ForkDiag.isOn(ctx, ForkDiag.KIND_FORKSTART);
        if (diagOn) maybeSample(ctx);
        KernelMemWatch.get().maybeRecord(ctx);
        FilterPool.maybeMeasure(ctx);

        int f5 = 0, f60 = 0, total;
        long first = 0, last = 0;
        List<String> crashed = new ArrayList<>();
        synchronized (mLock) {
            total = mFailures.size();
            for (Long t : mFailures) {
                if (first == 0) first = t;
                last = t;
                if (nowWall - t <= 5 * 60_000L) f5++;
                if (nowWall - t <= 3_600_000L) f60++;
            }
            SimpleDateFormat hms = new SimpleDateFormat("HH:mm:ss", Locale.US);
            for (Map.Entry<String, Long> e : mCrashed.entrySet()) {
                if (nowWall - e.getValue() <= 6 * 3_600_000L) {
                    crashed.add(e.getKey() + "  " + hms.format(new Date(e.getValue())));
                }
            }
        }

        Probe probe = mLastProbe;
        long failedProbe = mLastFailedProbeWall;
        boolean logRecent = last != 0 && nowWall - last <= ACTIVE_MS;
        boolean probeFailedRecent = failedProbe != 0 && nowWall - failedProbe <= ACTIVE_MS;
        boolean active = logRecent || probeFailedRecent;
        boolean lastProbeFailed = probe != null && !probe.ok && nowWall - probe.wallMs <= ACTIVE_MS;
        boolean probeOkSince = probe != null && probe.ok && probe.wallMs > last;
        long lastAny = Math.max(last, failedProbe);
        int status;
        if (lastProbeFailed || (logRecent && !probeOkSince)) status = STATUS_FAILING;
        else if (lastAny != 0 && nowWall - lastAny <= RECENT_MS) status = STATUS_RECENT;
        else status = STATUS_OK;
        mActive = active;

        // While an episode is open, keep probing: single log lines may be the system
        // retrying one service, and only the pass rate says whether every start fails.
        long gap = active ? EPISODE_PROBE_GAP_MS : mBackground ? HEARTBEAT_MS : Long.MAX_VALUE;
        if (gap != Long.MAX_VALUE && nowEl - mLastProbeEl >= gap && mAutoProbeBusy.compareAndSet(false, true)) {
            final String why = active ? "episode" : "heartbeat";
            ThreadUtils.postOnBackgroundThread(() -> {
                try {
                    probe(ctx, why);
                } finally {
                    mAutoProbeBusy.set(false);
                }
            });
        }

        Snapshot s = new Snapshot(nowWall, status, active, Math.max(mUsap, 0), mUsap >= 0, mLogScanned, f5, f60,
                total, first, last, crashed, mem[0], mem[1], mem[2],
                mSlabBaseline > 0 ? mem[2] - mSlabBaseline : 0, mKmalloc64, probe, mProcs, mThreads, mCached);
        if (diagOn) record(ctx, s, nowEl);
        mWasActive = active;
        return s;
    }

    /** Diagnostics: the episode's start (with the pre-trigger buffer and a full snapshot) and its end. */
    private void record(@NonNull Context ctx, @NonNull Snapshot s, long nowEl) {
        if (s.active && !mWasActive) {
            mEpisodeStartEl = nowEl;
            mEpProbesOk.set(0);
            mEpProbesFailed.set(0);
            mEpLogFailures.set(0);
            mEpContextDumps.set(0);
            diag(ctx, "episode-start\t" + figures(s));
            // What the phone looked like in the ten minutes before the first failure.
            SimpleDateFormat hms = new SimpleDateFormat("HH:mm:ss", Locale.US);
            synchronized (mSamples) {
                for (Sample sm : mSamples) {
                    if (sm.written) continue;
                    sm.written = true;
                    diag(ctx, "pre\t" + hms.format(new Date(sm.wallMs)) + "\t" + sm.line);
                }
            }
            final Snap base = mBaseline;
            ThreadUtils.postOnBackgroundThread(() -> {
                Snap now = takeSnap();
                mEpisodeSnap = now;
                writeSnapFull(ctx, "episode-start", now);
                if (base != null) writeSnapDiff(ctx, "episode-start vs baseline", base, now);
            });
        } else if (!s.active && mWasActive) {
            int ok = mEpProbesOk.get(), failed = mEpProbesFailed.get();
            diag(ctx, "episode-end\tafter=" + (nowEl - mEpisodeStartEl) / 1000 + "s logFailures=" + mEpLogFailures.get()
                    + " probesOk=" + ok + " probesFailed=" + failed + "\t" + figures(s));
            final Snap start = mEpisodeSnap;
            ThreadUtils.postOnBackgroundThread(() -> {
                Snap now = takeSnap();
                if (start != null) writeSnapDiff(ctx, "episode-end vs episode-start", start, now);
                mBaseline = now;
            });
        }
    }

    @NonNull
    static String figures(@NonNull Snapshot s) {
        return "status=" + s.status + " usap=" + (s.poolSeen ? s.usap : -1) + " fail5m=" + s.failures5Min
                + " fail1h=" + s.failures1Hour
                + " memFreeMB=" + (s.memFreeBytes >> 20) + " memAvailMB=" + (s.memAvailableBytes >> 20)
                + " sUnreclaimMB=" + (s.slabUnreclaimBytes >> 20)
                + " kmalloc64MB=" + (s.kmalloc64Bytes < 0 ? -1 : s.kmalloc64Bytes >> 20)
                + " procs=" + s.procs + " threads=" + s.threads + " cached=" + s.cached;
    }

    static void diag(@NonNull Context ctx, @NonNull String line) {
        ForkDiag.write(ctx, ForkDiag.KIND_FORKSTART, line);
    }

    // ---- The fork probe -------------------------------------------------------

    /** One of the two test processes of a probe. */
    private static final class Bind implements ServiceConnection {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean connected;
        volatile long doneEl;
        boolean bound;

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            doneEl = SystemClock.elapsedRealtime();
            connected = true;
            latch.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }

        @Override
        public void onNullBinding(ComponentName name) {
            doneEl = SystemClock.elapsedRealtime();
            latch.countDown();
        }

        void bind(@NonNull Context ctx, @NonNull Class<?> service) {
            try {
                bound = ctx.bindService(new Intent(ctx, service), this, Context.BIND_AUTO_CREATE);
            } catch (Throwable ignore) {
            }
        }

        void await(long deadlineEl) {
            if (!bound) return;
            try {
                long left = deadlineEl - SystemClock.elapsedRealtime();
                if (left > 0) latch.await(left, TimeUnit.MILLISECONDS);
            } catch (Throwable ignore) {
            }
        }

        void unbind(@NonNull Context ctx) {
            try {
                // Also after a failed bind: the system keeps the connection record otherwise.
                ctx.unbindService(this);
            } catch (Throwable ignore) {
            }
        }

        long ms(long t0) {
            return (connected ? doneEl : SystemClock.elapsedRealtime()) - t0;
        }
    }

    /** Counts probes, so the two processes take turns at being started first. */
    private int mProbeSeq;

    /**
     * Start two fresh processes side by side — an isolated one and an ordinary
     * one — and wait for each to answer. Blocks for up to
     * {@link #PROBE_TIMEOUT_MS}: never call on the main thread (the connection
     * callbacks arrive there).
     */
    @NonNull
    Probe probe(@NonNull Context context, @NonNull String reason) {
        final Context ctx = context.getApplicationContext();
        synchronized (mProbeLock) {
            waitForOldProbeProcess(ctx);
            Bind iso = new Bind(), plain = new Bind();
            long t0 = SystemClock.elapsedRealtime();
            // Fork (+101): the two take turns at going first. In the 2026-11-15 burst the isolated
            // one, always bound first, started 16 times of 17 and the ordinary one never — the
            // order tells "whoever asks first gets the one free slot" from "ordinary processes fail".
            boolean plainFirst = (mProbeSeq++ & 1) == 1;
            if (plainFirst) {
                plain.bind(ctx, ForkProbeService.Plain.class);
                iso.bind(ctx, ForkProbeService.class);
            } else {
                iso.bind(ctx, ForkProbeService.class);
                plain.bind(ctx, ForkProbeService.Plain.class);
            }
            long deadline = t0 + PROBE_TIMEOUT_MS;
            iso.await(deadline);
            plain.await(deadline);
            long isoMs = iso.ms(t0), plainMs = plain.ms(t0);
            iso.unbind(ctx);
            plain.unbind(ctx);
            Probe p = new Probe(System.currentTimeMillis(), iso.bound && iso.connected, isoMs,
                    plain.bound && plain.connected, plainMs);
            mLastProbe = p;
            mLastProbeEl = SystemClock.elapsedRealtime();
            if (!p.ok) mLastFailedProbeWall = p.wallMs;
            if (mActive) (p.ok ? mEpProbesOk : mEpProbesFailed).incrementAndGet();
            long[] mem = readMeminfo();
            diag(ctx, "probe\t" + (p.ok ? "ok" : "FAILED") + " isolated=" + (p.isoOk ? "ok" : "FAILED") + "/" + isoMs
                    + "ms ordinary=" + (p.plainOk ? "ok" : "FAILED") + "/" + plainMs + "ms first=" + (plainFirst ? "ordinary" : "isolated") + " reason=" + reason
                    + " memFreeMB=" + (mem[0] >> 20) + " memAvailMB=" + (mem[1] >> 20));
            // Fork (+102): the same kernel call from a child of this app, beside every probe.
            {
                String k = KernelProbe.run(ctx, !p.ok || mActive || !"heartbeat".equals(reason));
                if (!k.isEmpty()) diag(ctx, "kprobe\t" + k + " probe=" + (p.ok ? "ok" : "FAILED") + " reason=" + reason);
            }
            return p;
        }
    }

    /** The last probe's processes kill themselves when unbound; give them a moment to be gone. */
    private static void waitForOldProbeProcess(@NonNull Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            for (int i = 0; i < 20; i++) {
                boolean alive = false;
                List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
                if (procs != null) {
                    for (ActivityManager.RunningAppProcessInfo p : procs) {
                        if (p.processName != null && p.processName.contains(":forkprobe")) {
                            alive = true;
                            break;
                        }
                    }
                }
                if (!alive) return;
                Thread.sleep(100);
            }
        } catch (Throwable ignore) {
        }
    }

    // ---- The log scan ---------------------------------------------------------

    private void maybeScanLog(@NonNull Context ctx) {
        long now = SystemClock.elapsedRealtime();
        if (mLogScanMs != 0 && now - mLogScanMs < LOG_TTL_MS) return;
        if (!mLogBusy.compareAndSet(false, true)) return;
        mLogScanMs = now;
        ThreadUtils.postOnBackgroundThread(() -> {
            try {
                scanLog(ctx);
                readKmalloc64();
            } catch (Throwable ignore) {
            } finally {
                mLogBusy.set(false);
            }
        });
    }

    /** Wall time of a {@code threadtime} log line, or -1. The log carries no year. */
    private static long logTime(@NonNull String line, @NonNull SimpleDateFormat fmt, int year, long now) {
        if (line.length() < 30 || line.charAt(2) != '-') return -1;
        try {
            Date d = fmt.parse(year + "-" + line.substring(0, 18));
            if (d == null) return -1;
            long t = d.getTime();
            // A line "in the future" is from last December.
            if (t > now + 86_400_000L) {
                d = fmt.parse((year - 1) + "-" + line.substring(0, 18));
                if (d == null) return -1;
                t = d.getTime();
            }
            return t;
        } catch (Throwable e) {
            return -1;
        }
    }

    /**
     * Read the F-level zygote and crash-dump lines since the last scan. The first
     * scan takes the whole buffer, so an episode that happened before the watch
     * started is still found.
     */
    private void scanLog(@NonNull Context ctx) {
        long since;
        synchronized (mLock) {
            since = mLastSeenLogMs;
        }
        long scanStart = System.currentTimeMillis();
        String from = since == 0 ? "" : " -T '" + new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date(since - 2_000L)) + "'";
        Runner.Result r = Runner.runCommand("logcat -d -b main,crash -v threadtime" + from
                + " -s zygote64:F zygote:F DEBUG:F 2>/dev/null"
                + " | grep -E 'Could not set seccomp|Kernel refuses to turn on BPF|handled by all associated|>>> '"
                + "; true");
        if (r == null) return;
        Map<String, String> nameByLogPid = new HashMap<>();
        List<Long> found = new ArrayList<>();
        Map<String, Long> crashed = new LinkedHashMap<>();
        long newest = since;
        int year = Calendar.getInstance().get(Calendar.YEAR);
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        for (String line : r.getOutputAsList()) {
            long t = logTime(line, fmt, year, scanStart);
            if (t < 0) continue;
            String[] f = line.substring(18).trim().split("\\s+", 3);
            String logPid = f.length > 0 ? f[0] : "";
            int nameAt = line.indexOf(">>> ");
            if (nameAt >= 0) {
                int end = line.indexOf(" <<<", nameAt);
                if (end > nameAt) nameByLogPid.put(logPid, line.substring(nameAt + 4, end).trim());
                continue;
            }
            if (t <= since) continue;  // the 2 s overlap re-reads lines already counted
            if (t > newest) newest = t;
            if (line.contains("Could not set seccomp")) {
                // The crash dump repeats it as an "Abort message"; count zygote's own line only.
                if (!line.contains(" DEBUG ")) found.add(t);
            } else if (line.contains("Kernel refuses to turn on BPF")) {
                found.add(t);
            } else if (line.contains("handled by all associated")) {
                String name = nameByLogPid.get(logPid);
                crashed.put(name != null ? name : "?", t);
            }
        }
        synchronized (mLock) {
            mFailures.addAll(found);
            mCrashed.putAll(crashed);
            long cutoff = System.currentTimeMillis() - KEEP_EVENTS_MS;
            while (!mFailures.isEmpty() && (mFailures.peekFirst() < cutoff || mFailures.size() > MAX_EVENTS)) {
                mFailures.pollFirst();
            }
            // Nothing matched: still move on, or every scan would re-read the whole buffer.
            mLastSeenLogMs = Math.max(newest, since == 0 ? scanStart - 2_000L : since);
            if (found.isEmpty() && crashed.isEmpty() && since != 0) mLastSeenLogMs = Math.max(mLastSeenLogMs, scanStart - 2_000L);
        }
        mLogScanned = true;
        if (!found.isEmpty() || !crashed.isEmpty()) {
            Collections.sort(found);
            SimpleDateFormat hms = new SimpleDateFormat("HH:mm:ss", Locale.US);
            StringBuilder sb = new StringBuilder("log\tfailures=+").append(found.size());
            if (!found.isEmpty()) {
                sb.append(" first=").append(hms.format(new Date(found.get(0))))
                        .append(" last=").append(hms.format(new Date(found.get(found.size() - 1))));
            }
            for (Map.Entry<String, Long> e : crashed.entrySet()) {
                sb.append(" crashed=").append(e.getKey()).append('@').append(hms.format(new Date(e.getValue())));
            }
            diag(ctx, sb.toString());
        }
        // Not for the first scan: those failures are history, and their context has long scrolled away.
        if (since != 0 && !found.isEmpty()) {
            mEpLogFailures.addAndGet(found.size());
            if (ForkDiag.isOn(ctx, ForkDiag.KIND_FORKSTART)) maybeDumpContext(ctx, found);
        }
    }

    /**
     * Everything else the log kept within two seconds of a failed start — the
     * low-memory killer, Huawei's memory manager, whatever was starting. The first
     * three failures of an episode, then at most one a minute.
     */
    private void maybeDumpContext(@NonNull Context ctx, @NonNull List<Long> found) {
        long nowEl = SystemClock.elapsedRealtime();
        if (mEpContextDumps.get() >= 3 && nowEl - mLastContextEl < 60_000L) return;
        mEpContextDumps.incrementAndGet();
        mLastContextEl = nowEl;
        long first = found.get(0);
        long now = System.currentTimeMillis();
        Runner.Result r = Runner.runCommand("logcat -d -b main,system,crash -v threadtime -T '"
                + new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(first - 2_000L))
                + "' 2>/dev/null | grep -v ' DEBUG ' | head -n 800; true");
        if (r == null) return;
        int year = Calendar.getInstance().get(Calendar.YEAR);
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        int n = 0;
        for (String line : r.getOutputAsList()) {
            long t = logTime(line, fmt, year, now);
            if (t < 0) continue;
            boolean near = false;
            for (Long f : found) {
                if (Math.abs(t - f) <= 2_000L) {
                    near = true;
                    break;
                }
            }
            if (!near) continue;
            diag(ctx, "ctx\t" + line);
            if (++n >= CONTEXT_MAX_LINES) break;
        }
    }

    /** {@code /proc/slabinfo} is readable by the shell only; the leak showed up as 5.2 million kmalloc-64 objects. */
    private void readKmalloc64() {
        Runner.Result r = Runner.runCommand("grep '^kmalloc-64 ' /proc/slabinfo 2>/dev/null; true");
        if (r == null) return;
        for (String line : r.getOutputAsList()) {
            String[] f = line.trim().split("\\s+");
            if (f.length >= 4 && "kmalloc-64".equals(f[0])) {
                try {
                    mKmalloc64 = Long.parseLong(f[2]) * Long.parseLong(f[3]);
                } catch (NumberFormatException ignore) {
                }
            }
        }
    }

    // ---- Rich samples: counts, kernel-thread CPU, the pre-trigger buffer ----------

    private void maybeSample(@NonNull Context ctx) {
        long now = SystemClock.elapsedRealtime();
        if (mSampleEl != 0 && now - mSampleEl < SAMPLE_MS) return;
        if (!mSampleBusy.compareAndSet(false, true)) return;
        mSampleEl = now;
        ThreadUtils.postOnBackgroundThread(() -> {
            try {
                sample(ctx);
                // A reference for the episode-start difference, refreshed while all is quiet.
                Snap base = mBaseline;
                if (!mActive && (base == null || System.currentTimeMillis() - base.wallMs >= BASELINE_MS)) {
                    mBaseline = takeSnap();
                }
            } catch (Throwable ignore) {
            } finally {
                mSampleBusy.set(false);
            }
        });
    }

    /**
     * One rich sample: how many processes, threads, kernel threads, zygote
     * children and cached processes there are (is the limit per process rather
     * than memory?), the wider memory figures, and which kernel threads and
     * which processes burned CPU since the last sample (is a periodic job behind
     * the bursts?). Kept for ten minutes in memory and written out when an
     * episode starts; written directly every 30 s while one is open.
     */
    private void sample(@NonNull Context ctx) {
        // The zygotes are found by name through pidof: zygote64's comm is "main", not its name.
        Runner.Result r = Runner.runCommand("echo \"@@z64 $(pidof zygote64)\"; echo \"@@z32 $(pidof zygote)\";"
                + " echo \"@@zweb $(pidof webview_zygote)\"; cat /proc/[0-9]*/stat 2>/dev/null; echo @@lru;"
                + " dumpsys activity lru 2>/dev/null | grep -c -E '#[0-9]+: +cch'; true");
        if (r == null) return;
        long nowEl = SystemClock.elapsedRealtime();
        List<int[]> rows = new ArrayList<>(900);       // {pid, ppid, threads}
        Map<Integer, String> comm = new HashMap<>(900);
        Map<Integer, Long> ticks = new HashMap<>(900);
        Set<Integer> z64 = new HashSet<>(), z32 = new HashSet<>(), zweb = new HashSet<>();
        int usap = 0, cached = -1;
        boolean lru = false;
        for (String line : r.getOutputAsList()) {
            if (lru) {
                try {
                    cached = Integer.parseInt(line.trim());
                } catch (NumberFormatException ignore) {
                }
                continue;
            }
            if (line.startsWith("@@lru")) {
                lru = true;
                continue;
            }
            if (line.startsWith("@@z")) {
                String[] z = line.trim().split("\\s+");
                Set<Integer> into = "@@z64".equals(z[0]) ? z64 : "@@z32".equals(z[0]) ? z32 : zweb;
                for (int i = 1; i < z.length; i++) {
                    try {
                        into.add(Integer.parseInt(z[i]));
                    } catch (NumberFormatException ignore) {
                    }
                }
                continue;
            }
            int open = line.indexOf('(');
            int close = line.lastIndexOf(')');
            if (open < 0 || close < open) continue;
            // Fields after "pid (comm)": [0]=state, [1]=ppid, [11]=utime, [12]=stime, [17]=num_threads.
            String[] f = line.substring(close + 1).trim().split("\\s+");
            if (f.length < 18) continue;
            try {
                int pid = Integer.parseInt(line.substring(0, open).trim());
                String c = line.substring(open + 1, close).replace(' ', '_');
                rows.add(new int[]{pid, Integer.parseInt(f[1]), Integer.parseInt(f[17])});
                comm.put(pid, c);
                ticks.put(pid, Long.parseLong(f[11]) + Long.parseLong(f[12]));
                if ("usap64".equals(c) || "usap32".equals(c)) usap++;
            } catch (NumberFormatException ignore) {
            }
        }
        int threads = 0, kthreads = 0, c64 = 0, c32 = 0, cweb = 0;
        List<long[]> kernel = new ArrayList<>(), user = new ArrayList<>();  // {delta ticks, pid}
        Map<Integer, Long> prev = mPrevTicks;
        for (int[] row : rows) {
            threads += row[2];
            boolean kthread = row[1] == 2;
            if (kthread) kthreads++;
            if (z64.contains(row[1])) c64++;
            else if (z32.contains(row[1])) c32++;
            else if (zweb.contains(row[1])) cweb++;
            if (prev != null) {
                Long before = prev.get(row[0]);
                Long now = ticks.get(row[0]);
                if (before != null && now != null && now > before) {
                    (kthread ? kernel : user).add(new long[]{now - before, row[0]});
                }
            }
        }
        long dt = prev == null ? 0 : (nowEl - mPrevTicksEl) / 1000;
        mPrevTicks = ticks;
        mPrevTicksEl = nowEl;
        // Without the privileged server only our own processes are visible: say so rather than lie.
        boolean whole = rows.size() > 50;
        mProcs = whole ? rows.size() : -1;
        mThreads = whole ? threads : -1;
        mCached = cached;
        if (whole) mUsap = usap;

        Map<String, Long> mi = readMeminfoAll();
        StringBuilder sb = new StringBuilder();
        sb.append("procs=").append(whole ? rows.size() : -1).append(" threads=").append(whole ? threads : -1)
                .append(" kthreads=").append(whole ? kthreads : -1)
                .append(" zygote64=").append(c64).append(" zygote32=").append(c32).append(" webviewZygote=").append(cweb)
                .append(" usap=").append(whole ? usap : -1).append(" cached=").append(cached)
                .append(" memFreeMB=").append(kb(mi, "MemFree") >> 10)
                .append(" memAvailMB=").append(kb(mi, "MemAvailable") >> 10)
                .append(" sUnreclaimMB=").append(kb(mi, "SUnreclaim") >> 10)
                .append(" vmallocMB=").append(kb(mi, "VmallocUsed") >> 10)
                .append(" kernelStackMB=").append(kb(mi, "KernelStack") >> 10)
                .append(" pageTablesMB=").append(kb(mi, "PageTables") >> 10)
                .append(" committedGB=").append(kb(mi, "Committed_AS") >> 20)
                .append(" swapFreeMB=").append(kb(mi, "SwapFree") >> 10);
        if (dt > 0) {
            sb.append(" cpuOver=").append(dt).append("s kernelTop=").append(top(kernel, comm))
                    .append(" userTop=").append(top(user, comm));
        }
        String line = sb.toString();
        Sample s = new Sample(System.currentTimeMillis(), line);
        boolean write = mActive || nowEl - mLastQuietLineEl >= QUIET_SAMPLE_LINE_MS;
        if (write) {
            s.written = true;
            mLastQuietLineEl = nowEl;
            diag(ctx, "sample\t" + line);
        }
        synchronized (mSamples) {
            mSamples.addLast(s);
            long cutoff = s.wallMs - PRE_KEEP_MS;
            while (!mSamples.isEmpty() && mSamples.peekFirst().wallMs < cutoff) mSamples.pollFirst();
        }
    }

    private static long kb(@NonNull Map<String, Long> meminfo, @NonNull String key) {
        Long v = meminfo.get(key);
        return v == null ? 0 : v;
    }

    /** The six busiest as "name:ticks" (100 ticks = 1 s of CPU). */
    @NonNull
    private static String top(@NonNull List<long[]> deltas, @NonNull Map<Integer, String> comm) {
        Collections.sort(deltas, (a, b) -> Long.compare(b[0], a[0]));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < deltas.size() && i < 6; i++) {
            if (i > 0) sb.append(',');
            sb.append(comm.get((int) deltas.get(i)[1])).append(':').append(deltas.get(i)[0]);
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    // ---- Kernel-counter snapshots ---------------------------------------------

    /** Everything readable that counts kernel memory, at one moment. */
    static final class Snap {
        final long wallMs = System.currentTimeMillis();
        /** {@code /proc/meminfo}, kB. */
        final LinkedHashMap<String, Long> meminfo = new LinkedHashMap<>();
        final LinkedHashMap<String, Long> vmstat = new LinkedHashMap<>();
        /** {@code /proc/slabinfo}: cache → bytes (objects × object size). Shell only. */
        final LinkedHashMap<String, Long> slab = new LinkedHashMap<>();
        /** {@code /proc/slabinfo}: cache → objects in use. Far finer than the bytes, which move a page at a time. */
        final LinkedHashMap<String, Long> slabObjects = new LinkedHashMap<>();
        /** Memory-cgroup usage and fail counters, plus {@code pid_max}. */
        final LinkedHashMap<String, Long> cgroup = new LinkedHashMap<>();
        String pressure = "";
    }

    private static final String SNAP_COMMAND = "echo @@mi; cat /proc/meminfo 2>/dev/null;"
            + " echo @@vs; cat /proc/vmstat 2>/dev/null;"
            + " echo @@sl; cat /proc/slabinfo 2>/dev/null;"
            + " echo @@cg; for f in memory.failcnt memory.kmem.failcnt memory.memsw.failcnt memory.usage_in_bytes"
            + " memory.kmem.usage_in_bytes memory.kmem.max_usage_in_bytes apps/memory.failcnt"
            + " apps/memory.kmem.failcnt apps/memory.usage_in_bytes apps/memory.kmem.usage_in_bytes;"
            + " do echo \"$f $(head -n 1 /dev/memcg/$f 2>/dev/null)\"; done;"
            + " echo \"pid_max $(cat /proc/sys/kernel/pid_max 2>/dev/null)\";"
            + " echo @@ps; head -n 2 /sys/fs/cgroup/memory.pressure 2>/dev/null; echo @@end; true";

    /** Reads the counters in one shell round trip. Off the main thread. */
    @NonNull
    static Snap takeSnap() {
        Snap s = new Snap();
        Runner.Result r;
        try {
            r = Runner.runCommand(SNAP_COMMAND);
        } catch (Throwable t) {
            return s;
        }
        if (r == null) return s;
        String section = "";
        StringBuilder pressure = new StringBuilder();
        for (String line : r.getOutputAsList()) {
            if (line.startsWith("@@")) {
                section = line.substring(2).trim();
                continue;
            }
            String[] f = line.trim().split("\\s+");
            try {
                switch (section) {
                    case "mi":
                        if (f.length >= 2 && f[0].endsWith(":")) {
                            s.meminfo.put(f[0].substring(0, f[0].length() - 1), Long.parseLong(f[1]));
                        }
                        break;
                    case "vs":
                        if (f.length == 2) s.vmstat.put(f[0], Long.parseLong(f[1]));
                        break;
                    case "sl":
                        // name <active_objs> <num_objs> <objsize> …; the two header lines fail to parse.
                        if (f.length >= 4) {
                            s.slab.put(f[0], Long.parseLong(f[2]) * Long.parseLong(f[3]));
                            s.slabObjects.put(f[0], Long.parseLong(f[1]));
                        }
                        break;
                    case "cg":
                        if (f.length == 2) s.cgroup.put(f[0], Long.parseLong(f[1]));
                        break;
                    case "ps":
                        if (pressure.length() > 0) pressure.append(" | ");
                        pressure.append(line.trim());
                        break;
                    default:
                }
            } catch (NumberFormatException ignore) {
            }
        }
        s.pressure = pressure.toString();
        return s;
    }

    /** Write a snapshot in full, so the differences that follow have absolute values to stand on. */
    static void writeSnapFull(@NonNull Context ctx, @NonNull String tag, @NonNull Snap s) {
        diag(ctx, "snap\t" + tag + "\tmeminfo-kB\t" + join(s.meminfo, 0, 1));
        diag(ctx, "snap\t" + tag + "\tvmstat\t" + join(s.vmstat, 0, 1));
        // Caches under 1 MB are noise in a full listing; the difference lines catch any that move.
        diag(ctx, "snap\t" + tag + "\tslab-kB\t" + (s.slab.isEmpty() ? "unreadable" : join(s.slab, 1 << 20, 1024)));
        diag(ctx, "snap\t" + tag + "\tcgroup\t" + join(s.cgroup, 0, 1));
        if (!s.pressure.isEmpty()) diag(ctx, "snap\t" + tag + "\tpressure\t" + s.pressure);
    }

    @NonNull
    private static String join(@NonNull Map<String, Long> map, long min, long divisor) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : map.entrySet()) {
            if (e.getValue() < min) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(e.getKey()).append('=').append(e.getValue() / divisor);
        }
        return sb.toString();
    }

    // Monotonic vmstat counters worth a difference; the nr_* gauges are all taken.
    private static final String[] VMSTAT_COUNTERS = {"allocstall", "compact", "pgscan", "pgsteal", "oom", "kswapd",
            "slabs_scanned", "drop_", "workingset", "pswp", "fail", "stall", "pgmajfault", "balloon", "zswap"};

    /**
     * Write what changed between two snapshots, largest change first — whatever
     * shrinks when a fix works, or grows as an episode begins, is the lead.
     */
    static void writeSnapDiff(@NonNull Context ctx, @NonNull String tag, @NonNull Snap a, @NonNull Snap b) {
        String head = "snapdiff\t" + tag + " (" + (b.wallMs - a.wallMs) / 1000 + "s)\t";
        diag(ctx, head + "meminfo-kB\t" + diff(a.meminfo, b.meminfo, 1024, 1, 60, null));
        diag(ctx, head + "vmstat\t" + diff(a.vmstat, b.vmstat, 1, 1, 60, VMSTAT_COUNTERS));
        if (!b.slab.isEmpty()) diag(ctx, head + "slab-kB\t" + diff(a.slab, b.slab, 64 << 10, 1024, 30, null));
        diag(ctx, head + "cgroup\t" + diff(a.cgroup, b.cgroup, 1, 1, 30, null));
        if (!b.pressure.isEmpty()) diag(ctx, head + "pressure\t" + b.pressure);
    }

    @NonNull
    private static String diff(@NonNull Map<String, Long> a, @NonNull Map<String, Long> b, long minAbs, long divisor,
                               int max, @Nullable String[] counters) {
        List<Map.Entry<String, Long>> changes = new ArrayList<>();
        Map<String, Long> deltas = new HashMap<>();
        for (Map.Entry<String, Long> e : b.entrySet()) {
            Long before = a.get(e.getKey());
            long d = e.getValue() - (before == null ? 0 : before);
            if (Math.abs(d) < minAbs) continue;
            if (counters != null && !e.getKey().startsWith("nr_")) {
                boolean wanted = false;
                for (String c : counters) {
                    if (e.getKey().contains(c)) {
                        wanted = true;
                        break;
                    }
                }
                if (!wanted) continue;
            }
            deltas.put(e.getKey(), d);
        }
        changes.addAll(deltas.entrySet());
        Collections.sort(changes, (x, y) -> Long.compare(Math.abs(y.getValue()), Math.abs(x.getValue())));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < changes.size() && i < max; i++) {
            if (i > 0) sb.append(' ');
            long d = changes.get(i).getValue() / divisor;
            sb.append(changes.get(i).getKey()).append('=').append(d > 0 ? "+" : "").append(d);
        }
        return sb.length() == 0 ? "no change" : sb.toString();
    }

    // ---- /proc/meminfo, in-process (world-readable) ------------------------------

    /** {MemFree, MemAvailable, SUnreclaim} in bytes; zeros when unreadable. */
    @NonNull
    static long[] readMeminfo() {
        Map<String, Long> mi = readMeminfoAll();
        return new long[]{kb(mi, "MemFree") << 10, kb(mi, "MemAvailable") << 10, kb(mi, "SUnreclaim") << 10};
    }

    /** Every line of {@code /proc/meminfo}, in kB. */
    @NonNull
    static Map<String, Long> readMeminfoAll() {
        Map<String, Long> out = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length >= 2 && f[0].endsWith(":")) {
                    try {
                        out.put(f[0].substring(0, f[0].length() - 1), Long.parseLong(f[1]));
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return out;
    }
}
