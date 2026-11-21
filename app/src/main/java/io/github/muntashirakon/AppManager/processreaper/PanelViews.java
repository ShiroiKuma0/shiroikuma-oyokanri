// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.processreaper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.muntashirakon.AppManager.fonts.ColorPrefs;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;

/**
 * Fork (白い熊, +106): the building blocks of the monitor's panels and of the filter-room page,
 * so a panel is figures, bars, tiles and a chart instead of a wall of text: a large figure with
 * its caption, a meter, stat tiles, status chips, underlined section headings, dim-gold notes
 * with a left rule, a monospace table, and {@link Chart}. Colour carries meaning only — the
 * theme's text colour for values, dim gold for labels, the warning / ok / failure roles for
 * state, one second colour for a second curve.
 */
final class PanelViews {
    private PanelViews() {
    }

    @ColorInt
    static int ink() {
        return ForkThemeUtils.getTextColor();
    }

    @ColorInt
    static int dim(@NonNull Context c) {
        return ColorPrefs.getColor(c, ColorPrefs.SETTINGS_DESCRIPTION);
    }

    @ColorInt
    static int warn(@NonNull Context c) {
        return ColorPrefs.getColor(c, ColorPrefs.MONITOR_LEAK);
    }

    @ColorInt
    static int ok(@NonNull Context c) {
        return ColorPrefs.getColor(c, ColorPrefs.OPLOG_OK);
    }

    @ColorInt
    static int bad(@NonNull Context c) {
        return ColorPrefs.getColor(c, ColorPrefs.OPLOG_FAIL);
    }

    /** The colour of a second curve. */
    @ColorInt
    static int alt(@NonNull Context c) {
        return ColorPrefs.getColor(c, ColorPrefs.MONITOR_USER_PROTECTED);
    }

    static int dp(@NonNull Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    @NonNull
    static LinearLayout column(@NonNull Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    /** A dialog body: the column inside a scroller, with the dialog's side padding. */
    @NonNull
    static ScrollView dialogBody(@NonNull LinearLayout column) {
        Context c = column.getContext();
        column.setPadding(dp(c, 22), dp(c, 6), dp(c, 22), dp(c, 4));
        ScrollView s = new ScrollView(c);
        s.setVerticalScrollBarEnabled(false);
        s.addView(column, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    static void gap(@NonNull LinearLayout column, float dp) {
        View v = new View(column.getContext());
        column.addView(v, new LinearLayout.LayoutParams(1, dp(column.getContext(), dp)));
    }

    @NonNull
    private static TextView text(@NonNull Context c, @Nullable CharSequence t, float sp, @ColorInt int color, boolean bold) {
        TextView v = new TextView(c);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(v.getTypeface(), Typeface.BOLD);
        return v;
    }

    /** The one figure a panel is about: large, with its unit beside it and a caption below. */
    @NonNull
    static View hero(@NonNull Context c, @NonNull String value, @Nullable String unit, @Nullable CharSequence caption,
                     @ColorInt int color) {
        LinearLayout col = column(c);
        SpannableStringBuilder sb = new SpannableStringBuilder(value);
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (unit != null && !unit.isEmpty()) {
            int from = sb.length();
            sb.append("  ").append(unit);
            sb.setSpan(new RelativeSizeSpan(0.36f), from, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView big = text(c, sb, 46, color, false);
        big.setIncludeFontPadding(false);
        col.addView(big);
        if (caption != null) col.addView(text(c, caption, 13, dim(c), false));
        return col;
    }

    /** A state line: a coloured dot and a bold word. */
    @NonNull
    static View status(@NonNull Context c, @NonNull CharSequence label, @ColorInt int color) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(c);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        dot.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 14), dp(c, 14));
        lp.setMarginEnd(dp(c, 10));
        row.addView(dot, lp);
        row.addView(text(c, label, 20, color, true));
        return row;
    }

    /** A bar from 0 to 1 with a caption at each end. */
    @NonNull
    static View meter(@NonNull Context c, float fraction, @ColorInt int color, @Nullable String left, @Nullable String right) {
        LinearLayout col = column(c);
        col.addView(new Meter(c, fraction, color), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 12)));
        if (left != null || right != null) {
            LinearLayout row = new LinearLayout(c);
            TextView l = text(c, left, 12, dim(c), false);
            row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(text(c, right, 12, dim(c), false));
            col.addView(row);
        }
        return col;
    }

    private static final class Meter extends View {
        private final float mFraction;
        private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG), mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();

        Meter(Context c, float fraction, int color) {
            super(c);
            mFraction = Math.max(0f, Math.min(1f, fraction));
            mTrack.setStyle(Paint.Style.STROKE);
            mTrack.setStrokeWidth(dp(c, 1.25f));
            mTrack.setColor(color);
            mFill.setColor(color);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            float s = mTrack.getStrokeWidth() / 2f, h = getHeight(), w = getWidth();
            mRect.set(s, s, w - s, h - s);
            canvas.drawRoundRect(mRect, h / 2f, h / 2f, mTrack);
            float inset = s * 2 + dp(getContext(), 1.5f);
            float fw = (w - 2 * inset) * mFraction;
            if (fw > 0) {
                mRect.set(inset, inset, inset + Math.max(fw, h - 2 * inset), h - inset);
                canvas.drawRoundRect(mRect, h / 2f, h / 2f, mFill);
            }
        }
    }

    /** One stat: a small dim-gold label over a bold value, in a thin frame. */
    static final class Tile {
        final CharSequence label, value;
        final int color;

        Tile(@NonNull CharSequence label, @NonNull CharSequence value, @ColorInt int color) {
            this.label = label;
            this.value = value;
            this.color = color;
        }
    }

    /** Tiles in rows of {@code perRow}, all the same width. */
    @NonNull
    static View tiles(@NonNull Context c, int perRow, @NonNull List<Tile> tiles) {
        LinearLayout col = column(c);
        LinearLayout row = null;
        int inRow = 0;
        for (Tile t : tiles) {
            if (row == null || inRow == perRow) {
                row = new LinearLayout(c);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = dp(c, 6);
                col.addView(row, rlp);
                inRow = 0;
            }
            LinearLayout cell = column(c);
            GradientDrawable frame = new GradientDrawable();
            frame.setCornerRadius(dp(c, 10));
            frame.setColor(Color.TRANSPARENT);
            frame.setStroke(Math.max(1, dp(c, 1)), ColorUtils.setAlphaComponent(t.color, 150));
            cell.setBackground(frame);
            cell.setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 7));
            TextView label = text(c, t.label, 11.5f, dim(c), false);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            cell.addView(label);
            TextView value = text(c, t.value, 17, t.color, true);
            value.setSingleLine(true);
            value.setEllipsize(android.text.TextUtils.TruncateAt.END);
            cell.addView(value);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (inRow > 0) lp.setMarginStart(dp(c, 6));
            row.addView(cell, lp);
            inRow++;
        }
        // Keep a short last row's tiles the width of the others.
        while (row != null && inRow > 0 && inRow < perRow) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
            lp.setMarginStart(dp(c, 6));
            row.addView(new View(c), lp);
            inRow++;
        }
        return col;
    }

    /** A section heading: bold, underlined as wide as its text. */
    @NonNull
    static TextView heading(@NonNull Context c, @NonNull CharSequence t) {
        SpannableStringBuilder sb = new SpannableStringBuilder(t);
        sb.setSpan(new UnderlineSpan(), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView v = text(c, sb, 15, ink(), true);
        v.setPadding(0, dp(c, 14), 0, dp(c, 4));
        return v;
    }

    /** Explanatory text: dim gold, with the thin left rule. */
    @NonNull
    static View note(@NonNull Context c, @NonNull CharSequence t) {
        LinearLayout row = new LinearLayout(c);
        View rule = new View(c);
        rule.setBackgroundColor(dim(c));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(c, 2), ViewGroup.LayoutParams.MATCH_PARENT);
        rlp.setMarginEnd(dp(c, 10));
        row.addView(rule, rlp);
        TextView v = text(c, t, 13, dim(c), false);
        v.setLineSpacing(dp(c, 2), 1f);
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 12);
        row.setLayoutParams(lp);
        return row;
    }

    /** A plain value line in the theme's text colour. */
    @NonNull
    static TextView line(@NonNull Context c, @NonNull CharSequence t, @ColorInt int color) {
        return text(c, t, 14, color, false);
    }

    /** A table or a list: monospace, a little smaller, scrolls sideways with its parent if it must. */
    @NonNull
    static TextView mono(@NonNull Context c, @NonNull CharSequence t) {
        TextView v = text(c, t, 12, ink(), false);
        v.setTypeface(Typeface.MONOSPACE);
        v.setLineSpacing(dp(c, 2), 1f);
        v.setHorizontallyScrolling(true);
        return v;
    }

    /** An outlined pill for a page (not a dialog button): {@code main} = 3 dp frame and bold. */
    @NonNull
    static TextView pill(@NonNull Context c, @NonNull CharSequence t, @ColorInt int color, boolean main,
                         @NonNull View.OnClickListener click) {
        // Fork (+124): the plain accent means "a button" — the Buttons roles (Pills.frameFor).
        TextView v = text(c, t, 14, io.github.muntashirakon.AppManager.utils.Pills.textFor(c, color), main);
        color = io.github.muntashirakon.AppManager.utils.Pills.frameFor(c, color);
        GradientDrawable frame = new GradientDrawable();
        frame.setCornerRadius(dp(c, 100));
        frame.setColor(Color.TRANSPARENT);
        frame.setStroke(Math.max(1, dp(c, main ? 3f : 1.25f)), color);
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp(c, 100));
        mask.setColor(Color.WHITE);
        v.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(color, 77)), frame, mask));
        v.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        v.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(c, 8));
        lp.topMargin = dp(c, 3);
        lp.bottomMargin = dp(c, 3);
        v.setLayoutParams(lp);
        return v;
    }

    private static final Pattern LABELLED = Pattern.compile("^([^:：.!?。]{2,48}[:：])(\\s+\\S.*)$");
    private static final Pattern SIGNED = Pattern.compile("(?<![\\w.])[+\\u2212-]\\d[\\d,.]*(?:\\s?(?:KB|MB|GB|%))?");

    /**
     * A generated text made readable: a line that ends in a colon becomes a heading, a
     * "label: value" line gets a dim-gold label and a bold value, lines that begin with digits or
     * indentation are set in monospace, and signed figures take the growth colour.
     */
    @NonNull
    static CharSequence pretty(@NonNull Context c, @Nullable CharSequence text) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (text == null) return out;
        int dim = dim(c), warn = warn(c);
        for (String line : text.toString().split("\n", -1)) {
            int start = out.length();
            out.append(line).append('\n');
            int end = out.length() - 1;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            Matcher m = LABELLED.matcher(line);
            if ((trimmed.endsWith(":") || trimmed.endsWith("：")) && trimmed.length() <= 90) {
                out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (line.startsWith("  ") || Character.isDigit(trimmed.charAt(0))) {
                out.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(0.88f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.matches()) {
                int cut = start + m.group(1).length();
                out.setSpan(new ForegroundColorSpan(dim), start, cut, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new StyleSpan(Typeface.BOLD), cut, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            Matcher s = SIGNED.matcher(line);
            while (s.find()) {
                if (line.charAt(s.start()) == '+') {
                    out.setSpan(new ForegroundColorSpan(warn), start + s.start(), start + s.end(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                out.setSpan(new StyleSpan(Typeface.BOLD), start + s.start(), start + s.end(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        if (out.length() > 0) out.delete(out.length() - 1, out.length());
        return out;
    }

    // ---- The chart ---------------------------------------------------------------

    /** One point of the main curve. */
    static final class Point {
        long timeMs;
        double value;
        /** False: "at least this much" — a hollow mark on the top edge, never part of the curve. */
        boolean exact = true;
        /** Taken while starts were failing — drawn in the warning colour. */
        boolean flagged;
        /** The second curve's value at this point, or NaN. */
        double alt = Double.NaN;
        Object tag;
    }

    interface OnPick {
        void onPick(@Nullable Point p);
    }

    /**
     * A time chart: the main curve in the theme's colour through the exact points only, on a
     * scale fitted to them (an inexact point would be a hollow mark on the top edge, but the pool
     * chart no longer feeds any — Fork +108: plotted at their value, a row of "at least 6" read as
     * a night at zero),
     * an optional second curve on a scale of its own, and shaded bands for time spans (bursts).
     * A touch picks the nearest point.
     */
    static final class Chart extends View {
        private final List<Point> mPoints = new ArrayList<>();
        private final List<long[]> mBands = new ArrayList<>();
        private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG), mAlt = new Paint(Paint.ANTI_ALIAS_FLAG),
                mGrid = new Paint(Paint.ANTI_ALIAS_FLAG), mText = new Paint(Paint.ANTI_ALIAS_FLAG),
                mDot = new Paint(Paint.ANTI_ALIAS_FLAG), mBand = new Paint(), mPick = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();
        private final int mInk, mWarn, mAltColor, mDim;
        private boolean mShowAlt, mCompact;
        private String mEmpty = "";
        @Nullable
        private Point mPicked;
        @Nullable
        private OnPick mOnPick;
        private long mT0, mT1;
        private double mMin, mMax, mAltMin, mAltMax;

        Chart(@NonNull Context c) {
            super(c);
            mInk = ink();
            mWarn = warn(c);
            mAltColor = alt(c);
            mDim = dim(c);
            mLine.setStyle(Paint.Style.STROKE);
            mLine.setStrokeWidth(dp(c, 2));
            mLine.setStrokeJoin(Paint.Join.ROUND);
            mLine.setColor(mInk);
            mAlt.setStyle(Paint.Style.STROKE);
            mAlt.setStrokeWidth(dp(c, 1.5f));
            mAlt.setColor(mAltColor);
            mAlt.setPathEffect(new DashPathEffect(new float[]{dp(c, 5), dp(c, 3)}, 0));
            mGrid.setColor(ColorUtils.setAlphaComponent(mDim, 70));
            mGrid.setStrokeWidth(Math.max(1, dp(c, 0.75f)));
            mText.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10.5f,
                    c.getResources().getDisplayMetrics()));
            mBand.setColor(ColorUtils.setAlphaComponent(mWarn, 60));
            mPick.setColor(ColorUtils.setAlphaComponent(mInk, 150));
            mPick.setStrokeWidth(Math.max(1, dp(c, 1)));
        }

        void setData(@NonNull List<Point> points, @NonNull List<long[]> bands, boolean showAlt, @NonNull String empty) {
            mPoints.clear();
            mPoints.addAll(points);
            mBands.clear();
            mBands.addAll(bands);
            mShowAlt = showAlt;
            mEmpty = empty;
            mPicked = null;
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            mAltMin = Double.MAX_VALUE;
            mAltMax = -Double.MAX_VALUE;
            mT0 = Long.MAX_VALUE;
            mT1 = Long.MIN_VALUE;
            for (Point p : mPoints) {
                if (p.exact) {
                    lo = Math.min(lo, p.value);
                    hi = Math.max(hi, p.value);
                }
                mT0 = Math.min(mT0, p.timeMs);
                mT1 = Math.max(mT1, p.timeMs);
                if (!Double.isNaN(p.alt)) {
                    mAltMin = Math.min(mAltMin, p.alt);
                    mAltMax = Math.max(mAltMax, p.alt);
                }
            }
            if (mT1 <= mT0) mT1 = mT0 + 60_000L;
            if (hi < lo) {
                // No exact point at all: an empty scale, the marks say the rest.
                mMin = 0;
                mMax = 10;
            } else {
                // Fitted to the exact readings, so a loss of fifty in fifteen hundred can be seen.
                double pad = Math.max((hi - lo) * 0.15, 4);
                double step = niceStep((hi - lo + 2 * pad) / 4);
                mMin = Math.max(0, Math.floor((lo - pad) / step) * step);
                mMax = Math.ceil((hi + pad) / step) * step;
                if (mMax <= mMin) mMax = mMin + step;
            }
            if (mAltMax <= mAltMin) mAltMax = mAltMin + 1;
            invalidate();
        }

        /** A small, untouchable version for a dialog: no axis captions on the right, fewer labels. */
        void setCompact(boolean compact) {
            mCompact = compact;
        }

        void setOnPick(@Nullable OnPick l) {
            mOnPick = l;
        }

        private static double niceStep(double v) {
            if (v <= 1) return 1;
            double mag = Math.pow(10, Math.floor(Math.log10(v)));
            for (double step : new double[]{1, 2, 2.5, 5, 10}) {
                if (step * mag >= v) return step * mag;
            }
            return 10 * mag;
        }

        private float left() {
            return dp(getContext(), 44);
        }

        private float right() {
            return getWidth() - dp(getContext(), mShowAlt && !mCompact ? 46 : 8);
        }

        private float top() {
            return dp(getContext(), 8);
        }

        private float bottom() {
            return getHeight() - dp(getContext(), 18);
        }

        private float x(long t) {
            return left() + (right() - left()) * (float) ((t - mT0) / (double) (mT1 - mT0));
        }

        private float y(double v) {
            return bottom() - (bottom() - top()) * (float) ((v - mMin) / (mMax - mMin));
        }

        /** Where a point is drawn: on the curve when exact, on the top edge when "at least". */
        private float y(@NonNull Point p) {
            return p.exact ? y(p.value) : top();
        }

        private float yAlt(double v) {
            return bottom() - (bottom() - top()) * (float) ((v - mAltMin) / (mAltMax - mAltMin));
        }

        @NonNull
        private static String compact(double v) {
            if (v >= 1_000_000) return String.format(Locale.US, "%.1fM", v / 1_000_000);
            if (v >= 10_000) return String.format(Locale.US, "%.0fk", v / 1000);
            return String.format(Locale.US, "%,.0f", v);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            float l = left(), r = right(), t = top(), b = bottom();
            mText.setColor(mDim);
            if (mPoints.isEmpty()) {
                mText.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(mEmpty, getWidth() / 2f, getHeight() / 2f, mText);
                return;
            }
            // Bands first, so the curves run over them.
            for (long[] band : mBands) {
                long from = Math.max(band[0], mT0), to = Math.min(band[1] <= 0 ? mT1 : band[1], mT1);
                if (to < mT0 || from > mT1) continue;
                float x0 = x(from), x1 = Math.max(x(to), x0 + dp(getContext(), 2));
                canvas.drawRect(x0, t, x1, b, mBand);
            }
            mText.setTextAlign(Paint.Align.RIGHT);
            for (int i = 0; i <= 2; i++) {
                double v = mMin + (mMax - mMin) * i / 2.0;
                float yy = y(v);
                canvas.drawLine(l, yy, r, yy, mGrid);
                canvas.drawText(compact(v), l - dp(getContext(), 6), yy + mText.getTextSize() * 0.35f, mText);
            }
            SimpleDateFormat f = new SimpleDateFormat(mT1 - mT0 > 36 * 3_600_000L ? "MM-dd" : "HH:mm", Locale.US);
            mText.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(f.format(new Date(mT0)), l, getHeight() - dp(getContext(), 4), mText);
            mText.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(f.format(new Date(mT1)), r, getHeight() - dp(getContext(), 4), mText);

            if (mShowAlt) {
                mPath.reset();
                boolean first = true;
                for (Point p : mPoints) {
                    if (Double.isNaN(p.alt)) continue;
                    if (first) mPath.moveTo(x(p.timeMs), yAlt(p.alt));
                    else mPath.lineTo(x(p.timeMs), yAlt(p.alt));
                    first = false;
                }
                canvas.drawPath(mPath, mAlt);
                if (!mCompact && mAltMin != Double.MAX_VALUE) {
                    mText.setColor(mAltColor);
                    mText.setTextAlign(Paint.Align.LEFT);
                    canvas.drawText(compact(mAltMax), r + dp(getContext(), 5), t + mText.getTextSize(), mText);
                    canvas.drawText(compact(mAltMin), r + dp(getContext(), 5), b, mText);
                }
            }

            mPath.reset();
            boolean first = true;
            for (Point p : mPoints) {
                if (!p.exact) continue;
                if (first) mPath.moveTo(x(p.timeMs), y(p.value));
                else mPath.lineTo(x(p.timeMs), y(p.value));
                first = false;
            }
            canvas.drawPath(mPath, mLine);
            float rad = dp(getContext(), mPoints.size() > 120 ? 1.6f : 3f);
            for (Point p : mPoints) {
                int color = p.flagged ? mWarn : mInk;
                mDot.setColor(color);
                if (p.exact) {
                    mDot.setStyle(Paint.Style.FILL);
                } else {
                    mDot.setStyle(Paint.Style.STROKE);
                    mDot.setStrokeWidth(dp(getContext(), 1.25f));
                }
                canvas.drawCircle(x(p.timeMs), y(p), p.exact ? (p.flagged ? rad * 1.3f : rad) : rad * 0.7f, mDot);
            }
            if (mPicked != null) {
                float px = x(mPicked.timeMs);
                canvas.drawLine(px, t, px, b, mPick);
                mDot.setStyle(Paint.Style.STROKE);
                mDot.setStrokeWidth(dp(getContext(), 2));
                mDot.setColor(mInk);
                canvas.drawCircle(px, y(mPicked), dp(getContext(), 6), mDot);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (mOnPick == null || mPoints.isEmpty()) return false;
            if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE) {
                getParent().requestDisallowInterceptTouchEvent(true);
                Point best = null;
                float bestD = Float.MAX_VALUE;
                for (Point p : mPoints) {
                    float d = Math.abs(x(p.timeMs) - e.getX());
                    if (d < bestD) {
                        bestD = d;
                        best = p;
                    }
                }
                if (best != mPicked) {
                    mPicked = best;
                    invalidate();
                    mOnPick.onPick(best);
                }
                return true;
            }
            return e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL;
        }
    }
}
