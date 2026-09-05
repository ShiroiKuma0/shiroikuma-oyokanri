// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.appcompat.widget.LinearLayoutCompat;

import java.text.DateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.fonts.ColorPrefs;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;

/**
 * Fork (白い熊, +118): fills the pane that a tap unrolls under a row.
 *
 * <p>It answers the question the old behaviour answered badly. A tap used to leave the list
 * entirely for App details, so learning one fact about one app cost the place you were in; and
 * the actions lived in a bottom bar whose labels were cut off. Here the facts the row cannot fit
 * are stated plainly, and every action is a full-width-legible pill.
 *
 * <p>Everything is rebuilt on each bind rather than kept: a row is recycled, an app's state
 * changes under it, and a pane that remembered anything would be showing another app's facts.
 */
public final class AppPaneBinder {
    /** What the pane needs the list to do for it. Implemented by the adapter. */
    public interface Host {
        void onPaneAction(@NonNull String key, @NonNull ApplicationItem item);

        /** The pills were dragged into a new order and saved; redraw the open pane. */
        void onPaneReordered();
    }

    private AppPaneBinder() {
    }

    /**
     * Rebuild {@code pane} for {@code item}.
     *
     * @param collapsedHeightPx the pane's ceiling, so an app with many backups cannot push the
     *                          rest of the list off the screen.
     */
    public static void bind(@NonNull LinearLayoutCompat pane, @NonNull ApplicationItem item,
                            @NonNull Host host, int collapsedHeightPx) {
        Context context = pane.getContext();
        pane.removeAllViews();
        int ink = ForkThemeUtils.getTextColor();
        int dim = ColorPrefs.getColor(context, ColorPrefs.DATE_NORMAL);

        pane.addView(rule(context, ink));
        pane.addView(facts(context, item, ink, dim));

        // The actions, in the order they were configured. Wrapping is what makes the labels
        // readable — the bar this replaced had to cut them off because it was one row that could
        // not grow. libcore's FlowLayout already does exactly this and is what the backup dialog
        // uses, so there is no second one here.
        io.github.muntashirakon.widget.FlowLayout flow = flowLayout(context);
        List<String> placed = new ArrayList<>();
        for (String key : AppPanePrefs.loadVisibleOrder(context)) {
            CharSequence title = labelFor(context, key, item);
            if (title == null) {
                continue;
            }
            TextView pill = RowPills.actionPill(context, title, AppPanePrefs.iconForKey(key), ink, false);
            pill.setOnClickListener(v -> host.onPaneAction(key, item));
            flow.addView(pill);
            placed.add(key);
        }
        pane.addView(flow);
        // Fork (白い熊, +125): long-press a pill and drop it on another to reorder them here,
        // exactly as the batch pane does. Only the pills ACTUALLY PLACED are handed over — a
        // pill skipped because the action makes no sense for this app (an app with no launcher
        // has no Open) would otherwise put the keys and the views one out of step, and the drag
        // would move the wrong one.
        PillDragReorder.attach(flow, placed, reordered -> {
            List<String> hidden = AppPanePrefs.loadHiddenOrder(context);
            List<String> visible = new ArrayList<>(reordered);
            // A key that this app hid stays visible for every other app: it was never reordered,
            // so it keeps its place relative to the ones that were.
            for (String key : AppPanePrefs.loadVisibleOrder(context)) {
                if (!visible.contains(key)) {
                    visible.add(key);
                }
            }
            AppPanePrefs.save(context, visible, hidden);
            host.onPaneReordered();
        });
        pane.setMinimumHeight(0);
    }

    /**
     * The freeze pill says which way it will go, and the note pill says whether there is one —
     * a pill that reads "Freeze" on an already-frozen app is worse than no pill.
     */
    @Nullable
    private static CharSequence labelFor(@NonNull Context context, @NonNull String key,
                                         @NonNull ApplicationItem item) {
        switch (key) {
            case "freeze":
                return context.getString(item.isFrozen ? R.string.unfreeze : R.string.freeze);
            case "open":
                // An app with no launchable activity cannot be opened; offering it would be a
                // pill that does nothing.
                return item.isInstalled && item.hasActivities
                        ? context.getString(R.string.launch_app) : null;
            case "force_stop":
                return item.isInstalled ? context.getString(R.string.force_stop) : null;
            case "uninstall":
            case "clear_data":
            case "clear_cache":
            case "save_apk":
            case "app_settings":
                return item.isInstalled ? context.getString(AppPanePrefs.titleForKey(key)) : null;
            default: {
                int res = AppPanePrefs.titleForKey(key);
                return res == 0 ? null : context.getString(res);
            }
        }
    }

    /** The facts a row has no room for, two to a line. */
    @NonNull
    private static View facts(@NonNull Context context, @NonNull ApplicationItem item, int ink, int dim) {
        io.github.muntashirakon.widget.FlowLayout flow = flowLayout(context);
        DateFormat dateFormat = DateFormat.getDateInstance(DateFormat.SHORT);
        addFact(flow, context, R.string.size, item.totalSize != null && item.totalSize > 0
                ? Formatter.formatFileSize(context, item.totalSize) : null, ink, dim);
        addFact(flow, context, R.string.data_usage_msg, item.dataUsage != null && item.dataUsage > 0
                ? Formatter.formatFileSize(context, item.dataUsage) : null, ink, dim);
        addFact(flow, context, R.string.pane_last_used, item.lastUsageTime != null && item.lastUsageTime > 0
                ? dateFormat.format(new Date(item.lastUsageTime)) : null, ink, dim);
        addFact(flow, context, R.string.pane_installed, item.firstInstallTime > 0
                ? dateFormat.format(new Date(item.firstInstallTime)) : null, ink, dim);
        addFact(flow, context, R.string.pane_target_sdk, item.targetSdk != null
                ? String.valueOf(item.targetSdk) : null, ink, dim);
        addFact(flow, context, R.string.trackers, item.trackerCount != null && item.trackerCount > 0
                ? String.valueOf(item.trackerCount) : null, ink, dim);
        addFact(flow, context, R.string.pane_blocked, item.blockedCount != null && item.blockedCount > 0
                ? String.valueOf(item.blockedCount) : null, ink, dim);
        return flow;
    }

    /** A fact is only shown when it has a value; an empty one is noise, not information. */
    private static void addFact(@NonNull ViewGroup parent, @NonNull Context context, int labelRes,
                                @Nullable CharSequence value, int ink, int dim) {
        if (TextUtils.isEmpty(value)) {
            return;
        }
        AppCompatTextView view = new AppCompatTextView(context);
        String label = context.getString(labelRes);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        int start = sb.length();
        sb.append(label).append(' ');
        sb.setSpan(new android.text.style.ForegroundColorSpan(dim), start, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        start = sb.length();
        sb.append(value);
        sb.setSpan(new android.text.style.ForegroundColorSpan(ink), start, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(sb);
        view.setTextSize(12f);
        view.setSingleLine(true);
        int padH = Math.round(ForkThemeUtils.dpToPx(context, 6f));
        int padV = Math.round(ForkThemeUtils.dpToPx(context, 3f));
        view.setPadding(padH, padV, padH, padV);
        parent.addView(view);
    }

    /** A wrapping row of children, spaced the way the backup dialog's action row is. */
    @NonNull
    private static io.github.muntashirakon.widget.FlowLayout flowLayout(@NonNull Context context) {
        io.github.muntashirakon.widget.FlowLayout flow =
                new io.github.muntashirakon.widget.FlowLayout(context);
        flow.setChildSpacing(Math.round(ForkThemeUtils.dpToPx(context, 8f)));
        flow.setRowSpacing(Math.round(ForkThemeUtils.dpToPx(context, 6f)));
        return flow;
    }

    /** A hairline in the theme ink, separating the row from what it unrolled. */
    @NonNull
    private static View rule(@NonNull Context context, int ink) {
        View rule = new View(context);
        LinearLayoutCompat.LayoutParams lp = new LinearLayoutCompat.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, Math.round(ForkThemeUtils.dpToPx(context, 1f))));
        lp.bottomMargin = Math.round(ForkThemeUtils.dpToPx(context, 8f));
        rule.setLayoutParams(lp);
        rule.setBackgroundColor(RowPills.withAlpha(ink, 0.35f));
        return rule;
    }
}
