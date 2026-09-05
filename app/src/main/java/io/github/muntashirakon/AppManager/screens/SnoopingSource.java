// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.screens;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.UserHandleHidden;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.compat.AppOpsManagerCompat;
import io.github.muntashirakon.AppManager.details.AppDetailsActivity;
import io.github.muntashirakon.AppManager.details.struct.AppDetailsSnoopingItem;
import io.github.muntashirakon.AppManager.snooping.SnoopingResolver;
import io.github.muntashirakon.AppManager.snooping.SnoopingState;

/**
 * Fork (白い熊, +118): 盗み見一覧 — who can watch you right now.
 *
 * <p>The fork's own centre of gravity had no list view: 盗み見 could only ever be read one app at
 * a time, so "which apps hold a microphone they never asked for" was a question you answered by
 * opening two hundred pages. This is that page.
 *
 * <p><b>It asks {@link SnoopingResolver}</b> — the same resolver the per-app tab and the enforcer
 * use — rather than counting permissions itself. Three copies of "what counts as snooping" would
 * disagree within a build, and the resolver is where the tiers, the reachability rules and the
 * levers already live.
 *
 * <p><b>This is the expensive screen</b>, and honestly so: one resolver pass per installed app,
 * each of which reads app-ops and walks a manifest. It runs on a background thread, is not
 * refreshed on resume, and is re-read only on pull-to-refresh.
 */
public class SnoopingSource implements ScreenSource {
    private static final int SORT_ALLOWED = 0;
    private static final int SORT_NAME = 1;

    @Override
    public int titleRes() {
        return R.string.screen_snooping;
    }

    @Override
    public int emptyTextRes() {
        return R.string.screen_snooping_empty;
    }

    @NonNull
    @Override
    public List<ScreenRow> load(@NonNull Context context) {
        List<ScreenRow> rows = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        int userId = UserHandleHidden.myUserId();
        AppOpsManagerCompat appOpsManager = new AppOpsManagerCompat();
        List<PackageInfo> packages;
        try {
            // GET_SERVICES is required, not optional: SnoopingReachability judges the
            // service-gated capabilities from it, and a null services array is indistinguishable
            // from "the caller did not ask" (+11).
            packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS
                    | PackageManager.GET_SERVICES);
        } catch (Throwable th) {
            return rows;
        }
        for (PackageInfo packageInfo : packages) {
            if (packageInfo == null || packageInfo.applicationInfo == null) {
                continue;
            }
            List<AppDetailsSnoopingItem> items;
            try {
                items = SnoopingResolver.resolve(packageInfo, userId, appOpsManager, false);
            } catch (Throwable th) {
                continue;
            }
            int allowed = 0;
            int narrowed = 0;
            List<CharSequence> names = new ArrayList<>();
            for (AppDetailsSnoopingItem item : items) {
                int state;
                try {
                    state = item.getState();
                } catch (Throwable th) {
                    continue;
                }
                if (state == SnoopingState.FOREGROUND) {
                    ++narrowed;
                } else if (SnoopingState.isAllowed(state)) {
                    ++allowed;
                    if (names.size() < 4) {
                        names.add(context.getString(item.capability.entry.labelRes));
                    }
                }
            }
            if (allowed == 0 && narrowed == 0) {
                // Nothing to say about this app. A page of apps that can do nothing invasive is
                // a page nobody reads; the ones that matter would be buried in it.
                continue;
            }
            ApplicationInfo ai = packageInfo.applicationInfo;
            ScreenRow row = new ScreenRow(packageInfo.packageName, userId, ai.loadLabel(pm),
                    packageInfo.packageName, packageInfo.lastUpdateTime);
            row.info = ai;
            row.uid = ai.uid;
            row.frozen = io.github.muntashirakon.AppManager.utils.FreezeUtils.isFrozen(ai);
            row.sortKey = allowed;
            row.sortKey2 = narrowed;
            row.accent = allowed > 0 ? 0xFFFF0028 : 0;
            row.add(context.getString(R.string.screen_snooping_allowed, allowed));
            if (narrowed > 0) {
                row.add(context.getString(R.string.screen_snooping_narrowed, narrowed));
            }
            if (!names.isEmpty()) {
                row.add(TextUtils.join(" · ", names));
            }
            rows.add(row);
        }
        applySort(rows, SORT_ALLOWED);
        return rows;
    }

    @NonNull
    @Override
    public List<CharSequence> sortLabels(@NonNull Context context) {
        return Arrays.asList(
                context.getString(R.string.screen_sort_allowed_count),
                context.getString(R.string.screen_sort_name));
    }

    @Override
    public void applySort(@NonNull List<ScreenRow> rows, int sortMode) {
        if (sortMode == SORT_NAME) {
            Collections.sort(rows, (a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
        } else {
            Collections.sort(rows, (a, b) -> {
                int byAllowed = Long.compare(b.sortKey, a.sortKey);
                return byAllowed != 0 ? byAllowed : Long.compare(b.sortKey2, a.sortKey2);
            });
        }
    }

    @Override
    public void onRowClicked(@NonNull AppCompatActivity activity, @NonNull ScreenRow row) {
        activity.startActivity(AppDetailsActivity.getIntent(activity, row.packageName, row.userId,
                AppDetailsActivity.TAB_SNOOPING));
    }
}
