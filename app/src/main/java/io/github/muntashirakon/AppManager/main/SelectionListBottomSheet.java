// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;

/**
 * Fork: the "Selected apps" bottom sheet opened by tapping the selection
 * reminder pill. Lists every currently-selected app — label (bold) over package
 * id (italic), with a faint "· hidden" tag on apps not in the current filtered
 * view — each with a ✕ to deselect just that app, plus a "Clear all" action.
 *
 * <p>It owns no selection state of its own: it snapshots the live selection from
 * the {@link Host} on {@link #show()}, mutates it through the host, and asks the
 * host to refresh the rest of the UI (pill, action toolbar, row highlights) after
 * each change. Styled from the configurable fork theme.
 */
@UiThread
public class SelectionListBottomSheet {
    /** Bridge to the owning activity's selection state. */
    public interface Host {
        @NonNull
        List<ApplicationItem> getSelectedItems();

        @NonNull
        Set<String> getDisplayedPackages();

        void deselect(@NonNull ApplicationItem item);

        void clearAll();

        void onSelectionChanged();
    }

    private final Activity mActivity;
    private final Host mHost;
    private final List<ApplicationItem> mItems = new ArrayList<>();
    private final Set<String> mDisplayed = new HashSet<>();

    private BottomSheetDialog mDialog;
    private TextView mTitleView;
    private Adapter mAdapter;

    public SelectionListBottomSheet(@NonNull Activity activity, @NonNull Host host) {
        mActivity = activity;
        mHost = host;
    }

    public boolean isShowing() {
        return mDialog != null && mDialog.isShowing();
    }

    public void show() {
        if (mDialog == null) {
            build();
        }
        if (!reload()) {
            return; // nothing selected
        }
        mDialog.show();
    }

    public void dismiss() {
        if (mDialog != null && mDialog.isShowing()) {
            mDialog.dismiss();
        }
    }

    private void build() {
        View view = mActivity.getLayoutInflater().inflate(R.layout.bottom_sheet_selected_apps, null);
        View container = view.findViewById(R.id.selected_apps_container);
        mTitleView = view.findViewById(R.id.selected_apps_title);
        MaterialButton clearAll = view.findViewById(R.id.selected_apps_clear_all);
        RecyclerView list = view.findViewById(R.id.selected_apps_list);

        int textColor = ForkThemeUtils.getTextColor();
        // Top-rounded themed panel (uniform corners would round the off-screen
        // bottom too, which is fine, but match the sheet shape explicitly).
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ForkThemeUtils.getBackgroundColor());
        bg.setStroke((int) ForkThemeUtils.dpToPx(mActivity, ForkThemeUtils.getBorderWidthDp()),
                ForkThemeUtils.getBorderColor());
        float r = ForkThemeUtils.dpToPx(mActivity, 16f);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        container.setBackground(bg);
        mTitleView.setTextColor(textColor);
        ColorStateList accent = ColorStateList.valueOf(textColor);
        clearAll.setTextColor(accent);
        clearAll.setRippleColor(accent);
        clearAll.setOnClickListener(v -> {
            mHost.clearAll();
            dismiss();
        });

        mAdapter = new Adapter();
        list.setLayoutManager(new LinearLayoutManager(mActivity));
        list.setAdapter(mAdapter);

        mDialog = new BottomSheetDialog(mActivity);
        mDialog.setContentView(view);
        // Let the themed panel's own background show instead of the sheet's
        // default light surface.
        View sheet = mDialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) {
            sheet.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    /** Re-snapshot the selection. Returns false (and doesn't show) when empty. */
    private boolean reload() {
        mItems.clear();
        mItems.addAll(mHost.getSelectedItems());
        mDisplayed.clear();
        mDisplayed.addAll(mHost.getDisplayedPackages());
        if (mItems.isEmpty()) {
            dismiss();
            return false;
        }
        updateTitle();
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
        return true;
    }

    private void updateTitle() {
        mTitleView.setText(mActivity.getString(R.string.selected_apps_title, mItems.size()));
    }

    private void removeAt(int position) {
        if (position < 0 || position >= mItems.size()) {
            return;
        }
        ApplicationItem item = mItems.remove(position);
        mHost.deselect(item);
        mAdapter.notifyItemRemoved(position);
        mAdapter.notifyItemRangeChanged(position, mItems.size() - position);
        updateTitle();
        mHost.onSelectionChanged();
        if (mItems.isEmpty()) {
            dismiss();
        }
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_selected_app, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            ApplicationItem item = mItems.get(position);
            int textColor = ForkThemeUtils.getTextColor();
            CharSequence label = item.label != null ? item.label : item.packageName;
            holder.label.setText(label);
            holder.label.setTextColor(textColor);
            holder.pkg.setText(item.packageName);
            holder.pkg.setTextColor(textColor);
            boolean hidden = !mDisplayed.contains(item.packageName);
            if (hidden) {
                holder.hidden.setText(R.string.selection_hidden_tag);
                holder.hidden.setTextColor(textColor);
                holder.hidden.setVisibility(View.VISIBLE);
            } else {
                holder.hidden.setVisibility(View.GONE);
            }
            holder.remove.setImageTintList(ColorStateList.valueOf(textColor));
            holder.remove.setOnClickListener(v -> removeAt(holder.getBindingAdapterPosition()));
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView label;
            final TextView pkg;
            final TextView hidden;
            final ImageView remove;

            VH(@NonNull View itemView) {
                super(itemView);
                label = itemView.findViewById(R.id.selected_app_label);
                pkg = itemView.findViewById(R.id.selected_app_package);
                hidden = itemView.findViewById(R.id.selected_app_hidden);
                remove = itemView.findViewById(R.id.selected_app_remove);
            }
        }
    }
}
