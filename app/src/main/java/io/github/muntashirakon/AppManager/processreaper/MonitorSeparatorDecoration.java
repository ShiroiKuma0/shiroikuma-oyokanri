// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.processreaper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.HashMap;
import java.util.Map;

import io.github.muntashirakon.AppManager.fonts.ColorPrefs;

/**
 * Fork: separator grid for the process monitor — straight lines below every row
 * and to the right of every non-last column. Widths from
 * {@link MonitorSeparatorPrefs}, colours from {@link ColorPrefs#MONITOR_SEPARATOR_H}
 * / {@code MONITOR_SEPARATOR_V}. Same logic as the main-list decoration; kept
 * separate so the monitor and main list tune independently. Width 0 = no line.
 */
public class MonitorSeparatorDecoration extends RecyclerView.ItemDecoration {
    private final Paint mPaint = new Paint();

    private int mHPx;
    private int mVPx;
    private int mHColor;
    private int mVColor;

    public MonitorSeparatorDecoration(@NonNull Context context) {
        reload(context);
    }

    public void reload(@NonNull Context context) {
        mHPx = toPx(context, MonitorSeparatorPrefs.getWidthDp(context, true));
        mVPx = toPx(context, MonitorSeparatorPrefs.getWidthDp(context, false));
        mHColor = ColorPrefs.getColor(context, ColorPrefs.MONITOR_SEPARATOR_H);
        mVColor = ColorPrefs.getColor(context, ColorPrefs.MONITOR_SEPARATOR_V);
    }

    private static int toPx(@NonNull Context context, float dp) {
        if (dp <= 0f) return 0;
        return Math.max(1, Math.round(dp * context.getResources().getDisplayMetrics().density));
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        outRect.set(0, 0, 0, 0);
        RecyclerView.LayoutManager lm = parent.getLayoutManager();
        if (!(lm instanceof GridLayoutManager)) return;
        GridLayoutManager.LayoutParams lp = (GridLayoutManager.LayoutParams) view.getLayoutParams();
        int spanCount = ((GridLayoutManager) lm).getSpanCount();
        boolean lastColumn = lp.getSpanIndex() + lp.getSpanSize() >= spanCount;
        outRect.right = lastColumn ? 0 : mVPx;
        outRect.bottom = mHPx;
        // Top line above the very first row, so the grid reads as uniformly bounded.
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < spanCount) outRect.top = mHPx;
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        if (mHPx == 0 && mVPx == 0) return;
        RecyclerView.LayoutManager lm = parent.getLayoutManager();
        if (!(lm instanceof GridLayoutManager)) return;
        int childCount = parent.getChildCount();
        if (childCount == 0) return;
        GridLayoutManager glm = (GridLayoutManager) lm;
        Map<Integer, Integer> rowBottoms = new HashMap<>();
        for (int i = 0; i < childCount; ++i) {
            View child = parent.getChildAt(i);
            int top = lm.getDecoratedTop(child);
            int bottom = lm.getDecoratedBottom(child);
            Integer cur = rowBottoms.get(top);
            if (cur == null || bottom > cur) {
                rowBottoms.put(top, bottom);
            }
        }
        if (mHPx > 0) {
            mPaint.setColor(mHColor);
            int left = parent.getPaddingLeft();
            int right = parent.getWidth() - parent.getPaddingRight();
            for (int bottom : rowBottoms.values()) {
                c.drawRect(left, bottom - mHPx, right, bottom, mPaint);
            }
            // Top line for the first row (drawn once, full width).
            int spanCount = glm.getSpanCount();
            for (int i = 0; i < childCount; ++i) {
                View child = parent.getChildAt(i);
                int pos = parent.getChildAdapterPosition(child);
                if (pos >= 0 && pos < spanCount) {
                    int top = lm.getDecoratedTop(child);
                    c.drawRect(left, top, right, top + mHPx, mPaint);
                    break;
                }
            }
        }
        if (mVPx > 0) {
            mPaint.setColor(mVColor);
            for (int i = 0; i < childCount; ++i) {
                View child = parent.getChildAt(i);
                GridLayoutManager.LayoutParams lp = (GridLayoutManager.LayoutParams) child.getLayoutParams();
                if (lp.getSpanIndex() + lp.getSpanSize() >= glm.getSpanCount()) continue;
                int top = lm.getDecoratedTop(child);
                Integer rowBottom = rowBottoms.get(top);
                int bottom = rowBottom != null ? rowBottom : lm.getDecoratedBottom(child);
                int right = lm.getDecoratedRight(child);
                c.drawRect(right - mVPx, top, right, bottom, mPaint);
            }
        }
    }
}
