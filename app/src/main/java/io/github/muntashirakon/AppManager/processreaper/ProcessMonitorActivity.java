// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.processreaper;

import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SearchView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.List;

import io.github.muntashirakon.AppManager.BaseActivity;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.runningapps.AppProcessItem;
import io.github.muntashirakon.AppManager.settings.SettingsActivity;
import io.github.muntashirakon.AppManager.utils.UIUtils;

/**
 * Fork: the process monitor / reaper — Phase 2. The Phase-1 classified list +
 * smart kill, now <b>live</b>: an auto-refresh loop re-samples on an interval
 * with true instantaneous CPU% (Δ utime+stime from {@code /proc/<pid>/stat}),
 * a Pause toggle to freeze the view and act, a Sort toggle (RAM / CPU), and an
 * adjustable refresh interval.
 */
public class ProcessMonitorActivity extends BaseActivity {
    private ProcessMonitorViewModel mViewModel;
    private ProcessMonitorAdapter mAdapter;
    private RecyclerView mList;
    private MonitorSeparatorDecoration mSeparators;
    private LinearProgressIndicator mProgress;
    private View mEmpty;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private long mIntervalMs = 2000;
    private boolean mPaused = false;
    private boolean mInSelection = false;
    @Nullable
    private MenuItem mPauseItem;
    @Nullable
    private OnBackPressedCallback mBackCallback;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (mViewModel != null && !mPaused) mViewModel.load();
            mHandler.postDelayed(this, mIntervalMs);
        }
    };

    @Override
    protected void onAuthenticated(@Nullable Bundle savedInstanceState) {
        setContentView(R.layout.activity_process_monitor);
        setSupportActionBar(findViewById(R.id.toolbar));
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.monitor_title);
        }
        // Summary line under the title (yellow), so an empty result reads as
        // "scanned, nothing found" rather than "maybe broken".
        ((MaterialToolbar) findViewById(R.id.toolbar))
                .setSubtitleTextColor(ContextCompat.getColor(this, R.color.theme_bright_yellow));
        mProgress = findViewById(R.id.progress_linear);
        mProgress.setIndeterminate(true);
        mProgress.setVisibilityAfterHide(View.GONE);
        mEmpty = findViewById(R.id.monitor_empty);

        mList = findViewById(R.id.monitor_list);
        mList.setLayoutManager(new GridLayoutManager(this, MonitorPrefs.getColumns(this)));
        mSeparators = new MonitorSeparatorDecoration(this);
        mList.addItemDecoration(mSeparators);
        mAdapter = new ProcessMonitorAdapter(this, this::onRowClick, this::onSelectionChanged,
                new ProcessMonitorAdapter.RowActions() {
                    @Override
                    public void onProtect(@NonNull ProcessMonitorViewModel.Row row) {
                        onProtectQuick(row);
                    }

                    @Override
                    public void onKill(@NonNull ProcessMonitorViewModel.Row row) {
                        onKillQuick(row);
                    }

                    @Override
                    public void onOverrideToggle(@NonNull ProcessMonitorViewModel.Row row) {
                        toggleDenylistOverride(row);
                    }
                });
        mList.setAdapter(mAdapter);

        // Back exits selection mode (when active) instead of closing the screen.
        mBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                mAdapter.clearSelection();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, mBackCallback);

        mViewModel = new ViewModelProvider(this).get(ProcessMonitorViewModel.class);
        mViewModel.getRows().observe(this, rows -> {
            mAdapter.setRows(rows);
            mEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
            int pids = 0, killable = 0, leaks = 0;
            for (ProcessMonitorViewModel.Row r : rows) {
                pids += r.pids.size();
                if (r.cls.killable) killable++;
                if (r.isLeak) leaks++;
            }
            ActionBar ab = getSupportActionBar();
            if (ab != null) ab.setSubtitle(getString(R.string.monitor_summary, pids, killable, leaks));
        });
        mViewModel.getLoading().observe(this, loading -> {
            if (Boolean.TRUE.equals(loading)) mProgress.show();
            else mProgress.hide();
        });
        mViewModel.getKillResult().observe(this, pair -> {
            if (pair == null) return;
            UIUtils.displayShortToast(getString(
                    pair.second ? R.string.monitor_killed : R.string.monitor_kill_failed,
                    pair.first.title));
            mViewModel.load();  // reflect the new state immediately
        });
        mViewModel.getBulkKillResult().observe(this, res -> {
            if (res == null) return;
            UIUtils.displayShortToast(getString(R.string.monitor_killed_n_of_m, res[0], res[1]));
            mAdapter.clearSelection();  // → onSelectionChanged(0) resumes refresh + reloads
        });
    }

    private void onSelectionChanged(int count) {
        boolean sel = count > 0;
        mInSelection = sel;
        ActionBar ab = getSupportActionBar();
        if (ab != null) {
            if (sel) {
                ab.setTitle(getString(R.string.monitor_n_selected, count));
                ab.setSubtitle(null);
            } else {
                ab.setTitle(R.string.monitor_title);
            }
        }
        if (mBackCallback != null) mBackCallback.setEnabled(sel);
        // Freeze the list while selecting (stable positions); resume on exit.
        mHandler.removeCallbacks(mTick);
        if (!sel) mHandler.post(mTick);
        invalidateOptionsMenu();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mSeparators != null) {
            mSeparators.reload(this);   // pick up separator width/colour changes
            mList.invalidateItemDecorations();
        }
        mHandler.removeCallbacks(mTick);
        if (!mInSelection) mHandler.post(mTick);   // immediate load + schedule
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mTick);
    }

    /**
     * Tap opens the dedicated process detail screen — NOT the kill dialog. Killing
     * is the per-row ✕ button or the "Kill" action inside the detail screen.
     */
    private void onRowClick(@Nullable ProcessMonitorViewModel.Row row) {
        if (row == null) return;
        int[] pids = new int[row.pids.size()];
        for (int i = 0; i < pids.length; i++) pids[i] = row.pids.get(i);
        String pkg = packageOf(row);
        boolean realApp = pkg != null && row.item instanceof AppProcessItem
                && row.cls.method != ProcessClassifier.METHOD_SIGKILL;
        startActivity(ProcessDetailActivity.getIntent(this, row.item.pid, pids,
                realApp ? pkg : null, row.title, row.cls.killable, row.cls.method,
                row.item.uid, row.cls.reason, row.item.user, row.cpu, row.memBytes,
                "in use".equals(row.cls.reason)));
    }

    @NonNull
    private MaterialAlertDialogBuilder themedDialog() {
        return new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_AppTheme_MaterialAlertDialog_YellowOnBlack);
    }

    @Nullable
    private static String packageOf(@NonNull ProcessMonitorViewModel.Row row) {
        return row.item instanceof AppProcessItem
                ? ((AppProcessItem) row.item).packageInfo.packageName : null;
    }

    /** Top button — toggle user protection, no confirmation. */
    private void onProtectQuick(@NonNull ProcessMonitorViewModel.Row row) {
        String pkg = packageOf(row);
        if (pkg == null) return;
        if ("you".equals(row.cls.reason)) {
            ReaperPrefs.removeProtected(this, pkg);
            UIUtils.displayShortToast(getString(R.string.monitor_unprotected, row.title));
        } else {
            ReaperPrefs.addProtected(this, pkg);
            UIUtils.displayShortToast(getString(R.string.monitor_protected_added, row.title));
        }
        mViewModel.load();
    }

    /**
     * Long-press a built-in-denylist app (e.g. Huawei Home) — allow killing it
     * (override the built-in protection) or restore that protection. The privilege
     * chain is filtered out upstream ({@link ProcessClassifier#isOverridableDenylist}).
     */
    private void toggleDenylistOverride(@NonNull ProcessMonitorViewModel.Row row) {
        String pkg = packageOf(row);
        if (pkg == null) return;
        if (ReaperPrefs.isAllowed(this, pkg)) {
            presentWithYellowBorder(themedDialog()
                    .setTitle(getString(R.string.monitor_restore_title, row.title))
                    .setMessage(R.string.monitor_restore_msg)
                    .setPositiveButton(R.string.monitor_restore_protection, (d, w) -> {
                        ReaperPrefs.removeAllowed(this, pkg);
                        UIUtils.displayShortToast(getString(R.string.monitor_protected_added, row.title));
                        mViewModel.load();
                    })
                    .setNegativeButton(R.string.cancel, null));
        } else {
            presentWithYellowBorder(themedDialog()
                    .setTitle(getString(R.string.monitor_allow_title, row.title))
                    .setMessage(R.string.monitor_allow_msg)
                    .setPositiveButton(R.string.monitor_allow_killing, (d, w) -> {
                        ReaperPrefs.addAllowed(this, pkg);
                        UIUtils.displayShortToast(getString(R.string.monitor_allowed, row.title));
                        mViewModel.load();
                    })
                    .setNegativeButton(R.string.cancel, null));
        }
    }

    /** Bottom button — kill, no confirmation (the killResult observer reloads). */
    private void onKillQuick(@NonNull ProcessMonitorViewModel.Row row) {
        if (row.cls.killable) mViewModel.kill(row);
    }

    /**
     * Faceted filter: two dimensions (killability × type), AND across them, OR
     * within. Nothing checked in a dimension leaves it unconstrained. Applies on
     * top of the live search and survives the auto-refresh.
     */
    private void showFilterDialog() {
        final String[] items = {
                getString(R.string.monitor_filter_killable),
                getString(R.string.monitor_filter_protected),
                getString(R.string.monitor_filter_togglable),
                getString(R.string.monitor_filter_user),
                getString(R.string.monitor_filter_system),
                getString(R.string.monitor_filter_shell),
                getString(R.string.monitor_filter_leaks),
        };
        int fk = mViewModel.getFilterKill(), ft = mViewModel.getFilterType();
        final boolean[] checked = {
                (fk & ProcessMonitorViewModel.F_KILLABLE) != 0,
                (fk & ProcessMonitorViewModel.F_PROTECTED) != 0,
                (fk & ProcessMonitorViewModel.F_TOGGLABLE) != 0,
                (ft & ProcessMonitorViewModel.F_USER) != 0,
                (ft & ProcessMonitorViewModel.F_SYSTEM) != 0,
                (ft & ProcessMonitorViewModel.F_SHELL) != 0,
                (ft & ProcessMonitorViewModel.F_LEAK) != 0,
        };
        MaterialAlertDialogBuilder b = themedDialog()
                .setTitle(R.string.monitor_filter)
                .setMultiChoiceItems(items, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.monitor_filter_apply, (d, w) -> {
                    int nfk = (checked[0] ? ProcessMonitorViewModel.F_KILLABLE : 0)
                            | (checked[1] ? ProcessMonitorViewModel.F_PROTECTED : 0)
                            | (checked[2] ? ProcessMonitorViewModel.F_TOGGLABLE : 0);
                    int nft = (checked[3] ? ProcessMonitorViewModel.F_USER : 0)
                            | (checked[4] ? ProcessMonitorViewModel.F_SYSTEM : 0)
                            | (checked[5] ? ProcessMonitorViewModel.F_SHELL : 0)
                            | (checked[6] ? ProcessMonitorViewModel.F_LEAK : 0);
                    mViewModel.setFilter(nfk, nft);
                })
                .setNeutralButton(R.string.monitor_filter_clear, (d, w) -> mViewModel.setFilter(0, 0))
                .setNegativeButton(R.string.cancel, null);
        presentWithYellowBorder(b);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.monitor_actions, menu);
        mPauseItem = menu.findItem(R.id.action_monitor_pause);
        applyPauseItem();
        final int yellow = ContextCompat.getColor(this, R.color.theme_bright_yellow);
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            tintIcon(item);
            // M3 overflow item TextViews don't follow the popup theme's text
            // colour reliably (same as MainActivity) — force yellow via a span.
            CharSequence title = item.getTitle();
            if (title != null) {
                SpannableString span = new SpannableString(title);
                span.setSpan(new ForegroundColorSpan(yellow), 0, span.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
                item.setTitle(span);
            }
        }
        // Fork: search — filter the live list by app label / process name. Themed
        // yellow-on-black to match the toolbar; query survives the auto-refresh.
        MenuItem searchItem = menu.findItem(R.id.action_monitor_search);
        if (searchItem != null && searchItem.getActionView() instanceof SearchView) {
            SearchView sv = (SearchView) searchItem.getActionView();
            sv.setQueryHint(getString(R.string.search));
            EditText et = sv.findViewById(androidx.appcompat.R.id.search_src_text);
            if (et != null) {
                et.setTextColor(yellow);
                et.setHintTextColor(0x80FFFF00);  // dim yellow
            }
            tintSearchIcon(sv, androidx.appcompat.R.id.search_mag_icon, yellow);
            tintSearchIcon(sv, androidx.appcompat.R.id.search_close_btn, yellow);
            tintSearchIcon(sv, androidx.appcompat.R.id.search_button, yellow);
            sv.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String query) {
                    return false;
                }

                @Override
                public boolean onQueryTextChange(String newText) {
                    if (mViewModel != null) mViewModel.setQuery(newText);
                    return true;
                }
            });
            searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
                @Override
                public boolean onMenuItemActionExpand(@NonNull MenuItem item) {
                    return true;
                }

                @Override
                public boolean onMenuItemActionCollapse(@NonNull MenuItem item) {
                    if (mViewModel != null) mViewModel.setQuery("");
                    return true;
                }
            });
        }
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null && toolbar.getOverflowIcon() != null) {
            android.graphics.drawable.Drawable overflow = toolbar.getOverflowIcon().mutate();
            overflow.setColorFilter(yellow, android.graphics.PorterDuff.Mode.SRC_IN);
            toolbar.setOverflowIcon(overflow);
        }
        // Fork: long-press the overflow (hamburger) opens the 白い熊 応用管理 UI page
        // (same as MainActivity). Posted because menu views lay out after this.
        if (toolbar != null) {
            final MaterialToolbar tb = toolbar;
            tb.post(() -> attachOverflowLongPress(tb));
        }
        return super.onCreateOptionsMenu(menu);
    }

    private void attachOverflowLongPress(@NonNull MaterialToolbar toolbar) {
        for (int i = 0; i < toolbar.getChildCount(); ++i) {
            View child = toolbar.getChildAt(i);
            if (!(child instanceof androidx.appcompat.widget.ActionMenuView)) continue;
            androidx.appcompat.widget.ActionMenuView menuView = (androidx.appcompat.widget.ActionMenuView) child;
            for (int j = 0; j < menuView.getChildCount(); ++j) {
                View button = menuView.getChildAt(j);
                if (button instanceof ImageView) {
                    button.setOnLongClickListener(v -> {
                        startActivity(SettingsActivity.getSettingsIntent(this, "fonts_prefs"));
                        return true;
                    });
                }
            }
        }
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // In selection mode show only "Kill selected"; otherwise the live controls.
        boolean sel = mInSelection;
        toggle(menu, R.id.action_monitor_kill_selected, sel);
        toggle(menu, R.id.action_monitor_search, !sel);
        toggle(menu, R.id.action_monitor_filter, !sel);
        toggle(menu, R.id.action_monitor_pause, !sel);
        toggle(menu, R.id.action_monitor_sort, !sel);
        toggle(menu, R.id.action_monitor_columns, !sel);
        toggle(menu, R.id.action_monitor_refresh, !sel);
        toggle(menu, R.id.action_monitor_interval, !sel);
        return super.onPrepareOptionsMenu(menu);
    }

    private static void toggle(@NonNull Menu menu, int id, boolean visible) {
        MenuItem mi = menu.findItem(id);
        if (mi != null) mi.setVisible(visible);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_monitor_kill_selected) {
            List<ProcessMonitorViewModel.Row> sel = mAdapter.getSelectedRows();
            if (sel.isEmpty()) return true;
            presentWithYellowBorder(new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_AppTheme_MaterialAlertDialog_YellowOnBlack)
                    .setTitle(getString(R.string.monitor_kill_selected_confirm_title, sel.size()))
                    .setMessage(R.string.monitor_kill_selected_confirm_msg)
                    .setPositiveButton(R.string.monitor_kill, (d, w) -> mViewModel.killSelected(sel))
                    .setNegativeButton(R.string.cancel, null));
            return true;
        } else if (id == R.id.action_monitor_refresh) {
            mViewModel.load();
            return true;
        } else if (id == R.id.action_monitor_pause) {
            mPaused = !mPaused;
            applyPauseItem();
            if (!mPaused) mViewModel.load();  // resume → refresh now
            return true;
        } else if (id == R.id.action_monitor_filter) {
            showFilterDialog();
            return true;
        } else if (id == R.id.action_monitor_sort) {
            int next = mViewModel.getSort() == ProcessMonitorViewModel.SORT_RAM
                    ? ProcessMonitorViewModel.SORT_CPU : ProcessMonitorViewModel.SORT_RAM;
            mViewModel.setSort(next);
            UIUtils.displayShortToast(next == ProcessMonitorViewModel.SORT_CPU
                    ? R.string.monitor_sort_cpu : R.string.monitor_sort_ram);
            mViewModel.load();
            return true;
        } else if (id == R.id.action_monitor_columns) {
            int cols = MonitorPrefs.cycleColumns(this);
            mList.setLayoutManager(new GridLayoutManager(this, cols));
            UIUtils.displayShortToast(getString(R.string.monitor_columns_n, cols));
            return true;
        } else if (id == R.id.action_monitor_interval) {
            showIntervalPicker();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showIntervalPicker() {
        final long[] values = {1000, 2000, 3000, 5000};
        final String[] labels = {"1 s", "2 s", "3 s", "5 s"};
        int checked = 1;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == mIntervalMs) {
                checked = i;
                break;
            }
        }
        presentWithYellowBorder(new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_AppTheme_MaterialAlertDialog_YellowOnBlack)
                .setTitle(R.string.monitor_interval)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    mIntervalMs = values[which];
                    d.dismiss();
                    mHandler.removeCallbacks(mTick);
                    mHandler.post(mTick);  // restart with the new cadence
                })
                .setNegativeButton(R.string.cancel, null));
    }

    /**
     * Force the yellow border on a MaterialAlertDialog. The overlay's text +
     * control colours apply, but {@code MaterialAlertDialogBuilder.create()}
     * overrides the window background with its own
     * {@code InsetDrawable(MaterialShapeDrawable)} built from colorSurface
     * (black, no stroke) — so the theme's bordered drawable never reaches the
     * card. Replace the window background after show() with our bordered shape.
     */
    private void presentWithYellowBorder(@NonNull MaterialAlertDialogBuilder builder) {
        AlertDialog dialog = builder.create();
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            Drawable bg = ContextCompat.getDrawable(this, R.drawable.alert_dialog_bg_yellow_on_black);
            if (bg != null) {
                int inset = Math.round(getResources().getDisplayMetrics().density * 16);
                w.setBackgroundDrawable(new InsetDrawable(bg, inset));
            }
        }
    }

    private void applyPauseItem() {
        if (mPauseItem == null) return;
        mPauseItem.setIcon(mPaused ? R.drawable.ic_play_arrow : R.drawable.ic_pause);
        mPauseItem.setTitle(mPaused ? R.string.monitor_resume : R.string.monitor_pause);
        tintIcon(mPauseItem);
    }

    private void tintIcon(@NonNull MenuItem item) {
        Drawable icon = item.getIcon();
        if (icon != null) {
            icon = icon.mutate();
            icon.setColorFilter(ContextCompat.getColor(this, R.color.theme_bright_yellow),
                    PorterDuff.Mode.SRC_IN);
            item.setIcon(icon);
        }
    }

    private static void tintSearchIcon(@NonNull SearchView sv, int id, int color) {
        View v = sv.findViewById(id);
        if (v instanceof ImageView) {
            ((ImageView) v).setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        if (mInSelection) {
            mAdapter.clearSelection();
            return true;
        }
        finish();
        return true;
    }
}
