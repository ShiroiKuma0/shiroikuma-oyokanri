// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.trackers;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.annotation.WorkerThread;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.widget.NestedScrollView;

import java.util.Map;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.main.RowPills;
import io.github.muntashirakon.AppManager.rules.RuleType;
import io.github.muntashirakon.AppManager.rules.compontents.ComponentsBlocker;
import io.github.muntashirakon.AppManager.screens.ListScreenActivity;
import io.github.muntashirakon.AppManager.self.SelfPermissions;
import io.github.muntashirakon.AppManager.utils.ForkDialog;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;

/**
 * Fork (白い熊, +146): what one tracker is doing in this app.
 *
 * <p>白い熊 asked what a tracker pill should lead to — a description, the other apps carrying it,
 * a filter. It leads to <b>the local facts first</b>: the components this tracker owns here, which
 * is both the evidence for the pill's colour and the only part of it that is about <i>your</i>
 * phone. Then the two things worth leaving for: every other app that carries the same tracker, and
 * the Exodus report. The web link is last on purpose — it is the weakest thing on this sheet.
 *
 * <p><b>There is no Block button on this phone, and that is stated rather than hidden.</b>
 * {@code ComponentsBlocker.applyRules} refuses unless {@code canModifyAppComponentStates} passes,
 * and since Android O the shell may not change component state for anything but a test-only app;
 * the Intent Firewall route needs root. So the pill is offered only where it would actually work,
 * following the page's own rule that a switch which cannot move is not shown — but the reason is
 * printed, because a missing button with no explanation reads as an oversight.
 */
public final class TrackerDialog {
    private TrackerDialog() {
    }

    public static void show(@NonNull Context context, @NonNull PackageInfo packageInfo, int userId,
                            @NonNull TrackerHit hit) {
        int ink = ForkThemeUtils.getTextColor();
        int dim = RowPills.withAlpha(ink, 0.6f);
        float density = context.getResources().getDisplayMetrics().density;
        boolean canBlock = canBlock(packageInfo, userId);

        LinearLayoutCompat body = new LinearLayoutCompat(context);
        body.setOrientation(LinearLayoutCompat.VERTICAL);
        int pad = Math.round(20 * density);
        body.setPadding(pad, Math.round(4 * density), pad, 0);

        body.addView(line(context, rungText(context, hit), TrackerPanel.colorFor(hit.rung()), 14, true));
        if (hit.secondDegree) {
            body.addView(line(context, context.getString(R.string.tracker_second_degree_note), dim, 12, false));
        }
        if (hit.classes > 0) {
            body.addView(line(context, context.getString(R.string.tracker_class_count, hit.classes), dim, 12, false));
        }

        if (hit.isCodeOnly()) {
            body.addView(line(context, context.getString(R.string.tracker_code_only_note), dim, 12, false));
        } else {
            body.addView(header(context, context.getString(R.string.tracker_components_title,
                    hit.components.size()), ink, density));
            int shown = 0;
            for (Map.Entry<String, RuleType> entry : hit.components.entrySet()) {
                if (shown++ == 12) {
                    body.addView(line(context, context.getString(R.string.tracker_more_components,
                            hit.components.size() - 12), dim, 12, false));
                    break;
                }
                body.addView(component(context, entry.getKey(), entry.getValue(), ink, dim));
            }
        }

        if (!canBlock) {
            body.addView(header(context, "", ink, density));
            body.addView(line(context, context.getString(R.string.tracker_cannot_block), dim, 12, false));
        }

        io.github.muntashirakon.widget.FlowLayout actions = new io.github.muntashirakon.widget.FlowLayout(context);
        actions.setChildSpacing(Math.round(6 * density));
        actions.setRowSpacing(Math.round(2 * density));
        LinearLayoutCompat.LayoutParams actionsLp = new LinearLayoutCompat.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = Math.round(12 * density);
        actions.setLayoutParams(actionsLp);
        body.addView(actions);

        NestedScrollView scroller = new NestedScrollView(context);
        scroller.addView(body);

        androidx.appcompat.app.AlertDialog dialog = ForkDialog.present(ForkDialog.builder(context)
                .setTitle(hit.name)
                .setView(scroller)
                .setPositiveButton(R.string.close, null));

        TextView others = RowPills.actionPill(context, context.getString(R.string.tracker_other_apps),
                0, ink, false);
        others.setOnClickListener(v -> {
            dialog.dismiss();
            context.startActivity(ListScreenActivity.intentForTracker(context, hit.name));
        });
        actions.addView(others);
        if (canBlock) {
            TextView block = RowPills.actionPill(context, context.getString(R.string.tracker_block),
                    0, TrackerHit.COLOR_AUTONOMOUS, false);
            block.setOnClickListener(v -> {
                dialog.dismiss();
                ThreadUtils.postOnBackgroundThread(() -> {
                    boolean ok = block(packageInfo.packageName, userId, hit);
                    ThreadUtils.postOnMainThread(() -> UIUtils.displayShortToast(
                            ok ? R.string.done : R.string.failed));
                });
            });
            actions.addView(block);
        }
        TextView exodus = RowPills.actionPill(context, context.getString(R.string.tracker_exodus),
                0, dim, false);
        exodus.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://reports.exodus-privacy.eu.org/en/reports/" + packageInfo.packageName + "/latest/"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(intent);
            } catch (Throwable th) {
                UIUtils.displayShortToast(R.string.failed);
            }
        });
        actions.addView(exodus);
    }

    /**
     * Whether a Block button could do anything at all here. Asked of the platform rather than
     * assumed: on a rooted phone, or for a test-only app, the answer is yes and the pill appears.
     */
    private static boolean canBlock(@NonNull PackageInfo packageInfo, int userId) {
        try {
            boolean testOnly = packageInfo.applicationInfo != null
                    && io.github.muntashirakon.AppManager.compat.ApplicationInfoCompat
                    .isTestOnly(packageInfo.applicationInfo);
            return SelfPermissions.canModifyAppComponentStates(userId, packageInfo.packageName, testOnly);
        } catch (Throwable th) {
            return false;
        }
    }

    @WorkerThread
    private static boolean block(@NonNull String packageName, int userId, @NonNull TrackerHit hit) {
        try (ComponentsBlocker cb = ComponentsBlocker.getMutableInstance(packageName, userId)) {
            for (Map.Entry<String, RuleType> entry : hit.components.entrySet()) {
                cb.addComponent(entry.getKey(), entry.getValue());
            }
            return cb.applyRules(true);
        } catch (Throwable th) {
            th.printStackTrace();
            return false;
        }
    }

    @NonNull
    private static String rungText(@NonNull Context context, @NonNull TrackerHit hit) {
        @StringRes int res;
        switch (hit.rung()) {
            case TrackerHit.RUNG_AUTONOMOUS:
                res = R.string.tracker_rung_autonomous;
                break;
            case TrackerHit.RUNG_WITH_APP:
                res = R.string.tracker_rung_with_app;
                break;
            default:
                res = R.string.tracker_rung_passive;
                break;
        }
        return context.getString(res);
    }

    @NonNull
    private static AppCompatTextView line(@NonNull Context context, @NonNull CharSequence text,
                                          @ColorInt int colour, float sp, boolean bold) {
        AppCompatTextView view = new AppCompatTextView(context);
        view.setText(text);
        view.setTextColor(colour);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    @NonNull
    private static AppCompatTextView header(@NonNull Context context, @NonNull CharSequence text,
                                            @ColorInt int ink, float density) {
        AppCompatTextView view = line(context, text, ink, 13, true);
        LinearLayoutCompat.LayoutParams lp = new LinearLayoutCompat.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(10 * density);
        view.setLayoutParams(lp);
        return view;
    }

    /**
     * One component: its kind, then its class name. Middle-ellipsized, because a class name is
     * long, identical at the front across a whole SDK, and only distinguishable at the end.
     */
    @NonNull
    private static AppCompatTextView component(@NonNull Context context, @NonNull String name,
                                               @NonNull RuleType type, @ColorInt int ink,
                                               @ColorInt int dim) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int start = sb.length();
        sb.append(kind(type)).append("  ");
        sb.setSpan(new ForegroundColorSpan(dim), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        start = sb.length();
        sb.append(name);
        sb.setSpan(new ForegroundColorSpan(ink), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        AppCompatTextView view = new AppCompatTextView(context);
        view.setText(sb);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        view.setTypeface(Typeface.MONOSPACE);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        return view;
    }

    @NonNull
    private static String kind(@NonNull RuleType type) {
        switch (type) {
            case ACTIVITY: return "ACT";
            case SERVICE: return "SVC";
            case RECEIVER: return "RCV";
            case PROVIDER: return "PRV";
            default: return type.name();
        }
    }
}
