// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// Fork: free-text per-app notes (e.g. "do not freeze — needed for X").
// Stored in a dedicated SharedPreferences file (shiroikuma_notes.xml), keyed by
// package name, value = the note text. The dedicated file is deliberate: it lets
// SettingsBackupManager export/import the notes with ZERO extra code, because
// that exporter zips every shared_prefs/*.xml and the importer rewrites them
// before SIGKILL-restarting the process (so nothing flushes back over the
// imported copy). See SettingsBackupManager / BackupRestorePreferences.
//
// Limitation: notes are keyed by package name only, so a package installed under
// multiple Android users shares a single note. Per-user notes would key on
// "<pkg>:<userId>" instead — out of scope for now.
public final class AppNotesManager {
    private AppNotesManager() {}

    private static final String PREFS_NAME = "shiroikuma_notes"; // -> shared_prefs/shiroikuma_notes.xml

    @NonNull
    private static SharedPreferences sp(@NonNull Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** The stored note for {@code pkg}, or {@code null} if none. */
    @Nullable
    public static String getNote(@NonNull Context ctx, @NonNull String pkg) {
        return sp(ctx).getString(pkg, null);
    }

    /** True if {@code pkg} has a non-blank note. */
    public static boolean hasNote(@NonNull Context ctx, @NonNull String pkg) {
        String note = sp(ctx).getString(pkg, null);
        return note != null && !note.trim().isEmpty();
    }

    /**
     * Persist (or clear) the note for {@code pkg}. The text is trimmed; a blank
     * or empty result DELETES the note (the row's badge disappears and the "+"
     * affordance returns). Uses apply() like {@link io.github.muntashirakon.AppManager.fonts.ColorPrefs}:
     * the import path restarts via SIGKILL, never an orderly flush, so apply()
     * is safe.
     */
    public static void setNote(@NonNull Context ctx, @NonNull String pkg, @Nullable CharSequence text) {
        String trimmed = text == null ? "" : text.toString().trim();
        SharedPreferences.Editor editor = sp(ctx).edit();
        if (trimmed.isEmpty()) {
            editor.remove(pkg);
        } else {
            editor.putString(pkg, trimmed);
        }
        editor.apply();
    }

    /** Remove the note for {@code pkg}, if any. */
    public static void clear(@NonNull Context ctx, @NonNull String pkg) {
        sp(ctx).edit().remove(pkg).apply();
    }
}
