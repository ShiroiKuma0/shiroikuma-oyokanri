// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.utils;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

import io.github.muntashirakon.AppManager.settings.Prefs;

/**
 * Fork: the single source of truth for the configurable fork theme — the
 * text/background/border colour and border width that style both the themed
 * toasts ({@link UIUtils}) and the in-app batch-progress dialog. The values are
 * stored as preferences (see {@link Prefs.Appearance}); the defaults reproduce
 * the original hard-coded bright-yellow-on-black look so existing installs are
 * visually unchanged.
 */
public final class ForkThemeUtils {
    private ForkThemeUtils() {
    }

    @ColorInt
    public static int getTextColor() {
        return Prefs.Appearance.getThemeTextColor();
    }

    @ColorInt
    public static int getBackgroundColor() {
        return Prefs.Appearance.getThemeBackgroundColor();
    }

    @ColorInt
    public static int getBorderColor() {
        return Prefs.Appearance.getThemeBorderColor();
    }

    public static int getBorderWidthDp() {
        return Prefs.Appearance.getThemeBorderWidthDp();
    }

    /**
     * Build a rounded rectangle drawable filled with the theme background
     * colour and outlined with the theme border colour/width. Built at runtime
     * (rather than an XML drawable) so the colours can be reconfigured without
     * any static resource.
     *
     * @param context        any context (used only for the display metrics)
     * @param cornerRadiusDp corner radius in dp
     */
    @NonNull
    public static GradientDrawable makeThemedBackground(@NonNull Context context, float cornerRadiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(getBackgroundColor());
        drawable.setCornerRadius(dpToPx(context, cornerRadiusDp));
        drawable.setStroke(Math.round(dpToPx(context, getBorderWidthDp())), getBorderColor());
        return drawable;
    }

    /**
     * Apply {@link #makeThemedBackground(Context, float)} to {@code view} and,
     * when it is a {@link TextView}, set its text colour to the theme text
     * colour.
     */
    public static void applyThemedBackground(@NonNull View view, float cornerRadiusDp) {
        view.setBackground(makeThemedBackground(view.getContext(), cornerRadiusDp));
        if (view instanceof TextView) {
            ((TextView) view).setTextColor(getTextColor());
        }
    }

    public static float dpToPx(@NonNull Context context, float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                context.getResources().getDisplayMetrics());
    }
}
