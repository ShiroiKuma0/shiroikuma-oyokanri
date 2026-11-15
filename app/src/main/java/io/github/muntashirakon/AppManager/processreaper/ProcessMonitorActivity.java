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
import io.github.muntashirakon.AppManager.utils.LayoutGeometry;
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
    // Fork: the geometry the current layout manager was built for — the column
    // count is stored per orientation × fold state (see LayoutGeometry).
    private String mLayoutGeometry;
    private LinearProgressIndicator mProgress;
    private View mEmpty;
    private android.widget.LinearLayout mHealth;
    private com.google.android.material.card.MaterialCardView mHealthCard;
    // Fork (白い熊, 2026-11-15): the process-start watch — its latest reading, the health reading
    // it is drawn into, and whether a probe / fix step this screen asked for is still out.
    @Nullable
    private ForkStartWatch.Snapshot mFork;
    @Nullable
    private SystemHealth mHealthShown;
    private boolean mProbePending;
    private boolean mComparePending;
    private boolean mReportPending;
    private boolean mPoolPending;
    private boolean mFixPending;

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
        mHealth = findViewById(R.id.monitor_health);
        mHealthCard = findViewById(R.id.monitor_health_card);
        if (mHealthCard != null) {
            mHealthCard.setStrokeColor(io.github.muntashirakon.AppManager.utils.ForkThemeUtils.getTextColor());
        }

        mList = findViewById(R.id.monitor_list);
        applyListLayout();
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
                    public void onKillRestart(@NonNull ProcessMonitorViewModel.Row row) {
                        // Flash immediately that we are restarting the app.
                        UIUtils.displayShortToast(getString(R.string.monitor_restarting, row.title));
                        mViewModel.killAndRestartLeak(row);
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
        mViewModel.getHealth().observe(this, this::bindHealth);
        mViewModel.getForkStart().observe(this, f -> {
            mFork = f;
            if (mHealthShown != null) bindHealth(mHealthShown);
        });
        mViewModel.getProbeResult().observe(this, p -> {
            if (p == null || !mProbePending) return;  // a re-delivered old result after a re-create
            mProbePending = false;
            showForkPanel(p);
        });
        mViewModel.getFixResult().observe(this, this::onFixResult);
        mViewModel.getPoolResult().observe(this, summary -> {
            if (summary == null || !mPoolPending) return;  // a re-delivered old result after a re-create
            mPoolPending = false;
            showPoolPanel(summary);
        });
        mViewModel.getReportResult().observe(this, text -> {
            if (text == null || !mReportPending) return;  // a re-delivered old result after a re-create
            mReportPending = false;
            io.github.muntashirakon.AppManager.utils.ForkDialog.present(
                    io.github.muntashirakon.AppManager.utils.ForkDialog.builder(this)
                            .setTitle(R.string.monitor_report_title)
                            .setMessage(PanelViews.pretty(this, text))
                            .setPositiveButton(android.R.string.ok, null));
        });
        mViewModel.getCompareResult().observe(this, text -> {
            if (text == null || !mComparePending) return;  // a re-delivered old result after a re-create
            mComparePending = false;
            showCompare(text.isEmpty() ? null : text);
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
        // Fork (白い熊, +063): kill-and-restart of a protected leaking app finished.
        mViewModel.getRestartResult().observe(this, pair -> {
            if (pair == null) return;
            if (!Boolean.TRUE.equals(pair.second)) {
                UIUtils.displayShortToast(getString(R.string.monitor_kill_failed, pair.first.title));
            }
            mViewModel.load();
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
        applyListLayoutIfGeometryChanged();
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

    /**
     * Fork: the system-health header (see {@link SystemHealth}) — what a
     * per-process CPU/RAM ranking cannot show: memory, paging, thermal state, the
     * graphics allocator, and apps holding destroyed screens or large GPU memory.
     * A figure past its threshold is drawn in the leak colour; a flagged app is
     * one tappable line that opens its process detail page.
     */
    private void bindHealth(@Nullable SystemHealth h) {
        if (h == null || mHealth == null) return;
        mHealthShown = h;
        mHealth.removeAllViews();
        if (mHealthCard != null) mHealthCard.setVisibility(View.VISIBLE);
        mHealth.setVisibility(View.VISIBLE);
        int yellow = ContextCompat.getColor(this, R.color.theme_bright_yellow);
        int warn = io.github.muntashirakon.AppManager.fonts.ColorPrefs.getColor(this,
                io.github.muntashirakon.AppManager.fonts.ColorPrefs.MONITOR_LEAK);
        int dim = io.github.muntashirakon.AppManager.fonts.ColorPrefs.getColor(this,
                io.github.muntashirakon.AppManager.fonts.ColorPrefs.SETTINGS_DESCRIPTION);

        String alloc = h.allocatorBytes < 0 ? "—" : android.text.format.Formatter.formatShortFileSize(this, h.allocatorBytes);
        if (h.allocatorBytes >= 0 && Math.abs(h.allocatorDeltaBytes) >= (16L << 20)) {
            String d = android.text.format.Formatter.formatShortFileSize(this, Math.abs(h.allocatorDeltaBytes));
            alloc += " " + getString(R.string.monitor_health_alloc_delta, (h.allocatorDeltaBytes > 0 ? "+" : "−") + d);
        }

        // Fork (白い熊, +065): every figure is its own tappable pill; the whole-line
        // text is gone. Tapping a pill opens a dismissable OK dialog explaining it.
        boolean memThermalWarn = h.memoryLow() || h.thermalHot();
        android.widget.LinearLayout sys = addHealthRow();
        addHealthPill(sys, getString(R.string.monitor_health_pill_uptime, uptimeLabel(h.uptimeMs)),
                yellow, R.string.monitor_health_title_uptime, R.string.monitor_health_explain_uptime);
        addHealthPill(sys, getString(R.string.monitor_health_pill_memory,
                        android.text.format.Formatter.formatShortFileSize(this, h.memAvailableBytes),
                        android.text.format.Formatter.formatShortFileSize(this, h.memTotalBytes)),
                h.memoryLow() ? warn : yellow, R.string.monitor_health_title_memory,
                R.string.monitor_health_explain_memory);
        addHealthPill(sys, getString(R.string.monitor_health_pill_thermal, thermalLabel(h.thermalStatus)),
                h.thermalHot() ? warn : yellow, R.string.monitor_health_title_thermal,
                R.string.monitor_health_explain_thermal);

        int pagingColor = h.pagingHeavy() ? warn : yellow;
        android.widget.LinearLayout paging = addHealthRow();
        addHealthPill(paging, getString(R.string.monitor_health_pill_refaults, rate(h.refaultsPerSec)),
                pagingColor, R.string.monitor_health_title_refaults, R.string.monitor_health_explain_refaults);
        addHealthPill(paging, getString(R.string.monitor_health_pill_major, rate(h.majorFaultsPerSec)),
                pagingColor, R.string.monitor_health_title_major, R.string.monitor_health_explain_major);
        addHealthPill(paging, getString(R.string.monitor_health_pill_graphics, alloc),
                yellow, R.string.monitor_health_title_graphics, R.string.monitor_health_explain_graphics);

        addForkLine(warn, yellow);
        // Fork (+105): the kernel's room for filter programs — what runs out when starts fail.
        addHealthPill(addHealthRow(), FilterPool.pillText(this), FilterPool.pillWarn(this) ? warn : yellow, false,
                v -> openPool(0));

        if (!h.objectsSampled) {
            addHealthLine(getString(R.string.monitor_health_checking), dim, null);
            return;
        }
        // Fork (白い熊, +062): the leaking apps are no longer listed here as text — they are shown as
        // ordinary cards, pinned at the top of the list, with the leak note and a kill (+ restart)
        // button. The header just says how many, so the stats and the count stay together.
        // Fork (白い熊 / 地図, 2026-11-12): only a genuine leak (retained screens or growing GPU) is
        // called leaking; a heavy-but-steady GPU app is counted and named separately, calmly.
        int leaking = 0;
        for (SystemHealth.Suspect s : h.suspects) if (s.isLeak()) leaking++;
        int heavy = h.suspects.size() - leaking;
        if (leaking == 0 && heavy == 0) {
            addHealthPill(addHealthRow(), getString(R.string.monitor_health_clean), yellow,
                    R.string.monitor_health_title_leak, R.string.monitor_health_explain_leak);
        } else {
            if (leaking > 0) {
                android.widget.LinearLayout leak = addHealthRow();
                addHealthPill(leak, getResources().getQuantityString(R.plurals.monitor_health_leak_summary,
                                leaking, leaking), warn,
                        R.string.monitor_health_title_leak, R.string.monitor_health_explain_leak);
            }
            if (heavy > 0) {
                addHealthPill(addHealthRow(), getResources().getQuantityString(
                                R.plurals.monitor_health_heavy_summary, heavy, heavy), yellow,
                        R.string.monitor_health_title_heavy, R.string.monitor_health_explain_heavy);
            }
        }
    }

    /** Fork (白い熊, +065): a horizontal, left-and-right scrollable row of health pills. */
    @NonNull
    private android.widget.LinearLayout addHealthRow() {
        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        scroller.addView(row, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        int top = Math.round(getResources().getDisplayMetrics().density * 3);
        lp.topMargin = top;
        mHealth.addView(scroller, lp);
        return row;
    }

    /**
     * Fork (白い熊, +065): a figure drawn as the fork's pill (transparent fill,
     * stadium stroke in its accent) that opens a dismissable OK dialog explaining
     * what it means. Replaces the +064 long-press toast, which was too brief.
     */
    private void addHealthPill(@NonNull android.widget.LinearLayout row, @NonNull CharSequence text,
                               int color, int titleRes, int explainRes) {
        addHealthPill(row, text, color, false, v -> showHealthExplain(titleRes, explainRes));
    }

    /**
     * Fork (白い熊, +096): the pill itself. Everything tappable in the header is one — never a
     * bare line of text that gives no clue it can be pressed. {@code main} is the black-yellow
     * theme's main action: a 3 dp frame and bold text, never a fill.
     */
    @NonNull
    private androidx.appcompat.widget.AppCompatTextView addHealthPill(
            @NonNull android.widget.LinearLayout row, @NonNull CharSequence text, int color, boolean main,
            @NonNull View.OnClickListener click) {
        float density = getResources().getDisplayMetrics().density;
        androidx.appcompat.widget.AppCompatTextView tv = new androidx.appcompat.widget.AppCompatTextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setSingleLine(true);
        int hp = Math.round(density * 10), vp = Math.round(density * 4);
        tv.setPadding(hp, vp, hp, vp);

        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setColor(android.graphics.Color.TRANSPARENT);
        pill.setCornerRadius(density * 1000f);  // clamped to half-height → a stadium
        pill.setStroke(Math.max(1, Math.round(density * (main ? 3f : 1.25f))), color);
        tv.setBackground(pill);
        if (main) tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        // Ripple of the same hue on tap, as the row pills elsewhere in the fork.
        android.util.TypedValue fg = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, fg, true)) {
            tv.setForeground(androidx.core.content.ContextCompat.getDrawable(this, fg.resourceId));
        }
        tv.setOnClickListener(click);

        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        if (row.getChildCount() > 0) lp.leftMargin = Math.round(density * 6);
        row.addView(tv, lp);
        return tv;
    }

    /** Fork (白い熊, +065): a bordered yellow-on-black dialog explaining one health figure, dismissed with OK. */
    private void showHealthExplain(int titleRes, int explainRes) {
        io.github.muntashirakon.AppManager.utils.ForkDialog.present(
                io.github.muntashirakon.AppManager.utils.ForkDialog.builder(this)
                        .setTitle(titleRes)
                        .setMessage(PanelViews.pretty(this, getString(explainRes)))
                        .setPositiveButton(android.R.string.ok, null));
    }

    // ---- Fork (白い熊, 2026-11-15): "new processes cannot start" -----------------------

    @NonNull
    private static String hms(long wallMs) {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date(wallMs));
    }

    @NonNull
    private String size(long bytes) {
        return android.text.format.Formatter.formatShortFileSize(this, bytes);
    }

    /**
     * The process-start pill in the health header: the main pill (thick frame, bold, warning
     * colour) while starts fail, a warning-coloured pill after, an ordinary one otherwise.
     */
    private void addForkLine(int warn, int yellow) {
        ForkStartWatch.Snapshot f = mFork;
        if (f == null) return;
        View.OnClickListener open = v -> showForkPanel(null);
        android.widget.LinearLayout row = addHealthRow();
        if (f.status == ForkStartWatch.STATUS_FAILING) {
            String text = f.failures5Min > 0 || f.lastProbe == null
                    ? getString(R.string.monitor_fork_failing, f.failures5Min, hms(f.lastFailureMs))
                    : getString(R.string.monitor_fork_failing_probe, hms(f.lastProbe.wallMs));
            addHealthPill(row, text, warn, true, open);
        } else if (f.status == ForkStartWatch.STATUS_RECENT) {
            addHealthPill(row, getString(R.string.monitor_fork_recent, f.failures1Hour, hms(f.lastFailureMs)),
                    warn, false, open);
        } else {
            addHealthPill(row, getString(R.string.monitor_fork_ok, size(f.slabUnreclaimBytes)), yellow, false, open);
        }
    }

    /**
     * The panel behind the header line: the state as a coloured line, the two test processes as
     * tiles, the failure counts, the figures, the apps that crashed, and the Probe and Fix actions.
     * Fork (+106): drawn with {@link PanelViews} — it was one block of text.
     */
    private void showForkPanel(@Nullable ForkStartWatch.Probe fresh) {
        ForkStartWatch.Snapshot f = mFork;
        if (f == null) return;
        ForkStartWatch.Probe probe = fresh != null ? fresh : f.lastProbe;
        boolean failing = fresh != null ? !fresh.ok : f.status == ForkStartWatch.STATUS_FAILING;
        final int ink = PanelViews.ink(), warn = PanelViews.warn(this), ok = PanelViews.ok(this), bad = PanelViews.bad(this);
        android.widget.LinearLayout col = PanelViews.column(this);
        boolean recent = !failing && f.status == ForkStartWatch.STATUS_RECENT;
        col.addView(PanelViews.status(this, getString(failing ? R.string.fork_panel_state_failing
                : recent ? R.string.fork_panel_state_recent : R.string.fork_panel_state_ok),
                failing ? bad : recent ? warn : ok));
        col.addView(PanelViews.line(this, getString(failing ? R.string.monitor_fork_state_failing
                : recent ? R.string.monitor_fork_state_recent : R.string.monitor_fork_state_ok), PanelViews.dim(this)));

        col.addView(PanelViews.heading(this, getString(R.string.fork_panel_probe)));
        if (probe == null) {
            col.addView(PanelViews.line(this, getString(R.string.monitor_fork_panel_probe_none), PanelViews.dim(this)));
        } else {
            java.util.List<PanelViews.Tile> t = new java.util.ArrayList<>();
            t.add(new PanelViews.Tile(getString(R.string.fork_panel_isolated), (probe.isoOk ? "✓ " : "✕ ")
                    + getString(probe.isoOk ? R.string.monitor_fork_probe_started : R.string.monitor_fork_probe_not_started),
                    probe.isoOk ? ok : bad));
            t.add(new PanelViews.Tile(getString(R.string.fork_panel_ordinary), (probe.plainOk ? "✓ " : "✕ ")
                    + getString(probe.plainOk ? R.string.monitor_fork_probe_started : R.string.monitor_fork_probe_not_started),
                    probe.plainOk ? ok : bad));
            col.addView(PanelViews.tiles(this, 2, t));
            PanelViews.gap(col, 4);
            col.addView(PanelViews.line(this, getString(R.string.fork_panel_probe_at, hms(probe.wallMs), probe.tookMs),
                    PanelViews.dim(this)));
        }

        col.addView(PanelViews.heading(this, getString(R.string.fork_panel_failures)));
        if (!f.logScanned) {
            col.addView(PanelViews.line(this, getString(R.string.monitor_fork_panel_nolog), PanelViews.dim(this)));
        } else {
            java.util.List<PanelViews.Tile> t = new java.util.ArrayList<>();
            t.add(new PanelViews.Tile(getString(R.string.fork_panel_5min), String.valueOf(f.failures5Min),
                    f.failures5Min > 0 ? bad : ink));
            t.add(new PanelViews.Tile(getString(R.string.fork_panel_1h), String.valueOf(f.failures1Hour),
                    f.failures1Hour > 0 ? warn : ink));
            t.add(new PanelViews.Tile(getString(R.string.fork_panel_total), String.valueOf(f.failuresTotal), ink));
            col.addView(PanelViews.tiles(this, 3, t));
            if (f.failuresTotal > 0) {
                PanelViews.gap(col, 4);
                col.addView(PanelViews.line(this, getString(R.string.monitor_fork_panel_span, hms(f.firstFailureMs),
                        hms(f.lastFailureMs)), PanelViews.dim(this)));
            }
        }
        if (!f.crashedApps.isEmpty()) {
            col.addView(PanelViews.heading(this, getString(R.string.fork_panel_crashed)));
            StringBuilder apps = new StringBuilder();
            for (String a : f.crashedApps) apps.append(apps.length() > 0 ? "\n" : "").append(a);
            col.addView(PanelViews.mono(this, apps));
        }

        col.addView(PanelViews.heading(this, getString(R.string.fork_panel_figures)));
        java.util.List<PanelViews.Tile> figures = new java.util.ArrayList<>();
        figures.add(new PanelViews.Tile(getString(R.string.fork_panel_room), FilterPool.pillValue(this),
                FilterPool.pillWarn(this) ? warn : ink));
        if (f.procs > 0) {
            figures.add(new PanelViews.Tile(getString(R.string.fork_panel_procs), String.valueOf(f.procs), ink));
            figures.add(new PanelViews.Tile(getString(R.string.fork_panel_threads), String.valueOf(f.threads), ink));
            figures.add(new PanelViews.Tile(getString(R.string.fork_panel_cached), String.valueOf(f.cached), ink));
        }
        figures.add(new PanelViews.Tile(getString(R.string.fork_panel_free), size(f.memFreeBytes), ink));
        figures.add(new PanelViews.Tile(getString(R.string.fork_panel_avail), size(f.memAvailableBytes), ink));
        figures.add(new PanelViews.Tile(getString(R.string.fork_panel_slab), size(f.slabUnreclaimBytes), ink));
        if (f.kmalloc64Bytes >= 0) {
            figures.add(new PanelViews.Tile(getString(R.string.fork_panel_kmalloc), size(f.kmalloc64Bytes), ink));
        }
        col.addView(PanelViews.tiles(this, 2, figures));
        col.addView(PanelViews.note(this, getString(R.string.fork_panel_explain)));
        MaterialAlertDialogBuilder b = io.github.muntashirakon.AppManager.utils.ForkDialog.builder(this)
                .setTitle(R.string.monitor_fork_title)
                .setView(PanelViews.dialogBody(col))
                .setNegativeButton(R.string.close, null);
        android.content.DialogInterface.OnClickListener choose = (d, w) -> {
            mFixPending = true;
            UIUtils.displayShortToast(getString(R.string.monitor_fork_probing));
            mViewModel.fixProbe();
        };
        if (failing) {
            // Fork (+101): while starts fail the main pill is the one-tap fix of the notification;
            // the step-by-step fix with its checklist sits beside it. The watch probes by itself.
            b.setPositiveButton(R.string.monitor_watch_fix, (d, w) -> {
                mFixPending = true;
                UIUtils.displayShortToast(getString(R.string.monitor_fork_working));
                mViewModel.fixFreeMemory();
            }).setNeutralButton(R.string.monitor_fork_choose, choose);
        } else {
            b.setPositiveButton(R.string.monitor_fork_fix, choose)
                    .setNeutralButton(R.string.monitor_fork_probe, (d, w) -> {
                        mProbePending = true;
                        UIUtils.displayShortToast(getString(R.string.monitor_fork_probing));
                        mViewModel.runProbe();
                    });
        }
        io.github.muntashirakon.AppManager.utils.ForkDialog.present(b);
    }

    /**
     * Fork (白い熊, +097): the kernel-memory comparison. Without a first snapshot it offers to take
     * one; with one it offers to compare; {@code result} is a finished comparison to show.
     */
    private void showCompare(@Nullable String result) {
        long base = mViewModel.compareBaseMs();
        MaterialAlertDialogBuilder b = io.github.muntashirakon.AppManager.utils.ForkDialog.builder(this)
                .setTitle(R.string.monitor_compare_title);
        if (base == 0) {
            b.setMessage(R.string.monitor_compare_intro)
                    .setPositiveButton(R.string.monitor_compare_take, (d, w) -> {
                        mComparePending = true;
                        UIUtils.displayShortToast(getString(R.string.monitor_fork_working));
                        mViewModel.compareStart();
                    })
                    .setNegativeButton(R.string.close, null);
        } else {
            b.setMessage(PanelViews.pretty(this, result != null ? result : getString(R.string.monitor_compare_have, hms(base))))
                    .setPositiveButton(result != null ? R.string.monitor_compare_again : R.string.monitor_compare_now,
                            (d, w) -> {
                                mComparePending = true;
                                UIUtils.displayShortToast(getString(R.string.monitor_fork_working));
                                mViewModel.compareNow();
                            })
                    .setNeutralButton(R.string.monitor_compare_restart, (d, w) -> {
                        mViewModel.compareClear();
                        showCompare(null);
                    })
                    .setNegativeButton(R.string.close, null);
        }
        io.github.muntashirakon.AppManager.utils.ForkDialog.present(b);
    }

    /** Each fix step ends in a probe; what happens next depends on whether a process could start. */
    private void onFixResult(@Nullable ProcessMonitorViewModel.FixResult r) {
        if (r == null || !mFixPending) return;  // a re-delivered old result after a re-create
        mFixPending = false;
        MaterialAlertDialogBuilder b = io.github.muntashirakon.AppManager.utils.ForkDialog.builder(this)
                .setTitle(R.string.monitor_fork_title);
        if (r.stage == ProcessMonitorViewModel.FIX_STAGE_PROBE) {
            if (!r.probe.ok) {
                showStopChecklist(false, false);
                return;
            }
            b.setMessage(getString(R.string.monitor_fork_fix_not_needed, r.probe.tookMs))
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.monitor_fork_stop_anyway, (d, w) -> showStopChecklist(false, true));
        } else if (r.probe.ok) {
            b.setMessage(getString(R.string.monitor_fork_result_ok, r.probe.tookMs, r.stopped,
                            size(r.memFreeBefore), size(r.memFreeAfter), size(r.memAvailBefore), size(r.memAvailAfter)))
                    .setPositiveButton(android.R.string.ok, null);
        } else if (r.stage == ProcessMonitorViewModel.FIX_STAGE_KILL) {
            b.setMessage(getString(R.string.monitor_fork_result_still, r.stopped,
                            size(r.memFreeBefore), size(r.memFreeAfter), size(r.memAvailBefore), size(r.memAvailAfter)))
                    .setPositiveButton(R.string.monitor_fork_force_go, (d, w) -> showStopChecklist(true, false))
                    .setNegativeButton(R.string.close, null);
        } else {
            b.setMessage(getString(R.string.monitor_fork_result_failed, r.stopped,
                            size(r.memFreeBefore), size(r.memFreeAfter), size(r.memAvailBefore), size(r.memAvailAfter)))
                    .setPositiveButton(android.R.string.ok, null);
        }
        io.github.muntashirakon.AppManager.utils.ForkDialog.present(b);
    }

    /**
     * Says exactly what a fix step will stop, and lets each app be unticked. Step 1
     * lists every stoppable background app, all ticked; step 2 the ten heaviest,
     * of which the top three and anything burning half a core are ticked.
     */
    private void showStopChecklist(boolean forceStop, boolean optional) {
        java.util.List<ProcessMonitorViewModel.FixCandidate> all = mViewModel.fixCandidates();
        final java.util.List<ProcessMonitorViewModel.FixCandidate> items =
                forceStop && all.size() > 10 ? all.subList(0, 10) : all;
        if (items.isEmpty()) {
            UIUtils.displayLongToast(getString(R.string.monitor_fork_none_to_stop));
            return;
        }
        MaterialAlertDialogBuilder b = io.github.muntashirakon.AppManager.utils.ForkDialog.builder(this);
        android.content.Context dc = b.getContext();
        float density = getResources().getDisplayMetrics().density;
        int yellow = ContextCompat.getColor(this, R.color.theme_bright_yellow);
        int dim = io.github.muntashirakon.AppManager.fonts.ColorPrefs.getColor(this,
                io.github.muntashirakon.AppManager.fonts.ColorPrefs.SETTINGS_DESCRIPTION);
        android.widget.LinearLayout box = new android.widget.LinearLayout(dc);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Math.round(density * 20);
        box.setPadding(pad, Math.round(density * 8), pad, 0);
        androidx.appcompat.widget.AppCompatTextView note = new androidx.appcompat.widget.AppCompatTextView(dc);
        note.setText(forceStop ? R.string.monitor_fork_force_note
                : optional ? R.string.monitor_fork_kill_note_optional : R.string.monitor_fork_kill_note);
        note.setTextColor(dim);
        note.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(note);
        final java.util.List<android.widget.CheckBox> boxes = new java.util.ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            ProcessMonitorViewModel.FixCandidate c = items.get(i);
            com.google.android.material.checkbox.MaterialCheckBox cb =
                    new com.google.android.material.checkbox.MaterialCheckBox(dc);
            cb.setText(getString(R.string.monitor_fork_candidate, c.label, size(c.memBytes)));
            cb.setTextColor(yellow);
            cb.setButtonTintList(android.content.res.ColorStateList.valueOf(yellow));
            cb.setMinHeight(0);
            cb.setMinimumHeight(0);
            cb.setChecked(!forceStop || i < 3 || c.cpuPercent >= 50);
            boxes.add(cb);
            box.addView(cb);
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(dc);
        scroll.addView(box);
        b.setTitle(forceStop ? R.string.monitor_fork_force_title : R.string.monitor_fork_kill_title)
                .setView(scroll)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(forceStop ? R.string.monitor_fork_force_do : R.string.monitor_fork_kill_go, (d, w) -> {
                    java.util.List<ProcessMonitorViewModel.FixCandidate> chosen = new java.util.ArrayList<>();
                    for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).isChecked()) chosen.add(items.get(i));
                    if (chosen.isEmpty()) return;
                    mFixPending = true;
                    UIUtils.displayShortToast(getString(R.string.monitor_fork_working));
                    mViewModel.fixStop(chosen, forceStop);
                });
        io.github.muntashirakon.AppManager.utils.ForkDialog.present(b);
    }

    @NonNull
    private androidx.appcompat.widget.AppCompatTextView addHealthLine(@NonNull CharSequence text, int color,
                                                                      @Nullable View.OnClickListener click) {
        androidx.appcompat.widget.AppCompatTextView tv = new androidx.appcompat.widget.AppCompatTextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        int pad = Math.round(getResources().getDisplayMetrics().density * 2);
        tv.setPadding(0, pad, 0, pad);
        if (click != null) {
            tv.setOnClickListener(click);
            android.util.TypedValue tvBg = new android.util.TypedValue();
            if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tvBg, true)) {
                tv.setBackgroundResource(tvBg.resourceId);
            }
        }
        mHealth.addView(tv);
        return tv;
    }

    @NonNull
    private static String rate(double perSec) {
        if (Double.isNaN(perSec)) return "—";
        return perSec >= 10 ? String.valueOf(Math.round(perSec)) : String.format(java.util.Locale.US, "%.1f", perSec);
    }

    @NonNull
    private static String uptimeLabel(long ms) {
        long m = ms / 60_000L;
        long d = m / (24 * 60), hr = (m / 60) % 24, min = m % 60;
        if (d > 0) return d + " d " + hr + " h";
        if (hr > 0) return hr + " h " + min + " m";
        return min + " m";
    }

    @NonNull
    private String thermalLabel(int status) {
        switch (status) {
            case 0: return getString(R.string.monitor_thermal_none);
            case 1: return getString(R.string.monitor_thermal_light);
            case 2: return getString(R.string.monitor_thermal_moderate);
            case 3: return getString(R.string.monitor_thermal_severe);
            case 4: return getString(R.string.monitor_thermal_critical);
            case 5: return getString(R.string.monitor_thermal_emergency);
            case 6: return getString(R.string.monitor_thermal_shutdown);
            default: return getString(R.string.monitor_thermal_unknown);
        }
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

    /**
     * Fork (白い熊, +099): the monitor's overflow, as a popup of outlined pills — every tappable
     * action is a pill, never a bare line of text. Both entries open a dialog, hence the ›.
     */
    private void showMoreMenu() {
        float d = getResources().getDisplayMetrics().density;
        int ink = io.github.muntashirakon.AppManager.utils.ForkThemeUtils.getTextColor();
        androidx.appcompat.widget.LinearLayoutCompat body = new androidx.appcompat.widget.LinearLayoutCompat(this);
        body.setOrientation(androidx.appcompat.widget.LinearLayoutCompat.VERTICAL);
        body.setBackgroundResource(R.drawable.alert_dialog_bg_yellow_on_black);
        int pad = Math.round(8 * d);
        body.setPadding(pad, pad, pad, pad);
        // Fork (+104): full-width pills with their icons — the theme's menu rule.
        final android.widget.PopupWindow popup = new android.widget.PopupWindow(body,
                Math.round(280 * d), android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        popup.setElevation(8 * d);
        int[] titles = {R.string.monitor_interval, R.string.monitor_compare, R.string.monitor_report};
        int[] icons = {R.drawable.ic_autorenew, R.drawable.ic_content_duplicate, R.drawable.ic_list_status};
        Runnable[] actions = {this::showIntervalPicker, () -> showCompare(null), () -> {
            mReportPending = true;
            UIUtils.displayShortToast(getString(R.string.monitor_fork_working));
            mViewModel.kernelReport();
        }};
        for (int i = 0; i < titles.length; i++) {
            final Runnable action = actions[i];
            body.addView(io.github.muntashirakon.AppManager.utils.MenuPills.pillRow(this, icons[i],
                    getString(titles[i]), true, ink, v -> {
                        popup.dismiss();
                        action.run();
                    }));
        }
        View anchor = findViewById(R.id.action_monitor_more);
        if (anchor != null) popup.showAsDropDown(anchor);
        else popup.showAtLocation(getWindow().getDecorView(),
                android.view.Gravity.TOP | android.view.Gravity.END, pad, Math.round(96 * d));
    }

    private void openPool(int what) {
        mPoolPending = true;
        UIUtils.displayShortToast(getString(R.string.monitor_fork_working));
        mViewModel.pool(what);
    }

    /**
     * Fork (白い熊, +105, drawn in +106): the filter-program room panel — the room as one large
     * figure with its meter, the stats as tiles, a small chart since the restart. The page
     * behind "Browse" has the rest.
     */
    private void showPoolPanel(@NonNull FilterPool.Summary s) {
        final int ink = PanelViews.ink(), warn = PanelViews.warn(this);
        java.text.SimpleDateFormat dhm = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US);
        android.widget.LinearLayout col = PanelViews.column(this);
        if (s.headline != null) col.addView(PanelViews.note(this, s.headline));
        boolean low = s.free >= 0 && s.exact && s.free < 64;
        col.addView(PanelViews.hero(this,
                s.free < 0 ? "—" : String.format(java.util.Locale.US, "%,d", s.free) + (s.exact ? "" : "+"),
                getString(R.string.pool_page_unit),
                s.free < 0 ? getString(R.string.monitor_pool_none)
                        : getString(s.exact ? R.string.pool_page_hero_exact : R.string.pool_page_hero_atleast,
                        dhm.format(new java.util.Date(s.wallMs))),
                low ? warn : ink));
        if (s.fullest > 0 && s.free >= 0) {
            PanelViews.gap(col, 8);
            col.addView(PanelViews.meter(this, (float) s.free / (float) s.fullest, low ? warn : ink, "0",
                    getString(R.string.pool_page_meter_right,
                            String.format(java.util.Locale.US, "%,d", s.fullest),
                            dhm.format(new java.util.Date(s.fullestWallMs)))));
        }
        java.util.List<FilterPool.Reading> boot = new java.util.ArrayList<>();
        for (FilterPool.Reading r : s.all) if (FilterPool.sameBootAs(s, r)) boot.add(r);
        double[] trend = FilterPool.trend(boot);
        FilterPool.Reading latest = s.all.isEmpty() ? null : s.all.get(s.all.size() - 1);
        java.util.List<PanelViews.Tile> tiles = new java.util.ArrayList<>();
        tiles.add(new PanelViews.Tile(getString(R.string.pool_page_tile_size), s.fullest < 0
                ? getString(R.string.pool_page_not_measured)
                : String.format(java.util.Locale.US, "%,d", s.fullest), ink));
        tiles.add(new PanelViews.Tile(getString(R.string.pool_page_tile_apps),
                latest == null || latest.apps < 0 ? "—" : String.valueOf(latest.apps), ink));
        tiles.add(new PanelViews.Tile(getString(R.string.pool_page_tile_trend),
                Double.isNaN(trend[0]) ? "—" : String.format(java.util.Locale.US, "%+,.1f", trend[0]),
                !Double.isNaN(trend[0]) && trend[0] < -0.5 ? warn : ink));
        boolean ending = !Double.isNaN(trend[0]) && trend[0] < -0.5 && s.exact && s.free > 0;
        tiles.add(new PanelViews.Tile(getString(R.string.pool_page_tile_left),
                ending ? getString(R.string.monitor_pool_days, s.free / -trend[0]) : "—", ink));
        col.addView(PanelViews.tiles(this, 2, tiles));
        if (boot.size() >= 2) {
            java.util.List<PanelViews.Point> points = new java.util.ArrayList<>();
            for (FilterPool.Reading r : boot) {
                PanelViews.Point p = new PanelViews.Point();
                p.timeMs = r.wallMs;
                p.value = r.free;
                p.exact = r.exact;
                p.flagged = r.failing;
                points.add(p);
            }
            java.util.List<long[]> bands = new java.util.ArrayList<>();
            for (FilterPool.Burst b : s.bursts) bands.add(new long[]{b.startMs, b.endMs});
            PanelViews.Chart chart = new PanelViews.Chart(this);
            chart.setCompact(true);
            chart.setData(points, bands, false, "");
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, PanelViews.dp(this, 120));
            lp.topMargin = PanelViews.dp(this, 12);
            col.addView(chart, lp);
        }
        col.addView(PanelViews.note(this, getString(s.recording ? R.string.pool_panel_note : R.string.monitor_pool_off)));
        io.github.muntashirakon.AppManager.utils.ForkDialog.present(
                io.github.muntashirakon.AppManager.utils.ForkDialog.builder(this)
                        .setTitle(R.string.monitor_pool_title)
                        .setView(PanelViews.dialogBody(col))
                        .setPositiveButton(R.string.monitor_pool_measure, (d, w) -> openPool(1))
                        .setNeutralButton(R.string.pool_panel_browse, (d, w) ->
                                startActivity(FilterPoolActivity.intent(this)))
                        .setNegativeButton(R.string.close, null));
    }

    private void attachOverflowLongPress(@NonNull MaterialToolbar toolbar) {
        View more = toolbar.findViewById(R.id.action_monitor_more);
        if (more != null) {
            more.setOnLongClickListener(v -> {
                startActivity(SettingsActivity.getSettingsIntent(this, "fonts_prefs"));
                return true;
            });
        }
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
        toggle(menu, R.id.action_monitor_more, !sel);
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
            showLayoutPicker();
            return true;
        } else if (id == R.id.action_monitor_more) {
            showMoreMenu();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Fork: the same list-layout picker the main screen's grid icon opens, with
     * the same entries and the same prefs shape — the icon used to cycle
     * 1 → 2 → 3 blind, so reaching a layout meant tapping through the ones you
     * did not want and there was no way to see which was in effect.
     */
    private void showLayoutPicker() {
        // Entry index maps straight onto the column count from index 1 on
        // (1 = "1 column", 2 = "2 columns", …); index 0 is adaptive.
        String[] choices = new String[]{
                getString(R.string.layout_adaptive),
                getString(R.string.layout_1_column),
                getString(R.string.layout_2_columns),
                getString(R.string.layout_3_columns),
                getString(R.string.layout_4_columns)};
        int columns = MonitorPrefs.getColumns(this);
        int checked = (columns >= MonitorPrefs.MIN_COLUMNS && columns <= MonitorPrefs.MAX_COLUMNS)
                ? columns : 0;
        presentWithYellowBorder(themedDialog()
                .setTitle(R.string.list_layout)
                .setSingleChoiceItems(choices, checked, (d, which) -> {
                    MonitorPrefs.setColumns(this, which == 0 ? MonitorPrefs.COLUMNS_ADAPTIVE : which);
                    applyListLayout();
                    d.dismiss();
                })
                .setNegativeButton(R.string.cancel, null));
    }

    /** The picked layout: the auto-fit grid, or a fixed column count. */
    private void applyListLayout() {
        int columns = MonitorPrefs.getColumns(this);
        if (columns <= MonitorPrefs.COLUMNS_ADAPTIVE) {
            mList.setLayoutManager(UIUtils.getGridLayoutAt450Dp(this));
        } else {
            mList.setLayoutManager(new GridLayoutManager(this, columns));
        }
        // The pick is per geometry — remember which one this layout is for.
        mLayoutGeometry = LayoutGeometry.key(this);
    }

    /**
     * Fork: re-read the layout when the geometry changed under a surviving
     * activity (multi-window resize, or a window whose geometry moved while we
     * were paused). Folding or rotating normally recreates the activity, and
     * onAuthenticated applies the right pick then.
     */
    private void applyListLayoutIfGeometryChanged() {
        if (mList != null && !LayoutGeometry.key(this).equals(mLayoutGeometry)) {
            applyListLayout();
        }
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
