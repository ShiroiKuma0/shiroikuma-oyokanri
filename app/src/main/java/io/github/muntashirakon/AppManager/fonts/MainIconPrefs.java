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
 * change (both values are read at bind time).
 * <p>
 * Roundness is a percentage of the icon size (0 = square corners, 50 = a full
 * circle), so it scales with the icon size; the adapter clips the icon to a
 * rounded rect of {@code iconPx * percent / 100}.
 */
public final class MainIconPrefs {

    private MainIconPrefs() {}

    private static final String PREFS_NAME = "shiroikuma_main_icon";
    private static final String KEY_SIZE = "size_dp";
    private static final String KEY_ROUNDNESS = "roundness_pct";

    public static final int DEFAULT_SIZE_DP = 60;   // = @dimen/main_list_icon_size
    public static final int MIN_SIZE_DP = 40;
    public static final int MAX_SIZE_DP = 96;

    public static final int DEFAULT_ROUNDNESS_PCT = 0;   // square, matching the old look
    public static final int MIN_ROUNDNESS_PCT = 0;
    public static final int MAX_ROUNDNESS_PCT = 50;      // 50% of the icon = circle

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

    /** Corner roundness as a percentage of the icon size (0 = square, 50 = circle). */
    public static int getRoundnessPercent(@NonNull Context ctx) {
        return sp(ctx).getInt(KEY_ROUNDNESS, DEFAULT_ROUNDNESS_PCT);
    }

    public static void setRoundnessPercent(@NonNull Context ctx, int pct) {
        if (pct < MIN_ROUNDNESS_PCT) pct = MIN_ROUNDNESS_PCT;
        if (pct > MAX_ROUNDNESS_PCT) pct = MAX_ROUNDNESS_PCT;
        sp(ctx).edit().putInt(KEY_ROUNDNESS, pct).apply();
        sChanged = true;
    }
}
