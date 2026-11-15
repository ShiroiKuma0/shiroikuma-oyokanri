// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.processreaper;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.diag.ForkDiag;
import io.github.muntashirakon.AppManager.runner.Runner;

/**
 * Fork (白い熊, 2026-11-15): the process-start fix itself, shared by the monitor's
 * staged dialog and the background watch's notification button.
 *
 * <p>While an episode is open the apps are stopped in <b>two random halves</b>
 * with a probe after each, and the second half is left alone when the first
 * already cured it. Over a few episodes the record then separates "too many
 * processes" (either half helps) from "one culprit app" (only the half that
 * holds it helps). Every app stopped is recorded with its size and age.
 */
final class ForkFix {
    private ForkFix() {
    }

    static final class Target {
        @NonNull
        final String pkg;
        final int userId;
        /** -1 when unknown (the notification path has no monitor rows). */
        final long memBytes;
        final long ageSec;
        final double cpuPercent;

        Target(@NonNull String pkg, int userId, long memBytes, long ageSec, double cpuPercent) {
            this.pkg = pkg;
            this.userId = userId;
            this.memBytes = memBytes;
            this.ageSec = ageSec;
            this.cpuPercent = cpuPercent;
        }
    }

    static final class Outcome {
        int stopped;
        /** {MemFree, MemAvailable, SUnreclaim} bytes. */
        long[] before, after;
        ForkStartWatch.Probe probe;
    }

    /** 雫, 自由作業盤 and this app are never stopped, whatever a caller passes in. */
    static boolean neverStop(@NonNull String pkg) {
        return pkg.equals(BuildConfig.APPLICATION_ID) || pkg.contains("shiroikuma.jiyusagyoban")
                || pkg.contains("shizuku") || pkg.contains("shiroikuma.oyokanri");
    }

    private static final Pattern LRU_CACHED =
            Pattern.compile("#\\d+:\\s+(\\w+).*?\\s(\\d+):([A-Za-z0-9._]+)(?::[^/\\s]*)?/(?:u(\\d+))?");

    /**
     * The apps whose processes the system itself lists as cached — what
     * {@code am kill-all} would take, minus the never-stop set. For the
     * notification button, which has no monitor rows to choose from.
     */
    @NonNull
    static List<Target> cachedTargets() {
        List<Target> out = new ArrayList<>();
        // One app may hold several cached processes: the first seen keeps its place in the list.
        Map<String, Integer> users = new LinkedHashMap<>();
        Map<String, List<Integer>> pids = new HashMap<>();
        Set<String> inUse = new HashSet<>();
        try {
            Runner.Result r = Runner.runCommand("dumpsys activity lru 2>/dev/null; true");
            if (r == null) return out;
            for (String line : r.getOutputAsList()) {
                Matcher m = LRU_CACHED.matcher(line);
                if (!m.find()) continue;
                String pkg = m.group(3);
                if (pkg == null || neverStop(pkg)) continue;
                // Fork (+102): an app is a target only when every one of its processes is cached.
                // On 2026-11-15 one cached helper process of the previous app got the whole app
                // — tabs and all — ended.
                if (!"cch".equals(m.group(1))) {
                    inUse.add(pkg);
                    continue;
                }
                int user = 0, pid = -1;
                try {
                    pid = Integer.parseInt(m.group(2));
                    if (m.group(4) != null) user = Integer.parseInt(m.group(4));
                } catch (Throwable ignore) {
                }
                if (!users.containsKey(pkg)) users.put(pkg, user);
                List<Integer> l = pids.get(pkg);
                if (l == null) pids.put(pkg, l = new ArrayList<>());
                if (pid > 0) l.add(pid);
            }
        } catch (Throwable ignore) {
        }
        for (String pkg : inUse) {
            users.remove(pkg);
            pids.remove(pkg);
        }
        // Fork (+101): resident size and age straight from /proc, so a fix started from the
        // notification records them too (they were -1 in the 2026-11-15 burst).
        Map<Integer, long[]> stat = new HashMap<>();
        double uptime = -1;
        try {
            StringBuilder sh = new StringBuilder("for p in");
            for (List<Integer> l : pids.values()) for (int pid : l) sh.append(' ').append(pid);
            sh.append("; do echo \"P $p $(cat /proc/$p/statm 2>/dev/null) @ $(cat /proc/$p/stat 2>/dev/null)\"; done;"
                    + " echo \"U $(cat /proc/uptime)\"; true");
            Runner.Result r = Runner.runCommand(sh.toString());
            if (r != null) {
                for (String line : r.getOutputAsList()) {
                    try {
                        if (line.startsWith("U ")) {
                            uptime = Double.parseDouble(line.split("\\s+")[1]);
                        } else if (line.startsWith("P ")) {
                            int at = line.indexOf(" @ "), close = line.lastIndexOf(')');
                            if (at < 0 || close < at) continue;
                            String[] head = line.substring(0, at).trim().split("\\s+");
                            String[] tail = line.substring(close + 1).trim().split("\\s+");
                            if (head.length < 4 || tail.length < 20) continue;
                            // statm field 2 = resident pages; stat field 22 = start time in ticks.
                            stat.put(Integer.parseInt(head[1]),
                                    new long[]{Long.parseLong(head[3]) * 4096L, Long.parseLong(tail[19])});
                        }
                    } catch (Throwable ignore) {
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        for (Map.Entry<String, Integer> e : users.entrySet()) {
            long mem = -1, age = -1;
            List<Integer> l = pids.get(e.getKey());
            if (l != null) {
                for (int pid : l) {
                    long[] v = stat.get(pid);
                    if (v == null) continue;
                    mem = Math.max(mem, 0) + v[0];
                    if (uptime > 0) age = Math.max(age, (long) (uptime - v[1] / 100.0));
                }
            }
            out.add(new Target(e.getKey(), e.getValue(), mem, age, 0));
        }
        return out;
    }

    /**
     * Stop the chosen apps — {@code forceStop} false: end their background
     * processes ({@code am kill} through the shell, and the platform's own
     * {@code killBackgroundProcesses}, which needs no privileged server);
     * true: force-stop them — then settle and probe. Blocks for several
     * seconds; never on the main thread.
     */
    @NonNull
    static Outcome run(@NonNull Context context, @NonNull List<Target> chosen, boolean forceStop,
                       @NonNull String origin) {
        final Context ctx = context.getApplicationContext();
        final ForkStartWatch watch = ForkStartWatch.get();
        final String step = forceStop ? "force-stop" : "kill-background";
        final boolean diagOn = ForkDiag.isOn(ctx, ForkDiag.KIND_FORKSTART);
        Outcome o = new Outcome();
        o.before = ForkStartWatch.readMeminfo();

        List<Target> list = new ArrayList<>();
        for (Target t : chosen) {
            if (!neverStop(t.pkg) && t.pkg.matches("[A-Za-z0-9._]+")) list.add(t);
        }
        List<List<Target>> groups = new ArrayList<>();
        if (watch.isActive() && list.size() >= 2) {
            Collections.shuffle(list);
            groups.add(list.subList(0, list.size() / 2));
            groups.add(list.subList(list.size() / 2, list.size()));
        } else {
            // Nothing is failing right now ("stop anyway"): there is no cure to bisect.
            groups.add(list);
        }
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        for (int g = 0; g < groups.size(); g++) {
            List<Target> group = groups.get(g);
            String label = groups.size() == 1 ? "all" : g == 0 ? "A" : "B";
            ForkStartWatch.Snap s0 = diagOn ? ForkStartWatch.takeSnap() : null;
            long[] m0 = ForkStartWatch.readMeminfo();
            StringBuilder shell = new StringBuilder();
            StringBuilder names = new StringBuilder();
            for (Target t : group) {
                ForkStartWatch.diag(ctx, "fix-app\tstep=" + step + " group=" + label + " pkg=" + t.pkg
                        + " memMB=" + (t.memBytes < 0 ? -1 : t.memBytes >> 20) + " ageS=" + t.ageSec
                        + " cpu=" + Math.round(t.cpuPercent));
                names.append(' ').append(t.pkg);
                if (forceStop) {
                    try {
                        PackageManagerCompat.forceStopPackage(t.pkg, t.userId);
                    } catch (Throwable ignore) {
                    }
                } else {
                    shell.append("am kill --user ").append(t.userId).append(' ').append(t.pkg).append("; ");
                    try {
                        if (am != null && t.userId == 0) am.killBackgroundProcesses(t.pkg);
                    } catch (Throwable ignore) {
                    }
                }
            }
            if (shell.length() > 0) {
                try {
                    Runner.runCommand(shell + "true");
                } catch (Throwable ignore) {
                }
            }
            o.stopped += group.size();
            SystemClock.sleep(2_500L);  // let the kernel finish tearing the processes down
            long[] m1 = ForkStartWatch.readMeminfo();
            if (s0 != null) ForkStartWatch.writeSnapDiff(ctx, "fix " + step + " group " + label, s0, ForkStartWatch.takeSnap());
            ForkStartWatch.Probe p = watch.probe(ctx, "fix-" + step + "-" + label);
            if (p.ok && groups.size() > 1) {
                // The failure is intermittent: one pass may be luck, so ask twice before sparing the other half.
                SystemClock.sleep(1_000L);
                p = watch.probe(ctx, "fix-" + step + "-" + label + "-confirm");
            }
            o.probe = p;
            ForkStartWatch.diag(ctx, "fix\tstep=" + step + " origin=" + origin + " group=" + label
                    + " apps=" + group.size() + " probe=" + (p.ok ? "ok" : "FAILED")
                    + " memFreeMB=" + (m0[0] >> 20) + "->" + (m1[0] >> 20)
                    + " memAvailMB=" + (m0[1] >> 20) + "->" + (m1[1] >> 20) + " :" + names);
            if (p.ok) break;
        }
        if (o.probe == null) o.probe = watch.probe(ctx, "fix-" + step + "-none");
        o.after = ForkStartWatch.readMeminfo();
        return o;
    }
}
