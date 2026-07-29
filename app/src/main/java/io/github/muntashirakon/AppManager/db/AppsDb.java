// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.db;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import io.github.muntashirakon.AppManager.db.dao.AppDao;
import io.github.muntashirakon.AppManager.db.dao.BackupDao;
import io.github.muntashirakon.AppManager.db.dao.BatterySampleDao;
import io.github.muntashirakon.AppManager.db.dao.FmFavoriteDao;
import io.github.muntashirakon.AppManager.db.dao.FmDirectorySizeDao;
import io.github.muntashirakon.AppManager.db.dao.FmDirectorySortDao;
import io.github.muntashirakon.AppManager.db.dao.FreezeTypeDao;
import io.github.muntashirakon.AppManager.db.dao.LogFilterDao;
import io.github.muntashirakon.AppManager.db.dao.OpHistoryDao;
import io.github.muntashirakon.AppManager.db.dao.PermissionOverrideDao;
import io.github.muntashirakon.AppManager.db.dao.VtFileDao;
import io.github.muntashirakon.AppManager.db.dao.VtFileSourceDao;
import io.github.muntashirakon.AppManager.db.dao.VtScanAttemptDao;
import io.github.muntashirakon.AppManager.db.entity.App;
import io.github.muntashirakon.AppManager.db.entity.Backup;
import io.github.muntashirakon.AppManager.db.entity.BatterySample;
import io.github.muntashirakon.AppManager.db.entity.FmFavorite;
import io.github.muntashirakon.AppManager.db.entity.FmDirectorySize;
import io.github.muntashirakon.AppManager.db.entity.FmDirectorySort;
import io.github.muntashirakon.AppManager.db.entity.FreezeType;
import io.github.muntashirakon.AppManager.db.entity.LogFilter;
import io.github.muntashirakon.AppManager.db.entity.OpHistory;
import io.github.muntashirakon.AppManager.db.entity.PermissionOverride;
import io.github.muntashirakon.AppManager.db.entity.VtFile;
import io.github.muntashirakon.AppManager.db.entity.VtFileSource;
import io.github.muntashirakon.AppManager.db.entity.VtScanAttempt;
import io.github.muntashirakon.AppManager.utils.ContextUtils;

@Database(entities = {App.class, LogFilter.class, Backup.class, OpHistory.class, FmFavorite.class, FreezeType.class,
        BatterySample.class, PermissionOverride.class, FmDirectorySort.class, FmDirectorySize.class, VtFile.class,
        VtFileSource.class, VtScanAttempt.class}, version = 12)
public abstract class AppsDb extends RoomDatabase {
    private static AppsDb sAppsDb;

    public static final Migration M_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `op_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `time` INTEGER NOT NULL, `data` TEXT NOT NULL, `status` TEXT NOT NULL, `extra` TEXT)");
        }
    };
    public static final Migration M_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `fm_favorite` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `uri` TEXT NOT NULL, `init_uri` TEXT, `options` INTEGER NOT NULL, `order` INTEGER NOT NULL, `type` INTEGER NOT NULL)");
        }
    };
    public static final Migration M_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `freeze_type` (`package_name` TEXT NOT NULL, `type` INTEGER NOT NULL, PRIMARY KEY(`package_name`))");
        }
    };
    public static final Migration M_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE `app` ADD COLUMN `is_only_data_installed` INTEGER NOT NULL DEFAULT 0");
        }
    };
    public static final Migration M_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("DROP TABLE IF EXISTS `file_hash`");
        }
    };
    // Fork: battery-history sampler storage. Column order and nullability must
    // match BatterySample exactly or Room rejects the migration at open time.
    public static final Migration M_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `battery_sample` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`ts` INTEGER NOT NULL, "
                    + "`duration` INTEGER NOT NULL, "
                    + "`uid` INTEGER NOT NULL, "
                    + "`package_name` TEXT, "
                    + "`wifi_bytes` INTEGER NOT NULL, "
                    + "`wifi_packets` INTEGER NOT NULL, "
                    + "`mobile_bytes` INTEGER NOT NULL, "
                    + "`mobile_packets` INTEGER NOT NULL, "
                    + "`radio_active_ms` INTEGER NOT NULL, "
                    + "`wakelock_ms` INTEGER NOT NULL, "
                    + "`wakeup_count` INTEGER NOT NULL, "
                    + "`cpu_ms` INTEGER NOT NULL, "
                    + "`fg_service_ms` INTEGER NOT NULL, "
                    + "`sensor_ms` INTEGER NOT NULL, "
                    + "`screen_on_ms` INTEGER NOT NULL, "
                    + "`deep_idle_ms` INTEGER NOT NULL, "
                    + "`light_idle_ms` INTEGER NOT NULL, "
                    + "`battery_level` INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_battery_sample_ts` ON `battery_sample` (`ts`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_battery_sample_uid` ON `battery_sample` (`uid`)");
        }
    };

    // Fork: mAh (where the device's power profile is real) plus the open-ended
    // extras map. Defaults must match BatterySample's @ColumnInfo defaultValue
    // exactly — Room compares them and refuses to open the DB on a mismatch.
    public static final Migration M_8_9 = new Migration(8, 9) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE `battery_sample` ADD COLUMN `power_mah` REAL NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `battery_sample` ADD COLUMN `power_model_usable` INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `battery_sample` ADD COLUMN `extras` TEXT");
        }
    };

    // Fork: battery voltage + charging state on the device row, for the
    // level/voltage chart at the top of the battery screen.
    public static final Migration M_9_10 = new Migration(9, 10) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE `battery_sample` ADD COLUMN `voltage_mv` INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `battery_sample` ADD COLUMN `charging` INTEGER NOT NULL DEFAULT 0");
        }
    };

    // Upstream 4.1.1 introduced `permission_override` (the controller-based permission
    // system) as ITS version 8. This fork's v8/v9/v10 are already spent on the battery
    // sampler and are live on installed devices, so upstream's table is added here as
    // v11 instead. Do not renumber the battery migrations — existing installs are at
    // v10 and renumbering would either destroy their history or refuse to open.
    public static final Migration M_10_11 = new Migration(10, 11) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `permission_override` (`package_name` TEXT NOT NULL, `user_id` INTEGER NOT NULL, `permission_name` TEXT NOT NULL, `desired_granted` INTEGER NOT NULL, `controller` TEXT NOT NULL, `sync_status` INTEGER NOT NULL, `sync_time` INTEGER NOT NULL, PRIMARY KEY(`package_name`, `user_id`, `permission_name`))");
        }
    };
    // Fork: upstream's file-manager (fm_directory_sort/size) and VirusTotal (vt_*) tables.
    // Upstream created these at its own v8-v11; the fork's v8-v11 are the battery sampler +
    // permission_override (live on installed devices), so upstream's tables are appended here
    // as v12 instead — the fork's "append upstream's new tables at the next free version, never
    // renumber the live ones" rule. The steps are lifted verbatim (and in order) from upstream
    // M_8_9/M_9_10/M_10_11, so the resulting schema is byte-identical to what Room validates for
    // these entities, including the latest_read_at/read_at columns (originally added by ALTER).
    public static final Migration M_11_12 = new Migration(11, 12) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `fm_directory_sort` (`directory_key` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, `reverse_sort` INTEGER NOT NULL, `last_used_at` INTEGER NOT NULL, PRIMARY KEY(`directory_key`))");
            db.execSQL("CREATE TABLE IF NOT EXISTS `fm_directory_size` (`directory_key` TEXT NOT NULL, `size_bytes` INTEGER NOT NULL, `calculated_at` INTEGER NOT NULL, `last_used_at` INTEGER NOT NULL, PRIMARY KEY(`directory_key`))");
            db.execSQL("CREATE TABLE IF NOT EXISTS `vt_file` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`sha256` TEXT NOT NULL, " +
                    "`md5` TEXT, " +
                    "`sha1` TEXT, " +
                    "`display_name` TEXT, " +
                    "`mime_type` TEXT, " +
                    "`size_bytes` INTEGER NOT NULL, " +
                    "`latest_report_json` TEXT, " +
                    "`latest_detected` INTEGER, " +
                    "`latest_total` INTEGER, " +
                    "`latest_analysis_id` TEXT, " +
                    "`permalink` TEXT, " +
                    "`latest_analysis_at` INTEGER NOT NULL, " +
                    "`local_analysis_json` TEXT, " +
                    "`last_accessed_at` INTEGER NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_vt_file_sha256` ON `vt_file` (`sha256`)");
            db.execSQL("CREATE TABLE IF NOT EXISTS `vt_file_source` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`file_id` INTEGER NOT NULL, " +
                    "`source_uri` TEXT NOT NULL, " +
                    "`display_name` TEXT, " +
                    "`mime_type` TEXT, " +
                    "`last_seen_at` INTEGER NOT NULL, " +
                    "`is_readable` INTEGER NOT NULL, " +
                    "`source_type` TEXT, " +
                    "FOREIGN KEY(`file_id`) REFERENCES `vt_file`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vt_file_source_file_id` ON `vt_file_source` (`file_id`)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_vt_file_source_file_uri` ON `vt_file_source` (`file_id`, `source_uri`)");
            db.execSQL("CREATE TABLE IF NOT EXISTS `vt_scan_attempt` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`file_id` INTEGER NOT NULL, " +
                    "`type` TEXT NOT NULL, " +
                    "`status` TEXT NOT NULL, " +
                    "`analysis_id` TEXT, " +
                    "`permalink` TEXT, " +
                    "`report_json` TEXT, " +
                    "`detected` INTEGER, " +
                    "`total` INTEGER, " +
                    "`error_code` TEXT, " +
                    "`error_message` TEXT, " +
                    "`created_at` INTEGER NOT NULL, " +
                    "`started_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL, " +
                    "`completed_at` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`file_id`) REFERENCES `vt_file`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vt_scan_attempt_file_id` ON `vt_scan_attempt` (`file_id`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vt_scan_attempt_status` ON `vt_scan_attempt` (`status`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vt_scan_attempt_updated_at` ON `vt_scan_attempt` (`updated_at`)");
            db.execSQL("ALTER TABLE `vt_file` ADD COLUMN `latest_read_at` INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `vt_scan_attempt` ADD COLUMN `read_at` INTEGER NOT NULL DEFAULT 0");
        }
    };

    public static AppsDb getInstance() {
        if (sAppsDb == null) {
            sAppsDb = Room.databaseBuilder(ContextUtils.getContext(), AppsDb.class, "apps.db")
                    .addMigrations(M_2_3, M_3_4, M_4_5, M_5_6, M_6_7, M_7_8, M_8_9, M_9_10, M_10_11, M_11_12)
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build();
            try {
                sAppsDb.appDao().getAll();
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
        return sAppsDb;
    }

    public abstract AppDao appDao();

    public abstract BackupDao backupDao();

    public abstract LogFilterDao logFilterDao();

    public abstract OpHistoryDao opHistoryDao();

    public abstract FmFavoriteDao fmFavoriteDao();

    public abstract FmDirectorySortDao fmDirectorySortDao();

    public abstract FmDirectorySizeDao fmDirectorySizeDao();

    public abstract FreezeTypeDao freezeTypeDao();

    public abstract BatterySampleDao batterySampleDao();

    public abstract PermissionOverrideDao permissionOverrideDao();

    public abstract VtFileDao vtFileDao();

    public abstract VtFileSourceDao vtFileSourceDao();

    public abstract VtScanAttemptDao vtScanAttemptDao();
}
