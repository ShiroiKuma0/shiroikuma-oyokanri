// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.Px;
import androidx.core.widget.NestedScrollView;

/**
 * Fork: a scroller that grows with its content up to a ceiling and scrolls
 * beyond it.
 *
 * <p>It exists for the batch-progress dialog's in-flight list, whose length is
 * decided by the CPU: a backup runs one app per core, so the list is one block
 * on a small device and eight on this one. {@code wrap_content} would push the
 * dialog's buttons off the screen at eight; a fixed height would leave a hole
 * under the list at one. The framework has no {@code maxHeight} for a
 * {@link NestedScrollView} — it is a {@code FrameLayout} at heart — so the
 * ceiling is imposed in {@link #onMeasure} by handing the superclass an
 * {@code AT_MOST} spec, which is exactly what {@code wrap_content} against a
 * bounded parent already means.
 */
public class MaxHeightScrollView extends NestedScrollView {
    @Px
    private int mMaxHeight;

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

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mMaxHeight > 0) {
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(mMaxHeight, MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
