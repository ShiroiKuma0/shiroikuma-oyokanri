// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.batchops;

import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fork (白い熊, +116): the running narrative of a batch operation — every app, every stage
 * within it, and every file it produced — held for {@link BatchOpsProgressActivity} to draw.
 *
 * <p><b>Why it lives beside {@link BatchOpsProgressMonitor} rather than inside the activity.</b>
 * The monitor answers "what is happening right now": a handful of in-flight items, replaced as
 * they move on. That is the right shape for a progress bar and the wrong shape for a log, which
 * is precisely the history the monitor throws away. And the page that draws it is destroyed and
 * rebuilt whenever the phone is folded, unfolded or rotated — on a tri-fold that is a normal
 * thing to do while watching a long backup — so a log owned by the activity would be lost at the
 * exact moment its owner wanted to scroll back through it.
 *
 * <p><b>Interleaving is the hard part, and it is not a display problem.</b> Backup and restore
 * run one thread per core, so at any instant up to eight apps are genuinely being worked on and
 * their events arrive shuffled. A plain indented tree would then attribute one app's files to
 * whichever app printed the last header — a log that lies is worse than no log. So whenever the
 * emitting app differs from the last one to emit, a <em>continuation</em> header is written
 * first ({@link Entry#continued}), and every line beneath it belongs to the app named directly
 * above it. Nothing is ever inferred from position alone.
 *
 * <p>Publication is a revision counter rather than a copied list: {@link MutableLiveData
 * #postValue} keeps only the newest pending value, so a burst of a thousand events from eight
 * threads collapses into one redraw instead of a thousand list copies.
 */
public final class OpLog {
    /** The batch itself: what is being done, to how many apps, with which options. */
    public static final int KIND_BATCH = 0;
    /** One app. Depth 1. */
    public static final int KIND_APP = 1;
    /** A stage within an app — APK, Data, Extras, App-supplied data… Depth 2. */
    public static final int KIND_STAGE = 2;
    /** A leaf: one file, one category, one count. Depth 3. */
    public static final int KIND_ITEM = 3;
    /** An app or stage that finished cleanly. */
    public static final int KIND_OK = 4;
    /** An app or stage that failed, and why. */
    public static final int KIND_FAIL = 5;
    /** Deliberately not done — nothing to back up, every category unticked, no door. */
    public static final int KIND_SKIP = 6;
    /** Something worth reading that did not stop the work. */
    public static final int KIND_WARN = 7;
    /** The closing summary block. */
    public static final int KIND_SUMMARY = 8;

    /**
     * Most of a long batch is one app after another; 20 000 lines covers roughly 1 300 apps at
     * the depth a backup writes, which is more than the phone holds. Beyond that the oldest are
     * dropped in blocks — trimming one line at a time would copy the whole list per event.
     */
    private static final int MAX_ENTRIES = 20_000;
    private static final int TRIM_BLOCK = 2_000;

    /** One line. Immutable, so the adapter can read it off the main thread's snapshot safely. */
    public static final class Entry {
        /** Wall-clock time, for the timestamp column. */
        public final long atMillis;
        public final int depth;
        public final int kind;
        @NonNull
        public final CharSequence text;
        @Nullable
        public final CharSequence detail;
        /**
         * This app header is a re-announcement after another app's lines interrupted it, not a
         * new app. Drawn faded, so the eye follows the first mention rather than counting them.
         */
        public final boolean continued;

        Entry(long atMillis, int depth, int kind, @NonNull CharSequence text,
              @Nullable CharSequence detail, boolean continued) {
            this.atMillis = atMillis;
            this.depth = depth;
            this.kind = kind;
            this.text = text;
            this.detail = detail;
            this.continued = continued;
        }
    }

    /** What we know about one app while its lines are being written. */
    private static final class AppState {
        @NonNull
        final CharSequence label;
        @NonNull
        final String packageName;
        final int userId;
        final long startedAtRealtime;
        long bytes;

        AppState(@NonNull CharSequence label, @NonNull String packageName, int userId) {
            this.label = label;
            this.packageName = packageName;
            this.userId = userId;
            this.startedAtRealtime = SystemClock.elapsedRealtime();
        }
    }

    private static final OpLog INSTANCE = new OpLog();

    @NonNull
    public static OpLog getInstance() {
        return INSTANCE;
    }

    private final Object mLock = new Object();
    private final List<Entry> mEntries = new ArrayList<>();
    private final Map<String, AppState> mApps = new HashMap<>();
    private final MutableLiveData<Long> mRevision = new MutableLiveData<>(0L);
    private long mSeq;
    /** How many lines have been dropped off the front, so the adapter can tell a trim apart. */
    private long mDropped;
    @Nullable
    private String mLastKey;
    @Nullable
    private CharSequence mTitle;

    private OpLog() {
    }

    /** Bumped once per appended line; observers re-read {@link #snapshot()}. */
    @NonNull
    public LiveData<Long> getRevision() {
        return mRevision;
    }

    @Nullable
    public CharSequence getTitle() {
        synchronized (mLock) {
            return mTitle;
        }
    }

    public long getDropped() {
        synchronized (mLock) {
            return mDropped;
        }
    }

    /** A stable copy for the adapter. Cheap: entries are immutable, so this copies references. */
    @NonNull
    public List<Entry> snapshot() {
        synchronized (mLock) {
            return new ArrayList<>(mEntries);
        }
    }

    // ── Writing ─────────────────────────────────────────────────────────────

    /** A new batch. Clears everything: the previous run's log is not this run's. */
    @AnyThread
    public void begin(@NonNull CharSequence title, @Nullable CharSequence detail) {
        synchronized (mLock) {
            mEntries.clear();
            mApps.clear();
            mDropped = 0;
            mLastKey = null;
            mTitle = title;
        }
        append(0, KIND_BATCH, title, detail, false);
    }

    /** An app has been picked up by a worker thread. Balanced by {@link #appFinished}. */
    @AnyThread
    public void appStarted(@NonNull String key, @Nullable CharSequence label,
                           @NonNull String packageName, int userId) {
        CharSequence name = TextUtils.isEmpty(label) ? packageName : label;
        synchronized (mLock) {
            mApps.put(key, new AppState(name, packageName, userId));
            mLastKey = key;
        }
        append(1, KIND_APP, name, subtitle(packageName, userId), false);
    }

    /** A stage within one app: APK, Data, Extras, App-supplied data, Finalising. */
    @AnyThread
    public void stage(@Nullable String key, @NonNull CharSequence stage, @Nullable CharSequence detail) {
        ensureContext(key);
        append(2, KIND_STAGE, stage, detail, false);
    }

    /** A leaf under the current stage: one file and its size, one category, one count. */
    @AnyThread
    public void item(@Nullable String key, @NonNull CharSequence text, @Nullable CharSequence detail) {
        ensureContext(key);
        append(3, KIND_ITEM, text, detail, false);
    }

    /** Something that did not stop the work but should be read. */
    @AnyThread
    public void warn(@Nullable String key, @NonNull CharSequence text, @Nullable CharSequence detail) {
        ensureContext(key);
        append(3, KIND_WARN, text, detail, false);
    }

    /** Deliberately not done. Never an error — say so in its own colour and move on. */
    @AnyThread
    public void skip(@Nullable String key, @NonNull CharSequence text, @Nullable CharSequence detail) {
        ensureContext(key);
        append(2, KIND_SKIP, text, detail, false);
    }

    /** Bytes this app has produced. No line of its own; it lands in the app's closing line. */
    @AnyThread
    public void bytes(@Nullable String key, long delta) {
        if (key == null || delta <= 0) {
            return;
        }
        synchronized (mLock) {
            AppState s = mApps.get(key);
            if (s != null) {
                s.bytes += delta;
            }
        }
    }

    /** This app is done. Prints how long it took and what it produced, in its own colour. */
    @AnyThread
    public void appFinished(@NonNull String key, boolean ok, @Nullable CharSequence detail) {
        AppState s;
        synchronized (mLock) {
            s = mApps.remove(key);
            if (key.equals(mLastKey)) {
                mLastKey = null;
            }
        }
        CharSequence text;
        if (s != null) {
            long elapsed = SystemClock.elapsedRealtime() - s.startedAtRealtime;
            StringBuilder sb = new StringBuilder(s.label);
            sb.append(" · ").append(formatDuration(elapsed));
            if (s.bytes > 0) {
                sb.append(" · ").append(formatSize(s.bytes));
            }
            text = sb;
        } else {
            text = key;
        }
        if (!ok && !TextUtils.isEmpty(detail)) {
            // Fork (白い熊, +157): a failure's reason gets a line of its own.
            //
            // Appended to the summary it became the tail of "白い熊 自由作業盤 · 2.1 s · 14.1 MB ·
            // Row too big to fit into CursorWindow…", and rows here are single-line and
            // MIDDLE-ellipsized, so what survived on screen was "14.1…to CursorWindow" — the
            // size and the end of a sentence, with the part that names the failure elided. On
            // its own line the message starts at column 0, which is the half that must be
            // readable. Both surfaces follow, because the renderer is shared with asText()
            // (+140) and neither knows anything about this.
            append(1, KIND_FAIL, text, null, false);
            append(2, KIND_FAIL, detail, null, false);
        } else {
            append(1, ok ? KIND_OK : KIND_FAIL, text, detail, false);
        }
    }

    /**
     * One line for an op that has no stages — freeze, uninstall, force-stop and the rest. They
     * run sequentially and announce each app before working on it, so there is nothing deeper to
     * say and no closing line to pair with.
     */
    @AnyThread
    public void appLine(@Nullable CharSequence label, @NonNull String packageName, int userId) {
        synchronized (mLock) {
            mLastKey = null;
        }
        append(1, KIND_APP, TextUtils.isEmpty(label) ? packageName : label,
                subtitle(packageName, userId), false);
    }

    /**
     * A line that belongs to the batch rather than to any app — an imported file, a note about
     * the run as a whole. Depth 1, so it sits where an app would.
     */
    @AnyThread
    public void note(@NonNull CharSequence text, @Nullable CharSequence detail) {
        synchronized (mLock) {
            mLastKey = null;
        }
        append(1, KIND_ITEM, text, detail, false);
    }

    /** The closing block. Called once, after the last app. */
    @AnyThread
    public void end(@NonNull CharSequence text, @Nullable CharSequence detail) {
        synchronized (mLock) {
            mApps.clear();
            mLastKey = null;
        }
        append(0, KIND_SUMMARY, text, detail, false);
    }

    /** A summary line beneath {@link #end}, e.g. one failed package. */
    @AnyThread
    public void endDetail(@NonNull CharSequence text, boolean failed) {
        append(1, failed ? KIND_FAIL : KIND_ITEM, text, null, false);
    }

    // ── The whole log as text, for Copy and Save ─────────────────────────────

    @NonNull
    public String asText() {
        List<Entry> entries = snapshot();
        StringBuilder sb = new StringBuilder(entries.size() * 48);
        for (Entry e : entries) {
            sb.append(OpLogFormat.timestamp(e.atMillis)).append(' ');
            for (int i = 0; i < e.depth; ++i) {
                sb.append("    ");
            }
            sb.append(OpLogFormat.marker(e.kind, e.continued)).append(e.text);
            if (!TextUtils.isEmpty(e.detail)) {
                sb.append("  ").append(e.detail);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ── Internals ───────────────────────────────────────────────────────────

    /**
     * Make sure the line about to be written sits under the app it belongs to. When another
     * thread's app printed last, its header is re-announced first — see the class comment.
     */
    private void ensureContext(@Nullable String key) {
        if (key == null) {
            return;
        }
        AppState s;
        synchronized (mLock) {
            if (key.equals(mLastKey)) {
                return;
            }
            s = mApps.get(key);
            if (s == null) {
                // Finished, or never opened: nothing to re-announce. The line still lands where
                // it was asked to land rather than being dropped.
                return;
            }
            mLastKey = key;
        }
        append(1, KIND_APP, s.label, subtitle(s.packageName, s.userId), true);
    }

    private void append(int depth, int kind, @NonNull CharSequence text,
                        @Nullable CharSequence detail, boolean continued) {
        long revision;
        synchronized (mLock) {
            mEntries.add(new Entry(System.currentTimeMillis(), depth, kind, text, detail, continued));
            if (mEntries.size() > MAX_ENTRIES) {
                mEntries.subList(0, TRIM_BLOCK).clear();
                mDropped += TRIM_BLOCK;
            }
            revision = ++mSeq;
        }
        mRevision.postValue(revision);
    }

    @NonNull
    private static String subtitle(@NonNull String packageName, int userId) {
        return userId == 0 ? packageName : packageName + " · user " + userId;
    }

    @NonNull
    public static String formatDuration(long ms) {
        if (ms < 1000) {
            return ms + " ms";
        }
        long s = ms / 1000;
        if (s < 60) {
            return String.format(Locale.ROOT, "%.1f s", ms / 1000f);
        }
        long m = s / 60;
        if (m < 60) {
            return m + "m " + (s % 60) + "s";
        }
        return (m / 60) + "h " + (m % 60) + "m";
    }

    /**
     * Fork (白い熊, +132): a plain count with thousands separators.
     *
     * <p>A raw {@code 809500672/6212623411} is a wall of digits that has to be counted with a
     * fingertip to be read at all, and the whole point of the in-flight line is that it can be
     * read at a glance. Grouped, the magnitude is legible without counting.
     */
    @NonNull
    public static String formatCount(long value) {
        return String.format(Locale.getDefault(), "%,d", value);
    }

    @NonNull
    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024d;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.1f kB", kb);
        }
        double mb = kb / 1024d;
        if (mb < 1024) {
            return String.format(Locale.ROOT, "%.1f MB", mb);
        }
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024d);
    }
}
