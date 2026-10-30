// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.apk.installer;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.muntashirakon.AppManager.utils.ContextUtils;

/**
 * Fork (白い熊, 2026-10-29): a per-app install-source override, opt-in per package.
 *
 * <p>A RASP-hardened app (e.g. the banking app cz.csas.georgego, Talsec freeRASP) refuses to run
 * unless it was installed from an approved store, which it reads off the install session's owner
 * (what {@code pm install -i <store>} and Aurora set). A restore or sideload has no such owner, so
 * the app closes. The fix is NOT global — most restores must keep looking like what they are — so
 * this stores, for the few packages that need it, the store package name to present the install
 * session as owned by (e.g. {@code com.android.vending}, {@code com.huawei.appmarket}).
 *
 * <p>This is the source of truth for the toggle in the backup/restore dialog. It is its own
 * {@code shared_prefs/*.xml}, so it travels in Settings export/import automatically; it is also
 * embedded in each new backup's metadata ({@code installerSourceOverride}) so it reaches other
 * phones, and {@code RestoreOp} writes it back here on restore. {@code PackageInstallerCompat
 * .openSession} reads it by package name to decide the session owner. Empty value = no override.
 */
public final class InstallSourcePrefs {
    public static final String STORE_PLAY = "com.android.vending";
    public static final String STORE_APPGALLERY = "com.huawei.appmarket";

    private static final String PREFS = "shiroikuma_install_source";

    private InstallSourcePrefs() {
    }

    @NonNull
    private static SharedPreferences prefs() {
        return ContextUtils.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The store package to present as the install-session owner for {@code packageName}, or null. */
    @Nullable
    public static String getInstallSource(@Nullable String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return null;
        }
        String v = prefs().getString(packageName, null);
        return TextUtils.isEmpty(v) ? null : v;
    }

    /** Set (or, with a null/blank store, clear) the override for {@code packageName}. */
    public static void setInstallSource(@NonNull String packageName, @Nullable String store) {
        if (TextUtils.isEmpty(store)) {
            prefs().edit().remove(packageName).apply();
        } else {
            prefs().edit().putString(packageName, store.trim()).apply();
        }
    }
}
