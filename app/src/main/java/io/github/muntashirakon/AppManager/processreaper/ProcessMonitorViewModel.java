// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.processreaper;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandleHidden;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.runner.Runner;
import io.github.muntashirakon.AppManager.runningapps.AppProcessItem;
import io.github.muntashirakon.AppManager.runningapps.ProcessItem;
import io.github.muntashirakon.AppManager.runningapps.ProcessParser;
import io.github.muntashirakon.AppManager.settings.Ops;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;

public class ProcessMonitorViewModel extends AndroidViewModel {
    public static final int SORT_RAM = 0;
    public static final int SORT_CPU = 1;

    private static final long CLK_TCK = 100L;

    // Transient helper commands whose repetition (PPid 1, no package) reads as a leak.
    private static final Set<String> TRANSIENT = new HashSet<>(Arrays.asList(
            "logcat", "sh", "toybox", "sleep", "tail", "inotifywait", "sed", "awk", "cat"));

    /** A displayed row — a single process, a shared-process (one pid, many packages), or a leak group. */
    public static final class Row {
        public final ProcessItem item;                  // representative
        public final ProcessClassifier.Result cls;
        public final List<Integer> pids;                // every pid this row kills
        public final int memberCount;                   // packages (shared) or pids (leak)
        public final boolean isLeak;
        public final String title;
        public final String subtitle;
        public final String meta;
        public final String ram;
        public final String cpu;
        public final double cpuPercent;                 // for sorting
        public final long memBytes;                     // for sorting

        Row(ProcessItem item, ProcessClassifier.Result cls, List<Integer> pids, int memberCount,
            boolean isLeak, String title, String subtitle, String meta, String ram, String cpu,
            double cpuPercent, long memBytes) {
            this.item = item;
            this.cls = cls;
            this.pids = pids;
            this.memberCount = memberCount;
            this.isLeak = isLeak;
            this.title = title;
            this.subtitle = subtitle;
            this.meta = meta;
            this.ram = ram;
            this.cpu = cpu;
            this.cpuPercent = cpuPercent;
            this.memBytes = memBytes;
        }
    }

    /** Intermediate per-(pid, package) entry before dedup/leak grouping. */
    private static final class Base {
        final ProcessItem item;
        final ProcessClassifier.Result cls;
        final double cpuPct;

        Base(ProcessItem item, ProcessClassifier.Result cls, double cpuPct) {
            this.item = item;
            this.cls = cls;
            this.cpuPct = cpuPct;
        }
    }

    private final MutableLiveData<List<Row>> mRows = new MutableLiveData<>();
    // Full (unfiltered) rows from the last load + the live search query. The list
    // posted to the UI is mAllRows filtered by mQuery; the query survives the 2 s
    // auto-refresh because every load re-applies it.
    private volatile List<Row> mAllRows = Collections.emptyList();
    private volatile String mQuery = "";
    // Category filter (faceted, AND across the two dimensions, OR within; 0 = unconstrained).
    // Killability facets OR-combine: a Togglable row (protected now but user-flippable
    // to killable) also matches Protected, so Killable+Togglable = all eventually-killable.
    public static final int F_KILLABLE = 1, F_PROTECTED = 2, F_TOGGLABLE = 4;    // killability dim
    public static final int F_USER = 1, F_SYSTEM = 2, F_SHELL = 4, F_LEAK = 8;  // type dim
    private volatile int mFilterKill = 0;
    private volatile int mFilterType = 0;
    private final MutableLiveData<Boolean> mLoading = new MutableLiveData<>();
    private final MutableLiveData<Pair<Row, Boolean>> mKillResult = new MutableLiveData<>();
    private final MutableLiveData<int[]> mBulkKillResult = new MutableLiveData<>();  // {ok, total}

    private final java.util.concurrent.atomic.AtomicBoolean mBusy = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final Map<String, String> mLabelCache = new HashMap<>();
    private Map<Integer, Long> mPrevTicks;
    private long mPrevSampleMs;
    // pid → ppid, harvested for free from the same /proc/*/stat batch the CPU
    // sampler reads. Used to protect our own shell subtree (see ProcessClassifier
    // .ancestryProtectedPids). Touched only on the serialized background thread.
    private volatile Map<Integer, Integer> mPpid = Collections.emptyMap();
    // pid → process start time (clock ticks since boot, /proc stat field 22),
    // from the same batch. Used for the optional leak min-age filter.
    private volatile Map<Integer, Long> mStart = Collections.emptyMap();
    // Foreground/top app + foreground-service packages, refreshed at most every
    // ACTIVE_TTL_MS (the dumpsys parse is comparatively heavy vs. the /proc tick
    // read, and foreground state changes slowly). Touched only on the serialized
    // background load thread.
    private Set<String> mActivePkgs = Collections.emptySet();
    private long mActivePkgsMs;
    private static final long ACTIVE_TTL_MS = 5_000L;
    // Per-pid PSS (proportional set size) — the *accurate* footprint, since RSS
    // double-counts shared pages. Killing a process frees roughly its PSS, so
    // it's the right number for a reaper to rank by. Non-root shell can't read
    // /proc/<pid>/smaps_rollup (ptrace-gated, EPERM cross-process), so the only
    // source is `dumpsys meminfo`, which is heavy — cached for PSS_TTL_MS, much
    // longer than the CPU tick because memory moves slowly. Touched only on the
    // serialized background load thread; rows fall back to RSS when PSS is absent.
    private Map<Integer, Long> mPss = Collections.emptyMap();
    private long mPssMs;
    private static final long PSS_TTL_MS = 15_000L;
    // Fork: opens sorted by CPU — what a reaper is opened for is "what is burning
    // the CPU right now", and RAM is the standing state you can look up any time.
    // The very first load has no previous tick sample, so every cpuPercent is NaN
    // and sort() falls through to the RAM comparator; the CPU order lands on the
    // next refresh (one interval later). Session-only, like the toolbar toggle.
    private volatile int mSort = SORT_CPU;

    public ProcessMonitorViewModel(@NonNull Application application) {
        super(application);
    }

    public MutableLiveData<List<Row>> getRows() {
        return mRows;
    }

    /** Live search filter (matches the app label / process name / package). */
    public void setQuery(@Nullable String query) {
        mQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        mRows.postValue(filterRows(mAllRows));
    }

    /** Category filter: killMask over {@code F_KILLABLE/F_PROTECTED}, typeMask over {@code F_USER/SYSTEM/SHELL/LEAK}. */
    public void setFilter(int killMask, int typeMask) {
        mFilterKill = killMask;
        mFilterType = typeMask;
        mRows.postValue(filterRows(mAllRows));
    }

    public int getFilterKill() {
        return mFilterKill;
    }

    public int getFilterType() {
        return mFilterType;
    }

    @NonNull
    private List<Row> filterRows(@NonNull List<Row> rows) {
        String q = mQuery;
        boolean hasQuery = !q.isEmpty();
        boolean hasFilter = mFilterKill != 0 || mFilterType != 0;
        if (!hasQuery && !hasFilter) return rows;
        List<Row> out = new ArrayList<>(rows.size());
        for (Row r : rows) {
            if (hasQuery) {
                String pkg = (r.item instanceof AppProcessItem)
                        ? ((AppProcessItem) r.item).packageInfo.packageName : null;
                boolean m = (r.title != null && r.title.toLowerCase(Locale.ROOT).contains(q))
                        || (r.subtitle != null && r.subtitle.toLowerCase(Locale.ROOT).contains(q))
                        || (pkg != null && pkg.toLowerCase(Locale.ROOT).contains(q));
                if (!m) continue;
            }
            if (mFilterKill != 0 && (mFilterKill & killBits(r)) == 0) continue;
            if (mFilterType != 0 && (mFilterType & typeOf(r)) == 0) continue;
            out.add(r);
        }
        return out;
    }

    /** The row's killability facets — {@code F_KILLABLE}, or {@code F_PROTECTED} (+ {@code F_TOGGLABLE} when flippable). */
    private static int killBits(@NonNull Row r) {
        if (r.cls.killable) return F_KILLABLE;
        int bits = F_PROTECTED;
        if (isTogglable(r)) bits |= F_TOGGLABLE;
        return bits;
    }

    /** Protected now, but the user can flip it to killable: a "you" mark or an overridable denylist app. */
    private static boolean isTogglable(@NonNull Row r) {
        if (r.cls.killable) return false;
        if ("you".equals(r.cls.reason)) return true;
        String pkg = (r.item instanceof AppProcessItem)
                ? ((AppProcessItem) r.item).packageInfo.packageName : null;
        return ProcessClassifier.isOverridableDenylist(pkg);
    }

    /** The row's type facet — one of {@code F_USER/F_SYSTEM/F_SHELL/F_LEAK}. */
    private static int typeOf(@NonNull Row r) {
        if (r.isLeak) return F_LEAK;
        if (r.cls.method == ProcessClassifier.METHOD_SIGKILL) return F_SHELL;  // shell-owned (uid 2000)
        if (r.item.uid < Process.FIRST_APPLICATION_UID) return F_SYSTEM;       // system/native
        ApplicationInfo ai = (r.item instanceof AppProcessItem)
                ? ((AppProcessItem) r.item).packageInfo.applicationInfo : null;
        if (ai != null && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return F_SYSTEM;  // system app
        return F_USER;
    }

    public MutableLiveData<Boolean> getLoading() {
        return mLoading;
    }

    public MutableLiveData<Pair<Row, Boolean>> getKillResult() {
        return mKillResult;
    }

    public MutableLiveData<int[]> getBulkKillResult() {
        return mBulkKillResult;
    }

    public int getSort() {
        return mSort;
    }

    public void setSort(int sort) {
        mSort = sort;
    }

    public void load() {
        if (!mBusy.compareAndSet(false, true)) {
            return;
        }
        mLoading.postValue(true);
        ThreadUtils.postOnBackgroundThread(() -> {
            try {
                Context ctx = getApplication();
                PackageManager pm = ctx.getPackageManager();
                String selfPkg = BuildConfig.APPLICATION_ID;
                String ime = activeImePackage();
                boolean root = Ops.isWorkingUidRoot();
                Set<String> userProtected = ReaperPrefs.getProtectedPackages(ctx);
                Set<String> allowedOverride = ReaperPrefs.getAllowedPackages(ctx);
                Set<String> active = activePackages();
                pssMemory();  // refresh mPss (cached); row builders read it below

                // Enumerate FIRST, then read /proc/*/stat — the stat snapshot both
                // supplies CPU ticks + PPid and acts as a liveness filter. Our own
                // command pipeline (ProcessParser's `ps`, our `cat`/`sed`/`sh`)
                // spawns transient helpers that land in the enumeration but die
                // milliseconds later; they share the names in TRANSIENT (sh/cat/…)
                // so they'd masquerade as leaks. Dropping any pid absent from the
                // (later) stat read removes those ghosts; the persistent server
                // shell that survives is caught by ancestry below.
                List<ProcessItem> procsRaw = ProcessParser.getRunningProcessList();
                Map<Integer, Long> ticks = sampleCpuTicks();  // also refreshes mPpid
                long nowMs = SystemClock.elapsedRealtime();
                Map<Integer, Long> prev = mPrevTicks;
                double dtSec = (mPrevSampleMs > 0) ? (nowMs - mPrevSampleMs) / 1000.0 : 0;

                List<ProcessItem> procs;
                if (ticks.isEmpty()) {
                    procs = procsRaw;  // stat read failed — don't filter everything out
                } else {
                    procs = new ArrayList<>(procsRaw.size());
                    for (ProcessItem p : procsRaw) {
                        if (ticks.containsKey(p.pid)) procs.add(p);  // still alive
                    }
                }

                // 1. Classify every per-(pid, package) entry. Our own shell subtree
                //    (the privileged server + the shells it runs commands through)
                //    is protected by ancestry so the reaper can't sever its own kill.
                Set<Integer> seedRoots = grepOurRootPids();
                Set<Integer> ancestryProtected =
                        ProcessClassifier.ancestryProtectedPids(procs, mPpid, seedRoots, selfPkg);
                List<Base> base = new ArrayList<>();
                for (ProcessItem p : procs) {
                    ProcessClassifier.Result cls = ProcessClassifier.classify(
                            p, selfPkg, ime, root, userProtected, active, ancestryProtected, allowedOverride);
                    base.add(new Base(p, cls, cpuPercentFor(p, prev, ticks, dtSec)));
                }
                mPrevTicks = ticks;
                mPrevSampleMs = nowMs;

                // 2. Dedup by pid — a shared process (e.g. system_server) appears
                //    once per package; collapse it into a single row.
                LinkedHashMap<Integer, List<Base>> byPid = new LinkedHashMap<>();
                for (Base b : base) {
                    List<Base> list = byPid.get(b.item.pid);
                    if (list == null) {
                        list = new ArrayList<>();
                        byPid.put(b.item.pid, list);
                    }
                    list.add(b);
                }
                List<Row> rows = new ArrayList<>();
                List<Base> leakCandidates = new ArrayList<>();
                // App processes grouped by package: a multi-process app (browser main
                // + :tab/:gpu children, etc.) collapses into ONE row — force-stop kills
                // the whole package anyway, so N near-identical rows are just noise.
                LinkedHashMap<String, List<Base>> byPkg = new LinkedHashMap<>();
                for (List<Base> grp : byPid.values()) {
                    Base rep = grp.get(0);
                    if (grp.size() > 1) {
                        rows.add(sharedRow(ctx, pm, grp));
                    } else if (rep.cls.killable && rep.cls.method == ProcessClassifier.METHOD_SIGKILL) {
                        // Shell-owned (uid 2000) process — defer; it may be a leak
                        // member. NOTE: ProcessParser tags these as AppProcessItem
                        // (uid 2000 == com.android.shell), so gate on the SIGKILL
                        // method (= shell-owned), NOT on the item type.
                        leakCandidates.add(rep);
                    } else if (isAppGroupable(rep)) {
                        String pkg = ((AppProcessItem) rep.item).packageInfo.packageName;
                        List<Base> list = byPkg.get(pkg);
                        if (list == null) {
                            list = new ArrayList<>();
                            byPkg.put(pkg, list);
                        }
                        list.add(rep);
                    } else {
                        rows.add(singleRow(ctx, pm, rep));
                    }
                }
                for (List<Base> g : byPkg.values()) {
                    rows.add(g.size() == 1 ? singleRow(ctx, pm, g.get(0)) : appRow(ctx, pm, g));
                }

                // 3. Leak grouping — cluster identical package-less shell commands.
                LinkedHashMap<String, List<Base>> byComm = new LinkedHashMap<>();
                for (Base b : leakCandidates) {
                    String comm = comm(b.item);
                    List<Base> list = byComm.get(comm);
                    if (list == null) {
                        list = new ArrayList<>();
                        byComm.put(comm, list);
                    }
                    list.add(b);
                }
                int leakCount = MonitorPrefs.getLeakThreshold(ctx);
                int transientFloor = Math.min(2, leakCount);
                int leakMinAge = MonitorPrefs.getLeakMinAgeSec(ctx);
                for (Map.Entry<String, List<Base>> e : byComm.entrySet()) {
                    List<Base> g = e.getValue();
                    boolean countOk = g.size() >= leakCount
                            || (TRANSIENT.contains(e.getKey()) && g.size() >= transientFloor);
                    // Optional: require the cluster to be sustained — its OLDEST
                    // member must have lived at least leakMinAge seconds — so a
                    // momentary burst of identical processes isn't flagged.
                    boolean ageOk = leakMinAge <= 0;
                    if (!ageOk) {
                        for (Base b : g) {
                            if (ageSec(b.item.pid) >= leakMinAge) {
                                ageOk = true;
                                break;
                            }
                        }
                    }
                    boolean leak = countOk && ageOk;
                    if (leak) {
                        rows.add(leakRow(ctx, e.getKey(), g));
                    } else {
                        for (Base b : g) rows.add(singleRow(ctx, pm, b));
                    }
                }

                sort(rows);
                mAllRows = rows;
                mRows.postValue(filterRows(rows));
            } finally {
                mLoading.postValue(false);
                mBusy.set(false);
            }
        });
    }

    @NonNull
    private Row singleRow(@NonNull Context ctx, @NonNull PackageManager pm, @NonNull Base b) {
        ProcessItem p = b.item;
        String title, subtitle;
        // Shell-owned processes (SIGKILL) are tagged com.android.shell by
        // ProcessParser — show their actual command, not the "Shell" label.
        if (p instanceof AppProcessItem && b.cls.method != ProcessClassifier.METHOD_SIGKILL) {
            PackageInfo pi = ((AppProcessItem) p).packageInfo;
            title = labelFor(pi, pm);
            subtitle = !TextUtils.isEmpty(p.name) ? p.name : pi.packageName;
        } else {
            title = comm(p);
            subtitle = p.getCommandlineArgsAsString();
        }
        long mem = memBytesFor(p);
        return new Row(p, b.cls, Collections.singletonList(p.pid), 1, false,
                title, subtitle, meta(p), Formatter.formatShortFileSize(ctx, mem),
                cpuStr(b.cpuPct), b.cpuPct, mem);
    }

    @NonNull
    private Row sharedRow(@NonNull Context ctx, @NonNull PackageManager pm, @NonNull List<Base> grp) {
        Base rep = grp.get(0);
        ProcessItem p = rep.item;
        String proc = p.getCommandlineArgsAsString();
        if (TextUtils.isEmpty(proc)) proc = comm(p);
        StringBuilder apps = new StringBuilder();
        for (int i = 0; i < grp.size() && i < 4; i++) {
            if (i > 0) apps.append(", ");
            ProcessItem mi = grp.get(i).item;
            apps.append(mi instanceof AppProcessItem
                    ? labelFor(((AppProcessItem) mi).packageInfo, pm) : comm(mi));
        }
        if (grp.size() > 4) apps.append(", …");
        String subtitle = grp.size() + " apps: " + apps;
        long mem = memBytesFor(p);
        return new Row(p, rep.cls, Collections.singletonList(p.pid), grp.size(), false,
                proc, subtitle, meta(p), Formatter.formatShortFileSize(ctx, mem),
                cpuStr(rep.cpuPct), rep.cpuPct, mem);
    }

    /** A real app process (uid ≥ 10000, has a package, not a shell pid) — groupable by package. */
    private static boolean isAppGroupable(@NonNull Base b) {
        return b.item instanceof AppProcessItem
                && b.item.uid >= Process.FIRST_APPLICATION_UID
                && b.cls.method != ProcessClassifier.METHOD_SIGKILL;
    }

    /** One row for all of an app's processes (main + children); sums RAM/CPU, kills via force-stop. */
    @NonNull
    private Row appRow(@NonNull Context ctx, @NonNull PackageManager pm, @NonNull List<Base> group) {
        long mem = 0;
        double cpu = 0;
        List<Integer> pids = new ArrayList<>(group.size());
        Base rep = group.get(0);
        for (Base b : group) {
            long m = memBytesFor(b.item);
            mem += m;
            if (!Double.isNaN(b.cpuPct)) cpu += b.cpuPct;
            pids.add(b.item.pid);
            if (m > memBytesFor(rep.item)) rep = b;  // representative = heaviest (usually the main)
        }
        PackageInfo pi = ((AppProcessItem) rep.item).packageInfo;
        String title = labelFor(pi, pm);
        String subtitle = ctx.getString(io.github.muntashirakon.AppManager.R.string.monitor_n_processes, group.size());
        return new Row(rep.item, rep.cls, pids, group.size(), false,
                title, subtitle, meta(rep.item), Formatter.formatShortFileSize(ctx, mem),
                cpuStr(cpu), cpu, mem);
    }

    @NonNull
    private Row leakRow(@NonNull Context ctx, @NonNull String comm, @NonNull List<Base> g) {
        long mem = 0;
        double cpu = 0;
        List<Integer> pids = new ArrayList<>(g.size());
        for (Base b : g) {
            mem += memBytesFor(b.item);
            if (!Double.isNaN(b.cpuPct)) cpu += b.cpuPct;
            pids.add(b.item.pid);
        }
        ProcessItem rep = g.get(0).item;
        String title = comm + "  ×" + g.size();
        String subtitle = ctx.getString(io.github.muntashirakon.AppManager.R.string.monitor_leak_subtitle, g.size());
        String meta = "uid " + rep.uid + "  ·  SIGKILL";
        return new Row(rep, g.get(0).cls, pids, g.size(), true,
                title, subtitle, meta, Formatter.formatShortFileSize(ctx, mem),
                cpuStr(cpu), cpu, mem);
    }

    private void sort(@NonNull List<Row> rows) {
        final boolean byCpu = mSort == SORT_CPU;
        Collections.sort(rows, (a, b) -> {
            // Leaks pinned to the top — they're the most actionable, and (being
            // tiny orphaned helpers) would otherwise sink to the bottom of a
            // RAM/CPU sort and be missed.
            if (a.isLeak != b.isLeak) return a.isLeak ? -1 : 1;
            if (byCpu) {
                double ca = Double.isNaN(a.cpuPercent) ? -1 : a.cpuPercent;
                double cb = Double.isNaN(b.cpuPercent) ? -1 : b.cpuPercent;
                int c = Double.compare(cb, ca);
                if (c != 0) return c;
            }
            return Long.compare(b.memBytes, a.memBytes);
        });
    }

    public void kill(@NonNull Row row) {
        ThreadUtils.postOnBackgroundThread(() -> mKillResult.postValue(new Pair<>(row, doKill(row))));
    }

    public void killSelected(@NonNull List<Row> rows) {
        ThreadUtils.postOnBackgroundThread(() -> {
            int ok = 0, total = 0;
            for (Row row : rows) {
                if (!row.cls.killable) continue;
                total++;
                if (doKill(row)) ok++;
            }
            mBulkKillResult.postValue(new int[]{ok, total});
        });
    }

    private boolean doKill(@NonNull Row row) {
        try {
            if (row.cls.method == ProcessClassifier.METHOD_FORCE_STOP
                    && row.item instanceof AppProcessItem) {
                String pkg = ((AppProcessItem) row.item).packageInfo.packageName;
                PackageManagerCompat.forceStopPackage(pkg, UserHandleHidden.getUserId(row.item.uid));
                return true;
            } else if (row.cls.method == ProcessClassifier.METHOD_SIGKILL) {
                StringBuilder pids = new StringBuilder();
                for (Integer pid : row.pids) pids.append(pid).append(' ');
                String pl = pids.toString().trim();
                if (pl.isEmpty()) return false;
                // Report by whether the kill was PERMITTED, not by an immediate
                // liveness probe. `kill -9` returns before the kernel finishes
                // teardown, so checking /proc right after races a dying/zombie
                // entry and reports a false failure (the row vanishes yet the toast
                // says "Couldn't kill"). For a same-uid SIGKILL the only true
                // failure is EPERM ("Operation not permitted"); ESRCH ("No such
                // process" — a member already exited) means the goal is met.
                Runner.Result r = Runner.runCommand("kill -9 " + pl + " 2>&1");
                if (r == null) return false;
                for (String l : r.getOutputAsList()) {
                    String low = l.toLowerCase(Locale.ROOT);
                    if (low.contains("not permitted") || low.contains("permission denied")) {
                        return false;
                    }
                }
                return true;
            }
        } catch (Throwable t) {
            // fall through to false
        }
        return false;
    }

    private double cpuPercentFor(@NonNull ProcessItem p, @Nullable Map<Integer, Long> prev,
                                 @NonNull Map<Integer, Long> ticks, double dtSec) {
        if (prev == null || dtSec <= 0.05) return Double.NaN;
        Long pv = prev.get(p.pid);
        Long cur = ticks.get(p.pid);
        if (pv == null || cur == null) return Double.NaN;
        double d = cur - pv;
        if (d < 0) d = 0;
        return 100.0 * d / CLK_TCK / dtSec;
    }

    @NonNull
    private static String cpuStr(double pct) {
        return (Double.isNaN(pct) || Double.isInfinite(pct))
                ? "—" : String.format(Locale.US, "%.1f%% cpu", pct);
    }

    @NonNull
    private static String meta(@NonNull ProcessItem p) {
        String user = !TextUtils.isEmpty(p.user) ? p.user : ("uid " + p.uid);
        return "PID " + p.pid + "  ·  " + user + (!TextUtils.isEmpty(p.state) ? "  ·  " + p.state : "");
    }

    @NonNull
    private Map<Integer, Long> sampleCpuTicks() {
        Map<Integer, Long> map = new HashMap<>(400);
        Map<Integer, Integer> ppid = new HashMap<>(400);
        Map<Integer, Long> start = new HashMap<>(400);
        try {
            Runner.Result r = Runner.runCommand("cat /proc/[0-9]*/stat 2>/dev/null");
            if (r == null || !r.isSuccessful()) {
                mPpid = ppid;
                mStart = start;
                return map;
            }
            for (String line : r.getOutputAsList()) {
                int open = line.indexOf('(');
                int close = line.lastIndexOf(')');
                if (open < 0 || close < 0 || close < open) continue;
                int pid;
                try {
                    pid = Integer.parseInt(line.substring(0, open).trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                // Fields after "pid (comm)": [0]=state, [1]=ppid, … [11]=utime,
                // [12]=stime, … [19]=starttime (proc stat field 22).
                String[] f = line.substring(close + 1).trim().split("\\s+");
                if (f.length < 13) continue;
                try {
                    map.put(pid, Long.parseLong(f[11]) + Long.parseLong(f[12]));
                    ppid.put(pid, Integer.parseInt(f[1]));
                    if (f.length > 19) start.put(pid, Long.parseLong(f[19]));
                } catch (NumberFormatException ignore) {
                }
            }
        } catch (Throwable ignore) {
        }
        mPpid = ppid;
        mStart = start;
        return map;
    }

    /** Process age in seconds from /proc start time, or 0 if unknown. */
    private double ageSec(int pid) {
        Long st = mStart.get(pid);
        if (st == null) return 0;
        double age = SystemClock.elapsedRealtime() / 1000.0 - st / (double) CLK_TCK;
        return age > 0 ? age : 0;
    }

    /**
     * Packages that are actively in use — the foreground/top app(s) (multiple on
     * the foldable in split-screen) and any process hosting a live foreground
     * service (media, navigation, recorder, sync). Cached for {@link #ACTIVE_TTL_MS}
     * because the two {@code dumpsys} parses are heavier than the per-tick read and
     * the answer changes slowly. Best-effort: a parse failure just yields fewer
     * protections (the prior behaviour), never a crash.
     */
    @NonNull
    private Set<String> activePackages() {
        long now = SystemClock.elapsedRealtime();
        if (mActivePkgsMs != 0 && now - mActivePkgsMs < ACTIVE_TTL_MS) return mActivePkgs;
        mActivePkgs = gatherActivePackages();
        mActivePkgsMs = now;
        return mActivePkgs;
    }

    // u<USER_ID> <pkg>/<activity> — captures the package of a resumed activity.
    private static final Pattern RESUMED_PKG = Pattern.compile("u\\d+\\s+([\\w.]+)/");

    @NonNull
    private Set<String> gatherActivePackages() {
        Set<String> active = new HashSet<>();
        // Foreground services: walk each ServiceRecord block in `dumpsys activity
        // services`; a block carrying `isForeground=true` protects its package.
        try {
            Runner.Result r = Runner.runCommand("dumpsys activity services 2>/dev/null");
            if (r != null && r.isSuccessful()) {
                String curPkg = null;
                for (String line : r.getOutputAsList()) {
                    String t = line.trim();
                    if (t.startsWith("* ServiceRecord{")) {
                        curPkg = null;
                    } else if (t.startsWith("packageName=")) {
                        curPkg = t.substring("packageName=".length()).trim();
                    } else if (curPkg != null && t.contains("isForeground=true")) {
                        active.add(curPkg);
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        // Foreground / top app(s): every resumed activity in `dumpsys activity
        // activities` (more than one when split across the fold).
        try {
            Runner.Result r = Runner.runCommand("dumpsys activity activities 2>/dev/null");
            if (r != null && r.isSuccessful()) {
                for (String line : r.getOutputAsList()) {
                    if (!line.contains("ResumedActivity")) continue;
                    Matcher m = RESUMED_PKG.matcher(line);
                    if (m.find()) active.add(m.group(1));
                }
            }
        } catch (Throwable ignore) {
        }
        return active;
    }

    // "/proc/<pid>/cmdline" → pid.
    private static final Pattern PROC_PID = Pattern.compile("/proc/(\\d+)/cmdline");

    /**
     * Pids of our privilege roots, read straight from {@code /proc/*​/cmdline} —
     * NOT from {@link ProcessParser}, whose fields don't expose our package for the
     * uid-2000 {@code :priv:0} server (its {@code comm} is "main"). Any process
     * whose cmdline carries our package or "shizuku" is a root; the classifier then
     * protects its whole subtree. Always includes our own pid as a backstop.
     */
    @NonNull
    private Set<Integer> grepOurRootPids() {
        Set<Integer> roots = new HashSet<>();
        roots.add(android.os.Process.myPid());
        try {
            Runner.Result r = Runner.runCommand(
                    "grep -la -e " + BuildConfig.APPLICATION_ID
                            + " -e shizuku -e am_local_server /proc/[0-9]*/cmdline 2>/dev/null");
            if (r != null && r.isSuccessful()) {
                for (String line : r.getOutputAsList()) {
                    Matcher m = PROC_PID.matcher(line);
                    if (m.find()) {
                        try {
                            roots.add(Integer.parseInt(m.group(1)));
                        } catch (NumberFormatException ignore) {
                        }
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return roots;
    }

    /** Accurate per-process footprint: PSS from {@code dumpsys meminfo} when known, else RSS. */
    private long memBytesFor(@NonNull ProcessItem p) {
        Long pss = mPss.get(p.pid);
        return pss != null ? pss : p.getMemory();
    }

    @NonNull
    private Map<Integer, Long> pssMemory() {
        long now = SystemClock.elapsedRealtime();
        if (mPssMs != 0 && now - mPssMs < PSS_TTL_MS) return mPss;
        mPss = samplePss();
        mPssMs = now;
        return mPss;
    }

    // "   707,250K: name (pid 914 / activities)" → group 1 = PSS kB, group 2 = pid.
    private static final Pattern PSS_LINE = Pattern.compile("^\\s*([\\d,]+)K:\\s+.*\\(pid\\s+(\\d+)");

    @NonNull
    private Map<Integer, Long> samplePss() {
        Map<Integer, Long> map = new HashMap<>(256);
        try {
            Runner.Result r = Runner.runCommand("dumpsys meminfo 2>/dev/null");
            if (r == null || !r.isSuccessful()) return map;
            boolean inSection = false;
            for (String line : r.getOutputAsList()) {
                if (!inSection) {
                    if (line.contains("Total PSS by process")) inSection = true;
                    continue;
                }
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("Total ")) break;  // section ends
                Matcher m = PSS_LINE.matcher(line);
                if (m.find()) {
                    try {
                        long kb = Long.parseLong(m.group(1).replace(",", ""));
                        map.put(Integer.parseInt(m.group(2)), kb * 1024L);
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return map;
    }

    @NonNull
    private String labelFor(@NonNull PackageInfo pi, @NonNull PackageManager pm) {
        String cached = mLabelCache.get(pi.packageName);
        if (cached != null) return cached;
        CharSequence lbl = pi.applicationInfo != null ? pi.applicationInfo.loadLabel(pm) : null;
        String label = !TextUtils.isEmpty(lbl) ? lbl.toString() : pi.packageName;
        mLabelCache.put(pi.packageName, label);
        return label;
    }

    @NonNull
    private static String comm(@NonNull ProcessItem p) {
        String[] args = p.getCommandlineArgs();
        String first = (args.length > 0 && !TextUtils.isEmpty(args[0])) ? args[0] : p.name;
        if (first == null) return "?";
        int slash = first.lastIndexOf('/');
        String base = slash >= 0 ? first.substring(slash + 1) : first;
        return TextUtils.isEmpty(base) ? first : base;
    }

    @Nullable
    private String activeImePackage() {
        try {
            String s = Settings.Secure.getString(getApplication().getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD);
            if (!TextUtils.isEmpty(s) && s.contains("/")) {
                return s.substring(0, s.indexOf('/'));
            }
        } catch (Throwable ignore) {
        }
        return null;
    }
}
