// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.appstartup;

import android.graphics.Canvas;
import android.graphics.Color;
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
 * Fork (白い熊): the crossed-out battery — "no battery optimization" — that sits in a main-list
 * row's strip, beside the note and tag affordances.
 *
 * <p>Drawn in code rather than as a vector for the same reason {@code RowPills} is: the ink is the
 * configurable fork theme (re-read on every bind), and the oval must match the note / add-tag pills
 * exactly. The diagonal slash carries a black casing so it reads cleanly where it crosses the
 * battery outline. State (exempt vs optimized vs pinned) is carried by {@link #setAlpha(int)}, so
 * the one drawable serves every row.
 */
public final class BatteryExemptDrawable extends Drawable {
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCasing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    @ColorInt
    private int mInk;
    private int mAlpha = 255;

    public BatteryExemptDrawable(@ColorInt int ink) {
        mInk = ink;
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeCap(Paint.Cap.ROUND);
        mFill.setStyle(Paint.Style.FILL);
        mCasing.setStyle(Paint.Style.STROKE);
        mCasing.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setInk(@ColorInt int ink) {
        if (mInk != ink) {
            mInk = ink;
            invalidateSelf();
        }
    }

    @Override
    public void draw(@NonNull Canvas c) {
        Rect b = getBounds();
        float w = b.width();
        float h = b.height();
        if (w <= 0 || h <= 0) {
            return;
        }
        float unit = h / 28f;                       // design reference ≈ 44 × 28
        int ink = withAlpha(mInk, mAlpha);
        int black = withAlpha(Color.BLACK, mAlpha);
        mStroke.setColor(ink);
        mFill.setColor(ink);
        mCasing.setColor(black);

        // Oval (stadium) — the same outline the note / add-tag pills carry.
        float os = 2.4f * unit;
        mStroke.setStrokeWidth(os);
        mRect.set(b.left + os / 2f, b.top + os / 2f, b.right - os / 2f, b.bottom - os / 2f);
        c.drawRoundRect(mRect, mRect.height() / 2f, mRect.height() / 2f, mStroke);

        // Battery body, big inside the small oval.
        float bw = w * 0.50f;
        float bh = h * 0.46f;
        float cx = b.left + w / 2f;
        float cy = b.top + h / 2f;
        float bx = cx - bw / 2f - 1.5f * unit;      // shifted left to leave room for the nub
        float by = cy - bh / 2f;
        mStroke.setStrokeWidth(2.2f * unit);
        mRect.set(bx, by, bx + bw, by + bh);
        c.drawRoundRect(mRect, 2.5f * unit, 2.5f * unit, mStroke);

        // Terminal nub.
        mRect.set(bx + bw, cy - 2.6f * unit, bx + bw + 2.6f * unit, cy + 2.6f * unit);
        c.drawRoundRect(mRect, unit, unit, mFill);

        // Crossed-out slash (upper-right → lower-left), black casing under the ink line.
        float x1 = bx + bw + 1.5f * unit;
        float y1 = by - 2.5f * unit;
        float x2 = bx - 2.5f * unit;
        float y2 = by + bh + 2.5f * unit;
        mCasing.setStrokeWidth(4.4f * unit);
        c.drawLine(x1, y1, x2, y2, mCasing);
        mStroke.setStrokeWidth(2.1f * unit);
        c.drawLine(x1, y1, x2, y2, mStroke);
    }

    @ColorInt
    private static int withAlpha(@ColorInt int color, int alpha) {
        return (color & 0x00FFFFFF) | ((Color.alpha(color) * alpha / 255) << 24);
    }

    @Override
    public void setAlpha(int alpha) {
        if (mAlpha != alpha) {
            mAlpha = alpha;
            invalidateSelf();
        }
    }

    @Override
    public int getAlpha() {
        return mAlpha;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        // Ink is set directly; no filter.
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
