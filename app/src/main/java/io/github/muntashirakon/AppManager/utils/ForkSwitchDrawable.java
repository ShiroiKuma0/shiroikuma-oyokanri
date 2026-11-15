// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.utils;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Fork (白い熊, +104): the outlined switch of the black-yellow theme as a drawable — a round
 * outline with a solid dot, right and accent when on, left and a darker accent when off. Never
 * filled. Used as the button of a checkable menu row and as the marker of a custom popup's
 * toggle row.
 */
public final class ForkSwitchDrawable extends Drawable {
    private final int mOn, mOff, mWidth, mHeight;
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private boolean mChecked;

    public ForkSwitchDrawable(@ColorInt int on, @ColorInt int off, int width, int height, int strokeWidth) {
        mOn = on;
        mOff = off;
        mWidth = width;
        mHeight = height;
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeWidth(strokeWidth);
        mFill.setStyle(Paint.Style.FILL);
    }

    /** For a plain view that has no checked state of its own. */
    @NonNull
    public ForkSwitchDrawable checked(boolean checked) {
        mChecked = checked;
        invalidateSelf();
        return this;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        int color = mChecked ? mOn : mOff;
        float inset = mStroke.getStrokeWidth() / 2f;
        mRect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
        mStroke.setColor(color);
        canvas.drawRoundRect(mRect, mRect.height() / 2f, mRect.height() / 2f, mStroke);
        mFill.setColor(color);
        float cx = mChecked ? b.right - b.height() / 2f : b.left + b.height() / 2f;
        canvas.drawCircle(cx, b.exactCenterY(), b.height() * 0.23f, mFill);
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(@NonNull int[] state) {
        boolean c = false;
        for (int s : state) {
            if (s == android.R.attr.state_checked) {
                c = true;
                break;
            }
        }
        if (c == mChecked) return false;
        mChecked = c;
        invalidateSelf();
        return true;
    }

    @Override
    public int getIntrinsicWidth() {
        return mWidth;
    }

    @Override
    public int getIntrinsicHeight() {
        return mHeight;
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
