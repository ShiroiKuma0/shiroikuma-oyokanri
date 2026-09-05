// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.appdata.self;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.EnumSet;
import java.util.Set;

import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.settings.SecurityPrefGuard;
import io.github.muntashirakon.AppManager.settings.SettingsBackupManager;
import io.github.muntashirakon.io.Paths;

/**
 * Fork (白い熊, +134): 応用管理's own state, restored on the next start rather than on the spot.
 *
 * <p><b>LANDMINE — importing into a running app clobbers itself.</b>
 * {@link SettingsBackupManager#importFrom} replaces {@code shared_prefs/*.xml} on disk, but this
 * process still holds those files cached: on an orderly shutdown the framework writes the cached
 * map back over what was just imported, and the restore silently did nothing. That is why the
 * Export/Import panel hard-kills the process after an import rather than merely finishing.
 *
 * <p>A restore cannot take that way out. The batch runs <em>in this process</em>
 * ({@code BatchOpsService}), so killing ourselves would abort the very operation carrying the
 * restore. So the archive is <b>staged</b> in {@code files/} and applied later, through exactly
 * the flow that already handles this hazard: {@link #apply} writes the files and the caller
 * hard-kills, the same pair the Export/Import panel uses.
 *
 * <p><b>And it is applied on an explicit tap, not silently at startup.</b> Applying it in
 * {@code Application.attachBaseContext} looked tempting — nothing has read a preference yet — but
 * {@link SecurityPrefGuard}, which must run on a restore more than anywhere else, reads the
 * current values through {@code AppPref} to decide what an archive may not weaken. That single
 * read creates the cached {@code SharedPreferences} for the file about to be replaced, and the
 * next {@code apply()} anywhere in the app writes the stale map back over the import. A restart
 * that the reader asked for is worth more than one they did not.
 *
 * <p>The staged file survives a self-reinstall for the same reason the data directory does.
 */
public final class PendingStateImport {
    private static final String TAG = "PendingStateImport";
    private static final String FILE_NAME = "pending_state_import.zip";
    private static final String PREF_FILE = "shiroikuma_pending_import";
    private static final String KEY_CATEGORIES = "categories";

    private PendingStateImport() {
    }

    @NonNull
    public static File stagedFile(@NonNull Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    /**
     * Take the archive now, apply it later.
     *
     * @param categories the categories to restore, by wire id, comma separated; empty for all
     * @return the number of bytes staged
     */
    public static long stage(@NonNull Context context, @NonNull InputStream source,
                             @Nullable String categories) throws IOException {
        File target = stagedFile(context);
        long total = 0;
        // Written whole before the marker is set: a half-copied archive with a marker beside it
        // would be applied on the next start and would take the settings with it.
        File temp = new File(target.getParentFile(), FILE_NAME + ".part");
        try (OutputStream os = new FileOutputStream(temp)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = source.read(buffer)) > 0) {
                os.write(buffer, 0, read);
                total += read;
            }
        }
        //noinspection ResultOfMethodCallIgnored
        target.delete();
        if (!temp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IOException("could not stage the imported state");
        }
        prefs(context).edit().putString(KEY_CATEGORIES, categories == null ? "" : categories).apply();
        return total;
    }

    /** Whether a restored copy of this app's own state is waiting to be applied. */
    public static boolean isPending(@NonNull Context context) {
        return stagedFile(context).exists();
    }

    /**
     * Write the staged archive over this app's settings.
     *
     * <p>The caller <b>must</b> hard-kill the process afterwards — see the class note. The marker
     * is cleared whatever happens, so an archive that cannot be read is not retried for ever.
     *
     * @return the number of files restored
     */
    public static int apply(@NonNull Context context) throws IOException {
        File staged = stagedFile(context);
        SharedPreferences prefs = prefs(context);
        String categories = prefs.getString(KEY_CATEGORIES, "");
        prefs.edit().remove(KEY_CATEGORIES).apply();
        try {
            int restored = SettingsBackupManager.importFrom(context, Paths.get(staged),
                    parseCategories(categories));
            Log.d(TAG, "applied the staged state import: %d files", restored);
            return restored;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            staged.delete();
        }
    }

    /** Throw the staged archive away without applying it. */
    public static void discard(@NonNull Context context) {
        prefs(context).edit().remove(KEY_CATEGORIES).apply();
        //noinspection ResultOfMethodCallIgnored
        stagedFile(context).delete();
    }

    @NonNull
    private static Set<SettingsBackupManager.Category> parseCategories(@Nullable String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return SettingsBackupManager.allCategories();
        }
        Set<SettingsBackupManager.Category> out = EnumSet.noneOf(SettingsBackupManager.Category.class);
        for (String raw : csv.split(",")) {
            SettingsBackupManager.Category category = SettingsBackupManager.Category.byId(raw.trim());
            if (category != null) {
                out.add(category);
            }
        }
        return out.isEmpty() ? SettingsBackupManager.allCategories() : out;
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }
}
