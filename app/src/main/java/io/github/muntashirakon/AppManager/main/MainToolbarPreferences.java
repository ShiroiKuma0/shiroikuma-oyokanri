// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.muntashirakon.AppManager.R;

/**
 * Settings screen for the customisable bottom selection toolbar.
 *
 * Shows one row per action (all 13 keys defined in
 * {@link MainToolbarPrefs#ALL_KEYS}). Each row has a checkbox marking
 * visibility plus a drag handle for ordering. Changes commit
 * immediately to {@link MainToolbarPrefs}; {@link MainActivity}
 * re-reads on resume and rebuilds its menu accordingly.
 *
 * Visibility semantics on save: "visible" = rows whose checkbox is
 * ticked, in the order they appear in the list; "hidden" = the rest,
 * in their list order. The toolbar concatenates visible + hidden, the
 * widget shows what fits in the bar, and the auto-overflow ("More…")
 * picks up the rest - so hidden items still have a home.
 */
public class MainToolbarPreferences extends Fragment {

    public static final String TAG = MainToolbarPreferences.class.getSimpleName();

    /**
     * Fork (白い熊, +118): which registry this editor is editing.
     *
     * <p>The unrolled row's pills are the same kind of setting as the selection toolbar's — a
     * visible/hidden split with an order — so they get the same editor rather than a second one
     * for a person to learn. Passed as a preference {@code <extra>}; absent means the toolbar,
     * which is what every existing entry point wants.
     */
    public static final String ARG_REGISTRY = "registry";
    public static final String REGISTRY_APP_PANE = "app_pane";

    private boolean mIsAppPane;

    /** Ordered working copy of all 13 keys. Visible rows must come
     *  first in this list; the {@link #onRowMoved} callback enforces
     *  the order, and the checkbox callback re-classifies a row by
     *  moving it to the boundary between visible and hidden. */
    private final List<String> mOrder = new ArrayList<>();

    /** Per-key visibility flag, parallel to {@link #mOrder} membership
     *  state (true = visible, false = hidden). Stored as a separate map
     *  rather than implied by position so we don't have to chase the
     *  boundary index on every interaction. */
    private final java.util.Map<String, Boolean> mVisible = new java.util.HashMap<>();

    @Nullable
    private Adapter mAdapter;
    @Nullable
    private ItemTouchHelper mItemTouchHelper;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_main_toolbar_prefs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mIsAppPane = getArguments() != null
                && REGISTRY_APP_PANE.equals(getArguments().getString(ARG_REGISTRY));
        AppCompatTextView header = view.findViewById(R.id.header);
        if (header != null) {
            header.setText(mIsAppPane ? R.string.pref_app_pane_summary : R.string.pref_main_toolbar_summary);
        }
        // Load current state into the working lists.
        List<String> visible = mIsAppPane
                ? AppPanePrefs.loadVisibleOrder(requireContext())
                : MainToolbarPrefs.loadVisibleOrder(requireContext());
        List<String> hidden = mIsAppPane
                ? AppPanePrefs.loadHiddenOrder(requireContext())
                : MainToolbarPrefs.loadHiddenOrder(requireContext());
        mOrder.clear();
        mVisible.clear();
        for (String k : visible) {
            mOrder.add(k);
            mVisible.put(k, true);
        }
        for (String k : hidden) {
            mOrder.add(k);
            mVisible.put(k, false);
        }
        RecyclerView rv = view.findViewById(R.id.main_toolbar_list);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        mAdapter = new Adapter();
        rv.setAdapter(mAdapter);
        // Drag-to-reorder via ItemTouchHelper. The drag handle's
        // OnTouchListener triggers startDrag(viewHolder); we leave swipe
        // disabled. onMoved persists immediately so the result is
        // applied even if the user navigates away without an explicit
        // save action.
        mItemTouchHelper = new ItemTouchHelper(new DragCallback());
        mItemTouchHelper.attachToRecyclerView(rv);
    }

    /** Persist the current working state. Called after every mutation
     *  so there's no save button to forget. */
    private void persist() {
        List<String> visibleNow = new ArrayList<>();
        List<String> hiddenNow = new ArrayList<>();
        for (String k : mOrder) {
            Boolean v = mVisible.get(k);
            if (v != null && v) visibleNow.add(k);
            else hiddenNow.add(k);
        }
        if (mIsAppPane) {
            AppPanePrefs.save(requireContext(), visibleNow, hiddenNow);
        } else {
            MainToolbarPrefs.save(requireContext(), visibleNow, hiddenNow);
        }
    }

    /** Called by the adapter when a row's checkbox is toggled. */
    private void onVisibilityToggled(int position, boolean nowVisible) {
        if (position < 0 || position >= mOrder.size()) return;
        String key = mOrder.get(position);
        mVisible.put(key, nowVisible);
        persist();
    }

    /** Called by {@link DragCallback#onMove} for each move event. */
    private void onRowMoved(int from, int to) {
        if (from < 0 || to < 0 || from >= mOrder.size() || to >= mOrder.size()) return;
        Collections.swap(mOrder, from, to);
        if (mAdapter != null) mAdapter.notifyItemMoved(from, to);
        persist();
    }

    /** RecyclerView adapter binding {@link #mOrder} to the row layout. */
    private final class Adapter extends RecyclerView.Adapter<RowHolder> {
        @NonNull
        @Override
        public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_main_toolbar_pref_row, parent, false);
            return new RowHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RowHolder h, int position) {
            String key = mOrder.get(position);
            Boolean visible = mVisible.get(key);
            // Suspend checkbox listener so programmatic setChecked doesn't
            // feed back into onVisibilityToggled.
            h.checkbox.setOnCheckedChangeListener(null);
            h.checkbox.setChecked(visible != null && visible);
            int titleRes = mIsAppPane ? AppPanePrefs.titleForKey(key) : MainToolbarPrefs.titleForKey(key);
            int iconRes = mIsAppPane ? AppPanePrefs.iconForKey(key) : MainToolbarPrefs.iconForKey(key);
            if (titleRes != 0) h.label.setText(titleRes);
            else h.label.setText(key);
            if (iconRes != 0) h.icon.setImageResource(iconRes);
            // Row click toggles the checkbox (alongside direct checkbox tap).
            h.itemView.setOnClickListener(v -> {
                boolean next = !h.checkbox.isChecked();
                h.checkbox.setChecked(next);
                onVisibilityToggled(h.getBindingAdapterPosition(), next);
            });
            h.checkbox.setOnCheckedChangeListener((btn, isChecked) ->
                    onVisibilityToggled(h.getBindingAdapterPosition(), isChecked));
            // Touching the drag handle starts a drag.
            h.dragHandle.setOnTouchListener((v, ev) -> {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN
                        && mItemTouchHelper != null) {
                    mItemTouchHelper.startDrag(h);
                    return true;
                }
                return false;
            });
        }

        @Override
        public int getItemCount() {
            return mOrder.size();
        }
    }

    static final class RowHolder extends RecyclerView.ViewHolder {
        final MaterialCheckBox checkbox;
        final AppCompatImageView icon;
        final AppCompatTextView label;
        final AppCompatImageView dragHandle;

        RowHolder(@NonNull View itemView) {
            super(itemView);
            checkbox = itemView.findViewById(R.id.visible_checkbox);
            icon = itemView.findViewById(R.id.action_icon);
            label = itemView.findViewById(R.id.action_label);
            dragHandle = itemView.findViewById(R.id.drag_handle);
        }
    }

    /** ItemTouchHelper.Callback that supports vertical drag, no swipe. */
    private final class DragCallback extends ItemTouchHelper.Callback {
        @Override
        public int getMovementFlags(@NonNull RecyclerView rv,
                                    @NonNull RecyclerView.ViewHolder vh) {
            int dragFlags = ItemTouchHelper.UP | ItemTouchHelper.DOWN;
            return makeMovementFlags(dragFlags, 0);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView rv,
                              @NonNull RecyclerView.ViewHolder src,
                              @NonNull RecyclerView.ViewHolder dst) {
            onRowMoved(src.getBindingAdapterPosition(),
                    dst.getBindingAdapterPosition());
            return true;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {
            // No swipe behaviour.
        }

        @Override
        public boolean isLongPressDragEnabled() {
            // We trigger drag from the dedicated handle, not from a
            // long-press anywhere on the row, so that the row click
            // remains responsive for the checkbox toggle.
            return false;
        }
    }
}
