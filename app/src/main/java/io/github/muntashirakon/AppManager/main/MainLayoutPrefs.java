// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

// Fork: persists the main-list column layout chosen from the toolbar grid icon.
// 0 = adaptive auto-fit grid (the original layout, one column per 450dp);
// 1/2/3/4 = fixed column count. Dedicated SharedPreferences file so settings
// export/import picks it up automatically (it bundles shared_prefs/*.xml).
public final class MainLayoutPrefs {
    public static final int COLUMNS_ADAPTIVE = 0;

    private static final String PREF_FILE = "shiroikuma_main_layout";
    private static final String KEY_COLUMNS = "columns";

    private MainLayoutPrefs() {
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    public static int getColumns(@NonNull Context context) {
        return prefs(context).getInt(KEY_COLUMNS, COLUMNS_ADAPTIVE);
    }

    public static void setColumns(@NonNull Context context, int columns) {
        prefs(context).edit().putInt(KEY_COLUMNS, columns).apply();
    }

    // ── Row proportions ──────────────────────────────────────────────────────
    /** One column per this many dp, matching {@code UIUtils.getGridLayoutAt450Dp}. */
    private static final int ADAPTIVE_COLUMN_DP = 450;
    /** The icon column, which is a fixed width and not part of the weighted split. */
    private static final int ICON_COLUMN_DP = 60;
    /**
     * The version/backup block, expressed as a multiple of the width of the
     * signature string beneath it.
     * <p>
     * Measured rather than guessed in dp so it follows the <b>configurable
     * fonts</b>: enlarge the row's text and the block that has to hold it grows
     * with it. The factor covers the three paired lines above the signature —
     * the widest of them is the no-backup case, "System" beside the "long-press"
     * hint, which runs about a quarter wider than the signature itself.
     */
    private static final float RIGHT_COLUMN_REF_FACTOR = 1.45f;
    /** The tuned proportion: centre 1, right 2. Never exceeded. */
    private static final float RIGHT_COLUMN_MAX_SHARE = 2f / 3f;

    /**
     * The column count the list will actually use — the fixed pick, or what the
     * auto-fit grid works out for this window.
     */
    public static int resolveColumns(@NonNull Context context) {
        int columns = getColumns(context);
        if (columns > 0) return columns;
        int widthDp = context.getResources().getConfiguration().screenWidthDp;
        return Math.max(1, widthDp / ADAPTIVE_COLUMN_DP);
    }

    /**
     * Fork (白い熊, +76/+77): how wide the version/backup column should be, in
     * pixels, given the measured width of its own signature line.
     * <p>
     * The row's three columns were declared at 1:2 (label/package : version and
     * backup), a ratio tuned for a two-column grid and wrong the moment a row
     * gets wide. The right column's content <b>does not grow with the row</b> —
     * a version, user/system, an SDK level, a signature, and the backup values
     * beside them — so on a one-column list that fixed share bought it hundreds
     * of dp of nothing: a gap between the version block and the backup block,
     * another between the backup block and the card's edge, and the package name
     * ellipsized to make room for both. +76 capped the share at a constant 400dp
     * and 白い熊 measured what was left: still roughly a third used.
     * <p>
     * So it is no longer a share at all. The block takes what its text needs and
     * sits flush at the row's end, the label/package column absorbs everything
     * else, and the two blocks end up adjacent — which is exactly the order the
     * eye wants: name first and widest, then version, then backup at the edge.
     * <p>
     * The {@link #RIGHT_COLUMN_MAX_SHARE} clamp is the safety net for a narrow
     * row: on a folded panel or any multi-column grid the measured width can
     * exceed two thirds of the row, and there the old proportion still stands.
     * This can therefore only ever widen the label column, never narrow it.
     *
     * @param signatureWidthPx measured width of the signature line, i.e. the
     *                         widest single item in the block
     */
    public static int rightColumnWidthPx(@NonNull Context context, float signatureWidthPx) {
        int needed = Math.round(signatureWidthPx * RIGHT_COLUMN_REF_FACTOR);
        int rowPx = availableRowWidthPx(context);
        int max = Math.round(rowPx * RIGHT_COLUMN_MAX_SHARE);
        return Math.max(1, Math.min(needed, max));
    }

    /** A row's width in pixels, less the fixed icon column. */
    private static int availableRowWidthPx(@NonNull Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        int widthPx = Math.round(context.getResources().getConfiguration().screenWidthDp * density);
        int iconPx = Math.round(ICON_COLUMN_DP * density);
        return Math.max(1, widthPx / resolveColumns(context) - iconPx);
    }
}
