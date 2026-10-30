// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.details;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.viewpager2.widget.ViewPager2;

import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;

/**
 * Fork (白い熊, +059): folder-style tab bar for the App details pager, in the look 白い熊 設計
 * (shiroikuma-shoruikanri) uses for its file tabs — tabs drawn as stacked paper folders: a border
 * line runs under the inactive tabs, turns up the left of the active folder, across its top, slants
 * down its right, and continues under the rest; inactive folders show their own shaded outlines,
 * all corners rounded. Unlike shoruikanri's file tabs these are a fixed set (no close/new/reorder),
 * so this is the drawing only, driving a {@link ViewPager2}.
 */
public class FolderTabBar extends HorizontalScrollView {
    private final Row mRow;
    @Nullable
    private ViewPager2 mPager;

    public FolderTabBar(@NonNull Context context) {
        this(context, null);
    }

    public FolderTabBar(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setHorizontalScrollBarEnabled(false);
        setFillViewport(true);
        mRow = new Row(context);
        addView(mRow, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
    }

    /** Wire the bar to a pager and a fixed list of tab titles. */
    public void setup(@NonNull ViewPager2 pager, @NonNull CharSequence[] titles) {
        mPager = pager;
        mRow.build(titles);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                mRow.setSelected(position);
                scrollSelectedIntoView(position);
            }
        });
        mRow.setSelected(pager.getCurrentItem());
    }

    private void scrollSelectedIntoView(int position) {
        View child = mRow.getChildAt(position);
        if (child == null) return;
        post(() -> {
            int target = Math.max(0, child.getLeft() - getWidth() / 4);
            smoothScrollTo(target, 0);
        });
    }

    private class Row extends LinearLayout {
        private final float mDensity = getResources().getDisplayMetrics().density;
        private final float mSlant = 10 * mDensity;
        private final int mActiveTopMargin = Math.round(4 * mDensity);
        private final int mInactiveHeight = Math.round(34 * mDensity);
        private final Paint mBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mShaded = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int mSelected = 0;

        Row(@NonNull Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.BOTTOM);
            setWillNotDraw(false);
            mBorder.setStyle(Paint.Style.STROKE);
            mBorder.setStrokeWidth(1.5f * mDensity);
            mBorder.setPathEffect(new CornerPathEffect(8 * mDensity));
            mShaded.set(mBorder);
        }

        void build(@NonNull CharSequence[] titles) {
            removeAllViews();
            for (int i = 0; i < titles.length; i++) {
                final int index = i;
                TextView tab = new TextView(getContext());
                tab.setText(titles[i]);
                tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                tab.setGravity(Gravity.CENTER);
                tab.setMaxLines(1);
                int padH = Math.round(16 * mDensity);
                int padV = Math.round(6 * mDensity);
                tab.setPadding(padH, padV, padH, padV);
                tab.setOnClickListener(v -> {
                    if (mPager != null) mPager.setCurrentItem(index, true);
                });
                LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, mInactiveHeight);
                addView(tab, lp);
            }
            applyStyles();
        }

        void setSelected(int position) {
            if (position == mSelected && getChildAt(position) != null
                    && ((LayoutParams) getChildAt(position).getLayoutParams()).height != mInactiveHeight) {
                return;
            }
            mSelected = position;
            applyStyles();
        }

        private void applyStyles() {
            int ink = ForkThemeUtils.getTextColor();
            int dim = ColorUtils.setAlphaComponent(ink, 0x8C);   // ~55%
            mBorder.setColor(ink);
            mShaded.setColor(ColorUtils.setAlphaComponent(ink, 0x55));
            for (int i = 0; i < getChildCount(); i++) {
                TextView tab = (TextView) getChildAt(i);
                boolean active = i == mSelected;
                tab.setTextColor(active ? ink : dim);
                tab.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
                LayoutParams lp = (LayoutParams) tab.getLayoutParams();
                // The active folder is taller, so it reads as raised above the rest.
                int wanted = active ? LayoutParams.MATCH_PARENT : mInactiveHeight;
                if (lp.height != wanted) {
                    lp.height = wanted;
                    lp.topMargin = active ? mActiveTopMargin : 0;
                    tab.setLayoutParams(lp);
                }
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float sw = mBorder.getStrokeWidth();
            float bottom = getHeight() - sw / 2;
            float endX = getWidth();
            View selected = getChildAt(mSelected);
            // Background folders: shaded outlines.
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child == null || child == selected) continue;
                float left = child.getLeft() + child.getTranslationX();
                float right = child.getRight() + child.getTranslationX();
                float top = child.getTop() + sw / 2;
                Path path = new Path();
                path.moveTo(left, bottom);
                path.lineTo(left, top);
                path.lineTo(right, top);
                path.lineTo(right + mSlant, bottom);
                canvas.drawPath(path, mShaded);
            }
            // The border line, wrapping around the active folder.
            Path path = new Path();
            if (selected == null) {
                path.moveTo(0f, bottom);
                path.lineTo(endX, bottom);
            } else {
                float left = selected.getLeft() + selected.getTranslationX();
                float right = selected.getRight() + selected.getTranslationX();
                float top = selected.getTop() + sw / 2;
                path.moveTo(0f, bottom);
                path.lineTo(left, bottom);
                path.lineTo(left, top);
                path.lineTo(right, top);
                path.lineTo(right + mSlant, bottom);
                path.lineTo(endX, bottom);
            }
            canvas.drawPath(path, mBorder);
        }
    }
}
