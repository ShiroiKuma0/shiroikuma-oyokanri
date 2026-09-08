// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.backup;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import io.github.muntashirakon.AppManager.logs.Log;

/**
 * Fork (白い熊): only one <b>large</b> app-supplied transfer at a time.
 *
 * <p>The batch runs one worker per core — eight on the Mate XT — which is right for the many small
 * apps, where per-app overhead dominates and the disk is never the limit. It is wrong for the large
 * ones. 白い熊 watched five multi-gigabyte restores run at once and take half an hour to finish
 * three of eight; 猫管 needed <b>22m 37s to move 3.2 MB</b>, which is not a slow app, it is an app
 * that spent twenty-two minutes queued behind four others on one storage device.
 *
 * <p>The decisive cost is not seek contention, though that is real. {@code RestoreOp} stages each
 * archive to a full copy under {@code getCacheDir()} before the sister app reads it, so five 5 GB
 * restores want twenty-five gigabytes of cache <em>simultaneously</em>, on top of the destination
 * writes. That is not a slowdown but a way to run out of space and fail several apps at once.
 *
 * <p>So: a permit, not a smaller thread pool. Small apps keep flowing in parallel around a large
 * one — the parallelism that pays is kept and the parallelism that costs is removed. A backup and a
 * restore share the gate, because they compete for the same disk.
 *
 * <p><b>Waiting is announced.</b> A worker blocked here would otherwise look exactly like the hang
 * this fork has already been bitten by twice, so the caller is expected to say what it is waiting
 * for; {@link #acquire} polls rather than blocking outright so a cancel is still noticed.
 */
public final class LargeTransferGate {
    public static final String TAG = LargeTransferGate.class.getSimpleName();

    /**
     * What counts as large. Chosen well above the size at which per-app overhead still dominates
     * and well below the archives that caused the problem (2.28 GB, 4.33 GB), so the many small
     * sister apps are untouched and the handful of big ones serialise.
     */
    private static final long LARGE_BYTES = 256L * 1024 * 1024;

    private static final Semaphore GATE = new Semaphore(1, true);
    /** How often a waiting worker looks up, so a cancel is noticed while queued. */
    private static final long WAIT_POLL_MS = 500L;

    @AnyThread
    public static boolean isLarge(long bytes) {
        return bytes >= LARGE_BYTES;
    }

    /** Asked while queued; returning true abandons the wait. */
    public interface Cancelled {
        boolean isCancelled();
    }

    /**
     * Take the permit, waiting for it. Returns {@code false} only when the wait was abandoned
     * because the operation was cancelled — in which case nothing was acquired and
     * {@link #release} must not be called.
     *
     * @param onWait run once, if and only if the permit is not immediately free, so the caller can
     *               say so in the log rather than falling silent.
     */
    @WorkerThread
    public static boolean acquire(@Nullable Cancelled cancelled, @Nullable Runnable onWait) {
        if (GATE.tryAcquire()) {
            return true;
        }
        if (onWait != null) {
            onWait.run();
        }
        while (true) {
            if (cancelled != null && cancelled.isCancelled()) {
                return false;
            }
            try {
                if (GATE.tryAcquire(WAIT_POLL_MS, TimeUnit.MILLISECONDS)) {
                    return true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    @AnyThread
    public static void release() {
        try {
            GATE.release();
        } catch (Throwable th) {
            Log.w(TAG, "Could not release the large-transfer permit.", th);
        }
    }

    private LargeTransferGate() {
    }
}
