// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.batchops;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/**
 * Fork: a process-wide bridge between a running {@link BatchOpsService} batch
 * operation (executed on a background worker thread) and the in-app progress
 * dialog shown by the main window. It carries live progress <em>to</em> the UI
 * and carries pause/cancel commands <em>from</em> the UI back to the worker loop
 * in {@link BatchOpsManager}.
 *
 * <p>Only one batch operation runs at a time — {@link BatchOpsService} queues
 * the rest — so a singleton is sufficient. The same singleton is consulted by
 * {@code BatchOpsManager.updateProgress()} regardless of how the manager was
 * started; when no dialog session is in progress all flags are clear, so the
 * checkpoint is a no-op.
 */
public final class BatchOpsProgressMonitor {
    /**
     * Thrown out of the worker loop (from the per-item progress checkpoint) when
     * the user cancels via the dialog. It is unchecked so it propagates through
     * the op methods without touching their signatures, and is caught in
     * {@link BatchOpsManager#performOp(BatchOpsManager.BatchOpsInfo, io.github.muntashirakon.AppManager.progress.ProgressHandler)}.
     */
    public static class OperationCancelledException extends RuntimeException {
    }

    /** Immutable snapshot delivered to the dialog through {@link #getState()}. */
    public static final class State {
        public final boolean active;
        @Nullable
        public final CharSequence title;
        public final int current;
        public final int max;
        public final boolean paused;

        State(boolean active, @Nullable CharSequence title, int current, int max, boolean paused) {
            this.active = active;
            this.title = title;
            this.current = current;
            this.max = max;
            this.paused = paused;
        }
    }

    private static final BatchOpsProgressMonitor INSTANCE = new BatchOpsProgressMonitor();

    @NonNull
    public static BatchOpsProgressMonitor getInstance() {
        return INSTANCE;
    }

    private final MutableLiveData<State> mState = new MutableLiveData<>(
            new State(false, null, 0, 0, false));
    private final Object mPauseLock = new Object();

    @Nullable
    private volatile CharSequence mTitle;
    private volatile int mCurrent;
    private volatile int mMax;
    private volatile boolean mActive;
    private volatile boolean mPaused;
    private volatile boolean mCancelled;

    private BatchOpsProgressMonitor() {
    }

    @NonNull
    public LiveData<State> getState() {
        return mState;
    }

    public boolean isActive() {
        return mActive;
    }

    public boolean isPaused() {
        return mPaused;
    }

    public boolean isCancelled() {
        return mCancelled;
    }

    /** Called by the service when a new operation starts. Resets all flags. */
    @AnyThread
    public void begin(@Nullable CharSequence title, int max) {
        synchronized (mPauseLock) {
            mPaused = false;
            mCancelled = false;
            mPauseLock.notifyAll();
        }
        mActive = true;
        mTitle = title;
        mMax = max;
        mCurrent = 0;
        publish();
    }

    /** Called by the worker loop to report a new current value. */
    @AnyThread
    public void publishProgress(int max, int current) {
        if (max > 0) {
            mMax = max;
        }
        mCurrent = current;
        publish();
    }

    /** Called by the service when the operation finishes (or is cancelled). */
    @AnyThread
    public void finish() {
        synchronized (mPauseLock) {
            mPaused = false;
            mPauseLock.notifyAll();
        }
        mActive = false;
        publish();
    }

    @MainThread
    public void pause() {
        mPaused = true;
        publish();
    }

    @MainThread
    public void resume() {
        synchronized (mPauseLock) {
            mPaused = false;
            mPauseLock.notifyAll();
        }
        publish();
    }

    @MainThread
    public void cancel() {
        mCancelled = true;
        // Wake a paused worker so it can observe the cancellation immediately.
        synchronized (mPauseLock) {
            mPaused = false;
            mPauseLock.notifyAll();
        }
        publish();
    }

    /**
     * Block the calling (worker) thread for as long as the operation is paused.
     * Returns immediately if not paused, or as soon as the operation is
     * cancelled.
     */
    @WorkerThread
    public void awaitWhilePaused() {
        synchronized (mPauseLock) {
            while (mPaused && !mCancelled) {
                try {
                    mPauseLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void publish() {
        mState.postValue(new State(mActive, mTitle, mCurrent, mMax, mPaused));
    }
}
