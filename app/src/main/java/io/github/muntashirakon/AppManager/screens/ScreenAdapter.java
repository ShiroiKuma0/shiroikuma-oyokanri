// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.screens;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import java.util.LinkedHashSet;
import java.util.Set;
import com.google.android.material.card.MaterialCardView;
import io.github.muntashirakon.AppManager.fonts.ColorPrefs;
import io.github.muntashirakon.AppManager.fonts.SelectionFramePrefs;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.battery.MainCardBinder;

/**
 * Fork (白い熊, +118/+124): draws {@link ScreenRow}s for every sibling screen.
 *
 * <p><b>It inflates the main list's own row</b> — {@code item_main.xml} — and paints it through
 * {@link MainCardBinder}, the binder the battery screen already uses. 保存一覧, 盗み見一覧 and
 * 仲間 are lists of apps, so they have to BE the list of apps: the icon at the size you set, the
 * film behind a frozen row, the running box, the italic label, the snowflake. Only the right-hand
 * column says something different.
 *
 * <p>The first version of this drew a lookalike row of its own, and a lookalike is a thing that
 * agrees today and drifts on the first edit to either side. Going through the one binder makes
 * them identical by construction rather than by inspection.
 */
public class ScreenAdapter extends RecyclerView.Adapter<ScreenAdapter.ViewHolder> {
    public interface OnRowClickListener {
        void onRowClicked(@NonNull ScreenRow row);

        /** The selection changed — count included, so the host need not ask. */
        default void onSelectionChanged(int count) {
        }
    }

    private final Context mContext;
    private final List<ScreenRow> mRows = new ArrayList<>();
    private final OnRowClickListener mListener;
    /**
     * Fork (白い熊, +131): selected rows, keyed by package and user.
     *
     * <p>By key rather than by position, because the list re-sorts under the selection — pick
     * three stale backups, sort by size, and a position-keyed selection would be pointing at
     * three different apps.
     */
    private final Set<String> mSelected = new LinkedHashSet<>();
    private boolean mSelectionMode;

    public ScreenAdapter(@NonNull Context context, @NonNull OnRowClickListener listener) {
        mContext = context;
        mListener = listener;
    }

    @NonNull
    private static String keyOf(@NonNull ScreenRow row) {
        return row.packageName + ':' + row.userId;
    }

    public boolean isInSelectionMode() {
        return mSelectionMode;
    }

    public int getSelectedCount() {
        return mSelected.size();
    }

    @NonNull
    public List<ScreenRow> getSelectedRows() {
        List<ScreenRow> selected = new ArrayList<>();
        for (ScreenRow row : mRows) {
            if (mSelected.contains(keyOf(row))) {
                selected.add(row);
            }
        }
        return selected;
    }

    public void selectAll() {
        for (ScreenRow row : mRows) {
            mSelected.add(keyOf(row));
        }
        mSelectionMode = !mSelected.isEmpty();
        notifyDataSetChanged();
        mListener.onSelectionChanged(mSelected.size());
    }

    /** Leave selection mode. Returns whether anything was selected. */
    public boolean clearSelection() {
        boolean had = !mSelected.isEmpty() || mSelectionMode;
        mSelected.clear();
        mSelectionMode = false;
        notifyDataSetChanged();
        mListener.onSelectionChanged(0);
        return had;
    }

    private void toggle(@NonNull ScreenRow row) {
        String key = keyOf(row);
        if (!mSelected.remove(key)) {
            mSelected.add(key);
        }
        // Emptying the selection leaves selection mode: a mode with nothing in it is a mode you
        // cannot see and cannot leave.
        mSelectionMode = !mSelected.isEmpty();
        notifyDataSetChanged();
        mListener.onSelectionChanged(mSelected.size());
    }

    /** Kept for the screen's onResume: the card reads every colour itself on each bind. */
    public void reloadColors(@NonNull Context context) {
    }

    public void submit(@NonNull List<ScreenRow> rows) {
        mRows.clear();
        mRows.addAll(rows);
        // A selected row that is no longer in the list — deleted, or filtered away — is dropped
        // from the selection rather than silently acted on later.
        Set<String> present = new LinkedHashSet<>();
        for (ScreenRow row : rows) {
            present.add(keyOf(row));
        }
        mSelected.retainAll(present);
        mSelectionMode = !mSelected.isEmpty();
        notifyDataSetChanged();
        mListener.onSelectionChanged(mSelected.size());
    }

    @NonNull
    public List<ScreenRow> getRows() {
        return mRows;
    }

    @Override
    public int getItemCount() {
        return mRows.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_main, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ScreenRow row = mRows.get(position);
        MainCardBinder.bindLines(mContext, holder.itemView, row.info, row.packageName, row.uid,
                row.frozen, row.right, row.accent, v -> {
                    if (mSelectionMode) {
                        toggle(row);
                    } else {
                        mListener.onRowClicked(row);
                    }
                });
        // A row for an app that is gone is dimmed the way the main list dims one: the card is
        // still the card, it just has no app behind it any more.
        holder.itemView.setAlpha(row.installed ? 1f : 0.6f);
        holder.itemView.setOnLongClickListener(v -> {
            toggle(row);
            return true;
        });
        // The selected frame is the main list's own — same colour, width and roundness from
        // SelectionFramePrefs — so a selection looks the same wherever you make one.
        if (holder.itemView instanceof MaterialCardView) {
            MaterialCardView card = (MaterialCardView) holder.itemView;
            if (mSelected.contains(keyOf(row))) {
                float density = mContext.getResources().getDisplayMetrics().density;
                card.setRadius(SelectionFramePrefs.getRadiusDp(mContext) * density);
                card.setStrokeWidth(Math.max(1,
                        Math.round(SelectionFramePrefs.getWidthDp(mContext) * density)));
                card.setStrokeColor(ColorPrefs.getColor(mContext, ColorPrefs.SELECTED_FRAME));
            }
        }
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ViewHolder(@NonNull View itemView) {
            super(itemView);
        }
    }
}
