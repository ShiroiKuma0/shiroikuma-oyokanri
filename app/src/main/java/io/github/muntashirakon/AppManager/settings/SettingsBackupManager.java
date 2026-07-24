// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.settings;

import android.content.Context;

import androidx.annotation.NonNull;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import androidx.annotation.StringRes;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.io.Path;

/**
 * Fork: export and import of App Manager's own settings (as opposed to app
 * backups). Captures everything that lives in the app's {@code shared_prefs}
 * directory — the main {@code preferences} store (all options/toggles), the
 * fork's dedicated colour/font prefs, the backup-directory and backup-options
 * prefs, etc. — plus all saved profiles ({@code files/profiles/*.am.json})
 * and any user-imported custom font files ({@code files/fonts/}).
 * <p>
 * The archive is a plain zip with two top-level folders, {@code shared_prefs/}
 * and {@code profiles/}, written to a user-selected directory via the Path API
 * (so it honours whatever storage-access mode is in effect, like app backups).
 * The internal source/target files live in the app's own data dir and are read
 * and written directly with {@code java.io}.
 * <p>
 * Importing only rewrites the on-disk files; the caller must restart the
 * process afterwards so SharedPreferences are re-read from the new files.
 */
public final class SettingsBackupManager {
    public static final String EXPORT_PREFIX = "AppManager-settings_";
    public static final String EXPORT_EXT = ".zip";

    private static final String SP_DIR = "shared_prefs";
    private static final String PROFILES_DIR = "profiles";
    // Fork: user-imported custom font files live in filesDir/fonts; the font
    // *choice* is in shiroikuma_fonts.xml (captured via shared_prefs), but the
    // referenced file must travel with it or the setting points at nothing.
    private static final String FONTS_DIR = "fonts";
    private static final FileFilter XML_FILTER = f -> f.isFile() && f.getName().endsWith(".xml");

    /**
     * A selectable export/import category (Kōjiki flow). The zip layout is
     * unchanged ({@code shared_prefs/} + {@code profiles/} + {@code fonts/}),
     * so archives made before categories existed still import — every entry
     * is classified by {@link #classify} on both export and import, and only
     * entries whose category is selected pass the filter.
     */
    public enum Category {
        GENERAL(R.string.settings_eim_cat_general),
        APPEARANCE(R.string.settings_eim_cat_appearance),
        MONITOR(R.string.settings_eim_cat_monitor),
        TOOLBAR(R.string.settings_eim_cat_toolbar),
        NOTES(R.string.settings_eim_cat_notes),
        PROFILES(R.string.settings_eim_cat_profiles);

        @StringRes
        public final int labelRes;

        Category(@StringRes int labelRes) {
            this.labelRes = labelRes;
        }
    }

    // Shared-prefs stores per category; anything unlisted falls into GENERAL
    // (the main "preferences" store, backup dirs/options, and any future
    // prefs file we forget to classify — better over-carried than dropped).
    private static final Set<String> APPEARANCE_PREFS = new HashSet<>(Arrays.asList(
            "shiroikuma_colors", "shiroikuma_fonts", "shiroikuma_main_icon",
            "shiroikuma_main_layout", "shiroikuma_selection_frame",
            "shiroikuma_separators", "shiroikuma_running_box"));
    private static final Set<String> MONITOR_PREFS = new HashSet<>(Arrays.asList(
            "shiroikuma_monitor", "shiroikuma_monitor_sep", "shiroikuma_reaper"));
    private static final Set<String> TOOLBAR_PREFS = new HashSet<>(Arrays.asList(
            "am_main_toolbar", "am_main_page_profile_filter"));
    private static final String NOTES_PREFS = "shiroikuma_notes";

    /** Category of one zip-entry name (also used to gate what export writes). */
    @NonNull
    private static Category classify(@NonNull String entryName) {
        if (entryName.startsWith(PROFILES_DIR + "/")) return Category.PROFILES;
        if (entryName.startsWith(FONTS_DIR + "/")) return Category.APPEARANCE;
        String base = entryName.startsWith(SP_DIR + "/") ? entryName.substring(SP_DIR.length() + 1) : entryName;
        if (base.endsWith(".xml")) base = base.substring(0, base.length() - 4);
        if (APPEARANCE_PREFS.contains(base)) return Category.APPEARANCE;
        if (MONITOR_PREFS.contains(base)) return Category.MONITOR;
        if (TOOLBAR_PREFS.contains(base)) return Category.TOOLBAR;
        if (NOTES_PREFS.equals(base)) return Category.NOTES;
        return Category.GENERAL;
    }

    private SettingsBackupManager() {
    }

    /**
     * Zip the selected categories of the current settings into {@code destDir}.
     *
     * @return the display name of the created archive.
     */
    @NonNull
    public static String export(@NonNull Context context, @NonNull Path destDir,
                                @NonNull Set<Category> categories) throws IOException {
        String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date());
        String fileName = EXPORT_PREFIX + ts + EXPORT_EXT;
        // The name already carries the .zip extension, so pass a null mime
        // type — otherwise findOrCreateFile appends another ".zip" (the Path
        // API adds an extension from the mime type when one is given).
        Path outFile = destDir.findOrCreateFile(fileName, null);
        File sharedPrefs = new File(context.getApplicationInfo().dataDir, SP_DIR);
        File profiles = new File(context.getFilesDir(), PROFILES_DIR);
        File fonts = new File(context.getFilesDir(), FONTS_DIR);
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(outFile.openOutputStream()))) {
            File[] prefFiles = sharedPrefs.isDirectory() ? sharedPrefs.listFiles(XML_FILTER) : null;
            if (prefFiles != null) {
                for (File f : prefFiles) {
                    String entry = SP_DIR + "/" + f.getName();
                    if (categories.contains(classify(entry))) {
                        addEntry(zos, f, entry);
                    }
                }
            }
            if (categories.contains(Category.PROFILES)) {
                File[] profileFiles = profiles.isDirectory() ? profiles.listFiles(File::isFile) : null;
                if (profileFiles != null) {
                    for (File f : profileFiles) {
                        addEntry(zos, f, PROFILES_DIR + "/" + f.getName());
                    }
                }
            }
            if (categories.contains(Category.APPEARANCE)) {
                File[] fontFiles = fonts.isDirectory() ? fonts.listFiles(File::isFile) : null;
                if (fontFiles != null) {
                    for (File f : fontFiles) {
                        addEntry(zos, f, FONTS_DIR + "/" + f.getName());
                    }
                }
            }
        }
        return fileName;
    }

    /**
     * Restore the selected categories from a previously exported archive.
     * Only rewrites the on-disk files; the caller must restart the process
     * for the changes to take effect.
     *
     * @return the number of files restored.
     */
    public static int importFrom(@NonNull Context context, @NonNull Path zipFile,
                                 @NonNull Set<Category> categories) throws IOException {
        File sharedPrefs = new File(context.getApplicationInfo().dataDir, SP_DIR);
        File profiles = new File(context.getFilesDir(), PROFILES_DIR);
        File fonts = new File(context.getFilesDir(), FONTS_DIR);
        //noinspection ResultOfMethodCallIgnored
        sharedPrefs.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        profiles.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        fonts.mkdirs();
        int restored = 0;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(zipFile.openInputStream()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (!categories.contains(classify(name))) {
                    continue;
                }
                File target = null;
                if (name.startsWith(SP_DIR + "/")) {
                    String base = safeBaseName(name.substring(SP_DIR.length() + 1));
                    if (base.endsWith(".xml")) {
                        target = new File(sharedPrefs, base);
                    }
                } else if (name.startsWith(PROFILES_DIR + "/")) {
                    target = new File(profiles, safeBaseName(name.substring(PROFILES_DIR.length() + 1)));
                } else if (name.startsWith(FONTS_DIR + "/")) {
                    target = new File(fonts, safeBaseName(name.substring(FONTS_DIR.length() + 1)));
                }
                if (target == null) {
                    continue;
                }
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(target))) {
                    copy(zis, os);
                }
                ++restored;
            }
        }
        return restored;
    }

    /** Reject zip entries that would escape the target directory (zip-slip). */
    @NonNull
    private static String safeBaseName(@NonNull String name) throws IOException {
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IOException("Unsafe zip entry name: " + name);
        }
        return name;
    }

    private static void addEntry(@NonNull ZipOutputStream zos, @NonNull File file, @NonNull String entryName)
            throws IOException {
        zos.putNextEntry(new ZipEntry(entryName));
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            copy(in, zos);
        }
        zos.closeEntry();
    }

    private static void copy(@NonNull InputStream in, @NonNull OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }
}
