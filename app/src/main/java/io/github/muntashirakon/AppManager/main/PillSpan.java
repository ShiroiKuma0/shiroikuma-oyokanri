// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ReplacementSpan;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

/**
 * Fork (白い熊, +149): a filled pill drawn <b>inside</b> a TextView.
 *
 * <p>The sibling screens' right-hand column is three fixed TextViews of the main list's own row
 * ({@code MainCardBinder}), so a headline there could only ever be coloured text — and on black,
 * the fork's red {@code #FF0028} at 12sp is the one colour that is genuinely hard to read. A real
 * {@link RowPills} pill is a View and cannot go in a TextView; this is the same shape as a
 * {@link android.text.style.ReplacementSpan}, so the column keeps its layout and a line can still
 * be loud.
 *
 * <p>It carries its own text size, so a pill does not inherit whichever of the three rows it
 * happens to land in — and it enlarges the line through {@code fm}, or a bigger pill would be
 * clipped by the line it sits on.
 */
public class PillSpan extends ReplacementSpan {
    private static final float PAD_H_DP = 7f;
    private static final float PAD_V_DP = 2f;

    @ColorInt
    private final int mBackground;
    @ColorInt
    private final int mInk;
    private final float mTextSizePx;
    private final float mPadH;
    private final float mPadV;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Wrap {@code text} in a pill of its own, ready to hand to a TextView. */
    @NonNull
    public static CharSequence pill(@NonNull CharSequence text, @ColorInt int background,
                                    @ColorInt int ink, float textSizePx, float density) {
        SpannableString sb = new SpannableString(text);
        sb.setSpan(new PillSpan(background, ink, textSizePx, density), 0, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    public PillSpan(@ColorInt int background, @ColorInt int ink, float textSizePx, float density) {
        mBackground = background;
        mInk = ink;
        mTextSizePx = textSizePx;
        mPadH = PAD_H_DP * density;
        mPadV = PAD_V_DP * density;
    }

    private void configure(@NonNull Paint from) {
        mPaint.set(from);
        mPaint.setAntiAlias(true);
        if (mTextSizePx > 0) {
            mPaint.setTextSize(mTextSizePx);
        }
        mPaint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    @Override
    public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
                       Paint.FontMetricsInt fm) {
        configure(paint);
        if (fm != null) {
            // The line has to make room for a pill that is taller than the text around it,
            // otherwise the rounded ends are cut off by the line above and below.
            Paint.FontMetricsInt own = mPaint.getFontMetricsInt();
            fm.ascent = fm.top = own.ascent - Math.round(mPadV);
            fm.descent = fm.bottom = own.descent + Math.round(mPadV);
        }
        return Math.round(mPaint.measureText(text, start, end) + 2 * mPadH);
    }

    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x,
                     int top, int y, int bottom, @NonNull Paint paint) {
        configure(paint);
        Paint.FontMetricsInt fm = mPaint.getFontMetricsInt();
        float width = mPaint.measureText(text, start, end) + 2 * mPadH;
        RectF rect = new RectF(x, y + fm.ascent - mPadV, x + width, y + fm.descent + mPadV);
        int colour = mPaint.getColor();
        mPaint.setColor(mBackground);
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, mPaint);
        mPaint.setColor(mInk);
        canvas.drawText(text, start, end, x + mPadH, y, mPaint);
        mPaint.setColor(colour);
    }
}
