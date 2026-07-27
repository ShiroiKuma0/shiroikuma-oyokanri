// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.snooping;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.github.muntashirakon.AppManager.utils.ContextUtils;

/**
 * Fork: the store behind the app-details <em>Snooping</em> tab.
 * <p>
 * One dedicated SharedPreferences file, {@code shiroikuma_snooping.xml}, keyed by
 * <b>package name</b> — deliberately <i>not</i> by anything derived from an
 * installed app. A stored decision therefore outlives uninstalling the app, and
 * an archive imported onto a phone that has never seen the app is kept verbatim
 * until that package finally shows up, at which point
 * {@link SnoopingEnforcer} applies it.
 * <p>
 * Because the store is its own {@code shared_prefs/*.xml}, the settings
 * Export/Import picks it up through
 * {@code SettingsBackupManager.Category.SNOOPING} with no serialisation of its
 * own — the file <i>is</i> the wire format.
 * <p>
 * Value encoding, per package: {@code "id=0,id2=1"} where the key is a
 * {@link SnoopingCatalog.Entry#id} and the value is {@code 1} for allowed,
 * {@code 0} for blocked. Absence is a third state — <i>not managed</i> — and is
 * never written; only capabilities you actually decided on are stored, so an
 * import never disturbs anything you did not choose. Unknown ids are preserved
 * on read-modify-write so a settings file authored by a newer build (or on a
 * newer Android version, where more ops exist) survives a round-trip through an
 * older one.
 */
public final class SnoopingPrefs {
    public static final String PREF_FILE = "shiroikuma_snooping";

    /** Per-package settings live under this prefix so they cannot collide with option keys. */
    private static final String PKG_KEY_PREFIX = "pkg:";
    /** Master switch for re-applying stored settings on install and at startup. */
    private static final String KEY_AUTO_APPLY = "auto_apply";
    /** Whether the tab also lists capabilities the app has not requested. */
    private static final String KEY_SHOW_ALL = "show_all";

    private SnoopingPrefs() {
    }

    @NonNull
    private static SharedPreferences prefs() {
        return ContextUtils.getContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    /**
     * Re-apply stored settings when a package is installed and when the app
     * starts. On by default — a saved anti-snooping decision that silently
     * stopped being enforced would be worse than never having made it.
     */
    public static boolean isAutoApplyEnabled() {
        return prefs().getBoolean(KEY_AUTO_APPLY, true);
    }

    public static void setAutoApplyEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_AUTO_APPLY, enabled).apply();
    }

    /** Whether the tab also lists permission-gated capabilities the app never requested. */
    public static boolean isShowAllEnabled() {
        return prefs().getBoolean(KEY_SHOW_ALL, false);
    }

    public static void setShowAllEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_SHOW_ALL, enabled).apply();
    }

    /**
     * The stored decisions for a package: capability id → {@code true} allowed /
     * {@code false} blocked. Capabilities with no stored decision are absent.
     * Insertion order follows the stored string, which keeps unknown ids stable.
     */
    @NonNull
    public static Map<String, Boolean> getSettings(@NonNull String packageName) {
        return decode(prefs().getString(PKG_KEY_PREFIX + packageName, null));
    }

    /** Record one decision, leaving every other capability of the package untouched. */
    public static void setSetting(@NonNull String packageName, @NonNull String capabilityId, boolean allowed) {
        Map<String, Boolean> settings = getSettings(packageName);
        settings.put(capabilityId, allowed);
        write(packageName, settings);
    }

    /** Forget one decision — the capability goes back to being unmanaged. */
    public static void clearSetting(@NonNull String packageName, @NonNull String capabilityId) {
        Map<String, Boolean> settings = getSettings(packageName);
        if (settings.remove(capabilityId) != null) {
            write(packageName, settings);
        }
    }

    /** Forget every decision recorded for a package. */
    public static void clearPackage(@NonNull String packageName) {
        prefs().edit().remove(PKG_KEY_PREFIX + packageName).apply();
    }

    /** Record several decisions at once (one commit). */
    public static void setSettings(@NonNull String packageName, @NonNull Map<String, Boolean> newSettings) {
        Map<String, Boolean> settings = getSettings(packageName);
        settings.putAll(newSettings);
        write(packageName, settings);
    }

    /** Every package that has at least one stored decision, sorted for stable display. */
    @NonNull
    public static List<String> getManagedPackages() {
        Set<String> packages = new TreeSet<>();
        for (String key : prefs().getAll().keySet()) {
            if (key.startsWith(PKG_KEY_PREFIX)) {
                String pkg = key.substring(PKG_KEY_PREFIX.length());
                if (!pkg.isEmpty()) {
                    packages.add(pkg);
                }
            }
        }
        return new ArrayList<>(packages);
    }

    private static void write(@NonNull String packageName, @NonNull Map<String, Boolean> settings) {
        SharedPreferences.Editor editor = prefs().edit();
        String key = PKG_KEY_PREFIX + packageName;
        if (settings.isEmpty()) {
            editor.remove(key);
        } else {
            editor.putString(key, encode(settings));
        }
        editor.apply();
    }

    @NonNull
    private static Map<String, Boolean> decode(@Nullable String encoded) {
        Map<String, Boolean> settings = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return settings;
        }
        for (String pair : encoded.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || eq == pair.length() - 1) {
                continue;
            }
            settings.put(pair.substring(0, eq), "1".equals(pair.substring(eq + 1)));
        }
        return settings;
    }

    @NonNull
    private static String encode(@NonNull Map<String, Boolean> settings) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Boolean> e : settings.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue() ? '1' : '0');
        }
        return sb.toString();
    }

    /** Read-only view of everything stored, for diagnostics. */
    @NonNull
    public static Map<String, Map<String, Boolean>> dumpAll() {
        Map<String, Map<String, Boolean>> all = new LinkedHashMap<>();
        for (String pkg : getManagedPackages()) {
            all.put(pkg, getSettings(pkg));
        }
        return Collections.unmodifiableMap(all);
    }
}
