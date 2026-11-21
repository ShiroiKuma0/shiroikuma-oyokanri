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

    /**
     * Fork (+124): recolour an outlined switch that carries a meaning (the 盗み見 tab). Since +124
     * the outline IS the track drawable — so a caller must never set a transparent track tint
     * again; it goes through here.
     */
    public static void tint(@NonNull MaterialSwitch sw, @NonNull ColorStateList tint) {
        sw.setThumbTintList(tint);
        sw.setTrackTintList(tint);
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
            // Fork (白い熊, +124): the whole switch is drawn here, at the skill's size (52 x 28 dp,
            // 2 dp outline, a dot of 46 % of the height) — whatever style the layout names.
            // Until +123 the stock thumb's box was reused and the dot was an InsetDrawable. Both
            // were wrong: upstream's "medium" switch style has a far smaller track, and
            // SwitchCompat reads a drawable's PADDING as "this much of the thumb may overhang
            // the track" — an InsetDrawable reports its insets as padding, so the dot hung out
            // over the left end when off (白い熊's screenshot of 2026-11-21: "mangled, tiny").
            // A LayerDrawable's layer insets are not padding, so the dot's box now travels
            // inside the track and the gap to the outline is the same all round.
            float d = sw.getResources().getDisplayMetrics().density;
            int w = Math.round(52 * d), h = Math.round(28 * d);
            int dot = Math.round(h * 0.46f);
            int inset = (h - dot) / 2;
            GradientDrawable oval = new GradientDrawable();
            oval.setShape(GradientDrawable.OVAL);
            oval.setColor(Color.WHITE);
            oval.setSize(dot, dot);
            android.graphics.drawable.LayerDrawable thumb =
                    new android.graphics.drawable.LayerDrawable(new Drawable[]{oval});
            thumb.setLayerInset(0, inset, inset, h - dot - inset, h - dot - inset);
            GradientDrawable track = new GradientDrawable();
            track.setShape(GradientDrawable.RECTANGLE);
            track.setCornerRadius(h / 2f);
            track.setColor(Color.TRANSPARENT);
            track.setStroke(Math.max(1, Math.round(2 * d)), Color.WHITE);
            track.setSize(w, h);
            sw.setThumbDrawable(thumb);
            sw.setThumbIconDrawable(null);
            sw.setThumbTintList(tint);
            sw.setTrackDrawable(track);
            sw.setTrackTintList(tint);
            sw.setTrackDecorationDrawable(null);
            sw.setSwitchMinWidth(w);
        } catch (Throwable ignore) {
        }
    }
}
