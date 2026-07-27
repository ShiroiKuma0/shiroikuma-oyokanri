// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.details;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.GridLayoutManager;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.details.struct.AppDetailsItem;
import io.github.muntashirakon.AppManager.details.struct.AppDetailsSnoopingItem;
import io.github.muntashirakon.AppManager.snooping.SnoopingCatalog;
import io.github.muntashirakon.AppManager.snooping.SnoopingEnforcer;
import io.github.muntashirakon.AppManager.snooping.SnoopingPrefs;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;
import io.github.muntashirakon.view.ProgressIndicatorCompat;
import io.github.muntashirakon.widget.MaterialAlertView;
import io.github.muntashirakon.widget.RecyclerView;

/**
 * Fork: the <em>Snooping</em> tab — every privacy-invasive capability of this app
 * that we can <b>actually</b> switch off through ADB/Shizuku, grouped and in one
 * place, instead of scattered across the App Ops and Permissions tabs among
 * hundreds of rows that mostly cannot be moved.
 * <p>
 * Switch semantics match the rest of the app: <b>on = the app is allowed</b>,
 * off = blocked. Every flip is also <i>recorded</i> against the package name, so
 * the decision survives an uninstall, travels in a settings export, and is
 * replayed by {@link SnoopingEnforcer} when the package appears on another phone
 * — see {@link SnoopingPrefs}.
 */
public class AppDetailsSnoopingFragment extends AppDetailsFragment {
    // Fork: the shared success/failure colours (#1b8654 salem_green / #ff0028
    // electric_red) are meant for a light-ish surface; at body-small size on the
    // pure-black app-details page they are close to illegible — the red worst of
    // all. These rows therefore use brightened foregrounds on a chip tinted with
    // the same hue, which lifts the text off the black without shouting.
    /**
     * "Allowed" is the state worth flinching at, so its pill is filled blood red
     * rather than washed — with near-white text, since the point of the pill was
     * legibility in the first place.
     */
    @ColorInt
    private static final int STATUS_CHIP_ALLOWED = 0xFF6E0B14;
    @ColorInt
    private static final int STATUS_TEXT_ALLOWED = 0xFFFFD9DC;
    @ColorInt
    private static final int STATUS_COLOR_BLOCKED = 0xFF7FE3A5;
    @ColorInt
    private static final int DETAIL_COLOR = 0xFFD0D6DC;
    /** Blocked chip fill = its text colour at this alpha, i.e. a wash of its own hue. */
    private static final int STATUS_CHIP_ALPHA = 0x3D;
    private static final int DETAIL_CHIP_ALPHA = 0x1F;
    /** Outline of a row whose live state is not the default one. */
    private static final float CHANGED_STROKE_DP = 3f;
    /**
     * …and in red when the change went the dangerous way — switched ON where the
     * platform's own default is off. Yellow marks the protective direction.
     */
    @ColorInt
    private static final int CHANGED_STROKE_ALLOWED = 0xFFFF0028;

    private SnoopingRecyclerAdapter mAdapter;
    private boolean mCanEnforce;

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        emptyView.setText(R.string.snooping_no_capabilities);
        mAdapter = new SnoopingRecyclerAdapter();
        recyclerView.setAdapter(mAdapter);
        // Group headings must span the whole row when the list goes multi-column
        // (it does on the tri-fold, at 450dp per column).
        if (recyclerView.getLayoutManager() instanceof GridLayoutManager) {
            GridLayoutManager glm = (GridLayoutManager) recyclerView.getLayoutManager();
            glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
                @Override
                public int getSpanSize(int position) {
                    // Read the span count at call time: it is auto-fitted and
                    // changes when the fold state changes.
                    return mAdapter != null && mAdapter.isHeader(position) ? glm.getSpanCount() : 1;
                }
            });
        }
        mCanEnforce = SnoopingEnforcer.canEnforce();
        alertView.setEndIconOnClickListener(v -> alertView.hide());
        if (!mCanEnforce) {
            alertView.setAlertType(MaterialAlertView.ALERT_TYPE_WARN);
            alertView.setText(R.string.snooping_needs_privileges);
            alertView.show();
        } else {
            alertView.setVisibility(View.GONE);
        }
        if (viewModel == null) return;
        viewModel.get(AppDetailsFragment.SNOOPING).observe(getViewLifecycleOwner(), items -> {
            if (items != null && mAdapter != null && viewModel.isPackageExist()) {
                mAdapter.setItems(items);
            }
            ProgressIndicatorCompat.setVisibility(progressIndicator, false);
        });
    }

    @Override
    public void onRefresh() {
        refreshDetails();
        swipeRefresh.setRefreshing(false);
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.fragment_app_details_snooping_actions, menu);
    }

    @Override
    public void onPrepareMenu(@NonNull Menu menu) {
        MenuItem showAll = menu.findItem(R.id.action_snooping_show_all);
        if (showAll != null) {
            showAll.setChecked(SnoopingPrefs.isShowAllEnabled());
        }
        MenuItem autoApply = menu.findItem(R.id.action_snooping_auto_apply);
        if (autoApply != null) {
            autoApply.setChecked(SnoopingPrefs.isAutoApplyEnabled());
        }
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_refresh_details) {
            refreshDetails();
            return true;
        }
        if (id == R.id.action_snooping_show_all) {
            SnoopingPrefs.setShowAllEnabled(!SnoopingPrefs.isShowAllEnabled());
            item.setChecked(SnoopingPrefs.isShowAllEnabled());
            refreshDetails();
            return true;
        }
        if (id == R.id.action_snooping_auto_apply) {
            SnoopingPrefs.setAutoApplyEnabled(!SnoopingPrefs.isAutoApplyEnabled());
            item.setChecked(SnoopingPrefs.isAutoApplyEnabled());
            return true;
        }
        if (id == R.id.action_snooping_block_all) {
            confirmBlockAll();
            return true;
        }
        if (id == R.id.action_snooping_forget) {
            confirmForget();
            return true;
        }
        return false;
    }

    private void confirmBlockAll() {
        if (viewModel == null) return;
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.snooping_block_all)
                .setMessage(R.string.snooping_block_all_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.snooping_block_all, (dialog, which) -> {
                    ProgressIndicatorCompat.setVisibility(progressIndicator, true);
                    ThreadUtils.postOnBackgroundThread(() -> {
                        int blocked = viewModel.blockAllSnooping();
                        ThreadUtils.postOnMainThread(() -> {
                            if (isDetached()) return;
                            if (blocked < 0) {
                                UIUtils.displayShortToast(R.string.snooping_block_all_failed);
                            } else {
                                UIUtils.displayShortToast(R.string.snooping_blocked_count, blocked);
                            }
                            refreshDetails();
                        });
                    });
                })
                .show();
    }

    private void confirmForget() {
        if (viewModel == null) return;
        int stored = viewModel.getStoredSnoopingCount();
        if (stored == 0) {
            UIUtils.displayShortToast(R.string.snooping_nothing_saved);
            return;
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.snooping_forget)
                .setMessage(getString(R.string.snooping_forget_confirm, stored))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.snooping_forget, (dialog, which) -> {
                    viewModel.forgetSnoopingSettings();
                    refreshDetails();
                })
                .show();
    }

    private void refreshDetails() {
        if (viewModel == null) return;
        ProgressIndicatorCompat.setVisibility(progressIndicator, true);
        viewModel.triggerPackageChange();
    }

    @Override
    protected void search(String query, int type) {
        // Fork: the snooping tab is a fixed, grouped catalogue — not searchable.
    }

    /** A header row, or a capability row. */
    private static class Row {
        @Nullable
        final SnoopingCatalog.Group group;
        @Nullable
        final AppDetailsSnoopingItem item;

        Row(@NonNull SnoopingCatalog.Group group) {
            this.group = group;
            this.item = null;
        }

        Row(@NonNull AppDetailsSnoopingItem item) {
            this.group = null;
            this.item = item;
        }

        boolean isHeader() {
            return item == null;
        }
    }

    private class SnoopingRecyclerAdapter extends RecyclerView.Adapter<SnoopingRecyclerAdapter.ViewHolderBase> {
        private static final int TYPE_HEADER = 0;
        private static final int TYPE_ITEM = 1;

        private final List<Row> mRows = new ArrayList<>();

        @SuppressLint("NotifyDataSetChanged")
        @UiThread
        void setItems(@NonNull List<AppDetailsItem<?>> items) {
            mRows.clear();
            SnoopingCatalog.Group currentGroup = null;
            for (AppDetailsItem<?> raw : items) {
                if (!(raw instanceof AppDetailsSnoopingItem)) continue;
                AppDetailsSnoopingItem item = (AppDetailsSnoopingItem) raw;
                SnoopingCatalog.Group group = item.capability.entry.group;
                if (group != currentGroup) {
                    mRows.add(new Row(group));
                    currentGroup = group;
                }
                mRows.add(new Row(item));
            }
            notifyDataSetChanged();
        }

        boolean isHeader(int position) {
            return position >= 0 && position < mRows.size() && mRows.get(position).isHeader();
        }

        @Override
        public int getItemViewType(int position) {
            return mRows.get(position).isHeader() ? TYPE_HEADER : TYPE_ITEM;
        }

        @Override
        public int getItemCount() {
            return mRows.size();
        }

        @NonNull
        @Override
        public ViewHolderBase onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HEADER) {
                return new HeaderViewHolder(inflater.inflate(R.layout.item_app_details_snooping_header, parent, false));
            }
            return new ItemViewHolder(inflater.inflate(R.layout.item_app_details_snooping, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolderBase holder, int position) {
            Row row = mRows.get(position);
            if (holder instanceof HeaderViewHolder && row.group != null) {
                ((HeaderViewHolder) holder).title.setText(row.group.labelRes);
            } else if (holder instanceof ItemViewHolder && row.item != null) {
                ((ItemViewHolder) holder).bind(row.item);
            }
        }

        abstract class ViewHolderBase extends RecyclerView.ViewHolder {
            ViewHolderBase(@NonNull View itemView) {
                super(itemView);
            }
        }

        class HeaderViewHolder extends ViewHolderBase {
            final TextView title;

            HeaderViewHolder(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.snooping_group_title);
            }
        }

        class ItemViewHolder extends ViewHolderBase {
            final MaterialCardView card;
            final TextView label;
            final TextView status;
            final TextView detail;
            final MaterialSwitch toggle;
            /** The card's own outline, captured before we ever override it. */
            final int defaultStrokeColor;
            final int defaultStrokeWidth;

            ItemViewHolder(@NonNull View itemView) {
                super(itemView);
                card = (MaterialCardView) itemView;
                label = itemView.findViewById(R.id.snooping_label);
                status = itemView.findViewById(R.id.snooping_status);
                detail = itemView.findViewById(R.id.snooping_detail);
                toggle = itemView.findViewById(R.id.snooping_toggle);
                // getStrokeWidth/getStrokeColor are plain fields, safe before
                // layout — unlike getRadius(), which resolves against bounds.
                defaultStrokeColor = card.getStrokeColor();
                defaultStrokeWidth = card.getStrokeWidth();
            }

            void bind(@NonNull AppDetailsSnoopingItem item) {
                Context context = itemView.getContext();
                label.setText(item.capability.entry.labelRes);
                boolean allowed = item.isAllowed();
                toggle.setChecked(allowed);
                status.setText(statusText(context, item, allowed));
                status.setTextColor(allowed ? STATUS_TEXT_ALLOWED : STATUS_COLOR_BLOCKED);
                status.setBackgroundTintList(ColorStateList.valueOf(allowed
                        ? STATUS_CHIP_ALLOWED
                        : ColorUtils.setAlphaComponent(STATUS_COLOR_BLOCKED, STATUS_CHIP_ALPHA)));
                CharSequence detailLine = detailText(context, item);
                detail.setText(detailLine);
                // An empty detail would otherwise render as a stray chip.
                detail.setVisibility(detailLine.length() == 0 ? View.GONE : View.VISIBLE);
                detail.setTextColor(DETAIL_COLOR);
                detail.setBackgroundTintList(ColorStateList.valueOf(
                        ColorUtils.setAlphaComponent(DETAIL_COLOR, DETAIL_CHIP_ALPHA)));
                // One colour language for the whole row:
                //   red    — the app can do this right now
                //   yellow — you closed something the platform leaves open
                //   grey   — off, and off is what a fresh install gives you, so
                //            there is nothing here to look at
                boolean changed = item.isChangedFromDefault();
                int accent;
                if (allowed) {
                    accent = CHANGED_STROKE_ALLOWED;
                } else if (changed) {
                    accent = ForkThemeUtils.getTextColor();
                } else {
                    accent = defaultStrokeColor;
                }
                // The thick frame is reserved for a state you chose: it catches
                // the case with no other tell — a capability that is ON by
                // default and is off only because you turned it off. Both
                // branches set both properties (recycled views).
                if (changed) {
                    card.setStrokeColor(accent);
                    card.setStrokeWidth(Math.round(ForkThemeUtils.dpToPx(context, CHANGED_STROKE_DP)));
                } else {
                    card.setStrokeColor(defaultStrokeColor);
                    card.setStrokeWidth(defaultStrokeWidth);
                }
                // The switch says the same thing: its dot and its outline take the
                // accent, and the track stays hollow so the dot is what you read.
                ColorStateList accentTint = ColorStateList.valueOf(accent);
                toggle.setThumbTintList(accentTint);
                toggle.setTrackDecorationTintList(accentTint);
                toggle.setTrackTintList(ColorStateList.valueOf(Color.TRANSPARENT));
                card.setOnClickListener(v -> onToggle(item, !item.isAllowed()));
                card.setOnLongClickListener(v -> {
                    showRowMenu(item);
                    return true;
                });
            }

            void onToggle(@NonNull AppDetailsSnoopingItem item, boolean allowed) {
                if (viewModel == null) return;
                ProgressIndicatorCompat.setVisibility(progressIndicator, true);
                ThreadUtils.postOnBackgroundThread(() -> {
                    boolean ok = viewModel.setSnoopingAllowed(item, allowed);
                    ThreadUtils.postOnMainThread(() -> {
                        if (isDetached()) return;
                        if (!ok && allowed) {
                            // It refused to turn on, so it can never snoop: the
                            // view model has marked it and a reload drops the row
                            // from the page entirely (SnoopingImmovable).
                            UIUtils.displayLongToast(R.string.snooping_cannot_enable_removed);
                            refreshDetails();
                            return;
                        }
                        ProgressIndicatorCompat.setVisibility(progressIndicator, false);
                        if (!ok) {
                            UIUtils.displayShortToast(R.string.snooping_toggle_failed);
                        }
                        int pos = getBindingAdapterPosition();
                        if (pos != RecyclerView.NO_POSITION) {
                            notifyItemChanged(pos);
                        }
                    });
                });
            }
        }

        private void showRowMenu(@NonNull AppDetailsSnoopingItem item) {
            if (viewModel == null) return;
            CharSequence title = getString(item.capability.entry.labelRes);
            if (item.storedDecision == null) {
                new MaterialAlertDialogBuilder(activity)
                        .setTitle(title)
                        .setMessage(R.string.snooping_not_saved_explanation)
                        .setPositiveButton(R.string.ok, null)
                        .show();
                return;
            }
            new MaterialAlertDialogBuilder(activity)
                    .setTitle(title)
                    .setMessage(item.storedDecision
                            ? R.string.snooping_saved_allowed_explanation
                            : R.string.snooping_saved_blocked_explanation)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.snooping_forget_one, (dialog, which) -> {
                        viewModel.forgetSnoopingSetting(item);
                        refreshDetails();
                    })
                    .show();
        }
    }

    @NonNull
    private CharSequence statusText(@NonNull Context context, @NonNull AppDetailsSnoopingItem item, boolean allowed) {
        // Deliberately says nothing about the stored decision (白い熊, +13): the
        // store now holds only departures from the default, so "saved" would
        // merely restate the switch. What the eye needs instead is which rows
        // were changed at all — that is the card's highlight, in bind().
        StringBuilder sb = new StringBuilder(context.getString(allowed
                ? R.string.snooping_state_allowed
                : R.string.snooping_state_blocked));
        // "Needs no permission" is a property of the capability, NOT of the row's
        // tier: keying it off TIER_UNGATED made the label vanish the moment an op
        // was given an explicit non-default mode (which promotes the row to
        // TIER_REQUESTED), so blocking a row silently changed what it claimed
        // about itself. Ask the capability instead.
        if (item.capability.isUngated()) {
            sb.append(" · ").append(context.getString(R.string.snooping_tier_ungated));
        } else if (item.tier == AppDetailsSnoopingItem.TIER_NOT_REQUESTED) {
            sb.append(" · ").append(context.getString(R.string.snooping_tier_not_requested));
        }
        return sb;
    }

    @NonNull
    private CharSequence detailText(@NonNull Context context, @NonNull AppDetailsSnoopingItem item) {
        String permission = item.getPermissionName();
        if (permission != null) {
            return permission;
        }
        String opName = item.capability.entry.opName;
        return opName != null
                ? context.getString(R.string.snooping_app_op_only, opName)
                : "";
    }
}
