// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main.lens;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.appdata.AppDataContract;
import io.github.muntashirakon.AppManager.appdata.AppDataSelection;
import io.github.muntashirakon.AppManager.db.utils.AppDb;
import io.github.muntashirakon.AppManager.db.entity.Backup;
import io.github.muntashirakon.AppManager.main.ApplicationItem;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;

/**
 * Fork (白い熊, +166): 仲間 as a lens — every app on the phone that implements the app-data contract,
 * with what it says it speaks and whether its data has ever actually been carried.
 *
 * <p>Support is read from the manifest, so <b>nothing is woken</b> and a frozen app still appears —
 * which is the point, since a frozen sister app is exactly the one you would want to check on.
 *
 * <p>Membership cannot be answered from {@link ApplicationItem}: it needs {@code ApplicationInfo}
 * with {@code GET_META_DATA}, which the list does not carry. So {@link #prepare} does one bulk query
 * and caches the verdict, and {@link #includes} reads the cache. That is also why the query keeps
 * {@code MATCH_UNINSTALLED_PACKAGES | MATCH_DISABLED_COMPONENTS}: the contract's own discovery uses
 * them for the same reason.
 */
public class SisterAppsLens implements MainLens {
    public static final String ID = "sister";

    public static final int SORT_NAME = 0;
    public static final int SORT_BACKUP_DATE = 1;
    public static final int SORT_FORMAT = 2;

    /** A contract we do not speak is the single most useful thing this lens can tell you. */
    private static final int ACCENT_UNUSABLE = 0xFFFF0028;

    private static final class Info {
        boolean supported;
        boolean usable;
        int contract;
        int format;
        int minFormat;
        long withDataTime;
        boolean backedUpWithoutData;
        boolean everBackedUp;
        int chosen = -1;
        int seen;
        /** Fork (白い熊): no manifest was readable — the backup is what identifies it. */
        boolean knownFromBackupOnly;
    }

    private final Map<String, Info> mInfo = new ConcurrentHashMap<>();

    @NonNull
    @Override
    public String id() {
        return ID;
    }

    @Override
    public int titleRes() {
        return R.string.screen_sister;
    }

    /**
     * Fork (白い熊): 仲間 narrows, it never draws. See {@link MainLens#filterOnly()}.
     */
    @Override
    public boolean filterOnly() {
        return true;
    }

    @Override
    public boolean includes(@NonNull ApplicationItem item) {
        Info info = mInfo.get(item.packageName);
        return info != null && info.supported;
    }

    @WorkerThread
    @Override
    public void prepare(@NonNull Context context, @NonNull List<ApplicationItem> items) {
        PackageManager pm = context.getPackageManager();
        Map<String, ApplicationInfo> byName = new HashMap<>();
        try {
            for (ApplicationInfo app : pm.getInstalledApplications(PackageManager.GET_META_DATA
                    | PackageManager.MATCH_UNINSTALLED_PACKAGES
                    | PackageManager.MATCH_DISABLED_COMPONENTS)) {
                if (app != null) {
                    byName.put(app.packageName, app);
                }
            }
        } catch (Throwable th) {
            return;
        }
        // One pass over the backup table rather than a query per app.
        Map<String, Backup> latestWithAppData = new HashMap<>();
        Map<String, Backup> latestAny = new HashMap<>();
        try {
            for (Backup backup : new AppDb().getAllBackups()) {
                if (backup == null || backup.packageName == null) continue;
                Backup any = latestAny.get(backup.packageName);
                if (any == null || backup.backupTime > any.backupTime) {
                    latestAny.put(backup.packageName, backup);
                }
                if (backup.getFlags().backupAppData()) {
                    Backup withData = latestWithAppData.get(backup.packageName);
                    if (withData == null || backup.backupTime > withData.backupTime) {
                        latestWithAppData.put(backup.packageName, backup);
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        mInfo.clear();
        for (ApplicationItem item : items) {
            if (ThreadUtils.isInterrupted()) {
                return;
            }
            ApplicationInfo app = byName.get(item.packageName);
            AppDataContract.Support support = app != null
                    ? AppDataContract.fromMetaData(app.metaData) : null;
            Backup backupWithData = latestWithAppData.get(item.packageName);
            // Fork (白い熊): a backup that carried app-supplied data is proof of a sister app, and
            // on the phone this contract exists for -- a freshly wiped one -- it is the ONLY proof
            // available. The manifest cannot be read for an app that is not installed yet, so a
            // metadata-only rule hid exactly the apps the migration is about. Either witness is
            // enough; the manifest is preferred where both exist, because it is the current truth.
            if (support == null && backupWithData == null) {
                continue;
            }
            Info info = new Info();
            info.supported = true;
            info.usable = support != null && support.isUsable();
            info.contract = support != null ? support.contract : 0;
            info.format = support != null ? support.format : 0;
            info.minFormat = support != null ? support.minFormat : 0;
            // Known only from its backup: say so rather than claiming a contract we never read.
            info.knownFromBackupOnly = support == null;
            Backup withData = backupWithData;
            if (withData != null) {
                info.withDataTime = withData.backupTime;
            } else if (latestAny.get(item.packageName) != null) {
                // Backed up, but without its own data — a distinction worth drawing, because the
                // backup looks complete in every other list.
                info.backedUpWithoutData = true;
            }
            info.everBackedUp = latestAny.get(item.packageName) != null;
            AppDataSelection.Stored stored = AppDataSelection.get(context, item.packageName);
            if (stored != null) {
                info.chosen = stored.chosen.size();
                info.seen = stored.seen.size();
            }
            mInfo.put(item.packageName, info);
        }
    }

    @NonNull
    @Override
    public List<CharSequence> rightLines(@NonNull Context context, @NonNull ApplicationItem item) {
        Info info = mInfo.get(item.packageName);
        if (info == null) {
            return Collections.emptyList();
        }
        List<CharSequence> lines = new ArrayList<>(3);
        // Line 1 — what it says it speaks.
        String contractLine = context.getString(R.string.screen_sister_contract,
                info.contract, info.format, info.minFormat);
        if (!info.usable) {
            contractLine = contractLine + " · " + context.getString(R.string.screen_sister_unusable);
        }
        lines.add(contractLine);
        // Line 2 — whether a backup actually carried its data, and when.
        if (info.withDataTime > 0) {
            DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
            lines.add(context.getString(R.string.screen_sister_has_data,
                    dateFormat.format(new Date(info.withDataTime))));
        } else if (info.backedUpWithoutData) {
            lines.add(context.getString(R.string.screen_sister_backup_without_data));
        } else {
            lines.add(context.getString(R.string.screen_sister_never_backed_up));
        }
        // Line 3 — the category choice, or the app's own default when never customised.
        if (info.chosen < 0) {
            lines.add(context.getString(R.string.screen_sister_default_categories));
        } else {
            lines.add(context.getString(R.string.screen_sister_chosen_categories,
                    info.chosen, info.seen));
        }
        return lines;
    }

    @Override
    public int accent(@NonNull ApplicationItem item) {
        Info info = mInfo.get(item.packageName);
        return info != null && !info.usable ? ACCENT_UNUSABLE : 0;
    }

    @NonNull
    private long withData(@NonNull ApplicationItem item) {
        Info info = mInfo.get(item.packageName);
        return info != null ? info.withDataTime : 0;
    }

    /** Fork (白い熊): the app-data format, read by the list's own sort. */
    public int formatOf(@NonNull String packageName) {
        Info info = mInfo.get(packageName);
        return info != null ? info.format : 0;
    }

    private int format(@NonNull ApplicationItem item) {
        Info info = mInfo.get(item.packageName);
        return info != null ? info.format : 0;
    }

    @Override
    public void invalidate() {
        mInfo.clear();
    }

    /** Unused today; kept so a future line can say whether the app was ever backed up at all. */
    @SuppressWarnings("unused")
    private boolean everBackedUp(@Nullable Info info) {
        return info != null && info.everBackedUp;
    }
}
