// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.snooping;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import io.github.muntashirakon.AppManager.utils.ContextUtils;

/**
 * Fork: which packages we have actually written an all-network firewall DENY for,
 * and under which uid.
 * <p>
 * <b>Why this has to exist.</b> {@code setUidFirewallRule} has no counterpart on
 * Android 13 — {@code getUidFirewallRule} arrived in Android 14 — and the chain
 * state is nowhere in {@code dumpsys connectivity}. So on this phone the rule is
 * write-only, and the tab's usual discipline ("ask the platform what it actually
 * enforces, never trust your own record") has nothing to ask. This file is the
 * fallback: a record of writes that <em>returned without throwing</em>, which for
 * this API is real evidence — {@code ConnectivityService} throws
 * {@code SecurityException} when it refuses and {@code ServiceSpecificException}
 * when netd rejects the write, unlike {@code AppOpsService}, which accepts a
 * write and silently discards it. Where Android 14+ can answer for itself, it is
 * asked and this record is only a fallback.
 * <p>
 * The uid is stored alongside because it is what the rule is keyed by, and a
 * reinstall changes it. Unblocking clears the rule for the <em>recorded</em> uid
 * as well as the current one, so a stale DENY cannot outlive the app and land on
 * whatever uid the system recycles next.
 * <p>
 * Device-local by nature — a firewall rule on this phone says nothing about
 * another, and uids do not travel — so it lives in its own prefs file and is
 * listed in {@code SettingsBackupManager.EXCLUDED_PREFS}. The <i>decision</i>
 * still travels: that is {@link SnoopingPrefs}, keyed by package name.
 */
public final class NetBlockState {
    public static final String PREF_FILE = "shiroikuma_netblock";

    private static final String PKG_KEY_PREFIX = "pkg:";
    public static final int NO_UID = -1;

    private NetBlockState() {
    }

    @NonNull
    private static SharedPreferences prefs() {
        return ContextUtils.getContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    public static boolean isBlocked(@NonNull String packageName) {
        return blockedUid(packageName) != NO_UID;
    }

    /** The uid we wrote the DENY for, or {@link #NO_UID}. */
    public static int blockedUid(@NonNull String packageName) {
        return prefs().getInt(PKG_KEY_PREFIX + packageName, NO_UID);
    }

    public static void setBlocked(@NonNull String packageName, int uid) {
        prefs().edit().putInt(PKG_KEY_PREFIX + packageName, uid).apply();
    }

    public static void clear(@NonNull String packageName) {
        prefs().edit().remove(PKG_KEY_PREFIX + packageName).apply();
    }
}
