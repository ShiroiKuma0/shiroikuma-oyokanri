// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.processreaper;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * Fork: layout prefs for the process monitor — column count (1/2/3, toggled
 * from the top bar) and the per-row app-icon size in dp (set on the UI page).
 * Dedicated SharedPreferences file, so settings export/import covers it.
 */
public final class MonitorPrefs {
    private MonitorPrefs() {}

    private static final String PREFS_NAME = "shiroikuma_monitor";
    private static final String KEY_COLUMNS = "columns";
    private static final String KEY_ICON_DP = "icon_dp";
    private static final String KEY_ROW_PAD_DP = "row_pad_dp";
    private static final String KEY_LEAK_COUNT = "leak_count";
    private static final String KEY_LEAK_AGE_SEC = "leak_age_sec";

    public static final int MIN_COLUMNS = 1;
    public static final int MAX_COLUMNS = 3;
    public static final int DEFAULT_ICON_DP = 40;
    public static final int MIN_ICON_DP = 24;
    public static final int MAX_ICON_DP = 72;
    public static final int DEFAULT_ROW_PAD_DP = 6;
    public static final int MAX_ROW_PAD_DP = 16;
    // Leak grouping: a cluster of N identical package-less shell commands. The
    // count threshold is tunable; known-transient comms keep a floor of 2.
    public static final int DEFAULT_LEAK_COUNT = 3;
    public static final int MIN_LEAK_COUNT = 2;
    public static final int MAX_LEAK_COUNT = 10;
    // Optional minimum age (seconds): when > 0, a cluster is only a leak if its
    // oldest member has lived this long — so a momentary burst isn't flagged.
    // 0 = disabled (the default). Slider steps of LEAK_AGE_STEP_SEC.
    public static final int DEFAULT_LEAK_AGE_SEC = 0;
    public static final int MAX_LEAK_AGE_SEC = 300;
    public static final int LEAK_AGE_STEP_SEC = 15;

    @NonNull
    private static SharedPreferences sp(@NonNull Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static int getColumns(@NonNull Context ctx) {
        int c = sp(ctx).getInt(KEY_COLUMNS, 1);
        return Math.max(MIN_COLUMNS, Math.min(MAX_COLUMNS, c));
    }

    /** Cycle 1 → 2 → 3 → 1, persist, and return the new value. */
    public static int cycleColumns(@NonNull Context ctx) {
        int next = getColumns(ctx) + 1;
        if (next > MAX_COLUMNS) next = MIN_COLUMNS;
        sp(ctx).edit().putInt(KEY_COLUMNS, next).apply();
        return next;
    }

    public static int getIconSizeDp(@NonNull Context ctx) {
        int dp = sp(ctx).getInt(KEY_ICON_DP, DEFAULT_ICON_DP);
        return Math.max(MIN_ICON_DP, Math.min(MAX_ICON_DP, dp));
    }

    public static void setIconSizeDp(@NonNull Context ctx, int dp) {
        if (dp < MIN_ICON_DP) dp = MIN_ICON_DP;
        if (dp > MAX_ICON_DP) dp = MAX_ICON_DP;
        sp(ctx).edit().putInt(KEY_ICON_DP, dp).apply();
    }

    /** Vertical padding (dp) inside each row — 0 = tightest. */
    public static int getRowPaddingDp(@NonNull Context ctx) {
        int dp = sp(ctx).getInt(KEY_ROW_PAD_DP, DEFAULT_ROW_PAD_DP);
        return Math.max(0, Math.min(MAX_ROW_PAD_DP, dp));
    }

    public static void setRowPaddingDp(@NonNull Context ctx, int dp) {
        if (dp < 0) dp = 0;
        if (dp > MAX_ROW_PAD_DP) dp = MAX_ROW_PAD_DP;
        sp(ctx).edit().putInt(KEY_ROW_PAD_DP, dp).apply();
    }

    /** Minimum identical-process count to flag a leak (known-transient comms floor at 2). */
    public static int getLeakThreshold(@NonNull Context ctx) {
        int n = sp(ctx).getInt(KEY_LEAK_COUNT, DEFAULT_LEAK_COUNT);
        return Math.max(MIN_LEAK_COUNT, Math.min(MAX_LEAK_COUNT, n));
    }

    public static void setLeakThreshold(@NonNull Context ctx, int n) {
        if (n < MIN_LEAK_COUNT) n = MIN_LEAK_COUNT;
        if (n > MAX_LEAK_COUNT) n = MAX_LEAK_COUNT;
        sp(ctx).edit().putInt(KEY_LEAK_COUNT, n).apply();
    }

    /** Minimum sustained age (seconds) for a leak; 0 = disabled. */
    public static int getLeakMinAgeSec(@NonNull Context ctx) {
        int s = sp(ctx).getInt(KEY_LEAK_AGE_SEC, DEFAULT_LEAK_AGE_SEC);
        return Math.max(0, Math.min(MAX_LEAK_AGE_SEC, s));
    }

    public static void setLeakMinAgeSec(@NonNull Context ctx, int s) {
        if (s < 0) s = 0;
        if (s > MAX_LEAK_AGE_SEC) s = MAX_LEAK_AGE_SEC;
        sp(ctx).edit().putInt(KEY_LEAK_AGE_SEC, s).apply();
    }
}
