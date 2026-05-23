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
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import io.github.muntashirakon.io.Path;

/**
 * Fork: export and import of App Manager's own settings (as opposed to app
 * backups). Captures everything that lives in the app's {@code shared_prefs}
 * directory — the main {@code preferences} store (all options/toggles), the
 * fork's dedicated colour/font prefs, the backup-directory and backup-options
 * prefs, etc. — plus all saved profiles ({@code files/profiles/*.am.json}).
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
    private static final FileFilter XML_FILTER = f -> f.isFile() && f.getName().endsWith(".xml");

    private SettingsBackupManager() {
    }

    /**
     * Zip the current settings into {@code destDir}.
     *
     * @return the display name of the created archive.
     */
    @NonNull
    public static String export(@NonNull Context context, @NonNull Path destDir) throws IOException {
        String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date());
        String fileName = EXPORT_PREFIX + ts + EXPORT_EXT;
        // The name already carries the .zip extension, so pass a null mime
        // type — otherwise findOrCreateFile appends another ".zip" (the Path
        // API adds an extension from the mime type when one is given).
        Path outFile = destDir.findOrCreateFile(fileName, null);
        File sharedPrefs = new File(context.getApplicationInfo().dataDir, SP_DIR);
        File profiles = new File(context.getFilesDir(), PROFILES_DIR);
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(outFile.openOutputStream()))) {
            File[] prefFiles = sharedPrefs.isDirectory() ? sharedPrefs.listFiles(XML_FILTER) : null;
            if (prefFiles != null) {
                for (File f : prefFiles) {
                    addEntry(zos, f, SP_DIR + "/" + f.getName());
                }
            }
            File[] profileFiles = profiles.isDirectory() ? profiles.listFiles(File::isFile) : null;
            if (profileFiles != null) {
                for (File f : profileFiles) {
                    addEntry(zos, f, PROFILES_DIR + "/" + f.getName());
                }
            }
        }
        return fileName;
    }

    /**
     * Restore settings from a previously exported archive. Only rewrites the
     * on-disk files; the caller must restart the process for the changes to
     * take effect.
     *
     * @return the number of files restored.
     */
    public static int importFrom(@NonNull Context context, @NonNull Path zipFile) throws IOException {
        File sharedPrefs = new File(context.getApplicationInfo().dataDir, SP_DIR);
        File profiles = new File(context.getFilesDir(), PROFILES_DIR);
        //noinspection ResultOfMethodCallIgnored
        sharedPrefs.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        profiles.mkdirs();
        int restored = 0;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(zipFile.openInputStream()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                File target = null;
                if (name.startsWith(SP_DIR + "/")) {
                    String base = safeBaseName(name.substring(SP_DIR.length() + 1));
                    if (base.endsWith(".xml")) {
                        target = new File(sharedPrefs, base);
                    }
                } else if (name.startsWith(PROFILES_DIR + "/")) {
                    target = new File(profiles, safeBaseName(name.substring(PROFILES_DIR.length() + 1)));
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
