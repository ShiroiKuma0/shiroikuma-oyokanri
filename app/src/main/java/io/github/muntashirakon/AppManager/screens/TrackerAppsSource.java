// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.screens;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.UserHandleHidden;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.details.AppDetailsActivity;
import io.github.muntashirakon.AppManager.trackers.TrackerHit;
import io.github.muntashirakon.AppManager.trackers.TrackerScan;
import io.github.muntashirakon.AppManager.utils.FreezeUtils;
import io.github.muntashirakon.AppManager.utils.PackageUtils;

/**
 * Fork (白い熊, +146): every app on this phone carrying one particular tracker.
 *
 * <p>This is what a tracker pill leads to, and it is the answer to the question a name alone
 * raises — "how much of my phone is this one company inside?". A URL describing the tracker can
 * be looked up anywhere; which of <em>your</em> apps embed it cannot.
 *
 * <p>It is a {@link ScreenSource} rather than a screen of its own, so it inherits the sibling
 * screens' rows, colours, column picker, sort menu and separators for nothing — the +118 rule.
 * The pass is the expensive kind (a component scan per installed app), which is why it is only
 * ever entered deliberately.
 */
public class TrackerAppsSource implements ScreenSource {
    private static final int SORT_SEVERITY = 0;
    private static final int SORT_NAME = 1;
    private static final int SORT_COMPONENTS = 2;

    @NonNull
    private final String mTracker;

    public TrackerAppsSource(@NonNull String tracker) {
        mTracker = tracker;
    }

    @Override
    public int titleRes() {
        return R.string.trackers;
    }

    @NonNull
    @Override
    public CharSequence title(@NonNull Context context) {
        return mTracker;
    }

    @Override
    public int emptyTextRes() {
        return R.string.screen_tracker_empty;
    }

    @NonNull
    @Override
    public List<ScreenRow> load(@NonNull Context context) {
        List<ScreenRow> rows = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        int userId = UserHandleHidden.myUserId();
        Set<String> seen = new HashSet<>();
        List<PackageInfo> packages;
        try {
            packages = PackageUtils.getAllPackages(PackageManager.GET_ACTIVITIES
                    | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS
                    | PackageManager.GET_PROVIDERS, true);
        } catch (Throwable th) {
            return rows;
        }
        for (PackageInfo packageInfo : packages) {
            if (packageInfo == null || packageInfo.applicationInfo == null
                    || !seen.add(packageInfo.packageName)) {
                continue;
            }
            TrackerHit found = null;
            for (TrackerHit hit : TrackerScan.scan(context, packageInfo)) {
                if (hit.name.equalsIgnoreCase(mTracker)) {
                    found = hit;
                    break;
                }
            }
            if (found == null) {
                continue;
            }
            ApplicationInfo ai = packageInfo.applicationInfo;
            ScreenRow row = new ScreenRow(packageInfo.packageName, userId, ai.loadLabel(pm),
                    packageInfo.packageName, packageInfo.lastUpdateTime);
            row.info = ai;
            row.uid = ai.uid;
            row.frozen = FreezeUtils.isFrozen(ai);
            row.sortKey = found.rung();
            row.sortKey2 = found.components.size();
            row.accent = found.rung() == TrackerHit.RUNG_AUTONOMOUS ? TrackerHit.COLOR_AUTONOMOUS : 0;
            row.add(context.getString(rungRes(found.rung())));
            if (!found.components.isEmpty()) {
                row.add(context.getString(R.string.screen_tracker_components, found.components.size()));
            } else {
                row.add(context.getString(R.string.tracker_code_only));
            }
            rows.add(row);
        }
        applySort(rows, SORT_SEVERITY);
        return rows;
    }

    private static int rungRes(int rung) {
        switch (rung) {
            case TrackerHit.RUNG_AUTONOMOUS:
                return R.string.tracker_rung_autonomous;
            case TrackerHit.RUNG_WITH_APP:
                return R.string.tracker_rung_with_app;
            default:
                return R.string.tracker_rung_passive;
        }
    }

    @NonNull
    @Override
    public List<CharSequence> sortLabels(@NonNull Context context) {
        return Arrays.asList(
                context.getString(R.string.screen_sort_tracker_severity),
                context.getString(R.string.screen_sort_name),
                context.getString(R.string.screen_sort_tracker_components));
    }

    @Override
    public void applySort(@NonNull List<ScreenRow> rows, int sortMode) {
        if (sortMode == SORT_NAME) {
            Collections.sort(rows, (a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
        } else if (sortMode == SORT_COMPONENTS) {
            Collections.sort(rows, (a, b) -> Long.compare(b.sortKey2, a.sortKey2));
        } else {
            Collections.sort(rows, (a, b) -> {
                int bySeverity = Long.compare(b.sortKey, a.sortKey);
                return bySeverity != 0 ? bySeverity : Long.compare(b.sortKey2, a.sortKey2);
            });
        }
    }

    @Override
    public void onRowClicked(@NonNull AppCompatActivity activity, @NonNull ScreenRow row) {
        activity.startActivity(AppDetailsActivity.getIntent(activity, row.packageName, row.userId,
                AppDetailsActivity.TAB_SNOOPING));
    }
}
