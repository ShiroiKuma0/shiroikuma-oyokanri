// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.utils.AppNotesManager;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;

/**
 * Fork (白い熊, +094): the single place that builds and styles a main-list row's
 * pills — the note pill on the label line and the tag (profile) pills on the
 * line below, including the two "add" affordances.
 *
 * <p>It exists because the two add controls have to be <em>uniform</em>: same
 * outline, same width, same faded ink, so they stack into one column at the
 * row's right edge instead of reading as two unrelated controls. Two separate
 * styling blocks drift the moment either is touched; one builder cannot.
 *
 * <p>The shape is the fork's own pill language, the same one the Snooping
 * screen's status pills use: transparent fill over the card's black, a hairline
 * stadium outline in the accent, and a ripple of the same hue. Built in code
 * rather than as a drawable resource because the accent is the configurable
 * theme's, re-read on every bind.
 *
 * <p>Alpha carries the hierarchy. An <b>add</b> affordance is present but faded
 * back into the row ({@link #ADD_ALPHA}) — it is an invitation, not information.
 * A note that <em>exists</em> annotates the row and must not compete with the
 * app name for attention, so its outline and text are drawn below full opacity
 * too, which is what makes it read as a margin note rather than a second title.
 * A tag pill is the one thing here that is full-strength: it is a fact about the
 * app.
 */
public final class RowPills {
    private RowPills() {}

    /**
     * Shared width of the two add affordances — the note pill on the label line
     * and the "+" on the tag line — so they form one column rather than two
     * ragged controls. Both centre their content, so nothing depends on which
     * of the two carries a glyph and which carries text.
     */
    public static final float ADD_WIDTH_DP = 34f;

    /** Opacity of both add affordances. */
    private static final float ADD_ALPHA = 0.55f;
    /** A written note's outline and ink: legible, but under the app name. */
    private static final float NOTE_BORDER_ALPHA = 0.45f;
    private static final float NOTE_INK_ALPHA = 0.80f;

    private static final float TAG_TEXT_SP = 11f;
    private static final float ADD_TEXT_SP = 13f;
    private static final float NOTE_TEXT_SP = 11f;
    private static final float GLYPH_DP = 14f;
    private static final float PAD_H_DP = 8f;
    private static final float PAD_V_DP = 2f;
    private static final float STROKE_DP = 1.5f;
    private static final float GAP_DP = 4f;

    @ColorInt
    public static int withAlpha(@ColorInt int color, float alpha) {
        return ColorUtils.setAlphaComponent(color, Math.round(255f * alpha));
    }

    /**
     * The fork's row-pill outline: the card's black behind it, a hairline in
     * {@code ink}, fully rounded ends, and a ripple of the same hue so a tap
     * reads as a press. The corner radius is past half the height on purpose —
     * the framework clamps it, so the result is a stadium at whatever height the
     * content ends up needing.
     */
    @NonNull
    private static Drawable outline(@NonNull Context context, @ColorInt int ink) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(Color.TRANSPARENT);
        shape.setCornerRadius(ForkThemeUtils.dpToPx(context, 100f));
        shape.setStroke(Math.max(1, Math.round(ForkThemeUtils.dpToPx(context, STROKE_DP))), ink);
        return new RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(ink, 0x33)), shape, null);
    }

    /** Everything every pill shares: outline, paddings, centred single line. */
    private static void base(@NonNull TextView pill, @ColorInt int ink, @ColorInt int border) {
        Context context = pill.getContext();
        int padH = Math.round(ForkThemeUtils.dpToPx(context, PAD_H_DP));
        int padV = Math.round(ForkThemeUtils.dpToPx(context, PAD_V_DP));
        pill.setBackground(outline(context, border));
        pill.setPadding(padH, padV, padH, padV);
        pill.setGravity(Gravity.CENTER);
        pill.setIncludeFontPadding(false);
        pill.setSingleLine(true);
        pill.setEllipsize(TextUtils.TruncateAt.END);
        pill.setTextColor(ink);
        pill.setClickable(true);
        pill.setFocusable(true);
    }

    /**
     * A tag (profile) membership pill. Full-strength ink: unlike the two add
     * affordances, this is a fact about the app rather than an invitation.
     */
    @NonNull
    public static TextView tagPill(@NonNull Context context, @NonNull CharSequence text,
                                   @ColorInt int ink) {
        TextView pill = new AppCompatTextView(context);
        base(pill, ink, ink);
        pill.setText(text);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, TAG_TEXT_SP);
        // LinearLayoutCompat.LayoutParams, not the ViewGroup.MarginLayoutParams
        // it descends from: LinearLayout converts foreign params through
        // LayoutParams(ViewGroup.LayoutParams), which copies width and height
        // and silently drops the margins.
        LinearLayoutCompat.LayoutParams lp = new LinearLayoutCompat.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Math.round(ForkThemeUtils.dpToPx(context, GAP_DP)));
        pill.setLayoutParams(lp);
        return pill;
    }

    /**
     * The trailing "+" that adds this app to a profile. Pinned to
     * {@link #ADD_WIDTH_DP} so it and the empty note pill above it are exactly
     * as wide as each other.
     */
    public static void bindAddTag(@NonNull TextView pill, @ColorInt int ink) {
        Context context = pill.getContext();
        int faded = withAlpha(ink, ADD_ALPHA);
        base(pill, faded, faded);
        pill.setText("+");
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, ADD_TEXT_SP);
        pill.setCompoundDrawablesRelative(null, null, null, null);
        setWidth(pill, Math.round(ForkThemeUtils.dpToPx(context, ADD_WIDTH_DP)));
    }

    /**
     * Render {@code pkg}'s note affordance into {@code pill} and report whether
     * a note exists — the caller uses that to decide how much of the label line
     * the pill is allowed to take (see {@code MainRecyclerAdapter#bindNotePill}).
     *
     * <p>It is one pill either way, so the two states read as the same control:
     * with a note it holds the note glyph plus the note's <b>first line</b>,
     * ellipsized to whatever the row can spare; with none it holds just the
     * add glyph and is pinned to the add width. The full note is the tooltip.
     *
     * <p>Both branches set every property the other one sets — the pill lives in
     * a recycled row.
     */
    public static boolean bindNote(@NonNull TextView pill, @NonNull String pkg,
                                   @ColorInt int ink) {
        Context context = pill.getContext();
        String note = AppNotesManager.getNote(context, pkg);
        boolean has = note != null && !note.trim().isEmpty();
        int textInk = withAlpha(ink, has ? NOTE_INK_ALPHA : ADD_ALPHA);
        int border = withAlpha(ink, has ? NOTE_BORDER_ALPHA : ADD_ALPHA);
        base(pill, textInk, border);
        pill.setTypeface(Typeface.DEFAULT);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, has ? NOTE_TEXT_SP : ADD_TEXT_SP);
        setGlyph(pill, has ? R.drawable.ic_note_24dp : R.drawable.ic_note_add_24dp, textInk,
                has ? GAP_DP : 0f);
        if (has) {
            pill.setText(firstLine(note));
            pill.setContentDescription(note);
            setWidth(pill, ViewGroup.LayoutParams.WRAP_CONTENT);
        } else {
            pill.setText("");
            pill.setContentDescription(context.getString(R.string.note));
            setWidth(pill, Math.round(ForkThemeUtils.dpToPx(context, ADD_WIDTH_DP)));
        }
        return has;
    }

    // ── Action pills (白い熊, +118) ──────────────────────────────────────────
    //
    // The same stadium, one size up, for a pill that DOES something rather than
    // reporting something: the shelf under the toolbar, the actions inside an
    // expanded row, the batch actions, and the buttons on the operation-log
    // page. They are one control in four places, so they are built once here —
    // the alternative is four styling blocks that agree today and drift on the
    // first edit, which is exactly the reasoning that created this class.

    private static final float ACTION_TEXT_SP = 13f;
    private static final float ACTION_PAD_H_DP = 16f;
    private static final float ACTION_PAD_V_DP = 7f;
    private static final float ACTION_GLYPH_DP = 16f;

    /**
     * Style an existing view as an action pill.
     *
     * <p>{@code filled} is the on state: a solid accent with the screen's black
     * back through the text, which is the only way a pill can say "this is the
     * view you are looking at" without a second control beside it. An off pill
     * is the ordinary outline.
     */
    public static void styleActionPill(@NonNull TextView pill, @ColorInt int ink, boolean filled) {
        styleActionPill(pill, ink, filled, false);
    }

    /**
     * {@code compact} is for a pill that lives in a bar rather than in a pane: the shelf under
     * the toolbar is one row of furniture above the list, and at the full action size that row
     * eats a visible slice of the screen for nothing (白い熊, +121).
     */
    public static void styleActionPill(@NonNull TextView pill, @ColorInt int ink, boolean filled,
                                       boolean compact) {
        Context context = pill.getContext();
        int padH = Math.round(ForkThemeUtils.dpToPx(context, compact ? 12f : ACTION_PAD_H_DP));
        int padV = Math.round(ForkThemeUtils.dpToPx(context, compact ? 2f : ACTION_PAD_V_DP));
        if (filled) {
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.RECTANGLE);
            shape.setColor(ink);
            shape.setCornerRadius(ForkThemeUtils.dpToPx(context, 100f));
            pill.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(ColorUtils.setAlphaComponent(Color.BLACK, 0x33)), shape, null));
            pill.setTextColor(Color.BLACK);
        } else {
            pill.setBackground(outline(context, ink));
            pill.setTextColor(ink);
        }
        pill.setPadding(padH, padV, padH, padV);
        pill.setGravity(Gravity.CENTER);
        pill.setIncludeFontPadding(false);
        pill.setSingleLine(true);
        pill.setEllipsize(TextUtils.TruncateAt.END);
        pill.setAllCaps(false);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 12f : ACTION_TEXT_SP);
        pill.setClickable(true);
        pill.setFocusable(true);
        pill.setMinWidth(0);
        pill.setMinimumWidth(0);
        pill.setMinHeight(0);
        pill.setMinimumHeight(0);
        pill.setStateListAnimator(null);
    }

    /**
     * A new action pill. {@code iconRes} of 0 means no glyph.
     */
    @NonNull
    public static TextView actionPill(@NonNull Context context, @NonNull CharSequence text,
                                      int iconRes, @ColorInt int ink, boolean filled) {
        TextView pill = new AppCompatTextView(context);
        styleActionPill(pill, ink, filled);
        pill.setText(text);
        if (iconRes != 0) {
            setActionGlyph(pill, iconRes, filled ? Color.BLACK : ink);
        }
        LinearLayoutCompat.LayoutParams lp = new LinearLayoutCompat.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // LinearLayoutCompat.LayoutParams for the same reason tagPill uses them:
        // LinearLayout silently drops the margins of foreign params.
        lp.setMarginEnd(Math.round(ForkThemeUtils.dpToPx(context, GAP_DP + 2f)));
        lp.bottomMargin = Math.round(ForkThemeUtils.dpToPx(context, GAP_DP + 2f));
        pill.setLayoutParams(lp);
        return pill;
    }

    /** An action pill's leading glyph — a size up from the row pills' own. */
    public static void setActionGlyph(@NonNull TextView pill, int drawableRes, @ColorInt int ink) {
        Context context = pill.getContext();
        Drawable glyph = ContextCompat.getDrawable(context, drawableRes);
        if (glyph != null) {
            glyph = glyph.mutate();
            int size = Math.round(ForkThemeUtils.dpToPx(context, ACTION_GLYPH_DP));
            glyph.setBounds(0, 0, size, size);
            glyph.setTintList(ColorStateList.valueOf(ink));
        }
        pill.setCompoundDrawablePadding(Math.round(ForkThemeUtils.dpToPx(context, GAP_DP + 2f)));
        pill.setCompoundDrawablesRelative(glyph, null, null, null);
    }

    /**
     * The note's first line — a note is free text and may be a paragraph, but
     * the row can only ever show one line of it, and a line break rendered as a
     * space would silently run two unrelated sentences together.
     */
    @NonNull
    private static String firstLine(@Nullable String note) {
        if (note == null) return "";
        String trimmed = note.trim();
        int nl = trimmed.indexOf('\n');
        return (nl < 0 ? trimmed : trimmed.substring(0, nl)).trim();
    }

    /** A leading glyph sized for the pill, tinted to match its ink. */
    private static void setGlyph(@NonNull TextView pill, int drawableRes, @ColorInt int ink,
                                 float gapDp) {
        Context context = pill.getContext();
        Drawable glyph = ContextCompat.getDrawable(context, drawableRes);
        if (glyph != null) {
            // mutate() or the tint leaks into every other user of the shared
            // constant state — every other row, in a recycled list.
            glyph = glyph.mutate();
            int size = Math.round(ForkThemeUtils.dpToPx(context, GLYPH_DP));
            glyph.setBounds(0, 0, size, size);
            glyph.setTintList(ColorStateList.valueOf(ink));
        }
        pill.setCompoundDrawablePadding(Math.round(ForkThemeUtils.dpToPx(context, gapDp)));
        pill.setCompoundDrawablesRelative(glyph, null, null, null);
    }

    /** Set a laid-out view's width without disturbing the rest of its params. */
    private static void setWidth(@NonNull View view, int width) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp == null || lp.width == width) return;
        lp.width = width;
        view.setLayoutParams(lp);
    }
}
