// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main.lens;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.compat.AppOpsManagerCompat;
import io.github.muntashirakon.AppManager.db.utils.AppDb;
import io.github.muntashirakon.AppManager.db.entity.App;
import io.github.muntashirakon.AppManager.details.struct.AppDetailsSnoopingItem;
import io.github.muntashirakon.AppManager.main.ApplicationItem;
import io.github.muntashirakon.AppManager.main.PillSpan;
import io.github.muntashirakon.AppManager.snooping.SnoopingResolver;
import io.github.muntashirakon.AppManager.snooping.SnoopingState;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;

/**
 * Fork (白い熊, +166): 盗み見一覧 as a lens — every app that can snoop on you right now, ranked by how
 * many ways it can.
 *
 * <p>It replaces {@code screens/SnoopingSource} finding for finding, including both {@code PillSpan}
 * pills, and gains the whole main list around it: search, filters, profile pills, the app pane, the
 * batch pane.
 *
 * <p><b>This is the expensive lens, and the caching is the design.</b> Membership is not something
 * the app database knows: it is the answer of a {@link SnoopingResolver} pass per app, each of which
 * reads app-ops and walks a manifest. Left uncached that would run again on every keystroke in the
 * search box, because a keystroke re-runs the whole pipeline. So the verdict is cached per package
 * against its {@code lastUpdateTime} — an update can change what an app is able to do, and changing
 * that timestamp is precisely how an update announces itself, so the cache expires by itself exactly
 * when it should. Pull-to-refresh clears it outright.
 *
 * <p>The first pass is still seconds on a phone with five hundred apps, which is what the screen it
 * replaces always cost; entering the lens shows the progress indicator for that reason.
 */
public class SnoopingLens implements MainLens {
    public static final String ID = "snooping";

    public static final int SORT_ALLOWED = 0;
    public static final int SORT_NAME = 1;
    public static final int SORT_TRACKERS = 2;

    /** The Snooping page's own "allowed" pair, so one finding is read the same way everywhere. */
    private static final int ALLOWED_PILL_BG = 0xFF6E0B14;
    private static final int PILL_INK = 0xFFFFD9DC;
    /** The tracker red — the loudest thing on the row, by 白い熊's rule. */
    private static final int TRACKER_PILL_BG = 0xFFFF0028;

    private static final class Verdict {
        long forUpdateTime;
        int allowed;
        int narrowed;
        int trackers;
        @NonNull
        List<CharSequence> names = Collections.emptyList();
    }

    private final Map<String, Verdict> mVerdicts = new ConcurrentHashMap<>();

    @NonNull
    @Override
    public String id() {
        return ID;
    }

    @Override
    public int titleRes() {
        return R.string.screen_snooping;
    }

    @Override
    public boolean includes(@NonNull ApplicationItem item) {
        Verdict verdict = mVerdicts.get(item.packageName);
        if (verdict == null) {
            return false;
        }
        // A page of apps that can do nothing invasive is a page nobody reads, and the ones that
        // matter would be buried in it.
        return verdict.allowed > 0 || verdict.narrowed > 0;
    }

    @WorkerThread
    @Override
    public void prepare(@NonNull Context context, @NonNull List<ApplicationItem> items) {
        PackageManager pm = context.getPackageManager();
        // Which apps still need resolving. Everything else is answered from the cache, which is what
        // makes a keystroke in the search box free rather than a full re-resolve.
        List<ApplicationItem> pending = new ArrayList<>();
        for (ApplicationItem item : items) {
            Verdict known = mVerdicts.get(item.packageName);
            if (known == null || known.forUpdateTime != item.lastUpdateTime) {
                pending.add(item);
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        // The tracker count comes from the app database rather than a scan of our own: it is filled
        // by App.fromPackageInfo with every component flag set, so it is the same number the main
        // list sorts by, and this lens already runs a resolver pass per app.
        Map<String, Integer> trackerCounts = new HashMap<>();
        try {
            for (App app : new AppDb().getAllInstalledApplications()) {
                Integer known = trackerCounts.get(app.packageName);
                if (known == null || app.trackerCount > known) {
                    trackerCounts.put(app.packageName, app.trackerCount);
                }
            }
        } catch (Throwable ignore) {
        }
        // One bulk query rather than one per app. GET_SERVICES is required, not optional:
        // SnoopingReachability judges the service-gated capabilities from it, and a null services
        // array is indistinguishable from "the caller did not ask" (+11).
        Map<String, PackageInfo> byName = new HashMap<>();
        try {
            for (PackageInfo packageInfo : pm.getInstalledPackages(
                    PackageManager.GET_PERMISSIONS | PackageManager.GET_SERVICES)) {
                if (packageInfo != null && packageInfo.applicationInfo != null) {
                    byName.put(packageInfo.packageName, packageInfo);
                }
            }
        } catch (Throwable th) {
            return;
        }
        AppOpsManagerCompat appOpsManager = new AppOpsManagerCompat();
        for (ApplicationItem item : pending) {
            // Interruptible: a keystroke cancels the pass and the next one resumes from the cache
            // rather than starting over.
            if (ThreadUtils.isInterrupted()) {
                return;
            }
            PackageInfo packageInfo = byName.get(item.packageName);
            Verdict verdict = new Verdict();
            verdict.forUpdateTime = item.lastUpdateTime;
            Integer trackers = trackerCounts.get(item.packageName);
            verdict.trackers = trackers == null ? 0 : trackers;
            if (packageInfo == null) {
                // Not installed for this user, or unreadable: nothing can be claimed about it.
                mVerdicts.put(item.packageName, verdict);
                continue;
            }
            List<AppDetailsSnoopingItem> capabilities;
            try {
                capabilities = SnoopingResolver.resolve(packageInfo, item.getUserId(),
                        appOpsManager, false);
            } catch (Throwable th) {
                mVerdicts.put(item.packageName, verdict);
                continue;
            }
            List<CharSequence> names = new ArrayList<>();
            for (AppDetailsSnoopingItem capability : capabilities) {
                int state;
                try {
                    state = capability.getState();
                } catch (Throwable th) {
                    continue;
                }
                if (state == SnoopingState.FOREGROUND) {
                    ++verdict.narrowed;
                } else if (SnoopingState.isAllowed(state)) {
                    ++verdict.allowed;
                    if (names.size() < 4) {
                        names.add(context.getString(capability.capability.entry.labelRes));
                    }
                }
            }
            verdict.names = names;
            mVerdicts.put(item.packageName, verdict);
        }
    }

    @NonNull
    @Override
    public List<CharSequence> rightLines(@NonNull Context context, @NonNull ApplicationItem item) {
        Verdict verdict = mVerdicts.get(item.packageName);
        if (verdict == null) {
            return Collections.emptyList();
        }
        float density = context.getResources().getDisplayMetrics().density;
        List<CharSequence> lines = new ArrayList<>(4);
        // The two findings are pills, not coloured text (+149): the page's red is nearly unreadable
        // as 12sp text on black, and filled it says the same thing legibly.
        if (verdict.allowed > 0) {
            lines.add(PillSpan.pill(context.getString(R.string.screen_snooping_allowed, verdict.allowed),
                    ALLOWED_PILL_BG, PILL_INK, 15f * density, density));
        }
        if (verdict.narrowed > 0) {
            lines.add(context.getString(R.string.screen_snooping_narrowed, verdict.narrowed));
        }
        // Below the capability lines, and the loudest thing on the row: the trackers are the finding
        // you cannot reach any other way.
        if (verdict.trackers > 0) {
            lines.add(PillSpan.pill(context.getString(R.string.screen_trackers_count, verdict.trackers),
                    TRACKER_PILL_BG, PILL_INK, 16f * density, density));
        }
        if (!verdict.names.isEmpty()) {
            lines.add(TextUtils.join(" · ", verdict.names));
        }
        return lines;
    }

    @Override
    public int accent(@NonNull ApplicationItem item) {
        // The headline is already a pill carrying its own colours; an accent would fight it.
        return 0;
    }

    @NonNull
    @Override
    public List<CharSequence> sortLabels(@NonNull Context context) {
        return Arrays.asList(
                context.getString(R.string.screen_sort_allowed_count),
                context.getString(R.string.screen_sort_name),
                context.getString(R.string.screen_sort_tracker_count));
    }

    @Override
    public void applySort(@NonNull List<ApplicationItem> rows, int lensSort) {
        switch (lensSort) {
            case SORT_NAME:
                // The list's own label order already holds underneath.
                break;
            case SORT_TRACKERS:
                Collections.sort(rows, (a, b) -> Integer.compare(trackers(b), trackers(a)));
                break;
            case SORT_ALLOWED:
            default:
                Collections.sort(rows, (a, b) -> {
                    int byAllowed = Integer.compare(allowed(b), allowed(a));
                    // Ties broken by how many are merely narrowed, as the screen did.
                    return byAllowed != 0 ? byAllowed : Integer.compare(narrowed(b), narrowed(a));
                });
                break;
        }
    }

    private int allowed(@NonNull ApplicationItem item) {
        Verdict verdict = mVerdicts.get(item.packageName);
        return verdict != null ? verdict.allowed : 0;
    }

    private int narrowed(@NonNull ApplicationItem item) {
        Verdict verdict = mVerdicts.get(item.packageName);
        return verdict != null ? verdict.narrowed : 0;
    }

    private int trackers(@NonNull ApplicationItem item) {
        Verdict verdict = mVerdicts.get(item.packageName);
        return verdict != null ? verdict.trackers : 0;
    }

    @Override
    public void invalidate() {
        mVerdicts.clear();
    }
}
