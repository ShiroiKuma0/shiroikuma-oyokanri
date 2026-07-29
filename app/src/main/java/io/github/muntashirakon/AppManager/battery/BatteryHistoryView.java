// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.battery;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;

/**
 * Fork: a zoomable, pannable time-axis chart of one app's stored buckets.
 *
 * <p><b>Bars are placed and sized by time, not by index.</b> Buckets are not
 * uniform — Doze defers the sampler, so an overnight bucket can be hours wide
 * while a daytime one is fifteen minutes — and an index-based chart would draw
 * those as equals, quietly erasing exactly the gaps that matter when you are
 * asking what happened while the phone was idle. Each bar therefore spans its
 * own {@code [start, end)} and the empty stretches stay visibly empty.
 *
 * <p>Pinch to zoom, drag to pan, double-tap to fit everything, tap a bar to
 * read it. The vertical scale is computed from the <b>visible</b> bars, so
 * zooming into a quiet stretch actually reveals its shape instead of leaving it
 * flattened against the axis by one distant spike.
 */
public class BatteryHistoryView extends View {
    /** One stored bucket. */
    public static class Bar {
        public final long start;
        public final long end;
        public final long value;

        public Bar(long start, long end, long value) {
            this.start = start;
            this.end = end;
            this.value = value;
        }
    }

    /** Told when the selection changes, so the caller can render a readout. */
    public interface OnBarSelected {
        void onBarSelected(@Nullable Bar bar, long viewStart, long viewEnd);
    }

    private static final long MIN_SPAN_MS = 60_000L;          // one minute
    private static final long[] TICK_LADDER = {
            60_000L, 5 * 60_000L, 15 * 60_000L, 30 * 60_000L, 3_600_000L,
            3 * 3_600_000L, 6 * 3_600_000L, 12 * 3_600_000L, 86_400_000L,
    };

    private final Paint mBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBarSelectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mAxisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<Bar> mBars = new ArrayList<>();
    /** Left gutter for the value labels. */
    private float mPlotLeft;
    private long mDataStart;
    private long mDataEnd;
    private long mViewStart;
    private long mViewEnd;
    @Nullable
    private Bar mSelected;
    @Nullable
    private OnBarSelected mListener;

    private final float mDensity;
    private final ScaleGestureDetector mScaleDetector;
    private final GestureDetector mGestureDetector;

    public BatteryHistoryView(@NonNull Context context) {
        this(context, null);
    }

    public BatteryHistoryView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        int accent = ForkThemeUtils.getTextColor();
        mBarPaint.setColor(accent);
        mBarSelectedPaint.setColor(0xFFFF0028);
        mAxisPaint.setColor(0xFF3A3A3A);
        mGridPaint.setColor(0xFF242424);
        mDayPaint.setColor(0xFF4A4A4A);
        mLabelPaint.setColor(ForkThemeUtils.getTextColor());
        mLabelPaint.setTextSize(13 * mDensity);

        mScaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(@NonNull ScaleGestureDetector detector) {
                        zoomAround(detector.getFocusX(), detector.getScaleFactor());
                        return true;
                    }
                });
        mGestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2,
                                    float distanceX, float distanceY) {
                pan(distanceX);
                return true;
            }

            @Override
            public boolean onSingleTapUp(@NonNull MotionEvent e) {
                selectAt(e.getX());
                return true;
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                fitAll();
                return true;
            }
        });
    }

    public void setOnBarSelected(@Nullable OnBarSelected listener) {
        mListener = listener;
    }

    /** Chronological buckets, oldest first. */
    public void setBars(@NonNull List<Bar> bars) {
        mBars.clear();
        mBars.addAll(bars);
        mBarPaint.setColor(ForkThemeUtils.getTextColor());
        mSelected = null;
        if (mBars.isEmpty()) {
            mDataStart = mDataEnd = 0;
        } else {
            mDataStart = mBars.get(0).start;
            mDataEnd = mBars.get(mBars.size() - 1).end;
            if (mDataEnd - mDataStart < MIN_SPAN_MS) mDataEnd = mDataStart + MIN_SPAN_MS;
        }
        fitAll();
    }

    public void fitAll() {
        mViewStart = mDataStart;
        mViewEnd = mDataEnd;
        mSelected = null;
        notifyListener();
        invalidate();
    }

    // ------------------------------------------------------------- gestures

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            // The chart lives inside a NestedScrollView; without this the first
            // vertical wobble of a pan hands the gesture to the scroller and the
            // chart never sees the rest of it.
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        }
        mScaleDetector.onTouchEvent(event);
        // While a pinch is in progress the scroll gesture would fight it.
        if (!mScaleDetector.isInProgress()) mGestureDetector.onTouchEvent(event);
        return true;
    }

    private float plotWidth() {
        return Math.max(1f, getWidth() - mPlotLeft);
    }

    private void zoomAround(float focusX, float scaleFactor) {
        if (scaleFactor <= 0 || getWidth() <= 0) return;
        long span = mViewEnd - mViewStart;
        long newSpan = (long) (span / scaleFactor);
        long fullSpan = Math.max(MIN_SPAN_MS, mDataEnd - mDataStart);
        newSpan = Math.max(MIN_SPAN_MS, Math.min(fullSpan, newSpan));
        // Keep whatever is under the fingers pinned in place.
        double focusFraction = Math.max(0, Math.min(1, (focusX - mPlotLeft) / plotWidth()));
        long focusTime = mViewStart + (long) (span * focusFraction);
        mViewStart = focusTime - (long) (newSpan * focusFraction);
        mViewEnd = mViewStart + newSpan;
        clampView();
        notifyListener();
        invalidate();
    }

    private void pan(float distanceX) {
        if (getWidth() <= 0) return;
        long span = mViewEnd - mViewStart;
        long delta = (long) (distanceX / plotWidth() * span);
        mViewStart += delta;
        mViewEnd += delta;
        clampView();
        notifyListener();
        invalidate();
    }

    private void clampView() {
        long span = mViewEnd - mViewStart;
        if (mViewStart < mDataStart) {
            mViewStart = mDataStart;
            mViewEnd = mViewStart + span;
        }
        if (mViewEnd > mDataEnd) {
            mViewEnd = mDataEnd;
            mViewStart = mViewEnd - span;
            if (mViewStart < mDataStart) mViewStart = mDataStart;
        }
    }

    private void selectAt(float x) {
        if (getWidth() <= 0 || mBars.isEmpty()) return;
        long span = mViewEnd - mViewStart;
        long t = mViewStart + (long) (span * Math.max(0, Math.min(1, (x - mPlotLeft) / plotWidth())));
        Bar hit = null;
        // A one-minute bar can be sub-pixel when zoomed out, so accept the
        // nearest bar within a few pixels rather than demanding a literal hit.
        long tolerance = (long) (span * (8 * mDensity / plotWidth()));
        long best = Long.MAX_VALUE;
        for (Bar bar : mBars) {
            if (t >= bar.start && t < bar.end) {
                hit = bar;
                break;
            }
            long distance = t < bar.start ? bar.start - t : t - bar.end;
            if (distance < best && distance <= tolerance) {
                best = distance;
                hit = bar;
            }
        }
        mSelected = hit;
        notifyListener();
        invalidate();
    }

    private void notifyListener() {
        if (mListener != null) mListener.onBarSelected(mSelected, mViewStart, mViewEnd);
    }

    // ---------------------------------------------------------------- draw

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
        float axisHeight = 14 * mDensity;
        float plotBottom = height - axisHeight;
        canvas.drawRect(0, plotBottom, width, plotBottom + Math.max(1, mDensity), mAxisPaint);
        if (mBars.isEmpty() || mViewEnd <= mViewStart) return;

        long span = mViewEnd - mViewStart;
        // Scale to the tallest visible bar first — the axis labels describe it.
        long max = 0;
        for (Bar bar : mBars) {
            if (bar.end <= mViewStart || bar.start >= mViewEnd) continue;
            max = Math.max(max, bar.value);
        }
        mPlotLeft = mLabelPaint.measureText(formatValue(max)) + 6 * mDensity;
        double pxPerMs = plotWidth() / (double) span;

        drawValueAxis(canvas, width, plotBottom, max);
        drawGrid(canvas, width, plotBottom, span, pxPerMs);

        if (max <= 0) return;

        for (Bar bar : mBars) {
            if (bar.end <= mViewStart || bar.start >= mViewEnd) continue;
            if (bar.value <= 0) continue;
            float left = mPlotLeft + (float) ((bar.start - mViewStart) * pxPerMs);
            float right = mPlotLeft + (float) ((bar.end - mViewStart) * pxPerMs);
            // Sub-pixel buckets must still be visible, and adjacent ones must
            // not merge into a solid block, hence a 1px floor and a hairline gap.
            if (right - left < 1f) right = left + 1f;
            else right -= Math.min(1f, (right - left) * 0.15f);
            float barHeight = Math.max(1f, (float) (bar.value / (double) max * (plotBottom - 2)));
            canvas.drawRect(left, plotBottom - barHeight, right, plotBottom,
                    bar == mSelected ? mBarSelectedPaint : mBarPaint);
        }
    }

    /**
     * A value axis, so the bar heights mean something.
     *
     * <p>The unit is <b>awake-equivalent time per bucket</b>: held wakelock,
     * radio-active and CPU time are already milliseconds, and each network
     * packet is charged 2 ms because a steady packet rate is what keeps the
     * phone out of deep Doze. It is not mAh — this device cannot supply that —
     * but it <i>is</i> a real duration rather than an index, which is why it
     * can be labelled at all.
     */
    private void drawValueAxis(@NonNull Canvas canvas, int width, float plotBottom, long max) {
        if (max <= 0) return;
        for (int step = 0; step <= 4; step++) {
            long value = max * step / 4;
            float y = plotBottom - (step / 4f) * (plotBottom - 2);
            canvas.drawRect(mPlotLeft, y, width, y + Math.max(1, mDensity * 0.5f), mGridPaint);
            if (step == 0) continue;
            String text = formatValue(value);
            canvas.drawText(text, mPlotLeft - mLabelPaint.measureText(text) - 3 * mDensity,
                    y + mLabelPaint.getTextSize() / 3f, mLabelPaint);
        }
    }

    @NonNull
    static String formatValue(long ms) {
        if (ms >= 3_600_000L) return String.format(java.util.Locale.getDefault(), "%.1fh", ms / 3_600_000d);
        if (ms >= 60_000L) return String.format(java.util.Locale.getDefault(), "%.0fm", ms / 60_000d);
        if (ms >= 1_000L) return String.format(java.util.Locale.getDefault(), "%.0fs", ms / 1_000d);
        return ms + "ms";
    }

    /**
     * Day delimiters always; a finer tick ladder underneath them once the span
     * is short enough for the labels not to collide.
     */
    private void drawGrid(@NonNull Canvas canvas, int width, float plotBottom, long span,
                          double pxPerMs) {
        long tick = TICK_LADDER[TICK_LADDER.length - 1];
        for (long candidate : TICK_LADDER) {
            if (span / candidate <= 8) {
                tick = candidate;
                break;
            }
        }
        boolean labelWithDate = span > 36 * 3_600_000L;

        // Finer ticks, aligned to local wall-clock boundaries.
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(mViewStart);
        cal.set(Calendar.MILLISECOND, 0);
        cal.set(Calendar.SECOND, 0);
        if (tick >= 3_600_000L) cal.set(Calendar.MINUTE, 0);
        if (tick >= 86_400_000L) cal.set(Calendar.HOUR_OF_DAY, 0);
        long t = cal.getTimeInMillis();
        while (t < mViewStart) t += tick;
        java.text.DateFormat timeFormat = android.text.format.DateFormat.getTimeFormat(getContext());
        for (; t <= mViewEnd; t += tick) {
            float x = mPlotLeft + (float) ((t - mViewStart) * pxPerMs);
            canvas.drawRect(x, 0, x + Math.max(1, mDensity * 0.5f), plotBottom, mGridPaint);
            if (!labelWithDate) {
                canvas.drawText(timeFormat.format(new Date(t)), x + 2 * mDensity,
                        plotBottom + 11 * mDensity, mLabelPaint);
            }
        }

        // Day boundaries — brighter, and always labelled with the date.
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(mViewStart);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        CharSequence dayPattern = DateFormat.getBestDateTimePattern(
                java.util.Locale.getDefault(), "MMMd");
        java.text.SimpleDateFormat dayFormat =
                new java.text.SimpleDateFormat(dayPattern.toString(), java.util.Locale.getDefault());
        for (long d = day.getTimeInMillis(); d <= mViewEnd; d += 86_400_000L) {
            if (d < mViewStart) continue;
            float x = mPlotLeft + (float) ((d - mViewStart) * pxPerMs);
            canvas.drawRect(x, 0, x + Math.max(1, mDensity), plotBottom, mDayPaint);
            canvas.drawText(dayFormat.format(new Date(d)), x + 2 * mDensity,
                    11 * mDensity, mLabelPaint);
        }
    }
}
