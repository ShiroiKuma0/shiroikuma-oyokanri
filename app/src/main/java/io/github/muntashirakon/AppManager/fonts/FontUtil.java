// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.fonts;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves and applies a configurable font (family + weight + size) to a
 * TextView. Family values: "" (inherit), a system family name, or
 * "file:&lt;absolute path&gt;" for an imported font file. Weight is a real
 * per-weight on API 28+ (Typeface.create(base, weight, false)); on older
 * APIs it collapses to BOLD/NORMAL. Size is in sp; 0 means leave unchanged.
 */
public final class FontUtil {

    private FontUtil() {}

    public static final String FILE_PREFIX = "file:";

    /** Cap for the inline size slider; larger values can still be typed. */
    public static final int SIZE_SLIDER_MIN = 8;
    public static final int SIZE_SLIDER_MAX = 96;
    public static final int SIZE_HARD_CAP = 300;

    private static final Map<String, Typeface> sFileCache = new HashMap<>();

    /** Selectable font families: label shown to the user, value stored. */
    public static final class Option {
        public final String label;
        public final String value;
        public Option(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    @NonNull
    public static List<Option> families(@NonNull Context ctx) {
        List<Option> out = new ArrayList<>();
        out.add(new Option("Inherit (default)", ""));
        out.add(new Option("Sans serif", "sans-serif"));
        out.add(new Option("Sans serif light", "sans-serif-light"));
        out.add(new Option("Sans serif medium", "sans-serif-medium"));
        out.add(new Option("Sans serif condensed", "sans-serif-condensed"));
        out.add(new Option("Serif", "serif"));
        out.add(new Option("Monospace", "monospace"));
        out.add(new Option("Cursive", "cursive"));
        for (String path : FontPrefs.getImportedFonts(ctx)) {
            out.add(new Option(new File(path).getName(), FILE_PREFIX + path));
        }
        return out;
    }

    public static final int[] WEIGHT_VALUES = {0, 100, 300, 400, 500, 700, 900};
    public static final String[] WEIGHT_LABELS = {
            "Inherit (default)", "Thin (100)", "Light (300)", "Regular (400)",
            "Medium (500)", "Bold (700)", "Black (900)"
    };

    @NonNull
    public static String weightLabel(int weight) {
        for (int i = 0; i < WEIGHT_VALUES.length; ++i) {
            if (WEIGHT_VALUES[i] == weight) return WEIGHT_LABELS[i];
        }
        return String.valueOf(weight);
    }

    @NonNull
    public static String familyLabel(@NonNull Context ctx, @NonNull String value) {
        if (value.isEmpty()) return "Inherit (default)";
        if (value.startsWith(FILE_PREFIX)) {
            return new File(value.substring(FILE_PREFIX.length())).getName();
        }
        for (Option o : families(ctx)) {
            if (o.value.equals(value)) return o.label;
        }
        return value;
    }

    /**
     * Resolve a Typeface for a family + weight, or null when both are
     * "inherit" (caller should then leave the view's current typeface).
     *
     * @param current the view's current typeface, used as the base when only
     *                a weight is specified (family inherited).
     */
    @Nullable
    public static Typeface resolveTypeface(@NonNull String family, int weight, @Nullable Typeface current) {
        if (family.isEmpty() && weight == 0) return null;
        Typeface base;
        if (family.isEmpty()) {
            base = current != null ? current : Typeface.DEFAULT;
        } else if (family.startsWith(FILE_PREFIX)) {
            base = fromFile(family.substring(FILE_PREFIX.length()));
            if (base == null) base = current != null ? current : Typeface.DEFAULT;
        } else {
            base = Typeface.create(family, Typeface.NORMAL);
        }
        if (weight == 0) return base;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Typeface.create(base, weight, false);
        }
        return Typeface.create(base, weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
    }

    @Nullable
    private static Typeface fromFile(@NonNull String path) {
        Typeface tf = sFileCache.get(path);
        if (tf != null) return tf;
        try {
            File f = new File(path);
            if (!f.exists()) return null;
            tf = Typeface.createFromFile(f);
            sFileCache.put(path, tf);
            return tf;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static void clearCache() {
        sFileCache.clear();
    }

    /**
     * Apply the effective font for a category to a TextView. Non-destructive
     * when everything inherits: a category with no family/weight/size set
     * (and no DEFAULT override) leaves the view exactly as the layout/binding
     * configured it.
     */
    public static void apply(@NonNull TextView tv, @NonNull String category) {
        Context ctx = tv.getContext();
        String family = FontPrefs.effectiveFamily(ctx, category);
        int weight = FontPrefs.effectiveWeight(ctx, category);
        int size = FontPrefs.effectiveSize(ctx, category);
        Typeface resolved = resolveTypeface(family, weight, tv.getTypeface());
        if (resolved != null) {
            tv.setTypeface(resolved);
        }
        if (size > 0) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        }
    }
}
