// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.scanner.vt;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Fork (白い熊, +081): the ring at the top of the VirusTotal report dialog.
 *
 * <p>Two faces. <b>Result</b>: the engines as coloured segments of one ring — flagged, clean, no
 * answer — swept in once, with the count in the middle. <b>Scanning</b>: a determinate arc while a
 * percentage exists (the upload), otherwise an arc that travels round the ring, so a scan in flight
 * is visibly in flight even while VirusTotal says nothing for minutes.
 */
public class VtGaugeView extends View {
    private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mArc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBig = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSmall = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mOval = new RectF();
    private final float mDensity;

    private boolean mScanning;
    private int mPercent = -1;
    private float[] mFractions = new float[0];
    private int[] mColors = new int[0];
    private float mSweep = 1f;
    @NonNull
    private String mCenter = "";
    @NonNull
    private String mCaption = "";
    @ColorInt
    private int mInk;
    @Nullable
    private ValueAnimator mAnimator;

    public VtGaugeView(@NonNull Context context) {
        super(context);
        mDensity = getResources().getDisplayMetrics().density;
        float stroke = 9f * mDensity;
        mTrack.setStyle(Paint.Style.STROKE);
        mTrack.setStrokeWidth(stroke);
        mArc.setStyle(Paint.Style.STROKE);
        mArc.setStrokeWidth(stroke);
        mBig.setTextAlign(Paint.Align.CENTER);
        mBig.setTypeface(Typeface.DEFAULT_BOLD);
        mSmall.setTextAlign(Paint.Align.CENTER);
    }

    /** The colour of the empty ring and of the caption under the number. */
    public void setInk(@ColorInt int ink, @ColorInt int dim) {
        mInk = ink;
        mTrack.setColor((dim & 0x00FFFFFF) | 0x40000000);
        mSmall.setColor(dim);
        invalidate();
    }

    /**
     * Result face. {@code counts} and {@code colors} are parallel; segments are drawn clockwise
     * from the top in the order given, so put what matters first.
     */
    public void showResult(@NonNull int[] counts, @NonNull int[] colors, @NonNull String center,
                           @ColorInt int centerColor, @NonNull String caption) {
        mScanning = false;
        int total = 0;
        for (int c : counts) total += Math.max(0, c);
        mFractions = new float[counts.length];
        for (int i = 0; i < counts.length; ++i) {
            mFractions[i] = total == 0 ? 0f : Math.max(0, counts[i]) / (float) total;
        }
        mColors = colors;
        mCenter = center;
        mCaption = caption;
        mBig.setColor(centerColor);
        if (mAnimator != null) mAnimator.cancel();
        mAnimator = ValueAnimator.ofFloat(0f, 1f);
        mAnimator.setDuration(700);
        mAnimator.setInterpolator(new DecelerateInterpolator());
        mAnimator.addUpdateListener(a -> {
            mSweep = (float) a.getAnimatedValue();
            invalidate();
        });
        mAnimator.start();
    }

    /** A plain full ring in one colour with a glyph in the middle — failed, cancelled, not scanned. */
    public void showState(@ColorInt int color, @NonNull String center, @NonNull String caption) {
        showResult(new int[]{1}, new int[]{color}, center, color, caption);
    }

    /** Scanning face. {@code percent} below zero means "no number to show" — the arc travels. */
    public void showScanning(int percent, @NonNull String center, @NonNull String caption) {
        if (mAnimator != null) {
            mAnimator.cancel();
            mAnimator = null;
        }
        mScanning = true;
        mPercent = percent;
        mCenter = center;
        mCaption = caption;
        mBig.setColor(mInk);
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mAnimator != null) mAnimator.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float half = mTrack.getStrokeWidth() / 2f + mDensity;
        float size = Math.min(w, h);
        mOval.set((w - size) / 2f + half, (h - size) / 2f + half,
                (w + size) / 2f - half, (h + size) / 2f - half);
        canvas.drawArc(mOval, 0, 360, false, mTrack);

        if (mScanning) {
            mArc.setColor(mInk);
            mArc.setStrokeCap(Paint.Cap.ROUND);
            if (mPercent >= 0) {
                canvas.drawArc(mOval, -90, Math.max(2f, 3.6f * mPercent), false, mArc);
            } else {
                float start = (SystemClock.uptimeMillis() % 1600L) / 1600f * 360f - 90f;
                canvas.drawArc(mOval, start, 80f, false, mArc);
            }
            // Keep travelling (and keep a determinate arc alive across its updates).
            postInvalidateOnAnimation();
        } else {
            mArc.setStrokeCap(Paint.Cap.BUTT);
            float start = -90f;
            float gap = mFractions.length > 1 ? 2f : 0f;
            for (int i = 0; i < mFractions.length; ++i) {
                float sweep = 360f * mFractions[i] * mSweep;
                if (sweep <= 0f) continue;
                mArc.setColor(mColors[i]);
                canvas.drawArc(mOval, start, Math.max(0.5f, sweep - gap), false, mArc);
                start += sweep;
            }
        }

        float cx = w / 2f;
        float cy = h / 2f;
        boolean hasCaption = !mCaption.isEmpty();
        float bigSize = size * (mCenter.length() > 5 ? 0.17f : mCenter.length() > 3 ? 0.21f : 0.27f);
        mBig.setTextSize(bigSize);
        mSmall.setTextSize(size * 0.105f);
        float bigBaseline = cy - (mBig.descent() + mBig.ascent()) / 2f - (hasCaption ? size * 0.05f : 0f);
        canvas.drawText(mCenter, cx, bigBaseline, mBig);
        if (hasCaption) {
            canvas.drawText(mCaption, cx, bigBaseline + mSmall.getTextSize() * 1.35f, mSmall);
        }
    }
}
