// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.details.info;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;

/**
 * Fork (白い熊, +057): draws each group of App-info rows inside a rounded outlined box, in the
 * Snooping page's card language — the group header stays above its box as the section label. It is
 * a decoration rather than an adapter rewrite so the (upstream) row data and bindings are untouched.
 * Groups are delimited by {@link ListItem#LIST_ITEM_GROUP_BEGIN} headers: a run of non-header rows
 * following a header is one group, since every group starts with a header.
 */
class AppInfoGroupDecoration extends RecyclerView.ItemDecoration {
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mInset;
    private final float mRadius;
    private final float mVPad;
    private final int mTopGap;

    AppInfoGroupDecoration(@NonNull Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        mInset = density * 8;
        mRadius = density * 14;
        mVPad = density * 2;
        mTopGap = Math.round(density * 8);
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeWidth(density * 1.5f);
    }

    @Override
    public void getItemOffsets(@NonNull android.graphics.Rect outRect, @NonNull View view,
                               @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        RecyclerView.Adapter<?> adapter = parent.getAdapter();
        int pos = parent.getChildAdapterPosition(view);
        if (adapter == null || pos == RecyclerView.NO_POSITION) return;
        // A little air above each group header so consecutive boxes are visually separated.
        if (adapter.getItemViewType(pos) == ListItem.LIST_ITEM_GROUP_BEGIN && pos > 0) {
            outRect.top = mTopGap;
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        RecyclerView.Adapter<?> adapter = parent.getAdapter();
        if (adapter == null) return;
        mStroke.setColor(ForkThemeUtils.getTextColor());
        float left = parent.getPaddingLeft() + mInset;
        float right = parent.getWidth() - parent.getPaddingRight() - mInset;
        int childCount = parent.getChildCount();
        int i = 0;
        while (i < childCount) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos == RecyclerView.NO_POSITION
                    || adapter.getItemViewType(pos) == ListItem.LIST_ITEM_GROUP_BEGIN) {
                i++;
                continue;
            }
            // Start of a run of rows (one group). Extend while the next visible child is a row.
            float top = child.getY();
            float bottom = child.getY() + child.getHeight();
            int j = i + 1;
            while (j < childCount) {
                View next = parent.getChildAt(j);
                int npos = parent.getChildAdapterPosition(next);
                if (npos == RecyclerView.NO_POSITION
                        || adapter.getItemViewType(npos) == ListItem.LIST_ITEM_GROUP_BEGIN) {
                    break;
                }
                bottom = next.getY() + next.getHeight();
                j++;
            }
            RectF box = new RectF(left, top - mVPad, right, bottom + mVPad);
            c.drawRoundRect(box, mRadius, mRadius, mStroke);
            i = j;
        }
    }
}
