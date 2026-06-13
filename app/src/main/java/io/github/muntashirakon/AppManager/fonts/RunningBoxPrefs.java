// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.fonts;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * Fork: border width (dp) of the running/active main-list app box — the 1dp
 * hairline outline restored for installed-and-active apps (yellow = user,
 * orange = system; colours live in {@link ColorPrefs#STROKE_USER} /
 * {@link ColorPrefs#STROKE_SYSTEM}). Default is deliberately thicker than the
 * original hairline so live apps stand out. 0 = no box. Dedicated
 * SharedPreferences file, so settings export/import covers it automatically.
 * {@link #consumeChanged()} mirrors the other fork prefs so the main list
 * re-binds on resume after a change (the width is read at bind time).
 */
public final class RunningBoxPrefs {

    private RunningBoxPrefs() {}

    private static final String PREFS_NAME = "shiroikuma_running_box";
    private static final String KEY_WIDTH = "width_dp";
    private static final String KEY_RADIUS = "radius_dp";

    public static final float DEFAULT_WIDTH_DP = 2.5f;
    public static final float MAX_WIDTH_DP = 8f;
    public static final int DEFAULT_RADIUS_DP = 0;   // square, matching the edge-to-edge grid
    public static final int MAX_RADIUS_DP = 24;

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

    /** Corner radius (dp) of the running-app box. 0 = square (default). */
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
