// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.DrawableRes;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import io.github.muntashirakon.AppManager.R;

/**
 * Storage + registry for the per-fork customisable bottom selection
 * toolbar. The toolbar source of truth was a static menu XML; this class
 * replaces that with a stable-key list that can be reordered and
 * partially hidden through the matching settings fragment.
 *
 * Each action has a string key that is stable across builds (R.id
 * resource integers are not, which is why we don't persist them
 * directly), plus a menu id / title res / icon res used to rebuild the
 * Menu against {@link io.github.muntashirakon.widget.MultiSelectionView}.
 *
 * Storage layout (private prefs file {@code am_main_toolbar}):
 *  - {@code visible_order}: comma-delimited keys in display order
 *  - {@code hidden_order}:  comma-delimited keys, appended after visible
 *    when the menu is rebuilt so the auto-overflow ("More…") receives
 *    them.
 *
 * When prefs are absent (first run, or after the key set changes),
 * unknown / missing keys are reconciled in {@link #loadVisibleOrder}
 * and {@link #loadHiddenOrder} so the union of the two lists is always
 * exactly {@link #ALL_KEYS}, no duplicates, no missing entries.
 */
public final class MainToolbarPrefs {

    private MainToolbarPrefs() {}

    private static final String PREFS_NAME = "am_main_toolbar";
    private static final String PREF_VISIBLE_ORDER = "visible_order";
    private static final String PREF_HIDDEN_ORDER = "hidden_order";
    private static final String DELIM = ",";

    /**
     * Stable string keys for every action that may live on the selection
     * toolbar, in the original menu XML order. ALL_KEYS doubles as the
     * default "everything visible in this order" state.
     */
    public static final List<String> ALL_KEYS = Collections.unmodifiableList(Arrays.asList(
            "uninstall",
            "install_existing",
            "freeze_unfreeze",
            "unfreeze",
            "force_stop",
            "clear_data_cache",
            "save_apk",
            "backup",
            "disable_background",
            "block_unblock_trackers",
            "net_policy",
            "optimize",
            "export_blocking_rules",
            "export_app_list",
            "add_to_profile"
    ));

    @IdRes
    public static int idForKey(@NonNull String key) {
        switch (key) {
            case "uninstall":               return R.id.action_uninstall;
            case "install_existing":        return R.id.action_install_existing;
            case "freeze_unfreeze":         return R.id.action_freeze_unfreeze;
            case "unfreeze":                return R.id.action_unfreeze;
            case "force_stop":              return R.id.action_force_stop;
            case "clear_data_cache":        return R.id.action_clear_data_cache;
            case "save_apk":                return R.id.action_save_apk;
            case "backup":                  return R.id.action_backup;
            case "disable_background":      return R.id.action_disable_background;
            case "block_unblock_trackers":  return R.id.action_block_unblock_trackers;
            case "net_policy":              return R.id.action_net_policy;
            case "optimize":                return R.id.action_optimize;
            case "export_blocking_rules":   return R.id.action_export_blocking_rules;
            case "export_app_list":         return R.id.action_export_app_list;
            case "add_to_profile":          return R.id.action_add_to_profile;
            default: return 0;
        }
    }

    @StringRes
    public static int titleForKey(@NonNull String key) {
        switch (key) {
            case "uninstall":               return R.string.uninstall;
            case "install_existing":        return R.string.reinstall;
            case "freeze_unfreeze":         return R.string.freeze;
            case "unfreeze":                return R.string.unfreeze;
            case "force_stop":              return R.string.force_stop;
            case "clear_data_cache":        return R.string.clear;
            case "save_apk":                return R.string.save_apk;
            case "backup":                  return R.string.backup_restore;
            case "disable_background":      return R.string.disable_background;
            case "block_unblock_trackers":  return R.string.block_unblock_trackers;
            case "net_policy":              return R.string.net_policy;
            case "optimize":                return R.string.action_optimize_app;
            case "export_blocking_rules":   return R.string.export_blocking_rules;
            case "export_app_list":         return R.string.export_app_list;
            case "add_to_profile":          return R.string.add_to_profile;
            default: return 0;
        }
    }

    @DrawableRes
    public static int iconForKey(@NonNull String key) {
        switch (key) {
            case "uninstall":               return R.drawable.ic_trash_can;
            case "install_existing":        return R.drawable.ic_restore;
            case "freeze_unfreeze":         return R.drawable.ic_snowflake;
            case "unfreeze":                return R.drawable.ic_snowflake_off;
            case "force_stop":              return R.drawable.ic_power_settings;
            case "clear_data_cache":        return R.drawable.ic_brush;
            case "save_apk":                return R.drawable.ic_get_app;
            case "backup":                  return R.drawable.ic_backup_restore;
            case "disable_background":      return R.drawable.ic_block;
            case "block_unblock_trackers":  return R.drawable.ic_cctv_off;
            case "net_policy":              return R.drawable.ic_security_network;
            case "optimize":                return R.drawable.ic_run_fast;
            case "export_blocking_rules":   return R.drawable.ic_file_export;
            case "export_app_list":         return R.drawable.ic_file_export;
            case "add_to_profile":          return R.drawable.ic_file_plus;
            default: return 0;
        }
    }

    @NonNull
    private static SharedPreferences sp(@NonNull Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * @return ordered list of currently-visible action keys. Any
     * ALL_KEYS entry that is in neither the stored visible nor the
     * stored hidden list ends up here (so newly-added actions in
     * future versions become visible by default).
     */
    @NonNull
    public static List<String> loadVisibleOrder(@NonNull Context ctx) {
        String visibleRaw = sp(ctx).getString(PREF_VISIBLE_ORDER, null);
        String hiddenRaw = sp(ctx).getString(PREF_HIDDEN_ORDER, null);
        if (visibleRaw == null && hiddenRaw == null) {
            // First run: everything visible, original order
            return new ArrayList<>(ALL_KEYS);
        }
        LinkedHashSet<String> visible = parseKeys(visibleRaw);
        LinkedHashSet<String> hidden = parseKeys(hiddenRaw);
        // Any ALL_KEYS member missing from BOTH sets is appended to the
        // visible list - this is the path for new actions added after
        // the user had already customised the toolbar.
        for (String k : ALL_KEYS) {
            if (!visible.contains(k) && !hidden.contains(k)) {
                visible.add(k);
            }
        }
        return new ArrayList<>(visible);
    }

    /** @return ordered list of currently-hidden action keys. */
    @NonNull
    public static List<String> loadHiddenOrder(@NonNull Context ctx) {
        String hiddenRaw = sp(ctx).getString(PREF_HIDDEN_ORDER, null);
        if (hiddenRaw == null) return new ArrayList<>();
        return new ArrayList<>(parseKeys(hiddenRaw));
    }

    /**
     * Persist the new (visible, hidden) split. Both lists are written
     * atomically; together they should cover ALL_KEYS exactly once.
     * Caller is expected to enforce that invariant.
     */
    public static void save(@NonNull Context ctx,
                            @NonNull List<String> visible,
                            @NonNull List<String> hidden) {
        sp(ctx).edit()
                .putString(PREF_VISIBLE_ORDER, TextUtils.join(DELIM, visible))
                .putString(PREF_HIDDEN_ORDER, TextUtils.join(DELIM, hidden))
                .apply();
    }

    /** Parse a stored comma-delimited string, dropping unknown keys
     *  and dropping duplicates while preserving first-occurrence order. */
    @NonNull
    private static LinkedHashSet<String> parseKeys(@androidx.annotation.Nullable String raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String k : raw.split(DELIM)) {
            if (!k.isEmpty() && ALL_KEYS.contains(k)) {
                out.add(k);
            }
        }
        return out;
    }
}
