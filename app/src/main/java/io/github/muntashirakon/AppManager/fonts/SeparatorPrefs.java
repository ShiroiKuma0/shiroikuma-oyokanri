// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.fonts;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * Fork: widths for the main-list separator grid (the straight lines drawn
 * between app cells now that the cells touch edge-to-edge). Colours live in
 * {@link ColorPrefs} ({@link ColorPrefs#SEPARATOR_H} / {@link ColorPrefs#SEPARATOR_V});
 * this carries the two widths in dp (0 = no separator, default 0.5).
 * Dedicated SharedPreferences file, so settings export/import covers it
 * automatically. {@link #consumeChanged()} mirrors FontPrefs/ColorPrefs so
 * the main list refreshes its decoration on resume after a change.
 */
public final class SeparatorPrefs {

    private SeparatorPrefs() {}

    private static final String PREFS_NAME = "shiroikuma_separators";
    private static final String KEY_H_WIDTH = "h_width_dp";
    private static final String KEY_V_WIDTH = "v_width_dp";

    public static final float DEFAULT_WIDTH_DP = 0.5f;
    public static final float MAX_WIDTH_DP = 8f;

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

    /** Separator width in dp. {@code horizontal} = the line between rows. */
    public static float getWidthDp(@NonNull Context ctx, boolean horizontal) {
        return sp(ctx).getFloat(horizontal ? KEY_H_WIDTH : KEY_V_WIDTH, DEFAULT_WIDTH_DP);
    }

    public static void setWidthDp(@NonNull Context ctx, boolean horizontal, float dp) {
        if (dp < 0f) dp = 0f;
        if (dp > MAX_WIDTH_DP) dp = MAX_WIDTH_DP;
        sp(ctx).edit().putFloat(horizontal ? KEY_H_WIDTH : KEY_V_WIDTH, dp).apply();
        sChanged = true;
    }
}
