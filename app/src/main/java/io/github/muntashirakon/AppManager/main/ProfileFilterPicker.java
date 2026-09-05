// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.utils.ForkDialog;

/**
 * Fork (白い熊, +120): the profile filter — in this profile, not in this profile, or neither.
 *
 * <p>Extracted from {@link MainListOptions} so the shelf's pill editor can offer the <b>same</b>
 * control rather than a lesser one. A saved view that could carry filter flags but not profile
 * membership would be a view that cannot express 必要, which is the profile the whole feature
 * exists for.
 *
 * <p>The tri-state and its look are unchanged, because they are already right: a tap toggles
 * between neutral and <i>in</i>, a long press reaches <i>not in</i>, and the two pills set their
 * own polarity directly. The painting is done in code because the yellow-on-black look needs
 * filled rows, thick line-borders and a double-bordered pill that Material's checked states
 * cannot produce.
 */
public final class ProfileFilterPicker {
    /** Told the new filter when OK is pressed. */
    public interface OnCommit {
        void onCommit(@NonNull Set<String> include, @NonNull Set<String> exclude);
    }

    private static final int ROW_NEUTRAL = 0;
    private static final int ROW_INCLUDE = 1;
    private static final int ROW_EXCLUDE = 2;

    private ProfileFilterPicker() {
    }

    /**
     * @param names   every profile that exists, in display order
     * @param include profiles currently required
     * @param exclude profiles currently excluded
     */
    public static void show(@NonNull Context context, @NonNull List<String> names,
                            @NonNull Set<String> include, @NonNull Set<String> exclude,
                            @NonNull OnCommit onCommit) {
        if (names.isEmpty()) {
            // Nothing to choose between: no profiles are defined, or they have not loaded yet.
            return;
        }
        // Working copies, committed on OK — so Cancel really cancels.
        LinkedHashSet<String> workingInclude = new LinkedHashSet<>(include);
        LinkedHashSet<String> workingExclude = new LinkedHashSet<>(exclude);
        int yellow = ContextCompat.getColor(context, R.color.theme_bright_yellow);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (8 * context.getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);
        LayoutInflater inflater = LayoutInflater.from(context);
        for (String name : names) {
            View row = inflater.inflate(R.layout.dialog_profile_filter_picker_row, content, false);
            final TextView nameView = row.findViewById(R.id.profile_name);
            final TextView plus = row.findViewById(R.id.btn_include);
            final TextView minus = row.findViewById(R.id.btn_exclude);
            nameView.setText(name);
            final String profileName = name;
            final int[] state = {workingInclude.contains(name) ? ROW_INCLUDE
                    : (workingExclude.contains(name) ? ROW_EXCLUDE : ROW_NEUTRAL)};
            Runnable render = () -> applyRowState(context, row, nameView, plus, minus, state[0], yellow);
            render.run();
            Runnable sync = () -> {
                workingInclude.remove(profileName);
                workingExclude.remove(profileName);
                if (state[0] == ROW_INCLUDE) workingInclude.add(profileName);
                else if (state[0] == ROW_EXCLUDE) workingExclude.add(profileName);
            };
            row.setOnClickListener(v -> {
                state[0] = (state[0] == ROW_NEUTRAL) ? ROW_INCLUDE : ROW_NEUTRAL;
                sync.run();
                render.run();
            });
            row.setOnLongClickListener(v -> {
                state[0] = ROW_EXCLUDE;
                sync.run();
                render.run();
                return true;
            });
            plus.setOnClickListener(v -> {
                state[0] = (state[0] == ROW_INCLUDE) ? ROW_NEUTRAL : ROW_INCLUDE;
                sync.run();
                render.run();
            });
            minus.setOnClickListener(v -> {
                state[0] = (state[0] == ROW_EXCLUDE) ? ROW_NEUTRAL : ROW_EXCLUDE;
                sync.run();
                render.run();
            });
            content.addView(row);
        }
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        // ForkDialog, not a bare MaterialAlertDialogBuilder: a plain builder rebuilds the window
        // background stroke-less and the dialog renders borderless on a yellow-on-black screen.
        ForkDialog.present(ForkDialog.builder(context)
                .setTitle(R.string.profile_filter_title)
                .setView(scroll)
                .setPositiveButton(R.string.ok, (d, w) -> onCommit.onCommit(workingInclude, workingExclude))
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.profile_filter_clear, (d, w) ->
                        onCommit.onCommit(Collections.emptySet(), Collections.emptySet())));
    }

    /** A one-line description of the filter, for whatever opens the picker. */
    @NonNull
    public static CharSequence summary(@NonNull Context context, @NonNull Set<String> include,
                                       @NonNull Set<String> exclude) {
        if (include.isEmpty() && exclude.isEmpty()) {
            return context.getString(R.string.profile_filter_button_none);
        }
        List<String> parts = new ArrayList<>();
        if (!include.isEmpty()) {
            parts.add(TextUtils.join(", ", include));
        }
        for (String name : exclude) {
            parts.add("−" + name);
        }
        return context.getString(R.string.profile_filter_button_summary, TextUtils.join(" ", parts));
    }

    /**
     * Paint one row for one of the three states.
     *
     * <p>neutral: transparent row, yellow name, both pills yellow-outlined. include: row filled
     * yellow, name black, "+" filled with a black border, "−" black-filled and ringed so it stays
     * visible on the yellow. exclude: transparent row inside a thick yellow border, "−" filled.
     */
    private static void applyRowState(@NonNull Context ctx, @NonNull View row,
                                      @NonNull TextView name, @NonNull TextView plus,
                                      @NonNull TextView minus, int state, int yellow) {
        final int black = Color.BLACK;
        final float d = ctx.getResources().getDisplayMetrics().density;
        final int rowRadius = (int) (12 * d);
        final int pillRadius = (int) (100 * d);
        final int rowStroke = (int) (3 * d);
        final int pillStroke = Math.max(1, (int) (1.5f * d));
        final int plusBlackStroke = (int) (2 * d);
        final int ringInset = (int) (3 * d);

        if (state == ROW_INCLUDE) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(yellow);
            bg.setCornerRadius(rowRadius);
            row.setBackground(bg);
            name.setTextColor(black);
        } else if (state == ROW_EXCLUDE) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.TRANSPARENT);
            bg.setStroke(rowStroke, yellow);
            bg.setCornerRadius(rowRadius);
            row.setBackground(bg);
            name.setTextColor(yellow);
        } else {
            row.setBackground(null);
            name.setTextColor(yellow);
        }

        if (state == ROW_INCLUDE) {
            GradientDrawable p = new GradientDrawable();
            p.setColor(yellow);
            p.setStroke(plusBlackStroke, black);
            p.setCornerRadius(pillRadius);
            plus.setBackground(p);
            plus.setTextColor(black);
        } else {
            plus.setBackground(neutralPill(yellow, pillStroke, pillRadius));
            plus.setTextColor(yellow);
        }

        if (state == ROW_EXCLUDE) {
            GradientDrawable m = new GradientDrawable();
            m.setColor(yellow);
            m.setCornerRadius(pillRadius);
            minus.setBackground(m);
            minus.setTextColor(black);
        } else if (state == ROW_INCLUDE) {
            GradientDrawable outer = new GradientDrawable();
            outer.setColor(black);
            outer.setCornerRadius(pillRadius);
            GradientDrawable inner = new GradientDrawable();
            inner.setColor(black);
            inner.setStroke(pillStroke, yellow);
            inner.setCornerRadius(pillRadius);
            LayerDrawable layer = new LayerDrawable(new Drawable[]{outer, inner});
            layer.setLayerInset(1, ringInset, ringInset, ringInset, ringInset);
            minus.setBackground(layer);
            minus.setTextColor(yellow);
        } else {
            minus.setBackground(neutralPill(yellow, pillStroke, pillRadius));
            minus.setTextColor(yellow);
        }
    }

    @NonNull
    private static GradientDrawable neutralPill(int yellow, int stroke, int radius) {
        GradientDrawable p = new GradientDrawable();
        p.setColor(Color.TRANSPARENT);
        p.setStroke(stroke, yellow);
        p.setCornerRadius(radius);
        return p;
    }
}
