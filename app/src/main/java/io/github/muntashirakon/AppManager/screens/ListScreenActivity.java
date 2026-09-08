// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.screens;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.List;

import io.github.muntashirakon.AppManager.BaseActivity;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;
import androidx.core.content.ContextCompat;
import io.github.muntashirakon.AppManager.batchops.BatchOpsService;
import io.github.muntashirakon.AppManager.batchops.BatchQueueItem;
import io.github.muntashirakon.AppManager.batchops.struct.BatchBackupOptions;
import io.github.muntashirakon.AppManager.main.RowPills;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.battery.BatteryUsageActivity;
import io.github.muntashirakon.AppManager.fonts.ColorPrefs;
import io.github.muntashirakon.AppManager.main.MainSeparatorDecoration;
import io.github.muntashirakon.AppManager.main.ShelfPrefs;
import io.github.muntashirakon.AppManager.processreaper.ProcessMonitorActivity;
import io.github.muntashirakon.AppManager.utils.ForkDialog;
import io.github.muntashirakon.AppManager.utils.LayoutGeometry;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;
import io.github.muntashirakon.widget.SwipeRefreshLayout;

/**
 * Fork (白い熊, +118): the one activity every sibling screen is drawn in.
 *
 * <p>Three screens, one shell. What differs between 保存一覧, 盗み見一覧 and 仲間 is the data and
 * the right-hand column, and that is exactly what a {@link ScreenSource} supplies — so the layout,
 * the loading, the sort menu, the column picker and the separators are written once.
 *
 * <p><b>Loading is always off the main thread.</b> One of these walks the backup directory
 * summing file sizes and another runs a resolver pass per installed app; neither is a thing to do
 * while the window is trying to draw.
 */
public class ListScreenActivity extends BaseActivity {
    public static final String EXTRA_SCREEN = "screen";
    /**
     * Fork (白い熊, +146): the apps carrying one tracker. Not a shelf screen — it is entered from
     * a tracker pill and its subject is the tracker's name, so it takes an extra rather than an id.
     */
    public static final String EXTRA_TRACKER = "tracker";

    /** The screen listing every app that carries {@code tracker}. */
    @NonNull
    public static Intent intentForTracker(@NonNull Context context, @NonNull String tracker) {
        Intent intent = new Intent(context, ListScreenActivity.class);
        intent.putExtra(EXTRA_TRACKER, tracker);
        return intent;
    }

    /**
     * The intent for a screen id.
     *
     * <p>Fork (白い熊, +167): 保存一覧, 盗み見一覧 and 仲間 are no longer screens — they became lenses on
     * the main list (+162/+166) and {@code MainActivity.applyShelfPill} intercepts their ids before
     * reaching here. What is left are the two that were always separate activities, because their
     * rows are not apps: the battery screen is per-uid with its own history chart, and the process
     * monitor is per-pid with grouping and leak clusters. Anything else answers null.
     */
    @Nullable
    public static Intent intentFor(@NonNull Context context, @NonNull String screenId) {
        switch (screenId) {
            case ShelfPrefs.SCREEN_BATTERY:
                return new Intent(context, BatteryUsageActivity.class);
            case ShelfPrefs.SCREEN_MONITOR:
                return new Intent(context, ProcessMonitorActivity.class);
            default:
                return null;
        }
    }

    /**
     * Fork (白い熊, +167): this activity now serves exactly one page — the apps carrying one tracker
     * — so the tracker extra is required rather than a special case. There is deliberately no
     * fall-through: the old default built 保存一覧, and after that became a lens a malformed intent
     * would have silently opened a screen that no longer has a way in.
     */
    @Nullable
    private static ScreenSource sourceFor(@Nullable Intent intent) {
        String tracker = intent == null ? null : intent.getStringExtra(EXTRA_TRACKER);
        return tracker != null ? new TrackerAppsSource(tracker) : null;
    }

    private ScreenSource mSource;
    private ScreenAdapter mAdapter;
    private RecyclerView mRecyclerView;
    private SwipeRefreshLayout mSwipeRefresh;
    private AppCompatTextView mEmptyView;
    private LinearProgressIndicator mProgress;
    @Nullable
    private MainSeparatorDecoration mSeparators;
    private int mSortMode;
    private boolean mLoading;
    @Nullable
    private View mSelectionBar;
    @Nullable
    private TextView mSelectionCount;
    private boolean mReloadOnResume;

    @Override
    protected void onAuthenticated(@Nullable Bundle savedInstanceState) {
        ScreenSource source = sourceFor(getIntent());
        if (source == null) {
            // Fork (白い熊, +167): nothing to show. Since the three shelf screens became lenses this
            // activity has exactly one subject, and an intent that does not name a tracker names
            // nothing at all — better an immediate finish than an empty page with a wrong title.
            finish();
            return;
        }
        mSource = source;
        setContentView(R.layout.activity_list_screen);
        setSupportActionBar(findViewById(R.id.toolbar));
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(mSource.title(this));
        }
        mProgress = findViewById(R.id.progress_linear);
        mEmptyView = findViewById(R.id.screen_empty);
        mEmptyView.setText(mSource.emptyTextRes());
        mRecyclerView = findViewById(R.id.screen_list);
        mAdapter = new ScreenAdapter(this, new ScreenAdapter.OnRowClickListener() {
            @Override
            public void onRowClicked(@NonNull ScreenRow row) {
                mSource.onRowClicked(ListScreenActivity.this, row);
            }

            @Override
            public void onSelectionChanged(int count) {
                updateSelectionBar(count);
            }
        });
        mRecyclerView.setAdapter(mAdapter);
        applyListLayout();
        mSwipeRefresh = findViewById(R.id.swipe_refresh);
        mSwipeRefresh.setOnRefreshListener(this::load);
        setupSelectionBar();
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // A selection is a state you leave with Back, before the screen is.
                if (mAdapter != null && mAdapter.clearSelection()) {
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mAdapter.reloadColors(this);
        mAdapter.notifyDataSetChanged();
        applyListLayout();
        if (mReloadOnResume) {
            mReloadOnResume = false;
            load();
        }
    }

    /**
     * The same geometry-aware column count as the main list (+88): a count that suits the folded
     * cover panel is wrong unfolded, so the pick is stored per geometry rather than once.
     *
     * <p>LANDMINE — {@code LayoutGeometry} must be fed an <b>activity</b> context. On the
     * application context every multi-window geometry collapses onto the full-screen key.
     */
    private void applyListLayout() {
        int columns = ScreenLayoutPrefs.getColumns(this);
        mRecyclerView.setLayoutManager(columns <= 0
                ? UIUtils.getGridLayoutAt450Dp(this)
                : new androidx.recyclerview.widget.GridLayoutManager(this, columns));
        if (mSeparators != null) {
            mRecyclerView.removeItemDecoration(mSeparators);
        }
        mSeparators = new MainSeparatorDecoration(this);
        mRecyclerView.addItemDecoration(mSeparators);
    }

    private void load() {
        if (mLoading) {
            return;
        }
        mLoading = true;
        if (mProgress != null) mProgress.show();
        ThreadUtils.postOnBackgroundThread(() -> {
            List<ScreenRow> rows;
            try {
                rows = mSource.load(getApplicationContext());
            } catch (Throwable th) {
                rows = new ArrayList<>();
            }
            mSource.applySort(rows, mSortMode);
            List<ScreenRow> finalRows = rows;
            ThreadUtils.postOnMainThread(() -> {
                mLoading = false;
                if (isDestroyed()) return;
                if (mProgress != null) mProgress.hide();
                mSwipeRefresh.setRefreshing(false);
                mAdapter.submit(finalRows);
                mEmptyView.setVisibility(finalRows.isEmpty() ? View.VISIBLE : View.GONE);
                mEmptyView.setTextColor(ColorPrefs.getColor(this, ColorPrefs.PACKAGE_NORMAL));
                ActionBar actionBar = getSupportActionBar();
                if (actionBar != null) {
                    actionBar.setSubtitle(getString(R.string.screen_row_count, finalRows.size()));
                }
            });
        });
    }

    // ── Selection (白い熊, +131) ─────────────────────────────────────────────

    /**
     * Long-press selects, and the bar appears with what can be done to a set of apps on this
     * screen. Today that is deleting their backups — the one sweep the backups screen is for,
     * and one that had to be done app by app before this.
     */
    private void setupSelectionBar() {
        mSelectionBar = findViewById(R.id.screen_selection_bar);
        mSelectionCount = findViewById(R.id.screen_selection_count);
        if (mSelectionBar == null || mSelectionCount == null) {
            return;
        }
        int ink = ForkThemeUtils.getTextColor();
        int red = 0xFFFF0028;
        findViewById(R.id.screen_selection_rule).setBackgroundColor(RowPills.withAlpha(ink, 0.35f));
        mSelectionCount.setTextColor(ink);
        io.github.muntashirakon.widget.FlowLayout actions = findViewById(R.id.screen_selection_actions);
        if (actions != null) {
            float d = getResources().getDisplayMetrics().density;
            actions.setChildSpacing(Math.round(6 * d));
            actions.setRowSpacing(Math.round(6 * d));
        }
        TextView selectAll = findViewById(R.id.screen_select_all);
        TextView clear = findViewById(R.id.screen_clear_selection);
        RowPills.styleActionPill(selectAll, ink, false);
        RowPills.styleActionPill(clear, ink, false);
        selectAll.setOnClickListener(v -> mAdapter.selectAll());
        clear.setOnClickListener(v -> mAdapter.clearSelection());
        // Fork (白い熊, +167): the back-up / restore / delete pills went with 保存一覧. Under the
        // Backups LENS those three are the main list's own batch actions, which is strictly more
        // than this bar ever offered — the whole configured batch pane rather than three buttons.
        findViewById(R.id.screen_backup_selected).setVisibility(View.GONE);
        findViewById(R.id.screen_restore_selected).setVisibility(View.GONE);
        findViewById(R.id.screen_delete_selected).setVisibility(View.GONE);
    }

    /**
     * Fork (白い熊, +142): back up the selected apps, through the same table the main list uses.
     * Reuse rather than a second implementation — the per-app parts, the app-supplied categories
     * and the run-scoped choices all come with it.
     */
    private void updateSelectionBar(int count) {
        if (mSelectionBar == null || mSelectionCount == null) {
            return;
        }
        mSelectionBar.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        mSelectionCount.setText(getString(R.string.batch_pane_count, count,
                mAdapter.getItemCount()));
    }

    /**
     * Deleting backups is not undoable, so it asks — and it says how many apps and how many
     * backups, because "3 apps" and "41 backups" are the same press with very different weight.
     */
    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        getMenuInflater().inflate(R.menu.activity_list_screen_actions, menu);
        // Fork (白い熊, +167): cleaning the backup directory moved with 保存一覧 — it is on the main
        // list's overflow while the Backups lens is on, and in Settings as it always was.
        MenuItem clean = menu.findItem(R.id.action_clean_backups);
        if (clean != null) {
            clean.setVisible(false);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        }
        if (id == R.id.action_sort) {
            showSortPicker();
            return true;
        }
        if (id == R.id.action_layout_columns) {
            showLayoutPicker();
            return true;
        }
        if (id == R.id.action_refresh) {
            load();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showSortPicker() {
        List<CharSequence> labels = mSource.sortLabels(this);
        ForkDialog.present(ForkDialog.builder(this)
                .setTitle(R.string.sort)
                .setSingleChoiceItems(labels.toArray(new CharSequence[0]), mSortMode, (dialog, which) -> {
                    mSortMode = which;
                    List<ScreenRow> rows = new ArrayList<>(mAdapter.getRows());
                    mSource.applySort(rows, mSortMode);
                    mAdapter.submit(rows);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null));
    }

    private void showLayoutPicker() {
        String[] choices = new String[]{
                getString(R.string.layout_adaptive),
                getString(R.string.layout_1_column),
                getString(R.string.layout_2_columns),
                getString(R.string.layout_3_columns),
                getString(R.string.layout_4_columns)};
        int columns = ScreenLayoutPrefs.getColumns(this);
        int checked = (columns >= 1 && columns <= 4) ? columns : 0;
        ForkDialog.present(ForkDialog.builder(this)
                .setTitle(R.string.list_layout)
                .setSingleChoiceItems(choices, checked, (dialog, which) -> {
                    ScreenLayoutPrefs.setColumns(this, which == 0 ? 0 : which);
                    applyListLayout();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null));
    }

    /**
     * Column count for the sibling screens, one pick per geometry — the same rule and the same
     * key shape as the main list and the monitor (+88), in its own preferences file so a screen
     * that suits four columns does not force the app list to four as well.
     */
    public static final class ScreenLayoutPrefs {
        private static final String PREF_FILE = "shiroikuma_screen_layout";

        private ScreenLayoutPrefs() {
        }

        /**
         * LANDMINE — {@code context} must be the ACTIVITY, not the application: only a window
         * context carries the window's own configuration, and the application one collapses
         * every multi-window geometry onto the full-screen key (+88). The preferences FILE is
         * still opened on the application context, which is a different thing entirely.
         */
        public static int getColumns(@NonNull Context context) {
            return LayoutGeometry.getColumns(context, prefs(context), "columns", 0);
        }

        public static void setColumns(@NonNull Context context, int columns) {
            LayoutGeometry.setColumns(context, prefs(context), "columns", columns);
        }

        private static android.content.SharedPreferences prefs(@NonNull Context context) {
            return context.getApplicationContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        }
    }
}
