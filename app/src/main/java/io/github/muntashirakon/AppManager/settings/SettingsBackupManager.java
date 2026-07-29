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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.battery.BatteryPrefs;
import io.github.muntashirakon.AppManager.snooping.NetBlockState;
import io.github.muntashirakon.AppManager.snooping.SnoopingImmovable;
import io.github.muntashirakon.AppManager.snooping.SnoopingPrefs;
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
    /**
     * Fork: the family-wide backup file-name convention (白い熊, 2026-07-25) —
     * {@code <english-dash-separated-app-name>_<yyyy-MM-dd_HH-mm-ss>.zip}, no
     * version, no infix, no suffix. Every sister app writes into one shared
     * directory, so the names must sort and read uniformly.
     */
    public static final String EXPORT_PREFIX = "shiroikuma-oyokanri_";
    /** Pre-convention name, still recognised so older archives stay listed. */
    public static final String LEGACY_EXPORT_PREFIX = "AppManager-settings_";
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
        GENERAL("general", R.string.settings_eim_cat_general),
        APPEARANCE("appearance", R.string.settings_eim_cat_appearance),
        MONITOR("monitor", R.string.settings_eim_cat_monitor),
        TOOLBAR("toolbar", R.string.settings_eim_cat_toolbar),
        NOTES("notes", R.string.settings_eim_cat_notes),
        SNOOPING("snooping", R.string.settings_eim_cat_snooping),
        PROFILES("profiles", R.string.settings_eim_cat_profiles);

        /**
         * Stable wire id — what the automation contract's {@code items} extra
         * accepts and what {@code LIST_CATEGORIES} reports. Never rename these:
         * 自由作業盤's saved selections are keyed by them.
         */
        @NonNull
        public final String id;
        @StringRes
        public final int labelRes;

        Category(@NonNull String id, @StringRes int labelRes) {
            this.id = id;
            this.labelRes = labelRes;
        }

        /** The category with this wire id, or null if unknown. */
        @Nullable
        public static Category byId(@NonNull String id) {
            for (Category c : values()) {
                if (c.id.equals(id)) return c;
            }
            return null;
        }
    }

    /** Progress sink for the headless export path (real counts, never a %). */
    public interface ProgressListener {
        /**
         * @param current 1-based index of the category about to be written
         * @param total   number of selected categories
         * @param label   that category's human label
         */
        void onCategory(int current, int total, @NonNull String label);
    }

    /**
     * Prefs files that must never enter (or be restored from) an archive.
     * The automation token is a device-local secret; a backup that carried it
     * would hand out the gate key with the archive. The snooping "cannot be
     * turned on here" marks are a fact about <em>this</em> phone's app-ops
     * behaviour, not a decision, so carrying them to another phone could hide a
     * capability that is perfectly movable there. The battery sampler's state
     * is the previous raw counter reading from <em>this</em> phone at one
     * instant; restoring it elsewhere would make the next delta pure garbage.
     */
    private static final Set<String> EXCLUDED_PREFS = new HashSet<>(
            Arrays.asList(AutomationAuth.PREF_FILE, SnoopingImmovable.PREF_FILE, NetBlockState.PREF_FILE,
                    BatteryPrefs.STATE_PREF_FILE));

    // Shared-prefs stores per category; anything unlisted falls into GENERAL
    // (the main "preferences" store, backup dirs/options, and any future
    // prefs file we forget to classify — better over-carried than dropped).
    private static final Set<String> APPEARANCE_PREFS = new HashSet<>(Arrays.asList(
            "shiroikuma_colors", "shiroikuma_fonts", "shiroikuma_main_icon",
            "shiroikuma_main_layout", "shiroikuma_selection_frame",
            "shiroikuma_separators", "shiroikuma_running_box"));
    private static final Set<String> MONITOR_PREFS = new HashSet<>(Arrays.asList(
            "shiroikuma_monitor", "shiroikuma_monitor_sep", "shiroikuma_reaper",
            // Fork: battery-history *decisions* (sample or not, how often, how
            // long to keep) travel; the sampler's raw state does not — see
            // EXCLUDED_PREFS above.
            "shiroikuma_battery"));
    private static final Set<String> TOOLBAR_PREFS = new HashSet<>(Arrays.asList(
            "am_main_toolbar", "am_main_page_profile_filter"));
    private static final String NOTES_PREFS = "shiroikuma_notes";
    // Fork: the per-package anti-snooping decisions. Keyed by package name, so the
    // file is meaningful on a phone that does not (yet) have those apps — that is
    // the whole point of carrying it across.
    private static final String SNOOPING_PREFS = SnoopingPrefs.PREF_FILE;

    /** True for prefs files that are device-local and never travel in an archive. */
    private static boolean isExcluded(@NonNull String entryName) {
        String base = entryName.startsWith(SP_DIR + "/") ? entryName.substring(SP_DIR.length() + 1) : entryName;
        if (base.endsWith(".xml")) base = base.substring(0, base.length() - 4);
        return EXCLUDED_PREFS.contains(base);
    }

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
        if (SNOOPING_PREFS.equals(base)) return Category.SNOOPING;
        return Category.GENERAL;
    }

    private SettingsBackupManager() {
    }

    /** The archive name for an export made now, per the family convention. */
    @NonNull
    public static String newFileName() {
        String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date());
        return EXPORT_PREFIX + ts + EXPORT_EXT;
    }

    /** One file that would go into the archive, with the category it belongs to. */
    private static final class Source {
        final String entry;
        final File file;
        final Category category;

        Source(@NonNull String entry, @NonNull File file, @NonNull Category category) {
            this.entry = entry;
            this.file = file;
            this.category = category;
        }
    }

    /** Everything exportable right now, in no particular order. */
    @NonNull
    private static List<Source> collectSources(@NonNull Context context) {
        List<Source> sources = new ArrayList<>();
        File sharedPrefs = new File(context.getApplicationInfo().dataDir, SP_DIR);
        File[] prefFiles = sharedPrefs.isDirectory() ? sharedPrefs.listFiles(XML_FILTER) : null;
        if (prefFiles != null) {
            for (File f : prefFiles) {
                String entry = SP_DIR + "/" + f.getName();
                if (isExcluded(entry)) continue;
                sources.add(new Source(entry, f, classify(entry)));
            }
        }
        File profiles = new File(context.getFilesDir(), PROFILES_DIR);
        File[] profileFiles = profiles.isDirectory() ? profiles.listFiles(File::isFile) : null;
        if (profileFiles != null) {
            for (File f : profileFiles) {
                sources.add(new Source(PROFILES_DIR + "/" + f.getName(), f, Category.PROFILES));
            }
        }
        // The font *choice* lives in shiroikuma_fonts.xml (an APPEARANCE prefs
        // file); the referenced file must travel with it or the setting points
        // at nothing — so imported font files are APPEARANCE too.
        File fonts = new File(context.getFilesDir(), FONTS_DIR);
        File[] fontFiles = fonts.isDirectory() ? fonts.listFiles(File::isFile) : null;
        if (fontFiles != null) {
            for (File f : fontFiles) {
                sources.add(new Source(FONTS_DIR + "/" + f.getName(), f, Category.APPEARANCE));
            }
        }
        return sources;
    }

    /**
     * Fork: the headless export core — the single implementation both the
     * Export/Import panel and the automation receiver
     * ({@link StateExportReceiver}) call. Writes ONE zip covering the selected
     * categories into {@code out} (which the caller owns and closes),
     * reporting category-granular progress as it goes.
     *
     * @return the number of files written into the archive.
     */
    public static int writeExport(@NonNull Context context, @NonNull Set<Category> categories,
                                  @NonNull OutputStream out, @Nullable ProgressListener listener)
            throws IOException {
        List<Source> sources = collectSources(context);
        // Walk the categories in enum order so progress reads in a stable
        // sequence and the archive groups by category.
        List<Category> ordered = new ArrayList<>();
        for (Category c : Category.values()) {
            if (categories.contains(c)) ordered.add(c);
        }
        int written = 0;
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(out))) {
            for (int i = 0; i < ordered.size(); i++) {
                Category cat = ordered.get(i);
                if (listener != null) {
                    listener.onCategory(i + 1, ordered.size(), context.getString(cat.labelRes));
                }
                for (Source s : sources) {
                    if (s.category != cat) continue;
                    addEntry(zos, s.file, s.entry);
                    ++written;
                }
            }
        }
        return written;
    }

    /**
     * Zip the selected categories of the current settings into {@code destDir}.
     *
     * @return the display name of the created archive.
     */
    @NonNull
    public static String export(@NonNull Context context, @NonNull Path destDir,
                                @NonNull Set<Category> categories) throws IOException {
        String fileName = newFileName();
        // The name already carries the .zip extension, so pass a null mime
        // type — otherwise findOrCreateFile appends another ".zip" (the Path
        // API adds an extension from the mime type when one is given).
        Path outFile = destDir.findOrCreateFile(fileName, null);
        writeExport(context, categories, outFile.openOutputStream(), null);
        return fileName;
    }

    /** All categories — what an automation request with no {@code items} means. */
    @NonNull
    public static Set<Category> allCategories() {
        return EnumSet.allOf(Category.class);
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
                // Never let an archive plant an automation token on this device.
                if (isExcluded(name) || !categories.contains(classify(name))) {
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
