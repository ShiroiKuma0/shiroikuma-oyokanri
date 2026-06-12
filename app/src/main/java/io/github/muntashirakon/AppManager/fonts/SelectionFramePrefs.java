// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.fonts;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * Fork: the selected (checked) main-list card's frame — border width and
 * corner roundness in dp. The colour lives in {@link ColorPrefs}
 * ({@link ColorPrefs#SELECTED_FRAME}). Now that normal cells are square and
 * edge-to-edge, the selected card's look is fully owned by the adapter from
 * these prefs instead of the M3 checked-state theming: thick rounded yellow
 * by default (24dp matches the original listItemCornerRadius style value).
 * Dedicated SharedPreferences file, covered by settings export/import.
 */
public final class SelectionFramePrefs {

    private SelectionFramePrefs() {}

    private static final String PREFS_NAME = "shiroikuma_selection_frame";
    private static final String KEY_WIDTH = "width_dp";
    private static final String KEY_RADIUS = "radius_dp";

    public static final float DEFAULT_WIDTH_DP = 4f;
    public static final float MAX_WIDTH_DP = 8f;
    public static final int DEFAULT_RADIUS_DP = 24;
    public static final int MAX_RADIUS_DP = 32;

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

    public static float getWidthDp(@NonNull Context ctx) {
        return sp(ctx).getFloat(KEY_WIDTH, DEFAULT_WIDTH_DP);
    }

    public static void setWidthDp(@NonNull Context ctx, float dp) {
        if (dp < 0f) dp = 0f;
        if (dp > MAX_WIDTH_DP) dp = MAX_WIDTH_DP;
        sp(ctx).edit().putFloat(KEY_WIDTH, dp).apply();
        sChanged = true;
    }

    public static int getRadiusDp(@NonNull Context ctx) {
        return sp(ctx).getInt(KEY_RADIUS, DEFAULT_RADIUS_DP);
    }

    public static void setRadiusDp(@NonNull Context ctx, int dp) {
        if (dp < 0) dp = 0;
        if (dp > MAX_RADIUS_DP) dp = MAX_RADIUS_DP;
        sp(ctx).edit().putInt(KEY_RADIUS, dp).apply();
        sChanged = true;
    }
}
