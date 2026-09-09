// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.backup.dialog;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import io.github.muntashirakon.AppManager.appdata.AppDataCategory;
import io.github.muntashirakon.AppManager.appdata.AppDataCategoryPicker;
import io.github.muntashirakon.AppManager.appdata.AppDataContract;
import io.github.muntashirakon.AppManager.appdata.AppDataSelection;
import io.github.muntashirakon.AppManager.appdata.AppDataTransfer;
import io.github.muntashirakon.AppManager.backup.BackupFlags;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.graphics.ColorUtils;
import androidx.core.util.Consumer;

import java.util.List;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.utils.ContextUtils;
import io.github.muntashirakon.AppManager.utils.ForkDialog;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;

/**
 * Fork (白い熊, +121): how a backup's parts are drawn wherever you choose between them.
 *
 * <p>The list this replaces was a column of identical yellow checkbox labels with a paragraph
 * under each — everything the same colour, so the only way to find the one you wanted was to
 * read. Here each part carries its own colour and glyph from {@link BackupParts}, a ticked part
 * fills with a wash of that colour, and an unticked one is a hairline outline. You can see what
 * is selected from across the room, and the description is still there for the first time you
 * meet it.
 */
public final class BackupPartRows {
    private BackupPartRows() {
    }

    /**
     * A full row: glyph, title in the part's colour, description underneath, the whole box
     * tinted when it is on.
     */
    @NonNull
    public static View partRow(@NonNull Context context, @NonNull BackupParts.Part part,
                               boolean checked, @NonNull Consumer<Boolean> onToggle) {
        return partRow(context, part, checked, false, null, onToggle);
    }

    /**
     * @param locked the part cannot be turned off — it is drawn on and does not respond. Used
     *               where the platform, not the reader, has already decided: restoring into an
     *               app that is not installed has to bring the APK, and a tick that refuses to
     *               move is more honest than one that silently comes back.
     * @param note   a line under the description saying why, shown only when it is there
     */
    @NonNull
    public static View partRow(@NonNull Context context, @NonNull BackupParts.Part part,
                               boolean checked, boolean locked, @Nullable CharSequence note,
                               @NonNull Consumer<Boolean> onToggle) {
        float d = context.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = Math.round(12 * d);
        int padV = Math.round(10 * d);
        row.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(8 * d);
        row.setLayoutParams(lp);

        AppCompatImageView icon = new AppCompatImageView(context);
        icon.setImageResource(part.iconRes);
        icon.setImageTintList(ColorStateList.valueOf(part.color));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                Math.round(24 * d), Math.round(24 * d));
        iconLp.setMarginEnd(Math.round(12 * d));
        icon.setLayoutParams(iconLp);
        row.addView(icon);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        AppCompatTextView title = new AppCompatTextView(context);
        title.setText(part.labelRes);
        title.setTextColor(part.color);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        texts.addView(title);
        AppCompatTextView description = new AppCompatTextView(context);
        description.setText(part.descriptionRes);
        // The description is deliberately below full strength: it is there for the first time you
        // meet a part, not every time you glance at the list.
        description.setTextColor(ColorUtils.setAlphaComponent(part.color, 0xB0));
        description.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        texts.addView(description);
        if (note != null) {
            AppCompatTextView noteView = new AppCompatTextView(context);
            noteView.setText(note);
            noteView.setTextColor(ColorUtils.setAlphaComponent(part.color, 0xCC));
            noteView.setTypeface(Typeface.DEFAULT_BOLD);
            noteView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            texts.addView(noteView);
        }
        row.addView(texts);

        final boolean[] state = {checked};
        Runnable render = () -> applyBoxStyle(row, part.color, state[0]);
        render.run();
        if (locked) {
            row.setAlpha(0.75f);
        } else {
            row.setOnClickListener(v -> {
                state[0] = !state[0];
                render.run();
                onToggle.accept(state[0]);
            });
        }
        return row;
    }

    /** The compact form for a table cell: glyph and label, filled when on. */
    @NonNull
    public static TextView partPill(@NonNull Context context, @NonNull BackupParts.Part part,
                                    boolean checked, @NonNull Consumer<Boolean> onToggle) {
        float d = context.getResources().getDisplayMetrics().density;
        AppCompatTextView pill = new AppCompatTextView(context);
        pill.setText(part.labelRes);
        pill.setSingleLine(true);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        pill.setGravity(Gravity.CENTER);
        int padH = Math.round(10 * d);
        int padV = Math.round(4 * d);
        pill.setPadding(padH, padV, padH, padV);
        final boolean[] state = {checked};
        Runnable render = () -> {
            applyPillStyle(pill, part.color, state[0]);
            setGlyph(pill, part.iconRes, state[0] ? Color.BLACK : part.color);
        };
        render.run();
        pill.setOnClickListener(v -> {
            state[0] = !state[0];
            render.run();
            onToggle.accept(state[0]);
        });
        return pill;
    }

    /**
     * The fork's critical red, used here for the two answers that are not merely a narrowing:
     * a ticked part that would export nothing, and an app that would not answer at all.
     */
    private static final int WARN_MISSING = 0xFFFF0028;

    /** A plain pill in one colour — the app-data categories, which have no palette of their own. */
    @NonNull
    public static TextView plainPill(@NonNull Context context, @NonNull CharSequence text,
                                     int color, boolean checked, @NonNull Consumer<Boolean> onToggle) {
        float d = context.getResources().getDisplayMetrics().density;
        AppCompatTextView pill = new AppCompatTextView(context);
        pill.setText(text);
        pill.setSingleLine(true);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        pill.setGravity(Gravity.CENTER);
        int padH = Math.round(10 * d);
        int padV = Math.round(4 * d);
        pill.setPadding(padH, padV, padH, padV);
        final boolean[] state = {checked};
        Runnable render = () -> applyPillStyle(pill, color, state[0]);
        render.run();
        pill.setOnClickListener(v -> {
            state[0] = !state[0];
            render.run();
            onToggle.accept(state[0]);
        });
        return pill;
    }

    /** Filled with a wash of its own colour when on; a hairline outline when off. */
    public static void applyBoxStyle(@NonNull View view, int color, boolean on) {
        float d = view.getContext().getResources().getDisplayMetrics().density;
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(14 * d);
        shape.setColor(on ? ColorUtils.setAlphaComponent(color, 0x2E) : Color.TRANSPARENT);
        shape.setStroke(Math.max(1, Math.round((on ? 2f : 1.2f) * d)),
                on ? color : ColorUtils.setAlphaComponent(color, 0x66));
        view.setBackground(new RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 0x33)), shape, null));
    }

    private static void applyPillStyle(@NonNull TextView pill, int color, boolean on) {
        float d = pill.getContext().getResources().getDisplayMetrics().density;
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(100 * d);
        shape.setColor(on ? color : Color.TRANSPARENT);
        shape.setStroke(Math.max(1, Math.round(1.4f * d)),
                on ? color : ColorUtils.setAlphaComponent(color, 0x88));
        pill.setBackground(new RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 0x33)), shape, null));
        pill.setTextColor(on ? Color.BLACK : color);
    }

    private static void setGlyph(@NonNull TextView pill, int drawableRes, int tint) {
        Context context = pill.getContext();
        float d = context.getResources().getDisplayMetrics().density;
        Drawable glyph = androidx.core.content.ContextCompat.getDrawable(context, drawableRes);
        if (glyph != null) {
            // mutate(), or the tint leaks into every other user of the shared constant state.
            glyph = glyph.mutate();
            int size = Math.round(14 * d);
            glyph.setBounds(0, 0, size, size);
            glyph.setTintList(ColorStateList.valueOf(tint));
        }
        pill.setCompoundDrawablePadding(Math.round(5 * d));
        pill.setCompoundDrawablesRelative(glyph, null, null, null);
    }

    /**
     * The "what goes into this backup" chooser for a single app, as coloured rows.
     *
     * @param onChosen the flags as they stand when Back up is pressed
     */
    public static void showOptions(@NonNull Context context, int supportedFlags, int flags,
                                   @NonNull Consumer<Integer> onChosen) {
        showOptions(context, supportedFlags, flags, null, 0, onChosen);
    }

    /**
     * @param packageName the app this backup is for, or {@code null} for a chooser that is not
     *                    about one app. When it implements the sister-app contract, the
     *                    App-supplied row grows a way into its categories — choosing to include
     *                    an app's own data and choosing WHICH of it are the same decision, and
     *                    having to leave for a different screen to finish it was the gap.
     */
    public static void showOptions(@NonNull Context context, int supportedFlags, int flags,
                                   @Nullable String packageName, int userId,
                                   @NonNull Consumer<Integer> onChosen) {
        showOptions(context, supportedFlags, flags, packageName, userId,
                (newFlags, categories) -> onChosen.accept(newFlags));
    }

    /** Told the flags and, when the picker was opened and OK pressed, the run-scoped categories. */
    public interface OnOptionsChosen {
        void onChosen(int flags, @Nullable List<String> appDataCategories);
    }

    public static void showOptions(@NonNull Context context, int supportedFlags, int flags,
                                   @Nullable String packageName, int userId,
                                   @NonNull OnOptionsChosen onChosen) {
        final int[] chosen = {flags};
        // Fork (白い熊, +133): what the category picker chose for THIS backup, if anything. Null
        // means it was never opened, or was closed with Save / Use app's defaults — in both of
        // those the stored choice already says the right thing.
        final List<String>[] categories = new List[]{null};
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        float d = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * d);
        body.setPadding(pad, Math.round(4 * d), pad, 0);
        List<BackupParts.Part> parts = BackupParts.allParts(supportedFlags);
        boolean sister = packageName != null && AppDataContract.isSupported(context, packageName);
        // Fork (白い熊): filled in once the app has answered -- see askForCategories below.
        final Runnable[] askForCategories = {null};
        for (BackupParts.Part part : parts) {
            body.addView(partRow(context, part, (chosen[0] & part.flag) != 0, on -> {
                if (on) {
                    chosen[0] |= part.flag;
                } else {
                    chosen[0] &= ~part.flag;
                }
                // Switching the part ON is the moment its categories start to matter. Asking
                // before that would thaw an app to answer a question about a part that is off.
                if (on && part.flag == BackupFlags.BACKUP_APP_DATA && askForCategories[0] != null) {
                    askForCategories[0].run();
                }
            }));
            if (part.flag == BackupFlags.BACKUP_APP_DATA && sister) {
                final String pkg = packageName;
                TextView categoriesPill = plainPill(context,
                        context.getString(R.string.appdata_categories), part.color, false, on -> {
                        });
                // Fork (白い熊, +172): the pill says whether ANYTHING IS BEING LEFT OUT, before
                // it is tapped.
                //
                // It used to read "App data categories" and nothing else, so the one question you
                // have while setting up a backup -- is all of this app's data going in? -- could
                // only be answered by opening the picker and counting ticks, for every app, every
                // time. And the commonest way to lose data here is silent: an app whose own
                // defaults exclude a category, or a narrowing chosen months ago and forgotten.
                //
                // The categories cannot be known without asking the app (a broadcast round trip
                // that may have to thaw it), which is why nothing has said this until now. But
                // that cost is right HERE and wrong in a list: this dialog is one app, opened
                // deliberately to decide exactly this, and tapping the pill would pay the same
                // cost a moment later anyway.
                final List<AppDataCategory>[] offered = new List[]{null};
                final boolean[] asked = {false};
                Runnable renderSummary = () -> {
                    List<AppDataCategory> all = offered[0];
                    if (all == null) {
                        // Not asked yet, or the app would not answer. Both are said out loud:
                        // a pill that falls back to its bare name reads as "nothing to report",
                        // and for an app that refused to list its data that would be a lie.
                        boolean unavailable = asked[0];
                        categoriesPill.setText(unavailable
                                ? context.getString(R.string.appdata_categories_summary_unknown)
                                : context.getString(R.string.appdata_categories));
                        applyPillStyle(categoriesPill, unavailable ? WARN_MISSING : part.color, false);
                        return;
                    }
                    int total = all.size();
                    // The run-scoped pick wins where the picker made one; otherwise the stored
                    // choice, or the app's own defaults. Same resolution the exporter performs.
                    int on = categories[0] != null
                            ? categories[0].size()
                            : AppDataSelection.effective(AppDataSelection.get(context, pkg), all).size();
                    if (on >= total) {
                        categoriesPill.setText(context.getString(
                                R.string.appdata_categories_summary_all, total));
                        applyPillStyle(categoriesPill, part.color, false);
                    } else if (on <= 0) {
                        // The part is ticked and would export nothing -- the one case here that
                        // is plainly wrong rather than merely narrowed.
                        categoriesPill.setText(context.getString(
                                R.string.appdata_categories_summary_none, total));
                        applyPillStyle(categoriesPill, WARN_MISSING, false);
                    } else {
                        categoriesPill.setText(context.getString(
                                R.string.appdata_categories_summary_partial, on, total, total - on));
                        // The theme yellow, the fork's "this departs from the whole -- look at
                        // it", not the red reserved for a failure. A narrowed set is usually
                        // deliberate; it still has to be visible.
                        applyPillStyle(categoriesPill, ForkThemeUtils.getTextColor(), false);
                    }
                };
                askForCategories[0] = () -> {
                    if (asked[0]) {
                        return;
                    }
                    asked[0] = true;
                    categoriesPill.setText(context.getString(
                            R.string.appdata_categories_summary_asking));
                    ThreadUtils.postOnBackgroundThread(() -> {
                        List<AppDataCategory> listed = new AppDataTransfer(ContextUtils.getContext())
                                .listCategories(pkg, userId);
                        ThreadUtils.postOnMainThread(() -> {
                            offered[0] = listed == null || listed.isEmpty() ? null : listed;
                            renderSummary.run();
                        });
                    });
                };
                categoriesPill.setOnClickListener(v ->
                        AppDataCategoryPicker.show(context, pkg, userId,
                                picked -> {
                                    categories[0] = picked;
                                    // The picker has just listed them itself, so re-reading our
                                    // own answer costs nothing and keeps the pill honest whichever
                                    // way it was closed -- OK, Save, or the app's defaults.
                                    renderSummary.run();
                                }));
                if ((chosen[0] & BackupFlags.BACKUP_APP_DATA) != 0) {
                    askForCategories[0].run();
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMarginStart(Math.round(36 * d));
                lp.bottomMargin = Math.round(8 * d);
                categoriesPill.setLayoutParams(lp);
                body.addView(categoriesPill);
            }
        }
        ScrollView scroller = new ScrollView(context);
        scroller.addView(body);
        ForkDialog.present(ForkDialog.builder(context)
                .setTitle(R.string.backup_options)
                .setView(scroller)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.back_up, (dialog, which) ->
                        onChosen.onChosen(chosen[0], categories[0])));
    }

    /**
     * Fork (白い熊, +132): the "what to restore" chooser, for one app.
     *
     * <p>Restoring used to be all-or-nothing in practice: the flag list it offered was the same
     * grey checkbox column the backup options used before +121, so choosing to put back only the
     * data — or only the APK, over an app whose data you want to keep — meant reading eight
     * identical lines to find the two that mattered. It is the same decision as choosing what to
     * back up, so it is drawn the same way, in the same colours, from the same parts.
     *
     * <p>Only the parts the backup actually <b>contains</b> are offered. A greyed-out row for
     * something that is not in the archive tells you nothing you can act on.
     *
     * @param availableFlags what the backup holds
     * @param checkedFlags   what starts ticked
     * @param lockedOnFlags  what cannot be unticked, with {@code lockedNote} saying why
     * @param optionFlags    restore options that are not parts (signature check, custom users)
     */
    public static void showRestoreOptions(@NonNull Context context, int availableFlags,
                                          int checkedFlags, int lockedOnFlags,
                                          @Nullable CharSequence lockedNote, int optionFlags,
                                          @NonNull Consumer<Integer> onChosen) {
        final int[] chosen = {checkedFlags | lockedOnFlags};
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        float d = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * d);
        body.setPadding(pad, Math.round(4 * d), pad, 0);
        for (BackupParts.Part part : BackupParts.contentParts()) {
            if ((availableFlags & part.flag) == 0) {
                continue;
            }
            boolean locked = (lockedOnFlags & part.flag) != 0;
            body.addView(partRow(context, part, (chosen[0] & part.flag) != 0, locked,
                    locked ? lockedNote : null, on -> {
                        if (on) {
                            chosen[0] |= part.flag;
                        } else {
                            chosen[0] &= ~part.flag;
                        }
                    }));
        }
        if (optionFlags != 0) {
            io.github.muntashirakon.widget.FlowLayout options = new io.github.muntashirakon.widget.FlowLayout(context);
            options.setChildSpacing(Math.round(6 * d));
            options.setRowSpacing(Math.round(5 * d));
            int grey = 0xFFB0BEC5;
            if ((optionFlags & BackupFlags.BACKUP_NO_SIGNATURE_CHECK) != 0) {
                options.addView(plainPill(context, context.getString(R.string.restore_skip_signature),
                        grey, (chosen[0] & BackupFlags.BACKUP_NO_SIGNATURE_CHECK) != 0, on -> {
                            if (on) {
                                chosen[0] |= BackupFlags.BACKUP_NO_SIGNATURE_CHECK;
                            } else {
                                chosen[0] &= ~BackupFlags.BACKUP_NO_SIGNATURE_CHECK;
                            }
                        }));
            }
            if ((optionFlags & BackupFlags.BACKUP_CUSTOM_USERS) != 0) {
                options.addView(plainPill(context, context.getString(R.string.backup_custom_users),
                        grey, (chosen[0] & BackupFlags.BACKUP_CUSTOM_USERS) != 0, on -> {
                            if (on) {
                                chosen[0] |= BackupFlags.BACKUP_CUSTOM_USERS;
                            } else {
                                chosen[0] &= ~BackupFlags.BACKUP_CUSTOM_USERS;
                            }
                        }));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Math.round(4 * d);
            options.setLayoutParams(lp);
            body.addView(options);
        }
        ScrollView scroller = new ScrollView(context);
        scroller.addView(body);
        ForkDialog.present(ForkDialog.builder(context)
                .setTitle(R.string.restore_what_title)
                .setView(scroller)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.restore, (dialog, which) -> onChosen.accept(chosen[0])));
    }
}
