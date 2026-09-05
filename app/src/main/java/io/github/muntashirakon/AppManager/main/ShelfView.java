// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;

/**
 * Fork (白い熊, +118): the shelf of pills under the main toolbar.
 *
 * <p><b>The {@code +} lives outside the scroller.</b> This is the same trick the row pills use for
 * the tag {@code +} (+94), and for the same reason: once there are more pills than fit, a {@code +}
 * inside the scrolling strip is the one control you can no longer reach without scrolling to find
 * it. Outside, it is always at the end of the bar.
 *
 * <p><b>It is a RecyclerView and not a LinearLayout</b> because reordering has to be a drag, and
 * {@link ItemTouchHelper} does drags properly — long-press to lift, live reorder, drop to persist.
 * Hand-rolled drag on a row of views is a great deal of code that ends up worse.
 */
public class ShelfView extends LinearLayoutCompat {
    /** What the host does when a pill is used. */
    public interface Listener {
        void onPillClicked(@NonNull ShelfPrefs.Pill pill);

        void onPillLongClicked(@NonNull ShelfPrefs.Pill pill);

        void onAddPill();
    }

    private final List<ShelfPrefs.Pill> mPills = new ArrayList<>();
    private RecyclerView mRecyclerView;
    private Adapter mAdapter;
    private TextView mAddPill;
    @Nullable
    private Listener mListener;
    /** The pill whose view is on screen right now, drawn filled. */
    @Nullable
    private String mActiveId;

    public ShelfView(@NonNull Context context) {
        this(context, null);
    }

    public ShelfView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(@NonNull Context context) {
        setOrientation(HORIZONTAL);
        // END, not START: with no pills yet the list is GONE and its weight collapses, and a "+"
        // left hanging at the far left of an empty bar is not where anybody looks for it.
        setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        // No top padding: the shelf sits directly under the toolbar, and a gap there reads as
        // a broken bar rather than as breathing room (白い熊, +119).
        int padH = Math.round(ForkThemeUtils.dpToPx(context, 8f));
        setPadding(padH, 0, padH, 0);

        mRecyclerView = new RecyclerView(context);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        mRecyclerView.setHorizontalScrollBarEnabled(false);
        mRecyclerView.setClipToPadding(false);
        mAdapter = new Adapter();
        mRecyclerView.setAdapter(mAdapter);
        LayoutParams listLp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        listLp.weight = 1f;
        mRecyclerView.setLayoutParams(listLp);
        addView(mRecyclerView);

        mAddPill = new AppCompatTextView(context);
        LayoutParams addLp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.setMarginStart(Math.round(ForkThemeUtils.dpToPx(context, 6f)));
        mAddPill.setLayoutParams(addLp);
        mAddPill.setText("+");
        mAddPill.setContentDescription(context.getString(R.string.shelf_add));
        mAddPill.setOnClickListener(v -> {
            if (mListener != null) mListener.onAddPill();
        });
        addView(mAddPill);

        new ItemTouchHelper(new DragCallback()).attachToRecyclerView(mRecyclerView);
        reload();
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    /**
     * Mark one pill as the view currently on screen, or {@code null} for none. Purely how it is
     * drawn — the shelf never decides what the list shows.
     */
    public void setActiveId(@Nullable String id) {
        if (mActiveId == null ? id == null : mActiveId.equals(id)) {
            return;
        }
        mActiveId = id;
        mAdapter.notifyDataSetChanged();
    }

    @Nullable
    public String getActiveId() {
        return mActiveId;
    }

    /** Re-read the shelf from preferences and redraw. Cheap; called on resume. */
    public void reload() {
        mPills.clear();
        mPills.addAll(ShelfPrefs.load(getContext()));
        applyColours();
        mAdapter.notifyDataSetChanged();
        // The "+" is ALWAYS here (白い熊, +123): it is how a pill is made, and hiding it in a
        // menu put it somewhere nobody would look. The strip is one compact pill tall and sits
        // directly under the toolbar's icons — the black band that used to sit between them
        // belonged to the toolbar, not to this.
        //
        // The list itself is still GONE while empty: an empty RecyclerView measures taller than
        // the pill beside it, and the row's gravity would then centre the "+" in that height.
        mRecyclerView.setVisibility(mPills.isEmpty() ? GONE : VISIBLE);
        setVisibility(VISIBLE);
    }

    private void applyColours() {
        int ink = ForkThemeUtils.getTextColor();
        // LANDMINE (白い熊, +119) — do NOT pin this to RowPills.ADD_WIDTH_DP. An action pill
        // carries 16dp of padding on each side, so a 34dp box leaves about two pixels for the
        // glyph and the "+" is clipped away entirely: an empty pill that looks like a bug.
        // The row pills' add affordances are pinned because they must line up in a column;
        // this one has nothing to line up with.
        RowPills.styleActionPill(mAddPill, RowPills.withAlpha(ink, 0.75f), false, true);
        mAddPill.setText("+");
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(new AppCompatTextView(parent.getContext()));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            ShelfPrefs.Pill pill = mPills.get(position);
            TextView view = (TextView) holder.itemView;
            int ink = ForkThemeUtils.getTextColor();
            boolean active = pill.id.equals(mActiveId);
            RowPills.styleActionPill(view, ink, active, true);
            view.setText(pill.name);
            if (pill.isScreen()) {
                int icon = ShelfPrefs.screenIcon(pill.payload);
                if (icon != 0) {
                    RowPills.setActionGlyph(view, icon, active ? android.graphics.Color.BLACK : ink);
                }
            } else {
                view.setCompoundDrawablesRelative(null, null, null, null);
            }
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Math.round(ForkThemeUtils.dpToPx(view.getContext(), 6f)));
            view.setLayoutParams(lp);
            view.setOnClickListener(v -> {
                if (mListener != null) mListener.onPillClicked(pill);
            });
            view.setOnLongClickListener(v -> {
                if (mListener != null) mListener.onPillLongClicked(pill);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return mPills.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            Holder(@NonNull View itemView) {
                super(itemView);
            }
        }
    }

    /**
     * Drag to reorder, horizontally. The order is persisted on <b>drop</b> rather than on every
     * move: a drag across six pills would otherwise write the file six times.
     */
    private class DragCallback extends ItemTouchHelper.SimpleCallback {
        private boolean mMoved;

        DragCallback() {
            super(ItemTouchHelper.START | ItemTouchHelper.END, 0);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView,
                              @NonNull RecyclerView.ViewHolder viewHolder,
                              @NonNull RecyclerView.ViewHolder target) {
            int from = viewHolder.getBindingAdapterPosition();
            int to = target.getBindingAdapterPosition();
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
                return false;
            }
            Collections.swap(mPills, from, to);
            mAdapter.notifyItemMoved(from, to);
            mMoved = true;
            return true;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            if (mMoved) {
                mMoved = false;
                ShelfPrefs.save(getContext(), mPills);
            }
        }
    }
}
