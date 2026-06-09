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
        // Fork: the app currently being processed, so the dialog can show its
        // label (bold) and package id (italic) under the counter. Null when no
        // per-app item is in flight (e.g. between begin() and the first item, or
        // for ops that don't report a current package).
        @Nullable
        public final CharSequence currentLabel;
        @Nullable
        public final String currentPackage;

        State(boolean active, @Nullable CharSequence title, int current, int max, boolean paused,
              @Nullable CharSequence currentLabel, @Nullable String currentPackage) {
            this.active = active;
            this.title = title;
            this.current = current;
            this.max = max;
            this.paused = paused;
            this.currentLabel = currentLabel;
            this.currentPackage = currentPackage;
        }
    }

    private static final BatchOpsProgressMonitor INSTANCE = new BatchOpsProgressMonitor();

    @NonNull
    public static BatchOpsProgressMonitor getInstance() {
        return INSTANCE;
    }

    private final MutableLiveData<State> mState = new MutableLiveData<>(
            new State(false, null, 0, 0, false, null, null));
    private final Object mPauseLock = new Object();

    @Nullable
    private volatile CharSequence mTitle;
    @Nullable
    private volatile CharSequence mCurrentLabel;
    @Nullable
    private volatile String mCurrentPackage;
    private volatile int mCurrent;
    private volatile int mMax;
    private volatile boolean mActive;
    private volatile boolean mPaused;
    private volatile boolean mCancelled;
    // Fork: whether the main window is in the foreground. Set by MainActivity in
    // onResume/onPause; read by BatchOpsService to decide whether to post the
    // system completion heads-up (suppressed while foreground, since the in-app
    // themed toast covers it).
    private volatile boolean mHostForeground;

    private BatchOpsProgressMonitor() {
    }

    public void setHostForeground(boolean foreground) {
        mHostForeground = foreground;
    }

    public boolean isHostForeground() {
        return mHostForeground;
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
        mCurrentLabel = null;
        mCurrentPackage = null;
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

    /**
     * Fork: report a new current value along with the app being processed, so the
     * dialog can show its label and package id. Used by the per-app ops
     * (freeze/unfreeze/uninstall/reinstall).
     */
    @AnyThread
    public void publishProgress(int max, int current, @Nullable CharSequence currentLabel,
                                @Nullable String currentPackage) {
        if (max > 0) {
            mMax = max;
        }
        mCurrent = current;
        mCurrentLabel = currentLabel;
        mCurrentPackage = currentPackage;
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
        mState.postValue(new State(mActive, mTitle, mCurrent, mMax, mPaused, mCurrentLabel, mCurrentPackage));
    }
}
