// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.processreaper;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.fonts.ColorPrefs;
import io.github.muntashirakon.AppManager.runningapps.AppProcessItem;
import io.github.muntashirakon.AppManager.self.imagecache.ImageLoader;

/**
 * Fork: process monitor / reaper list. Each row carries the app icon (size from
 * {@link MonitorPrefs}), state-coloured title/badge ({@link ColorPrefs}
 * MONITOR_* roles), and two quick no-confirmation buttons — Protect/Unprotect
 * and Kill. Tap the body for the confirm dialog; long-press a killable row to
 * multi-select.
 */
public class ProcessMonitorAdapter extends RecyclerView.Adapter<ProcessMonitorAdapter.VH> {

    public interface OnRowClick {
        void onClick(@NonNull ProcessMonitorViewModel.Row row);
    }

    public interface OnSelectionChange {
        void onChange(int count);
    }

    public interface RowActions {
        void onProtect(@NonNull ProcessMonitorViewModel.Row row);

        void onKill(@NonNull ProcessMonitorViewModel.Row row);

        /** Long-press on a built-in-denylist app — toggle the user override. */
        void onOverrideToggle(@NonNull ProcessMonitorViewModel.Row row);
    }

    private final Context mCtx;
    private final List<ProcessMonitorViewModel.Row> mRows = new ArrayList<>();
    private final Set<Integer> mSelected = new HashSet<>();
    private boolean mSelectionMode;
    private final OnRowClick mClick;
    private final OnSelectionChange mSelChange;
    private final RowActions mActions;
    private final int mSelectedBg = 0x4DFFFF00;  // ~30% yellow over black

    public ProcessMonitorAdapter(@NonNull Context ctx, @NonNull OnRowClick click,
                                 @NonNull OnSelectionChange selChange, @NonNull RowActions actions) {
        mCtx = ctx.getApplicationContext();
        mClick = click;
        mSelChange = selChange;
        mActions = actions;
    }

    public void setRows(@NonNull List<ProcessMonitorViewModel.Row> rows) {
        mRows.clear();
        mRows.addAll(rows);
        if (!mSelected.isEmpty() || mSelectionMode) {
            mSelected.clear();
            mSelectionMode = false;
            mSelChange.onChange(0);
        }
        notifyDataSetChanged();
    }

    public void clearSelection() {
        if (mSelected.isEmpty() && !mSelectionMode) return;
        mSelected.clear();
        mSelectionMode = false;
        notifyDataSetChanged();
        mSelChange.onChange(0);
    }

    @NonNull
    public List<ProcessMonitorViewModel.Row> getSelectedRows() {
        List<ProcessMonitorViewModel.Row> out = new ArrayList<>(mSelected.size());
        for (Integer pos : mSelected) {
            if (pos >= 0 && pos < mRows.size()) out.add(mRows.get(pos));
        }
        return out;
    }

    private void toggleSelect(int pos) {
        if (pos < 0 || pos >= mRows.size()) return;
        if (!mRows.get(pos).cls.killable) return;
        if (!mSelected.remove(pos)) mSelected.add(pos);
        mSelectionMode = !mSelected.isEmpty();
        notifyItemChanged(pos);
        mSelChange.onChange(mSelected.size());
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_monitor_process, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ProcessMonitorViewModel.Row row = mRows.get(position);
        boolean killable = row.cls.killable;
        boolean leak = row.isLeak;
        boolean userProt = "you".equals(row.cls.reason);

        int killableColor = ColorPrefs.getColor(mCtx, ColorPrefs.MONITOR_KILLABLE);
        int leakColor = ColorPrefs.getColor(mCtx, ColorPrefs.MONITOR_LEAK);
        int protColor = ColorPrefs.getColor(mCtx, ColorPrefs.MONITOR_PROTECTED);
        int userProtColor = ColorPrefs.getColor(mCtx, ColorPrefs.MONITOR_USER_PROTECTED);
        int stateColor = leak ? leakColor : (userProt ? userProtColor : (killable ? killableColor : protColor));

        h.title.setText(row.title);
        h.title.setTextColor(stateColor);
        h.subtitle.setText(row.subtitle);
        h.meta.setText(row.meta);
        h.ram.setText(row.ram);
        h.ram.setTextColor(stateColor);
        h.cpu.setText(row.cpu);
        if (leak) {
            h.badge.setText(mCtx.getString(R.string.monitor_badge_leak) + "  ×" + row.memberCount);
            h.badge.setTextColor(leakColor);
        } else if (userProt) {
            h.badge.setText(String.format(Locale.US, "%s · %s",
                    mCtx.getString(R.string.monitor_badge_protected), row.cls.reason));
            h.badge.setTextColor(userProtColor);
        } else if (killable) {
            h.badge.setText(R.string.monitor_badge_killable);
            h.badge.setTextColor(killableColor);
        } else {
            h.badge.setText(String.format(Locale.US, "%s · %s",
                    mCtx.getString(R.string.monitor_badge_protected), row.cls.reason));
            h.badge.setTextColor(protColor);
        }

        // Row vertical padding (settable, 0 = tightest).
        float density = mCtx.getResources().getDisplayMetrics().density;
        int hPad = Math.round(12 * density);
        int vPad = Math.round(MonitorPrefs.getRowPaddingDp(mCtx) * density);
        h.content.setPadding(hPad, vPad, hPad, vPad);

        // App icon (size from prefs). Real apps get their icon; shell/leak rows a generic glyph.
        int iconPx = Math.round(MonitorPrefs.getIconSizeDp(mCtx) * density);
        ViewGroup.LayoutParams ilp = h.icon.getLayoutParams();
        if (ilp.width != iconPx) {
            ilp.width = iconPx;
            ilp.height = iconPx;
            h.icon.setLayoutParams(ilp);
        }
        boolean realApp = row.item instanceof AppProcessItem
                && row.cls.method != ProcessClassifier.METHOD_SIGKILL;
        if (realApp) {
            PackageInfo pi = ((AppProcessItem) row.item).packageInfo;
            h.icon.setTag(pi.packageName);
            ImageLoader.getInstance().displayImage(pi.packageName, pi.applicationInfo, h.icon);
        } else {
            h.icon.setTag(null);
            h.icon.setImageResource(R.drawable.ic_android);
        }

        h.itemView.setBackgroundColor(mSelected.contains(position) ? mSelectedBg : Color.BLACK);
        h.itemView.setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            if (mSelectionMode) toggleSelect(pos);
            else mClick.onClick(mRows.get(pos));
        });
        h.itemView.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return false;
            ProcessMonitorViewModel.Row r = mRows.get(pos);
            // Built-in-denylist apps (e.g. Huawei Home): long-press toggles the
            // user override (Allow killing / Restore protection) instead of
            // multi-selecting — they're special and not bulk-killable.
            if (ProcessClassifier.isOverridableDenylist(packageOf(r))) {
                mActions.onOverrideToggle(r);
                return true;
            }
            if (!r.cls.killable) return false;
            toggleSelect(pos);
            return true;
        });

        // Quick-action buttons. Protect only for real apps (has a package to
        // denylist) or already user-protected rows; Kill for any killable row.
        // Built-in-denylist apps are controlled by the long-press override, not
        // the protect button, so they don't show it.
        String pkg = packageOf(row);
        boolean overridable = ProcessClassifier.isOverridableDenylist(pkg);
        boolean showProtect = pkg != null && !overridable
                && ((killable && row.cls.method == ProcessClassifier.METHOD_FORCE_STOP) || userProt);
        h.btnProtect.setVisibility(showProtect ? View.VISIBLE : View.INVISIBLE);
        h.btnProtect.setImageResource(userProt ? R.drawable.ic_lock : R.drawable.ic_unlock);
        h.btnProtect.setColorFilter(userProt ? userProtColor : killableColor);
        h.btnProtect.setOnClickListener(v -> mActions.onProtect(row));
        h.btnKill.setVisibility(killable ? View.VISIBLE : View.INVISIBLE);
        h.btnKill.setColorFilter(leakColor);
        h.btnKill.setOnClickListener(v -> mActions.onKill(row));
    }

    @Override
    public int getItemCount() {
        return mRows.size();
    }

    @Nullable
    private static String packageOf(@NonNull ProcessMonitorViewModel.Row row) {
        return row.item instanceof AppProcessItem
                ? ((AppProcessItem) row.item).packageInfo.packageName : null;
    }

    static class VH extends RecyclerView.ViewHolder {
        final View content;
        final AppCompatImageView icon;
        final AppCompatTextView title, subtitle, meta, ram, cpu, badge;
        final AppCompatImageButton btnProtect, btnKill;

        VH(@NonNull View v) {
            super(v);
            content = v.findViewById(R.id.monitor_content);
            icon = v.findViewById(R.id.monitor_icon);
            title = v.findViewById(R.id.monitor_title);
            subtitle = v.findViewById(R.id.monitor_subtitle);
            meta = v.findViewById(R.id.monitor_meta);
            ram = v.findViewById(R.id.monitor_ram);
            cpu = v.findViewById(R.id.monitor_cpu);
            badge = v.findViewById(R.id.monitor_badge);
            btnProtect = v.findViewById(R.id.monitor_btn_protect);
            btnKill = v.findViewById(R.id.monitor_btn_kill);
            badge.setTypeface(badge.getTypeface(), Typeface.BOLD);
        }
    }
}
