// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.appdata.self;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.settings.Prefs;
import io.github.muntashirakon.AppManager.settings.SettingsBackupManager;
import io.github.muntashirakon.io.Path;
import io.github.muntashirakon.io.Paths;

/**
 * Fork (白い熊, +154): everything a clean phone needs to become this phone again.
 *
 * <p><b>The problem it solves is circular.</b> Restoring 応用管理's own state through the app
 * backup would need Shizuku or ADB working first; getting those working needs 白い熊 雫; finding
 * 雫 needs the app configured; configuring it needs the restore. So the bootstrap does not ride
 * on the backups at all — it rides on the <b>settings export</b>, which needs no privileges to
 * write or to read back, and on two plain APKs deposited where a file manager can reach them.
 *
 * <p>The kit lives at the root of the backup directory and is refreshed at the end of every
 * backup run — not only when 応用管理 backs itself up, because a kit older than the archives
 * beside it is a kit that restores the wrong phone.
 *
 * <pre>
 *   shiroikuma-oyokanri_&lt;version&gt;_arm64-v8a.apk   install second
 *   shiroikuma-shizuku_&lt;version&gt;_arm64-v8a.apk    install FIRST - phase 0
 *   shiroikuma-oyokanri_state.zip                  every category, no privileges needed
 *   shiroikuma-oyokanri_migration.json             the marker the scan looks for
 * </pre>
 *
 * <p><b>Pruning is 白い熊's explicit decision</b> (2026-09-06), against the standing rule that a
 * build artefact is never deleted: when a new APK is deposited the previous one goes. It happens
 * only after the new file is written and its length verified against the source, so a failed copy
 * can never leave neither — and it matches only <b>this kit's own deposit shape</b> for the
 * package being deposited: the package's slug, then anything, then the ABI suffix. Nothing else
 * in the directory can match, the state archive and the marker included.
 */
public final class MigrationKit {
    public static final String TAG = "MigrationKit";

    /** What the scan looks for, and the only file that identifies a kit. */
    public static final String MARKER = "shiroikuma-oyokanri_migration.json";
    /**
     * The name the marker had in +154, before it was renamed to match the family's file naming
     * (白い熊, 2026-09-06). Still recognised when finding a kit, so a kit written by that build
     * is not silently invisible, and removed when a kit is rewritten — it is bookkeeping of ours
     * that a rename superseded, not a build artefact.
     */
    private static final String LEGACY_MARKER = "oyokanri-migration.json";
    public static final String STATE_ZIP = "shiroikuma-oyokanri_state.zip";
    public static final String SHIZUKU_PACKAGE = "shiroikuma.shizuku";
    /** The one ABI this family ships; the deposited names say so (白い熊, 2026-09-06). */
    private static final String ABI_SUFFIX = "_arm64-v8a.apk";
    private static final int KIT_FORMAT = 1;

    /** Written beside the app's own files after a restore, and read once on the next start. */
    private static final String REPORT_FILE = "migration_report.txt";
    /** Device-local: whether the offer has been made on THIS phone. */
    public static final String PREF_FILE = "shiroikuma_migration";
    private static final String KEY_OFFER_DONE = "offer_done";

    private MigrationKit() {
    }

    // -- Writing -------------------------------------------------------------

    /** Refresh the kit. Never throws: a backup run must not fail because of it. */
    @WorkerThread
    public static void writeQuietly(@NonNull Context context) {
        try {
            write(context);
        } catch (Throwable th) {
            Log.w(TAG, "Could not write the migration kit: %s", th, th.getMessage());
        }
    }

    /** Write or refresh the kit, and return the directory it was written to. */
    @WorkerThread
    @NonNull
    public static Path write(@NonNull Context context) throws IOException {
        Context appContext = context.getApplicationContext();
        Path root = Prefs.Storage.getAppManagerDirectory();
        JSONObject previous = readMarker(root);
        JSONObject marker = new JSONObject();
        try {
            marker.put("kit", KIT_FORMAT);
            marker.put("written", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                    .format(new Date()));
            marker.put("app", depositApk(appContext, root, BuildConfig.APPLICATION_ID,
                    section(previous, "app")));
            JSONObject shizuku = depositApk(appContext, root, SHIZUKU_PACKAGE,
                    section(previous, "shizuku"));
            if (shizuku != null) {
                marker.put("shizuku", shizuku);
            }
            marker.put("state", writeState(appContext, root));
            marker.put("steps", steps());
            Path markerFile = root.findOrCreateFile(MARKER, null);
            try (OutputStream out = markerFile.openOutputStream()) {
                out.write(marker.toString(2).getBytes());
            }
            Path legacy = root.findFileOrNull(LEGACY_MARKER);
            if (legacy != null && legacy.delete()) {
                Log.d(TAG, "Removed the pre-rename marker %s", LEGACY_MARKER);
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return root;
    }

    /**
     * Copy one installed app's base APK into the kit, and prune the predecessor the previous
     * marker named. Returns the JSON section describing it, or {@code null} when the app is not
     * installed — 雫 on a phone that has not got it yet.
     */
    @Nullable
    private static JSONObject depositApk(@NonNull Context context, @NonNull Path root,
                                         @NonNull String packageName,
                                         @Nullable JSONObject previous) throws IOException {
        PackageInfo packageInfo;
        try {
            packageInfo = context.getPackageManager().getPackageInfo(packageName, 0);
        } catch (Throwable th) {
            return null;
        }
        ApplicationInfo applicationInfo = packageInfo.applicationInfo;
        if (applicationInfo == null || applicationInfo.sourceDir == null) {
            return null;
        }
        File source = new File(applicationInfo.sourceDir);
        if (!source.isFile()) {
            return null;
        }
        String version = packageInfo.versionName == null ? "0" : packageInfo.versionName;
        String name = slug(packageName) + "_" + version + ABI_SUFFIX;
        String previousName = previous == null ? null : previous.optString("apk", "");
        Path existing = root.findFileOrNull(name);
        if (existing == null || existing.length() != source.length()) {
            Path target = root.findOrCreateFile(name, null);
            long copied;
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = target.openOutputStream()) {
                copied = copy(in, out);
            }
            if (copied != source.length()) {
                // A short copy is worse than none: it would install as a corrupt file on the one
                // phone that has nothing else to fall back on.
                target.delete();
                throw new IOException("short copy of " + name);
            }
            Log.d(TAG, "Deposited %s (%d bytes)", name, copied);
        }
        // Only now, with the new file written and its length verified, do the old ones go.
        //
        // It sweeps the kit directory for OUR OWN deposit shape for this package rather than
        // trusting the previous marker's record, because that record is exactly what a rename,
        // a lost write or a hand-deleted marker takes away — and then the leftover APK is
        // orphaned for ever (+156). The pattern is narrow on purpose: the package's own slug,
        // and the ABI suffix this kit writes, so the state archive and the marker beside it can
        // never match, and neither can a file with any other name.
        String prefix = slug(packageName) + "_";
        Path[] siblings = root.listFiles();
        if (siblings != null) {
            for (Path sibling : siblings) {
                String siblingName = sibling.getName();
                if (siblingName.equals(name) || !siblingName.startsWith(prefix)
                        || !siblingName.endsWith(ABI_SUFFIX)) {
                    continue;
                }
                if (sibling.delete()) {
                    Log.d(TAG, "Pruned %s", siblingName);
                }
            }
        }
        if (previousName != null && !previousName.isEmpty() && !previousName.equals(name)) {
            Path stale = root.findFileOrNull(previousName);
            if (stale != null && stale.delete()) {
                Log.d(TAG, "Pruned the recorded %s", previousName);
            }
        }
        JSONObject section = new JSONObject();
        try {
            section.put("package", packageName);
            section.put("version", version);
            section.put("apk", name);
            section.put("bytes", source.length());
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return section;
    }

    /** The full settings export - every category, and deliberately never encrypted. */
    @NonNull
    private static JSONObject writeState(@NonNull Context context, @NonNull Path root)
            throws IOException {
        Path zip = root.findOrCreateFile(STATE_ZIP, null);
        int files;
        try (OutputStream out = zip.openOutputStream()) {
            files = SettingsBackupManager.writeExport(context,
                    EnumSet.allOf(SettingsBackupManager.Category.class), out, null);
        }
        JSONObject section = new JSONObject();
        try {
            section.put("file", STATE_ZIP);
            section.put("files", files);
            section.put("bytes", zip.length());
            JSONArray categories = new JSONArray();
            for (SettingsBackupManager.Category c : SettingsBackupManager.Category.values()) {
                categories.put(c.id);
            }
            section.put("categories", categories);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return section;
    }

    @NonNull
    private static JSONArray steps() {
        JSONArray steps = new JSONArray();
        steps.put("0. Install the shiroikuma-shizuku APK in this directory, set up Shizuku, and "
                + "make it Device Owner by adb - before adding any account to the phone.");
        steps.put("1. Install the shiroikuma-oyokanri APK in this directory.");
        steps.put("2. Open it, grant all-files access, and accept the restore it offers. "
                + "It restarts itself.");
        steps.put("3. Restore the other apps from the backups in this directory. Settings for an "
                + "app that is not installed yet are applied when it is.");
        return steps;
    }

    // -- Finding -------------------------------------------------------------

    /**
     * The kit's directory, or {@code null}.
     *
     * <p>The configured backup directory is asked first, because on a phone that is already set
     * up that is the answer. Then the scan: the external storage root and two levels under it -
     * two, because the real one is two deep - and every {@code /storage/*} volume for cards.
     * {@code Android/} is skipped: it is enormous, access-restricted, and never where a backup
     * directory is put.
     *
     * <p>It needs all-files access to see anything at all, so it is only worth running once that
     * has been granted.
     */
    @WorkerThread
    @Nullable
    public static Path find(@NonNull Context context) {
        String configured = Prefs.Storage.getBackupDirectory();
        if (!configured.isEmpty()) {
            Path path = Paths.get(configured);
            if (path.findFileOrNull(MARKER) != null || path.findFileOrNull(LEGACY_MARKER) != null) {
                return path;
            }
        }
        List<File> roots = new ArrayList<>();
        roots.add(android.os.Environment.getExternalStorageDirectory());
        File[] volumes = new File("/storage").listFiles();
        if (volumes != null) {
            for (File volume : volumes) {
                String name = volume.getName();
                if (volume.isDirectory() && !"emulated".equals(name) && !"self".equals(name)) {
                    roots.add(volume);
                }
            }
        }
        for (File root : roots) {
            File found = scan(root, 2);
            if (found != null) {
                return Paths.get(found);
            }
        }
        return null;
    }

    @Nullable
    private static File scan(@Nullable File dir, int depth) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        if (new File(dir, MARKER).isFile() || new File(dir, LEGACY_MARKER).isFile()) {
            return dir;
        }
        if (depth <= 0) {
            return null;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            String name = child.getName();
            if (!child.isDirectory() || "Android".equals(name) || name.startsWith(".")) {
                continue;
            }
            File found = scan(child, depth - 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // -- Restoring -----------------------------------------------------------

    /**
     * Import the kit's state and leave the report behind for the next start.
     *
     * <p>The caller hard-kills the process straight after - the same rule the Export/Import panel
     * and {@link PendingStateImport} live by: this process holds the preference files it has just
     * replaced, and an orderly shutdown writes the cached maps back over them.
     */
    @WorkerThread
    public static void restore(@NonNull Context context, @NonNull Path kitDir) throws IOException {
        Path zip = kitDir.findFileOrNull(STATE_ZIP);
        if (zip == null) {
            throw new IOException("no state archive in the kit");
        }
        SettingsBackupManager.importFrom(context, zip,
                EnumSet.allOf(SettingsBackupManager.Category.class));
        String report = buildReport(context);
        File file = new File(context.getFilesDir(), REPORT_FILE);
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(report.getBytes());
        }
    }

    /** The report from the last restore, read once and then removed. */
    @Nullable
    public static String consumeReport(@NonNull Context context) {
        File file = new File(context.getFilesDir(), REPORT_FILE);
        if (!file.isFile()) {
            return null;
        }
        String content = read(file);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        return content;
    }

    /**
     * What landed, counted from the files on disk rather than through
     * {@link SharedPreferences} - this process still holds the pre-import maps, so asking it
     * would report the state that was just replaced. Same reasoning, and the same
     * regex-on-the-XML technique, as {@code SecurityPrefGuard}.
     *
     * <p>Format is one line of fields, rendered by the caller:
     * {@code profiles notes snoopingDecisions snoopingPackagesMissing dir dirExists}.
     */
    @WorkerThread
    @NonNull
    private static String buildReport(@NonNull Context context) {
        File prefsDir = new File(context.getApplicationInfo().dataDir, "shared_prefs");
        int profiles = countFiles(new File(context.getFilesDir(), "profiles"), ".am.json");
        int notes = countKeys(new File(prefsDir, "shiroikuma_notes.xml"), "<string name=\"");
        List<String> snoopingPackages = keySuffixes(new File(prefsDir, "shiroikuma_snooping.xml"),
                "<string name=\"pkg:");
        int missing = 0;
        PackageManager pm = context.getPackageManager();
        for (String packageName : snoopingPackages) {
            try {
                pm.getPackageInfo(packageName, 0);
            } catch (Throwable th) {
                ++missing;
            }
        }
        // The restored backup directory, and whether it is a path that exists on THIS phone - the
        // one field of an import that can be wrong through no fault of the archive.
        String dir = firstValue(new File(prefsDir, "am_backup_directory.xml"), "path");
        if (dir == null) {
            dir = "";
        }
        return profiles + " " + notes + " " + snoopingPackages.size() + " " + missing + " "
                + (!dir.isEmpty() && new File(dir).isDirectory() ? "1" : "0") + " " + dir;
    }

    /** Split a report line into its fields: the last one is the path and may contain spaces. */
    @Nullable
    public static String[] parseReport(@Nullable String report) {
        if (report == null) {
            return null;
        }
        String[] parts = report.trim().split(" ", 6);
        return parts.length >= 5 ? parts : null;
    }

    private static int countFiles(@NonNull File dir, @NonNull String suffix) {
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        int n = 0;
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(suffix)) {
                ++n;
            }
        }
        return n;
    }

    private static int countKeys(@NonNull File xml, @NonNull String needle) {
        String content = read(xml);
        if (content == null) {
            return 0;
        }
        int n = 0;
        int at = content.indexOf(needle);
        while (at >= 0) {
            ++n;
            at = content.indexOf(needle, at + needle.length());
        }
        return n;
    }

    @NonNull
    private static List<String> keySuffixes(@NonNull File xml, @NonNull String needle) {
        List<String> out = new ArrayList<>();
        String content = read(xml);
        if (content == null) {
            return out;
        }
        int at = content.indexOf(needle);
        while (at >= 0) {
            int start = at + needle.length();
            int end = content.indexOf('"', start);
            if (end < 0) {
                break;
            }
            out.add(content.substring(start, end));
            at = content.indexOf(needle, end);
        }
        return out;
    }

    @Nullable
    private static String firstValue(@NonNull File xml, @NonNull String key) {
        String content = read(xml);
        if (content == null) {
            return null;
        }
        String needle = "name=\"" + key + "\">";
        int at = content.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int start = at + needle.length();
        int end = content.indexOf('<', start);
        return end < 0 ? null : content.substring(start, end);
    }

    @Nullable
    private static String read(@NonNull File file) {
        if (!file.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] raw = new byte[(int) file.length()];
            int read = in.read(raw);
            return read <= 0 ? "" : new String(raw, 0, read);
        } catch (Throwable th) {
            return null;
        }
    }

    // -- The offer -----------------------------------------------------------

    /**
     * Whether this install has nothing of 白い熊's own in it yet - no profiles, no notes, no
     * snooping decisions. That, and not "first run", is what makes the offer safe: a phone that
     * has been used never sees it, whatever else is on the storage.
     */
    public static boolean looksUnconfigured(@NonNull Context context) {
        File prefsDir = new File(context.getApplicationInfo().dataDir, "shared_prefs");
        return countFiles(new File(context.getFilesDir(), "profiles"), ".am.json") == 0
                && !new File(prefsDir, "shiroikuma_notes.xml").isFile()
                && !new File(prefsDir, "shiroikuma_snooping.xml").isFile();
    }

    public static boolean isOfferDone(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_OFFER_DONE, false);
    }

    public static void setOfferDone(@NonNull Context context) {
        prefs(context).edit().putBoolean(KEY_OFFER_DONE, true).apply();
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    // -- Helpers -------------------------------------------------------------

    @Nullable
    private static JSONObject readMarker(@NonNull Path root) {
        Path marker = root.findFileOrNull(MARKER);
        if (marker == null) {
            // The +154 name. Reading it matters as much as finding a kit by it: without this the
            // rename itself lost the record of the previous APK, and the deposit that followed
            // had nothing to prune (白い熊 caught it, +156).
            marker = root.findFileOrNull(LEGACY_MARKER);
        }
        if (marker == null) {
            return null;
        }
        try (InputStream in = marker.openInputStream()) {
            byte[] raw = new byte[(int) marker.length()];
            int read = in.read(raw);
            return read <= 0 ? null : new JSONObject(new String(raw, 0, read));
        } catch (Throwable th) {
            return null;
        }
    }

    @Nullable
    private static JSONObject section(@Nullable JSONObject marker, @NonNull String name) {
        return marker == null ? null : marker.optJSONObject(name);
    }

    /** {@code shiroikuma.oyokanri} to {@code shiroikuma-oyokanri}. */
    @NonNull
    private static String slug(@NonNull String packageName) {
        return packageName.replace('.', '-');
    }

    private static long copy(@NonNull InputStream in, @NonNull OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
            total += read;
        }
        out.flush();
        return total;
    }
}
