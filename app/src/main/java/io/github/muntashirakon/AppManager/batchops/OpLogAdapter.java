// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.batchops;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.fonts.ColorPrefs;
import io.github.muntashirakon.AppManager.fonts.OpLogPrefs;

/**
 * Fork (白い熊, +116): draws {@link OpLog} lines.
 *
 * <p>One {@code TextView} per row, built as a {@link SpannableStringBuilder}: timestamp, indent
 * ladder, marker, text, detail — four colours in one view rather than four views to lay out. A
 * log scrolls fast and is mostly off-screen; the cheapest row that can carry the colours is the
 * right row.
 *
 * <p>The whole line is monospaced. Not a stylistic choice: the ladder is drawn with box-drawing
 * characters, and in a proportional face the rungs of successive lines do not sit above one
 * another, which is the one thing the indentation exists to do.
 */
public class OpLogAdapter extends RecyclerView.Adapter<OpLogAdapter.ViewHolder> {
    /** A line was tapped. Lines are ellipsized in the middle, so this is how the rest is read. */
    public interface OnLineClickListener {
        void onLineClick(@NonNull OpLog.Entry entry);
    }

    private final Context mContext;
    @Nullable
    private OnLineClickListener mListener;
    private List<OpLog.Entry> mEntries = Collections.emptyList();
    private float mTextSizeSp;
    private boolean mTimestamps;
    private int mTimeColor;
    private int mGuideColor;
    private int mDetailColor;

    public OpLogAdapter(@NonNull Context context) {
        mContext = context;
        reloadAppearance();
    }

    public void setOnLineClickListener(@Nullable OnLineClickListener listener) {
        mListener = listener;
    }

    /** Re-read the settable colours and size. Called from the page's onResume. */
    public final void reloadAppearance() {
        mTextSizeSp = OpLogPrefs.getTextSizeSp(mContext);
        mTimestamps = OpLogPrefs.showTimestamps(mContext);
        mTimeColor = ColorPrefs.getColor(mContext, ColorPrefs.OPLOG_TIME);
        mGuideColor = ColorPrefs.getColor(mContext, ColorPrefs.OPLOG_GUIDE);
        mDetailColor = ColorPrefs.getColor(mContext, ColorPrefs.OPLOG_DETAIL);
    }

    /**
     * Swap in a new snapshot.
     *
     * @return the number of rows appended at the end, or {@code -1} when the list changed in a
     * way that is not a simple append (a trim of the oldest lines, or a new batch) — the caller
     * uses that to decide whether it may keep its scroll position.
     */
    public int submit(@NonNull List<OpLog.Entry> entries) {
        int previous = mEntries.size();
        boolean appended = entries.size() >= previous
                && (previous == 0 || entries.get(previous - 1) == mEntries.get(previous - 1));
        mEntries = new ArrayList<>(entries);
        if (appended) {
            int added = mEntries.size() - previous;
            if (added > 0) {
                notifyItemRangeInserted(previous, added);
            }
            return added;
        }
        notifyDataSetChanged();
        return -1;
    }

    @Override
    public int getItemCount() {
        return mEntries.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_op_log, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        OpLog.Entry e = mEntries.get(position);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (mTimestamps) {
            append(sb, OpLogFormat.timestamp(e.atMillis) + "  ", mTimeColor, false);
        }
        String guides = OpLogFormat.guides(e.depth);
        if (!guides.isEmpty()) {
            append(sb, guides, mGuideColor, false);
        }
        int color = OpLogFormat.color(mContext, e.kind, e.continued);
        String marker = OpLogFormat.marker(e.kind, e.continued);
        if (!marker.isEmpty()) {
            append(sb, marker, color, false);
        }
        // An app header is the one line the eye uses to find its place, so it alone is bold —
        // and a continuation is not, or a busy eight-thread stretch would be bold throughout.
        append(sb, e.text, color, e.kind == OpLog.KIND_APP && !e.continued);
        if (!TextUtils.isEmpty(e.detail)) {
            append(sb, "  ", mDetailColor, false);
            append(sb, e.detail, mDetailColor, false);
        }
        holder.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, mTextSizeSp);
        holder.text.setText(sb);
        holder.itemView.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onLineClick(e);
            }
        });
    }

    /**
     * Append {@code text} in the row's colour — <b>without painting over a colour the text
     * brought with it</b>.
     *
     * <p>Fork (白い熊, +143): LANDMINE. A blanket {@code setSpan} over the whole appended range
     * wins against any {@link ForegroundColorSpan} already inside it, because for one attribute
     * the span added last is the one that draws. So the stage number carried by a progress line
     * — the whole point of which is that it is a different colour from the line — was applied at
     * the source, copied faithfully into this builder, and then silently repainted here. The
     * base colour is laid into the gaps between existing colour spans instead; bold is blanketed
     * as before, since it settles a different attribute and cannot collide.
     */
    private static void append(@NonNull SpannableStringBuilder sb, @NonNull CharSequence text,
                               int color, boolean bold) {
        int start = sb.length();
        sb.append(text);
        int end = sb.length();
        ForegroundColorSpan[] existing = sb.getSpans(start, end, ForegroundColorSpan.class);
        if (existing.length == 0) {
            sb.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            int[][] taken = new int[existing.length][2];
            for (int i = 0; i < existing.length; ++i) {
                taken[i][0] = Math.max(start, sb.getSpanStart(existing[i]));
                taken[i][1] = Math.min(end, sb.getSpanEnd(existing[i]));
            }
            java.util.Arrays.sort(taken, (a, b) -> Integer.compare(a[0], b[0]));
            int cursor = start;
            for (int[] range : taken) {
                if (range[0] > cursor) {
                    sb.setSpan(new ForegroundColorSpan(color), cursor, range[0],
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                cursor = Math.max(cursor, range[1]);
            }
            if (cursor < end) {
                sb.setSpan(new ForegroundColorSpan(color), cursor, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        if (bold) {
            sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final AppCompatTextView text;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.op_log_line);
        }
    }
}
