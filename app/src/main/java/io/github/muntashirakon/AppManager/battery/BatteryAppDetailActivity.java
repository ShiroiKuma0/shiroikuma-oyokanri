// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.battery;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandleHidden;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.muntashirakon.AppManager.BaseActivity;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.db.AppsDb;
import io.github.muntashirakon.AppManager.db.dao.BatterySampleDao;
import io.github.muntashirakon.AppManager.db.entity.BatterySample;
import io.github.muntashirakon.AppManager.details.AppDetailsActivity;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.main.ApplicationItem;
import io.github.muntashirakon.AppManager.main.MainViewModel;
import io.github.muntashirakon.AppManager.self.imagecache.ImageLoader;
import io.github.muntashirakon.AppManager.settings.Ops;
import io.github.muntashirakon.AppManager.snooping.SnoopingState;
import io.github.muntashirakon.AppManager.users.Users;
import io.github.muntashirakon.AppManager.snooping.lever.SnoopingLever;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;

/**
 * Fork: the per-app battery control panel.
 *
 * <p>Replaces the read-only dialog the ranking used to open. A list that only
 * tells you an app is draining the battery leaves you to guess which control
 * addresses it, and the cheapest guess — freeze it — is usually the wrong one:
 * a map app burning GPS needs a location lever, not removal. So this screen
 * pairs the measurement with the specific lever that answers it
 * ({@link BatteryAdvisor}), and keeps the blunt instruments (force-stop,
 * freeze) available but last.
 */
public class BatteryAppDetailActivity extends BaseActivity {
    public static final String EXTRA_UID = "uid";
    public static final String EXTRA_PACKAGE = "package";
    public static final String EXTRA_WINDOW_HOURS = "window_hours";

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private LinearLayoutCompat mContainer;
    private int mUid;
    private int mUserId;
    @Nullable
    private String mPackageName;
    private int mWindowHours = 24;

    @Nullable
    private PackageInfo mPackageInfo;
    @Nullable
    private ApplicationInfo mApplicationInfo;
    @Nullable
    private BatterySampleDao.BatteryAggregate mAggregate;
    /** The equally long window immediately before this one. */
    @Nullable
    private BatterySampleDao.BatteryAggregate mPrevious;
    private Map<String, Double> mExtras = Collections.emptyMap();
    private List<BatteryHistoryView.Bar> mHistory = Collections.emptyList();
    private boolean mFrozen;
    private float mShare;
    @Nullable
    private ApplicationItem mAppItem;
    private List<String> mProfileTags = Collections.emptyList();
    @Nullable
    private LinearLayoutCompat mPillRow;
    private int mPillRowCount;

    @NonNull
    public static Intent getIntent(@NonNull Context context, int uid, @Nullable String packageName,
                                   int windowHours) {
        Intent intent = new Intent(context, BatteryAppDetailActivity.class);
        intent.putExtra(EXTRA_UID, uid);
        intent.putExtra(EXTRA_PACKAGE, packageName);
        intent.putExtra(EXTRA_WINDOW_HOURS, windowHours);
        return intent;
    }

    @Override
    protected void onAuthenticated(@Nullable Bundle savedInstanceState) {
        setContentView(R.layout.activity_battery_detail);
        setSupportActionBar(findViewById(R.id.toolbar));
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);
        mContainer = findViewById(R.id.battery_detail_container);

        mUid = getIntent().getIntExtra(EXTRA_UID, -1);
        mPackageName = getIntent().getStringExtra(EXTRA_PACKAGE);
        mWindowHours = getIntent().getIntExtra(EXTRA_WINDOW_HOURS, 24);
        mUserId = UserHandleHidden.getUserId(mUid);
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mContainer != null && mAggregate != null) reload();
    }

    private void reload() {
        mExecutor.execute(() -> {
            gather();
            runOnUiThread(this::render);
        });
    }

    private void gather() {
        long since = System.currentTimeMillis() - mWindowHours * 3_600_000L;
        try {
            BatterySampleDao dao = AppsDb.getInstance().batterySampleDao();
            // The same share the ranking shows, so the header pill carries the
            // app's real figure rather than a meaningless 100% of itself.
            long totalImpact = 0;
            for (BatterySampleDao.BatteryAggregate agg : dao.aggregate(since)) {
                totalImpact += agg.impactScore();
                if (agg.uid == mUid) mAggregate = agg;
            }
            mShare = totalImpact > 0 && mAggregate != null
                    ? (float) mAggregate.impactScore() / totalImpact : 0f;
            mExtras = BatterySampler.mergeExtras(dao.extrasForUid(mUid, since));
            // Before/after: this window against the equally long one before it.
            // After throttling an app the only question that matters is whether
            // it worked, and delta storage answers it with two sums.
            long windowMs = mWindowHours * 3_600_000L;
            mPrevious = dao.aggregateForUidRange(mUid, since - windowMs, since);
            List<BatterySample> samples = dao.forUid(mUid, since);
            List<BatteryHistoryView.Bar> history = new ArrayList<>(samples.size());
            for (BatterySample s : samples) {
                // One bar = this bucket's share of implied wakefulness, matching
                // the ranking's own notion of impact. Placed on a real time axis
                // by its own [start, end), so Doze gaps stay visibly empty.
                long value = s.wakelockMs + s.radioActiveMs + s.cpuMs + s.sensorMs
                        + (s.wifiPackets + s.mobilePackets) * 2;
                history.add(new BatteryHistoryView.Bar(s.ts - s.duration, s.ts, value));
            }
            mHistory = history;
        } catch (Throwable th) {
            Log.e("BatteryDetail", "Could not read history", th);
        }
        if (mPackageName != null) {
            try {
                // MATCH_UNINSTALLED_PACKAGES is what makes a *frozen* app resolve:
                // a hidden package is invisible to a plain lookup, and without it
                // the very apps you froze for draining would lose their name and
                // icon here.
                mPackageInfo = PackageManagerCompat.getPackageInfo(mPackageName,
                        PackageManager.GET_PERMISSIONS | PackageManagerCompat.MATCH_UNINSTALLED_PACKAGES
                                | PackageManager.MATCH_DISABLED_COMPONENTS, mUserId);
                mApplicationInfo = mPackageInfo != null ? mPackageInfo.applicationInfo : null;
            } catch (Throwable th) {
                mPackageInfo = null;
                mApplicationInfo = null;
            }
        }
        mFrozen = BatteryControls.isFrozen(mApplicationInfo);
        if (mPackageName != null) {
            try {
                // The real main-list item, from the same derivation the main list
                // uses — so the header card carries the same version, type, SDK,
                // signature and profile tags rather than an approximation.
                mAppItem = MainViewModel.buildApplicationItem(getApplicationContext(), mPackageName,
                        AppsDb.getInstance().appDao().getAll(mPackageName));
                mProfileTags = ProfileTags.forPackage(mPackageName);
            } catch (Throwable th) {
                mAppItem = null;
            }
        }
    }

    // ---------------------------------------------------------------- render

    private void render() {
        if (mContainer == null) return;
        mContainer.removeAllViews();
        float density = getResources().getDisplayMetrics().density;

        CharSequence label = mApplicationInfo != null
                ? mApplicationInfo.loadLabel(getPackageManager())
                : (mPackageName != null ? mPackageName : "uid " + mUid);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) actionBar.setTitle(label);

        addHeader(density, label);
        addRecommendations(density);
        addComparison(density);
        addHistory(density);
        addControls(density);
        addStats(density);
    }

    /**
     * This window against the one before it. Rates, not totals — the previous
     * window can be shorter (the history simply does not reach back far enough
     * yet), and comparing raw sums would then invent an improvement that is
     * only missing data.
     */
    private void addComparison(float density) {
        if (mAggregate == null || mPrevious == null) return;
        if (mPrevious.duration < mAggregate.duration / 4) return; // too little to compare
        addSectionHeading(density, getString(R.string.battery_section_comparison));
        addComparisonRow(density, getString(R.string.battery_compare_packets),
                rate(mAggregate.totalPackets(), mAggregate.duration) * 1000,
                rate(mPrevious.totalPackets(), mPrevious.duration) * 1000, false);
        addComparisonRow(density, getString(R.string.battery_compare_wakelock),
                percent(mAggregate.wakelockMs, mAggregate.duration),
                percent(mPrevious.wakelockMs, mPrevious.duration), true);
        addComparisonRow(density, getString(R.string.battery_compare_cpu),
                percent(mAggregate.cpuMs, mAggregate.duration),
                percent(mPrevious.cpuMs, mPrevious.duration), true);
        addComparisonRow(density, getString(R.string.battery_compare_sensors),
                percent(mAggregate.sensorMs, mAggregate.duration),
                percent(mPrevious.sensorMs, mPrevious.duration), true);
        TextView caption = plainText(12, 0xFF7A7A7A);
        int m = Math.round(density * 20);
        caption.setPadding(m, Math.round(density * 4), m, 0);
        caption.setText(getString(R.string.battery_compare_caption,
                BatteryUsageAdapter.formatDuration(mPrevious.duration)));
        mContainer.addView(caption);
    }

    private static double rate(long value, long durationMs) {
        return durationMs > 0 ? (double) value / durationMs : 0;
    }

    private static double percent(long value, long durationMs) {
        return durationMs > 0 ? 100d * value / durationMs : 0;
    }

    private void addComparisonRow(float density, @NonNull String label, double now, double before,
                                  boolean isPercent) {
        if (now <= 0 && before <= 0) return;
        LinearLayoutCompat row = new LinearLayoutCompat(this);
        row.setOrientation(LinearLayoutCompat.HORIZONTAL);
        int padH = Math.round(density * 20);
        row.setPadding(padH, Math.round(density * 3), padH, Math.round(density * 3));

        TextView k = plainText(13, 0xFF9A9A9A);
        k.setLayoutParams(new LinearLayoutCompat.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        k.setText(label);
        row.addView(k);

        String fmt = isPercent ? "%.1f%%" : "%.1f";
        TextView v = plainText(13, ForkThemeUtils.getTextColor());
        v.setLayoutParams(new LinearLayoutCompat.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        String arrow;
        int colour;
        if (before <= 0) {
            arrow = "";
            colour = ForkThemeUtils.getTextColor();
        } else {
            double change = 100 * (now - before) / before;
            // Down is good here — every metric on this panel is a cost.
            colour = change <= -10 ? 0xFF6ADf6A : change >= 10 ? 0xFFFF0028 : 0xFF9A9A9A;
            arrow = String.format(Locale.getDefault(), "  (%s%.0f%%)", change > 0 ? "+" : "", change);
        }
        v.setText(String.format(Locale.getDefault(), fmt, now)
                + "  ←  " + String.format(Locale.getDefault(), fmt, before) + arrow);
        v.setTextColor(colour);
        row.addView(v);
        mContainer.addView(row);
    }

    /**
     * The header is the <b>same card the ranking uses</b> — icon column with the
     * freeze snowflake and force-stop ✕, label, package, and the app's drain on
     * the right, colour-bordered by type exactly as on the main list. Reusing
     * the row rather than inventing a second header keeps one visual language
     * across the three screens, and it is clickable straight through to App
     * details, which is why the Controls section no longer needs an "Open" row.
     */
    /**
     * The header is <b>the main list's own card</b>, inflated from the same
     * layout and populated from the same {@link ApplicationItem} derivation —
     * version, type, SDK, signature, profile tags and all. Reusing the card
     * rather than approximating it means the three screens cannot drift, and it
     * is clickable through to App details, which is why Controls no longer
     * needs an "Open" row.
     */
    private void addHeader(float density, @NonNull CharSequence label) {
        View card = getLayoutInflater().inflate(R.layout.item_main, mContainer, false);
        BatteryUsageViewModel.Row row = new BatteryUsageViewModel.Row(
                mAggregate != null ? mAggregate : new BatterySampleDao.BatteryAggregate());
        row.share = mShare;
        MainCardBinder.bind(this, card, mAppItem, mApplicationInfo, mPackageName, mUid, mFrozen,
                mProfileTags, row, BatteryUsageViewModel.shortWindowLabel(
                        BatteryPrefs.getWindowMinutes(this)), true,
                v -> openAppDetails(), v -> toggleFreeze(), v -> forceStop());
        mContainer.addView(card);
    }

    private void addRecommendations(float density) {
        if (mAggregate == null) return;
        boolean exempt = mPackageInfo != null
                && BatteryControls.isLeverAllowed(BatteryControls.LEVER_BATTERY_EXEMPTION,
                mPackageInfo, mUserId);
        // Every applicable recommendation is offered, always. Hiding one because
        // privileges happen to be down right now would make the page's contents
        // depend on whether ADB was up when you opened it — 白い熊's call, and the
        // right one. When an action cannot be carried out the pill says why.
        List<BatteryAdvisor.Suggestion> suggestions = BatteryAdvisor.advise(this, mAggregate, exempt);
        if (suggestions.isEmpty()) return;
        addSectionHeading(density, getString(R.string.battery_section_recommended));
        for (BatteryAdvisor.Suggestion s : suggestions) {
            addSuggestionRow(density, s);
        }
    }

    private void addSuggestionRow(float density, @NonNull BatteryAdvisor.Suggestion s) {
        LinearLayoutCompat row = new LinearLayoutCompat(this);
        row.setOrientation(LinearLayoutCompat.VERTICAL);
        int pad = Math.round(density * 12);
        row.setPadding(pad, pad, pad, pad);

        int accent = s.severity == BatteryAdvisor.SEVERITY_HIGH ? 0xFFFF0028
                : s.severity == BatteryAdvisor.SEVERITY_WARN ? 0xFFFFC24B
                : ForkThemeUtils.getTextColor();
        TextView title = plainText(16, accent);
        title.setText(getString(s.titleRes));
        row.addView(title);
        TextView reason = plainText(13, 0xFF9A9A9A);
        reason.setText(s.reason);
        row.addView(reason);
        // A recommendation that maps to a switch carries the switch, so acting
        // on it does not mean hunting for the matching control further down.
        String op = opForAction(s.actionId);
        Boolean state = null;
        if (op != null && mPackageName != null) {
            state = BatteryControls.isOpAllowed(op, mUid, mPackageName);
        } else if (BatteryAdvisor.ACTION_BATTERY_EXEMPTION.equals(s.actionId) && mPackageInfo != null) {
            state = BatteryControls.isLeverAllowed(BatteryControls.LEVER_BATTERY_EXEMPTION,
                    mPackageInfo, mUserId);
        }
        if (state != null) {
            MaterialSwitch sw = new MaterialSwitch(this);
            sw.setChecked(state);
            final String opName = op;
            sw.setOnClickListener(v -> {
                if (opName != null) toggleOp(opName, sw.isChecked());
                else toggleLever(BatteryControls.LEVER_BATTERY_EXEMPTION, sw.isChecked());
            });
            row.addView(sw);
            row.setOnClickListener(v -> sw.performClick());
        } else {
            row.setOnClickListener(v -> runSuggestion(s));
        }
        addPill(row, density);
    }

    /** The app-op a recommendation toggles, or null when it is not a switch. */
    @Nullable
    private static String opForAction(@NonNull String actionId) {
        switch (actionId) {
            case BatteryAdvisor.ACTION_WAKE_LOCK: return BatteryControls.OP_WAKE_LOCK;
            case BatteryAdvisor.ACTION_START_FOREGROUND: return BatteryControls.OP_START_FOREGROUND;
            case BatteryAdvisor.ACTION_RUN_ANY_BACKGROUND: return BatteryControls.OP_RUN_ANY_IN_BACKGROUND;
            default: return null;
        }
    }

    /** Jumps to the control the suggestion is about, rather than acting blind. */
    private void runSuggestion(@NonNull BatteryAdvisor.Suggestion s) {
        switch (s.actionId) {
            case BatteryAdvisor.ACTION_FREEZE:
                toggleFreeze();
                break;
            case BatteryAdvisor.ACTION_NETWORK:
                cycleNetworkLever();
                break;
            case BatteryAdvisor.ACTION_BATTERY_EXEMPTION:
                toggleLever(BatteryControls.LEVER_BATTERY_EXEMPTION, false);
                break;
            case BatteryAdvisor.ACTION_LOCATION:
                openSnooping();
                break;
            case BatteryAdvisor.ACTION_WAKE_LOCK:
                toggleOp(BatteryControls.OP_WAKE_LOCK, false);
                break;
            case BatteryAdvisor.ACTION_START_FOREGROUND:
                toggleOp(BatteryControls.OP_START_FOREGROUND, false);
                break;
            default:
                toggleOp(BatteryControls.OP_RUN_ANY_IN_BACKGROUND, false);
                break;
        }
    }

    private void addHistory(float density) {
        if (mHistory.size() < 2) return;
        addSectionHeading(density, getString(R.string.battery_section_history));
        BatteryHistoryView chart = new BatteryHistoryView(this);
        LinearLayoutCompat.LayoutParams lp = new LinearLayoutCompat.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(density * 132));
        int m = Math.round(density * 20);
        lp.leftMargin = m;
        lp.rightMargin = m;
        lp.bottomMargin = Math.round(density * 4);
        chart.setLayoutParams(lp);

        TextView caption = plainText(15, ForkThemeUtils.getTextColor());
        caption.setPadding(m, 0, m, Math.round(density * 8));
        // The caption doubles as the readout: it names the visible range, and
        // the selected bucket's own window and value once you tap one. Without
        // it a zoomed-in chart says nothing about *when* you are looking at.
        chart.setOnBarSelected((bar, viewStart, viewEnd) -> {
            if (bar != null) {
                // Say what the height means, not just when it happened — the bar
                // is awake-equivalent time, and "12% of the bucket" is the part
                // that tells you whether it mattered.
                long bucket = Math.max(1, bar.end - bar.start);
                caption.setText(getString(R.string.battery_history_selected,
                        formatRange(bar.start, bar.end),
                        BatteryUsageAdapter.formatDuration(bucket),
                        BatteryHistoryView.formatValue(bar.value),
                        100d * bar.value / bucket));
            } else {
                caption.setText(getString(R.string.battery_history_range,
                        formatRange(viewStart, viewEnd), mHistory.size()));
            }
        });
        chart.setBars(mHistory);
        mContainer.addView(chart);
        mContainer.addView(caption);

        TextView legend = plainText(13, 0xFF9A9A9A);
        legend.setPadding(m, 0, m, Math.round(density * 4));
        legend.setText(R.string.battery_history_legend);
        mContainer.addView(legend);

        TextView hint = plainText(12, 0xFF8A8A8A);
        hint.setPadding(m, 0, m, Math.round(density * 8));
        hint.setText(R.string.battery_history_hint);
        mContainer.addView(hint);
    }

    /** Compact range label — drops the date when both ends share a day. */
    @NonNull
    private String formatRange(long start, long end) {
        java.text.DateFormat time = android.text.format.DateFormat.getTimeFormat(this);
        java.text.DateFormat date = android.text.format.DateFormat.getDateFormat(this);
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(start);
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(end);
        boolean sameDay = a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
        if (sameDay) {
            return date.format(new Date(start)) + " " + time.format(new Date(start))
                    + "–" + time.format(new Date(end));
        }
        return date.format(new Date(start)) + " " + time.format(new Date(start))
                + " – " + date.format(new Date(end)) + " " + time.format(new Date(end));
    }

    private void addControls(float density) {
        addSectionHeading(density, getString(R.string.battery_section_controls));

        // Freeze — the 必要 profile is enforced inside FreezeUtils, so a
        // protected app shows the switch and gets a concrete refusal.
        addSwitchRow(density, getString(R.string.battery_control_freeze),
                getString(R.string.battery_control_freeze_summary), mFrozen,
                checked -> toggleFreeze());

        if (mPackageInfo != null) {
            SnoopingLever network = BatteryControls.lever(BatteryControls.LEVER_NETWORK);
            if (network != null && network.isApplicable(mPackageInfo, mUserId)) {
                int state = network.getState(mPackageInfo, mUserId);
                addActionRow(density, getString(R.string.battery_control_network),
                        stateLabel(network, state), v -> cycleNetworkLever());
            }
            SnoopingLever doze = BatteryControls.lever(BatteryControls.LEVER_BATTERY_EXEMPTION);
            if (doze != null && doze.isApplicable(mPackageInfo, mUserId)) {
                boolean exempt = doze.isAllowed(mPackageInfo, mUserId);
                addSwitchRow(density, getString(R.string.battery_control_doze_exemption),
                        getString(R.string.battery_control_doze_exemption_summary), exempt,
                        checked -> toggleLever(BatteryControls.LEVER_BATTERY_EXEMPTION, checked));
            }
        }

        addOpRow(density, BatteryControls.OP_RUN_ANY_IN_BACKGROUND,
                R.string.battery_control_run_any_background, R.string.battery_control_run_any_background_summary);
        addOpRow(density, BatteryControls.OP_RUN_IN_BACKGROUND,
                R.string.battery_control_run_background, R.string.battery_control_run_background_summary);
        addOpRow(density, BatteryControls.OP_START_FOREGROUND,
                R.string.battery_control_start_foreground, R.string.battery_control_start_foreground_summary);
        addOpRow(density, BatteryControls.OP_WAKE_LOCK,
                R.string.battery_control_wake_lock, R.string.battery_control_wake_lock_summary);

        addActionRow(density, getString(R.string.battery_control_force_stop),
                getString(R.string.battery_control_force_stop_summary), v -> forceStop());
        // Accepting an app as legitimately heavy (a music player, navigation)
        // has to be possible, or the alerts become noise you learn to dismiss.
        if (mPackageName != null) {
            final String packageName = mPackageName;
            boolean ignored = BatteryPrefs.isIgnored(this, packageName);
            addActionRow(density,
                    getString(ignored ? R.string.battery_alert_unignore : R.string.battery_alert_ignore),
                    null, v -> {
                        BatteryPrefs.setIgnored(this, packageName, !ignored);
                        render();
                    });
        }
    }

    /**
     * A lever's own name for a state, falling back to the generic one.
     *
     * <p><b>{@link SnoopingLever#stateLabelRes} returns 0 to mean "use the
     * generic label", not "no label"</b> — passing it straight to
     * {@code getString} throws {@code Resources$NotFoundException: String
     * resource ID #0x0}, which is exactly how this screen crashed in +27 (the
     * network lever only names its FOREGROUND rung). The fallback below mirrors
     * {@code AppDetailsSnoopingFragment} so the two screens cannot disagree
     * about what a state is called.
     */
    @NonNull
    private String stateLabel(@NonNull SnoopingLever lever, int state) {
        int res = lever.stateLabelRes(state);
        if (res == 0) {
            res = state == SnoopingState.FOREGROUND ? R.string.snooping_state_foreground
                    : state == SnoopingState.ALLOWED ? R.string.snooping_state_allowed
                    : R.string.snooping_state_blocked;
        }
        return getString(res);
    }

    private void addOpRow(float density, @NonNull String opName, @StringRes int titleRes,
                          @StringRes int summaryRes) {
        if (mPackageName == null || !BatteryControls.hasOp(opName)) return;
        Boolean allowed = BatteryControls.isOpAllowed(opName, mUid, mPackageName);
        if (allowed == null) return;
        addSwitchRow(density, getString(titleRes), getString(summaryRes), allowed,
                checked -> toggleOp(opName, checked));
    }

    private void addStats(float density) {
        if (mAggregate == null) return;
        addSectionHeading(density, getString(R.string.battery_section_stats));
        BatterySampleDao.BatteryAggregate a = mAggregate;
        addKeyValue(density, getString(R.string.battery_detail_window),
                BatteryUsageAdapter.formatDuration(a.duration));
        if (a.powerModelUsable) {
            addKeyValue(density, getString(R.string.battery_detail_power),
                    getString(R.string.battery_mah, a.powerMah));
        }
        addKeyValue(density, getString(R.string.battery_detail_wifi),
                Formatter.formatShortFileSize(this, a.wifiBytes) + " / " + a.wifiPackets + " pkt");
        addKeyValue(density, getString(R.string.battery_detail_mobile),
                Formatter.formatShortFileSize(this, a.mobileBytes) + " / " + a.mobilePackets + " pkt");
        if (a.duration > 0) {
            addKeyValue(density, getString(R.string.battery_detail_packet_rate),
                    String.format(Locale.getDefault(), "%.1f /s",
                            a.totalPackets() / (a.duration / 1000d)));
        }
        addKeyValue(density, getString(R.string.battery_detail_radio),
                BatteryUsageAdapter.formatDuration(a.radioActiveMs));
        addKeyValue(density, getString(R.string.battery_detail_wakelock),
                BatteryUsageAdapter.formatDuration(a.wakelockMs));
        addKeyValue(density, getString(R.string.battery_detail_wakeups), String.valueOf(a.wakeupCount));
        addKeyValue(density, getString(R.string.battery_detail_cpu),
                BatteryUsageAdapter.formatDuration(a.cpuMs));
        addKeyValue(density, getString(R.string.battery_detail_sensors),
                BatteryUsageAdapter.formatDuration(a.sensorMs));
        addKeyValue(density, getString(R.string.battery_detail_fg_service),
                BatteryUsageAdapter.formatDuration(a.fgServiceMs));

        if (!mExtras.isEmpty()) {
            List<String> keys = new ArrayList<>(mExtras.keySet());
            Collections.sort(keys);
            for (String key : keys) {
                Double value = mExtras.get(key);
                if (value == null || value <= 0) continue;
                addKeyValue(density, BatteryMetrics.label(this, key),
                        BatteryMetrics.format(this, key, value));
            }
        }
    }

    // --------------------------------------------------------------- actions

    private void toggleFreeze() {
        if (mPackageName == null) return;
        final String packageName = mPackageName;
        final boolean freeze = !mFrozen;
        mExecutor.execute(() -> {
            String error = null;
            try {
                BatteryControls.setFrozen(packageName, mUserId, freeze);
            } catch (Throwable th) {
                error = BatteryControls.isProtected(packageName)
                        ? getString(R.string.protected_profile_block, packageName)
                        : th.getMessage();
            }
            final String message = error;
            gather();
            runOnUiThread(() -> {
                if (message != null) UIUtils.displayLongToast(message);
                render();
            });
        });
    }

    private void forceStop() {
        if (mPackageName == null) return;
        final String packageName = mPackageName;
        MaterialAlertDialogBuilder builder = UIUtils.yellowOnBlackDialog(this)
                .setTitle(R.string.battery_control_force_stop)
                .setMessage(getString(R.string.battery_force_stop_confirm, packageName))
                .setPositiveButton(R.string.ok, (d, w) -> mExecutor.execute(() -> {
                    boolean ok = BatteryControls.forceStop(packageName, mUserId);
                    runOnUiThread(() -> UIUtils.displayShortToast(ok
                            ? R.string.battery_force_stopped : R.string.battery_force_stop_failed));
                }))
                .setNegativeButton(R.string.cancel, null);
        UIUtils.presentWithYellowBorder(this, builder);
    }

    private void toggleOp(@NonNull String opName, boolean allowed) {
        if (mPackageName == null) return;
        final String packageName = mPackageName;
        mExecutor.execute(() -> {
            boolean ok = BatteryControls.setOpAllowed(opName, mUid, packageName, allowed);
            gather();
            runOnUiThread(() -> {
                if (!ok) reportFailure();
                render();
            });
        });
    }

    private void toggleLever(@NonNull String leverId, boolean allowed) {
        if (mPackageInfo == null) return;
        final PackageInfo packageInfo = mPackageInfo;
        mExecutor.execute(() -> {
            boolean ok = BatteryControls.setLeverAllowed(leverId, packageInfo, mUserId, allowed);
            gather();
            runOnUiThread(() -> {
                if (!ok) reportFailure();
                render();
            });
        });
    }

    /**
     * The network lever is three-state (allowed → no background mobile data →
     * blocked), so it advances rather than toggles — same behaviour as the
     * 盗み見 card, so the two screens cannot disagree.
     */
    private void cycleNetworkLever() {
        if (mPackageInfo == null) return;
        final PackageInfo packageInfo = mPackageInfo;
        SnoopingLever lever = BatteryControls.lever(BatteryControls.LEVER_NETWORK);
        if (lever == null) return;
        mExecutor.execute(() -> {
            // Advance through the states this lever really offers. Cycling a
            // hard-coded allowed → foreground → blocked asked for a middle rung
            // the lever does not have when the firewall path is unavailable, and
            // it correctly refused — which surfaced as "the platform refused
            // that change" and looked like a privilege problem. It was not.
            int[] states = lever.supportedStates();
            int state = lever.getState(packageInfo, mUserId);
            int index = 0;
            for (int i = 0; i < states.length; i++) {
                if (states[i] == state) {
                    index = i;
                    break;
                }
            }
            int next = states[(index + 1) % states.length];
            boolean ok = lever.setState(packageInfo, mUserId, next);
            gather();
            runOnUiThread(() -> {
                if (!ok) reportFailure();
                render();
            });
        });
    }

    /** Opens App details on the 盗み見 tab, not its front page. */
    /**
     * Why a change did not take. Missing privileges is by far the most common
     * cause and the only one 白い熊 can act on, so it is named explicitly rather
     * than hidden behind a generic refusal.
     */
    private void reportFailure() {
        if (!Ops.isAuthenticated() || Users.getSelfOrRemoteUid() == Process.myUid()) {
            UIUtils.displayLongToast(R.string.battery_control_needs_adb);
        } else {
            UIUtils.displayLongToast(R.string.battery_control_refused);
        }
    }

    private void openSnooping() {
        if (mPackageName == null) return;
        startActivity(AppDetailsActivity.getIntent(this, mPackageName, mUserId,
                AppDetailsActivity.TAB_SNOOPING));
    }

    private void openAppDetails() {
        if (mPackageName == null) return;
        startActivity(AppDetailsActivity.getIntent(this, mPackageName, mUserId));
    }

    // ----------------------------------------------------------- view helpers

    private int outValueSelectableBackground() {
        android.util.TypedValue out = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true);
        return out.resourceId;
    }

    @NonNull
    private TextView plainText(float sizeSp, int color) {
        AppCompatTextView tv = new AppCompatTextView(this);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        tv.setLayoutParams(new LinearLayoutCompat.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return tv;
    }

    private void addSectionHeading(float density, @NonNull String text) {
        endPillRow();
        TextView tv = plainText(17, ForkThemeUtils.getTextColor());
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        int padH = Math.round(density * 16);
        tv.setPadding(padH, Math.round(density * 16), padH, Math.round(density * 4));
        tv.setText(text);
        mContainer.addView(tv);
        View rule = new View(this);
        LinearLayoutCompat.LayoutParams lp = new LinearLayoutCompat.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(density)));
        lp.bottomMargin = Math.round(density * 4);
        rule.setLayoutParams(lp);
        rule.setBackgroundColor(ForkThemeUtils.getTextColor());
        mContainer.addView(rule);
    }

    private void addKeyValue(float density, @NonNull String key, @NonNull String value) {
        LinearLayoutCompat row = new LinearLayoutCompat(this);
        row.setOrientation(LinearLayoutCompat.HORIZONTAL);
        int padH = Math.round(density * 20);
        row.setPadding(padH, Math.round(density * 2), padH, Math.round(density * 2));
        TextView k = plainText(13, 0xFF9A9A9A);
        k.setLayoutParams(new LinearLayoutCompat.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        k.setText(key);
        TextView v = plainText(13, ForkThemeUtils.getTextColor());
        v.setLayoutParams(new LinearLayoutCompat.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        v.setTextIsSelectable(true);
        v.setText(value);
        row.addView(k);
        row.addView(v);
        mContainer.addView(row);
    }

    private interface OnToggle {
        void onToggle(boolean checked);
    }

    /**
     * Controls are pills of roughly half the page, not full-width rows. The
     * empty gutter on the right is deliberate: with edge-to-edge rows there was
     * nowhere to start a scroll without landing on a switch, which is how 白い熊
     * toggled something by accident while trying to scroll.
     */
    /**
     * Pills go two to a row. Each is a little under half the page, so there is
     * comfortable whitespace between and around them — and, more to the point,
     * somewhere to start a scroll that is not a control. Edge-to-edge rows are
     * how 白い熊 toggled a switch while trying to scroll.
     */
    private void addPill(@NonNull View pill, float density) {
        pill.setBackgroundResource(R.drawable.bg_battery_pill);
        int gap = Math.round(density * 10);
        if (mPillRow == null || mPillRowCount >= 2) {
            mPillRow = new LinearLayoutCompat(this);
            mPillRow.setOrientation(LinearLayoutCompat.HORIZONTAL);
            LinearLayoutCompat.LayoutParams rowLp = new LinearLayoutCompat.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.leftMargin = Math.round(density * 12);
            rowLp.rightMargin = Math.round(density * 12);
            rowLp.topMargin = gap / 2;
            rowLp.bottomMargin = gap / 2;
            mPillRow.setLayoutParams(rowLp);
            mContainer.addView(mPillRow);
            mPillRowCount = 0;
        }
        // Equal weights, so two pills share the row and a lone pill still stops
        // at half width rather than stretching across the page.
        LinearLayoutCompat.LayoutParams lp = new LinearLayoutCompat.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = mPillRowCount == 0 ? 0 : gap;
        pill.setLayoutParams(lp);
        mPillRow.addView(pill);
        mPillRowCount++;
    }

    /** Starts a fresh pill row, so a section never continues another's row. */
    private void endPillRow() {
        mPillRow = null;
        mPillRowCount = 0;
    }

    private void addSwitchRow(float density, @NonNull String title, @Nullable String summary,
                              boolean checked, @NonNull OnToggle onToggle) {
        LinearLayoutCompat row = new LinearLayoutCompat(this);
        row.setOrientation(LinearLayoutCompat.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(density * 12);
        row.setPadding(pad, pad, pad, pad);

        LinearLayoutCompat texts = new LinearLayoutCompat(this);
        texts.setOrientation(LinearLayoutCompat.VERTICAL);
        texts.setLayoutParams(new LinearLayoutCompat.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = plainText(15, ForkThemeUtils.getTextColor());
        t.setText(title);
        texts.addView(t);
        if (summary != null) {
            TextView s = plainText(12, 0xFF9A9A9A);
            s.setText(summary);
            texts.addView(s);
        }
        row.addView(texts);

        MaterialSwitch sw = new MaterialSwitch(this);
        sw.setChecked(checked);
        sw.setOnClickListener(v -> onToggle.onToggle(sw.isChecked()));
        row.addView(sw);
        row.setOnClickListener(v -> sw.performClick());
        addPill(row, density);
    }

    private void addActionRow(float density, @NonNull String title, @Nullable String summary,
                              @NonNull View.OnClickListener onClick) {
        LinearLayoutCompat row = new LinearLayoutCompat(this);
        row.setOrientation(LinearLayoutCompat.VERTICAL);
        int pad = Math.round(density * 12);
        row.setPadding(pad, pad, pad, pad);
        TextView t = plainText(15, ForkThemeUtils.getTextColor());
        t.setText(title);
        row.addView(t);
        if (summary != null) {
            TextView s = plainText(12, 0xFF9A9A9A);
            s.setText(summary);
            row.addView(s);
        }
        row.setOnClickListener(onClick);
        addPill(row, density);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.battery_detail_actions, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem freeze = menu.findItem(R.id.action_battery_detail_freeze);
        if (freeze != null) {
            freeze.setIcon(mFrozen ? R.drawable.ic_snowflake_24dp : R.drawable.ic_snowflake_outline_24dp);
            freeze.setTitle(mFrozen ? R.string.unfreeze : R.string.freeze);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.action_battery_detail_freeze) {
            toggleFreeze();
            return true;
        } else if (id == R.id.action_battery_detail_kill) {
            forceStop();
            return true;
        } else if (id == R.id.action_battery_detail_app_info) {
            openAppDetails();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        mExecutor.shutdownNow();
        super.onDestroy();
    }
}
