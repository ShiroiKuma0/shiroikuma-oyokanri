// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.utils;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.fonts.ColorPrefs;
import io.github.muntashirakon.AppManager.fonts.RunningBoxPrefs;

/**
 * Fork: the main list's card frame, applied to list cards on screens that are
 * not the main list.
 *
 * <p>On a pure-black surface an M3 card is black-on-black and its rows merge
 * into one another, so the frame is not decoration — it is what separates the
 * rows. The width, corner roundness and colours are read from the SAME prefs
 * the main list and the battery list read ({@link RunningBoxPrefs},
 * {@link ColorPrefs#STROKE_USER} / {@link ColorPrefs#STROKE_SYSTEM}) and read
 * at bind time, so the screens cannot drift apart and a settings change lands
 * everywhere at once.
 */
public final class ForkCardFrame {
    private ForkCardFrame() {
    }

    /**
     * Frame a card the way the main list frames a live app: yellow for a user
     * app, orange for a system one.
     */
    public static void apply(@NonNull Context context, @NonNull MaterialCardView card, boolean system) {
        float density = context.getResources().getDisplayMetrics().density;
        float boxDp = RunningBoxPrefs.getWidthDp(context);
        card.setCardBackgroundColor(Color.BLACK);
        card.setRadius(RunningBoxPrefs.getRadiusDp(context) * density);
        card.setStrokeWidth(boxDp <= 0f ? 0 : Math.max(1, Math.round(boxDp * density)));
        card.setStrokeColor(system
                ? ColorPrefs.getColor(context, ColorPrefs.STROKE_SYSTEM,
                ContextCompat.getColor(context, R.color.theme_bright_orange))
                : ColorPrefs.getColor(context, ColorPrefs.STROKE_USER,
                ContextCompat.getColor(context, R.color.theme_bright_yellow)));
    }

    /**
     * Same, deciding user vs. system from the app itself. A missing
     * {@link ApplicationInfo} is treated as a user app — the yellow frame is
     * the neutral one here.
     */
    public static void apply(@NonNull Context context, @NonNull MaterialCardView card,
                             @Nullable ApplicationInfo applicationInfo) {
        apply(context, card, applicationInfo != null
                && (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
    }
}
