// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.utils;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.os.Build;

import androidx.annotation.ColorInt;
import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.ForkColorLoader;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.fonts.ColorPrefs;

/**
 * Fork (白い熊, +123): the theme's colour roles, every one of them settable on the 白い熊 応用管理 UI
 * screen, and the one place that makes XML follow them.
 *
 * <p><b>Why a resources loader.</b> A theme attribute, a drawable or a layout can only name a
 * colour <em>resource</em>, and a resource is fixed at build time — which is why the black-yellow
 * theme used to pin {@code #FFFF00} and black in some eighty places that no preference could
 * reach. From Android 11 a {@link ResourcesLoader} can replace resources at run time: each role
 * is a resource ({@code @color/fork_*}; the text base is the long-standing
 * {@code @color/theme_bright_yellow}), XML names the role, and {@link #apply} lays the stored
 * colours over them. Below Android 11 XML shows the defaults; everything drawn in code follows
 * the roles on every version.
 *
 * <p><b>Rules.</b> New XML names a role, never a literal and never {@code @android:color/black}.
 * New code asks {@link #get}. A new role is one line in {@link #ROLES} plus its
 * {@code <color>} — the UI screen builds its row from the table.
 *
 * <p>The values live in {@link ColorPrefs} (so they travel in the export with every other
 * colour); the two bases are the long-standing {@code Prefs.Appearance} theme text and
 * background, which {@code ColorPrefs} maps onto {@link #TEXT} and {@link #GROUND}.
 */
public final class ForkPalette {
    private ForkPalette() {
    }

    // Bases. Every other role defaults to one of these, so changing a base moves everything
    // that has not been given a colour of its own.
    public static final String TEXT = "base_text";
    public static final String GROUND = "base_ground";

    public static final String TOPBAR_BG = "topbar_bg";
    public static final String TOPBAR_TEXT = "topbar_text";
    public static final String DIALOG_BG = "dialog_bg";
    public static final String DIALOG_FRAME = "dialog_frame";
    public static final String DIALOG_TEXT = "dialog_text";
    public static final String MENU_BG = "menu_bg";
    public static final String MENU_FRAME = "menu_frame";
    public static final String MENU_TEXT = "menu_text";
    public static final String MENU_DIVIDER = "menu_divider";
    public static final String SHEET_BG = "sheet_bg";
    public static final String SHEET_FRAME = "sheet_frame";
    public static final String BUTTON_FRAME = "button_frame";
    public static final String BUTTON_TEXT = "button_text";
    public static final String SWITCH_ON = "switch_on";
    public static final String SWITCH_OFF = "switch_off";
    public static final String HEADING = "ui_heading";
    public static final String DIVIDER = "ui_divider";
    public static final String DELETE = "role_delete";
    /** Fork (+124): the track behind a bar, and the fill under a selected row or a search match. */
    public static final String TRACK = "bar_track";
    public static final String SELECTION = "selection_fill";
    public static final String ICON_CONNECT = "icon_connect";
    public static final String ICON_MESSAGE = "icon_message";
    public static final String ICON_CONTACT = "icon_contact";
    public static final String ICON_SHARE = "icon_share";
    public static final String ICON_VIEW = "icon_view";
    public static final String ICON_BLOCK = "icon_block";

    /** How a role's default is found. */
    private static final int FROM_TEXT = 0, FROM_GROUND = 1, FIXED = 2, BLEND = 3;

    public static final class Role {
        @NonNull
        public final String key;
        @ColorRes
        public final int colorRes;
        @StringRes
        public final int labelRes;
        final int source;
        final int value;      // FIXED: the colour; BLEND: percent of the way from ground to text

        Role(@NonNull String key, @ColorRes int colorRes, @StringRes int labelRes, int source, int value) {
            this.key = key;
            this.colorRes = colorRes;
            this.labelRes = labelRes;
            this.source = source;
            this.value = value;
        }
    }

    /** Every role, in the order the UI screen lists them within their sections. */
    public static final Role[] ROLES = {
            new Role(TEXT, R.color.theme_bright_yellow, R.string.palette_text, FIXED, 0xFFFFFF00),
            new Role(GROUND, R.color.fork_ground, R.string.palette_ground, FIXED, 0xFF000000),
            new Role(SELECTION, R.color.fork_selection, R.string.palette_selection, BLEND, 25),
            new Role(TRACK, R.color.fork_track, R.string.palette_track, BLEND, 20),
            new Role(TOPBAR_BG, R.color.fork_topbar_bg, R.string.palette_topbar_bg, FROM_GROUND, 0),
            new Role(TOPBAR_TEXT, R.color.fork_topbar_text, R.string.palette_topbar_text, FROM_TEXT, 0),
            new Role(BUTTON_FRAME, R.color.fork_button_frame, R.string.palette_button_frame, FROM_TEXT, 0),
            new Role(BUTTON_TEXT, R.color.fork_button_text, R.string.palette_button_text, FROM_TEXT, 0),
            new Role(DIALOG_BG, R.color.fork_dialog_bg, R.string.palette_dialog_bg, FROM_GROUND, 0),
            new Role(DIALOG_FRAME, R.color.fork_dialog_frame, R.string.palette_dialog_frame, FROM_TEXT, 0),
            new Role(DIALOG_TEXT, R.color.fork_dialog_text, R.string.palette_dialog_text, FROM_TEXT, 0),
            new Role(MENU_BG, R.color.fork_menu_bg, R.string.palette_menu_bg, FROM_GROUND, 0),
            new Role(MENU_FRAME, R.color.fork_menu_frame, R.string.palette_menu_frame, FROM_TEXT, 0),
            new Role(MENU_TEXT, R.color.fork_menu_text, R.string.palette_menu_text, FROM_TEXT, 0),
            new Role(MENU_DIVIDER, R.color.fork_menu_divider, R.string.palette_menu_divider, BLEND, 70),
            new Role(SHEET_BG, R.color.fork_sheet_bg, R.string.palette_sheet_bg, FROM_GROUND, 0),
            new Role(SHEET_FRAME, R.color.fork_sheet_frame, R.string.palette_sheet_frame, FROM_TEXT, 0),
            new Role(SWITCH_ON, R.color.fork_switch_on, R.string.palette_switch_on, FROM_TEXT, 0),
            new Role(SWITCH_OFF, R.color.fork_switch_off, R.string.palette_switch_off, BLEND, 55),
            new Role(HEADING, R.color.fork_heading, R.string.palette_heading, FROM_TEXT, 0),
            new Role(DIVIDER, R.color.fork_divider, R.string.palette_divider, BLEND, 70),
            new Role(ICON_CONNECT, R.color.fork_icon_connect, R.string.palette_icon_connect, FIXED, 0xFF00FF00),
            new Role(ICON_MESSAGE, R.color.fork_icon_message, R.string.palette_icon_message, FIXED, 0xFF4D94FF),
            new Role(ICON_CONTACT, R.color.fork_icon_contact, R.string.palette_icon_contact, FIXED, 0xFF00FFFF),
            new Role(ICON_SHARE, R.color.fork_icon_share, R.string.palette_icon_share, FIXED, 0xFFFF8000),
            new Role(ICON_VIEW, R.color.fork_icon_view, R.string.palette_icon_view, FROM_TEXT, 0),
            new Role(ICON_BLOCK, R.color.fork_icon_block, R.string.palette_icon_block, FIXED, 0xFFFF00FF),
            new Role(DELETE, R.color.fork_delete, R.string.palette_delete, FIXED, 0xFFFF0000),
    };

    private static final Map<String, Role> BY_KEY = new HashMap<>();

    static {
        for (Role r : ROLES) BY_KEY.put(r.key, r);
    }

    @Nullable
    public static Role role(@NonNull String key) {
        return BY_KEY.get(key);
    }

    public static boolean isRole(@NonNull String key) {
        return BY_KEY.containsKey(key);
    }

    /** The default of a role, or null when {@code key} is not one of the palette's. */
    @Nullable
    public static Integer defaultOf(@NonNull String key) {
        Role r = BY_KEY.get(key);
        if (r == null) return null;
        switch (r.source) {
            case FROM_TEXT:
                return ForkThemeUtils.getTextColor();
            case FROM_GROUND:
                return ForkThemeUtils.getBackgroundColor();
            case BLEND:
                return ColorUtils.blendARGB(ForkThemeUtils.getBackgroundColor(), ForkThemeUtils.getTextColor(),
                        r.value / 100f);
            default:
                return r.value;
        }
    }

    @ColorInt
    public static int get(@NonNull String key) {
        return get(ContextUtils.getContext(), key);
    }

    @ColorInt
    public static int get(@NonNull Context context, @NonNull String key) {
        if (TEXT.equals(key)) return ForkThemeUtils.getTextColor();
        if (GROUND.equals(key)) return ForkThemeUtils.getBackgroundColor();
        Integer def = defaultOf(key);
        return ColorPrefs.getColor(context, key, def != null ? def : ForkThemeUtils.getTextColor());
    }

    /** {@code color} at 38 % — a disabled frame or label. */
    @ColorInt
    public static int disabled(@ColorInt int color) {
        return ColorUtils.setAlphaComponent(color, Math.round(android.graphics.Color.alpha(color) * 0.38f));
    }

    // ---- laying the roles over the resources ----

    private static volatile int sEpoch = 1;
    private static int sLoaderEpoch = 0;
    @Nullable
    private static Object sLoader;   // a ResourcesLoader; typed Object so the class loads below API 30
    private static final WeakHashMap<Resources, Object> sApplied = new WeakHashMap<>();
    private static final WeakHashMap<Activity, Integer> sActivityEpoch = new WeakHashMap<>();

    /** A role changed: every screen is rebuilt with the new colours when it next comes forward. */
    public static void invalidate() {
        sEpoch++;
        try {
            apply(ContextUtils.getContext());
        } catch (Throwable ignore) {
        }
    }

    /**
     * Lay the stored colours over the role resources of this context. Call it before anything is
     * inflated from the context: for an activity that is {@code onActivityPreCreated}.
     */
    public static synchronized void apply(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            Api30.apply(context);
        } catch (Throwable ignore) {
            // A platform that refuses the loader shows the defaults; code-drawn surfaces still follow.
        }
        if (context instanceof Activity) sActivityEpoch.put((Activity) context, sEpoch);
    }

    /** Whether this activity was built with colours that have changed since. */
    public static synchronized boolean isStale(@NonNull Activity activity) {
        Integer epoch = sActivityEpoch.get(activity);
        return epoch != null && epoch != sEpoch;
    }

    @NonNull
    static Map<Integer, Integer> colors(@NonNull Context context) {
        Map<Integer, Integer> map = new HashMap<>();
        for (Role r : ROLES) map.put(r.colorRes, get(context, r.key));
        // The dim gold of descriptions and left rules is a role of ColorPrefs' own.
        map.put(R.color.settings_description, ColorPrefs.getColor(context, ColorPrefs.SETTINGS_DESCRIPTION,
                0xFFC8C800));
        return map;
    }

    private static final class Api30 {
        static void apply(@NonNull Context context) {
            if (sLoader == null || sLoaderEpoch != sEpoch) {
                Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
                sLoader = ForkColorLoader.create(app, colors(app));
                sLoaderEpoch = sEpoch;
            }
            if (sLoader == null) return;
            Resources res = context.getResources();
            Object old = sApplied.get(res);
            if (old == sLoader) return;
            if (old != null) res.removeLoaders((ResourcesLoader) old);
            res.addLoaders((ResourcesLoader) sLoader);
            sApplied.put(res, sLoader);
        }
    }
}
