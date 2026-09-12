// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.fonts;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
    /** Fork, +81: the padlock a suspended row shows in place of the snowflake. */
    public static final String FREEZE_SUSPENDED = "freeze_suspended";
    public static final String CHIP = "chip";
    public static final String ADDPILL = "addpill";
    // Fork: dormant-row films (painted as the card background)
    public static final String FILM_FROZEN = "film_frozen";
    public static final String FILM_UNINSTALLED = "film_uninstalled";
    public static final String FILM_SUSPENDED = "film_suspended";

    // ---- Fork (白い熊, +034): the freeze ladder, one colour per gate ----
    // Indexed by the FreezeUtils gate number, so FREEZE_LEVEL[3] is the disable
    // gate's colour. Step 1 has no film on purpose — an app that is only
    // force-stopped is not frozen and its row must stay an ordinary row.
    public static final String FREEZE_LEVEL_1 = "freeze_level_1";
    public static final String FREEZE_LEVEL_2 = "freeze_level_2";
    public static final String FREEZE_LEVEL_3 = "freeze_level_3";
    public static final String FREEZE_LEVEL_4 = "freeze_level_4";
    public static final String FILM_LEVEL_2 = "film_level_2";
    public static final String FILM_LEVEL_3 = "film_level_3";
    public static final String FILM_LEVEL_4 = "film_level_4";

    /** The accent key for a gate number, 1-4. */
    @NonNull
    public static String freezeLevelKey(int level) {
        switch (level) {
            case 1: return FREEZE_LEVEL_1;
            case 2: return FREEZE_LEVEL_2;
            case 3: return FREEZE_LEVEL_3;
            default: return FREEZE_LEVEL_4;
        }
    }

    /** The film key for a gate number. Level 1 has none — see above. */
    @Nullable
    public static String freezeFilmKey(int level) {
        switch (level) {
            case 2: return FILM_LEVEL_2;
            case 3: return FILM_LEVEL_3;
            case 4: return FILM_LEVEL_4;
            default: return null;
        }
    }

    // ---- colour-role keys (process monitor / reaper row state) ----
    public static final String MONITOR_KILLABLE = "monitor_killable";        // default yellow
    public static final String MONITOR_LEAK = "monitor_leak";                // default orange
    public static final String MONITOR_PROTECTED = "monitor_protected";      // default grey
    public static final String MONITOR_USER_PROTECTED = "monitor_user_protected"; // default ice blue
    public static final String MONITOR_SEPARATOR_H = "monitor_separator_h";   // default dark grey
    public static final String MONITOR_SEPARATOR_V = "monitor_separator_v";   // default dark grey

    // ---- process detail page text colours ----
    public static final String MONITOR_DETAIL_LABEL = "monitor_detail_label";        // default yellow
    public static final String MONITOR_DETAIL_ID = "monitor_detail_id";              // default dim yellow
    public static final String MONITOR_DETAIL_SECTION = "monitor_detail_section";    // default yellow
    public static final String MONITOR_DETAIL_ROW_LABEL = "monitor_detail_row_label"; // default dim yellow
    public static final String MONITOR_DETAIL_ROW_VALUE = "monitor_detail_row_value"; // default yellow

    // ---- colour-role keys (App details header, Stage 2) ----
    public static final String DETAIL_LABEL = "detail_label";
    public static final String DETAIL_PACKAGE = "detail_package";
    public static final String DETAIL_VERSION = "detail_version";

    // ---- colour-role keys (main-list separator grid; widths in SeparatorPrefs) ----
    public static final String SEPARATOR_H = "separator_h";
    public static final String SEPARATOR_V = "separator_v";

    // ---- colour-role key (selected card frame; width/radius in SelectionFramePrefs) ----
    public static final String SELECTED_FRAME = "selected_frame";

    // ---- colour-role keys (batch operation log; text size in OpLogPrefs) ----
    // Fork (白い熊, +116): the running backup/restore log. Every kind of line is settable,
    // because the whole point of the colours is orientation while scrolling past thousands of
    // them — and what reads well is a matter of the eye looking at it.
    public static final String OPLOG_TIME = "oplog_time";
    public static final String OPLOG_GUIDE = "oplog_guide";
    public static final String OPLOG_BATCH = "oplog_batch";
    public static final String OPLOG_APP = "oplog_app";
    public static final String OPLOG_STAGE = "oplog_stage";
    public static final String OPLOG_ITEM = "oplog_item";
    public static final String OPLOG_DETAIL = "oplog_detail";
    public static final String OPLOG_OK = "oplog_ok";
    public static final String OPLOG_FAIL = "oplog_fail";
    /** Fork (白い熊): the fill behind a failure line. See the default below for why. */
    public static final String OPLOG_FAIL_BG = "oplog_fail_bg";
    /** Fork (白い熊): the ✗ glyph, kept at full alarm strength. */
    public static final String OPLOG_FAIL_MARK = "oplog_fail_mark";
    public static final String OPLOG_SKIP = "oplog_skip";
    public static final String OPLOG_WARN = "oplog_warn";

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
            case MONITOR_LEAK:
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
            case MONITOR_KILLABLE:
            case MONITOR_DETAIL_LABEL:
            case MONITOR_DETAIL_SECTION:
            case MONITOR_DETAIL_ROW_VALUE:
            case OPLOG_BATCH:
            case OPLOG_APP:
                return ContextCompat.getColor(ctx, R.color.theme_bright_yellow);
            case MONITOR_DETAIL_ID:
            case MONITOR_DETAIL_ROW_LABEL:
            case OPLOG_STAGE:
                return 0x99FFFF00;  // dim yellow
            case OPLOG_TIME:
                return 0xFF6E6E6E;  // dim grey — present, never competing with the line
            case OPLOG_GUIDE:
                return 0xFF4A4A4A;  // the indent ladder: structure, not content
            case OPLOG_DETAIL:
            case OPLOG_SKIP:
                return 0xFF8A8A8A;  // grey: said, and not worth acting on
            case OPLOG_OK:
                return 0xFF4CD07A;  // green — the only colour here that means "nothing to do"
            case OPLOG_FAIL:
                // Fork (白い熊): the INK of a failure line, and deliberately not #FF0028.
                //
                // Saturated red has very low luminance, so at this size on black it is the one
                // colour in the fork that genuinely cannot be read -- the same finding as +149,
                // where the sibling screens' red headline had to become a filled pill. Red must
                // stay, because it is the critical colour; what changes is which half of the
                // pair carries it. The fill below is red; the words are near-white, exactly the
                // pairing the Snooping page already uses for its "Allowed" state.
                return 0xFFFFD9DC;  // near-white pink — reads instantly, still unmistakably red
            case OPLOG_FAIL_BG:
                return 0xFF6E0B14;  // blood red — the fill, shared with the Snooping page
            case OPLOG_FAIL_MARK:
                return 0xFFFF0028;  // the fork's alarm red, kept for the ✗ glyph alone
            case OPLOG_WARN:
                return ContextCompat.getColor(ctx, R.color.theme_bright_orange);
            case VERSION_INACTIVE:
                return ContextCompat.getColor(ctx, io.github.muntashirakon.ui.R.color.stopped);
            case APPTYPE_PERSISTENT:
                return Color.MAGENTA;
            case FREEZE_FROZEN:
            case MONITOR_USER_PROTECTED:
                return ContextCompat.getColor(ctx, R.color.theme_ice_blue);
            case MONITOR_SEPARATOR_H:
            case MONITOR_SEPARATOR_V:
                return 0xFF2A2A2A;  // subtle dark grey (matches the old hairline)
            case FREEZE_SUSPENDED:
                return ContextCompat.getColor(ctx, R.color.theme_violet);
            case FILM_FROZEN:
                return ContextCompat.getColor(ctx, R.color.theme_film_frozen);
            case FILM_UNINSTALLED:
                return ContextCompat.getColor(ctx, R.color.theme_film_uninstalled);
            case FILM_SUSPENDED:
                return ContextCompat.getColor(ctx, R.color.theme_film_suspended);
            case FREEZE_LEVEL_1:
                return ContextCompat.getColor(ctx, R.color.theme_freeze_1);
            case FREEZE_LEVEL_2:
                return ContextCompat.getColor(ctx, R.color.theme_freeze_2);
            case FREEZE_LEVEL_3:
                return ContextCompat.getColor(ctx, R.color.theme_freeze_3);
            case FREEZE_LEVEL_4:
                return ContextCompat.getColor(ctx, R.color.theme_freeze_4);
            case FILM_LEVEL_2:
                return ContextCompat.getColor(ctx, R.color.theme_film_freeze_2);
            case FILM_LEVEL_3:
                return ContextCompat.getColor(ctx, R.color.theme_film_freeze_3);
            case FILM_LEVEL_4:
                return ContextCompat.getColor(ctx, R.color.theme_film_freeze_4);
            case VERSION_NORMAL:
            case APPTYPE_NORMAL:
            case DATE_NORMAL:
            case UID_NORMAL:
            case SDK_NORMAL:
            case SIGNATURE:
            case DETAIL_LABEL:
            case DETAIL_PACKAGE:
            case DETAIL_VERSION:
            case OPLOG_ITEM:
            default:
                return ContextCompat.getColor(ctx, io.github.muntashirakon.ui.R.color.textColorSecondary);
        }
    }
}
