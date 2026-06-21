// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.fonts;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * Fork: app-icon size (dp) for the main app list. Read at bind time in
 * {@link io.github.muntashirakon.AppManager.main.MainRecyclerAdapter}, which
 * sizes the icon, widens the icon column to match, and scales the snowflake +
 * force-stop ✕ glyphs under the icon proportionally (so the pair always fits
 * with a gap, at any icon size). Default mirrors the original
 * {@code @dimen/main_list_icon_size}. Dedicated SharedPreferences file, so
 * settings export/import covers it automatically. {@link #consumeChanged()}
 * mirrors the other fork prefs so the main list re-binds on resume after a
 * change (the size is read at bind time).
 */
public final class MainIconPrefs {

    private MainIconPrefs() {}

    private static final String PREFS_NAME = "shiroikuma_main_icon";
    private static final String KEY_SIZE = "size_dp";

    public static final int DEFAULT_SIZE_DP = 60;   // = @dimen/main_list_icon_size
    public static final int MIN_SIZE_DP = 40;
    public static final int MAX_SIZE_DP = 96;

    private static volatile boolean sChanged = false;

    public static boolean consumeChanged() {
        boolean c = sChanged;
        sChanged = false;
        return c;
    }

    @NonNull
    private static SharedPreferences sp(@NonNull Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static int getSizeDp(@NonNull Context ctx) {
        return sp(ctx).getInt(KEY_SIZE, DEFAULT_SIZE_DP);
    }

    public static void setSizeDp(@NonNull Context ctx, int dp) {
        if (dp < MIN_SIZE_DP) dp = MIN_SIZE_DP;
        if (dp > MAX_SIZE_DP) dp = MAX_SIZE_DP;
        sp(ctx).edit().putInt(KEY_SIZE, dp).apply();
        sChanged = true;
    }
}
