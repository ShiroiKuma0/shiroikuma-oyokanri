// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import io.github.muntashirakon.AppManager.utils.LayoutGeometry;

// Fork: persists the main-list column layout chosen from the toolbar grid icon.
// 0 = adaptive auto-fit grid (the original layout, one column per 450dp);
// 1/2/3/4 = fixed column count. Stored PER GEOMETRY (orientation × fold state,
// see LayoutGeometry) — folded and unfolded want different layouts and each is
// remembered on its own. Dedicated SharedPreferences file so settings
// export/import picks it up automatically (it bundles shared_prefs/*.xml).
public final class MainLayoutPrefs {
    public static final int COLUMNS_ADAPTIVE = 0;

    private static final String PREF_FILE = "shiroikuma_main_layout";
    private static final String KEY_COLUMNS = "columns";
    /**
     * Fork (白い熊, +173): how wide the right-hand block is, as a percentage of the reference
     * string, stored <b>per geometry</b> like the column count beside it.
     * <p>
     * The trade it settles cannot be settled once: the row has one pool of width, and every pixel
     * the version block takes comes out of the app name and package id. Which of those you would
     * rather read in full genuinely differs folded, unfolded and in a four-column grid, so it is a
     * per-geometry choice rather than a constant somebody has to be right about.
     */
    private static final String KEY_RIGHT_COLUMN_PCT = "right_column_pct";
    public static final int RIGHT_COLUMN_PCT_MIN = 40;
    public static final int RIGHT_COLUMN_PCT_MAX = 200;
    public static final int RIGHT_COLUMN_PCT_DEFAULT = 100;

    private MainLayoutPrefs() {
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    /** The pick for the current geometry — see {@link LayoutGeometry}. */
    public static int getColumns(@NonNull Context context) {
        return LayoutGeometry.getColumns(context, prefs(context), KEY_COLUMNS, COLUMNS_ADAPTIVE);
    }

    /** Stores the pick for the current geometry only. */
    public static void setColumns(@NonNull Context context, int columns) {
        LayoutGeometry.setColumns(context, prefs(context), KEY_COLUMNS, columns);
    }

    /**
     * The right-column width percentage for the current geometry. 100 means "exactly the reference
     * string", which is a full fork version name — see {@code MainRecyclerAdapter}.
     * <p>
     * <b>Pass a view or activity context</b>, never the application one: {@link LayoutGeometry}
     * reads the window's configuration to decide which geometry this is.
     */
    public static int getRightColumnPct(@NonNull Context context) {
        int pct = LayoutGeometry.getColumns(context, prefs(context), KEY_RIGHT_COLUMN_PCT,
                RIGHT_COLUMN_PCT_DEFAULT);
        return Math.max(RIGHT_COLUMN_PCT_MIN, Math.min(RIGHT_COLUMN_PCT_MAX, pct));
    }

    /** Stores the width for the current geometry only. */
    public static void setRightColumnPct(@NonNull Context context, int pct) {
        LayoutGeometry.setColumns(context, prefs(context), KEY_RIGHT_COLUMN_PCT,
                Math.max(RIGHT_COLUMN_PCT_MIN, Math.min(RIGHT_COLUMN_PCT_MAX, pct)));
    }

    // ── Row proportions ──────────────────────────────────────────────────────
    /** One column per this many dp, matching {@code UIUtils.getGridLayoutAt450Dp}. */
    private static final int ADAPTIVE_COLUMN_DP = 450;
    /** The icon column, which is a fixed width and not part of the weighted split. */
    private static final int ICON_COLUMN_DP = 60;
    /**
     * Fork (白い熊, +173): the block was sized from the <b>signature</b> line —
     * {@code "SHA384withRSA"}, 13 characters, times a constant 1.45 — which made the whole column
     * about 19 characters wide. That was right while a version was {@code 1.6.0+081}, and became
     * wrong the moment the family's version names started pinning their upstream base:
     * {@code 4.1.1+2026-09-05.03-37.g41d79af5+016} is 36 characters, and 雫's is 43. The column was
     * sized for a fifth of what it had to carry, so the version could not help but truncate.
     * <p>
     * The reference is now a version, measured with the version line's own paint, and the multiple
     * is {@link #getRightColumnPct} rather than a constant — see there for why it is per geometry.
     */
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
     * @param referenceWidthPx measured width of the reference version string, i.e. the
     *                         widest single item the block now has to carry
     */
    public static int rightColumnWidthPx(@NonNull Context context, float referenceWidthPx) {
        int needed = Math.round(referenceWidthPx * (getRightColumnPct(context) / 100f));
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
