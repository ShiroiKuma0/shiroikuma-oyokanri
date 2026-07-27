// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.snooping;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.HashSet;
import java.util.Set;

import io.github.muntashirakon.AppManager.utils.ContextUtils;

/**
 * Fork: capabilities this device refuses to turn <em>on</em> for a given package.
 * <p>
 * The Snooping tab exists to list what is snooping, or what could be made to
 * snoop. A capability that is blocked and cannot be un-blocked here is neither:
 * it is permanently safe, and a switch that cannot move is exactly what the tab
 * promises never to show. There is no way to ask the platform this in advance —
 * {@code AppOpsService} accepts a write and silently discards it — so it is
 * learnt the only way it can be: the first time a "turn it on" write does not
 * change what {@code checkOperation} enforces, the capability is marked here and
 * the row leaves the page.
 * <p>
 * <b>A mark only ever hides a row that is currently blocked</b>
 * ({@link SnoopingResolver}). A stale mark can therefore never hide something
 * that is snooping — the worst it can do is omit a safe row that has quietly
 * become movable again.
 * <p>
 * Device-local by nature: "this op would not move on this phone, for this
 * package" says nothing about another phone. It lives in its own prefs file and
 * is listed in {@code SettingsBackupManager.EXCLUDED_PREFS}, so it never travels
 * in a settings export nor gets planted by an import. It is also self-healing —
 * {@link SnoopingInstallReceiver} clears a package's marks when that package is
 * installed or updated, and clears every mark when 応用管理 itself is updated
 * (a new build may well write app-ops differently; 4.1.0+9 did exactly that).
 */
public final class SnoopingImmovable {
    public static final String PREF_FILE = "shiroikuma_snooping_immovable";

    private static final String PKG_KEY_PREFIX = "pkg:";

    private SnoopingImmovable() {
    }

    @NonNull
    private static SharedPreferences prefs() {
        return ContextUtils.getContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    @NonNull
    private static String keyOf(@NonNull String packageName) {
        return PKG_KEY_PREFIX + packageName;
    }

    /** Every capability id known not to be turn-on-able for this package. */
    @NonNull
    public static Set<String> get(@NonNull String packageName) {
        Set<String> stored = prefs().getStringSet(keyOf(packageName), null);
        // The returned set must never be mutated (SharedPreferences contract).
        return stored != null ? new HashSet<>(stored) : new HashSet<>();
    }

    public static boolean isMarked(@NonNull String packageName, @NonNull String capabilityId) {
        Set<String> stored = prefs().getStringSet(keyOf(packageName), null);
        return stored != null && stored.contains(capabilityId);
    }

    /** Record that this capability would not turn on. */
    public static void mark(@NonNull String packageName, @NonNull String capabilityId) {
        Set<String> ids = get(packageName);
        if (!ids.add(capabilityId)) {
            return;
        }
        prefs().edit().putStringSet(keyOf(packageName), ids).apply();
    }

    /** Forget the mark — the capability moved after all. */
    public static void clear(@NonNull String packageName, @NonNull String capabilityId) {
        Set<String> ids = get(packageName);
        if (!ids.remove(capabilityId)) {
            return;
        }
        SharedPreferences.Editor editor = prefs().edit();
        if (ids.isEmpty()) {
            editor.remove(keyOf(packageName));
        } else {
            editor.putStringSet(keyOf(packageName), ids);
        }
        editor.apply();
    }

    /** Re-test everything for this package, e.g. because it was just updated. */
    public static void clearPackage(@NonNull String packageName) {
        prefs().edit().remove(keyOf(packageName)).apply();
    }

    /** Re-test everything, everywhere — for when our own write path changes. */
    public static void clearAll() {
        prefs().edit().clear().apply();
    }
}
