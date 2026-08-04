// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.devicepolicy;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

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

    /** Forget every 雫-side lock recorded for this package. */
    public static void clearPackage(@NonNull String packageName) {
        prefs().edit()
                .remove(ACCESSIBILITY_PREFIX + packageName)
                .remove(USER_CONTROL_PREFIX + packageName)
                .apply();
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
