// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.fonts;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * Fork (白い熊, +116): how the batch operation log is drawn — text size, and whether the
 * timestamp column is shown at all. Colours live in {@link ColorPrefs} with every other colour
 * in the fork.
 *
 * <p>Its own preferences file, so it travels in Export/Import with no serialisation of its own;
 * classified APPEARANCE in {@code SettingsBackupManager}, since that is what it is.
 */
public final class OpLogPrefs {
    private OpLogPrefs() {
    }

    public static final String PREF_FILE = "shiroikuma_oplog";

    private static final String KEY_TEXT_SIZE = "text_size_sp";
    private static final String KEY_TIMESTAMPS = "timestamps";

    /** Small by default: the log is read as a block, and density is what makes it readable. */
    public static final int DEFAULT_TEXT_SIZE_SP = 12;
    public static final int MIN_TEXT_SIZE_SP = 8;
    public static final int MAX_TEXT_SIZE_SP = 24;

    private static volatile boolean sChanged = false;

    public static boolean consumeChanged() {
        boolean c = sChanged;
        sChanged = false;
        return c;
    }

    @NonNull
    private static SharedPreferences sp(@NonNull Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    public static int getTextSizeSp(@NonNull Context ctx) {
        int v = sp(ctx).getInt(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE_SP);
        return Math.max(MIN_TEXT_SIZE_SP, Math.min(MAX_TEXT_SIZE_SP, v));
    }

    public static void setTextSizeSp(@NonNull Context ctx, int sp) {
        sp(ctx).edit().putInt(KEY_TEXT_SIZE, Math.max(MIN_TEXT_SIZE_SP, Math.min(MAX_TEXT_SIZE_SP, sp))).apply();
        sChanged = true;
    }

    public static boolean showTimestamps(@NonNull Context ctx) {
        return sp(ctx).getBoolean(KEY_TIMESTAMPS, true);
    }

    public static void setShowTimestamps(@NonNull Context ctx, boolean show) {
        sp(ctx).edit().putBoolean(KEY_TIMESTAMPS, show).apply();
        sChanged = true;
    }
}
