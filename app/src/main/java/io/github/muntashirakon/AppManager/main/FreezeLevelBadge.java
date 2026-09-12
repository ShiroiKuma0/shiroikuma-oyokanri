// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.muntashirakon.AppManager.fonts.ColorPrefs;
import io.github.muntashirakon.AppManager.utils.FreezeUtils;

/**
 * Fork (白い熊, +034): the little numbered circle on the snowflake, and the one
 * place that turns a freeze gate number into a colour.
 * <p>
 * A freeze used to be one state and is now four switches, so the row had to gain
 * a way of saying <em>which</em>. Colour carries the severity — a ramp from the
 * pale aqua of a mere force-stop to the violet this app has always used for the
 * deepest "off" — and the digit removes the guesswork, because four shades of
 * cool are four shades of cool when you are looking at a folded panel in
 * daylight.
 * <p>
 * It lives here rather than in the adapter because three surfaces draw the same
 * row — the main list, the battery/lens cards through {@link
 * io.github.muntashirakon.AppManager.battery.MainCardBinder} and the Freeze box
 * on the 盗み見 page — and the +30 rule for those is that they share the colour
 * keys. Sharing the builder is strictly better: they cannot drift even by one
 * shade.
 */
public final class FreezeLevelBadge {
    /** Hairline, the same weight the row's other outlined pills use. */
    private static final float RING_STROKE_DP = 1.5f;

    private FreezeLevelBadge() {
    }

    /**
     * The accent for a gate number: what the snowflake, the badge and the Freeze
     * box's switch are painted in. Level 0 has no colour of its own — the caller
     * decides what "nothing in force" looks like on its surface.
     */
    @ColorInt
    public static int accent(@NonNull Context context, int level, @ColorInt int fallback) {
        if (level <= 0) return fallback;
        return ColorPrefs.getColor(context, ColorPrefs.freezeLevelKey(level));
    }

    /**
     * The card background for a gate number.
     * <p>
     * <b>Level 1 deliberately has none.</b> An app that is only force-stopped is
     * not frozen — {@link FreezeUtils#isFrozen} says so, and anything that opens
     * it clears the mark — so its row stays an ordinary row and only the badge
     * reports it. Tinting it would claim a dormancy that is not there.
     */
    @ColorInt
    public static int film(@NonNull Context context, int level, @ColorInt int fallback) {
        String key = level > 0 ? ColorPrefs.freezeFilmKey(level) : null;
        return key != null ? ColorPrefs.getColor(context, key) : fallback;
    }

    /**
     * Draw the badge into {@code view}, or hide it when nothing is in force.
     * <p>
     * Both branches set every property they touch: these views are recycled, and
     * a badge left over from the previous binding is worse than none.
     *
     * @param view the square TextView beside the snowflake, the same size as it.
     */
    public static void bind(@Nullable TextView view, int level) {
        if (view == null) return;
        if (level <= 0) {
            view.setVisibility(View.GONE);
            return;
        }
        Context context = view.getContext();
        int accent = accent(context, level, Color.WHITE);
        // Fork (白い熊, +035): a RING, not a disc — hairline outline in the rung's
        // colour, nothing inside it, the digit in the same colour. That is the fork's
        // own pill language (see RowPills and the Snooping status pills), and the
        // right way round for a mark this size: a filled disc read as a solid blob
        // of colour on the row rather than as a label.
        //
        // The fill can be genuinely transparent since +036, when the badge moved out
        // from under the snowflake to sit beside it. While it overlapped, it had to
        // be filled with the row's own colour or the glyph's arm ran through the
        // digit.
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(Math.max(1, Math.round(
                RING_STROKE_DP * context.getResources().getDisplayMetrics().density)), accent);
        view.setBackground(ring);
        view.setText(String.valueOf(level));
        view.setTextColor(accent);
        view.setVisibility(View.VISIBLE);
        view.setContentDescription(context.getString(
                io.github.muntashirakon.AppManager.R.string.freeze_level_badge_description, level));
    }
}
