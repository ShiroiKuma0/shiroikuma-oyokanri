// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.LinearLayoutCompat;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;

/**
 * Fork (白い熊, +095): the main-list row's label line — app name · debug star ·
 * note pill — with the one measurement rule LinearLayout cannot express on its
 * own.
 *
 * <p>What 白い熊 asked for is: <b>the app name in full, and not one pixel more;
 * the note takes everything else, reaching back to where the name ends.</b> Both
 * halves matter — a name cut short to make room for a note is wrong, and so is a
 * note truncated at some fixed share of the line while empty space sits between
 * it and the name.
 *
 * <p>Plain weights cannot do that. Give both children a weight and the surplus
 * splits between them, so a short name leaves a gap and the note is cut early
 * (that is what +094 shipped). Give only the note a weight and it does reach the
 * name — but a name longer than the whole line then leaves it {@code 0} pixels
 * wide and the note disappears from the row altogether. ConstraintLayout answers
 * this with {@code layout_constrainedWidth} against the pill's minimum, and
 * ConstraintLayout is <b>not a dependency here</b>.
 *
 * <p>So the rule is applied by hand, and it is exactly one line of arithmetic:
 * before measuring, cap the label at "everything the line has, minus what the
 * others need". Below that cap the label is untouched — {@code wrap_content},
 * its natural width, no ellipsis — and the note (weighted, so it absorbs all the
 * slack) starts precisely where the name stops. Above it the name ellipsizes,
 * which it had to do anyway, and the note keeps a readable floor instead of
 * vanishing.
 */
public class LabelLineLayout extends LinearLayoutCompat {
    /**
     * The note pill's floor: the width it takes when it holds nothing but the
     * add glyph. A name long enough to bite into this is a name that could not
     * have fitted the line anyway, so the pixels are better spent keeping the
     * note on the row than on four more characters of an already-ellipsized
     * label.
     */
    private static final float NOTE_FLOOR_DP = RowPills.ADD_WIDTH_DP;

    @Nullable
    private TextView mLabel;
    @Nullable
    private View mNoteSlot;

    public LabelLineLayout(@NonNull Context context) {
        super(context);
    }

    public LabelLineLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public LabelLineLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        View label = findViewById(R.id.label);
        if (label instanceof TextView) mLabel = (TextView) label;
        mNoteSlot = findViewById(R.id.note_slot);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        applyLabelCap(MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight());
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private void applyLabelCap(int available) {
        if (mLabel == null || available <= 0) return;
        int reserved = Math.round(ForkThemeUtils.dpToPx(getContext(), NOTE_FLOOR_DP));
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child == mLabel || child.getVisibility() == GONE) continue;
            ViewGroup.LayoutParams params = child.getLayoutParams();
            if (params instanceof MarginLayoutParams) {
                MarginLayoutParams lp = (MarginLayoutParams) params;
                reserved += lp.getMarginStart() + lp.getMarginEnd();
            }
            // The slot's own share is the floor added above; every other child
            // (the debug star, which is INVISIBLE rather than GONE on a
            // non-debuggable app and so always occupies its width) is measured
            // for what it actually needs.
            if (child == mNoteSlot) continue;
            child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            reserved += child.getMeasuredWidth();
        }
        ViewGroup.LayoutParams labelParams = mLabel.getLayoutParams();
        if (labelParams instanceof MarginLayoutParams) {
            MarginLayoutParams lp = (MarginLayoutParams) labelParams;
            reserved += lp.getMarginStart() + lp.getMarginEnd();
        }
        // Never below half the line: in a 3- or 4-column grid the reservation can
        // be most of a narrow row, and an app with no name at all is a worse
        // outcome than a note squeezed under its floor.
        int cap = Math.max(available / 2, available - reserved);
        // Guarded, because setMaxWidth requests a layout — harmless here (the
        // child is measured by super.onMeasure immediately after), but not worth
        // doing on every measure pass of every row.
        if (mLabel.getMaxWidth() != cap) mLabel.setMaxWidth(cap);
    }
}
