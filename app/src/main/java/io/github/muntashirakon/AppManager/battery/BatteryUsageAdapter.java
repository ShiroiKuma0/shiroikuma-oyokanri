// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.battery;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.db.dao.BatterySampleDao;
import io.github.muntashirakon.AppManager.fonts.ColorPrefs;
import io.github.muntashirakon.AppManager.fonts.RunningBoxPrefs;
import io.github.muntashirakon.AppManager.self.imagecache.ImageLoader;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;

/**
 * Fork: the ranked battery list, rendered to the <b>same conventions as the
 * main app list</b> — card film, icon column with the freeze snowflake and the
 * force-stop ✕ beneath the icon, italic label for dormant rows, type-coloured
 * running box. Only the right-hand column differs: where the main list shows
 * version and signature, this shows the app's share of drain and the counters
 * behind it.
 *
 * <p>Sharing the conventions rather than the code is deliberate — the main
 * adapter is bound to {@code ApplicationItem} and the whole selection/profile
 * machinery — but every colour and rule is read from the same prefs, so the two
 * screens cannot drift apart visually.
 */
public class BatteryUsageAdapter extends RecyclerView.Adapter<BatteryUsageAdapter.ViewHolder> {
    /** Rows above this share of the window's impact are drawn in alarm red. */
    private static final float HOT_SHARE = 0.25f;
    private static final int COLOR_HOT = 0xFFFF0028;

    public interface OnRowClick {
        void onClick(@NonNull BatteryUsageViewModel.Row row);
    }

    public interface RowActions {
        void onKill(@NonNull BatteryUsageViewModel.Row row);

        void onToggleFreeze(@NonNull BatteryUsageViewModel.Row row);

        /** Raw counters without leaving the list — the quick look. */
        void onLongPress(@NonNull BatteryUsageViewModel.Row row);
    }

    private final Context mContext;
    private final OnRowClick mOnRowClick;
    private final RowActions mActions;
    private final List<BatteryUsageViewModel.Row> mRows = new ArrayList<>();
    /**
     * The device chart + drainers panel, carried as row 0 so the whole page
     * scrolls as one — a fixed header ate a third of the screen permanently.
     */
    @Nullable
    private View mHeaderView;

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ROW = 1;

    public BatteryUsageAdapter(@NonNull Context context, @NonNull OnRowClick onRowClick,
                               @NonNull RowActions actions) {
        mContext = context;
        mOnRowClick = onRowClick;
        mActions = actions;
    }

    public void setHeaderView(@Nullable View header) {
        mHeaderView = header;
        notifyDataSetChanged();
    }

    public boolean hasHeader() {
        return mHeaderView != null;
    }

    @Override
    public int getItemViewType(int position) {
        return mHeaderView != null && position == 0 ? TYPE_HEADER : TYPE_ROW;
    }

    public void setRows(@NonNull List<BatteryUsageViewModel.Row> rows) {
        mRows.clear();
        mRows.addAll(rows);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER && mHeaderView != null) {
            ViewGroup parentOfHeader = (ViewGroup) mHeaderView.getParent();
            if (parentOfHeader != null) parentOfHeader.removeView(mHeaderView);
            mHeaderView.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new ViewHolder(mHeaderView);
        }
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_main, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        if (getItemViewType(position) == TYPE_HEADER) return;
        BatteryUsageViewModel.Row row = mRows.get(mHeaderView != null ? position - 1 : position);
        bindCard(mContext, h.itemView, row, true,
                v -> mOnRowClick.onClick(row),
                v -> mActions.onToggleFreeze(row),
                v -> mActions.onKill(row));
        h.itemView.setOnLongClickListener(v -> {
            mActions.onLongPress(row);
            return true;
        });
    }

    /**
     * Binds one battery card. Static and shared, because the app's own page uses
     * the very same card as its header — one visual language across the ranking,
     * the detail page and the main list, rather than three that drift apart.
     *
     * @param showShare false on the detail header, where "100%" of itself would
     *                  be noise
     */
    public static void bindCard(@NonNull Context context, @NonNull View itemView,
                                @NonNull BatteryUsageViewModel.Row row, boolean showShare,
                                @NonNull View.OnClickListener onRow,
                                @NonNull View.OnClickListener onFreeze,
                                @NonNull View.OnClickListener onKill) {
        // Everything expensive was resolved on the worker thread — see Row.
        MainCardBinder.bind(context, itemView, row.appItem, row.applicationInfo,
                row.packageName, row.uid, row.frozen, row.profileTags,
                row, row.windowLabel, false, onRow, onFreeze, onKill);
    }

    /**
     * The one-line "why". Only the counters that carry signal for this row are
     * shown, strongest first, so a network hog and a wakelock hog do not read
     * as the same kind of problem.
     */
    /**
     * The metric lines for the card's right column, strongest first.
     *
     * <p>Different apps show different counters because the two lines carry the
     * two strongest signals <i>for that app</i> — a messenger's drain is packets,
     * a map app's is sensor time. Showing "0 packets" on the map app would fill
     * the slot with a zero and bury the line that matters. The slot is never
     * left empty though: when an app has fewer signals than lines, the measured
     * window fills the rest.
     */
    @NonNull
    static String[] describeLines(@NonNull Context context,
                                  @NonNull BatterySampleDao.BatteryAggregate agg, int max) {
        List<String> parts = metricParts(context, agg);
        while (parts.size() < max) {
            parts.add(context.getString(R.string.battery_metric_over,
                    formatDuration(agg.duration)));
            break;
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < parts.size() && i < max; i++) out.add(parts.get(i));
        while (out.size() < Math.min(2, max)) out.add("");
        return out.toArray(new String[0]);
    }

    /** Compact counts, so "15412 packets" does not overrun a two-column cell. */
    @NonNull
    private static String compact(long value) {
        if (value >= 1_000_000L) return String.format(Locale.getDefault(), "%.1fM", value / 1_000_000d);
        if (value >= 1_000L) return String.format(Locale.getDefault(), "%.1fk", value / 1_000d);
        return String.valueOf(value);
    }

    @NonNull
    private static List<String> metricParts(@NonNull Context mContext,
                                            @NonNull BatterySampleDao.BatteryAggregate agg) {
        List<String> parts = new ArrayList<>();
        double hours = agg.duration > 0 ? agg.duration / 3_600_000d : 0;
        long packets = agg.totalPackets();
        if (packets > 0 && agg.duration > 0) {
            double perSecond = packets / (agg.duration / 1000d);
            parts.add(perSecond >= 1
                    ? mContext.getString(R.string.battery_metric_packets_per_sec, Math.round(perSecond))
                    : mContext.getString(R.string.battery_metric_packets, compact(packets)));
        }
        if (agg.totalBytes() > 0 && hours > 0) {
            String perHour = Formatter.formatShortFileSize(mContext, Math.round(agg.totalBytes() / hours));
            parts.add(mContext.getString(R.string.battery_metric_per_hour, perHour));
        }
        if (agg.wakelockMs > 0) {
            parts.add(mContext.getString(R.string.battery_metric_wakelock, formatDuration(agg.wakelockMs)));
        }
        if (agg.radioActiveMs > 0) {
            parts.add(mContext.getString(R.string.battery_metric_radio, formatDuration(agg.radioActiveMs)));
        }
        if (agg.cpuMs > 0 && agg.duration > 0) {
            double percent = 100d * agg.cpuMs / agg.duration;
            if (percent >= 0.1) parts.add(mContext.getString(R.string.battery_metric_cpu, percent));
        }
        if (agg.sensorMs > 0) {
            parts.add(mContext.getString(R.string.battery_metric_sensor, formatDuration(agg.sensorMs)));
        }
        if (agg.wakeupCount > 0) {
            parts.add(mContext.getString(R.string.battery_metric_wakeups, compact(agg.wakeupCount)));
        }
        return parts;
    }

    @NonNull
    static String formatDuration(long ms) {
        if (ms >= 3_600_000L) return String.format(Locale.getDefault(), "%.1fh", ms / 3_600_000d);
        if (ms >= 60_000L) return String.format(Locale.getDefault(), "%.0fm", ms / 60_000d);
        if (ms >= 1_000L) return String.format(Locale.getDefault(), "%.0fs", ms / 1_000d);
        return ms + "ms";
    }

    @Override
    public int getItemCount() {
        return mRows.size() + (mHeaderView != null ? 1 : 0);
    }

    /**
     * Holds nothing: rows are {@code item_main.xml} and every lookup happens in
     * {@link MainCardBinder}, so caching a second set of references here would
     * only be a chance for the two to disagree.
     */
    static class ViewHolder extends RecyclerView.ViewHolder {
        ViewHolder(@NonNull View v) {
            super(v);
        }
    }
}
