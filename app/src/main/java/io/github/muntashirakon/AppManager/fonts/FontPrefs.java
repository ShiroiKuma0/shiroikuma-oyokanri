// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.fonts;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Per-surface configurable fonts for the fork. Each text "category" (app
 * label, package name, version, ...) carries its own font family, weight,
 * and size, with a global {@link #DEFAULT} category that any unset value
 * falls back to before finally inheriting the view's built-in styling.
 *
 * Mirrors the FontPrefs design from the ArcaneChat / GNU Jami forks:
 *  - family : "" = inherit; a system family name (sans-serif, serif,
 *    monospace, cursive, sans-serif-light, ...); or "file:&lt;abs path&gt;"
 *    for an imported .ttf/.otf.
 *  - weight : 0 = inherit; otherwise 100..900 (applied as a real weight on
 *    API 28+, BOLD/NORMAL fallback below).
 *  - size   : 0 = inherit; otherwise an sp value.
 *
 * Stored in a dedicated SharedPreferences file so it is independent of
 * libcore AppPref. Keys: font_&lt;category&gt;_{family,weight,size}. The set of
 * imported font file paths is kept under {@link #KEY_IMPORTED}.
 */
public final class FontPrefs {

    private FontPrefs() {}

    private static final String PREFS_NAME = "shiroikuma_fonts";
    private static final String KEY_IMPORTED = "imported_fonts";

    // Set whenever any font setting changes; the main list consumes it on
    // resume to re-bind rows so new typefaces/sizes render after the user
    // leaves the Fonts settings screen. Process-wide (settings runs in a
    // separate activity, same process).
    private static volatile boolean sChanged = false;

    public static boolean consumeChanged() {
        boolean c = sChanged;
        sChanged = false;
        return c;
    }

    // Category keys. DEFAULT is the global fallback. The rest map to a
    // specific text surface. Increment 1 wires DEFAULT + LABEL + PACKAGE in
    // the UI and at the bind sites; the remaining constants are declared now
    // so later increments only add UI rows + apply() calls.
    public static final String DEFAULT = "default";
    public static final String LABEL = "label";
    public static final String PACKAGE = "package";
    public static final String VERSION = "version";
    public static final String INSTALL_DATE = "install_date";
    public static final String SDK = "sdk";
    public static final String SIGNATURE = "signature";
    public static final String BACKUP_INFO = "backup_info";

    @NonNull
    private static SharedPreferences sp(@NonNull Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ---- raw per-category values ----

    @NonNull
    public static String getFamily(@NonNull Context ctx, @NonNull String cat) {
        return sp(ctx).getString("font_" + cat + "_family", "");
    }

    public static int getWeight(@NonNull Context ctx, @NonNull String cat) {
        return sp(ctx).getInt("font_" + cat + "_weight", 0);
    }

    public static int getSize(@NonNull Context ctx, @NonNull String cat) {
        return sp(ctx).getInt("font_" + cat + "_size", 0);
    }

    public static void setFamily(@NonNull Context ctx, @NonNull String cat, @NonNull String family) {
        sp(ctx).edit().putString("font_" + cat + "_family", family).apply();
        sChanged = true;
    }

    public static void setWeight(@NonNull Context ctx, @NonNull String cat, int weight) {
        sp(ctx).edit().putInt("font_" + cat + "_weight", weight).apply();
        sChanged = true;
    }

    public static void setSize(@NonNull Context ctx, @NonNull String cat, int size) {
        sp(ctx).edit().putInt("font_" + cat + "_size", size).apply();
        sChanged = true;
    }

    // ---- effective values (category, then DEFAULT, then inherit) ----

    @NonNull
    public static String effectiveFamily(@NonNull Context ctx, @NonNull String cat) {
        String v = getFamily(ctx, cat);
        if (!v.isEmpty()) return v;
        if (!cat.equals(DEFAULT)) {
            String d = getFamily(ctx, DEFAULT);
            if (!d.isEmpty()) return d;
        }
        return "";
    }

    public static int effectiveWeight(@NonNull Context ctx, @NonNull String cat) {
        int v = getWeight(ctx, cat);
        if (v != 0) return v;
        if (!cat.equals(DEFAULT)) {
            int d = getWeight(ctx, DEFAULT);
            if (d != 0) return d;
        }
        return 0;
    }

    public static int effectiveSize(@NonNull Context ctx, @NonNull String cat) {
        int v = getSize(ctx, cat);
        if (v != 0) return v;
        if (!cat.equals(DEFAULT)) {
            int d = getSize(ctx, DEFAULT);
            if (d != 0) return d;
        }
        return 0;
    }

    public static void reset(@NonNull Context ctx, @NonNull String cat) {
        sp(ctx).edit()
                .remove("font_" + cat + "_family")
                .remove("font_" + cat + "_weight")
                .remove("font_" + cat + "_size")
                .apply();
        sChanged = true;
    }

    // ---- imported font registry ----

    @NonNull
    public static List<String> getImportedFonts(@NonNull Context ctx) {
        List<String> out = new ArrayList<>(sp(ctx).getStringSet(KEY_IMPORTED, Collections.emptySet()));
        // Drop entries whose file no longer exists (iterator loop rather than
        // removeIf, which is API 24+ and minSdk here is 21).
        for (java.util.Iterator<String> it = out.iterator(); it.hasNext(); ) {
            if (!new File(it.next()).exists()) it.remove();
        }
        Collections.sort(out);
        return out;
    }

    public static void addImportedFont(@NonNull Context ctx, @NonNull String path) {
        LinkedHashSet<String> set = new LinkedHashSet<>(sp(ctx).getStringSet(KEY_IMPORTED, Collections.emptySet()));
        set.add(path);
        sp(ctx).edit().putStringSet(KEY_IMPORTED, set).apply();
        sChanged = true;
    }
}
