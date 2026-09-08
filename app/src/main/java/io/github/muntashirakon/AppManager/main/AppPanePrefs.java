// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import io.github.muntashirakon.AppManager.R;

/**
 * Fork (白い熊, +118): which action pills the unrolled row shows, and in what order.
 *
 * <p>Deliberately the same shape as {@link MainToolbarPrefs} — same visible/hidden split, same
 * "a key nobody has classified becomes visible" rule, same drag-and-drop editor — because these
 * are the same kind of setting and there is no reason for a person to learn two of them. The
 * editor fragment is shared; only the registry differs.
 *
 * <p><b>Two keys are the point of the pane.</b> {@code app_info} and {@code snooping} lead to the
 * two pages a tap on the row used to reach, so nothing that used to be one tap away is now
 * unreachable — it is one pill away instead, and both are reachable rather than only whichever
 * App details happened to open on.
 *
 * <p>Unlike the toolbar, not everything starts visible: the pane is read at a glance while the
 * list is still on screen, and sixteen pills is not a glance. The rest wait in the editor.
 */
public final class AppPanePrefs {
    private AppPanePrefs() {
    }

    public static final String PREF_FILE = "shiroikuma_app_pane";
    private static final String PREF_VISIBLE_ORDER = "visible_order";
    private static final String PREF_HIDDEN_ORDER = "hidden_order";
    private static final String DELIM = ",";

    /** Every action the pane can offer. Stable wire keys — never rename one. */
    public static final List<String> ALL_KEYS = Collections.unmodifiableList(Arrays.asList(
            "open",
            "app_info",
            "snooping",
            "freeze",
            "force_stop",
            "backup",
            "restore",
            "delete_backup",
            "share_backup",
            "note",
            "uninstall",
            "add_to_profile",
            "save_apk",
            "clear_data",
            "clear_cache",
            "battery",
            "manifest",
            "scanner",
            "app_settings"));

    /** What a fresh install shows, in this order. */
    private static final List<String> DEFAULT_VISIBLE = Collections.unmodifiableList(Arrays.asList(
            "open", "app_info", "snooping", "freeze", "force_stop", "backup", "restore",
            "share_backup", "note", "uninstall"));

    @StringRes
    public static int titleForKey(@NonNull String key) {
        switch (key) {
            case "open":            return R.string.launch_app;
            case "app_info":        return R.string.app_info;
            case "snooping":        return R.string.snooping;
            case "freeze":          return R.string.freeze;
            case "force_stop":      return R.string.force_stop;
            // Fork (白い熊): backing up and restoring are separate actions here too. One
            // "Backup/restore" pill is the same merge the batch pane had, in the pane a
            // single app opens.
            case "backup":          return R.string.back_up;
            case "restore":         return R.string.restore;
            case "delete_backup":   return R.string.delete_backup;
            case "share_backup":    return R.string.share_backup;
            case "note":            return R.string.note;
            case "uninstall":       return R.string.uninstall;
            case "add_to_profile":  return R.string.add_to_profile;
            case "save_apk":        return R.string.save_apk;
            case "clear_data":      return R.string.clear_data;
            case "clear_cache":     return R.string.clear_cache;
            case "battery":         return R.string.battery_title;
            case "manifest":        return R.string.manifest;
            case "scanner":         return R.string.scanner;
            case "app_settings":    return R.string.view_in_settings;
            default: return 0;
        }
    }

    @DrawableRes
    public static int iconForKey(@NonNull String key) {
        switch (key) {
            case "open":            return R.drawable.ic_open_in_new;
            case "app_info":        return R.drawable.ic_information_circle;
            case "snooping":        return R.drawable.ic_cctv_off;
            case "freeze":          return R.drawable.ic_snowflake;
            case "force_stop":      return R.drawable.ic_power_settings;
            case "backup":          return R.drawable.ic_archive;
            case "restore":         return R.drawable.ic_restore;
            case "delete_backup":   return R.drawable.ic_trash_can;
            case "share_backup":    return R.drawable.ic_share;
            case "note":            return R.drawable.ic_note_24dp;
            case "uninstall":       return R.drawable.ic_trash_can;
            case "add_to_profile":  return R.drawable.ic_file_plus;
            case "save_apk":        return R.drawable.ic_get_app;
            case "clear_data":      return R.drawable.ic_clear_data;
            case "clear_cache":     return R.drawable.ic_clear_cache;
            case "battery":         return R.drawable.ic_battery_history;
            case "manifest":        return R.drawable.ic_package;
            case "scanner":         return R.drawable.ic_security;
            case "app_settings":    return R.drawable.ic_settings;
            default: return 0;
        }
    }

    @NonNull
    private static SharedPreferences sp(@NonNull Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    /**
     * The visible pills, in order. A key in neither stored list is appended, so an action added
     * by a later build shows up rather than disappearing into a set nobody knows about.
     */
    @NonNull
    public static List<String> loadVisibleOrder(@NonNull Context ctx) {
        String visibleRaw = sp(ctx).getString(PREF_VISIBLE_ORDER, null);
        String hiddenRaw = sp(ctx).getString(PREF_HIDDEN_ORDER, null);
        if (visibleRaw == null && hiddenRaw == null) {
            return new ArrayList<>(DEFAULT_VISIBLE);
        }
        LinkedHashSet<String> visible = parseKeys(visibleRaw);
        LinkedHashSet<String> hidden = parseKeys(hiddenRaw);
        for (String k : ALL_KEYS) {
            if (!visible.contains(k) && !hidden.contains(k) && DEFAULT_VISIBLE.contains(k)) {
                visible.add(k);
            }
        }
        return new ArrayList<>(visible);
    }

    @NonNull
    public static List<String> loadHiddenOrder(@NonNull Context ctx) {
        String visibleRaw = sp(ctx).getString(PREF_VISIBLE_ORDER, null);
        String hiddenRaw = sp(ctx).getString(PREF_HIDDEN_ORDER, null);
        if (visibleRaw == null && hiddenRaw == null) {
            List<String> hidden = new ArrayList<>();
            for (String k : ALL_KEYS) {
                if (!DEFAULT_VISIBLE.contains(k)) hidden.add(k);
            }
            return hidden;
        }
        LinkedHashSet<String> visible = parseKeys(visibleRaw);
        LinkedHashSet<String> hidden = parseKeys(hiddenRaw);
        for (String k : ALL_KEYS) {
            if (!visible.contains(k) && !hidden.contains(k)) {
                hidden.add(k);
            }
        }
        return new ArrayList<>(hidden);
    }

    public static void save(@NonNull Context ctx, @NonNull List<String> visible,
                            @NonNull List<String> hidden) {
        sp(ctx).edit()
                .putString(PREF_VISIBLE_ORDER, TextUtils.join(DELIM, visible))
                .putString(PREF_HIDDEN_ORDER, TextUtils.join(DELIM, hidden))
                .apply();
    }

    @NonNull
    private static LinkedHashSet<String> parseKeys(@androidx.annotation.Nullable String raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (TextUtils.isEmpty(raw)) return out;
        for (String part : raw.split(DELIM)) {
            String key = part.trim();
            if (!key.isEmpty() && ALL_KEYS.contains(key)) {
                out.add(key);
            }
        }
        return out;
    }
}
