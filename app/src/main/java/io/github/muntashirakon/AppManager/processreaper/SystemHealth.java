// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.processreaper;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fork: the process monitor's system-health header (白い熊, 2026-10-29). Answers
 * what a per-process CPU/PSS list cannot: the phone can crawl while every row
 * looks harmless. Measured on the Mate XT that day — 160 % of 800 % CPU in use,
 * yet browser and apps crawling — and the cause was the Huawei launcher holding
 * <b>67 destroyed Activities with 0 windows</b> and 378 MB of GPU memory after
 * 16 days. Neither number appears in a CPU or PSS ranking; both are in
 * {@code dumpsys meminfo}.
 *
 * <p>Two halves at two cadences. The cheap half (uptime, MemAvailable, page
 * refault and major-fault rates, thermal status, graphics allocator size) is
 * read every refresh: {@code /proc/meminfo} and {@code /proc/vmstat} are
 * world-readable, so they are read in-process. The expensive half — per-app
 * Activity/window/View counts and GL memory — needs
 * {@code dumpsys meminfo --checkin}, which takes ~9 s on this phone, so it runs
 * once a minute on its own thread and never stalls the 2 s refresh.
 */
public final class SystemHealth {
    /**
     * Activities minus windows at or above which an app is holding destroyed
     * screens. A stopped Activity on the back stack keeps its ViewRootImpl, so
     * the two counts agree in a healthy app; the leaking launcher read 67 / 0.
     */
    public static final int ACTIVITY_LEAK_EXCESS = 5;
    /** One app's GPU memory (GL + EGL mtrack) at or above which it is flagged. */
    public static final long GRAPHICS_HIGH_BYTES = 256L << 20;
    /** Growth of one app's GPU memory since the monitor was opened that is flagged. */
    public static final long GRAPHICS_GROWTH_BYTES = 128L << 20;

    /** One app process flagged by the slow sampler. */
    public static final class Suspect {
        public final int pid;
        @NonNull
        public final String processName;
        @NonNull
        public final String label;
        public final int activities;
        public final int windows;
        public final int views;
        public final long graphicsBytes;
        /** Growth since first seen this session, or 0. */
        public final long graphicsGrowthBytes;
        public final long pssBytes;

        Suspect(int pid, @NonNull String processName, @NonNull String label, int activities,
                int windows, int views, long graphicsBytes, long graphicsGrowthBytes, long pssBytes) {
            this.pid = pid;
            this.processName = processName;
            this.label = label;
            this.activities = activities;
            this.windows = windows;
            this.views = views;
            this.graphicsBytes = graphicsBytes;
            this.graphicsGrowthBytes = graphicsGrowthBytes;
            this.pssBytes = pssBytes;
        }

        public boolean leaksActivities() {
            return activities - windows >= ACTIVITY_LEAK_EXCESS;
        }
    }

    /** Per-process figures parsed from one {@code dumpsys meminfo --checkin} line. */
    public static final class AppObjects {
        public final int pid;
        @NonNull
        public final String processName;
        public final long pssBytes;
        public final long graphicsBytes;
        public final int views;
        public final int windows;
        public final int appContexts;
        public final int activities;

        AppObjects(int pid, @NonNull String processName, long pssBytes, long graphicsBytes,
                   int views, int windows, int appContexts, int activities) {
            this.pid = pid;
            this.processName = processName;
            this.pssBytes = pssBytes;
            this.graphicsBytes = graphicsBytes;
            this.views = views;
            this.windows = windows;
            this.appContexts = appContexts;
            this.activities = activities;
        }
    }

    public final long uptimeMs;
    public final long memTotalBytes;
    public final long memAvailableBytes;
    /** File pages re-read after eviction, per second; NaN on the first sample. */
    public final double refaultsPerSec;
    public final double majorFaultsPerSec;
    /** {@code PowerManager.THERMAL_STATUS_*}, or -1 when unavailable (pre-Q). */
    public final int thermalStatus;
    /** PSS of the graphics allocator HAL, or -1 when not seen. */
    public final long allocatorBytes;
    /** Allocator growth since the monitor was opened (may be negative). */
    public final long allocatorDeltaBytes;
    /** False until the slow sampler has finished once. */
    public final boolean objectsSampled;
    @NonNull
    public final List<Suspect> suspects;

    SystemHealth(long uptimeMs, long memTotalBytes, long memAvailableBytes, double refaultsPerSec,
                 double majorFaultsPerSec, int thermalStatus, long allocatorBytes,
                 long allocatorDeltaBytes, boolean objectsSampled, @NonNull List<Suspect> suspects) {
        this.uptimeMs = uptimeMs;
        this.memTotalBytes = memTotalBytes;
        this.memAvailableBytes = memAvailableBytes;
        this.refaultsPerSec = refaultsPerSec;
        this.majorFaultsPerSec = majorFaultsPerSec;
        this.thermalStatus = thermalStatus;
        this.allocatorBytes = allocatorBytes;
        this.allocatorDeltaBytes = allocatorDeltaBytes;
        this.objectsSampled = objectsSampled;
        this.suspects = suspects;
    }

    @NonNull
    SystemHealth withSuspects(@NonNull List<Suspect> s) {
        return new SystemHealth(uptimeMs, memTotalBytes, memAvailableBytes, refaultsPerSec,
                majorFaultsPerSec, thermalStatus, allocatorBytes, allocatorDeltaBytes, true, s);
    }

    /** MemAvailable under a tenth of RAM — the point where the kernel starts dropping code pages. */
    public boolean memoryLow() {
        return memTotalBytes > 0 && memAvailableBytes < memTotalBytes / 10;
    }

    /** Refault rate at which apps visibly stall on re-reading their own code. */
    public boolean pagingHeavy() {
        return !Double.isNaN(refaultsPerSec) && refaultsPerSec >= 2000;
    }

    public boolean thermalHot() {
        return thermalStatus >= 3;  // THERMAL_STATUS_SEVERE
    }

    // ---- Readers ---------------------------------------------------------

    /** {kB total, kB available} from {@code /proc/meminfo}; zeros when unreadable. */
    @NonNull
    static long[] readMeminfo() {
        long[] out = new long[2];
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemTotal:")) out[0] = kb(line);
                else if (line.startsWith("MemAvailable:")) {
                    out[1] = kb(line);
                    break;
                }
            }
        } catch (Throwable ignore) {
        }
        return out;
    }

    /** {workingset_refault_file, pgmajfault} from {@code /proc/vmstat}; -1 when unreadable. */
    @NonNull
    static long[] readVmstat() {
        long[] out = {-1, -1};
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/vmstat"))) {
            String line;
            while ((line = r.readLine()) != null) {
                // Older kernels name it plain "workingset_refault".
                if (line.startsWith("workingset_refault_file ") || line.startsWith("workingset_refault ")) {
                    out[0] = Long.parseLong(line.substring(line.indexOf(' ') + 1).trim());
                } else if (line.startsWith("pgmajfault ")) {
                    out[1] = Long.parseLong(line.substring(line.indexOf(' ') + 1).trim());
                }
            }
        } catch (Throwable ignore) {
        }
        return out;
    }

    static int readThermalStatus(@NonNull Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1;
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null ? pm.getCurrentThermalStatus() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static long kb(@NonNull String line) {
        String[] f = line.trim().split("\\s+");
        try {
            return f.length >= 2 ? Long.parseLong(f[1]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Parse {@code dumpsys meminfo --checkin}. Each app is one line:
     * {@code 4,<pid>,<name>,} then four groups of four (heap size, allocated,
     * free, PSS — the fourth of each is the total), then labelled categories of
     * eight values each ({@code GL mtrack,<pss>,…}), then — directly after the
     * last category, {@code Other mtrack} — the object counts: Views,
     * ViewRootImpl, AppContexts, Activities. Native processes are not in it (the
     * allocator comes from the plain dump instead). A line whose shape differs is
     * skipped rather than guessed at.
     */
    @NonNull
    static List<AppObjects> parseCheckin(@NonNull List<String> lines) {
        List<AppObjects> out = new ArrayList<>();
        for (String line : lines) {
            if (!line.startsWith("4,")) continue;
            String[] t = line.split(",", -1);
            if (t.length < 30) continue;
            try {
                int pid = Integer.parseInt(t[1]);
                long pss = num(t[18]);
                long gfx = 0;
                int objects = -1;
                for (int i = 19; i < t.length - 1; i++) {
                    if ("GL mtrack".equals(t[i]) || "EGL mtrack".equals(t[i])) {
                        gfx += num(t[i + 1]);
                    } else if ("Other mtrack".equals(t[i])) {
                        objects = i + 9;
                        break;
                    }
                }
                if (objects < 0 || objects + 3 >= t.length) continue;
                out.add(new AppObjects(pid, t[2], pss << 10, gfx << 10,
                        (int) num(t[objects]), (int) num(t[objects + 1]),
                        (int) num(t[objects + 2]), (int) num(t[objects + 3])));
            } catch (NumberFormatException ignore) {
            }
        }
        return out;
    }

    private static long num(@Nullable String s) {
        if (s == null || s.isEmpty() || "N/A".equals(s)) return 0;
        return Long.parseLong(s.trim());
    }

    @NonNull
    static List<Suspect> emptySuspects() {
        return Collections.emptyList();
    }
}
