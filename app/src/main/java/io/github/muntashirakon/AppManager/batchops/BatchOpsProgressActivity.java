// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.batchops;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.github.muntashirakon.AppManager.BaseActivity;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.fonts.ColorPrefs;
import io.github.muntashirakon.AppManager.main.RowPills;
import io.github.muntashirakon.AppManager.settings.Prefs;
import io.github.muntashirakon.AppManager.utils.ForkDialog;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;

/**
 * Fork (白い熊, +116): the full-page view of a running batch operation.
 *
 * <p>It replaces the one-line progress dialog, which could say what was happening but never what
 * <em>had</em> happened: a backup that spent four minutes inside one app showed a single line
 * and a bar, and when it failed there was nothing to look back at. This page keeps the whole
 * narrative — {@link OpLog} — and adds a header for the batch as a whole.
 *
 * <p><b>The activity owns nothing durable.</b> Both the log and the counters live in
 * process-wide singletons, so folding the phone (which destroys and rebuilds the activity, and
 * is a normal thing to do while a long backup runs) costs nothing but a redraw.
 *
 * <p><b>Following is a mode, not a rule.</b> The list sticks to the newest line, and stops the
 * moment 白い熊 scrolls up — a log that yanks itself back to the bottom cannot be read. A pill
 * offers the way back rather than taking it.
 */
public class BatchOpsProgressActivity extends BaseActivity {
    /** The one destructive control on this page. Not a theme colour: it must never be furniture. */
    private static final int CANCEL_RED = 0xFFFF6E6E;

    private RecyclerView mRecyclerView;
    private OpLogAdapter mAdapter;
    private LinearLayoutManager mLayoutManager;
    private AppCompatTextView mSummary;
    private AppCompatTextView mCurrent;
    private LinearProgressIndicator mProgress;
    private AppCompatTextView mPrimary;
    private AppCompatTextView mSecondary;
    private AppCompatTextView mTertiary;
    private AppCompatTextView mFollowButton;

    /** Whether the list is pinned to the newest line. Off as soon as the user scrolls up. */
    private boolean mFollow = true;

    @Override
    protected void onAuthenticated(@Nullable Bundle savedInstanceState) {
        setContentView(R.layout.activity_batch_ops_progress);
        setSupportActionBar(findViewById(R.id.toolbar));
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        mSummary = findViewById(R.id.op_summary);
        mCurrent = findViewById(R.id.op_current);
        mProgress = findViewById(R.id.progress_linear);
        mPrimary = findViewById(R.id.op_primary);
        mSecondary = findViewById(R.id.op_secondary);
        mTertiary = findViewById(R.id.op_tertiary);
        mFollowButton = findViewById(R.id.op_follow);
        mRecyclerView = findViewById(R.id.op_log);
        mLayoutManager = new LinearLayoutManager(this);
        // stackFromEnd is deliberately NOT used: it would pin a short log to the bottom of the
        // screen with empty space above it, which reads as though lines had been lost.
        mRecyclerView.setLayoutManager(mLayoutManager);
        mAdapter = new OpLogAdapter(this);
        mAdapter.setOnLineClickListener(this::showLine);
        mRecyclerView.setAdapter(mAdapter);
        mRecyclerView.setItemAnimator(null);  // a log that animates its inserts is unreadable
        applyColors();

        mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                boolean atBottom = !rv.canScrollVertically(1);
                if (atBottom) {
                    setFollow(true);
                } else if (dy < 0) {
                    // Only a deliberate scroll UP stops the follow. A downward scroll that has
                    // not yet reached the end is the user catching up, not leaving.
                    setFollow(false);
                }
            }
        });
        mFollowButton.setOnClickListener(v -> {
            setFollow(true);
            scrollToLatest();
        });

        mAdapter.submit(OpLog.getInstance().snapshot());
        scrollToLatest();

        OpLog.getInstance().getRevision().observe(this, revision -> onLogChanged());
        BatchOpsProgressMonitor.getInstance().getState().observe(this, this::onStateChanged);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Fork (白い熊, +138): while this page is up it IS the report — the log's last line says
        // "Finished — 1 of 1" — so the system's completion banner is noise flashed over the thing
        // it is announcing.
        BatchOpsProgressMonitor.getInstance().setHostForeground(true);
        // Re-read rather than consume: ColorPrefs.consumeChanged() is the MAIN LIST's flag, and
        // swallowing it here would leave the list showing the old colours. Re-reading a dozen
        // preferences on resume costs nothing.
        mAdapter.reloadAppearance();
        mAdapter.notifyDataSetChanged();
        applyColors();
        // applyColors() paints every pill in the accent; the bottom bar's roles are decided by
        // the operation's state, so it is re-read afterwards or Cancel loses its red.
        bindButtons(BatchOpsProgressMonitor.getInstance().getState().getValue());
    }

    @Override
    protected void onPause() {
        super.onPause();
        BatchOpsProgressMonitor.getInstance().setHostForeground(false);
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        getMenuInflater().inflate(R.menu.activity_batch_ops_progress_actions, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        }
        if (id == R.id.action_copy_log) {
            copyLog();
            return true;
        }
        if (id == R.id.action_save_log) {
            saveLog();
            return true;
        }
        if (id == R.id.action_scroll_latest) {
            setFollow(true);
            scrollToLatest();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ── Live updates ────────────────────────────────────────────────────────

    private void onLogChanged() {
        int appended = mAdapter.submit(OpLog.getInstance().snapshot());
        if (mFollow && appended != 0) {
            scrollToLatest();
        }
    }

    private void onStateChanged(@NonNull BatchOpsProgressMonitor.State state) {
        CharSequence title = state.title != null ? state.title : OpLog.getInstance().getTitle();
        setTitle(title != null ? title : getString(R.string.batch_ops));
        StringBuilder sb = new StringBuilder();
        sb.append(state.completed()).append(" / ").append(Math.max(state.max, state.completed()));
        if (state.failed > 0) {
            sb.append(" · ").append(getString(R.string.batch_progress_failed, state.failed));
        }
        if (state.startedAtRealtime > 0) {
            sb.append(" · ").append(OpLog.formatDuration(
                    SystemClock.elapsedRealtime() - state.startedAtRealtime));
        }
        if (state.bytes > 0) {
            sb.append(" · ").append(OpLog.formatSize(state.bytes));
        }
        mSummary.setText(sb);

        // The apps in flight RIGHT NOW — one line each, because a backup runs one thread per
        // core and naming only one of them would be a lie about what the other seven are doing.
        //
        // Fork (白い熊, +124): each line now carries the stage's own detail and how long that app
        // has been going. Without them a slow app — a sister app exporting four hundred messages
        // — sat there unchanged while the log raced on beneath it, and looked stuck rather than
        // busy. The elapsed time is the tell: it moves even when nothing else does.
        // EVERY app in flight gets a line (白い熊, +130). There is one worker per core, so the
        // header is as tall as the batch is wide — and that is the point of it: a glance says how
        // many apps are moving at once, and a cap of any size turns that into a guess.
        // Fork (白い熊, +140): Spannable, not StringBuilder — the stage carries its "5/7" in its
        // own colour and a plain builder would flatten it back to the app colour here.
        SpannableStringBuilder cur = new SpannableStringBuilder();
        for (BatchOpsProgressMonitor.Item item : state.items) {
            if (cur.length() > 0) {
                cur.append('\n');
            }
            cur.append(item.label != null ? item.label : item.packageName);
            if (!TextUtils.isEmpty(item.stage)) {
                cur.append(" · ").append(item.stage);
            }
            if (!TextUtils.isEmpty(item.detail)) {
                cur.append(" · ").append(item.detail);
            }
            if (item.startedAtRealtime > 0) {
                cur.append("  ").append(OpLog.formatDuration(
                        SystemClock.elapsedRealtime() - item.startedAtRealtime));
            }
        }
        if (cur.length() == 0 && state.paused) {
            cur.append(getString(R.string.batch_progress_paused_note));
        }
        mCurrent.setText(cur);
        mCurrent.setVisibility(cur.length() == 0 ? View.GONE : View.VISIBLE);

        if (mProgress != null) {
            if (state.active && state.max > 0) {
                mProgress.setIndeterminate(false);
                mProgress.setMax(state.max);
                mProgress.setProgressCompat(state.completed(), true);
                mProgress.setVisibility(View.VISIBLE);
            } else {
                mProgress.setVisibility(state.active ? View.VISIBLE : View.GONE);
            }
        }
        if (!state.active) {
            // The finished log is on screen: the main list's way-back bar has nothing to offer.
            BatchOpsProgressMonitor.getInstance().noteResultSeen();
        }
        bindButtons(state);
    }

    private void bindButtons(@Nullable BatchOpsProgressMonitor.State state) {
        BatchOpsProgressMonitor monitor = BatchOpsProgressMonitor.getInstance();
        if (state != null && state.active) {
            mSecondary.setText(state.paused ? R.string.continue_operation : R.string.pause);
            mSecondary.setOnClickListener(v -> {
                if (monitor.isPaused()) {
                    monitor.resume();
                } else {
                    monitor.pause();
                }
            });
            mSecondary.setVisibility(View.VISIBLE);
            // Fork (白い熊, +132): the way out that is not a way to stop it. Back has always
            // done exactly this — the work runs in a foreground service, and closing its page
            // has never touched it — but a bar offering only Cancel invited the opposite
            // conclusion, and it is not a conclusion anyone should have to test on a backup.
            mTertiary.setText(R.string.op_leave_running);
            mTertiary.setOnClickListener(v -> finish());
            mTertiary.setVisibility(View.VISIBLE);
            // Fork (白い熊, +143): a cancel is heard at the next checkpoint, not at the tap, and
            // a button that only greys out says nothing about which of those happened. It now
            // states that it was heard — and stays that way, because the operation ending is the
            // only thing that changes this bar.
            boolean cancelling = monitor.isCancelled();
            mPrimary.setText(cancelling ? R.string.op_cancelling : R.string.cancel);
            mPrimary.setOnClickListener(v -> {
                monitor.cancel();
                mPrimary.setText(R.string.op_cancelling);
                mPrimary.setEnabled(false);
            });
            mPrimary.setEnabled(!cancelling);
            // Cancel is the only destructive control on the page; it is drawn as one, so the
            // two neighbours cannot be mistaken for it.
            RowPills.styleActionPill(mPrimary, CANCEL_RED, false);
        } else {
            mSecondary.setText(R.string.op_log_copy);
            mSecondary.setOnClickListener(v -> copyLog());
            mSecondary.setVisibility(View.VISIBLE);
            mTertiary.setVisibility(View.GONE);
            mPrimary.setText(R.string.close);
            mPrimary.setEnabled(true);
            mPrimary.setOnClickListener(v -> finish());
            RowPills.styleActionPill(mPrimary, ColorPrefs.getColor(this, ColorPrefs.OPLOG_APP), false);
        }
    }

    // ── Following ───────────────────────────────────────────────────────────

    private void setFollow(boolean follow) {
        if (mFollow == follow) {
            return;
        }
        mFollow = follow;
        mFollowButton.setVisibility(follow ? View.GONE : View.VISIBLE);
    }

    private void scrollToLatest() {
        int count = mAdapter.getItemCount();
        if (count > 0) {
            mLayoutManager.scrollToPositionWithOffset(count - 1, 0);
        }
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    private void copyLog() {
        String text = OpLog.getInstance().asText();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || text.isEmpty()) {
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.batch_ops), text));
        UIUtils.displayShortToast(R.string.copied_to_clipboard);
    }

    /**
     * Write the log beside the backups. Never automatic: a file per batch would accumulate for
     * ever in the one directory 白い熊 actually reads.
     */
    private void saveLog() {
        String text = OpLog.getInstance().asText();
        if (text.isEmpty()) {
            return;
        }
        ThreadUtils.postOnBackgroundThread(() -> {
            String path = null;
            try {
                String dir = Prefs.Storage.getSettingsExportDirectory();
                File parent = TextUtils.isEmpty(dir) ? getExternalFilesDir(null) : new File(dir);
                if (parent != null && (parent.isDirectory() || parent.mkdirs())) {
                    String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)
                            .format(new Date());
                    File out = new File(parent, "shiroikuma-oyokanri_batch-log_" + stamp + ".txt");
                    try (OutputStream os = new FileOutputStream(out)) {
                        os.write(text.getBytes(StandardCharsets.UTF_8));
                    }
                    path = out.getAbsolutePath();
                }
            } catch (Throwable ignore) {
            }
            String finalPath = path;
            ThreadUtils.postOnMainThread(() -> {
                if (finalPath != null) {
                    UIUtils.displayLongToast(R.string.op_log_saved, finalPath);
                } else {
                    UIUtils.displayLongToast(R.string.op_log_save_failed);
                }
            });
        });
    }

    /**
     * The whole of one line. Lines are ellipsized in the middle so the ladder stays aligned, and
     * a path that has lost its middle is exactly the line worth reading in full.
     */
    private void showLine(@NonNull OpLog.Entry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append(OpLogFormat.timestamp(entry.atMillis)).append('\n').append(entry.text);
        if (!TextUtils.isEmpty(entry.detail)) {
            sb.append('\n').append(entry.detail);
        }
        ForkDialog.present(ForkDialog.builder(this)
                .setMessage(sb.toString())
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.copy, (dialog, which) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.batch_ops), sb.toString()));
                        UIUtils.displayShortToast(R.string.copied_to_clipboard);
                    }
                }));
    }

    private void applyColors() {
        int rule = ColorPrefs.getColor(this, ColorPrefs.OPLOG_GUIDE);
        findViewById(R.id.op_header_rule).setBackgroundColor(rule);
        findViewById(R.id.op_bar_rule).setBackgroundColor(rule);
        mSummary.setTextColor(ColorPrefs.getColor(this, ColorPrefs.OPLOG_BATCH));
        // The in-flight lines are the app colour, not the stage colour: they name apps, and at
        // the stage colour they read as finished lines of the log above them.
        mCurrent.setTextColor(ColorPrefs.getColor(this, ColorPrefs.OPLOG_APP));
        // The buttons are painted explicitly rather than left to the theme: a Material tonal
        // button resolves its own container colour, and on this palette that can land yellow on
        // yellow. Nothing here is allowed to depend on which theme attribute survived.
        // Fork (白い熊, +118): real pills, in the fork's own pill language, rather than the
        // bare text buttons a Material TextButton draws — which on this palette read as loose
        // words rather than as controls. One builder, shared with the shelf and the panes.
        int accent = ColorPrefs.getColor(this, ColorPrefs.OPLOG_APP);
        RowPills.styleActionPill(mFollowButton, accent, false);
        RowPills.styleActionPill(mPrimary, accent, false);
        RowPills.styleActionPill(mSecondary, accent, false);
        RowPills.styleActionPill(mTertiary, accent, false);
    }

    /**
     * Open the page for a batch that has just started.
     *
     * <p>The rule 白い熊 set: <b>always for a backup or a restore</b>, whatever its size — those
     * are the operations worth watching — and for anything else only when more than one app is
     * involved, because a full screen for a two-second freeze of a single app is in the way
     * rather than useful.
     */
    public static boolean shouldAutoOpen(int op, int packageCount) {
        switch (op) {
            case BatchOpsManager.OP_BACKUP:
            case BatchOpsManager.OP_RESTORE_BACKUP:
            case BatchOpsManager.OP_BACKUP_APK:
            case BatchOpsManager.OP_DELETE_BACKUP:
            case BatchOpsManager.OP_IMPORT_BACKUPS:
                return true;
            default:
                return packageCount > 1;
        }
    }

    /**
     * An intent that reuses the page when it is already open rather than stacking a second copy
     * — one batch, one log, one page. {@code NEW_TASK} is required because the service (which is
     * not an activity) launches it too, and it lands in the app's existing task all the same.
     */
    @NonNull
    public static Intent getIntent(@NonNull Context context) {
        Intent intent = new Intent(context, BatchOpsProgressActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return intent;
    }
}
