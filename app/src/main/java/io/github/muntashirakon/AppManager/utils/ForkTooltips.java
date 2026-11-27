// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.utils;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;

import io.github.muntashirakon.AppManager.R;

/**
 * Fork (白い熊, 2026-11-27): the hint a long-press shows on an icon of a bar.
 *
 * <p>The platform draws its tooltip from theme attributes that are not public, so it cannot be
 * themed: on this app it came out as a light grey box with our yellow text on it, or with black
 * text where the view sat under another theme overlay — 白い熊: "some yellow-white, some
 * black-white, both wrong". So every view that carries a tooltip text hands it over to a small
 * popup of ours, drawn in the tooltip roles ({@link ForkPalette#TOOLTIP_BG} / {@code _FRAME} /
 * {@code _TEXT}).
 *
 * <p>The views come and go (a menu is rebuilt, a selection bar opens), so the window is looked
 * through again after every layout pass. A view that has a long-press of its own keeps it, and
 * simply has no hint. Same pattern as 白い熊 メッセージ's {@code helpers/Tooltips.kt}.
 *
 * <p>Covers activity windows. A dialog is a window of its own and is not watched; its buttons
 * are labelled pills and carry no tooltip.
 */
public final class ForkTooltips {
    private ForkTooltips() {
    }

    private static final long SHOWN_MS = 2500L;

    public static void watch(@NonNull Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;   // no platform tooltip to replace
        try {
            final View decor = activity.getWindow().getDecorView();
            decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                try {
                    adopt(decor);
                } catch (Throwable ignore) {
                }
            });
        } catch (Throwable ignore) {
        }
    }

    private static void adopt(@NonNull View view) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        CharSequence text = view.getTooltipText();
        if (text != null) {
            // Taking the text away is what switches the platform's own tooltip off.
            view.setTooltipText(null);
            boolean ours = view.getTag(R.id.fork_tooltip_text) != null;
            boolean hasOwnLongPress = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && view.hasOnLongClickListeners();
            if (ours || !hasOwnLongPress) {
                view.setTag(R.id.fork_tooltip_text, text);
                if (!ours) {
                    view.setOnLongClickListener(v -> {
                        Object hint = v.getTag(R.id.fork_tooltip_text);
                        if (hint instanceof CharSequence) show(v, (CharSequence) hint);
                        return true;
                    });
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) adopt(group.getChildAt(i));
        }
    }

    private static void show(@NonNull View anchor, @NonNull CharSequence text) {
        Context context = anchor.getContext();
        float d = context.getResources().getDisplayMetrics().density;
        TextView hint = new TextView(context);
        hint.setText(text);
        hint.setMaxLines(1);
        hint.setTextColor(ForkPalette.get(context, ForkPalette.TOOLTIP_TEXT));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setPaddingRelative(Math.round(12 * d), Math.round(6 * d), Math.round(12 * d), Math.round(6 * d));
        GradientDrawable panel = new GradientDrawable();
        panel.setCornerRadius(8 * d);
        panel.setColor(ForkPalette.get(context, ForkPalette.TOOLTIP_BG));
        panel.setStroke(Math.max(1, Math.round(1.25f * d)), ForkPalette.get(context, ForkPalette.TOOLTIP_FRAME));
        hint.setBackground(panel);
        hint.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        // A pure overlay: it takes no touches, and the platform keeps it on the screen.
        final PopupWindow popup = new PopupWindow(hint, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        popup.setTouchable(false);
        try {
            popup.showAsDropDown(anchor, (anchor.getWidth() - hint.getMeasuredWidth()) / 2, Math.round(4 * d));
        } catch (Throwable th) {
            return;
        }
        anchor.postDelayed(() -> {
            try {
                if (popup.isShowing()) popup.dismiss();
            } catch (Throwable ignore) {
            }
        }, SHOWN_MS);
    }
}
