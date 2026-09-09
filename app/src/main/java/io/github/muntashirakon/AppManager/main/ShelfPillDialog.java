// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.appcompat.widget.LinearLayoutCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import java.util.LinkedHashSet;
import java.util.Set;
import io.github.muntashirakon.AppManager.profiles.ProfileManager;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.utils.ForkDialog;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;
import io.github.muntashirakon.widget.FlowLayout;
import io.github.muntashirakon.AppManager.main.lens.SisterAppsLens;

/**
 * Fork (白い熊, +119): the dialog that makes a shelf pill.
 *
 * <p>It replaced a plain list of ready-made views, which was wrong twice over. It could not
 * express anything the list did not already name — and crafting a specific filter is exactly what
 * the funnel icon lets you do, so a shelf that could only save someone else's presets was the
 * poorer of the two. And it was bare text in a sea of black: a menu, not a control.
 *
 * <p>So the dialog is the filter itself. Every filter flag is a <b>toggle pill</b>, every sort a
 * pill, and the whole thing <b>opens preloaded with what the list is showing right now</b> — which
 * makes "save this view" the default action rather than a separate entry, and any change from
 * there a crafted filter. The profile filter and the search query ride along silently, because
 * they are part of the view whether or not this dialog draws them.
 */
public final class ShelfPillDialog {
    /** Told when a pill has been made, so the shelf can redraw. */
    public interface Listener {
        void onPillCreated(@NonNull ShelfPrefs.Pill pill);
    }

    private ShelfPillDialog() {
    }

    public static void show(@NonNull AppCompatActivity activity, @NonNull ShelfPrefs.ViewState current,
                            @NonNull Listener listener) {
        Context context = activity;
        int ink = ForkThemeUtils.getTextColor();
        int dim = RowPills.withAlpha(ink, 0.6f);

        LinearLayoutCompat body = new LinearLayoutCompat(context);
        body.setOrientation(LinearLayoutCompat.VERTICAL);
        int pad = Math.round(ForkThemeUtils.dpToPx(context, 16f));
        body.setPadding(pad, Math.round(ForkThemeUtils.dpToPx(context, 4f)), pad, 0);

        // ── Name ────────────────────────────────────────────────────────────
        AppCompatEditText name = new AppCompatEditText(context);
        name.setSingleLine(true);
        name.setTextColor(ink);
        name.setHintTextColor(dim);
        name.setHint(R.string.shelf_name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        RowPills.styleActionPill(name, ink, false);
        name.setTypeface(Typeface.DEFAULT);
        name.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        name.setLayoutParams(new LinearLayoutCompat.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(name);

        // The name follows the ticked filters until it is typed in — then it is yours and this
        // stops touching it, because a field that keeps rewriting itself cannot be edited.
        final boolean[] nameIsMine = {false};
        name.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (name.hasFocus()) {
                    nameIsMine[0] = true;
                }
            }
        });

        // ── Filters ─────────────────────────────────────────────────────────
        body.addView(sectionLabel(context, R.string.shelf_section_filters, dim));
        final int[] flags = {current.filterFlags};
        FlowLayout filterFlow = flow(context);
        LinkedHashMap<Integer, Integer> filterLabels = new MainListOptions().getFilterFlagLocaleMap();
        final Runnable[] renameFromFilters = new Runnable[1];
        if (filterLabels != null) {
            for (Map.Entry<Integer, Integer> entry : filterLabels.entrySet()) {
                int flag = entry.getKey();
                TextView pill = RowPills.actionPill(context, context.getString(entry.getValue()), 0,
                        ink, (flags[0] & flag) != 0);
                pill.setOnClickListener(v -> {
                    flags[0] ^= flag;
                    RowPills.styleActionPill(pill, ink, (flags[0] & flag) != 0);
                    if (renameFromFilters[0] != null) {
                        renameFromFilters[0].run();
                    }
                });
                filterFlow.addView(pill);
            }
        }
        body.addView(filterFlow);

        // ── Sort ────────────────────────────────────────────────────────────
        final List<TextView> sortPillsRef = new ArrayList<>();
        body.addView(sectionLabel(context, R.string.shelf_section_sort, dim));
        // Fork (白い熊, +174): a pill either carries a sort or deliberately does not.
        //
        // 白い熊 wants both kinds on the shelf: some views are a whole reading — "biggest apps,
        // by size" — and mean nothing without their order; others are just a narrowing you drop
        // over whatever order you are already in, and having those re-impose a sort saved months
        // ago is what made the sort look like it kept resetting itself.
        //
        // The toggle leads the section rather than hiding at the end of it, because it decides
        // whether anything below it is even read. With it off the orders stay visible but faded
        // and inert: greying them says "not applicable" where removing them would say "there is
        // no sort", which is a different and wrong claim.
        final boolean[] savesSort = {current.savesSort};
        final int[] sortBy = {current.sortBy};
        FlowLayout sortFlow = flow(context);
        TextView saveSortPill = RowPills.actionPill(context, context.getString(R.string.shelf_save_sort),
                0, ink, savesSort[0]);
        saveSortPill.setOnClickListener(v -> {
            savesSort[0] = !savesSort[0];
            RowPills.styleActionPill(saveSortPill, ink, savesSort[0]);
            saveSortPill.setText(context.getString(savesSort[0]
                    ? R.string.shelf_save_sort : R.string.shelf_save_sort_off));
            sortFlow.setAlpha(savesSort[0] ? 1f : 0.4f);
            for (TextView p : sortPillsRef) {
                p.setEnabled(savesSort[0]);
            }
        });
        if (!savesSort[0]) {
            saveSortPill.setText(context.getString(R.string.shelf_save_sort_off));
        }
        FlowLayout saveSortFlow = flow(context);
        saveSortFlow.addView(saveSortPill);
        body.addView(saveSortFlow);
        LinkedHashMap<Integer, Integer> sortLabels = new MainListOptions().getSortIdLocaleMap();
        List<TextView> sortPills = sortPillsRef;
        List<Integer> sortIds = new ArrayList<>();
        if (sortLabels != null) {
            for (Map.Entry<Integer, Integer> entry : sortLabels.entrySet()) {
                int id = entry.getKey();
                TextView pill = RowPills.actionPill(context, context.getString(entry.getValue()), 0,
                        ink, id == sortBy[0]);
                pill.setOnClickListener(v -> {
                    sortBy[0] = id;
                    // Single choice, so every pill is redrawn: the filled one IS the answer.
                    for (int i = 0; i < sortPills.size(); ++i) {
                        RowPills.styleActionPill(sortPills.get(i), ink, sortIds.get(i) == id);
                    }
                });
                sortPills.add(pill);
                sortIds.add(id);
                sortFlow.addView(pill);
            }
        }
        sortFlow.setAlpha(savesSort[0] ? 1f : 0.4f);
        for (TextView p : sortPills) {
            p.setEnabled(savesSort[0]);
        }
        body.addView(sortFlow);

        // ── Profiles ────────────────────────────────────────────────────────
        // The same tri-state picker the funnel opens, not a lesser copy: a saved view that could
        // not express "in 必要" or "not in 必要" would be missing the profile the whole feature
        // exists for.
        body.addView(sectionLabel(context, R.string.shelf_section_profiles, dim));
        final Set<String> include = new LinkedHashSet<>(current.profilesInclude);
        final Set<String> exclude = new LinkedHashSet<>(current.profilesExclude);
        TextView profilePill = RowPills.actionPill(context,
                ProfileFilterPicker.summary(context, include, exclude), 0, ink,
                !include.isEmpty() || !exclude.isEmpty());
        body.addView(profilePill);
        final List<String> profileNames = new ArrayList<>();
        ThreadUtils.postOnBackgroundThread(() -> {
            List<String> names = new ArrayList<>(ProfileManager.getProfileNames());
            java.util.Collections.sort(names);
            ThreadUtils.postOnMainThread(() -> {
                profileNames.clear();
                profileNames.addAll(names);
            });
        });
        profilePill.setOnClickListener(v -> ProfileFilterPicker.show(context, profileNames,
                include, exclude, (newInclude, newExclude) -> {
                    include.clear();
                    include.addAll(newInclude);
                    exclude.clear();
                    exclude.addAll(newExclude);
                    profilePill.setText(ProfileFilterPicker.summary(context, include, exclude));
                    RowPills.styleActionPill(profilePill, ink,
                            !include.isEmpty() || !exclude.isEmpty());
                }));

        // The search query rides along without a control of its own — it belongs to the view,
        // and saying so beats restoring it silently.
        if (!TextUtils.isEmpty(current.query)) {
            AppCompatTextView note = new AppCompatTextView(context);
            note.setText(context.getString(R.string.shelf_also_carries, "\"" + current.query + "\""));
            note.setTextColor(dim);
            note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            note.setPadding(0, Math.round(ForkThemeUtils.dpToPx(context, 8f)), 0, 0);
            body.addView(note);
        }

        // ── Screens ─────────────────────────────────────────────────────────
        // Fork (白い熊, +167): lenses and screens are offered apart. A lens re-dresses the list you
        // are already on and hands it back with one more tap; a screen takes you somewhere else.
        body.addView(sectionLabel(context, R.string.shelf_section_lenses, dim));
        FlowLayout lensFlow = flow(context);
        body.addView(lensFlow);
        body.addView(sectionLabel(context, R.string.shelf_section_screens, dim));
        FlowLayout screenFlow = flow(context);
        body.addView(screenFlow);

        ScrollView scroller = new ScrollView(context);
        scroller.addView(body);

        androidx.appcompat.app.AlertDialog dialog = ForkDialog.present(ForkDialog.builder(context)
                .setTitle(R.string.shelf_add)
                .setView(scroller)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, which) -> {
                    ShelfPrefs.ViewState state = new ShelfPrefs.ViewState(flags[0], sortBy[0],
                            current.reverseSort, include, exclude,
                            current.query, current.queryType, savesSort[0]);
                    listener.onPillCreated(ShelfPrefs.newView(
                            nameOf(context, name, flags[0], filterLabels), state));
                }));

        // A screen pill is not a view, so it is made and saved there and then rather than by the
        // Save button, which would have to mean two different things depending on what was last
        // touched.
        for (String targetId : ShelfPrefs.allTargetIds()) {
            // Fork (白い熊, +175): 仲間 is NOT offered as a lens any more.
            //
            // It drew nothing — filterOnly() — so it was a second Sister-apps filter under
            // another name, sitting in the same dialog as the real one and selecting a slightly
            // different set. 白い熊: "are they a duplicate in fact? In that case leave as filter
            // only." Its (wider) membership rule moved into SisterAppOption, so the filter pill
            // above is strictly the better of the two.
            //
            // It stays REGISTERED in MainLenses on purpose: the payload is the shelf's wire
            // format, so a pill made before this build keeps working instead of becoming a pill
            // that does nothing. Only the way to make NEW ones is withdrawn.
            if (SisterAppsLens.ID.equals(targetId)) {
                continue;
            }
            TextView pill = RowPills.actionPill(context,
                    context.getString(ShelfPrefs.screenTitle(targetId)),
                    ShelfPrefs.screenIcon(targetId), ink, false);
            pill.setOnClickListener(v -> {
                listener.onPillCreated(ShelfPrefs.newScreen(
                        context.getString(ShelfPrefs.screenTitle(targetId)), targetId));
                dialog.dismiss();
            });
            (ShelfPrefs.lensIds().contains(targetId) ? lensFlow : screenFlow).addView(pill);
        }

        renameFromFilters[0] = () -> {
            if (!nameIsMine[0]) {
                name.setText(describe(context, flags[0], filterLabels));
            }
        };
        renameFromFilters[0].run();
    }

    /** What the pill is called: what was typed, or what the ticked filters describe. */
    @NonNull
    private static String nameOf(@NonNull Context context, @NonNull AppCompatEditText field,
                                 int flags, @Nullable Map<Integer, Integer> labels) {
        CharSequence typed = field.getText();
        if (!TextUtils.isEmpty(typed)) {
            return typed.toString().trim();
        }
        String described = describe(context, flags, labels);
        return described.isEmpty() ? context.getString(R.string.shelf_new_view) : described;
    }

    /**
     * The ticked filters, joined. Two is a readable name; beyond that it becomes a sentence, so
     * the rest are counted instead.
     */
    @NonNull
    private static String describe(@NonNull Context context, int flags,
                                   @Nullable Map<Integer, Integer> labels) {
        if (labels == null || flags == 0) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : labels.entrySet()) {
            if ((flags & entry.getKey()) != 0) {
                names.add(context.getString(entry.getValue()));
            }
        }
        if (names.isEmpty()) {
            return "";
        }
        if (names.size() <= 2) {
            return TextUtils.join(" · ", names);
        }
        return names.get(0) + " +" + (names.size() - 1);
    }

    @NonNull
    private static View sectionLabel(@NonNull Context context, @StringRes int textRes, int color) {
        AppCompatTextView label = new AppCompatTextView(context);
        label.setText(textRes);
        label.setTextColor(color);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        label.setAllCaps(true);
        label.setPadding(0, Math.round(ForkThemeUtils.dpToPx(context, 14f)),
                0, Math.round(ForkThemeUtils.dpToPx(context, 6f)));
        return label;
    }

    @NonNull
    private static FlowLayout flow(@NonNull Context context) {
        FlowLayout flow = new FlowLayout(context);
        flow.setChildSpacing(Math.round(ForkThemeUtils.dpToPx(context, 8f)));
        flow.setRowSpacing(Math.round(ForkThemeUtils.dpToPx(context, 6f)));
        return flow;
    }
}
