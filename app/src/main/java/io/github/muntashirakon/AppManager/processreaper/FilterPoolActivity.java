// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.processreaper;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.github.muntashirakon.AppManager.BaseActivity;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.utils.ForkDialog;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;

/**
 * Fork (白い熊, +106): the filter-program room, browsable — the room now, the pool's size, a
 * chart of the room over time with the bursts shaded on it and a second curve to hold against
 * it, the bursts, and every recorded reading. Opened from the monitor's "Filter room" panel.
 * All data comes from {@link FilterPool#summary}, read off the main thread.
 */
public class FilterPoolActivity extends BaseActivity {
    private static final int TABLE_SHORT = 40;
    private static final String[] ALT_KEYS = {"", "apps", "procs", "forks", "mounts", "kernel"};
    private static final int[] ALT_LABELS = {R.string.pool_page_alt_none, R.string.pool_page_alt_apps,
            R.string.pool_page_alt_procs, R.string.pool_page_alt_forks, R.string.pool_page_alt_mounts,
            R.string.pool_page_alt_kernel};
    private static final int[] RANGE_LABELS = {R.string.pool_page_range_boot, R.string.pool_page_range_day,
            R.string.pool_page_range_week, R.string.pool_page_range_all};

    private LinearLayout mContent;
    @Nullable
    private FilterPool.Summary mSummary;
    private int mRange, mAlt;
    private boolean mShowAll, mBusy;

    @NonNull
    public static Intent intent(@NonNull Context context) {
        return new Intent(context, FilterPoolActivity.class);
    }

    @Override
    protected void onAuthenticated(@Nullable Bundle savedInstanceState) {
        setContentView(R.layout.activity_process_detail);
        setSupportActionBar(findViewById(R.id.toolbar));
        ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
            ab.setTitle(R.string.monitor_pool_title);
        }
        mContent = findViewById(R.id.detail_content);
        if (savedInstanceState != null) {
            mRange = savedInstanceState.getInt("range");
            mAlt = savedInstanceState.getInt("alt");
            mShowAll = savedInstanceState.getBoolean("all");
        }
        load(0);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("range", mRange);
        outState.putInt("alt", mAlt);
        outState.putBoolean("all", mShowAll);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void load(int what) {
        if (mBusy) return;
        mBusy = true;
        if (what != 0) UIUtils.displayShortToast(getString(R.string.monitor_fork_working));
        final Context app = getApplicationContext();
        ThreadUtils.postOnBackgroundThread(() -> {
            FilterPool.Summary s;
            try {
                s = FilterPool.run(app, what);
            } catch (Throwable t) {
                s = new FilterPool.Summary();
                s.headline = String.valueOf(t);
            }
            final FilterPool.Summary result = s;
            ThreadUtils.postOnMainThread(() -> {
                mBusy = false;
                if (isFinishing() || isDestroyed()) return;
                mSummary = result;
                render();
            });
        });
    }

    @NonNull
    private static String n(long v) {
        return v < 0 ? "—" : String.format(Locale.US, "%,d", v);
    }

    @NonNull
    private String span(long seconds) {
        if (seconds < 3600) return getString(R.string.monitor_pool_minutes, Math.max(1, seconds / 60));
        if (seconds < 2 * 86400) return getString(R.string.monitor_pool_hours, seconds / 3600.0);
        return getString(R.string.monitor_pool_days, seconds / 86400.0);
    }

    @NonNull
    private List<FilterPool.Reading> inRange(@NonNull FilterPool.Summary s) {
        List<FilterPool.Reading> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (FilterPool.Reading r : s.all) {
            boolean keep;
            switch (mRange) {
                case 0: keep = FilterPool.sameBootAs(s, r); break;
                case 1: keep = now - r.wallMs <= 86_400_000L; break;
                case 2: keep = now - r.wallMs <= 7 * 86_400_000L; break;
                default: keep = true; break;
            }
            if (keep) out.add(r);
        }
        return out;
    }

    private static double altOf(@NonNull FilterPool.Reading r, @NonNull String key) {
        long v;
        switch (key) {
            case "apps": v = r.apps; break;
            case "procs": v = r.procs; break;
            case "forks": v = r.forks; break;
            case "mounts": v = r.mounts; break;
            case "kernel": v = r.sUnreclaimKB < 0 ? -1 : r.sUnreclaimKB / 1024; break;
            default: return Double.NaN;
        }
        return v < 0 ? Double.NaN : v;
    }

    private void render() {
        final FilterPool.Summary s = mSummary;
        if (s == null) return;
        final Context c = this;
        final int ink = PanelViews.ink(), warn = PanelViews.warn(c), dim = PanelViews.dim(c);
        final SimpleDateFormat dhm = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
        mContent.removeAllViews();

        if (s.headline != null) {
            mContent.addView(PanelViews.note(c, s.headline));
            PanelViews.gap(mContent, 8);
        }

        // The room now.
        boolean low = s.free >= 0 && s.exact && s.free < 64;
        mContent.addView(PanelViews.hero(c, s.free < 0 ? "—" : n(s.free) + (s.exact ? "" : "+"),
                getString(R.string.pool_page_unit),
                s.free < 0 ? getString(R.string.monitor_pool_none)
                        : getString(s.exact ? R.string.pool_page_hero_exact : R.string.pool_page_hero_atleast,
                        dhm.format(new Date(s.wallMs))),
                low ? warn : ink));
        if (s.fullest > 0 && s.free >= 0) {
            PanelViews.gap(mContent, 8);
            mContent.addView(PanelViews.meter(c, (float) s.free / (float) s.fullest, low ? warn : ink, "0",
                    getString(R.string.pool_page_meter_right, n(s.fullest),
                            dhm.format(new Date(s.fullestWallMs)))));
        }

        final List<FilterPool.Reading> range = inRange(s);
        List<FilterPool.Reading> boot = new ArrayList<>();
        for (FilterPool.Reading r : s.all) if (FilterPool.sameBootAs(s, r)) boot.add(r);
        double[] trend = FilterPool.trend(boot);
        FilterPool.Reading latest = s.all.isEmpty() ? null : s.all.get(s.all.size() - 1);
        long from = range.isEmpty() ? Long.MAX_VALUE : range.get(0).wallMs;
        int burstsInRange = 0;
        for (FilterPool.Burst b : s.bursts) if (b.startMs >= from) burstsInRange++;

        List<PanelViews.Tile> tiles = new ArrayList<>();
        tiles.add(new PanelViews.Tile(getString(R.string.pool_page_tile_size),
                s.fullest < 0 ? getString(R.string.pool_page_not_measured) : n(s.fullest), ink));
        tiles.add(new PanelViews.Tile(getString(R.string.pool_page_tile_uptime), span(s.uptimeS), ink));
        tiles.add(new PanelViews.Tile(getString(R.string.pool_page_tile_apps),
                latest == null ? "—" : n(latest.apps), ink));
        tiles.add(new PanelViews.Tile(getString(R.string.pool_page_tile_trend),
                Double.isNaN(trend[0]) ? "—" : String.format(Locale.US, "%+,.1f", trend[0]),
                !Double.isNaN(trend[0]) && trend[0] < -0.5 ? warn : ink));
        boolean ending = !Double.isNaN(trend[0]) && trend[0] < -0.5 && s.exact && s.free > 0;
        tiles.add(new PanelViews.Tile(getString(R.string.pool_page_tile_left),
                ending ? getString(R.string.monitor_pool_days, s.free / -trend[0]) : "—",
                ending && s.free / -trend[0] < 7 ? warn : ink));
        tiles.add(new PanelViews.Tile(getString(R.string.pool_page_tile_bursts), n(burstsInRange),
                burstsInRange > 0 ? warn : ink));
        mContent.addView(PanelViews.tiles(c, 3, tiles));

        if (!s.recording) mContent.addView(PanelViews.note(c, getString(R.string.monitor_pool_off)));

        // The chart with its two selectors.
        mContent.addView(PanelViews.heading(c, getString(R.string.pool_page_chart)));
        mContent.addView(selector(RANGE_LABELS, mRange, i -> {
            mRange = i;
            render();
        }));
        mContent.addView(selector(ALT_LABELS, mAlt, i -> {
            mAlt = i;
            render();
        }));
        final String altKey = ALT_KEYS[mAlt];
        List<PanelViews.Point> points = new ArrayList<>();
        for (FilterPool.Reading r : range) {
            PanelViews.Point p = new PanelViews.Point();
            p.timeMs = r.wallMs;
            p.value = r.free;
            p.exact = r.exact;
            p.flagged = r.failing;
            p.alt = altOf(r, altKey);
            p.tag = r;
            points.add(p);
        }
        List<long[]> bands = new ArrayList<>();
        for (FilterPool.Burst b : s.bursts) bands.add(new long[]{b.startMs, b.endMs});
        final TextView readout = PanelViews.line(c, getString(R.string.pool_page_touch), dim);
        readout.setTextSize(12.5f);
        PanelViews.Chart chart = new PanelViews.Chart(c);
        chart.setData(points, bands, mAlt != 0, getString(R.string.pool_page_chart_empty));
        chart.setOnPick(p -> {
            if (p == null || !(p.tag instanceof FilterPool.Reading)) return;
            FilterPool.Reading r = (FilterPool.Reading) p.tag;
            readout.setTextColor(ink);
            readout.setText(getString(R.string.pool_page_readout, dhm.format(new Date(r.wallMs)),
                    n(r.free) + (r.exact ? "" : "+"), r.kind + (r.failing ? " !" : ""), n(r.apps), n(r.procs),
                    n(r.threads), n(r.forks < 0 ? -1 : r.forks / 1000), n(r.mounts),
                    n(r.sUnreclaimKB < 0 ? -1 : r.sUnreclaimKB / 1024), r.uptimeS < 0 ? "—" : span(r.uptimeS)));
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                PanelViews.dp(c, 220));
        clp.topMargin = PanelViews.dp(c, 6);
        mContent.addView(chart, clp);
        mContent.addView(legend(mAlt != 0 ? getString(ALT_LABELS[mAlt]) : null));
        PanelViews.gap(mContent, 4);
        mContent.addView(readout);

        // Actions.
        PanelViews.gap(mContent, 10);
        LinearLayout actions = new LinearLayout(c);
        actions.addView(PanelViews.pill(c, getString(R.string.monitor_pool_measure), ink, true, v -> load(1)));
        actions.addView(PanelViews.pill(c, getString(R.string.monitor_pool_size_go) + "…", ink, false,
                v -> confirm(R.string.pool_page_size_explain, 2)));
        actions.addView(PanelViews.pill(c, getString(R.string.monitor_pool_churn_go) + "…", ink, false,
                v -> confirm(R.string.pool_page_churn_explain, 3)));
        HorizontalScrollView as = new HorizontalScrollView(c);
        as.setHorizontalScrollBarEnabled(false);
        as.addView(actions);
        mContent.addView(as);

        // Bursts.
        mContent.addView(PanelViews.heading(c, getString(R.string.pool_page_bursts, s.bursts.size())));
        if (s.bursts.isEmpty()) {
            mContent.addView(PanelViews.line(c, getString(R.string.pool_page_bursts_none), dim));
        } else {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            int shown = 0;
            for (int i = s.bursts.size() - 1; i >= 0 && (mShowAll || shown < 12); i--, shown++) {
                FilterPool.Burst b = s.bursts.get(i);
                int start = sb.length();
                String length = b.endMs == 0 ? getString(R.string.pool_page_burst_open)
                        : span(Math.max(60, (b.endMs - b.startMs) / 1000L));
                sb.append(String.format(Locale.US, "%s  %-9s", dhm.format(new Date(b.startMs)), length));
                if (b.failures >= 0) sb.append("  ").append(getString(R.string.pool_page_burst_failures, b.failures));
                if (b.appsEnded > 0) {
                    sb.append("  ").append(getString(b.cured ? R.string.pool_page_burst_cured
                            : R.string.pool_page_burst_ended, b.appsEnded));
                }
                if (b.endMs == 0) {
                    sb.setSpan(new ForegroundColorSpan(warn), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                sb.append('\n');
            }
            if (sb.length() > 0) sb.delete(sb.length() - 1, sb.length());
            mContent.addView(scrollable(PanelViews.mono(c, sb)));
        }

        // Readings.
        mContent.addView(PanelViews.heading(c, getString(R.string.pool_page_readings, range.size())));
        if (range.isEmpty()) {
            mContent.addView(PanelViews.line(c, getString(R.string.monitor_pool_table_none), dim));
        } else {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            int headStart = sb.length();
            sb.append(String.format(Locale.US, "%-11s %7s  %-12s %5s %6s %9s %7s", getString(R.string.pool_page_col_time),
                    getString(R.string.pool_page_col_room), getString(R.string.pool_page_col_kind),
                    getString(R.string.pool_page_col_apps), getString(R.string.pool_page_col_procs),
                    getString(R.string.pool_page_col_forks), getString(R.string.pool_page_col_mounts)));
            sb.setSpan(new ForegroundColorSpan(dim), headStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int count = 0;
            for (int i = range.size() - 1; i >= 0 && (mShowAll || count < TABLE_SHORT); i--, count++) {
                FilterPool.Reading r = range.get(i);
                sb.append('\n');
                int start = sb.length();
                sb.append(String.format(Locale.US, "%-11s %7s  %-12s %5s %6s %9s %7s", dhm.format(new Date(r.wallMs)),
                        n(r.free) + (r.exact ? "" : "+"), r.kind, n(r.apps), n(r.procs),
                        n(r.forks < 0 ? -1 : r.forks / 1000), n(r.mounts)));
                if (r.failing) {
                    sb.setSpan(new ForegroundColorSpan(warn), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            mContent.addView(scrollable(PanelViews.mono(c, sb)));
            if (range.size() > TABLE_SHORT || s.bursts.size() > 12) {
                PanelViews.gap(mContent, 6);
                LinearLayout row = new LinearLayout(c);
                row.addView(PanelViews.pill(c, getString(mShowAll ? R.string.pool_page_show_less
                        : R.string.pool_page_show_all, range.size()), ink, false, v -> {
                    mShowAll = !mShowAll;
                    render();
                }));
                mContent.addView(row);
            }
        }
        mContent.addView(PanelViews.note(c, getString(R.string.pool_page_explain)));
    }

    /** A table wider than the screen scrolls sideways by itself. */
    @NonNull
    private HorizontalScrollView scrollable(@NonNull TextView table) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(table);
        return h;
    }

    private interface OnChoose {
        void choose(int index);
    }

    /** One choice out of a few: a row of pills, the chosen one with the heavy frame. */
    @NonNull
    private HorizontalScrollView selector(@NonNull int[] labels, int chosen, @NonNull OnChoose on) {
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            row.addView(PanelViews.pill(this, getString(labels[i]), PanelViews.ink(), i == chosen,
                    v -> on.choose(index)));
        }
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(row);
        return h;
    }

    @NonNull
    private TextView legend(@Nullable String alt) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int ink = PanelViews.ink(), warn = PanelViews.warn(this), dim = PanelViews.dim(this);
        add(sb, "● ", ink);
        add(sb, getString(R.string.pool_page_legend_exact) + "   ", dim);
        add(sb, "○ ", ink);
        add(sb, getString(R.string.pool_page_legend_atleast) + "   ", dim);
        add(sb, "● ", warn);
        add(sb, getString(R.string.pool_page_legend_failing) + "   ", dim);
        add(sb, "▮ ", warn);
        add(sb, getString(R.string.pool_page_legend_burst), dim);
        if (alt != null) {
            add(sb, "   ┄ ", PanelViews.alt(this));
            add(sb, alt, dim);
        }
        TextView v = PanelViews.line(this, sb, dim);
        v.setTextSize(11.5f);
        return v;
    }

    private static void add(@NonNull SpannableStringBuilder sb, @NonNull String text, int color) {
        int start = sb.length();
        sb.append(text);
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void confirm(int explainRes, int what) {
        LinearLayout col = PanelViews.column(this);
        col.addView(PanelViews.note(this, getString(explainRes, FilterPool.CHURN_CYCLES)));
        ForkDialog.present(ForkDialog.builder(this)
                .setTitle(what == 2 ? R.string.monitor_pool_size_go : R.string.monitor_pool_churn_go)
                .setView(PanelViews.dialogBody(col))
                .setPositiveButton(R.string.pool_page_run, (d, w) -> load(what))
                .setNegativeButton(R.string.cancel, null));
    }
}
