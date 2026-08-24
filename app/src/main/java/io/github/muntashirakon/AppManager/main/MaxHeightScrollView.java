// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.Px;
import androidx.core.widget.NestedScrollView;

/**
 * Fork: a scroller that grows with its content up to a ceiling, and walks
 * through that content by itself while nobody is touching it.
 *
 * <p><b>The ceiling.</b> It exists for the batch-progress dialog's in-flight
 * list, whose length is decided by the CPU: a backup runs one app per core, so
 * the list is one block on a small device and eight on this one.
 * {@code wrap_content} would push the dialog's buttons off the screen at eight;
 * a fixed height would leave a hole under the list at one. The framework has no
 * {@code maxHeight} for a {@link NestedScrollView} — it is a {@code FrameLayout}
 * at heart — so the ceiling is imposed in {@link #onMeasure} by handing the
 * superclass an {@code AT_MOST} spec, which is exactly what {@code wrap_content}
 * against a bounded parent already means.
 *
 * <p><b>The walk.</b> A ceiling alone leaves most of the list below the fold:
 * two or three blocks fit, the other five are reachable only by dragging, and
 * this is a progress readout that is meant to be watched rather than operated.
 * So it advances one row at a time, dwelling on each, and wraps back to the top
 * at the end. Rows rather than pixels: a crawl is hard to read and lands text
 * mid-line, whereas stepping to a row's own top edge always presents a whole
 * block.
 *
 * <p><b>Touching stops it.</b> Any touch — including the one that starts a
 * fling, whose settling continues long after the finger is gone — pushes the
 * resume moment out by {@link #TOUCH_QUIET_MS}, so a deliberate look at one row
 * is never yanked away, and the walk simply resumes from wherever it was left.
 */
public class MaxHeightScrollView extends NestedScrollView {
    /** How long each row is held before advancing to the next. */
    private static final long STEP_INTERVAL_MS = 2500L;
    /** Untouched for this long before the walk resumes. */
    private static final long TOUCH_QUIET_MS = 6000L;

    @Px
    private int mMaxHeight;
    private boolean mAutoScroll;
    private long mLastTouchAtRealtime;

    private final Runnable mStep = new Runnable() {
        @Override
        public void run() {
            if (!mAutoScroll) {
                return;
            }
            long sinceTouch = SystemClock.elapsedRealtime() - mLastTouchAtRealtime;
            if (mLastTouchAtRealtime != 0L && sinceTouch < TOUCH_QUIET_MS) {
                // Come back when the quiet period is actually up, rather than
                // waking on the step interval only to find it is not.
                postDelayed(this, TOUCH_QUIET_MS - sinceTouch);
                return;
            }
            stepOnce();
            postDelayed(this, STEP_INTERVAL_MS);
        }
    };

    public MaxHeightScrollView(@NonNull Context context) {
        super(context);
    }

    public MaxHeightScrollView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public MaxHeightScrollView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** {@code 0} (the default) means no ceiling: behave as an ordinary scroller. */
    public void setMaxHeight(@Px int maxHeight) {
        if (mMaxHeight == maxHeight) {
            return;
        }
        mMaxHeight = maxHeight;
        requestLayout();
    }

    /** Walk through the content while it is not being touched. Off by default. */
    public void setAutoScrollEnabled(boolean enabled) {
        if (mAutoScroll == enabled) {
            return;
        }
        mAutoScroll = enabled;
        removeCallbacks(mStep);
        if (enabled) {
            postDelayed(mStep, STEP_INTERVAL_MS);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        noteTouch();
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        noteTouch();
        return super.onTouchEvent(ev);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mAutoScroll) {
            postDelayed(mStep, STEP_INTERVAL_MS);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        // A posted callback holds this view, and this view holds the dialog.
        removeCallbacks(mStep);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mMaxHeight > 0) {
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(mMaxHeight, MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private void noteTouch() {
        mLastTouchAtRealtime = SystemClock.elapsedRealtime();
    }

    /** Advance to the next row's top edge, or wrap to the start at the end. */
    private void stepOnce() {
        View content = getChildAt(0);
        if (content == null) {
            return;
        }
        int range = content.getHeight() - (getHeight() - getPaddingTop() - getPaddingBottom());
        if (range <= 0) {
            // Everything fits; there is nothing to walk through.
            return;
        }
        int y = getScrollY();
        if (y >= range) {
            smoothScrollTo(0, 0);
            return;
        }
        smoothScrollTo(0, Math.min(nextRowTop(content, y), range));
    }

    /**
     * The top of the first row that begins below the current offset. Rows differ
     * in height — a row with no note, no destination yet, or a two-line stage is
     * not the height of its neighbours — so a fixed step would drift out of
     * alignment within a few moves. Falls back to the end of the range when the
     * last row is already showing, which the next step then wraps.
     */
    private int nextRowTop(@NonNull View content, int currentY) {
        if (!(content instanceof ViewGroup)) {
            return Integer.MAX_VALUE;
        }
        ViewGroup rows = (ViewGroup) content;
        for (int i = 0; i < rows.getChildCount(); ++i) {
            View row = rows.getChildAt(i);
            if (row.getVisibility() == GONE) {
                continue;
            }
            // +1 so the row we are already parked on does not re-select itself.
            if (row.getTop() > currentY + 1) {
                return row.getTop();
            }
        }
        return Integer.MAX_VALUE;
    }
}
