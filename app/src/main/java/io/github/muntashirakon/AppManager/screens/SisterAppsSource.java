// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.screens;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.UserHandleHidden;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.appdata.AppDataCategoryPicker;
import io.github.muntashirakon.AppManager.appdata.AppDataContract;
import io.github.muntashirakon.AppManager.appdata.AppDataSelection;
import io.github.muntashirakon.AppManager.db.entity.Backup;
import io.github.muntashirakon.AppManager.db.utils.AppDb;
import io.github.muntashirakon.AppManager.utils.FreezeUtils;

/**
 * Fork (白い熊, +118): 仲間 — the apps that implement the sister-app data contract.
 *
 * <p>Forty-two of them, and until now no way to see them together: whether an app speaks the
 * contract at all, which format it writes, whether its last backup actually carried its data, and
 * which categories have been chosen for it were four separate places to look.
 *
 * <p><b>Nothing is woken.</b> Support is read from the manifest, exactly as
 * {@link AppDataContract} does it — so a frozen app (and 270 of them are frozen) still appears
 * here, which is the entire reason discovery was built that way.
 */
public class SisterAppsSource implements ScreenSource {
    private static final int SORT_NAME = 0;
    private static final int SORT_BACKUP_DATE = 1;
    private static final int SORT_FORMAT = 2;

    @Override
    public int titleRes() {
        return R.string.screen_sister;
    }

    @Override
    public int emptyTextRes() {
        return R.string.screen_sister_empty;
    }

    @NonNull
    @Override
    public List<ScreenRow> load(@NonNull Context context) {
        List<ScreenRow> rows = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        int userId = UserHandleHidden.myUserId();
        List<ApplicationInfo> apps;
        try {
            // MATCH_UNINSTALLED_PACKAGES | MATCH_DISABLED_COMPONENTS for the same reason the
            // contract's own discovery uses them: a frozen app vanishes from a plain query, and
            // frozen apps are exactly the ones that must stay listed.
            apps = pm.getInstalledApplications(PackageManager.GET_META_DATA
                    | PackageManager.MATCH_UNINSTALLED_PACKAGES
                    | PackageManager.MATCH_DISABLED_COMPONENTS);
        } catch (Throwable th) {
            return rows;
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
        DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        for (ApplicationInfo app : apps) {
            AppDataContract.Support support = AppDataContract.fromMetaData(app.metaData);
            if (support == null) {
                continue;
            }
            ScreenRow row = new ScreenRow(app.packageName, userId, app.loadLabel(pm),
                    app.packageName, 0L);
            row.info = app;
            row.uid = app.uid;
            row.frozen = FreezeUtils.isFrozen(app);
            row.sortKey2 = support.format;

            // Line 1 — what it says it speaks. A contract number we do not speak is the single
            // most useful thing this screen can tell you, so it is said in the alarm colour.
            String contractLine = context.getString(R.string.screen_sister_contract,
                    support.contract, support.format, support.minFormat);
            if (!support.isUsable()) {
                row.accent = 0xFFFF0028;
                contractLine = contractLine + " · " + context.getString(R.string.screen_sister_unusable);
            }
            row.add(contractLine);

            // Line 2 — whether a backup actually carried its data, and when.
            Backup withData = latestWithAppData.get(app.packageName);
            if (withData != null) {
                row.sortKey = withData.backupTime;
                row.add(context.getString(R.string.screen_sister_has_data,
                        dateFormat.format(new Date(withData.backupTime))));
            } else if (latestAny.get(app.packageName) != null) {
                // Backed up, but without its own data — a distinction worth drawing, because the
                // backup looks complete in every other list.
                row.sortKey = 0;
                row.add(context.getString(R.string.screen_sister_backup_without_data));
            } else {
                row.sortKey = 0;
                row.add(context.getString(R.string.screen_sister_never_backed_up));
            }

            // Line 3 — the category choice, or the app's own default when never customised.
            AppDataSelection.Stored stored = AppDataSelection.get(context, app.packageName);
            if (stored == null) {
                row.add(context.getString(R.string.screen_sister_default_categories));
            } else {
                row.add(context.getString(R.string.screen_sister_chosen_categories,
                        stored.chosen.size(), stored.seen.size()));
            }
            rows.add(row);
        }
        applySort(rows, SORT_NAME);
        return rows;
    }

    @NonNull
    @Override
    public List<CharSequence> sortLabels(@NonNull Context context) {
        return Arrays.asList(
                context.getString(R.string.screen_sort_name),
                context.getString(R.string.screen_sort_backup_date),
                context.getString(R.string.screen_sort_format));
    }

    @Override
    public void applySort(@NonNull List<ScreenRow> rows, int sortMode) {
        switch (sortMode) {
            case SORT_BACKUP_DATE:
                Collections.sort(rows, (a, b) -> Long.compare(b.sortKey, a.sortKey));
                break;
            case SORT_FORMAT:
                Collections.sort(rows, (a, b) -> Long.compare(b.sortKey2, a.sortKey2));
                break;
            case SORT_NAME:
            default:
                Collections.sort(rows, (a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
                break;
        }
    }

    @Override
    public void onRowClicked(@NonNull AppCompatActivity activity, @NonNull ScreenRow row) {
        // The category picker: it thaws the app, asks it what it can export, and puts it back —
        // which is the one thing you would want to do from a list of sister apps.
        AppDataCategoryPicker.show(activity, row.packageName, row.userId, null);
    }
}
