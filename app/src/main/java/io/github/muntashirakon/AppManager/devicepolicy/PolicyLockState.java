// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.devicepolicy;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.github.muntashirakon.AppManager.utils.ContextUtils;

/**
 * Fork: which 雫-side device-policy locks we have actually applied, per package.
 * <p>
 * <b>Why this has to exist.</b> The Snooping page's standing rule is to ask the
 * platform what it enforces and never to trust our own record — and for the
 * delegated powers it does exactly that ({@code isSuspended},
 * {@code isUninstallBlocked}, {@code getPermissionGrantState} all read back).
 * The two powers that live in 白い熊 雫 have no such read: {@code
 * setPermittedAccessibilityServices} and {@code setUserControlDisabledPackages}
 * are Device-Owner-only in <em>both</em> directions, so their getters refuse us
 * as surely as their setters do, and 雫's {@code status} call answers about the
 * device ({@code is_device_owner}, {@code api_level}, {@code delegated_scopes})
 * — never about one package. A switch has to show something, so this is what it
 * shows.
 * <p>
 * It is honest for the same reason {@link
 * io.github.muntashirakon.AppManager.snooping.NetBlockState} is: only a write
 * that came back {@code ok} is recorded, and 雫 sets {@code ok} from the real
 * {@code DevicePolicyManager} call rather than from having received the request.
 * A refusal therefore never becomes a tick. What it cannot see is a change made
 * behind our back — 雫's own UI, or a {@code clear_all_locks} issued from there —
 * so {@link #clearPackage} is called whenever this side clears everything, and
 * anything else is corrected by toggling the switch off and on.
 * <p>
 * Device-local by nature: the Device Owner, and therefore every lock it holds,
 * belongs to one phone. Listed in {@code SettingsBackupManager.EXCLUDED_PREFS}
 * so it never travels in an export and cannot be planted by an import.
 */
public final class PolicyLockState {
    public static final String PREF_FILE = "shiroikuma_policy_locks";

    private static final String ACCESSIBILITY_PREFIX = "a11y:";
    private static final String USER_CONTROL_PREFIX = "usercontrol:";
    /** {@code permlock:<package>:<permission>} — see the per-row memory below. */
    private static final String PERM_LOCK_PREFIX = "permlock:";

    private PolicyLockState() {
    }

    @NonNull
    private static SharedPreferences prefs() {
        return ContextUtils.getContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    public static boolean isAccessibilityBlocked(@NonNull String packageName) {
        return prefs().getBoolean(ACCESSIBILITY_PREFIX + packageName, false);
    }

    public static void setAccessibilityBlocked(@NonNull String packageName, boolean blocked) {
        put(ACCESSIBILITY_PREFIX + packageName, blocked);
    }

    public static boolean isUserControlDisabled(@NonNull String packageName) {
        return prefs().getBoolean(USER_CONTROL_PREFIX + packageName, false);
    }

    public static void setUserControlDisabled(@NonNull String packageName, boolean disabled) {
        put(USER_CONTROL_PREFIX + packageName, disabled);
    }

    // ── Per-row permission locks: the memory, not the lock ──────────────────

    /**
     * Fork, +80: whether a row's device-policy lock should be <b>put back</b> if it
     * ever stops being in force.
     * <p>
     * This is a different kind of record from the two above. Those exist because
     * the platform will not tell us the state at all; this one sits <em>beside</em>
     * a state the platform answers perfectly well ({@code getPermissionGrantState}),
     * and never stands in for it — the padlock's fill is still read from the
     * platform, and this only decides whether {@link PolicyEnforcer} re-applies the
     * lock and whether the row draws a ring.
     * <p>
     * <b>Why it is needed at all.</b> A hard lock cannot drift the way an app-op
     * can: Settings will not lift it and the app cannot. But it is stored against
     * the package's <em>installation</em>, so a full uninstall takes it with it —
     * and everything 雫 holds vanishes the moment it stops being Device Owner.
     * Both leave a lock you set silently absent, with nothing on the page to say
     * so.
     * <p>
     * <b>It deliberately does not travel.</b> {@code PREF_FILE} is in {@code
     * SettingsBackupManager.EXCLUDED_PREFS}, so unlike a remembered capability —
     * which is keyed by package name and is meaningful on a phone that does not
     * even have the app yet — a remembered lock is replayed on this phone only. A
     * Device Owner belongs to one phone, and so does everything it holds.
     */
    public static boolean isPermissionLockRemembered(@NonNull String packageName,
                                                     @NonNull String permission) {
        return prefs().getBoolean(permKey(packageName, permission), false);
    }

    public static void setPermissionLockRemembered(@NonNull String packageName,
                                                   @NonNull String permission, boolean remembered) {
        put(permKey(packageName, permission), remembered);
    }

    /** The permissions whose locks this package wants put back. */
    @NonNull
    public static Set<String> getRememberedPermissionLocks(@NonNull String packageName) {
        String prefix = PERM_LOCK_PREFIX + packageName + ":";
        Set<String> permissions = new HashSet<>();
        for (Map.Entry<String, ?> entry : prefs().getAll().entrySet()) {
            String key = entry.getKey();
            if (key != null && key.startsWith(prefix) && Boolean.TRUE.equals(entry.getValue())) {
                permissions.add(key.substring(prefix.length()));
            }
        }
        return permissions;
    }

    /** Every package with at least one remembered lock — what a sweep walks. */
    @NonNull
    public static Set<String> getPackagesWithRememberedLocks() {
        Set<String> packages = new HashSet<>();
        for (Map.Entry<String, ?> entry : prefs().getAll().entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.startsWith(PERM_LOCK_PREFIX) || !Boolean.TRUE.equals(entry.getValue())) {
                continue;
            }
            // package:permission — neither half may contain a colon, so the first
            // one after the prefix is the boundary.
            int split = key.indexOf(':', PERM_LOCK_PREFIX.length());
            if (split > PERM_LOCK_PREFIX.length()) {
                packages.add(key.substring(PERM_LOCK_PREFIX.length(), split));
            }
        }
        return packages;
    }

    @NonNull
    private static String permKey(@NonNull String packageName, @NonNull String permission) {
        return PERM_LOCK_PREFIX + packageName + ":" + permission;
    }

    /**
     * Forget every lock recorded for this package — the 雫-side pair and every
     * remembered per-row lock.
     * <p>
     * Called when everything on the app is released. A memory that outlived a
     * deliberate "clear all locks" would put back, at the next install, exactly
     * what was just let go.
     */
    public static void clearPackage(@NonNull String packageName) {
        SharedPreferences.Editor editor = prefs().edit()
                .remove(ACCESSIBILITY_PREFIX + packageName)
                .remove(USER_CONTROL_PREFIX + packageName);
        String prefix = PERM_LOCK_PREFIX + packageName + ":";
        for (String key : prefs().getAll().keySet()) {
            if (key != null && key.startsWith(prefix)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    /**
     * Store only what departs from the default, the same rule {@code
     * SnoopingPrefs} follows: a recorded "not locked" is indistinguishable from
     * never having been touched, and keeping it would only grow the file.
     */
    private static void put(@NonNull String key, boolean value) {
        if (value) {
            prefs().edit().putBoolean(key, true).apply();
        } else {
            prefs().edit().remove(key).apply();
        }
    }
}
