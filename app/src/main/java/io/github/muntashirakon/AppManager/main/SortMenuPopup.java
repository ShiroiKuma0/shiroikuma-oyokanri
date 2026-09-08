// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.LinearLayoutCompat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;

/**
 * Fork (白い熊, +164): the sort dropdown, drawn by hand.
 *
 * <p>It began as a framework {@link android.widget.PopupMenu}, which was the fastest way to get the
 * behaviour right and the worst way to get the look right: a {@code ListMenuItemView} row is at
 * least {@code dropdownListPreferredItemHeight} tall, has no rule between rows and no notion of a
 * group heading, so eighteen sort orders came out as a very tall black panel of evenly-spaced yellow
 * text with nothing to catch the eye on. None of that is themeable — the row height, the padding and
 * the absence of separators are all baked into the platform's own layout.
 *
 * <p>So the rows are ours. They are packed to the text (6dp of breathing room rather than 48dp of
 * row), and what separates them is a hairline rather than a gap — the same device the main list uses
 * between its cells, at the same low alpha, so the panel reads as a ruled list instead of a wall.
 * The selected row carries a faint wash of the theme ink as well as a filled marker, because on a
 * long list a single small dot is easy to lose.
 *
 * <p>Headings use the fork's kxkb rule (the 白い熊 応用管理 UI page, +72): a heading is followed by an
 * underline exactly as wide as its own text, which is a {@code match_parent} View inside a
 * {@code wrap_content} vertical container. That is the whole trick, and it is what tells a group
 * apart from an item without spending vertical space on it.
 *
 * <p>Everything is built in code and nothing is a resource, for the same reason {@code RowPills} is:
 * the ink is the configurable fork theme's, so no drawable can be authored ahead of time.
 */
public final class SortMenuPopup {
    /** Text size of an ordinary row. */
    private static final float ROW_TEXT_SP = 16f;
    /**
     * Vertical breathing room per row — and therefore the gap on either side of every hairline,
     * since the rule sits directly between two rows' padding. Kept deliberately small: the rule is
     * what separates one order from the next, so it does not also need empty space to do the job.
     */
    private static final float ROW_PAD_V_DP = 3.5f;
    private static final float ROW_PAD_H_DP = 14f;
    /** Diameter of the radio/check marker. */
    private static final float MARKER_DP = 15f;
    private static final float PANEL_WIDTH_DP = 250f;
    private static final float PANEL_RADIUS_DP = 16f;
    /** A hairline between rows; a heavier rule between groups. */
    private static final float HAIRLINE_ALPHA = 0.18f;
    private static final float RULE_ALPHA = 0.45f;
    /** The wash behind the selected row. Enough to find, not enough to shout. */
    private static final float SELECTED_WASH_ALPHA = 0.13f;
    private static final float HEADING_ALPHA = 0.75f;
    /** Breathing room kept below the panel so it never sits flush on the screen edge. */
    private static final float BOTTOM_MARGIN_DP = 8f;

    public interface Listener {
        void onListSort(int sortId);

        void onReverseToggled(boolean reverse);

        void onLensSort(int index);
    }

    /**
     * @param listSorts  sort id → label resource, in menu order
     * @param lensTitle  the active lens's name, or null when there is none
     * @param lensSorts  that lens's own orders, in its own order; may be empty
     */
    public static void show(@NonNull Activity activity,
                            @NonNull View anchor,
                            @NonNull LinkedHashMap<Integer, Integer> listSorts,
                            int currentSort,
                            boolean reverse,
                            @Nullable CharSequence lensTitle,
                            @NonNull List<CharSequence> lensSorts,
                            int currentLensSort,
                            @NonNull Listener listener) {
        Context context = activity;
        int ink = ForkThemeUtils.getTextColor();
        int widthPx = Math.round(dp(context, PANEL_WIDTH_DP));
        int maxWidth = activity.getResources().getDisplayMetrics().widthPixels
                - Math.round(dp(context, 24f));
        widthPx = Math.min(widthPx, maxWidth);

        LinearLayoutCompat body = new LinearLayoutCompat(context);
        body.setOrientation(LinearLayoutCompat.VERTICAL);

        PopupWindow popup = new PopupWindow(context);
        boolean[] first = {true};

        // The list's own orders.
        for (Map.Entry<Integer, Integer> entry : listSorts.entrySet()) {
            final int sortId = entry.getKey();
            if (!first[0]) {
                body.addView(rule(context, ink, HAIRLINE_ALPHA, 1));
            }
            first[0] = false;
            body.addView(row(context, ink, context.getString(entry.getValue()),
                    sortId == currentSort, false, v -> {
                        popup.dismiss();
                        listener.onListSort(sortId);
                    }));
        }

        // Reverse is not one of the orders — it modifies whichever is chosen — so a heavier rule
        // rather than another hairline, and a square marker rather than a round one.
        body.addView(rule(context, ink, RULE_ALPHA, 1));
        body.addView(row(context, ink, context.getString(
                        io.github.muntashirakon.AppManager.R.string.reverse), reverse, true,
                v -> {
                    popup.dismiss();
                    listener.onReverseToggled(!reverse);
                }));

        // The active lens's own orders, under a heading that names it.
        if (lensTitle != null && !lensSorts.isEmpty()) {
            body.addView(rule(context, ink, RULE_ALPHA, 1));
            body.addView(heading(context, ink, lensTitle));
            for (int i = 0; i < lensSorts.size(); ++i) {
                final int index = i;
                if (i > 0) {
                    body.addView(rule(context, ink, HAIRLINE_ALPHA, 1));
                }
                body.addView(row(context, ink, lensSorts.get(i), i == currentLensSort, false,
                        v -> {
                            popup.dismiss();
                            listener.onLensSort(index);
                        }));
            }
        }

        ScrollView scroller = new ScrollView(context);
        scroller.setVerticalScrollBarEnabled(false);
        // A little ground top and bottom, so the first row's wash does not sit on the frame and the
        // rounded corners do not clip into the first and last labels.
        int inset = Math.round(dp(context, 4f));
        scroller.setPadding(0, inset, 0, inset);
        scroller.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Clip the panel's own corners, or a row's wash paints over the rounded frame.
        GradientDrawable panel = ForkThemeUtils.makeThemedBackground(context, PANEL_RADIUS_DP);
        scroller.setBackground(panel);
        scroller.setClipToOutline(true);

        popup.setContentView(scroller);
        popup.setWidth(widthPx);
        // Measure so a short menu hugs its content and a long one stops at the cap and scrolls.
        body.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        // Fork (白い熊, +165): no fraction of the screen — the panel hugs its content, and when the
        // content is taller than the room beneath the toolbar it runs all the way to the bottom edge
        // and scrolls inside that. Measured from the anchor rather than assumed, because on the
        // Mate XT the window is a different height folded, unfolded and in multi-window.
        int[] anchorPos = new int[2];
        anchor.getLocationOnScreen(anchorPos);
        View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        int windowBottom = decor != null && decor.getHeight() > 0
                ? decor.getHeight() : activity.getResources().getDisplayMetrics().heightPixels;
        int available = windowBottom - (anchorPos[1] + anchor.getHeight())
                - Math.round(dp(context, BOTTOM_MARGIN_DP));
        int wanted = body.getMeasuredHeight() + inset * 2 + Math.round(dp(context, 4f));
        // A pathologically short anchor position should never collapse the panel to nothing.
        popup.setHeight(available > Math.round(dp(context, 96f))
                ? Math.min(wanted, available) : wanted);
        popup.setFocusable(true);
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(context, 12f));
        popup.showAsDropDown(anchor, 0, 0, Gravity.END);
    }

    /** One tappable order. */
    @NonNull
    private static View row(@NonNull Context context, @ColorInt int ink, @NonNull CharSequence text,
                            boolean checked, boolean square,
                            @NonNull View.OnClickListener onClick) {
        LinearLayoutCompat row = new LinearLayoutCompat(context);
        row.setOrientation(LinearLayoutCompat.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = Math.round(dp(context, ROW_PAD_H_DP));
        int padV = Math.round(dp(context, ROW_PAD_V_DP));
        row.setPadding(padH, padV, padH, padV);
        row.setClickable(true);
        row.setOnClickListener(onClick);
        if (checked) {
            row.setBackgroundColor(withAlpha(ink, SELECTED_WASH_ALPHA));
        }

        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, ROW_TEXT_SP);
        label.setTextColor(ink);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayoutCompat.LayoutParams labelLp = new LinearLayoutCompat.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(label, labelLp);

        View marker = new View(context);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(square ? GradientDrawable.RECTANGLE : GradientDrawable.OVAL);
        if (square) {
            shape.setCornerRadius(dp(context, 3f));
        }
        int stroke = Math.max(1, Math.round(dp(context, 1.5f)));
        if (checked) {
            // Filled, with a ring of ground between the fill and the stroke, so it reads as a
            // marker rather than a blob at this size.
            shape.setColor(ink);
            shape.setStroke(stroke, ink);
        } else {
            shape.setColor(0x00000000);
            shape.setStroke(stroke, withAlpha(ink, 0.55f));
        }
        marker.setBackground(shape);
        int markerPx = Math.round(dp(context, MARKER_DP));
        LinearLayoutCompat.LayoutParams markerLp =
                new LinearLayoutCompat.LayoutParams(markerPx, markerPx);
        markerLp.leftMargin = Math.round(dp(context, 10f));
        row.addView(marker, markerLp);
        return row;
    }

    /**
     * A group heading with the fork's text-width underline: the underline is {@code match_parent}
     * inside a {@code wrap_content} column, so it is exactly as wide as the word above it.
     */
    @NonNull
    private static View heading(@NonNull Context context, @ColorInt int ink,
                                @NonNull CharSequence text) {
        LinearLayoutCompat column = new LinearLayoutCompat(context);
        column.setOrientation(LinearLayoutCompat.VERTICAL);
        int padH = Math.round(dp(context, ROW_PAD_H_DP));
        column.setPadding(padH, Math.round(dp(context, 5f)), padH, Math.round(dp(context, 3f)));

        LinearLayoutCompat holder = new LinearLayoutCompat(context);
        holder.setOrientation(LinearLayoutCompat.VERTICAL);

        TextView title = new TextView(context);
        title.setText(text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        title.setTextColor(withAlpha(ink, HEADING_ALPHA));
        title.setAllCaps(true);
        title.setLetterSpacing(0.08f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setSingleLine(true);
        holder.addView(title, new LinearLayoutCompat.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View underline = new View(context);
        underline.setBackgroundColor(withAlpha(ink, HEADING_ALPHA));
        LinearLayoutCompat.LayoutParams underlineLp = new LinearLayoutCompat.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(dp(context, 1f))));
        underlineLp.topMargin = Math.round(dp(context, 2f));
        holder.addView(underline, underlineLp);

        column.addView(holder, new LinearLayoutCompat.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return column;
    }

    @NonNull
    private static View rule(@NonNull Context context, @ColorInt int ink, float alpha, int heightDp) {
        View line = new View(context);
        line.setBackgroundColor(withAlpha(ink, alpha));
        line.setLayoutParams(new LinearLayoutCompat.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(dp(context, heightDp)))));
        return line;
    }

    @ColorInt
    private static int withAlpha(@ColorInt int color, float alpha) {
        int a = Math.round(255 * Math.max(0f, Math.min(1f, alpha)));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private static float dp(@NonNull Context context, float dp) {
        return dp * context.getResources().getDisplayMetrics().density;
    }

    private SortMenuPopup() {
    }
}
