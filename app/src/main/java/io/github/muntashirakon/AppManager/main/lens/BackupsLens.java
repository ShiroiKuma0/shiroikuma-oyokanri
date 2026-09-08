// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main.lens;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.backup.BackupFlags;
import io.github.muntashirakon.AppManager.backup.CryptoUtils;
import io.github.muntashirakon.AppManager.batchops.OpLog;
import io.github.muntashirakon.AppManager.db.entity.Backup;
import io.github.muntashirakon.AppManager.main.ApplicationItem;
import io.github.muntashirakon.AppManager.settings.Prefs;
import io.github.muntashirakon.io.Path;

/**
 * Fork (白い熊, +162): 保存一覧 as a lens — every app that has a backup, with the backup's own facts in
 * the right-hand column.
 *
 * <p>It replaces {@code screens/BackupsSource} line for line, and gains everything that source could
 * never have: the search field, the filter sheet, profile filters, saved views, the app pane, the
 * batch pane, per-geometry columns.
 *
 * <p>One thing improves for free rather than by design. The screen drew the Android robot for an app
 * that is no longer installed, because its rows carried an {@code ApplicationInfo} and there was
 * none; here a row IS an {@link ApplicationItem}, whose {@code loadIcon} has always fallen back to
 * the backup's own {@code icon.png}. The same is true of the label.
 *
 * <p><b>Cost.</b> The size of a backup is the one expensive fact on the page — it is a walk of the
 * backup's directory, because the metadata records what was <em>written</em> and what is on disk is
 * what you would actually get back. That walk happens in {@link #prepare} on the pipeline's worker
 * thread and is cached against the newest backup's timestamp, so it is paid once per app and redone
 * by itself when a new backup lands. {@link #rightLines} runs at bind time and only reads the cache.
 */
public class BackupsLens implements MainLens {
    public static final String ID = "backups";

    public static final int SORT_BACKUP_DATE = 0;
    public static final int SORT_NAME = 1;
    public static final int SORT_COUNT = 2;
    public static final int SORT_SIZE = 3;
    public static final int SORT_STALE_FIRST = 4;

    /** Grey: there is no app behind this row any more. */
    private static final int ACCENT_ORPHAN = 0xFF8A8A8A;
    /** The page's red: every backup here predates the installed version. */
    private static final int ACCENT_STALE = 0xFFFF0028;

    /** What {@link #prepare} works out per app, so that binding a row touches no disk. */
    private static final class Info {
        int count;
        long size;
        boolean stale;
        boolean installed;
        long newestBackupTime;
        @Nullable
        String backupVersion;
        @Nullable
        String installedVersion;
        @Nullable
        String contents;
    }

    private final Map<String, Info> mInfo = new ConcurrentHashMap<>();
    /** pkg + "@" + newest backup time -> bytes. Survives a pass; invalidated by a newer backup. */
    private final Map<String, Long> mSizes = new ConcurrentHashMap<>();

    @NonNull
    @Override
    public String id() {
        return ID;
    }

    @Override
    public int titleRes() {
        return R.string.screen_backups;
    }

    @Override
    public boolean includes(@NonNull ApplicationItem item) {
        // Cheap by construction: the field is filled by the same DB read that builds the list, and
        // an app with no backup has nothing this lens could say about it.
        return item.backup != null;
    }

    @WorkerThread
    @Override
    public void prepare(@NonNull Context context, @NonNull List<ApplicationItem> items) {
        DateFormat dateFormat = android.text.format.DateFormat.getDateFormat(context);
        for (ApplicationItem item : items) {
            if (item.backup == null) {
                continue;
            }
            Info info = new Info();
            Backup[] backups = item.getBackups();
            Backup latest = item.backup;
            long total = 0;
            for (Backup backup : backups) {
                if (backup.backupTime > latest.backupTime) {
                    latest = backup;
                }
                total += sizeOf(backup);
            }
            if (backups.length == 0) {
                // The DB says there is a backup but the per-package query came back empty; trust
                // the one we were handed rather than reporting "0 backups".
                total = sizeOf(latest);
            }
            info.count = Math.max(backups.length, 1);
            info.size = total;
            info.installed = item.isInstalled;
            info.newestBackupTime = latest.backupTime;
            info.backupVersion = latest.versionName != null ? latest.versionName
                    : String.valueOf(latest.versionCode);
            info.installedVersion = item.versionName;
            // The +105 rule, unchanged: ANY backup reaching the installed version clears the app,
            // however many older ones sit beside it.
            boolean reaches = false;
            for (Backup backup : backups) {
                if (backup.versionCode >= item.versionCode) {
                    reaches = true;
                    break;
                }
            }
            info.stale = item.isInstalled && !reaches;
            info.contents = contents(context, latest);
            info.newestBackupTime = latest.backupTime;
            mInfo.put(item.packageName, info);
            // Cached here too, so a re-sort or a keystroke in the search box does not re-walk it.
            mSizes.put(item.packageName + "@" + latest.backupTime, total);
        }
    }

    @NonNull
    @Override
    public List<CharSequence> rightLines(@NonNull Context context, @NonNull ApplicationItem item) {
        Info info = mInfo.get(item.packageName);
        if (info == null || item.backup == null) {
            // prepare() has not reached this row yet (it is published one pass behind on a cold
            // start). Say the one thing that is certainly true rather than nothing at all.
            return Collections.singletonList(item.backup != null && item.backup.versionName != null
                    ? item.backup.versionName : "");
        }
        List<CharSequence> lines = new ArrayList<>(4);
        // Line 1 — the comparison the page exists for.
        String backupVersion = info.backupVersion != null ? info.backupVersion : "";
        if (!info.installed) {
            lines.add(context.getString(R.string.screen_backups_orphan, backupVersion));
        } else if (info.stale) {
            lines.add(backupVersion + "  →  " + (info.installedVersion != null
                    ? info.installedVersion : String.valueOf(item.versionCode)));
        } else {
            lines.add(backupVersion);
        }
        // Line 2 — how many, and how much room they take.
        lines.add(context.getString(R.string.screen_backups_count, info.count)
                + " · " + OpLog.formatSize(info.size));
        // Line 3 — when the newest one was made.
        lines.add(shortDateTime(info.newestBackupTime));
        // Line 4 — what is actually inside it, which is not always what you assume.
        lines.add(info.contents != null ? info.contents : "");
        return lines;
    }

    @Override
    public int accent(@NonNull ApplicationItem item) {
        Info info = mInfo.get(item.packageName);
        if (info == null) {
            return 0;
        }
        if (!info.installed) return ACCENT_ORPHAN;
        if (info.stale) return ACCENT_STALE;
        return 0;
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
    public void applySort(@NonNull List<ApplicationItem> rows, int lensSort) {
        switch (lensSort) {
            case SORT_NAME:
                // The list's own label order already holds underneath; leave it alone.
                break;
            case SORT_COUNT:
                Collections.sort(rows, (a, b) -> Integer.compare(count(b), count(a)));
                break;
            case SORT_SIZE:
                Collections.sort(rows, (a, b) -> Long.compare(size(b), size(a)));
                break;
            case SORT_STALE_FIRST:
                Collections.sort(rows, (a, b) -> Integer.compare(rank(b), rank(a)));
                break;
            case SORT_BACKUP_DATE:
            default:
                Collections.sort(rows, (a, b) -> Long.compare(newest(b), newest(a)));
                break;
        }
    }

    private int count(@NonNull ApplicationItem item) {
        Info info = mInfo.get(item.packageName);
        return info != null ? info.count : 0;
    }

    private long size(@NonNull ApplicationItem item) {
        Info info = mInfo.get(item.packageName);
        return info != null ? info.size : 0;
    }

    private long newest(@NonNull ApplicationItem item) {
        Info info = mInfo.get(item.packageName);
        if (info != null) return info.newestBackupTime;
        return item.backup != null ? item.backup.backupTime : 0;
    }

    /** Stale (2) above orphaned (1) above everything else — the screen's own ranking. */
    private int rank(@NonNull ApplicationItem item) {
        Info info = mInfo.get(item.packageName);
        if (info == null) return 0;
        if (info.stale) return 2;
        if (!info.installed) return 1;
        return 0;
    }

    @NonNull
    private static String shortDateTime(long when) {
        // Locale.ROOT, matching the main list's own backup column: a fixed format is what makes two
        // rows comparable at a glance.
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date(when));
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
    private long sizeOf(@NonNull Backup backup) {
        Long cached = mSizes.get(backup.packageName + "@" + backup.backupTime);
        if (cached != null) {
            return cached;
        }
        long size;
        try {
            size = sizeOf(backup.getItem().getBackupPath());
        } catch (Throwable th) {
            size = 0;
        }
        mSizes.put(backup.packageName + "@" + backup.backupTime, size);
        return size;
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

    /** Dropped when the backup directory changes underneath us, so sizes cannot go stale silently. */
    @Override
    public void invalidate() {
        mInfo.clear();
        mSizes.clear();
    }

    /** Unused today, kept so a future lens can tell whether the backup root is even readable. */
    @SuppressWarnings("unused")
    private static boolean backupRootReadable() {
        try {
            Path path = Prefs.Storage.getAppManagerDirectory();
            return path.isDirectory();
        } catch (Throwable th) {
            return false;
        }
    }
}
