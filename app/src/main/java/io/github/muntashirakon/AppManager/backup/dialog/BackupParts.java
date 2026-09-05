// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.backup.dialog;

import android.content.Context;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.backup.BackupFlags;

/**
 * Fork (白い熊, +121): what a backup is made of, and how each part looks.
 *
 * <p>Before this the parts were a list of checkbox labels — identical yellow text in a column,
 * where the only way to tell the APK from the rules was to read a paragraph about each. They are
 * not interchangeable things: one is the app, one is its data, one is what other apps were told
 * about it. So each part now has <b>its own colour and its own glyph</b>, and every surface that
 * shows a backup — the options list, the batch table, the row of an existing backup — draws it
 * the same way. Learning it once is enough.
 *
 * <p>The palette is fixed rather than settable. These colours are a legend, not decoration: if
 * the APK were yellow on one screen and blue on another the legend would be worth nothing, and a
 * per-part colour picker is a setting nobody would ever have a second opinion about.
 */
public final class BackupParts {
    /** One component of a backup. */
    public static final class Part {
        @BackupFlags.BackupFlag
        public final int flag;
        @StringRes
        public final int labelRes;
        @StringRes
        public final int descriptionRes;
        @DrawableRes
        public final int iconRes;
        public final int color;

        Part(int flag, @StringRes int labelRes, @StringRes int descriptionRes,
             @DrawableRes int iconRes, int color) {
            this.flag = flag;
            this.labelRes = labelRes;
            this.descriptionRes = descriptionRes;
            this.iconRes = iconRes;
            this.color = color;
        }

        @NonNull
        public CharSequence label(@NonNull Context context) {
            return context.getString(labelRes);
        }
    }

    // The palette. Cool colours for what the app IS, warm for what it HOLDS, violet for what the
    // system knows about it — so a glance at a row says what kind of thing is being kept.
    private static final int APK = 0xFF7FD1FF;        // ice blue — the app itself
    private static final int DATA = 0xFFFFE066;       // theme yellow — its own data
    private static final int EXTERNAL = 0xFFFFB74D;   // amber — data out on shared storage
    private static final int APP_DATA = 0xFF4CD07A;   // green — what the app hands over itself
    private static final int MEDIA = 0xFFB388FF;      // violet — bulk media and OBB
    private static final int CACHE = 0xFF8A8A8A;      // grey — disposable by definition
    private static final int EXTRAS = 0xFFFF8A9B;     // rose — permissions and ops
    private static final int RULES = 0xFFFF6E40;      // orange — blocking rules

    /**
     * The parts of a backup that are a <em>thing being kept</em>, in the order they are written.
     * Options that are not parts — "back up multiple", "custom users", "skip signature checks" —
     * are deliberately not here: they change how the backup is made, not what is in it.
     */
    @NonNull
    public static List<Part> contentParts() {
        return Arrays.asList(
                new Part(BackupFlags.BACKUP_APK_FILES, R.string.backup_apk_files,
                        R.string.backup_apk_files_description, R.drawable.ic_package, APK),
                new Part(BackupFlags.BACKUP_INT_DATA, R.string.internal_data,
                        R.string.backup_internal_data_description, R.drawable.ic_database, DATA),
                new Part(BackupFlags.BACKUP_EXT_DATA, R.string.external_data,
                        R.string.backup_external_data_description, R.drawable.ic_folder, EXTERNAL),
                new Part(BackupFlags.BACKUP_APP_DATA, R.string.backup_app_data,
                        R.string.backup_app_data_description, R.drawable.ic_backup_restore, APP_DATA),
                new Part(BackupFlags.BACKUP_EXT_OBB_MEDIA, R.string.backup_obb_media,
                        R.string.backup_obb_media_description, R.drawable.ic_image, MEDIA),
                new Part(BackupFlags.BACKUP_CACHE, R.string.backup_cache,
                        R.string.backup_cache_description, R.drawable.ic_clear_cache, CACHE),
                new Part(BackupFlags.BACKUP_EXTRAS, R.string.backup_extras,
                        R.string.backup_extras_description, R.drawable.ic_shield_key, EXTRAS),
                new Part(BackupFlags.BACKUP_RULES, R.string.rules,
                        R.string.backup_rules_description, R.drawable.ic_block, RULES));
    }

    /** Every part, plus the options that change how the backup is written. */
    @NonNull
    public static List<Part> allParts(int supportedFlags) {
        List<Part> parts = new ArrayList<>(contentParts());
        parts.add(new Part(BackupFlags.BACKUP_MULTIPLE, R.string.backup_multiple,
                R.string.backup_multiple_description, R.drawable.ic_file_plus, 0xFFB0BEC5));
        parts.add(new Part(BackupFlags.BACKUP_CUSTOM_USERS, R.string.backup_custom_users,
                R.string.backup_custom_users_description, R.drawable.ic_content_duplicate, 0xFFB0BEC5));
        List<Part> supported = new ArrayList<>(parts.size());
        for (Part part : parts) {
            if ((supportedFlags & part.flag) != 0) {
                supported.add(part);
            }
        }
        return supported;
    }

    private BackupParts() {
    }
}
