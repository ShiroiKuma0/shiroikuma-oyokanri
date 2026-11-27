// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.fonts;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.appcompat.widget.LinearLayoutCompat;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.settings.Prefs;
import io.github.muntashirakon.AppManager.utils.ForkDialog;
import io.github.muntashirakon.AppManager.utils.ForkPalette;
import io.github.muntashirakon.AppManager.utils.LangUtils;
import io.github.muntashirakon.AppManager.utils.Pills;
import io.github.muntashirakon.AppManager.utils.appearance.AppearanceUtils;

/**
 * Fork (白い熊, 2026-11-27): "App language", the first section of the UI page, and its picker in
 * the look the black-yellow skill settled — plain rows, only the current language in a pill, a
 * radio ring, the name in its own language over its region in dim gold, the tag in dim-gold
 * monospace in a right-aligned column, hairlines between rows.
 *
 * <p>There is <b>no second stored value</b>: this sets upstream's own language preference
 * ({@link Prefs.Appearance#setLanguage}), which Settings › Language sets too and which upstream
 * applies to every context at start. The list is the declaration, {@code res/xml/locales_config.xml}
 * (read by {@link LangUtils}), which the fork cut down to the short list: Japanese, English,
 * then the languages translated to four fifths or more.
 */
final class UiLanguage {
    private UiLanguage() {
    }

    @NonNull
    private static String currentTag() {
        String tag = Prefs.Appearance.getLanguage();
        return TextUtils.isEmpty(tag) ? LangUtils.LANG_AUTO : tag;
    }

    /** The name of the language now in force, as the "Language: …" row shows it. */
    @NonNull
    static String currentName(@NonNull Context context) {
        String tag = currentTag();
        if (LangUtils.LANG_AUTO.equals(tag)) return context.getString(R.string.ui_language_system);
        Locale locale = LangUtils.getAppLanguages(context).get(tag);
        return nameOf(locale != null ? locale : Locale.forLanguageTag(tag));
    }

    @NonNull
    private static String nameOf(@NonNull Locale locale) {
        String name = locale.getDisplayLanguage(locale);
        if (name.isEmpty()) return locale.toLanguageTag();
        return name.substring(0, 1).toUpperCase(locale) + name.substring(1);
    }

    static void showPicker(@NonNull Context context) {
        final Map<String, Locale> langs = LangUtils.getAppLanguages(context);
        final String current = langs.containsKey(currentTag()) ? currentTag() : LangUtils.LANG_AUTO;
        // System default, Japanese, English, then the rest by name.
        List<String> rest = new ArrayList<>();
        for (String tag : langs.keySet()) {
            if (!LangUtils.LANG_AUTO.equals(tag) && !"ja".equals(tag) && !"en".equals(tag)) rest.add(tag);
        }
        final Collator collator = Collator.getInstance(Locale.ROOT);
        Collections.sort(rest, (a, b) -> {
            int c = collator.compare(nameOf(langs.get(a)), nameOf(langs.get(b)));
            return c != 0 ? c : a.compareTo(b);
        });
        List<String> tags = new ArrayList<>();
        tags.add(LangUtils.LANG_AUTO);
        if (langs.containsKey("ja")) tags.add("ja");
        if (langs.containsKey("en")) tags.add("en");
        tags.addAll(rest);

        final float d = context.getResources().getDisplayMetrics().density;
        final int text = ForkPalette.get(context, ForkPalette.DIALOG_TEXT);
        final int dim = ColorPrefs.getColor(context, ColorPrefs.SETTINGS_DESCRIPTION);
        final int on = ForkPalette.get(context, ForkPalette.SWITCH_ON);
        final int off = ForkPalette.get(context, ForkPalette.SWITCH_OFF);
        final int line = ForkPalette.get(context, ForkPalette.DIVIDER);
        final int frame = ForkPalette.get(context, ForkPalette.BUTTON_FRAME);

        LinearLayoutCompat list = new LinearLayoutCompat(context);
        list.setOrientation(LinearLayoutCompat.VERTICAL);
        list.setPadding(Math.round(12 * d), Math.round(8 * d), Math.round(12 * d), Math.round(4 * d));
        final AlertDialog[] dialog = new AlertDialog[1];
        boolean previousWasCurrent = true;   // no hairline above the first row
        for (final String tag : tags) {
            boolean isCurrent = tag.equals(current);
            if (!isCurrent && !previousWasCurrent) {
                View hair = new View(context);
                hair.setBackgroundColor(line);
                LinearLayoutCompat.LayoutParams hp = new LinearLayoutCompat.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1);
                hp.setMarginStart(Math.round(42 * d));   // inset to the text
                hp.setMarginEnd(Math.round(12 * d));
                list.addView(hair, hp);
            }
            previousWasCurrent = isCurrent;

            LinearLayoutCompat row = new LinearLayoutCompat(context);
            row.setOrientation(LinearLayoutCompat.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Math.round(12 * d), Math.round(7 * d), Math.round(12 * d), Math.round(7 * d));
            row.setClickable(true);
            row.setFocusable(true);
            if (isCurrent) row.setBackground(Pills.frame(context, true, frame));

            View ring = new View(context);
            ring.setBackground(ring(d, isCurrent ? on : off, isCurrent));
            row.addView(ring, new LinearLayoutCompat.LayoutParams(Math.round(18 * d), Math.round(18 * d)));

            LinearLayoutCompat texts = new LinearLayoutCompat(context);
            texts.setOrientation(LinearLayoutCompat.VERTICAL);
            LinearLayoutCompat.LayoutParams tp = new LinearLayoutCompat.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tp.setMarginStart(Math.round(12 * d));
            Locale locale = langs.get(tag);
            AppCompatTextView name = new AppCompatTextView(context);
            name.setText(locale == null ? context.getString(R.string.ui_language_system) : nameOf(locale));
            name.setTextColor(text);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            name.setTypeface(null, isCurrent ? Typeface.BOLD : Typeface.NORMAL);
            // A right-to-left name stays at the row's start side like every other.
            name.setTextDirection(View.TEXT_DIRECTION_LTR);
            texts.addView(name);
            String country = locale == null ? "" : locale.getDisplayCountry(locale);
            if (!country.isEmpty()) {
                AppCompatTextView region = new AppCompatTextView(context);
                region.setText(country);
                region.setTextColor(dim);
                region.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                region.setTextDirection(View.TEXT_DIRECTION_LTR);
                texts.addView(region);
            }
            row.addView(texts, tp);

            if (locale != null) {
                AppCompatTextView code = new AppCompatTextView(context);
                code.setText(locale.toLanguageTag());
                code.setTextColor(dim);
                code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                code.setTypeface(Typeface.MONOSPACE);
                code.setGravity(Gravity.END);
                LinearLayoutCompat.LayoutParams cp = new LinearLayoutCompat.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cp.setMarginStart(Math.round(10 * d));
                row.addView(code, cp);
            }
            row.setOnClickListener(v -> {
                if (dialog[0] != null) dialog[0].dismiss();
                if (tag.equals(current)) return;
                Prefs.Appearance.setLanguage(tag);
                AppearanceUtils.applyConfigurationChangesToActivities();
            });
            list.addView(row, new LinearLayoutCompat.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        ScrollView scroll = new ScrollView(context);
        scroll.addView(list);
        dialog[0] = ForkDialog.present(ForkDialog.builder(context)
                .setTitle(R.string.ui_language_group)
                .setView(scroll)
                .setNegativeButton(R.string.cancel, null));
    }

    /** The selection mark: a ring, with a dot on the current row. */
    @NonNull
    private static Drawable ring(float d, int color, boolean dot) {
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(android.graphics.Color.TRANSPARENT);
        ring.setStroke(Math.max(1, Math.round(2 * d)), color);
        if (!dot) return ring;
        GradientDrawable fill = new GradientDrawable();
        fill.setShape(GradientDrawable.OVAL);
        fill.setColor(color);
        LayerDrawable both = new LayerDrawable(new Drawable[]{ring, fill});
        int inset = Math.round(5 * d);
        both.setLayerInset(1, inset, inset, inset, inset);
        return both;
    }
}
