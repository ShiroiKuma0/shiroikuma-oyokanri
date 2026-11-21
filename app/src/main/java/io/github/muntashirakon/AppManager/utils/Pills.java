// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.utils;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewParent;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import io.github.muntashirakon.AppManager.R;

/**
 * Fork (白い熊, +123): the black-yellow theme's button — an outlined pill, never filled. The main
 * action of a block has a 3 dp frame and a bold label, every other action 1.25 dp and normal
 * weight; a deleting action lends its red to frame and label. Colours are the settable roles of
 * {@link ForkPalette}.
 *
 * <p>Nothing in a layout asks for this: {@link ForkViewInflater} hands every inflated button,
 * floating button, top bar and switch to this class, so a screen nobody remembered is covered
 * as well. A button built in code calls {@link #button} itself.
 *
 * <p>Left alone on purpose (白い熊, 2026-11-21): a button that is only a glyph (no label), and
 * a checkable button of a selector — a selection may mark its state with a fill.
 */
public final class Pills {
    private Pills() {
    }

    public static final float MAIN_DP = 3f;
    public static final float REGULAR_DP = 1.25f;

    /** Labels that delete something: a button carrying one is red. */
    private static final int[] DESTRUCTIVE = {
            R.string.delete, R.string.uninstall, R.string.delete_backup, R.string.clear_data,
            R.string.diag_purge, R.string.discard,
    };

    @NonNull
    private static ColorStateList states(@ColorInt int color) {
        return new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{ForkPalette.disabled(color), color});
    }

    public static boolean isDestructiveLabel(@NonNull Context context, @Nullable CharSequence label) {
        if (TextUtils.isEmpty(label)) return false;
        String text = label.toString().trim();
        for (int res : DESTRUCTIVE) {
            try {
                if (context.getString(res).equalsIgnoreCase(text)) return true;
            } catch (Throwable ignore) {
            }
        }
        return false;
    }

    /** An outlined pill as a background for any view (a row, a hand-built button). */
    @NonNull
    public static Drawable frame(@NonNull Context context, boolean main, @ColorInt int color) {
        float d = context.getResources().getDisplayMetrics().density;
        GradientDrawable frame = new GradientDrawable();
        frame.setColor(Color.TRANSPARENT);
        frame.setCornerRadius(100 * d);
        frame.setStroke(Math.max(1, Math.round((main ? MAIN_DP : REGULAR_DP) * d)), color);
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(100 * d);
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 77)), frame, mask);
    }

    /** Make {@code b} a pill now. */
    public static void button(@NonNull MaterialButton b, boolean main) {
        button(b, main, isDestructiveLabel(b.getContext(), b.getText()));
    }

    public static void button(@NonNull MaterialButton b, boolean main, boolean destructive) {
        try {
            Context context = b.getContext();
            float d = context.getResources().getDisplayMetrics().density;
            int frame = destructive ? ForkPalette.get(context, ForkPalette.DELETE)
                    : ForkPalette.get(context, ForkPalette.BUTTON_FRAME);
            int text = destructive ? frame : ForkPalette.get(context, ForkPalette.BUTTON_TEXT);
            if (b.isCheckable()) {
                // A segment of a selector: the fill marks the chosen one, the frame stays.
                int ground = ForkPalette.get(context, ForkPalette.GROUND);
                b.setBackgroundTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                        new int[]{frame, Color.TRANSPARENT}));
                ColorStateList ink = new ColorStateList(new int[][]{{android.R.attr.state_checked},
                        {-android.R.attr.state_enabled}, {}}, new int[]{ground, ForkPalette.disabled(text), text});
                b.setTextColor(ink);
                b.setIconTint(ink);
                b.setStrokeColor(states(frame));
                b.setStrokeWidth(Math.max(1, Math.round(REGULAR_DP * d)));
                return;
            }
            b.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
            b.setStrokeColor(states(frame));
            b.setStrokeWidth(Math.max(1, Math.round((main ? MAIN_DP : REGULAR_DP) * d)));
            b.setCornerRadius(Math.round(100 * d));
            b.setTextColor(states(text));
            if (b.getIcon() != null) {
                // An icon beside a label is coloured by what the button does — told, as in the
                // menus, from the name of the button's id (a tile of a bottom sheet, a list action).
                int icon = text;
                if (!destructive) {
                    try {
                        if (b.getId() != View.NO_ID) {
                            icon = ForkPalette.get(context, MenuPills.roleForName(
                                    context.getResources().getResourceEntryName(b.getId())));
                        }
                    } catch (Throwable ignore) {
                    }
                }
                b.setIconTint(states(icon));
            }
            b.setRippleColor(ColorStateList.valueOf(ColorUtils.setAlphaComponent(frame, 77)));
            Typeface face = b.getTypeface();
            b.setTypeface(Typeface.create(face, main ? Typeface.BOLD : Typeface.NORMAL));
            b.setElevation(0);
            b.setStateListAnimator(null);
        } catch (Throwable ignore) {
        }
    }

    /**
     * Style a button once it is attached — by then its label, icon and checkable state are what
     * the screen made of them, which is what decides glyph or pill, and red or not.
     */
    static void onInflated(@NonNull MaterialButton b, boolean main) {
        b.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View v) {
                if (v.getTag(R.id.fork_pill_done) != null) return;
                v.setTag(R.id.fork_pill_done, Boolean.TRUE);
                MaterialButton button = (MaterialButton) v;
                boolean glyph = TextUtils.isEmpty(button.getText());
                ViewParent parent = v.getParent();
                // An extended floating button is a pill even while shrunk to its glyph.
                boolean extended = v instanceof com.google.android.material.floatingactionbutton
                        .ExtendedFloatingActionButton;
                if (glyph && !extended && !(parent instanceof MaterialButtonToggleGroup)) return;
                button(button, main && !button.isCheckable());
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View v) {
            }
        });
    }

    /** A floating button: ground inside, an accent ring, the glyph in the accent. Never a slab. */
    static void floating(@NonNull FloatingActionButton fab) {
        try {
            Context context = fab.getContext();
            float d = context.getResources().getDisplayMetrics().density;
            int frame = ForkPalette.get(context, ForkPalette.BUTTON_FRAME);
            fab.setBackgroundTintList(ColorStateList.valueOf(ForkPalette.get(context, ForkPalette.GROUND)));
            fab.setImageTintList(ColorStateList.valueOf(ForkPalette.get(context, ForkPalette.BUTTON_TEXT)));
            fab.setRippleColor(ColorUtils.setAlphaComponent(frame, 77));
            fab.setCompatElevation(0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                GradientDrawable ring = new GradientDrawable();
                ring.setShape(GradientDrawable.OVAL);
                ring.setColor(Color.TRANSPARENT);
                ring.setStroke(Math.max(1, Math.round(MAIN_DP * d)), frame);
                fab.setForeground(ring);
            }
        } catch (Throwable ignore) {
        }
    }

    /** The top bar's own roles: title, subtitle, navigation and overflow glyphs. */
    static void toolbar(@NonNull Toolbar bar) {
        try {
            int text = ForkPalette.get(bar.getContext(), ForkPalette.TOPBAR_TEXT);
            bar.setTitleTextColor(text);
            bar.setSubtitleTextColor(text);
            bar.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(@NonNull View v) {
                    try {
                        Toolbar t = (Toolbar) v;
                        Drawable nav = t.getNavigationIcon();
                        if (nav != null) {
                            nav = nav.mutate();
                            nav.setTint(text);
                            t.setNavigationIcon(nav);
                        }
                        Drawable more = t.getOverflowIcon();
                        if (more != null) {
                            more = more.mutate();
                            more.setTint(text);
                            t.setOverflowIcon(more);
                        }
                    } catch (Throwable ignore) {
                    }
                }

                @Override
                public void onViewDetachedFromWindow(@NonNull View v) {
                }
            });
        } catch (Throwable ignore) {
        }
    }

    static void appBar(@NonNull AppBarLayout bar) {
        try {
            bar.setBackgroundColor(ForkPalette.get(bar.getContext(), ForkPalette.TOPBAR_BG));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) bar.setStateListAnimator(null);
        } catch (Throwable ignore) {
        }
    }

    /** A plain platform or AppCompat button (not a MaterialButton): the pill as its background. */
    public static void plain(@NonNull TextView button, boolean main) {
        try {
            Context context = button.getContext();
            boolean destructive = isDestructiveLabel(context, button.getText());
            int frame = destructive ? ForkPalette.get(context, ForkPalette.DELETE)
                    : ForkPalette.get(context, ForkPalette.BUTTON_FRAME);
            float d = context.getResources().getDisplayMetrics().density;
            button.setBackground(frame(context, main, frame));
            button.setTextColor(states(destructive ? frame : ForkPalette.get(context, ForkPalette.BUTTON_TEXT)));
            button.setTypeface(Typeface.create(button.getTypeface(), main ? Typeface.BOLD : Typeface.NORMAL));
            button.setAllCaps(false);
            int h = Math.round(18 * d), v = Math.round(9 * d);
            button.setPaddingRelative(h, v, h, v);
            button.setMinimumHeight(0);
            button.setMinHeight(0);
            button.setStateListAnimator(null);
        } catch (Throwable ignore) {
        }
    }
}
