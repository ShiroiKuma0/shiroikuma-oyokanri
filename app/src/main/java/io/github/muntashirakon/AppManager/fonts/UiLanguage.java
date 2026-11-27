// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.fonts;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
 *
 * <p>The dialog (settled 2026-11-27, replacing the plain list): 日本語 and English as two pinned
 * pills that never scroll, then one framed box that scrolls by itself with "System default" first
 * and the rest by name. Every line carries a bundled flag ({@code drawable-nodpi/fork_flag_*},
 * Noto Color Emoji); a language declared without a country shows the flag of its likely country.
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

    /**
     * The country a tag stands for, for showing only: its own, or the likely country of a
     * language declared without one ({@code de} → DE, {@code ja} → JP; ICU likely subtags, as
     * 白い熊 臨電話 does). What is stored and applied stays the declared tag.
     */
    @NonNull
    private static String countryOf(@NonNull String tag, @Nullable Locale locale) {
        if (locale == null) return "";
        if (!locale.getCountry().isEmpty()) return locale.getCountry();
        // ICU reads bare "pt" as Brazil; 白い熊 (2026-11-27) keeps Portugal's variant as the one
        // Portuguese row, and that is what the declared "pt" is.
        if ("pt".equals(tag)) return "PT";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                return android.icu.util.ULocale.addLikelySubtags(
                        android.icu.util.ULocale.forLanguageTag(tag)).getCountry();
            } catch (Throwable ignore) {
            }
        }
        return "";
    }

    /** The locale a row shows: the declared one with its likely country filled in. */
    @NonNull
    private static Locale shown(@NonNull String tag, @NonNull Locale locale) {
        if (!locale.getCountry().isEmpty()) return locale;
        String country = countryOf(tag, locale);
        return country.isEmpty() ? locale : new Locale(locale.getLanguage(), country);
    }

    /** The bundled flag of a tag's country (Noto Color Emoji); a globe where there is none. */
    private static int flagOf(@NonNull String tag, @Nullable Locale locale) {
        String country = countryOf(tag, locale);
        switch (country) {
            case "JP": return R.drawable.fork_flag_jp;
            case "US": return R.drawable.fork_flag_us;
            case "ES": return R.drawable.fork_flag_es;
            case "BR": return R.drawable.fork_flag_br;
            case "RU": return R.drawable.fork_flag_ru;
            case "TR": return R.drawable.fork_flag_tr;
            case "UA": return R.drawable.fork_flag_ua;
            case "CN": return R.drawable.fork_flag_cn;
            case "TW": return R.drawable.fork_flag_tw;
            case "EG": return R.drawable.fork_flag_eg;
            case "DE": return R.drawable.fork_flag_de;
            case "FR": return R.drawable.fork_flag_fr;
            case "ID": return R.drawable.fork_flag_id;
            case "IT": return R.drawable.fork_flag_it;
            case "NL": return R.drawable.fork_flag_nl;
            case "PL": return R.drawable.fork_flag_pl;
            case "PT": return R.drawable.fork_flag_pt;
            case "SE": return R.drawable.fork_flag_se;
            case "VN": return R.drawable.fork_flag_vn;
            default: return R.drawable.fork_flag_globe;
        }
    }

    /**
     * The dialog as the skill settled it on 2026-11-27: 日本語 and English as two full-width
     * pills on top, outside the scrolling part; under them one framed box that scrolls by itself,
     * "System default" first, then every other listed language by its own name.
     */
    static void showPicker(@NonNull Context context) {
        final Map<String, Locale> langs = LangUtils.getAppLanguages(context);
        final String current = langs.containsKey(currentTag()) ? currentTag() : LangUtils.LANG_AUTO;
        List<String> pinned = new ArrayList<>();
        if (langs.containsKey("ja")) pinned.add("ja");
        if (langs.containsKey("en")) pinned.add("en");
        List<String> rest = new ArrayList<>();
        for (String tag : langs.keySet()) {
            if (!LangUtils.LANG_AUTO.equals(tag) && !pinned.contains(tag)) rest.add(tag);
        }
        final Collator collator = Collator.getInstance(Locale.ROOT);
        Collections.sort(rest, (a, b) -> {
            int c = collator.compare(nameOf(langs.get(a)), nameOf(langs.get(b)));
            return c != 0 ? c : a.compareTo(b);
        });
        List<String> boxed = new ArrayList<>();
        boxed.add(LangUtils.LANG_AUTO);
        boxed.addAll(rest);

        final float d = context.getResources().getDisplayMetrics().density;
        final int line = ForkPalette.get(context, ForkPalette.DIVIDER);
        final AlertDialog[] dialog = new AlertDialog[1];

        LinearLayoutCompat content = new LinearLayoutCompat(context);
        content.setOrientation(LinearLayoutCompat.VERTICAL);
        content.setPadding(Math.round(12 * d), Math.round(8 * d), Math.round(12 * d), Math.round(4 * d));
        // The pinned pair: always visible, never scrolled away.
        for (String tag : pinned) {
            LinearLayoutCompat.LayoutParams pp = new LinearLayoutCompat.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pp.bottomMargin = Math.round(6 * d);
            content.addView(row(context, langs, tag, current, true, dialog), pp);
        }
        // The box with everything else.
        LinearLayoutCompat list = new LinearLayoutCompat(context);
        list.setOrientation(LinearLayoutCompat.VERTICAL);
        View currentRow = null;
        for (int i = 0; i < boxed.size(); i++) {
            String tag = boxed.get(i);
            boolean isCurrent = tag.equals(current);
            // A hairline between rows, but none right above or below the current row's pill.
            if (i > 0 && !isCurrent && !boxed.get(i - 1).equals(current)) {
                View hair = new View(context);
                hair.setBackgroundColor(line);
                LinearLayoutCompat.LayoutParams hp = new LinearLayoutCompat.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1);
                hp.setMarginStart(Math.round(12 * d));
                hp.setMarginEnd(Math.round(12 * d));
                list.addView(hair, hp);
            }
            View row = row(context, langs, tag, current, false, dialog);
            if (isCurrent) currentRow = row;
            list.addView(row, new LinearLayoutCompat.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        final ScrollView scroll = new ScrollView(context);
        scroll.setScrollbarFadingEnabled(false);
        scroll.addView(list);
        FrameLayout box = new FrameLayout(context);
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(android.graphics.Color.TRANSPARENT);
        panel.setCornerRadius(12 * d);
        panel.setStroke(Math.max(1, Math.round(1.25f * d)), ForkPalette.get(context, ForkPalette.DIALOG_FRAME));
        box.setBackground(panel);
        int inset = Math.round(5 * d);
        box.setPadding(inset, inset, inset, inset);
        box.addView(scroll);
        LinearLayoutCompat.LayoutParams bp = new LinearLayoutCompat.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);   // the box takes what the pair leaves
        bp.topMargin = Math.round(4 * d);
        content.addView(box, bp);

        dialog[0] = ForkDialog.present(ForkDialog.builder(context)
                .setTitle(R.string.ui_language_group)
                .setIcon(R.drawable.ic_globe)
                .setView(content)
                .setNegativeButton(R.string.cancel, null));
        // Open with the current language in view.
        if (currentRow != null) {
            final View r = currentRow;
            scroll.post(() -> scroll.scrollTo(0, Math.max(0, r.getTop() - Math.round(60 * d))));
        }
    }

    /** One line; {@code pill} = one of the pinned pair, which is a pill whether chosen or not. */
    @NonNull
    private static View row(@NonNull Context context, @NonNull Map<String, Locale> langs, @NonNull String tag,
                            @NonNull String current, boolean pill, @NonNull AlertDialog[] dialog) {
        final float d = context.getResources().getDisplayMetrics().density;
        final int text = ForkPalette.get(context, ForkPalette.DIALOG_TEXT);
        final int dim = ColorPrefs.getColor(context, ColorPrefs.SETTINGS_DESCRIPTION);
        final int frame = ForkPalette.get(context, ForkPalette.BUTTON_FRAME);
        final boolean isCurrent = tag.equals(current);
        final Locale locale = langs.get(tag);

        LinearLayoutCompat row = new LinearLayoutCompat(context);
        row.setOrientation(LinearLayoutCompat.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // The tag column stays at the right for right-to-left languages too.
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setPadding(Math.round(12 * d), Math.round(7 * d), Math.round(14 * d), Math.round(7 * d));
        row.setClickable(true);
        row.setFocusable(true);
        if (isCurrent) row.setBackground(Pills.frame(context, true, frame));
        else if (pill) row.setBackground(Pills.frame(context, false, frame));

        View ring = new View(context);
        ring.setBackground(ring(d, ForkPalette.get(context, isCurrent ? ForkPalette.SWITCH_ON
                : ForkPalette.SWITCH_OFF), isCurrent));
        row.addView(ring, new LinearLayoutCompat.LayoutParams(Math.round(18 * d), Math.round(18 * d)));

        ImageView flag = new ImageView(context);
        flag.setImageResource(flagOf(tag, locale));
        flag.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayoutCompat.LayoutParams fp = new LinearLayoutCompat.LayoutParams(
                Math.round(28 * d), Math.round(26 * d));
        fp.setMarginStart(Math.round(12 * d));
        row.addView(flag, fp);

        LinearLayoutCompat texts = new LinearLayoutCompat(context);
        texts.setOrientation(LinearLayoutCompat.VERTICAL);
        LinearLayoutCompat.LayoutParams tp = new LinearLayoutCompat.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.setMarginStart(Math.round(12 * d));
        AppCompatTextView name = new AppCompatTextView(context);
        name.setText(locale == null ? context.getString(R.string.ui_language_system) : nameOf(locale));
        name.setTextColor(text);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTypeface(null, isCurrent ? Typeface.BOLD : Typeface.NORMAL);
        // A right-to-left name stays at the row's start side like every other.
        name.setTextDirection(View.TEXT_DIRECTION_LTR);
        texts.addView(name);
        // Country line and tag follow the same likely country as the flag (de → Deutschland, de-DE).
        final Locale shown = locale == null ? null : shown(tag, locale);
        String country = shown == null ? "" : shown.getDisplayCountry(shown);
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
            code.setText(shown.getCountry().isEmpty() ? shown.getLanguage()
                    : shown.getLanguage() + "-" + shown.getCountry());
            code.setTextColor(dim);
            code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            code.setTypeface(Typeface.MONOSPACE);
            LinearLayoutCompat.LayoutParams cp = new LinearLayoutCompat.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.setMarginStart(Math.round(10 * d));
            row.addView(code, cp);
        }
        row.setOnClickListener(v -> {
            if (dialog[0] != null) dialog[0].dismiss();
            if (isCurrent) return;
            Prefs.Appearance.setLanguage(tag);
            AppearanceUtils.applyConfigurationChangesToActivities();
        });
        return row;
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
