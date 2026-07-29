// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.battery;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
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
 * Fork: the device's own battery history — level over time, with charging
 * stretches called out in red.
 *
 * <p>Charging must be marked, not merged: a discharge chart that silently
 * includes charging periods shows a line going <i>up</i>, and once a reader
 * sees that they distrust the whole thing. Voltage is drawn as a fainter second
 * trace where it is available, because the percentage is quantised to whole
 * points while millivolts show the actual shape between them.
 *
 * <p>Pans and zooms on the same rules as {@link BatteryHistoryView} — drag to
 * scroll back through history, pinch to zoom, double-tap to fit.
 */
public class BatteryLevelView extends View {
    /** One device-level bucket. */
    public static class Point {
        public final long ts;
        public final int level;
        public final int voltageMv;
        public final boolean charging;

        public Point(long ts, int level, int voltageMv, boolean charging) {
            this.ts = ts;
            this.level = level;
            this.voltageMv = voltageMv;
            this.charging = charging;
        }
    }

    public interface OnRangeChanged {
        void onRangeChanged(long viewStart, long viewEnd, @Nullable Point at);
    }

    private static final long MIN_SPAN_MS = 5 * 60_000L;
    private static final int COLOR_CHARGING = 0xFFFF0028;

    private final Paint mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mChargePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<Point> mPoints = new ArrayList<>();
    private long mDataStart;
    private long mDataEnd;
    private long mViewStart;
    private long mViewEnd;
    @Nullable
    private OnRangeChanged mListener;
    @Nullable
    private Point mTouched;

    /** Left gutter reserved for the percentage labels. */
    private float mPlotLeft;
    private final float mDensity;
    private final ScaleGestureDetector mScaleDetector;
    private final GestureDetector mGestureDetector;

    public BatteryLevelView(@NonNull Context context) {
        this(context, null);
    }

    public BatteryLevelView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        int accent = ForkThemeUtils.getTextColor();
        mLinePaint.setColor(accent);
        mLinePaint.setStyle(Paint.Style.STROKE);
        mLinePaint.setStrokeWidth(1.6f * mDensity);
        mFillPaint.setColor((accent & 0x00FFFFFF) | 0x30000000);
        mChargePaint.setColor((COLOR_CHARGING & 0x00FFFFFF) | 0x44000000);
        mGridPaint.setColor(0xFF242424);
        mDayPaint.setColor(0xFF4A4A4A);
        mLabelPaint.setColor(ForkThemeUtils.getTextColor());
        mLabelPaint.setTextSize(14 * mDensity);

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
                touchAt(e.getX());
                return true;
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                fitAll();
                return true;
            }
        });
    }

    public void setOnRangeChanged(@Nullable OnRangeChanged listener) {
        mListener = listener;
    }

    public void setPoints(@NonNull List<Point> points) {
        mPoints.clear();
        mPoints.addAll(points);
        mLinePaint.setColor(ForkThemeUtils.getTextColor());
        mFillPaint.setColor((ForkThemeUtils.getTextColor() & 0x00FFFFFF) | 0x30000000);
        mLabelPaint.setColor(ForkThemeUtils.getTextColor());
        mTouched = null;
        if (mPoints.isEmpty()) {
            mDataStart = mDataEnd = 0;
        } else {
            mDataStart = mPoints.get(0).ts;
            mDataEnd = mPoints.get(mPoints.size() - 1).ts;
            if (mDataEnd - mDataStart < MIN_SPAN_MS) mDataEnd = mDataStart + MIN_SPAN_MS;
        }
        fitAll();
    }

    /** Opens on the most recent {@code hours}, leaving the rest reachable by dragging left. */
    public void showLast(int hours) {
        if (mDataEnd <= mDataStart) return;
        long span = Math.min(mDataEnd - mDataStart, hours * 3_600_000L);
        mViewEnd = mDataEnd;
        mViewStart = Math.max(mDataStart, mViewEnd - span);
        notifyListener();
        invalidate();
    }

    public void fitAll() {
        mViewStart = mDataStart;
        mViewEnd = mDataEnd;
        mTouched = null;
        notifyListener();
        invalidate();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        }
        mScaleDetector.onTouchEvent(event);
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

    private void touchAt(float x) {
        if (getWidth() <= 0 || mPoints.isEmpty()) return;
        long span = mViewEnd - mViewStart;
        long t = mViewStart + (long) (span * Math.max(0, Math.min(1, (x - mPlotLeft) / plotWidth())));
        Point best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Point p : mPoints) {
            long d = Math.abs(p.ts - t);
            if (d < bestDistance) {
                bestDistance = d;
                best = p;
            }
        }
        mTouched = best;
        notifyListener();
        invalidate();
    }

    private void notifyListener() {
        if (mListener != null) mListener.onRangeChanged(mViewStart, mViewEnd, mTouched);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0 || mPoints.size() < 2 || mViewEnd <= mViewStart) return;
        float axisHeight = 20 * mDensity;
        // Gutter wide enough for "100%" at the label size.
        mPlotLeft = mLabelPaint.measureText("100%") + 6 * mDensity;
        float plotBottom = height - axisHeight;
        float plotWidth = plotWidth();
        long span = mViewEnd - mViewStart;
        double pxPerMs = plotWidth / (double) span;

        drawPercentAxis(canvas, width, plotBottom);
        drawGrid(canvas, width, plotBottom, span, pxPerMs);

        // Charging stretches first, as a red band behind the trace.
        Point previous = null;
        for (Point p : mPoints) {
            if (previous != null && previous.charging) {
                float left = mPlotLeft + (float) ((previous.ts - mViewStart) * pxPerMs);
                float right = mPlotLeft + (float) ((p.ts - mViewStart) * pxPerMs);
                if (right > mPlotLeft && left < width) {
                    canvas.drawRect(Math.max(mPlotLeft, left), 0, Math.min(width, right),
                            plotBottom, mChargePaint);
                }
            }
            previous = p;
        }

        // Level trace, 0–100 %. Voltage is deliberately NOT drawn here: this
        // axis is labelled in percent, and a second series on a different scale
        // sharing it is a lie — the old millivolt trace dived off the bottom
        // whenever one low reading stretched its own min/max. Voltage is still
        // stored, and still reported in the caption when a point is tapped.
        Path line = new Path();
        Path fill = new Path();
        boolean started = false;
        float lastX = mPlotLeft;
        for (Point p : mPoints) {
            if (p.level < 0) continue;
            float x = mPlotLeft + (float) ((p.ts - mViewStart) * pxPerMs);
            float y = plotBottom - (p.level / 100f) * (plotBottom - 2);
            if (!started) {
                line.moveTo(x, y);
                fill.moveTo(x, plotBottom);
                fill.lineTo(x, y);
                started = true;
            } else {
                line.lineTo(x, y);
                fill.lineTo(x, y);
            }
            lastX = x;
        }
        if (started) {
            fill.lineTo(lastX, plotBottom);
            fill.close();
            canvas.drawPath(fill, mFillPaint);
            canvas.drawPath(line, mLinePaint);
        }
    }

    /** Horizontal 0/25/50/75/100 % rules with labels in the left gutter. */
    private void drawPercentAxis(@NonNull Canvas canvas, int width, float plotBottom) {
        for (int pct = 0; pct <= 100; pct += 25) {
            float y = plotBottom - (pct / 100f) * (plotBottom - 2);
            canvas.drawRect(mPlotLeft, y, width, y + Math.max(1, mDensity * 0.5f), mGridPaint);
            String label = pct + "%";
            float textWidth = mLabelPaint.measureText(label);
            // Nudge the extremes inward so they are not clipped by the edges.
            float baseline = pct == 100 ? y + mLabelPaint.getTextSize()
                    : pct == 0 ? y - 2 * mDensity : y + mLabelPaint.getTextSize() / 3f;
            canvas.drawText(label, mPlotLeft - textWidth - 3 * mDensity, baseline, mLabelPaint);
        }
    }

    private void drawGrid(@NonNull Canvas canvas, int width, float plotBottom, long span,
                          double pxPerMs) {
        long tick = span > 12 * 3_600_000L ? 6 * 3_600_000L
                : span > 3 * 3_600_000L ? 3_600_000L : 15 * 60_000L;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(mViewStart);
        cal.set(Calendar.MILLISECOND, 0);
        cal.set(Calendar.SECOND, 0);
        if (tick >= 3_600_000L) cal.set(Calendar.MINUTE, 0);
        long t = cal.getTimeInMillis();
        while (t < mViewStart) t += tick;
        java.text.DateFormat timeFormat = android.text.format.DateFormat.getTimeFormat(getContext());
        for (; t <= mViewEnd; t += tick) {
            float x = mPlotLeft + (float) ((t - mViewStart) * pxPerMs);
            canvas.drawRect(x, 0, x + Math.max(1, mDensity * 0.5f), plotBottom, mGridPaint);
            canvas.drawText(timeFormat.format(new Date(t)), x + 2 * mDensity,
                    plotBottom + 15 * mDensity, mLabelPaint);
        }
        // Day delimiters.
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(mViewStart);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        for (long d = day.getTimeInMillis(); d <= mViewEnd; d += 86_400_000L) {
            if (d < mViewStart) continue;
            float x = mPlotLeft + (float) ((d - mViewStart) * pxPerMs);
            canvas.drawRect(x, 0, x + Math.max(1, mDensity), plotBottom, mDayPaint);
        }
    }
}
