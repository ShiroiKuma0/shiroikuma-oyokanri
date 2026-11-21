// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.fonts;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

/**
 * Fork (白い熊, +123): children laid out left to right, wrapping to the next line — the chip
 * index of the UI screen. Deliberately tiny (no flexbox dependency): every child is measured at
 * its own size and separated by one fixed gap.
 */
final class UiFlowLayout extends ViewGroup {
    private final int mGap;

    UiFlowLayout(@NonNull Context context, int gapPx) {
        super(context);
        mGap = gapPx;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int avail = width - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, line = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            c.measure(MeasureSpec.makeMeasureSpec(avail, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            if (x > 0 && x + c.getMeasuredWidth() > avail) {
                x = 0;
                y += line + mGap;
                line = 0;
            }
            x += c.getMeasuredWidth() + mGap;
            line = Math.max(line, c.getMeasuredHeight());
        }
        setMeasuredDimension(width, y + line + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int avail = r - l - getPaddingLeft() - getPaddingRight();
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int x = 0, y = getPaddingTop(), line = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            int w = c.getMeasuredWidth(), h = c.getMeasuredHeight();
            if (x > 0 && x + w > avail) {
                x = 0;
                y += line + mGap;
                line = 0;
            }
            int left = rtl ? getPaddingLeft() + avail - x - w : getPaddingLeft() + x;
            c.layout(left, y, left + w, y + h);
            x += w + mGap;
            line = Math.max(line, h);
        }
    }

    /** The index of the child under this point of the layout, or -1. */
    int childAt(float px, float py) {
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (px >= c.getLeft() - mGap / 2f && px < c.getRight() + mGap / 2f
                    && py >= c.getTop() - mGap / 2f && py < c.getBottom() + mGap / 2f) {
                return i;
            }
        }
        return -1;
    }
}
