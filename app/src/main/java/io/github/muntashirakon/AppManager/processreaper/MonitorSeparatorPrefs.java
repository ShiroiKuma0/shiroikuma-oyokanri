// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.processreaper;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * Fork: widths (dp) for the process-monitor separator grid — horizontal lines
 * between rows and vertical lines between columns. Colours live in
 * {@link io.github.muntashirakon.AppManager.fonts.ColorPrefs#MONITOR_SEPARATOR_H}
 * / {@code MONITOR_SEPARATOR_V}. Independent of the main-list separators.
 * 0 = no line; default 0.5dp. Dedicated SharedPreferences file (export-covered).
 */
public final class MonitorSeparatorPrefs {
    private MonitorSeparatorPrefs() {}

    private static final String PREFS_NAME = "shiroikuma_monitor_sep";
    private static final String KEY_H_WIDTH = "h_width_dp";
    private static final String KEY_V_WIDTH = "v_width_dp";

    public static final float DEFAULT_WIDTH_DP = 0.5f;
    public static final float MAX_WIDTH_DP = 8f;

    @NonNull
    private static SharedPreferences sp(@NonNull Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** {@code horizontal} = the line between rows. */
    public static float getWidthDp(@NonNull Context ctx, boolean horizontal) {
        return sp(ctx).getFloat(horizontal ? KEY_H_WIDTH : KEY_V_WIDTH, DEFAULT_WIDTH_DP);
    }

    public static void setWidthDp(@NonNull Context ctx, boolean horizontal, float dp) {
        if (dp < 0f) dp = 0f;
        if (dp > MAX_WIDTH_DP) dp = MAX_WIDTH_DP;
        sp(ctx).edit().putFloat(horizontal ? KEY_H_WIDTH : KEY_V_WIDTH, dp).apply();
    }
}
