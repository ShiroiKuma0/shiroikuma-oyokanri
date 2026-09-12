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
    private static final String PREF_MIGRATED_CLEAR_DATA = "migrated_clear_data";
    private static final String PREF_MIGRATED_FREEZE_LEVELS = "migrated_freeze_levels";
    private static final String DELIM = ",";

    /** Every action the pane can offer. Stable wire keys — never rename one. */
    public static final List<String> ALL_KEYS = Collections.unmodifiableList(Arrays.asList(
            "open",
            "app_info",
            "snooping",
            "freeze",
            // Fork (白い熊, +039): the ladder, one pill per rung, in place of the single Freeze
            // pill. "freeze" itself stays a key so it can be brought back from the editor.
            "freeze_level_1",
            "freeze_level_2",
            "freeze_level_3",
            "freeze_level_4",
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
            "open", "app_info", "snooping", "freeze_level_1", "freeze_level_2", "freeze_level_3",
            "freeze_level_4", "force_stop", "backup", "restore", "share_backup", "note",
            "uninstall", "clear_data"));

    /**
     * Fork (白い熊, +038): one-shot promotion of {@code clear_data} into the visible set.
     *
     * <p><b>LANDMINE — adding a key to {@link #DEFAULT_VISIBLE} is inert on an install that has
     * already edited the pane.</b> Once the editor has saved, both stored orders exist, and
     * {@link #loadVisibleOrder}'s reconciliation only promotes a key missing from <em>both</em>;
     * {@code clear_data} has always been in {@link #ALL_KEYS}, so it is sitting in the stored
     * hidden list and would stay there for ever. Same shape as
     * {@code Prefs.Blocking.migrateDefaultFreezingMethodToTotal} and for the same reason.
     *
     * <p>Fires once, and only while the key is actually hidden, so hiding it again afterwards is
     * a decision that stands.
     */
    public static void migrateClearDataVisible(@NonNull Context ctx) {
        SharedPreferences sp = sp(ctx);
        if (sp.getBoolean(PREF_MIGRATED_CLEAR_DATA, false)) {
            return;
        }
        sp.edit().putBoolean(PREF_MIGRATED_CLEAR_DATA, true).apply();
        String visibleRaw = sp.getString(PREF_VISIBLE_ORDER, null);
        String hiddenRaw = sp.getString(PREF_HIDDEN_ORDER, null);
        if (visibleRaw == null && hiddenRaw == null) {
            // Never edited: DEFAULT_VISIBLE already carries it.
            return;
        }
        List<String> visible = new ArrayList<>(parseKeys(visibleRaw));
        List<String> hidden = new ArrayList<>(parseKeys(hiddenRaw));
        if (visible.contains("clear_data")) {
            return;
        }
        hidden.remove("clear_data");
        visible.add("clear_data");
        save(ctx, visible, hidden);
    }

    /**
     * Fork (白い熊, +039): one-shot swap of the single {@code freeze} pill for the four rungs.
     *
     * <p>Same trap as {@link #migrateClearDataVisible} and answered the same way, with one extra
     * requirement: the rungs must land <b>where the Freeze pill was</b>. Left to the ordinary
     * reconciliation they would be appended to the end of the pane, which for four pills at once
     * is not a detail.
     *
     * <p>A pane that had already hidden Freeze is left alone here — reconciliation will offer the
     * rungs at the end, which is the right answer for someone who did not want the pill.
     */
    public static void migrateFreezeLevelPills(@NonNull Context ctx) {
        SharedPreferences sp = sp(ctx);
        if (sp.getBoolean(PREF_MIGRATED_FREEZE_LEVELS, false)) {
            return;
        }
        sp.edit().putBoolean(PREF_MIGRATED_FREEZE_LEVELS, true).apply();
        String visibleRaw = sp.getString(PREF_VISIBLE_ORDER, null);
        String hiddenRaw = sp.getString(PREF_HIDDEN_ORDER, null);
        if (visibleRaw == null && hiddenRaw == null) {
            // Never edited: DEFAULT_VISIBLE already carries the rungs, and not "freeze".
            return;
        }
        List<String> visible = new ArrayList<>(parseKeys(visibleRaw));
        List<String> hidden = new ArrayList<>(parseKeys(hiddenRaw));
        int at = visible.indexOf("freeze");
        if (at < 0) {
            return;
        }
        visible.remove(at);
        List<String> rungs = new ArrayList<>();
        for (String rung : new String[]{"freeze_level_1", "freeze_level_2", "freeze_level_3",
                "freeze_level_4"}) {
            if (!visible.contains(rung)) {
                rungs.add(rung);
                hidden.remove(rung);
            }
        }
        visible.addAll(at, rungs);
        if (!hidden.contains("freeze")) {
            hidden.add("freeze");
        }
        save(ctx, visible, hidden);
    }

    @StringRes
    public static int titleForKey(@NonNull String key) {
        switch (key) {
            case "open":            return R.string.launch_app;
            case "app_info":        return R.string.app_info;
            case "snooping":        return R.string.snooping;
            case "freeze":          return R.string.freeze;
            case "freeze_level_1":  return R.string.freeze_level_1;
            case "freeze_level_2":  return R.string.freeze_level_2;
            case "freeze_level_3":  return R.string.freeze_level_3;
            case "freeze_level_4":  return R.string.freeze_level_4;
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
            case "freeze_level_1":  return R.drawable.ic_snowflake;
            case "freeze_level_2":  return R.drawable.ic_snowflake;
            case "freeze_level_3":  return R.drawable.ic_snowflake;
            case "freeze_level_4":  return R.drawable.ic_snowflake;
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
