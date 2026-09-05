// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.backup.dialog;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.content.Context;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckedTextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

import com.google.android.material.resources.MaterialAttributes;

import java.util.List;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.backup.BackupFlags;
import io.github.muntashirakon.AppManager.utils.UIUtils;
import io.github.muntashirakon.util.AdapterUtils;

class FlagsAdapter extends RecyclerView.Adapter<FlagsAdapter.ViewHolder> {
    // Fork: a row may carry a trailing action. Used by App-supplied data, whose per-app category
    // picker has to be reachable from the row it belongs to rather than from a dialog button
    // somewhere else (白い熊, +112).
    private static final int TYPE_PLAIN = 0;
    private static final int TYPE_WITH_ACTION = 1;

    public interface FlagActionListener {
        void onAction(@BackupFlags.BackupFlag int flag);
    }

    private final int mLayoutId;
    private final List<Integer> mSupportedBackupFlags;
    private final CharSequence[] mSupportedBackupFlagNames;
    @BackupFlags.BackupFlag
    private final int mDisabledFlags;

    @BackupFlags.BackupFlag
    private int mSelectedFlags;
    @BackupFlags.BackupFlag
    private int mActionFlag;
    @Nullable
    private FlagActionListener mActionListener;
    private int mActionLabelRes;

    @SuppressLint("RestrictedApi")
    public FlagsAdapter(@NonNull Context context, @BackupFlags.BackupFlag int flags,
                        @BackupFlags.BackupFlag int supportedFlags) {
        this(context, flags, supportedFlags, 0);
    }

    @SuppressLint("RestrictedApi")
    public FlagsAdapter(@NonNull Context context, @BackupFlags.BackupFlag int flags,
                        @BackupFlags.BackupFlag int supportedFlags, @BackupFlags.BackupFlag int disabledFlags) {
        mLayoutId = MaterialAttributes.resolveInteger(context, androidx.appcompat.R.attr.multiChoiceItemLayout,
                com.google.android.material.R.layout.mtrl_alert_select_dialog_multichoice);
        // We list |supportedFlags| and select |flags| by default
        mSupportedBackupFlags = BackupFlags.getBackupFlagsAsArray(supportedFlags);
        mSupportedBackupFlagNames = BackupFlags.getFormattedFlagNames(context, mSupportedBackupFlags);
        mSelectedFlags = flags;
        mDisabledFlags = disabledFlags;
        notifyItemRangeInserted(0, mSupportedBackupFlags.size());
    }

    public int getSelectedFlags() {
        return mSelectedFlags;
    }

    /**
     * Fork: give one flag's row a trailing button. Pass {@code flag == 0} for none.
     */
    public void setFlagAction(@BackupFlags.BackupFlag int flag, int labelRes,
                              @Nullable FlagActionListener listener) {
        mActionFlag = flag;
        mActionLabelRes = labelRes;
        mActionListener = listener;
        notifyItemRangeChanged(0, mSupportedBackupFlags.size());
    }

    @Override
    public int getItemViewType(int position) {
        return mActionFlag != 0 && mSupportedBackupFlags.get(position) == mActionFlag
                ? TYPE_WITH_ACTION : TYPE_PLAIN;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(mLayoutId, parent, false);
        if (viewType != TYPE_WITH_ACTION) {
            return new ViewHolder(view);
        }
        // Built in code rather than as a layout so the row keeps the M3 multi-choice item exactly
        // as every other row has it — the item layout is resolved from a theme attribute at
        // runtime and cannot be <include>d.
        Context context = parent.getContext();
        LinearLayoutCompat container = new LinearLayoutCompat(context);
        container.setOrientation(LinearLayoutCompat.VERTICAL);
        container.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(view, new LinearLayoutCompat.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        MaterialButton button = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        LinearLayoutCompat.LayoutParams lp = new LinearLayoutCompat.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.END;
        lp.setMarginEnd((int) (16 * context.getResources().getDisplayMetrics().density));
        button.setLayoutParams(lp);
        return new ViewHolder(container, view.findViewById(android.R.id.text1), button, container);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        int flag = mSupportedBackupFlags.get(position);
        boolean isSelected = (mSelectedFlags & flag) != 0;
        boolean isDisabled = (mDisabledFlags & flag) != 0;
        holder.item.setChecked(isSelected);
        holder.item.setEnabled(!isDisabled);
        holder.item.setText(mSupportedBackupFlagNames[position]);
        if (holder.action != null && holder.actionParent != null) {
            if (mActionListener != null && mActionLabelRes != 0) {
                if (holder.action.getParent() == null) {
                    holder.actionParent.addView(holder.action);
                }
                holder.action.setText(mActionLabelRes);
                holder.action.setVisibility(View.VISIBLE);
                holder.action.setOnClickListener(v -> {
                    if (mActionListener != null) {
                        mActionListener.onAction(flag);
                    }
                });
            } else {
                holder.action.setVisibility(View.GONE);
            }
        }
        holder.item.setOnClickListener(v -> {
            if (isSelected) {
                // Now unselected
                mSelectedFlags &= ~flag;
            } else {
                // Now selected
                mSelectedFlags |= flag;
            }
            notifyItemChanged(position, AdapterUtils.STUB);
        });
    }

    @Override
    public int getItemCount() {
        return mSupportedBackupFlags.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        CheckedTextView item;
        @Nullable
        MaterialButton action;
        @Nullable
        ViewGroup actionParent;

        public ViewHolder(@NonNull View itemView) {
            this(itemView, itemView.findViewById(android.R.id.text1), null, null);
        }

        public ViewHolder(@NonNull View itemView, @NonNull CheckedTextView text,
                          @Nullable MaterialButton action, @Nullable ViewGroup actionParent) {
            super(itemView);
            item = text;
            this.action = action;
            this.actionParent = actionParent;
            // textAppearanceBodyLarge
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            item.setTextColor(UIUtils.getTextColorSecondary(item.getContext()));
            // Fork: same tick-tint fix as the searchable dialogs — the framework check drawable
            // ignores this theme's accent and renders the Material baseline lavender.
            int checked = MaterialColors.getColor(item.getContext(),
                    androidx.appcompat.R.attr.colorPrimary, -1);
            TextViewCompat.setCompoundDrawableTintList(item, new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[0]},
                    new int[]{checked, UIUtils.getTextColorSecondary(item.getContext())}));
        }
    }
}
