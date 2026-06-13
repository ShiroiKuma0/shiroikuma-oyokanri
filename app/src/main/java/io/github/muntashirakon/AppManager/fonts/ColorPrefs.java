// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.fonts;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import io.github.muntashirakon.AppManager.R;

/**
 * Per-element colour overrides for the fork's custom-theme list (and, later,
 * other surfaces). Companion to {@link FontPrefs}: where FontPrefs carries
 * family/weight/size per text category, ColorPrefs carries an optional colour
 * per <em>colour role</em> (a specific element in a specific state, e.g. the
 * package name when the app has trackers).
 *
 * Design notes:
 *  - A key is either set (an explicit ARGB int the user picked) or unset. When
 *    unset, callers fall back to {@link #defaultColor}, which returns the
 *    hardcoded palette value that the list used before this feature — so an
 *    unset key reproduces the original appearance exactly.
 *  - 0 is a valid colour (transparent black), so "unset" is tracked by key
 *    presence ({@link #isSet}), never by a sentinel value.
 *  - Stored in a dedicated SharedPreferences file (independent of AppPref and
 *    of FontPrefs). Keys: color_&lt;role&gt;.
 *  - {@link #consumeChanged()} mirrors FontPrefs so the main list re-binds on
 *    resume after a colour changes.
 */
public final class ColorPrefs {

    private ColorPrefs() {}

    private static final String PREFS_NAME = "shiroikuma_colors";

    private static volatile boolean sChanged = false;

    public static boolean consumeChanged() {
        boolean c = sChanged;
        sChanged = false;
        return c;
    }

    // ---- colour-role keys (main list, Stage 1) ----
    public static final String LABEL_FROZEN = "label_frozen";
    public static final String LABEL_SYSTEM = "label_system";
    public static final String LABEL_USER = "label_user";
    public static final String PACKAGE_TRACKERS = "package_trackers";
    public static final String PACKAGE_NORMAL = "package_normal";
    public static final String VERSION_INACTIVE = "version_inactive";
    public static final String VERSION_NORMAL = "version_normal";
    public static final String APPTYPE_PERSISTENT = "apptype_persistent";
    public static final String APPTYPE_NORMAL = "apptype_normal";
    public static final String DATE_READABLE = "date_readable";
    public static final String DATE_NORMAL = "date_normal";
    public static final String UID_SHARED = "uid_shared";
    public static final String UID_NORMAL = "uid_normal";
    public static final String SDK_CLEARTEXT = "sdk_cleartext";
    public static final String SDK_NORMAL = "sdk_normal";
    public static final String SIGNATURE = "signature";
    public static final String BACKUP = "backup";

    // ---- colour-role keys (non-text list indicators, Stage 2) ----
    public static final String STROKE_USER = "stroke_user";
    public static final String STROKE_SYSTEM = "stroke_system";
    public static final String FREEZE_FROZEN = "freeze_frozen";
    public static final String FREEZE_THAWED = "freeze_thawed";
    public static final String CHIP = "chip";
    public static final String ADDPILL = "addpill";
    // Fork: dormant-row films (painted as the card background)
    public static final String FILM_FROZEN = "film_frozen";
    public static final String FILM_UNINSTALLED = "film_uninstalled";

    // ---- colour-role keys (App details header, Stage 2) ----
    public static final String DETAIL_LABEL = "detail_label";
    public static final String DETAIL_PACKAGE = "detail_package";
    public static final String DETAIL_VERSION = "detail_version";

    // ---- colour-role keys (main-list separator grid; widths in SeparatorPrefs) ----
    public static final String SEPARATOR_H = "separator_h";
    public static final String SEPARATOR_V = "separator_v";

    // ---- colour-role key (selected card frame; width/radius in SelectionFramePrefs) ----
    public static final String SELECTED_FRAME = "selected_frame";

    @NonNull
    private static SharedPreferences sp(@NonNull Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isSet(@NonNull Context ctx, @NonNull String key) {
        return sp(ctx).contains("color_" + key);
    }

    /** Stored colour for {@code key}, or {@code def} if unset. */
    public static int getColor(@NonNull Context ctx, @NonNull String key, int def) {
        SharedPreferences s = sp(ctx);
        String k = "color_" + key;
        return s.contains(k) ? s.getInt(k, def) : def;
    }

    /** Stored colour for {@code key}, or its palette default if unset. */
    public static int getColor(@NonNull Context ctx, @NonNull String key) {
        return getColor(ctx, key, defaultColor(ctx, key));
    }

    public static void setColor(@NonNull Context ctx, @NonNull String key, int color) {
        sp(ctx).edit().putInt("color_" + key, color).apply();
        sChanged = true;
    }

    public static void reset(@NonNull Context ctx, @NonNull String key) {
        sp(ctx).edit().remove("color_" + key).apply();
        sChanged = true;
    }

    /**
     * The original hardcoded palette value for a role — the colour the list
     * showed before colours became configurable. Centralised here so the
     * adapter (which paints) and the settings screen (which shows the "Default"
     * swatch) never disagree.
     */
    public static int defaultColor(@NonNull Context ctx, @NonNull String key) {
        switch (key) {
            case LABEL_FROZEN:
                return ContextCompat.getColor(ctx, R.color.theme_ice_blue);
            case LABEL_SYSTEM:
            case PACKAGE_TRACKERS:
            case DATE_READABLE:
            case UID_SHARED:
            case SDK_CLEARTEXT:
            case STROKE_SYSTEM:
                return ContextCompat.getColor(ctx, R.color.theme_bright_orange);
            case LABEL_USER:
            case PACKAGE_NORMAL:
            case BACKUP:
            case STROKE_USER:
            case FREEZE_THAWED:
            case CHIP:
            case ADDPILL:
            case SEPARATOR_H:
            case SEPARATOR_V:
            case SELECTED_FRAME:
                return ContextCompat.getColor(ctx, R.color.theme_bright_yellow);
            case VERSION_INACTIVE:
                return ContextCompat.getColor(ctx, io.github.muntashirakon.ui.R.color.stopped);
            case APPTYPE_PERSISTENT:
                return Color.MAGENTA;
            case FREEZE_FROZEN:
                return ContextCompat.getColor(ctx, R.color.theme_ice_blue);
            case FILM_FROZEN:
                return ContextCompat.getColor(ctx, R.color.theme_film_frozen);
            case FILM_UNINSTALLED:
                return ContextCompat.getColor(ctx, R.color.theme_film_uninstalled);
            case VERSION_NORMAL:
            case APPTYPE_NORMAL:
            case DATE_NORMAL:
            case UID_NORMAL:
            case SDK_NORMAL:
            case SIGNATURE:
            case DETAIL_LABEL:
            case DETAIL_PACKAGE:
            case DETAIL_VERSION:
            default:
                return ContextCompat.getColor(ctx, io.github.muntashirakon.ui.R.color.textColorSecondary);
        }
    }
}
