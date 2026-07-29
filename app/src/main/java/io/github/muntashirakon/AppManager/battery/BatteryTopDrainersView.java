// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.battery;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fork: the last two hours' worst offenders, as colour columns with their share.
 *
 * <p>Sits beside the device's level chart so the two answer one question
 * together: the chart shows <i>how fast</i> the battery went down, this shows
 * <i>who</i>. Two hours rather than the list's window because "what is eating
 * it right now" is a different question from "what ate it today", and the
 * answer that prompts action is the recent one.
 */
public class BatteryTopDrainersView extends View {
    /**
     * Distinct hues, worst-first so the leader reads as the alarming one. The
     * panel scrolls horizontally now, so the palette cycles rather than capping
     * how many columns can be shown.
     */
    private static final int[] PALETTE = {
            0xFFFF0028, 0xFFFFC24B, 0xFFFFFF00, 0xFF6ADF6A, 0xFF7FD4FF,
            0xFFC792EA, 0xFFFF8A65, 0xFFA5D6A7, 0xFF80CBC4, 0xFFB0BEC5,
    };
    /** Width of one column's slot; the view measures itself from this. */
    private static final float SLOT_DP = 104f;

    public static class Slice {
        public final String label;
        /** Share of the window's measured impact, 0–1 — drives the bar height. */
        public final float share;
        /**
         * Battery percentage points this app is estimated to have used, i.e.
         * {@link #share} of the drop the device actually recorded. This is the
         * number worth reading: a share-of-total figure says an app is 18% of
         * whatever was measured, which sounds alarming even when the phone lost
         * three percent all day.
         */
        public final float batteryPct;
        public final int uid;
        @Nullable
        public final String packageName;

        public Slice(@NonNull String label, float share, float batteryPct, int uid,
                     @Nullable String packageName) {
            this.label = label;
            this.share = share;
            this.batteryPct = batteryPct;
            this.uid = uid;
            this.packageName = packageName;
        }
    }

    /** Tapping a column jumps to that app's battery panel. */
    public interface OnSliceClick {
        void onSliceClick(@NonNull Slice slice);
    }

    private final Paint mBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mValuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Slice> mSlices = new ArrayList<>();
    private final float mDensity;
    @Nullable
    private OnSliceClick mOnSliceClick;
    /** Column count at last draw — the hit test must match what was drawn. */
    private int mDrawnColumns;

    public BatteryTopDrainersView(@NonNull Context context) {
        this(context, null);
    }

    public BatteryTopDrainersView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        mBasePaint.setColor(0xFF242424);
        mLabelPaint.setColor(ForkThemeUtils.getTextColor());
        mLabelPaint.setTextSize(16 * mDensity);
        mValuePaint.setTextSize(22 * mDensity);
        mValuePaint.setFakeBoldText(true);
    }

    public void setOnSliceClick(@Nullable OnSliceClick listener) {
        mOnSliceClick = listener;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(@NonNull android.view.MotionEvent event) {
        if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP
                && mOnSliceClick != null && mDrawnColumns > 0) {
            int index = (int) (event.getX() / (getWidth() / (float) mDrawnColumns));
            if (index >= 0 && index < mSlices.size()) {
                performClick();
                mOnSliceClick.onSliceClick(mSlices.get(index));
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    public void setSlices(@NonNull List<Slice> slices) {
        mSlices.clear();
        mSlices.addAll(slices);
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        // Fixed slot per column, so columns keep a readable width however many
        // there are and the parent HorizontalScrollView gets real overflow.
        int desired = Math.max(getMeasuredWidth(),
                Math.round(Math.max(1, mSlices.size()) * SLOT_DP * mDensity));
        setMeasuredDimension(desired, getMeasuredHeight());
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0 || mSlices.isEmpty()) return;
        float labelBand = 48 * mDensity;   // two lines of 16sp label
        float valueBand = 28 * mDensity;   // percentage above each column
        float plotTop = valueBand;
        float plotBottom = height - labelBand;
        if (plotBottom <= plotTop) return;

        mDrawnColumns = mSlices.size();
        float slot = width / (float) mSlices.size();
        float barWidth = Math.min(slot * 0.82f, 64 * mDensity);
        float maxShare = 0;
        for (Slice s : mSlices) maxShare = Math.max(maxShare, s.share);
        if (maxShare <= 0) return;

        for (int i = 0; i < mSlices.size(); i++) {
            Slice s = mSlices.get(i);
            float cx = slot * i + slot / 2f;
            float left = cx - barWidth / 2f;
            float right = cx + barWidth / 2f;
            // Track, so a small column still reads as "measured, small".
            canvas.drawRect(left, plotTop, right, plotBottom, mBasePaint);
            float barHeight = Math.max(1f, (s.share / maxShare) * (plotBottom - plotTop));
            mBarPaint.setColor(PALETTE[i % PALETTE.length]);
            canvas.drawRect(left, plotBottom - barHeight, right, plotBottom, mBarPaint);

            String pct = formatBatteryPercent(s.batteryPct);
            // Yellow like the rest of the header — the column beneath already
            // carries the per-app colour, so the number need not repeat it.
            mValuePaint.setColor(ForkThemeUtils.getTextColor());
            float pctWidth = mValuePaint.measureText(pct);
            canvas.drawText(pct, cx - pctWidth / 2f, valueBand - 6 * mDensity, mValuePaint);

            // Labels are cramped by design — two short lines beat one ellipsis.
            drawWrappedLabel(canvas, s.label, cx, plotBottom + 17 * mDensity, slot - 6 * mDensity);
        }
    }

    /**
     * Percentage points of battery. Sub-tenth values collapse to "&lt;0.1%"
     * rather than rounding to a flat 0%, which would read as "used nothing".
     */
    @NonNull
    private static String formatBatteryPercent(float pct) {
        if (pct <= 0) return "0%";
        if (pct < 0.1f) return "<0.1%";
        if (pct < 10f) return String.format(Locale.getDefault(), "%.1f%%", pct);
        return String.format(Locale.getDefault(), "%.0f%%", pct);
    }

    private void drawWrappedLabel(@NonNull Canvas canvas, @NonNull String label, float cx,
                                  float baseline, float maxWidth) {
        String first = label;
        String second = null;
        if (mLabelPaint.measureText(label) > maxWidth) {
            int cut = mLabelPaint.breakText(label, true, maxWidth, null);
            if (cut > 0 && cut < label.length()) {
                first = label.substring(0, cut);
                String rest = label.substring(cut);
                int cut2 = mLabelPaint.breakText(rest, true, maxWidth, null);
                second = cut2 < rest.length() && cut2 > 1
                        ? rest.substring(0, Math.max(1, cut2 - 1)) + "…" : rest;
            }
        }
        canvas.drawText(first, cx - mLabelPaint.measureText(first) / 2f, baseline, mLabelPaint);
        if (second != null) {
            canvas.drawText(second, cx - mLabelPaint.measureText(second) / 2f,
                    baseline + 18 * mDensity, mLabelPaint);
        }
    }
}
