// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.utils;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * Fork (白い熊, +104): the theme's switch on a {@link MaterialSwitch} — an outlined track with the
 * ground showing through and one solid dot, the same shape on and off; only the side and the
 * brightness change. Never filled, not even when on. On is the theme's text colour, off an
 * opaque darker shade of it (55 % of the way from the ground), never a faded one.
 *
 * <p>Every {@code MaterialSwitch} inflated in a black-yellow activity gets this through
 * {@link ForkViewInflater}; a page that gives a switch a meaning-carrying colour of its own
 * (the 盗み見 tab) tints it again afterwards.
 */
public final class ForkSwitch {
    private ForkSwitch() {
    }

    /** The colour of a switch that is off, derived from the two theme roles. */
    @ColorInt
    public static int offColor(@ColorInt int on, @ColorInt int ground) {
        return ColorUtils.blendARGB(ground, on, 0.55f);
    }

    /** Fork (+123): both colours are settable roles of their own — never constants. */
    public static void outline(@NonNull MaterialSwitch sw) {
        outlineWith(sw, ForkPalette.get(sw.getContext(), ForkPalette.SWITCH_ON),
                ForkPalette.get(sw.getContext(), ForkPalette.SWITCH_OFF));
    }

    /** A switch that carries a meaning in its colour: off is that colour's own darker shade. */
    public static void outline(@NonNull MaterialSwitch sw, @ColorInt int on, @ColorInt int ground) {
        outlineWith(sw, on, offColor(on, ground));
    }

    private static void outlineWith(@NonNull MaterialSwitch sw, @ColorInt int on, @ColorInt int off) {
        try {
            ColorStateList tint = new ColorStateList(
                    new int[][]{{-android.R.attr.state_enabled, android.R.attr.state_checked},
                            {-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}},
                    new int[]{ForkPalette.disabled(on), ForkPalette.disabled(off), on, off});
            sw.setMinHeight(0);
            sw.setMinimumHeight(0);
            // One dot for both states, 46 % of the track's height, inside the stock thumb's box so
            // the switch keeps its size and travel.
            Drawable stock = sw.getThumbDrawable();
            int box = stock != null ? stock.getIntrinsicHeight() : 0;
            if (box > 0) {
                int dot = Math.round(box * 0.46f);
                GradientDrawable oval = new GradientDrawable();
                oval.setShape(GradientDrawable.OVAL);
                oval.setColor(Color.WHITE);
                oval.setSize(dot, dot);
                int inset = (box - dot) / 2;
                sw.setThumbDrawable(new InsetDrawable(oval, inset, inset, box - dot - inset, box - dot - inset));
            }
            sw.setThumbIconDrawable(null);
            sw.setThumbTintList(tint);
            // The track decoration is the outline; the track itself stays hollow.
            sw.setTrackDecorationTintList(tint);
            sw.setTrackTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        } catch (Throwable ignore) {
        }
    }
}
