// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.lifecycle.Observer;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.batchops.BatchOpsProgressMonitor;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;

/**
 * Fork: the in-app pop-up that mirrors batch-operation progress (the same info
 * the foreground-service notification shows) in the main window, and lets the
 * user pause/continue or cancel the operation. Styled from the configurable fork
 * theme (yellow-on-black bordered by default).
 *
 * <p>Lifecycle is driven by {@link MainActivity}: it is {@link #show() shown} on
 * the {@code ACTION_BATCH_OPS_STARTED} broadcast (or on resume while an op is
 * still running) and {@link #dismiss() dismissed} on completion. While shown it
 * observes {@link BatchOpsProgressMonitor} for live counts.
 *
 * <p><b>It says more than the counter, because the counter can stand still for
 * minutes.</b> A 550-app backup moves "52 / 550" once per app, and a single app
 * with a large data directory holds it there for as long as its archive takes —
 * during which the old dialog was indistinguishable from a hung one. So each app
 * in flight is listed by name and id, with the directory it is writing to and
 * the stage it has reached, over a summary line carrying elapsed time, the
 * estimate, how many are running, how much has been written and how many failed.
 *
 * <p>A once-a-second tick redraws the times. Progress events alone cannot: they
 * arrive when something happens, and the interesting case is precisely when
 * nothing has happened for a while.
 */
@UiThread
public class BatchProgressDialog {
    private static final long TICK_MS = 1000L;
    /** Fraction of the window the in-flight list may occupy before it scrolls. */
    private static final float ITEM_LIST_MAX_HEIGHT_FRACTION = 0.4f;
    /** Below this, an estimate is guesswork dressed up as a number. */
    private static final int ETA_MIN_COMPLETED = 2;
    private static final long ETA_MIN_ELAPSED_MS = 5000L;

    private final Activity mActivity;
    private final BatchOpsProgressMonitor mMonitor = BatchOpsProgressMonitor.getInstance();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    /** In-flight rows by item key, so a redraw updates views instead of rebuilding them. */
    private final LinkedHashMap<String, ItemViews> mItemViews = new LinkedHashMap<>();

    private Dialog mDialog;
    private TextView mTitleView;
    private TextView mCounterView;
    private TextView mSummaryView;
    private TextView mNoteView;
    private MaxHeightScrollView mItemScroller;
    private LinearLayout mItemContainer;
    private LinearProgressIndicator mProgressBar;
    private MaterialButton mPauseButton;
    private int mTextColor;
    @Nullable
    private BatchOpsProgressMonitor.State mLastState;

    private final Observer<BatchOpsProgressMonitor.State> mObserver = this::onState;

    private final Runnable mTicker = new Runnable() {
        @Override
        public void run() {
            if (mDialog == null) {
                return;
            }
            if (mLastState != null) {
                // Only the clocks move between progress events.
                bindSummary(mLastState);
                bindElapsedTimes(mLastState);
            }
            mHandler.postDelayed(this, TICK_MS);
        }
    };

    /** The views of one in-flight row, kept so an update need not re-inflate. */
    private static final class ItemViews {
        final View root;
        final TextView label;
        final TextView elapsed;
        final TextView packageName;
        final TextView destination;
        final TextView stage;

        ItemViews(@NonNull View root) {
            this.root = root;
            label = root.findViewById(R.id.batch_item_label);
            elapsed = root.findViewById(R.id.batch_item_elapsed);
            packageName = root.findViewById(R.id.batch_item_package);
            destination = root.findViewById(R.id.batch_item_dest);
            stage = root.findViewById(R.id.batch_item_stage);
        }
    }

    public BatchProgressDialog(@NonNull Activity activity) {
        mActivity = activity;
    }

    public boolean isShowing() {
        return mDialog != null && mDialog.isShowing();
    }

    public void show() {
        if (isShowing()) {
            return;
        }
        View view = mActivity.getLayoutInflater().inflate(R.layout.dialog_batch_progress_shiroikuma, null);
        mTitleView = view.findViewById(R.id.batch_progress_title);
        mCounterView = view.findViewById(R.id.batch_progress_counter);
        mSummaryView = view.findViewById(R.id.batch_progress_summary);
        mNoteView = view.findViewById(R.id.batch_progress_note);
        mItemScroller = view.findViewById(R.id.batch_progress_item_scroller);
        mItemContainer = view.findViewById(R.id.batch_progress_items);
        mProgressBar = view.findViewById(R.id.batch_progress_bar);
        mPauseButton = view.findViewById(R.id.batch_progress_pause);
        MaterialButton cancelButton = view.findViewById(R.id.batch_progress_cancel);
        mItemViews.clear();
        mItemContainer.removeAllViews();

        DisplayMetrics metrics = mActivity.getResources().getDisplayMetrics();
        mItemScroller.setMaxHeight((int) (metrics.heightPixels * ITEM_LIST_MAX_HEIGHT_FRACTION));
        // Only two or three blocks fit under that ceiling while eight apps are in
        // flight, and this is a readout to be watched rather than operated — so
        // it walks through them on its own until someone touches it.
        mItemScroller.setAutoScrollEnabled(true);

        applyTheme(view.findViewById(R.id.batch_progress_container), cancelButton);

        mPauseButton.setOnClickListener(v -> {
            if (mMonitor.isPaused()) {
                mMonitor.resume();
            } else {
                mMonitor.pause();
            }
            updatePauseLabel(mMonitor.isPaused());
        });
        cancelButton.setOnClickListener(v -> {
            mMonitor.cancel();
            dismiss();
        });

        Dialog dialog = new Dialog(mActivity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(view);
        // The user must explicitly Pause (stay) or Cancel (exit); a stray back
        // press or outside tap must not silently abandon the dialog.
        dialog.setCancelable(false);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout((int) (metrics.widthPixels * 0.86f), WindowManager.LayoutParams.WRAP_CONTENT);
        }
        mDialog = dialog;

        // Seed from the current snapshot, then observe for updates.
        BatchOpsProgressMonitor.State current = mMonitor.getState().getValue();
        if (current != null) {
            onState(current);
        }
        mMonitor.getState().observeForever(mObserver);
        mHandler.postDelayed(mTicker, TICK_MS);
        dialog.show();
    }

    public void dismiss() {
        mMonitor.getState().removeObserver(mObserver);
        mHandler.removeCallbacks(mTicker);
        if (mItemScroller != null) {
            mItemScroller.setAutoScrollEnabled(false);
        }
        if (mDialog != null) {
            if (mDialog.isShowing()) {
                mDialog.dismiss();
            }
            mDialog = null;
        }
        mItemViews.clear();
        mLastState = null;
    }

    private void applyTheme(@NonNull View container, @NonNull MaterialButton cancelButton) {
        mTextColor = ForkThemeUtils.getTextColor();
        container.setBackground(ForkThemeUtils.makeThemedBackground(container.getContext(), 16f));
        mTitleView.setTextColor(mTextColor);
        mCounterView.setTextColor(mTextColor);
        mSummaryView.setTextColor(mTextColor);
        mNoteView.setTextColor(mTextColor);
        ColorStateList accent = ColorStateList.valueOf(mTextColor);
        mPauseButton.setTextColor(accent);
        mPauseButton.setRippleColor(accent);
        cancelButton.setTextColor(accent);
        cancelButton.setRippleColor(accent);
        mProgressBar.setIndicatorColor(mTextColor);
        // Dim the track to ~20% of the indicator colour so it reads as a faint
        // groove on the themed background.
        mProgressBar.setTrackColor((mTextColor & 0x00FFFFFF) | 0x33000000);
    }

    private void onState(@NonNull BatchOpsProgressMonitor.State state) {
        if (mDialog == null) {
            return;
        }
        mLastState = state;
        mTitleView.setText(state.title);
        int max = Math.max(state.max, 0);
        int current = Math.max(0, Math.min(state.current, max));
        String counter = String.format(Locale.getDefault(), "%d / %d", current, max);
        if (max > 0) {
            counter += String.format(Locale.getDefault(), " · %d%%", current * 100 / max);
        }
        mCounterView.setText(counter);
        mProgressBar.setMax(Math.max(max, 1));
        mProgressBar.setProgressCompat(current, true);
        bindSummary(state);
        bindItems(state);
        bindNote(state);
        updatePauseLabel(state.paused);
    }

    /** Elapsed, estimate, in-flight count, bytes written and failures, in that order. */
    private void bindSummary(@NonNull BatchOpsProgressMonitor.State state) {
        long elapsed = state.startedAtRealtime > 0
                ? SystemClock.elapsedRealtime() - state.startedAtRealtime
                : 0L;
        List<String> parts = new ArrayList<>(5);
        if (elapsed > 0) {
            parts.add(mActivity.getString(R.string.batch_progress_elapsed, formatDuration(elapsed)));
        }
        String eta = estimateRemaining(state, elapsed);
        if (eta != null) {
            parts.add(eta);
        }
        // Only worth saying when it is more than one: the list below already
        // shows a single in-flight app, and "1 running" beside it is noise.
        int running = state.items.size();
        if (running > 1) {
            parts.add(mActivity.getString(R.string.batch_progress_running, running));
        }
        if (state.bytes > 0) {
            parts.add(mActivity.getString(R.string.batch_progress_written,
                    Formatter.formatShortFileSize(mActivity, state.bytes)));
        }
        if (state.failed > 0) {
            parts.add(mActivity.getString(R.string.batch_progress_failed, state.failed));
        }
        if (parts.isEmpty()) {
            mSummaryView.setVisibility(View.GONE);
        } else {
            mSummaryView.setText(TextUtils.join(" · ", parts));
            mSummaryView.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Time left, from the rate the batch has actually achieved. Withheld until
     * a couple of apps have finished — before that the "rate" is one sample of a
     * quantity that varies by three orders of magnitude between a stub app and a
     * game, and a wildly wrong number is worse than none.
     */
    @Nullable
    private String estimateRemaining(@NonNull BatchOpsProgressMonitor.State state, long elapsed) {
        int completed = state.completed();
        int max = state.max;
        if (max <= 0 || completed >= max) {
            return null;
        }
        if (completed < ETA_MIN_COMPLETED || elapsed < ETA_MIN_ELAPSED_MS) {
            return mActivity.getString(R.string.batch_progress_eta_unknown);
        }
        long remaining = (long) ((double) elapsed / completed * (max - completed));
        return mActivity.getString(R.string.batch_progress_eta, formatDuration(remaining));
    }

    /**
     * Sync the in-flight rows against the snapshot. Views are reused per item
     * key: a rebuild every state change would flicker, and on a busy backup the
     * state changes several times a second.
     */
    private void bindItems(@NonNull BatchOpsProgressMonitor.State state) {
        Set<String> live = new HashSet<>(state.items.size());
        for (BatchOpsProgressMonitor.Item item : state.items) {
            live.add(item.key);
        }
        for (String key : new ArrayList<>(mItemViews.keySet())) {
            if (!live.contains(key)) {
                ItemViews views = mItemViews.remove(key);
                if (views != null) {
                    mItemContainer.removeView(views.root);
                }
            }
        }
        LayoutInflater inflater = LayoutInflater.from(mItemContainer.getContext());
        for (int i = 0; i < state.items.size(); ++i) {
            BatchOpsProgressMonitor.Item item = state.items.get(i);
            ItemViews views = mItemViews.get(item.key);
            if (views == null) {
                views = new ItemViews(inflater.inflate(R.layout.view_batch_progress_item, mItemContainer, false));
                tint(views);
                mItemViews.put(item.key, views);
                mItemContainer.addView(views.root);
            }
            bindItem(views, item);
        }
        // Keep the container in the snapshot's order — items come and go as
        // worker threads pick up apps, so an appended row can be out of place.
        for (int i = 0; i < state.items.size(); ++i) {
            ItemViews views = mItemViews.get(state.items.get(i).key);
            if (views != null && mItemContainer.indexOfChild(views.root) != i) {
                mItemContainer.removeView(views.root);
                mItemContainer.addView(views.root, i);
            }
        }
        mItemScroller.setVisibility(state.items.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void bindItem(@NonNull ItemViews views, @NonNull BatchOpsProgressMonitor.Item item) {
        boolean hasLabel = item.label != null && item.label.length() > 0;
        views.label.setText(hasLabel ? item.label : item.packageName);
        // With no label the id is already the headline; repeating it says nothing.
        views.packageName.setText(item.packageName);
        views.packageName.setVisibility(hasLabel ? View.VISIBLE : View.GONE);
        if (item.destination != null) {
            views.destination.setText(mActivity.getString(R.string.batch_progress_dest, item.destination));
            views.destination.setVisibility(View.VISIBLE);
        } else {
            views.destination.setVisibility(View.GONE);
        }
        CharSequence stage = item.stage;
        if (stage != null && item.detail != null && item.detail.length() > 0) {
            stage = stage + " · " + item.detail;
        }
        if (stage != null && stage.length() > 0) {
            views.stage.setText(stage);
            views.stage.setVisibility(View.VISIBLE);
        } else {
            views.stage.setVisibility(View.GONE);
        }
        bindElapsed(views, item);
    }

    private void bindElapsedTimes(@NonNull BatchOpsProgressMonitor.State state) {
        for (BatchOpsProgressMonitor.Item item : state.items) {
            ItemViews views = mItemViews.get(item.key);
            if (views != null) {
                bindElapsed(views, item);
            }
        }
    }

    private void bindElapsed(@NonNull ItemViews views, @NonNull BatchOpsProgressMonitor.Item item) {
        long elapsed = SystemClock.elapsedRealtime() - item.startedAtRealtime;
        if (elapsed < 1000L) {
            views.elapsed.setVisibility(View.GONE);
        } else {
            views.elapsed.setText(formatDuration(elapsed));
            views.elapsed.setVisibility(View.VISIBLE);
        }
    }

    private void bindNote(@NonNull BatchOpsProgressMonitor.State state) {
        CharSequence note = null;
        if (state.paused) {
            // Pause is a checkpoint between apps, not a freeze: whatever a worker
            // thread has already started runs to the end. Say so, or the apps that
            // keep finishing after the tap read as the button not working.
            note = mActivity.getText(R.string.batch_progress_paused_note);
        } else if (state.items.isEmpty() && state.active) {
            note = mActivity.getText(R.string.batch_progress_waiting);
        }
        if (note != null) {
            mNoteView.setText(note);
            mNoteView.setVisibility(View.VISIBLE);
        } else {
            mNoteView.setVisibility(View.GONE);
        }
    }

    private void tint(@NonNull ItemViews views) {
        views.label.setTextColor(mTextColor);
        views.elapsed.setTextColor(mTextColor);
        views.packageName.setTextColor(mTextColor);
        views.destination.setTextColor(mTextColor);
        views.stage.setTextColor(mTextColor);
    }

    /**
     * Coarse but stable: two units at most, and the larger unit never disappears
     * mid-count. A duration that flickers between formats is unreadable at a
     * glance, which is the only way this one is ever read.
     *
     * <p>The unit names come from resources rather than being spelled here, so a
     * Japanese reader gets 12分04秒 rather than 12m 04s. {@code DateUtils} is not
     * reused for this: its {@code getFormattedDuration} is built for usage
     * statistics — it drops seconds and answers "less than a minute" below one,
     * which is exactly the range this readout opens in, and its unit count varies
     * with the value, so the field would jitter every second.
     */
    @NonNull
    private String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return mActivity.getString(R.string.duration_hours_minutes, hours, minutes);
        }
        if (minutes > 0) {
            return mActivity.getString(R.string.duration_minutes_seconds, minutes, seconds);
        }
        return mActivity.getString(R.string.duration_seconds, seconds);
    }

    private void updatePauseLabel(boolean paused) {
        mPauseButton.setText(paused ? R.string.continue_operation : R.string.pause);
    }
}
