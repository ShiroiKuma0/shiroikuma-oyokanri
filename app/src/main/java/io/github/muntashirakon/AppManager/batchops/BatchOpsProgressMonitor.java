// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.batchops;

import android.os.SystemClock;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import io.github.muntashirakon.AppManager.types.UserPackagePair;

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
 *
 * <p><b>In-flight items are a list, not a single item.</b> Backup, restore and
 * backup-import run their per-app work on a {@code MultithreadedExecutor} —
 * one thread per CPU core — so at any instant several apps are genuinely being
 * processed at once and "the current app" does not exist. Every op therefore
 * reports into a keyed map of {@link Item}s: the sequential ops keep exactly one
 * entry in it (via {@link #setCurrentItem}), the concurrent ones keep as many as
 * they have threads. Each item carries what it is, where it is writing, and what
 * stage it has reached, so a batch that takes an hour has something to say
 * throughout instead of only a counter that moves once per app.
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

    /** One app currently being worked on. Immutable snapshot for the dialog. */
    public static final class Item {
        @NonNull
        public final String key;
        @Nullable
        public final CharSequence label;
        @NonNull
        public final String packageName;
        public final int userId;
        /** {@link SystemClock#elapsedRealtime()} at which this item started. */
        public final long startedAtRealtime;
        /** Full destination directory of this item's output, when it has one. */
        @Nullable
        public final String destination;
        /** What is happening to this item right now, e.g. "Data 2/4". */
        @Nullable
        public final CharSequence stage;
        /** Extra detail for the stage, e.g. the directory or size involved. */
        @Nullable
        public final CharSequence detail;

        Item(@NonNull ItemState s) {
            key = s.key;
            label = s.label;
            packageName = s.packageName;
            userId = s.userId;
            startedAtRealtime = s.startedAtRealtime;
            destination = s.destination;
            stage = s.stage;
            detail = s.detail;
        }
    }

    /** Mutable working copy of an {@link Item}; lives only inside the monitor. */
    private static final class ItemState {
        @NonNull
        final String key;
        @Nullable
        CharSequence label;
        @NonNull
        final String packageName;
        final int userId;
        final long startedAtRealtime;
        @Nullable
        String destination;
        @Nullable
        CharSequence stage;
        @Nullable
        CharSequence detail;

        ItemState(@NonNull String key, @Nullable CharSequence label, @NonNull String packageName, int userId) {
            this.key = key;
            this.label = label;
            this.packageName = packageName;
            this.userId = userId;
            this.startedAtRealtime = SystemClock.elapsedRealtime();
        }
    }

    /** Immutable snapshot delivered to the dialog through {@link #getState()}. */
    public static final class State {
        public final boolean active;
        @Nullable
        public final CharSequence title;
        public final int current;
        public final int max;
        public final boolean paused;
        /** Items being worked on right now — empty between apps, several while concurrent. */
        @NonNull
        public final List<Item> items;
        /** Items that have finished, successfully or not. */
        public final int done;
        /** Items that finished by failing. */
        public final int failed;
        /** Bytes written so far by the whole operation, or 0 when it doesn't count them. */
        public final long bytes;
        /** {@link SystemClock#elapsedRealtime()} at which the operation started. */
        public final long startedAtRealtime;

        State(boolean active, @Nullable CharSequence title, int current, int max, boolean paused,
              @NonNull List<Item> items, int done, int failed, long bytes, long startedAtRealtime) {
            this.active = active;
            this.title = title;
            this.current = current;
            this.max = max;
            this.paused = paused;
            this.items = items;
            this.done = done;
            this.failed = failed;
            this.bytes = bytes;
            this.startedAtRealtime = startedAtRealtime;
        }

        /**
         * Items finished, derived rather than trusted: an op that never calls
         * {@link #itemFinished} still knows how many it has started and how many
         * are in flight, and started − running is finished.
         */
        public int completed() {
            return Math.max(done, Math.max(0, current - items.size()));
        }
    }

    private static final BatchOpsProgressMonitor INSTANCE = new BatchOpsProgressMonitor();

    @NonNull
    public static BatchOpsProgressMonitor getInstance() {
        return INSTANCE;
    }

    private final MutableLiveData<State> mState = new MutableLiveData<>(
            new State(false, null, 0, 0, false, Collections.emptyList(), 0, 0, 0L, 0L));
    private final Object mPauseLock = new Object();
    // Guards mItems and the three counters below it; every mutator publishes a
    // fresh immutable snapshot while holding it, so the dialog can never observe
    // a half-updated list.
    private final Object mItemLock = new Object();
    private final LinkedHashMap<String, ItemState> mItems = new LinkedHashMap<>();
    private int mDone;
    private int mFailed;
    private long mBytes;

    @Nullable
    private volatile CharSequence mTitle;
    private volatile int mCurrent;
    private volatile int mMax;
    private volatile boolean mActive;
    private volatile boolean mPaused;
    private volatile boolean mCancelled;
    private volatile long mStartedAtRealtime;

    /**
     * Fork (白い熊): what this run WAS — the operation, and the apps it named — deliberately kept
     * past {@link #finish()}.
     *
     * <p>{@link State#items} is the wrong source for the same question: that list holds what is
     * <em>in flight</em> and is emptied by {@code finish()}, because it exists to drive a
     * progress bar. The finished page needs the opposite — what the run covered, still readable
     * after it has ended — so the backup just made can be handed to 魔法絨毯 from the bar that is
     * on screen the moment it completes, rather than from a screen where the batch must be
     * selected all over again.
     */
    private volatile int mOp = BatchOpsManager.OP_NONE;
    @NonNull
    private volatile List<UserPackagePair> mTargets = Collections.emptyList();
    // Fork: whether the main window is in the foreground. Set by MainActivity in
    // onResume/onPause; read by BatchOpsService to decide whether to post the
    // system completion heads-up (suppressed while foreground, since the in-app
    // themed toast covers it).
    /**
     * How many of our own windows are in front. Fork (白い熊, +138): a plain boolean was wrong the
     * moment a second screen wanted a say — moving from the list to the progress page runs the
     * new screen's onResume before the old one's onPause, so the last write was "background"
     * while a window of ours was plainly on top, and the un-themeable system banner flashed over
     * the page you were reading.
     */
    /**
     * Whether the finished result has actually been looked at. Fork (白い熊, +142): the main
     * list's way-back bar exists for a result nobody has seen — so once the progress page has
     * DRAWN the finished log, the bar has nothing left to offer and must not be waiting on the
     * list afterwards. Closing the page and finding a banner about the thing you just closed
     * reads as a leftover, which is what it was.
     */
    private volatile boolean mResultSeen;

    private final java.util.concurrent.atomic.AtomicInteger mForegroundHosts =
            new java.util.concurrent.atomic.AtomicInteger();

    private BatchOpsProgressMonitor() {
    }

    public void setHostForeground(boolean foreground) {
        if (foreground) {
            mForegroundHosts.incrementAndGet();
        } else {
            mForegroundHosts.updateAndGet(count -> count > 0 ? count - 1 : 0);
        }
    }

    public boolean isHostForeground() {
        return mForegroundHosts.get() > 0;
    }

    /** Called by the progress page when it renders a finished operation. */
    public void noteResultSeen() {
        mResultSeen = true;
    }

    public boolean isResultSeen() {
        return mResultSeen;
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

    /** Key an item by package and user, the pair that identifies it in a batch. */
    @NonNull
    public static String keyOf(@NonNull String packageName, int userId) {
        return packageName + ':' + userId;
    }

    /**
     * Called by the service when a new operation starts. Resets all flags.
     *
     * <p>The operation and its targets are taken here rather than through a setter of their own:
     * there is exactly one caller, and a second call site is precisely how the two would come to
     * disagree about which run the page is describing.
     */
    @AnyThread
    public void begin(@Nullable CharSequence title, int max, @BatchOpsManager.OpType int op,
                      @NonNull List<UserPackagePair> targets) {
        synchronized (mPauseLock) {
            mPaused = false;
            mCancelled = false;
            mPauseLock.notifyAll();
        }
        mActive = true;
        mResultSeen = false;
        mTitle = title;
        mMax = max;
        mOp = op;
        mTargets = new ArrayList<>(targets);
        mCurrent = 0;
        mStartedAtRealtime = SystemClock.elapsedRealtime();
        synchronized (mItemLock) {
            mItems.clear();
            mDone = 0;
            mFailed = 0;
            mBytes = 0;
        }
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
     * Fork: the sequential ops' way of naming what they are working on. Replaces
     * the whole in-flight list with this one app, since such an op only ever has
     * one — no {@link #itemFinished} is expected, the next call supersedes it.
     */
    @AnyThread
    public void setCurrentItem(@Nullable CharSequence label, @Nullable String packageName, int userId) {
        synchronized (mItemLock) {
            mItems.clear();
            if (packageName != null) {
                mItems.put(keyOf(packageName, userId), new ItemState(keyOf(packageName, userId), label, packageName, userId));
            }
        }
        publish();
    }

    /**
     * Fork: a concurrent op reporting that it has picked up an app. Balanced by
     * {@link #itemFinished(String, boolean)}, which must run even when the item
     * fails or the whole thing would leak in-flight rows.
     */
    @AnyThread
    public void itemStarted(@NonNull String key, @Nullable CharSequence label, @NonNull String packageName, int userId) {
        synchronized (mItemLock) {
            mItems.put(key, new ItemState(key, label, packageName, userId));
        }
        publish();
    }

    /** Fork: where this item's output is being written. */
    @AnyThread
    public void itemDestination(@NonNull String key, @Nullable String destination) {
        synchronized (mItemLock) {
            ItemState s = mItems.get(key);
            if (s == null) {
                return;
            }
            s.destination = destination;
        }
        publish();
    }

    /** Fork: what this item is doing right now, with optional extra detail. */
    @AnyThread
    public void itemStage(@NonNull String key, @Nullable CharSequence stage, @Nullable CharSequence detail) {
        synchronized (mItemLock) {
            ItemState s = mItems.get(key);
            if (s == null) {
                return;
            }
            s.stage = stage;
            s.detail = detail;
        }
        publish();
    }

    /** Fork: add to the operation-wide byte total shown in the summary line. */
    @AnyThread
    public void addBytes(long delta) {
        if (delta <= 0) {
            return;
        }
        synchronized (mItemLock) {
            mBytes += delta;
        }
        // No publish() — the next stage or progress update carries it. Publishing
        // here would post a snapshot per tar member on a busy 8-thread backup.
    }

    /** Fork: this item is done; drop it from the in-flight list and count it. */
    @AnyThread
    public void itemFinished(@NonNull String key, boolean success) {
        synchronized (mItemLock) {
            if (mItems.remove(key) == null && success) {
                // Never started here (or already reported) — don't double-count.
                return;
            }
            ++mDone;
            if (!success) {
                ++mFailed;
            }
        }
        publish();
    }

    /** The operation this run performed; {@link BatchOpsManager#OP_NONE} before the first run. */
    @AnyThread
    @BatchOpsManager.OpType
    public int getOp() {
        return mOp;
    }

    /** The apps this run named, in the order the batch listed them. Never null, often empty. */
    @AnyThread
    @NonNull
    public List<UserPackagePair> getTargets() {
        return mTargets;
    }

    /** Called by the service when the operation finishes (or is cancelled). */
    @AnyThread
    public void finish() {
        synchronized (mPauseLock) {
            mPaused = false;
            mPauseLock.notifyAll();
        }
        mActive = false;
        synchronized (mItemLock) {
            mItems.clear();
        }
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
        List<Item> items;
        int done;
        int failed;
        long bytes;
        synchronized (mItemLock) {
            items = new ArrayList<>(mItems.size());
            for (ItemState s : mItems.values()) {
                items.add(new Item(s));
            }
            done = mDone;
            failed = mFailed;
            bytes = mBytes;
        }
        mState.postValue(new State(mActive, mTitle, mCurrent, mMax, mPaused,
                Collections.unmodifiableList(items), done, failed, bytes, mStartedAtRealtime));
    }
}
