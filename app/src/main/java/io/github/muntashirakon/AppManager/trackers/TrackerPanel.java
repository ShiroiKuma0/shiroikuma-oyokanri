// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.trackers;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.appcompat.widget.LinearLayoutCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.main.PillSpan;
import io.github.muntashirakon.AppManager.main.RowPills;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;

/**
 * Fork (白い熊, +146): the tracker row at the top of an app's 盗み見 page.
 *
 * <p>白い熊's ask was that the number be <b>loud</b> — the page's first line, not a fact buried in
 * a list — and that every tracker be named beside it. So: the count at headline size in the colour
 * of the worst thing found, one line saying what those trackers can do, and a pill per tracker.
 *
 * <p><b>The pills are the fork's own pill language</b> ({@link RowPills}), which is what keeps this
 * row from reading as a foreign widget dropped onto the page. Colour is the severity rung
 * ({@link TrackerHit}); a second-degree tracker is drawn faded, the same way an "add" affordance is
 * faded — present, but not making a claim about the app itself.
 *
 * <p>The scan runs on a background thread and the row renders twice: the count as soon as the
 * manifest pass is done, and again after a deep scan if one is asked for. The manifest result is
 * memoised per package version, because a RecyclerView will bind this row again for every scroll
 * that takes it off screen and back.
 */
public final class TrackerPanel {
    /**
     * Scanned results, keyed by package and installed version. Bounded: a RecyclerView binds this
     * row again for every scroll that takes it off screen and back, so a memo is worth having —
     * but a page visited once should not keep its component map for the life of the process.
     */
    private static final Map<String, List<TrackerHit>> sMemo =
            new LinkedHashMap<String, List<TrackerHit>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<TrackerHit>> eldest) {
                    return size() > 24;
                }
            };

    private TrackerPanel() {
    }

    /** Drop the memo for one app — after a deep scan, or when the page is reloaded. */
    public static void invalidate(@Nullable PackageInfo packageInfo) {
        if (packageInfo != null) {
            sMemo.remove(key(packageInfo));
        }
    }

    /**
     * Fill {@code container} with the row for this app. Safe to call again on every bind.
     */
    public static void bind(@NonNull ViewGroup container, @Nullable PackageInfo packageInfo, int userId) {
        Context context = container.getContext();
        container.removeAllViews();
        if (packageInfo == null) {
            return;
        }
        List<TrackerHit> memo = sMemo.get(key(packageInfo));
        if (memo != null) {
            render(container, packageInfo, userId, memo, false);
            return;
        }
        render(container, packageInfo, userId, null, false);
        ThreadUtils.postOnBackgroundThread(() -> {
            List<TrackerHit> hits = TrackerScan.scan(context, packageInfo);
            ThreadUtils.postOnMainThread(() -> {
                sMemo.put(key(packageInfo), hits);
                if (container.isAttachedToWindow() || container.getChildCount() > 0) {
                    render(container, packageInfo, userId, hits, false);
                }
            });
        });
    }

    @NonNull
    private static String key(@NonNull PackageInfo packageInfo) {
        return packageInfo.packageName + "@" + packageInfo.lastUpdateTime;
    }

    /**
     * @param hits     {@code null} while the first scan is still running.
     * @param scanning true while a deep scan is in flight.
     */
    private static void render(@NonNull ViewGroup container, @NonNull PackageInfo packageInfo,
                               int userId, @Nullable List<TrackerHit> hits, boolean scanning) {
        Context context = container.getContext();
        container.removeAllViews();
        int ink = ForkThemeUtils.getTextColor();
        int dim = RowPills.withAlpha(ink, 0.6f);
        float density = context.getResources().getDisplayMetrics().density;

        // No padding of its own: the card this is drawn into supplies the list-item padding, the
        // same values the device-policy card below uses (+147).
        LinearLayoutCompat root = new LinearLayoutCompat(context);
        root.setOrientation(LinearLayoutCompat.VERTICAL);

        int count = hits == null ? 0 : hits.size();
        boolean deep = TrackerScan.hasDeepScan(context, packageInfo);
        @ColorInt int headline = hits == null || count == 0
                ? dim : colorFor(worstRung(hits));

        // The headline: the number first, at a size that cannot be missed, and the word after it.
        LinearLayoutCompat head = new LinearLayoutCompat(context);
        head.setOrientation(LinearLayoutCompat.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        AppCompatTextView number = new AppCompatTextView(context);
        number.setText(hits == null ? "…" : String.valueOf(count));
        number.setTextColor(headline);
        number.setTypeface(Typeface.DEFAULT_BOLD);
        number.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        number.setIncludeFontPadding(false);
        head.addView(number);
        AppCompatTextView label = new AppCompatTextView(context);
        label.setText(R.string.trackers);
        label.setTextColor(ink);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayoutCompat.LayoutParams labelLp = new LinearLayoutCompat.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.setMarginStart(Math.round(8 * density));
        label.setLayoutParams(labelLp);
        head.addView(label);
        root.addView(head);

        // What those trackers can actually do, which is the whole reason the colours mean anything.
        AppCompatTextView summary = new AppCompatTextView(context);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        summary.setTextColor(dim);
        summary.setText(summaryText(context, hits, deep, scanning, ink, dim));
        LinearLayoutCompat.LayoutParams sumLp = new LinearLayoutCompat.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sumLp.topMargin = Math.round(2 * density);
        sumLp.bottomMargin = Math.round(8 * density);
        summary.setLayoutParams(sumLp);
        root.addView(summary);

        io.github.muntashirakon.widget.FlowLayout flow = new io.github.muntashirakon.widget.FlowLayout(context);
        flow.setChildSpacing(Math.round(6 * density));
        flow.setRowSpacing(Math.round(2 * density));
        if (hits != null) {
            for (TrackerHit hit : hits) {
                flow.addView(pill(context, packageInfo, userId, hit, hits));
            }
        }
        // The deep-scan invitation sits with the pills rather than in a menu: on an app with no
        // component-bearing tracker the row would otherwise be a bare "0" with no way to find out
        // whether that is true.
        TextView scan = RowPills.actionPill(context,
                context.getString(scanning ? R.string.trackers_deep_scanning
                        : deep ? R.string.trackers_deep_rescan : R.string.trackers_deep_scan),
                0, RowPills.withAlpha(ink, 0.55f), false);
        scan.setEnabled(!scanning);
        scan.setOnClickListener(v -> {
            render(container, packageInfo, userId, hits, true);
            ThreadUtils.postOnBackgroundThread(() -> {
                List<TrackerHit> scanned = TrackerScan.deepScan(context, packageInfo);
                ThreadUtils.postOnMainThread(() -> {
                    sMemo.put(key(packageInfo), scanned);
                    render(container, packageInfo, userId, scanned, false);
                });
            });
        });
        flow.addView(scan);
        root.addView(flow);
        container.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    @NonNull
    private static TextView pill(@NonNull Context context, @NonNull PackageInfo packageInfo, int userId,
                                 @NonNull TrackerHit hit, @NonNull List<TrackerHit> all) {
        // Fork (白い熊, +169): the autonomous rung is FILLED rather than outlined. Outlined it was
        // red text on black at pill size — the same unreadable pair as the summary above, and on the
        // rung that matters most. Filled, black on #FF0028, it is both legible and the loudest thing
        // on the card, which is 白い熊's standing rule for trackers. The quieter two rungs keep the
        // outline: they are information, not an alarm.
        TextView pill;
        if (hit.rung() == TrackerHit.RUNG_AUTONOMOUS) {
            // The same pair as the summary pill above it: blood red under near-white. One severity,
            // one treatment, and the ink is named rather than left to the pill's default black.
            //
            // LANDMINE (白い熊, +171) — a filled pill must NOT be faded as a whole. Second degree was
            // first expressed as setAlpha(0.55f) on the view, which fades the fill towards the black
            // behind it and the ink along with it: 白い熊 read the result as "not filled any more and
            // the text grey", which is exactly what it was. The fill carries the severity and has to
            // stay at full strength; only the ink is allowed to soften, and only as far as it can
            // still be read against it.
            int ink = hit.secondDegree
                    ? RowPills.withAlpha(TrackerHit.COLOR_AUTONOMOUS_INK, 0.75f)
                    : TrackerHit.COLOR_AUTONOMOUS_INK;
            pill = RowPills.filledPill(context, hit.name, TrackerHit.COLOR_AUTONOMOUS_FILL, ink);
        } else {
            // The quieter two rungs keep the outline, and there fading the colour is safe: it moves
            // stroke and text together and neither was near the edge of legibility to begin with.
            int colour = colorFor(hit.rung());
            if (hit.secondDegree) {
                colour = RowPills.withAlpha(colour, 0.55f);
            }
            pill = RowPills.actionPill(context, hit.name, 0, colour, false);
        }
        pill.setOnClickListener(v -> TrackerDialog.show(context, packageInfo, userId, hit));
        return pill;
    }

    @ColorInt
    public static int colorFor(int rung) {
        switch (rung) {
            case TrackerHit.RUNG_AUTONOMOUS:
                return TrackerHit.COLOR_AUTONOMOUS;
            case TrackerHit.RUNG_WITH_APP:
                return ForkThemeUtils.getTextColor();
            default:
                return TrackerHit.COLOR_PASSIVE;
        }
    }

    private static int worstRung(@NonNull List<TrackerHit> hits) {
        int worst = TrackerHit.RUNG_PASSIVE;
        for (TrackerHit hit : hits) {
            worst = Math.max(worst, hit.rung());
        }
        return worst;
    }

    /**
     * One line: how many of each rung, then which scan the number came from. The scan depth is
     * stated every time — a manifest count is a floor, and a number that does not say so is a
     * number that will be believed.
     */
    @NonNull
    private static CharSequence summaryText(@NonNull Context context, @Nullable List<TrackerHit> hits,
                                            boolean deep, boolean scanning, @ColorInt int ink,
                                            @ColorInt int dim) {
        if (hits == null) {
            return context.getString(R.string.loading);
        }
        List<CharSequence> parts = new ArrayList<>();
        int autonomous = 0, withApp = 0, passive = 0;
        for (TrackerHit hit : hits) {
            switch (hit.rung()) {
                case TrackerHit.RUNG_AUTONOMOUS: ++autonomous; break;
                case TrackerHit.RUNG_WITH_APP: ++withApp; break;
                default: ++passive; break;
            }
        }
        if (autonomous > 0) {
            // Fork (白い熊, +169): a filled pill, not red words. At 12sp on black the page's
            // #FF0028 is unreadable — the finding that made 盗み見一覧's headline a pill in +149,
            // and the same pair is used here so one severity is read one way everywhere.
            float density = context.getResources().getDisplayMetrics().density;
            parts.add(PillSpan.pill(context.getString(R.string.tracker_rung_autonomous_n, autonomous),
                    TrackerHit.COLOR_AUTONOMOUS_FILL, TrackerHit.COLOR_AUTONOMOUS_INK,
                    12f * density, density));
        }
        if (withApp > 0) {
            parts.add(colored(context.getString(R.string.tracker_rung_with_app_n, withApp), ink));
        }
        if (passive > 0) {
            parts.add(colored(context.getString(R.string.tracker_rung_passive_n, passive),
                    TrackerHit.COLOR_PASSIVE));
        }
        parts.add(colored(context.getString(scanning ? R.string.trackers_deep_scanning
                : deep ? R.string.trackers_scan_deep : R.string.trackers_scan_manifest), dim));
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (CharSequence part : parts) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(part);
        }
        return sb;
    }

    @NonNull
    private static CharSequence colored(@NonNull CharSequence text, @ColorInt int colour) {
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        sb.setSpan(new ForegroundColorSpan(colour), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }
}
