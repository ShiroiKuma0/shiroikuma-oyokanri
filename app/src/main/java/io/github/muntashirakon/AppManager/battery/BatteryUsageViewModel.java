// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.battery;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.db.AppsDb;
import io.github.muntashirakon.AppManager.db.dao.BatterySampleDao;
import io.github.muntashirakon.AppManager.db.entity.BatterySample;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.main.ApplicationItem;
import io.github.muntashirakon.AppManager.main.MainViewModel;

/**
 * Fork: ranks the stored battery history over a chosen window.
 *
 * <p>Every figure here comes from stored <b>deltas</b>, so a window query is a
 * plain sum and needs no awareness of charge cycles. Nothing is presented as
 * mAh — see {@link BatterySampleDao.BatteryAggregate#impactScore()} for why.
 */
public class BatteryUsageViewModel extends AndroidViewModel {
    public static final String TAG = BatteryUsageViewModel.class.getSimpleName();

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<List<Row>> mRows = new MutableLiveData<>();
    private final MutableLiveData<Summary> mSummary = new MutableLiveData<>();
    private final MutableLiveData<Header> mHeader = new MutableLiveData<>();

    private final List<Row> mAllRows = new ArrayList<>();
    @Nullable
    private String mQuery;
    private int mWindowHours;
    private int mSort;

    public BatteryUsageViewModel(@NonNull Application application) {
        super(application);
        mWindowHours = BatteryPrefs.getWindowHours(application);
        mSort = BatteryPrefs.getSort(application);
    }

    /** One ranked app. */
    public static class Row {
        public int uid;
        @Nullable
        public String packageName;
        @NonNull
        public String label = "";
        @Nullable
        public ApplicationInfo applicationInfo;
        @NonNull
        public BatterySampleDao.BatteryAggregate agg;
        /** Share of the window's total impact score, 0–1. */
        public float share;
        /** Disabled, suspended or hidden — the same notion the main list uses. */
        public boolean frozen;
        /**
         * The main-list item and profile tags, resolved <b>here on the worker
         * thread</b>. Both cost a database read and a profile-file scan; doing
         * them at bind time would put that on the main thread once per row per
         * scroll, which janks visibly on a long list.
         */
        @Nullable
        public ApplicationItem appItem;
        @NonNull
        public List<String> profileTags = Collections.emptyList();

        Row(@NonNull BatterySampleDao.BatteryAggregate agg) {
            this.agg = agg;
        }
    }

    /** Device-level context for the same window. */
    public static class Summary {
        public long coveredMs;
        public long screenOnMs;
        public long deepIdleMs;
        public long lightIdleMs;
        public int levelDrop = -1;
        public int buckets;
        public long oldestTs;
        public boolean sampling;
        public boolean directAccess;
        /** Whether this device's power model produced usable mAh in the window. */
        public boolean powerModelUsable;
    }

    public LiveData<List<Row>> getRows() {
        return mRows;
    }

    public LiveData<Summary> getSummary() {
        return mSummary;
    }

    public LiveData<Header> getHeader() {
        return mHeader;
    }

    /** Device-level chart plus the last two hours' worst offenders. */
    public static class Header {
        public final List<BatteryLevelView.Point> points = new ArrayList<>();
        public final List<BatteryTopDrainersView.Slice> drainers = new ArrayList<>();
        /** Battery percentage points the device actually lost in the drainer window. */
        public float dropPercent;
        public int windowMinutes;
    }

    /**
     * The header spans the whole retained history, not the list's window: the
     * chart is meant to be dragged backwards, so it must be given everything
     * and opened on the last day.
     */
    private void loadHeader(@NonNull BatterySampleDao dao) {
        Header header = new Header();
        long now = System.currentTimeMillis();
        try {
            for (BatterySample s : dao.deviceSamples(0)) {
                header.points.add(new BatteryLevelView.Point(s.ts, s.batteryLevel, s.voltageMv, s.charging));
            }
            int drainerMinutes = BatteryPrefs.getDrainerWindowMinutes(getApplication());
            header.windowMinutes = drainerMinutes;
            long from = now - drainerMinutes * 60_000L;
            // How much battery the device really lost over the window. Only
            // decreases between two non-charging readings count, so a charge in
            // the middle of the window cannot cancel out the discharge around it.
            float drop = 0;
            BatterySample prev = null;
            for (BatterySample d : dao.deviceSamples(from)) {
                if (prev != null && !prev.charging && !d.charging
                        && prev.batteryLevel >= 0 && d.batteryLevel >= 0) {
                    drop += Math.max(0, prev.batteryLevel - d.batteryLevel);
                }
                prev = d;
            }
            header.dropPercent = drop;
            List<BatterySampleDao.BatteryAggregate> recent = dao.aggregateRange(from, now);
            Collections.sort(recent, (a, b) -> Long.compare(b.impactScore(), a.impactScore()));
            long total = 0;
            for (BatterySampleDao.BatteryAggregate a : recent) total += a.impactScore();
            PackageManager pm = getApplication().getPackageManager();
            for (BatterySampleDao.BatteryAggregate a : recent) {
                // The panel scrolls horizontally, so the cap is generous.
                if (header.drainers.size() >= 12 || a.impactScore() <= 0) break;
                float share = total > 0 ? (float) a.impactScore() / total : 0f;
                // Share of the measured impact × the drop the device recorded.
                // An estimate, and stated as one: the drop also includes screen
                // and other costs no uid owns, so this apportions the whole loss
                // across the apps we can measure.
                header.drainers.add(new BatteryTopDrainersView.Slice(
                        resolveLabel(pm, resolveInfo(pm, a.packageName), a.packageName, a.uid),
                        share, share * drop, a.uid, a.packageName));
            }
        } catch (Throwable th) {
            Log.e(TAG, "Could not build battery header", th);
        }
        mHeader.postValue(header);
    }

    public int getWindowHours() {
        return mWindowHours;
    }

    public int getSort() {
        return mSort;
    }

    public boolean isScreenOffOnly() {
        return BatteryPrefs.isScreenOffOnly(getApplication());
    }

    public void setScreenOffOnly(boolean screenOffOnly) {
        BatteryPrefs.setScreenOffOnly(getApplication(), screenOffOnly);
        load();
    }

    public void setWindowHours(int hours) {
        mWindowHours = hours;
        BatteryPrefs.setWindowHours(getApplication(), hours);
        load();
    }

    public void setSort(int sort) {
        mSort = sort;
        BatteryPrefs.setSort(getApplication(), sort);
        // Re-sorting is cheap and needs no re-query.
        mExecutor.submit(() -> {
            sortRows(mAllRows);
            publish();
        });
    }

    public void setQuery(@Nullable String query) {
        mQuery = query == null || query.trim().isEmpty() ? null : query.trim().toLowerCase(Locale.ROOT);
        publish();
    }

    /** Takes one reading right now, then reloads — the "Sample now" action. */
    public void sampleNow() {
        mExecutor.submit(() -> {
            try {
                BatterySampler.sample(getApplication());
            } catch (Throwable th) {
                Log.e(TAG, "Manual sample failed", th);
            }
            loadBlocking();
        });
    }

    public void load() {
        mExecutor.submit(this::loadBlocking);
    }

    private void loadBlocking() {
        try {
            long since = System.currentTimeMillis() - mWindowHours * 3_600_000L;
            BatterySampleDao dao = AppsDb.getInstance().batterySampleDao();
            boolean screenOffOnly = BatteryPrefs.isScreenOffOnly(getApplication());
            List<BatterySampleDao.BatteryAggregate> aggregates = screenOffOnly
                    ? dao.aggregateScreenOff(since) : dao.aggregate(since);
            PackageManager pm = getApplication().getPackageManager();

            List<Row> rows = new ArrayList<>(aggregates.size());
            long totalImpact = 0;
            for (BatterySampleDao.BatteryAggregate agg : aggregates) {
                Row row = new Row(agg);
                row.uid = agg.uid;
                row.packageName = agg.packageName;
                row.applicationInfo = resolveInfo(pm, agg.packageName);
                row.label = resolveLabel(pm, row.applicationInfo, agg.packageName, agg.uid);
                row.frozen = BatteryControls.isFrozen(row.applicationInfo);
                if (agg.packageName != null) {
                    row.appItem = MainViewModel.buildApplicationItem(getApplication(),
                            agg.packageName, AppsDb.getInstance().appDao().getAll(agg.packageName));
                    row.profileTags = ProfileTags.forPackage(agg.packageName);
                }
                totalImpact += agg.impactScore();
                rows.add(row);
            }
            for (Row row : rows) {
                row.share = totalImpact > 0 ? (float) row.agg.impactScore() / totalImpact : 0f;
            }
            sortRows(rows);

            loadHeader(dao);
            Summary summary = buildSummary(dao, since);
            synchronized (mAllRows) {
                mAllRows.clear();
                mAllRows.addAll(rows);
            }
            mSummary.postValue(summary);
            publish();
        } catch (Throwable th) {
            Log.e(TAG, "Failed to load battery history", th);
            mRows.postValue(Collections.emptyList());
        }
    }

    @NonNull
    private Summary buildSummary(@NonNull BatterySampleDao dao, long since) {
        Summary s = new Summary();
        s.sampling = BatteryPrefs.isEnabled(getApplication());
        s.directAccess = BatteryStatsReader.hasDirectAccess();
        List<BatterySample> device = dao.deviceSamples(since);
        s.buckets = device.size();
        int firstLevel = -1;
        int lastLevel = -1;
        for (BatterySample sample : device) {
            s.coveredMs += sample.duration;
            s.screenOnMs += sample.screenOnMs;
            s.deepIdleMs += sample.deepIdleMs;
            s.lightIdleMs += sample.lightIdleMs;
            if (sample.batteryLevel >= 0) {
                if (firstLevel < 0) firstLevel = sample.batteryLevel;
                lastLevel = sample.batteryLevel;
            }
        }
        if (firstLevel >= 0 && lastLevel >= 0 && firstLevel >= lastLevel) {
            s.levelDrop = firstLevel - lastLevel;
        }
        Long oldest = dao.oldestTimestamp();
        s.oldestTs = oldest != null ? oldest : 0;
        Integer usable = dao.powerModelUsable(since);
        s.powerModelUsable = usable != null && usable != 0;
        return s;
    }

    public boolean isPowerModelUsable() {
        Summary s = mSummary.getValue();
        return s != null && s.powerModelUsable;
    }

    /**
     * Number of device-level buckets in the window. The device row's profile
     * capacity is summed like every other extra, so dividing by this recovers
     * the per-bucket value it actually was.
     */
    public int deviceBucketCount() {
        Summary s = mSummary.getValue();
        return s != null ? s.buckets : 0;
    }

    private void sortRows(@NonNull List<Row> rows) {
        Collections.sort(rows, (a, b) -> Long.compare(metric(b), metric(a)));
    }

    private long metric(@NonNull Row row) {
        switch (mSort) {
            case BatteryPrefs.SORT_PACKETS: return row.agg.totalPackets();
            case BatteryPrefs.SORT_BYTES: return row.agg.totalBytes();
            case BatteryPrefs.SORT_WAKELOCK: return row.agg.wakelockMs;
            case BatteryPrefs.SORT_CPU: return row.agg.cpuMs;
            // Scaled so sub-mAh differences still order correctly.
            case BatteryPrefs.SORT_MAH: return Math.round(row.agg.powerMah * 1000);
            default: return row.agg.impactScore();
        }
    }

    /** Every extra counter this device recorded for one app over the window. */
    @NonNull
    public Map<String, Double> extrasFor(int uid) {
        try {
            long since = System.currentTimeMillis() - mWindowHours * 3_600_000L;
            return BatterySampler.mergeExtras(AppsDb.getInstance().batterySampleDao()
                    .extrasForUid(uid, since));
        } catch (Throwable th) {
            return Collections.emptyMap();
        }
    }

    private void publish() {
        List<Row> snapshot;
        synchronized (mAllRows) {
            snapshot = new ArrayList<>(mAllRows);
        }
        String query = mQuery;
        if (query == null) {
            mRows.postValue(snapshot);
            return;
        }
        List<Row> filtered = new ArrayList<>();
        for (Row row : snapshot) {
            if (row.label.toLowerCase(Locale.ROOT).contains(query)
                    || (row.packageName != null && row.packageName.toLowerCase(Locale.ROOT).contains(query))) {
                filtered.add(row);
            }
        }
        mRows.postValue(filtered);
    }

    @NonNull
    private static String resolveLabel(@NonNull PackageManager pm, @Nullable ApplicationInfo info,
                                       @Nullable String packageName, int uid) {
        if (info != null) {
            try {
                return info.loadLabel(pm).toString();
            } catch (Throwable ignore) {
            }
        }
        if (packageName != null) return packageName;
        // Kernel (uid 0) and the system server have no package; the uid is the
        // only honest name for them and hiding them would hide real drain.
        if (uid == 0) return "kernel";
        return "uid " + uid;
    }

    /**
     * <b>Frozen apps need the match flags.</b> A package frozen by <i>hiding</i>
     * is invisible to a plain {@code getApplicationInfo}, so without
     * MATCH_UNINSTALLED_PACKAGES the apps you froze for draining would lose
     * their name and icon here and show as a bare package string — the exact
     * rows you most want to recognise. MATCH_DISABLED_COMPONENTS covers the
     * disable-based freeze methods.
     */
    @Nullable
    private static ApplicationInfo resolveInfo(@NonNull PackageManager pm, @Nullable String packageName) {
        if (packageName == null) return null;
        int flags = PackageManagerCompat.MATCH_UNINSTALLED_PACKAGES
                | PackageManager.MATCH_DISABLED_COMPONENTS;
        try {
            return pm.getApplicationInfo(packageName, flags);
        } catch (Throwable th) {
            try {
                return pm.getApplicationInfo(packageName, 0);
            } catch (Throwable th2) {
                return null;
            }
        }
    }

    @Override
    protected void onCleared() {
        mExecutor.shutdownNow();
        super.onCleared();
    }
}
