// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.screens;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.backup.BackupFlags;
import io.github.muntashirakon.AppManager.backup.CryptoUtils;
import io.github.muntashirakon.AppManager.backup.dialog.AppBackupDialogFragment;
import io.github.muntashirakon.AppManager.batchops.OpLog;
import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.db.entity.Backup;
import io.github.muntashirakon.AppManager.db.utils.AppDb;
import io.github.muntashirakon.io.Path;

/**
 * Fork (白い熊, +118): 保存一覧 — every package that has backups on disk.
 *
 * <p><b>Including the ones that are not installed any more</b>, which is most of the value: those
 * are exactly the backups the main list cannot show you, because the main list is a list of apps
 * and they have no app. A backup you have forgotten you own is the one worth a screen.
 *
 * <p>Everything on the right is a fact about the backups themselves — how many, how large, how
 * old, what is inside them, and whether the newest one still matches the installed app. The
 * staleness rule is the one from the "Backup older than app" filter (+105) and is deliberately the
 * same rule: an app is stale when it is installed, has backups, and <b>none</b> of them reaches the
 * installed versionCode. A single current backup clears it, whatever sits beside it.
 */
public class BackupsSource implements ScreenSource {
    private static final int SORT_DATE = 0;
    private static final int SORT_NAME = 1;
    private static final int SORT_COUNT = 2;
    private static final int SORT_SIZE = 3;
    private static final int SORT_STALE = 4;

    @Override
    public int titleRes() {
        return R.string.screen_backups;
    }

    @Override
    public int emptyTextRes() {
        return R.string.screen_backups_empty;
    }

    @NonNull
    @Override
    public List<ScreenRow> load(@NonNull Context context) {
        Map<String, List<Backup>> byPackage = new LinkedHashMap<>();
        for (Backup backup : new AppDb().getAllBackups()) {
            if (backup == null || backup.packageName == null) continue;
            List<Backup> list = byPackage.get(backup.packageName);
            if (list == null) {
                list = new ArrayList<>();
                byPackage.put(backup.packageName, list);
            }
            list.add(backup);
        }
        DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        List<ScreenRow> rows = new ArrayList<>(byPackage.size());
        for (Map.Entry<String, List<Backup>> entry : byPackage.entrySet()) {
            rows.add(buildRow(context, entry.getKey(), entry.getValue(), dateFormat));
        }
        applySort(rows, SORT_DATE);
        return rows;
    }

    @NonNull
    private ScreenRow buildRow(@NonNull Context context, @NonNull String packageName,
                               @NonNull List<Backup> backups, @NonNull DateFormat dateFormat) {
        Backup latest = backups.get(0);
        long totalSize = 0;
        for (Backup backup : backups) {
            if (backup.backupTime > latest.backupTime) {
                latest = backup;
            }
            totalSize += sizeOf(backup);
        }
        // The installed app, if there still is one. MATCH_UNINSTALLED_PACKAGES so a frozen app
        // is not mistaken for a gone one — the whole screen turns on that distinction.
        ApplicationInfo installed = null;
        long installedVersionCode = -1;
        String installedVersionName = null;
        try {
            installed = PackageManagerCompat.getApplicationInfo(packageName,
                    PackageManagerCompat.MATCH_UNINSTALLED_PACKAGES
                            | PackageManagerCompat.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES,
                    latest.userId);
            android.content.pm.PackageInfo pi = context.getPackageManager().getPackageInfo(packageName, 0);
            installedVersionName = pi.versionName;
            installedVersionCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi);
        } catch (Throwable ignore) {
        }
        boolean isInstalled = installed != null
                && io.github.muntashirakon.AppManager.compat.ApplicationInfoCompat.isInstalled(installed);
        CharSequence label = latest.label != null ? latest.label : packageName;
        if (installed != null) {
            try {
                label = installed.loadLabel(context.getPackageManager());
            } catch (Throwable ignore) {
            }
        }
        ScreenRow row = new ScreenRow(packageName, latest.userId, label, packageName,
                latest.backupTime);
        for (Backup backup : backups) {
            if (backup.relativeDir != null) {
                row.relativeDirs.add(backup.relativeDir);
            }
        }
        row.info = installed;
        row.uid = installed != null ? installed.uid : 0;
        row.frozen = installed != null && io.github.muntashirakon.AppManager.utils.FreezeUtils.isFrozen(installed);
        row.installed = isInstalled;
        row.sortKey = latest.backupTime;
        row.sortKey2 = totalSize;

        // Line 1 — the comparison the screen exists for.
        boolean stale = isInstalled && installedVersionCode >= 0
                && !reachesInstalled(backups, installedVersionCode);
        String backupVersion = latest.versionName != null ? latest.versionName
                : String.valueOf(latest.versionCode);
        String versions;
        if (!isInstalled) {
            versions = context.getString(R.string.screen_backups_orphan, backupVersion);
            row.accent = 0xFF8A8A8A;
        } else if (stale) {
            versions = backupVersion + "  →  " + (installedVersionName != null
                    ? installedVersionName : String.valueOf(installedVersionCode));
            row.accent = 0xFFFF0028;
        } else {
            versions = backupVersion;
        }
        row.add(versions);
        // Line 2 — how many, and how much room they take.
        row.add(context.getString(R.string.screen_backups_count, backups.size())
                + " · " + OpLog.formatSize(totalSize));
        // Line 3 — when the newest one was made.
        row.add(dateFormat.format(new Date(latest.backupTime)));
        // Line 4 — what is actually inside it, which is not always what you assume.
        row.add(contents(context, latest));
        return row;
    }

    /** The +105 rule, stated once: any backup reaching the installed version clears the app. */
    private static boolean reachesInstalled(@NonNull List<Backup> backups, long installedVersionCode) {
        for (Backup backup : backups) {
            if (backup.versionCode >= installedVersionCode) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    private static String contents(@NonNull Context context, @NonNull Backup backup) {
        BackupFlags flags = backup.getFlags();
        List<String> parts = new ArrayList<>();
        if (flags.backupApkFiles()) parts.add(context.getString(R.string.screen_backups_part_apk));
        if (flags.backupData()) parts.add(context.getString(R.string.screen_backups_part_data));
        if (flags.backupExtras()) parts.add(context.getString(R.string.screen_backups_part_extras));
        if (flags.backupRules()) parts.add(context.getString(R.string.screen_backups_part_rules));
        if (flags.backupAppData()) parts.add(context.getString(R.string.screen_backups_part_app_data));
        String crypto = backup.crypto;
        if (!TextUtils.isEmpty(crypto) && !CryptoUtils.MODE_NO_ENCRYPTION.equals(crypto)) {
            parts.add(crypto.toUpperCase(Locale.ROOT));
        }
        return TextUtils.join(" ", parts);
    }

    /**
     * Bytes this backup occupies. Walks the backup's own directory rather than trusting metadata:
     * the metadata records what was written, and what is on disk is what you would get back.
     */
    private static long sizeOf(@NonNull Backup backup) {
        try {
            Path path = backup.getItem().getBackupPath();
            return sizeOf(path);
        } catch (Throwable th) {
            return 0;
        }
    }

    private static long sizeOf(@Nullable Path path) {
        if (path == null) {
            return 0;
        }
        try {
            if (!path.isDirectory()) {
                return path.length();
            }
            long total = 0;
            Path[] children = path.listFiles();
            if (children != null) {
                for (Path child : children) {
                    total += sizeOf(child);
                }
            }
            return total;
        } catch (Throwable th) {
            return 0;
        }
    }

    @NonNull
    @Override
    public List<CharSequence> sortLabels(@NonNull Context context) {
        return Arrays.asList(
                context.getString(R.string.screen_sort_backup_date),
                context.getString(R.string.screen_sort_name),
                context.getString(R.string.screen_sort_backup_count),
                context.getString(R.string.screen_sort_size),
                context.getString(R.string.screen_sort_stale_first));
    }

    @Override
    public void applySort(@NonNull List<ScreenRow> rows, int sortMode) {
        switch (sortMode) {
            case SORT_NAME:
                Collections.sort(rows, (a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
                break;
            case SORT_COUNT:
                Collections.sort(rows, (a, b) -> Long.compare(countOf(b), countOf(a)));
                break;
            case SORT_SIZE:
                Collections.sort(rows, (a, b) -> Long.compare(b.sortKey2, a.sortKey2));
                break;
            case SORT_STALE:
                // Stale first, then orphaned, then the rest — worst news at the top.
                Collections.sort(rows, (a, b) -> Integer.compare(rank(b), rank(a)));
                break;
            case SORT_DATE:
            default:
                Collections.sort(rows, (a, b) -> Long.compare(b.sortKey, a.sortKey));
                break;
        }
    }

    private static int rank(@NonNull ScreenRow row) {
        if (row.accent == 0xFFFF0028) return 2;
        if (!row.installed) return 1;
        return 0;
    }

    /** The count is in the second right-hand line; parsing it back beats storing it twice. */
    private static long countOf(@NonNull ScreenRow row) {
        if (row.right.size() < 2) return 0;
        String s = row.right.get(1).toString();
        long n = 0;
        for (int i = 0; i < s.length(); ++i) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                n = n * 10 + (c - '0');
            } else if (n > 0) {
                break;
            }
        }
        return n;
    }

    @Override
    public void onRowClicked(@NonNull AppCompatActivity activity, @NonNull ScreenRow row) {
        // Straight into the dialog that holds every backup action for this app (+72) — which is
        // what a person on a list of backups came here to reach.
        AppBackupDialogFragment.getInstance(row.packageName, row.userId, row.label)
                .show(activity.getSupportFragmentManager(), AppBackupDialogFragment.TAG);
    }
}
