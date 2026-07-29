// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.battery;

import android.graphics.PorterDuff;
import android.os.Bundle;
import android.os.UserHandleHidden;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.SearchView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.muntashirakon.AppManager.BaseActivity;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.db.AppsDb;
import io.github.muntashirakon.AppManager.db.dao.BatterySampleDao;
import io.github.muntashirakon.AppManager.details.AppDetailsActivity;
import io.github.muntashirakon.AppManager.utils.UIUtils;

/**
 * Fork: the battery-history screen.
 *
 * <p>Answers the question the platform refuses to: <i>which app drained the
 * battery over the last N hours</i>. Android keeps ten days of daily discharge
 * <b>rates</b> and wipes every per-app counter at each full charge, so this
 * screen reads from {@link BatterySampler}'s own stored deltas instead.
 *
 * <p>Nothing here is labelled mAh. On this device the platform's power model is
 * gutted ({@code Capacity: 5.00}, {@code Computed drain: 0}), so the ranking is
 * built from measured counters — packets, bytes, wakelock ms, radio-active ms,
 * CPU ms, sensor ms — and the composite is called "impact", not power.
 */
public class BatteryUsageActivity extends BaseActivity {
    private static final int[] WINDOW_HOURS = {1, 6, 24, 24 * 3, 24 * 7, 24 * 14};

    private BatteryUsageViewModel mViewModel;
    private BatteryUsageAdapter mAdapter;
    private RecyclerView mList;
    private View mEmpty;
    @Nullable
    private View mHeaderPanel;
    private BatteryLevelView mLevelView;
    private BatteryTopDrainersView mDrainersView;
    private TextView mLevelCaption;
    @Nullable
    private MaterialToolbar mToolbar;

    @Override
    protected void onAuthenticated(@Nullable Bundle savedInstanceState) {
        setContentView(R.layout.activity_battery_usage);
        setSupportActionBar(findViewById(R.id.toolbar));
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.battery_title);
        }
        mToolbar = findViewById(R.id.toolbar);
        mToolbar.setSubtitleTextColor(ContextCompat.getColor(this, R.color.theme_bright_yellow));
        mEmpty = findViewById(R.id.battery_empty);

        mList = findViewById(R.id.battery_list);
        applyListLayout();
        mAdapter = new BatteryUsageAdapter(this, this::openDetail,
                new BatteryUsageAdapter.RowActions() {
                    @Override
                    public void onKill(@NonNull BatteryUsageViewModel.Row row) {
                        killRow(row);
                    }

                    @Override
                    public void onToggleFreeze(@NonNull BatteryUsageViewModel.Row row) {
                        toggleFreezeRow(row);
                    }

                    @Override
                    public void onLongPress(@NonNull BatteryUsageViewModel.Row row) {
                        showRowDetail(row);
                    }
                });
        // Pull the header out of the static layout and hand it to the adapter,
        // so it scrolls away with everything else.
        final View headerPanel = findViewById(R.id.battery_header);
        mHeaderPanel = headerPanel;
        if (headerPanel != null) {
            ((ViewGroup) headerPanel.getParent()).removeView(headerPanel);
            mAdapter.setHeaderView(headerPanel);
        }
        mList.setAdapter(mAdapter);

        mViewModel = new ViewModelProvider(this).get(BatteryUsageViewModel.class);
        mViewModel.getRows().observe(this, rows -> {
            mAdapter.setRows(rows);
            mEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        });
        mViewModel.getSummary().observe(this, this::renderSummary);

        mLevelView = headerPanel.findViewById(R.id.battery_header_level);
        mDrainersView = headerPanel.findViewById(R.id.battery_header_drainers);
        mLevelCaption = headerPanel.findViewById(R.id.battery_header_level_caption);
        mDrainersView.setOnSliceClick(slice -> startActivity(
                BatteryAppDetailActivity.getIntent(this, slice.uid, slice.packageName,
                        mViewModel.getWindowHours())));
        mLevelView.setOnRangeChanged((viewStart, viewEnd, at) -> {
            if (at != null) {
                mLevelCaption.setText(getString(R.string.battery_header_level_at,
                        android.text.format.DateFormat.getTimeFormat(this)
                                .format(new java.util.Date(at.ts)),
                        at.level, at.voltageMv / 1000f,
                        getString(at.charging ? R.string.battery_header_charging
                                : R.string.battery_header_discharging)));
            } else {
                mLevelCaption.setText(getString(R.string.battery_header_level));
            }
        });
        mViewModel.getHeader().observe(this, header -> {
            mLevelView.setPoints(header.points);
            // Opens on the last day; everything older stays reachable by
            // dragging left, which is the point of keeping the full history.
            mLevelView.showLast(24);
            mDrainersView.setSlices(header.drainers);
            TextView drainersCaption = headerPanel == null ? null
                    : headerPanel.findViewById(R.id.battery_header_drainers_caption);
            if (drainersCaption != null) {
                drainersCaption.setText(getString(R.string.battery_header_drainers_used,
                        windowLabel(header.windowMinutes), header.dropPercent));
                drainersCaption.setOnClickListener(v -> showWindowPicker());
            }
            if (headerPanel != null) {
                headerPanel.setVisibility(header.points.size() < 2 && header.drainers.isEmpty()
                        ? View.GONE : View.VISIBLE);
            }
        });

        // Sampling normally runs on the periodic job, but opening the screen is
        // a strong hint that the freshest possible reading is wanted.
        BatterySamplerJob.schedule(this);
        mViewModel.sampleNow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Colours are read at bind time (bindCard), so a rebind is all that a
        // settings change needs.
        if (mAdapter != null) mAdapter.notifyDataSetChanged();
    }

    private void renderSummary(@NonNull BatteryUsageViewModel.Summary summary) {
        if (mToolbar == null) return;
        List<String> parts = new ArrayList<>();
        parts.add(windowLabel(mViewModel.getWindowMinutes()));
        // A filtered list must say so, or the smaller numbers read as a bug.
        if (mViewModel.isScreenOffOnly()) parts.add(getString(R.string.battery_summary_screen_off));
        if (summary.levelDrop >= 0) {
            parts.add(getString(R.string.battery_summary_drop, summary.levelDrop));
        }
        if (summary.coveredMs > 0) {
            parts.add(getString(R.string.battery_summary_deep_doze,
                    BatteryUsageAdapter.formatDuration(summary.deepIdleMs)));
        }
        if (!summary.sampling) {
            parts.add(getString(R.string.battery_summary_paused));
        } else if (summary.buckets == 0) {
            parts.add(getString(R.string.battery_summary_collecting));
        }
        mToolbar.setSubtitle(android.text.TextUtils.join("  ·  ", parts));
        // Same window, said again where the rows are — the header's two panels
        // use their own spans, so three different periods shared one screen
        // with nothing distinguishing them.
        TextView listPeriod = mHeaderPanel == null ? null
                : mHeaderPanel.findViewById(R.id.battery_header_list_period);
        if (listPeriod != null) {
            listPeriod.setText(getString(R.string.battery_list_period,
                    windowLabel(mViewModel.getWindowMinutes())));
        }
    }

    @NonNull
    private String windowLabel(int minutes) {
        if (minutes < 60) return getString(R.string.battery_window_minutes, minutes);
        if (minutes < 1440) {
            int hours = minutes / 60;
            return getResources().getQuantityString(R.plurals.battery_window_hours, hours, hours);
        }
        int days = minutes / 1440;
        return getResources().getQuantityString(R.plurals.battery_window_days, days, days);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.battery_actions, menu);
        MenuItem searchItem = menu.findItem(R.id.action_battery_search);
        if (searchItem != null && searchItem.getActionView() instanceof SearchView) {
            SearchView searchView = (SearchView) searchItem.getActionView();
            // Themed by hand: the yellow-on-black overlay does not reach into
            // SearchView's internal EditText and icons.
            int yellow = ContextCompat.getColor(this, R.color.theme_bright_yellow);
            searchView.setQueryHint(getString(R.string.search));
            EditText editText = searchView.findViewById(androidx.appcompat.R.id.search_src_text);
            if (editText != null) {
                editText.setTextColor(yellow);
                editText.setHintTextColor(0x80FFFF00);
            }
            tintSearchIcon(searchView, androidx.appcompat.R.id.search_mag_icon, yellow);
            tintSearchIcon(searchView, androidx.appcompat.R.id.search_close_btn, yellow);
            tintSearchIcon(searchView, androidx.appcompat.R.id.search_button, yellow);
            searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String query) {
                    mViewModel.setQuery(query);
                    return true;
                }

                @Override
                public boolean onQueryTextChange(String newText) {
                    mViewModel.setQuery(newText);
                    return true;
                }
            });
        }
        return super.onCreateOptionsMenu(menu);
    }

    private static void tintSearchIcon(@NonNull SearchView searchView, int id, int color) {
        View view = searchView.findViewById(id);
        if (view instanceof ImageView) {
            ((ImageView) view).setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem sampling = menu.findItem(R.id.action_battery_sampling);
        if (sampling != null) sampling.setChecked(BatteryPrefs.isEnabled(this));
        MenuItem screenOff = menu.findItem(R.id.action_battery_screen_off);
        if (screenOff != null) screenOff.setChecked(mViewModel.isScreenOffOnly());
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.action_battery_window) {
            showWindowPicker();
            return true;
        } else if (id == R.id.action_battery_sort) {
            showSortPicker();
            return true;
        } else if (id == R.id.action_battery_sample_now) {
            mViewModel.sampleNow();
            UIUtils.displayShortToast(R.string.battery_sampling_now);
            return true;
        } else if (id == R.id.action_battery_sampling) {
            boolean enabled = !BatteryPrefs.isEnabled(this);
            BatteryPrefs.setEnabled(this, enabled);
            item.setChecked(enabled);
            BatterySamplerJob.schedule(this);
            mViewModel.load();
            return true;
        } else if (id == R.id.action_battery_columns) {
            showLayoutPicker();
            return true;
        } else if (id == R.id.action_battery_screen_off) {
            boolean on = !mViewModel.isScreenOffOnly();
            item.setChecked(on);
            mViewModel.setScreenOffOnly(on);
            return true;
        } else if (id == R.id.action_battery_alerts) {
            showAlertSettings();
            return true;
        } else if (id == R.id.action_battery_retention) {
            showRetentionPicker();
            return true;
        } else if (id == R.id.action_battery_status) {
            showStatus();
            return true;
        } else if (id == R.id.action_battery_clear) {
            confirmClear();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * One window picker, reached from the ⋮ entry and from the pill above the
     * drainers. Both write the same setting — the screen shows one span.
     */
    private void showWindowPicker() {
        final int[] options = {15, 30, 60, 120, 360, 720, 1440, 4320, 10080, 20160};
        String[] labels = new String[options.length];
        int checked = 0;
        int current = mViewModel.getWindowMinutes();
        for (int i = 0; i < options.length; i++) {
            labels[i] = windowLabel(options[i]);
            if (options[i] == current) checked = i;
        }
        MaterialAlertDialogBuilder builder = UIUtils.yellowOnBlackDialog(this)
                .setTitle(R.string.battery_window)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    mViewModel.setWindowMinutes(options[which]);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null);
        UIUtils.presentWithYellowBorder(this, builder);
    }

    private void showSortPicker() {
        List<String> labels = new ArrayList<>(Arrays.asList(
                getString(R.string.battery_sort_impact),
                getString(R.string.battery_sort_packets),
                getString(R.string.battery_sort_bytes),
                getString(R.string.battery_sort_wakelock),
                getString(R.string.battery_sort_cpu)));
        // Offered only where the device's power profile is real — a sort by a
        // column that is zero everywhere would look like the list was broken.
        if (mViewModel.isPowerModelUsable()) {
            labels.add(getString(R.string.battery_sort_mah));
        }
        int checked = Math.min(mViewModel.getSort(), labels.size() - 1);
        MaterialAlertDialogBuilder builder = UIUtils.yellowOnBlackDialog(this)
                .setTitle(R.string.battery_sort)
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (dialog, which) -> {
                    mViewModel.setSort(which);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null);
        UIUtils.presentWithYellowBorder(this, builder);
    }

    /**
     * Alerts are off by default and judged over a trailing hour — a threshold
     * that fires on a single busy bucket would train you to ignore it.
     */
    /** Adaptive auto-fit, or a fixed 2/3/4 columns — the main list's rule. */
    private void applyListLayout() {
        int columns = BatteryLayoutPrefs.getColumns(this);
        RecyclerView.LayoutManager manager = columns <= BatteryLayoutPrefs.COLUMNS_ADAPTIVE
                ? UIUtils.getGridLayoutAt450Dp(this)
                : new GridLayoutManager(this, columns);
        // The header is row 0 and must span every column, or it would be laid
        // out as a single cell beside an app.
        if (manager instanceof GridLayoutManager) {
            GridLayoutManager grid = (GridLayoutManager) manager;
            grid.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
                @Override
                public int getSpanSize(int position) {
                    return mAdapter != null && mAdapter.hasHeader() && position == 0
                            ? grid.getSpanCount() : 1;
                }
            });
        }
        mList.setLayoutManager(manager);
    }

    @NonNull
    private String drainerWindowLabel(int minutes) {
        if (minutes < 60) return getString(R.string.battery_window_minutes, minutes);
        int hours = minutes / 60;
        return getResources().getQuantityString(R.plurals.battery_window_hours, hours, hours);
    }

    private void showLayoutPicker() {
        String[] choices = new String[]{
                getString(R.string.layout_adaptive),
                getString(R.string.layout_2_columns),
                getString(R.string.layout_3_columns),
                getString(R.string.layout_4_columns)};
        int columns = BatteryLayoutPrefs.getColumns(this);
        int checked = (columns >= 2 && columns <= 4) ? columns - 1 : 0;
        MaterialAlertDialogBuilder builder = UIUtils.yellowOnBlackDialog(this)
                .setTitle(R.string.list_layout)
                .setSingleChoiceItems(choices, checked, (dialog, which) -> {
                    BatteryLayoutPrefs.setColumns(this, which == 0 ? BatteryLayoutPrefs.COLUMNS_ADAPTIVE : which + 1);
                    applyListLayout();
                    mList.setAdapter(mAdapter);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null);
        UIUtils.presentWithYellowBorder(this, builder);
    }

    private void showAlertSettings() {
        boolean enabled = BatteryPrefs.areAlertsEnabled(this);
        // A plain list, not checkboxes: only the first row is a toggle and the
        // rest open value pickers, which a checkbox list would misrepresent.
        String[] labels = new String[]{
                getString(enabled ? R.string.battery_alerts_on : R.string.battery_alerts_off_row),
                getString(R.string.battery_alerts_packets, BatteryPrefs.getAlertPacketsPerSec(this)),
                getString(R.string.battery_alerts_wakelock, BatteryPrefs.getAlertWakelockPercent(this)),
                getString(R.string.battery_alerts_cpu, BatteryPrefs.getAlertCpuPercent(this)),
        };
        MaterialAlertDialogBuilder builder = UIUtils.yellowOnBlackDialog(this)
                .setTitle(R.string.battery_alerts)
                .setMessage(R.string.battery_alerts_summary)
                .setItems(labels, (dialog, which) -> {
                    if (which == 0) {
                        BatteryPrefs.setAlertsEnabled(this, !enabled);
                    }
                    dialog.dismiss();
                    if (which == 0) showAlertSettings();
                    else showThresholdPicker(which);
                })
                .setPositiveButton(R.string.ok, null);
        UIUtils.presentWithYellowBorder(this, builder);
    }

    private void showThresholdPicker(int which) {
        final int[] options;
        final int titleRes;
        if (which == 1) {
            options = new int[]{0, 5, 10, 20, 50, 100};
            titleRes = R.string.battery_alerts_packets_title;
        } else if (which == 2) {
            options = new int[]{0, 10, 25, 50, 75};
            titleRes = R.string.battery_alerts_wakelock_title;
        } else {
            options = new int[]{0, 5, 10, 25, 50};
            titleRes = R.string.battery_alerts_cpu_title;
        }
        int current = which == 1 ? BatteryPrefs.getAlertPacketsPerSec(this)
                : which == 2 ? BatteryPrefs.getAlertWakelockPercent(this)
                : BatteryPrefs.getAlertCpuPercent(this);
        String[] labels = new String[options.length];
        int checked = 0;
        for (int i = 0; i < options.length; i++) {
            labels[i] = options[i] == 0 ? getString(R.string.battery_alerts_off)
                    : (which == 1 ? getString(R.string.battery_metric_packets_per_sec, options[i])
                    : String.format(java.util.Locale.getDefault(), "%d%%", options[i]));
            if (options[i] == current) checked = i;
        }
        MaterialAlertDialogBuilder builder = UIUtils.yellowOnBlackDialog(this)
                .setTitle(titleRes)
                .setSingleChoiceItems(labels, checked, (dialog, index) -> {
                    int value = options[index];
                    if (which == 1) BatteryPrefs.setAlertPacketsPerSec(this, value);
                    else if (which == 2) BatteryPrefs.setAlertWakelockPercent(this, value);
                    else BatteryPrefs.setAlertCpuPercent(this, value);
                    dialog.dismiss();
                    showAlertSettings();
                })
                .setNegativeButton(R.string.cancel, (d, w) -> showAlertSettings());
        UIUtils.presentWithYellowBorder(this, builder);
    }

    private void showRetentionPicker() {
        int[] options = {1, 3, 7, 14, 30, 60};
        String[] labels = new String[options.length];
        int checked = 0;
        for (int i = 0; i < options.length; i++) {
            labels[i] = getResources().getQuantityString(R.plurals.battery_window_days, options[i], options[i]);
            if (options[i] == BatteryPrefs.getRetentionDays(this)) checked = i;
        }
        MaterialAlertDialogBuilder builder = UIUtils.yellowOnBlackDialog(this)
                .setTitle(R.string.battery_retention)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    BatteryPrefs.setRetentionDays(this, options[which]);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null);
        UIUtils.presentWithYellowBorder(this, builder);
    }

    /**
     * The honesty panel: where the numbers come from, whether the permanent
     * permission has landed, and how much history exists. Without it, an empty
     * list is indistinguishable from a broken one.
     */
    private void showStatus() {
        StringBuilder sb = new StringBuilder();
        boolean direct = BatteryStatsReader.hasDirectAccess();
        sb.append(getString(direct
                ? R.string.battery_status_direct
                : R.string.battery_status_shell)).append("\n\n");
        long last = BatteryPrefs.getLastSampleAt(this);
        sb.append(getString(R.string.battery_status_last_sample, last > 0
                ? DateUtils.getRelativeTimeSpanString(last).toString()
                : getString(R.string.battery_status_never))).append('\n');
        sb.append(getString(R.string.battery_status_interval, BatteryPrefs.getIntervalMinutes(this))).append('\n');
        sb.append(getString(R.string.battery_status_retention, BatteryPrefs.getRetentionDays(this))).append("\n\n");
        // Why there is (or is not) an mAh column on this phone.
        if (mViewModel.isPowerModelUsable()) {
            sb.append(getString(R.string.battery_status_power_model_ok));
        } else {
            Map<String, Double> deviceExtras = mViewModel.extrasFor(BatterySnapshot.UID_DEVICE);
            Double capacity = deviceExtras.get("profile_capacity_mah");
            sb.append(getString(R.string.battery_status_power_model_broken,
                    capacity == null ? 0d : capacity / Math.max(1, mViewModel.deviceBucketCount())));
        }
        MaterialAlertDialogBuilder builder = UIUtils.yellowOnBlackDialog(this)
                .setTitle(R.string.battery_status)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.ok, null);
        UIUtils.presentWithYellowBorder(this, builder);
    }

    private void confirmClear() {
        MaterialAlertDialogBuilder builder = UIUtils.yellowOnBlackDialog(this)
                .setTitle(R.string.battery_clear_history)
                .setMessage(R.string.battery_clear_history_confirm)
                .setPositiveButton(R.string.ok, (dialog, which) -> new Thread(() -> {
                    AppsDb.getInstance().batterySampleDao().clear();
                    BatteryPrefs.clearState(this);
                    runOnUiThread(() -> mViewModel.load());
                }).start())
                .setNegativeButton(R.string.cancel, null);
        UIUtils.presentWithYellowBorder(this, builder);
    }

    /** The ranking's whole point: from "this app drained it" to acting on it. */
    private void openDetail(@NonNull BatteryUsageViewModel.Row row) {
        startActivity(BatteryAppDetailActivity.getIntent(this, row.uid, row.packageName,
                mViewModel.getWindowHours()));
    }

    private void killRow(@NonNull BatteryUsageViewModel.Row row) {
        if (row.packageName == null) return;
        final String packageName = row.packageName;
        final int userId = UserHandleHidden.getUserId(row.uid);
        new Thread(() -> {
            boolean ok = BatteryControls.forceStop(packageName, userId);
            runOnUiThread(() -> UIUtils.displayShortToast(ok
                    ? R.string.battery_force_stopped : R.string.battery_force_stop_failed));
        }).start();
    }

    private void toggleFreezeRow(@NonNull BatteryUsageViewModel.Row row) {
        if (row.packageName == null) return;
        final String packageName = row.packageName;
        final int userId = UserHandleHidden.getUserId(row.uid);
        final boolean freeze = !row.frozen;
        new Thread(() -> {
            String error = null;
            try {
                BatteryControls.setFrozen(packageName, userId, freeze);
            } catch (Throwable th) {
                // The 必要 profile guard lives in FreezeUtils and throws; turn it
                // into the same concrete refusal the other screens show.
                error = BatteryControls.isProtected(packageName)
                        ? getString(R.string.protected_profile_block, row.label)
                        : th.getMessage();
            }
            final String message = error;
            runOnUiThread(() -> {
                if (message != null) UIUtils.displayLongToast(message);
                mViewModel.load();
            });
        }).start();
    }

    /** Kept for the long-press path: the raw counters without leaving the list. */
    private void showRowDetail(@NonNull BatteryUsageViewModel.Row row) {
        BatterySampleDao.BatteryAggregate a = row.agg;
        StringBuilder sb = new StringBuilder();
        appendLine(sb, R.string.battery_detail_window, BatteryUsageAdapter.formatDuration(a.duration));
        if (a.powerModelUsable) {
            appendLine(sb, R.string.battery_detail_power, getString(R.string.battery_mah, a.powerMah));
        }
        appendLine(sb, R.string.battery_detail_wifi, Formatter.formatShortFileSize(this, a.wifiBytes)
                + " / " + a.wifiPackets + " pkt");
        appendLine(sb, R.string.battery_detail_mobile, Formatter.formatShortFileSize(this, a.mobileBytes)
                + " / " + a.mobilePackets + " pkt");
        if (a.duration > 0) {
            double perSecond = a.totalPackets() / (a.duration / 1000d);
            appendLine(sb, R.string.battery_detail_packet_rate,
                    String.format(Locale.getDefault(), "%.1f /s", perSecond));
        }
        appendLine(sb, R.string.battery_detail_radio, BatteryUsageAdapter.formatDuration(a.radioActiveMs));
        appendLine(sb, R.string.battery_detail_wakelock, BatteryUsageAdapter.formatDuration(a.wakelockMs));
        appendLine(sb, R.string.battery_detail_wakeups, String.valueOf(a.wakeupCount));
        appendLine(sb, R.string.battery_detail_cpu, BatteryUsageAdapter.formatDuration(a.cpuMs));
        appendLine(sb, R.string.battery_detail_sensors, BatteryUsageAdapter.formatDuration(a.sensorMs));
        appendLine(sb, R.string.battery_detail_fg_service, BatteryUsageAdapter.formatDuration(a.fgServiceMs));
        appendLine(sb, R.string.battery_detail_uid, String.valueOf(row.uid));

        // Everything else this device happened to record. Different phones and
        // Android releases print different subsets, so this section is whatever
        // was actually there rather than a fixed list with blanks.
        Map<String, Double> extras = mViewModel.extrasFor(row.uid);
        if (!extras.isEmpty()) {
            List<String> keys = new ArrayList<>(extras.keySet());
            Collections.sort(keys);
            sb.append('\n').append(getString(R.string.battery_detail_more)).append('\n');
            for (String key : keys) {
                double value = extras.get(key) == null ? 0 : extras.get(key);
                if (value <= 0) continue;
                sb.append(BatteryMetrics.label(this, key)).append(": ")
                        .append(BatteryMetrics.format(this, key, value)).append('\n');
            }
        }

        MaterialAlertDialogBuilder builder = UIUtils.yellowOnBlackDialog(this)
                .setTitle(row.label)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.ok, null);
        if (row.packageName != null) {
            final String packageName = row.packageName;
            builder.setNeutralButton(R.string.app_info, (dialog, which) ->
                    startActivity(AppDetailsActivity.getIntent(this, packageName,
                            UserHandleHidden.getUserId(row.uid))));
        }
        UIUtils.presentWithYellowBorder(this, builder);
    }

    private void appendLine(@NonNull StringBuilder sb, int labelRes, @NonNull String value) {
        sb.append(getString(labelRes)).append(": ").append(value).append('\n');
    }
}
