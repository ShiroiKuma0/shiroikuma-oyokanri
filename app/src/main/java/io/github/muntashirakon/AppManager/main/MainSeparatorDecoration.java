// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

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
import io.github.muntashirakon.AppManager.fonts.SeparatorPrefs;

/**
 * Fork: straight separator lines between the main-list cells, replacing the
 * old per-card margins (which let the grey screen background seep through).
 * Cells touch edge-to-edge; this decoration reserves a configurable gap to
 * the right of every non-last column (vertical separator) and below every
 * row (horizontal separator) and paints it with the configured colour.
 *
 * Width 0 means no gap at all. Widths come from {@link SeparatorPrefs},
 * colours from {@link ColorPrefs#SEPARATOR_H} / {@link ColorPrefs#SEPARATOR_V}.
 * Call {@link #reload} + {@code invalidateItemDecorations()} after a setting
 * changes (MainActivity.onResume does, flag-guarded).
 *
 * All spans in a vertical GridLayoutManager row share the same decorated top,
 * but not necessarily the same height — the horizontal line is drawn at the
 * tallest cell's bottom, and the vertical lines extend down to it, so the
 * grid stays straight even when cells in a row differ in height (the slack
 * under a shorter cell shows the list's black background, same as the cards).
 */
public class MainSeparatorDecoration extends RecyclerView.ItemDecoration {
    private final Paint mPaint = new Paint();

    private int mHPx;
    private int mVPx;
    private int mHColor;
    private int mVColor;

    public MainSeparatorDecoration(@NonNull Context context) {
        reload(context);
    }

    /** Re-read widths and colours from the prefs. */
    public void reload(@NonNull Context context) {
        mHPx = toPx(context, SeparatorPrefs.getWidthDp(context, true));
        mVPx = toPx(context, SeparatorPrefs.getWidthDp(context, false));
        mHColor = ColorPrefs.getColor(context, ColorPrefs.SEPARATOR_H);
        mVColor = ColorPrefs.getColor(context, ColorPrefs.SEPARATOR_V);
    }

    private static int toPx(@NonNull Context context, float dp) {
        if (dp <= 0f) return 0;
        // A non-zero width must paint at least one physical pixel.
        return Math.max(1, Math.round(dp * context.getResources().getDisplayMetrics().density));
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        outRect.set(0, 0, 0, 0);
        RecyclerView.LayoutManager lm = parent.getLayoutManager();
        if (!(lm instanceof GridLayoutManager)) return;
        GridLayoutManager.LayoutParams lp = (GridLayoutManager.LayoutParams) view.getLayoutParams();
        boolean lastColumn = lp.getSpanIndex() + lp.getSpanSize() >= ((GridLayoutManager) lm).getSpanCount();
        outRect.right = lastColumn ? 0 : mVPx;
        outRect.bottom = mHPx;
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        if (mHPx == 0 && mVPx == 0) return;
        RecyclerView.LayoutManager lm = parent.getLayoutManager();
        if (!(lm instanceof GridLayoutManager)) return;
        int childCount = parent.getChildCount();
        if (childCount == 0) return;
        GridLayoutManager glm = (GridLayoutManager) lm;
        // decorated top -> max decorated bottom of that row
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
