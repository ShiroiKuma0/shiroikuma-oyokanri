// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.snooping.lever;

import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.PackageInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.Arrays;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.compat.DeviceIdleManagerCompat;
import io.github.muntashirakon.AppManager.compat.ManifestCompat;
import io.github.muntashirakon.AppManager.self.SelfPermissions;

/**
 * Fork: is this app exempt from doze, i.e. free to keep working when the phone is
 * idle and nobody is looking?
 * <p>
 * Not a capability of its own — it is what turns every other capability into a
 * continuous one. An app on the power-save whitelist keeps its alarms, its jobs
 * and its network while the screen is off; the same app off the whitelist gets
 * batched and deferred. The background app-ops on this page
 * ({@code RUN_ANY_IN_BACKGROUND} and friends) govern whether it may run at all;
 * this governs whether the system stops getting in its way.
 */
public class BatteryExemptionLever implements SnoopingLever {
    private static final String PERM_REQUEST_IGNORE = "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS";

    @WorkerThread
    @Override
    public boolean isApplicable(@NonNull PackageInfo packageInfo, @UserIdInt int userId) {
        // Worth a row when the app asked to be exempt, or already is — an app that
        // has neither cannot become exempt without the user's own consent dialog.
        if (packageInfo.requestedPermissions != null
                && Arrays.asList(packageInfo.requestedPermissions).contains(PERM_REQUEST_IGNORE)) {
            return true;
        }
        return isExempt(packageInfo.packageName);
    }

    @Override
    public boolean isModifiable() {
        return SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.DEVICE_POWER);
    }

    @WorkerThread
    @Override
    public boolean isAllowed(@NonNull PackageInfo packageInfo, @UserIdInt int userId) {
        return isExempt(packageInfo.packageName);
    }

    @WorkerThread
    @Override
    public boolean setAllowed(@NonNull PackageInfo packageInfo, @UserIdInt int userId, boolean allowed) {
        try {
            if (allowed) {
                DeviceIdleManagerCompat.disableBatteryOptimization(packageInfo.packageName);
            } else {
                DeviceIdleManagerCompat.enableBatteryOptimization(packageInfo.packageName);
            }
        } catch (Throwable th) {
            return false;
        }
        // Both compat methods return true for cases they did not actually change
        // (a system-whitelisted app, an uninstalled package), so the state is
        // re-read rather than believed.
        return isExempt(packageInfo.packageName) == allowed;
    }

    @Nullable
    @Override
    public Boolean defaultAllowed() {
        // A fresh install is optimised like everything else.
        return Boolean.FALSE;
    }

    @Nullable
    @Override
    public CharSequence detail(@NonNull Context context) {
        return context.getString(R.string.snooping_detail_battery_whitelist);
    }

    @WorkerThread
    private static boolean isExempt(@NonNull String packageName) {
        try {
            return !DeviceIdleManagerCompat.isBatteryOptimizedApp(packageName);
        } catch (Throwable th) {
            return false;
        }
    }
}
