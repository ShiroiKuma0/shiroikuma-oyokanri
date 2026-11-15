// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.processreaper;

import androidx.annotation.NonNull;

/**
 * Fork (白い熊, +102): the kernel-call probe — see {@code shiroikuma_kernel_probe.c}. Makes the
 * system call that fails when "new processes cannot start" from a throwaway child of this app:
 * a one-instruction and a 224-instruction seccomp filter, plus two neighbours that draw on the
 * kernel's per-CPU pool ({@code io_setup}, and {@code unshare} of a mount namespace, which the
 * kernel normally refuses outright). Tokens of the record line are never renamed.
 */
final class KernelProbe {
    private KernelProbe() {
    }

    private static boolean sLoaded, sBroken;

    private static native int[] nativeRun();

    /** Healthy phone: the fork tests run this seldom — each one makes two filter programs. */
    private static final long QUIET_MS = 30 * 60_000L;
    private static long sLastQuietEl;

    static synchronized boolean load() {
        if (sBroken) return false;
        try {
            if (!sLoaded) {
                System.loadLibrary("am");
                sLoaded = true;
            }
            return true;
        } catch (Throwable t) {
            sBroken = true;
            return false;
        }
    }

    /**
     * {@code seccomp1=ok seccomp224=ENOMEM iosetup=ok unshare=errno1 tookMs=3 sockfree=0
     * reserve=freed-ok@20ms}; never throws. Empty when nothing was due: on a healthy phone the
     * tests run every 30 minutes at most, with every probe while {@code failing}.
     */
    @NonNull
    static synchronized String run(@NonNull android.content.Context ctx, boolean failing) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!failing) {
            if (sLastQuietEl != 0 && now - sLastQuietEl < QUIET_MS) return "";
            sLastQuietEl = now;
        }
        if (!load()) return "unavailable";
        try {
            int[] r = nativeRun();
            if (r == null || r.length < 5) return "unavailable";
            return "seccomp1=" + name(r[0]) + " seccomp224=" + name(r[1]) + " iosetup=" + name(r[2])
                    + " unshare=" + name(r[3]) + " tookMs=" + r[4] + (failing ? FilterPool.gaugeForProbe(ctx) : "");
        } catch (Throwable t) {
            sBroken = true;
            return "unavailable " + t.getClass().getSimpleName();
        }
    }

    /** The small gauge with its reserve: {room, cap, firstErrno, reserveState, reserveMs}, or null. */
    @androidx.annotation.Nullable
    static synchronized int[] sockets() {
        if (!load()) return null;
        try {
            return nativeSockets();
        } catch (Throwable t) {
            return null;
        }
    }

    /** {count, hitWall, firstErrno, tookMs}, or null. */
    @androidx.annotation.Nullable
    static synchronized int[] room(int cap) {
        if (!load()) return null;
        try {
            return nativeRoom(cap);
        } catch (Throwable t) {
            return null;
        }
    }

    /** {cyclesDone, refusals, tookMs}, or null. */
    @androidx.annotation.Nullable
    static synchronized int[] churn(int cycles) {
        if (!load()) return null;
        try {
            return nativeChurn(cycles);
        } catch (Throwable t) {
            return null;
        }
    }

    private static native int[] nativeRoom(int cap);

    private static native int[] nativeChurn(int cycles);

    private static native int[] nativeSockets();

    @NonNull
    private static String name(int code) {
        if (code == 0) return "ok";
        if (code == 12) return "ENOMEM";
        if (code >= 1000) return "signal" + (code - 1000);
        if (code < 0) return "forkErrno" + (-code);
        return "errno" + code;
    }
}
