// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.appdata;

import android.annotation.UserIdInt;
import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.graphics.ColorUtils;

import java.util.ArrayList;
import java.util.List;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.main.RowPills;
import io.github.muntashirakon.AppManager.utils.ContextUtils;
import io.github.muntashirakon.AppManager.utils.ForkDialog;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;

/**
 * Fork: the per-app category picker for App-supplied data.
 * <p>
 * Lives here rather than in one dialog because several surfaces offer it — the bottom sheet's
 * options list, the single-app dialog, the 仲間 screen — and a second copy would drift.
 * <p>
 * <b>Two different questions, and they were the same button until +133 (白い熊).</b> Ticking a
 * category can mean <i>this is what I always want from this app</i> or <i>this is what I want in
 * the backup I am about to take</i>, and the picker only ever answered the first: every close
 * wrote the preference, so narrowing one backup silently narrowed every future one. Now the
 * footer says which you meant — <b>Save</b> keeps it for the future (and uses it now, since
 * saving a preference and then not applying it to the backup in front of you would be absurd),
 * <b>OK</b> uses it for this backup and leaves the preference exactly as it was, and
 * <b>Use app's defaults</b> forgets the stored choice altogether. Back cancels.
 */
public class AppDataCategoryPicker {
    /**
     * What the picker decided for the backup being set up.
     *
     * @param categories the ids to export this once, or {@code null} to fall back to whatever is
     *                   stored for the app (which is what Save and Use-app's-defaults leave
     *                   behind — after either, the stored state already says the right thing)
     */
    public interface OnPicked {
        void onPicked(@Nullable List<String> categories);
    }

    private AppDataCategoryPicker() {
    }

    /** Whether this app can be asked at all — a manifest read, so it never starts anything. */
    public static boolean isAvailable(@NonNull Context context, @Nullable String packageName) {
        return packageName != null && AppDataContract.isSupported(context, packageName);
    }

    /**
     * Ask the app what it can export, then let 白い熊 tick a subset.
     * <p>
     * The listing is a broadcast round trip and may have to thaw the app, so this happens only
     * when the picker is opened — never while drawing a list of apps.
     *
     * @param onPicked run on the main thread once the picker closes, however it closed. Pass
     *                 {@code null} where there is no backup being set up (the 仲間 screen), and
     *                 the run-scoped <b>OK</b> is not offered — it would have nothing to act on.
     */
    @MainThread
    public static void show(@NonNull Context context, @NonNull String packageName,
                            @UserIdInt int userId, @Nullable OnPicked onPicked) {
        UIUtils.displayShortToast(R.string.appdata_categories_asking);
        ThreadUtils.postOnBackgroundThread(() -> {
            List<AppDataCategory> categories = new AppDataTransfer(ContextUtils.getContext())
                    .listCategories(packageName, userId);
            ThreadUtils.postOnMainThread(() -> {
                if (categories == null || categories.isEmpty()) {
                    UIUtils.displayLongToast(R.string.appdata_categories_unavailable);
                    if (onPicked != null) {
                        onPicked.onPicked(null);
                    }
                    return;
                }
                showDialog(context, packageName, categories, onPicked);
            });
        });
    }

    @MainThread
    private static void showDialog(@NonNull Context context, @NonNull String packageName,
                                   @NonNull List<AppDataCategory> categories,
                                   @Nullable OnPicked onPicked) {
        // The colour of the App-supplied data part, so the categories read as belonging to it
        // wherever they are opened from.
        final int accent = 0xFF4CD07A;
        // Fork (白い熊, +135): the controls are NOT in that colour. Green here means "an item of
        // app-supplied data, and this one is chosen" — it is the tick, carrying state. A button
        // carries no state, and painting Save and Cancel the same green made them read as two
        // more categories sitting outside the list. Controls are the theme's yellow, which is
        // what every other button in the fork is.
        final int ink = ForkThemeUtils.getTextColor();
        float d = context.getResources().getDisplayMetrics().density;

        List<String> offered = new ArrayList<>(categories.size());
        for (AppDataCategory category : categories) {
            offered.add(category.id);
        }
        AppDataSelection.Stored stored = AppDataSelection.get(context, packageName);
        List<String> ticked = new ArrayList<>(stored != null
                ? AppDataSelection.reconcile(stored, categories)
                : AppDataCategory.defaultIds(categories));

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * d);
        body.setPadding(pad, Math.round(4 * d), pad, 0);
        // Rebuilt rather than tracked: select-all and select-none change every row at once, and a
        // row that draws its own state from a boolean array is one more thing to keep in step.
        final Runnable[] rebuild = new Runnable[1];
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        rebuild[0] = () -> {
            rows.removeAllViews();
            for (AppDataCategory category : categories) {
                rows.addView(categoryRow(context, category, accent, ticked.contains(category.id), on -> {
                    if (on) {
                        if (!ticked.contains(category.id)) {
                            ticked.add(category.id);
                        }
                    } else {
                        ticked.remove(category.id);
                    }
                }));
            }
        };

        // All / none on top, where a long list needs them — the alternative is dragging a finger
        // down twenty categories to clear them.
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, Math.round(8 * d));
        TextView selectAll = pill(context, context.getString(R.string.select_all), ink);
        TextView selectNone = pill(context, context.getString(R.string.clear_all), ink);
        ((LinearLayout.LayoutParams) selectNone.getLayoutParams()).setMarginStart(Math.round(8 * d));
        selectAll.setOnClickListener(v -> {
            ticked.clear();
            for (AppDataCategory category : categories) {
                ticked.add(category.id);
            }
            rebuild[0].run();
        });
        selectNone.setOnClickListener(v -> {
            ticked.clear();
            rebuild[0].run();
        });
        header.addView(selectAll);
        header.addView(selectNone);
        body.addView(header);
        body.addView(rows);
        rebuild[0].run();

        // Capped rather than weighted: a weighted child of a wrap_content dialog view can measure
        // to nothing, and a footer that has been pushed off the bottom of the dialog is worse
        // than a list that scrolls.
        ScrollView scroller = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int max = Math.round(getResources().getDisplayMetrics().heightPixels * 0.55f);
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        scroller.addView(body);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The footer, in the order 白い熊 asked for: the two preference actions together on the
        // left, the one that touches nothing pushed to the far right.
        LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(pad, Math.round(10 * d), pad, Math.round(4 * d));
        TextView useDefaults = pill(context, context.getString(R.string.appdata_categories_use_defaults), ink);
        TextView save = pill(context, context.getString(R.string.save), ink);
        ((LinearLayout.LayoutParams) save.getLayoutParams()).setMarginStart(Math.round(8 * d));
        View spacer = new View(context);
        TextView cancel = pill(context, context.getString(R.string.cancel), ink);
        TextView ok = pill(context, context.getString(android.R.string.ok), ink);
        ((LinearLayout.LayoutParams) ok.getLayoutParams()).setMarginStart(Math.round(8 * d));
        footer.addView(useDefaults);
        footer.addView(save);
        footer.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        footer.addView(cancel);
        footer.addView(ok);
        if (onPicked == null) {
            // Nothing is being backed up, so "just for this backup" would be a button that does
            // nothing at all.
            ok.setVisibility(View.GONE);
        }
        root.addView(footer);

        AlertDialog dialog = ForkDialog.builder(context)
                .setTitle(R.string.appdata_categories)
                .setView(root)
                .create();
        useDefaults.setOnClickListener(v -> {
            // Forget the choice, so the app exports what IT recommends — deliberately not the
            // same as ticking everything.
            AppDataSelection.clear(context, packageName);
            dialog.dismiss();
            if (onPicked != null) {
                onPicked.onPicked(null);
            }
        });
        save.setOnClickListener(v -> {
            // Both sets are stored: what was ticked, and what was on offer at the time. Without
            // the second, a category the app adds later is indistinguishable from one
            // deliberately unticked and would stay out of every backup for ever.
            AppDataSelection.set(context, packageName, ticked, offered);
            dialog.dismiss();
            if (onPicked != null) {
                onPicked.onPicked(null);
            }
        });
        cancel.setOnClickListener(v -> {
            dialog.dismiss();
            if (onPicked != null) {
                onPicked.onPicked(null);
            }
        });
        ok.setOnClickListener(v -> {
            dialog.dismiss();
            if (onPicked != null) {
                onPicked.onPicked(new ArrayList<>(ticked));
            }
        });
        dialog.show();
        ForkDialog.bordered(dialog);
    }

    /** One category: a tickable box in the App-supplied colour, children indented under parents. */
    @NonNull
    private static View categoryRow(@NonNull Context context, @NonNull AppDataCategory category,
                                    int accent, boolean checked,
                                    @NonNull androidx.core.util.Consumer<Boolean> onToggle) {
        float d = context.getResources().getDisplayMetrics().density;
        AppCompatTextView row = new AppCompatTextView(context);
        row.setText(category.label);
        row.setTypeface(Typeface.DEFAULT_BOLD);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        int padH = Math.round(12 * d);
        int padV = Math.round(9 * d);
        row.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(6 * d);
        // The app sends parents first, so an indent is all a child needs to read as one.
        lp.setMarginStart(category.isChild() ? Math.round(24 * d) : 0);
        row.setLayoutParams(lp);
        final boolean[] state = {checked};
        Runnable render = () -> {
            io.github.muntashirakon.AppManager.backup.dialog.BackupPartRows.applyBoxStyle(row, accent, state[0]);
            row.setTextColor(state[0] ? accent : ColorUtils.setAlphaComponent(accent, 0xAA));
        };
        render.run();
        row.setOnClickListener(v -> {
            state[0] = !state[0];
            render.run();
            onToggle.accept(state[0]);
        });
        return row;
    }

    @NonNull
    private static TextView pill(@NonNull Context context, @NonNull CharSequence text, int ink) {
        AppCompatTextView pill = new AppCompatTextView(context);
        pill.setText(text);
        pill.setSingleLine(true);
        pill.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        RowPills.styleActionPill(pill, ink, false);
        return pill;
    }
}
