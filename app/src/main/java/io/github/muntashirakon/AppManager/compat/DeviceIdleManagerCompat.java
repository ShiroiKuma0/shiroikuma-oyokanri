// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.compat;

import android.os.Build;
import android.os.IDeviceIdleController;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;

import io.github.muntashirakon.AppManager.ipc.ProxyBinder;
import io.github.muntashirakon.AppManager.utils.ExUtils;

public final class DeviceIdleManagerCompat {
    @RequiresPermission(ManifestCompat.permission.DEVICE_POWER)
    public static boolean disableBatteryOptimization(@NonNull String packageName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                // Undo a previous removal from the system list first. Documented
                // as a no-op for a package that was never removed from it
                // ("only packages that were earlier removed from the system
                // whitelist can be added back"), so it is safe unconditionally.
                try {
                    getDeviceIdleController().restoreSystemPowerWhitelistApp(packageName);
                } catch (Throwable ignore) {
                }
                getDeviceIdleController().addPowerSaveWhitelistApp(packageName);
                return true; // returns true when the package isn't installed
            } catch (RemoteException e) {
                ExUtils.rethrowFromSystemServer(e);
            }
        }
        return false;
    }

    @RequiresPermission(ManifestCompat.permission.DEVICE_POWER)
    public static boolean enableBatteryOptimization(@NonNull String packageName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                getDeviceIdleController().removePowerSaveWhitelistApp(packageName);
                // Fork: the user list is not the only one. An OEM ships its own
                // apps on the *system* list, which the call above never touches —
                // so on a Motorola razr 40 ultra "un-exempting" Play Store was a
                // silent no-op. removeSystemPowerWhitelistApp is the matching
                // lever, and is reversible via restoreSystemPowerWhitelistApp.
                if (!isBatteryOptimizedApp(packageName)) {
                    try {
                        getDeviceIdleController().removeSystemPowerWhitelistApp(packageName);
                    } catch (Throwable ignore) {
                        // Older release, or a ROM that refuses: the caller
                        // re-reads the state and reports the truth either way.
                    }
                }
                return true;
            } catch (RemoteException e) {
                ExUtils.rethrowFromSystemServer(e);
            } catch (UnsupportedOperationException e) {
                // System whitelisted app
                e.printStackTrace();
            }
        }
        return false;
    }

    /**
     * Fork: whether an exemption this package holds can be taken away <em>at
     * all</em> on this device.
     * <p>
     * <b>Measured 2026-08-01.</b> There are three power-save whitelists, and
     * {@code dumpsys deviceidle whitelist} names them in its own output:
     * {@code user,…}, {@code system,…} and {@code system-excidle,…}. The first
     * two have per-package removals ({@code removePowerSaveWhitelistApp},
     * {@code removeSystemPowerWhitelistApp} — the latter verified working on
     * EMUI, which restored cleanly afterwards). The <b>except-idle</b> list has
     * none: {@code cmd deviceidle except-idle-whitelist} offers only {@code +}
     * (add), {@code =} (check) and {@code reset} (all of it), and no binder call
     * exists either. So an entry there cannot be cleared for one app by us, by
     * {@code adb}, or by root — which is exactly how a Motorola razr 40 ultra
     * ships {@code com.android.vending}.
     * <p>
     * Judged from list membership rather than from
     * {@code isPowerSaveWhitelistExceptIdleApp}, whose exact reach varies by
     * release: a wrong answer here hides a switch that works.
     *
     * @return {@code true} when nothing pins the exemption, i.e. it is ours to
     *         remove — including when the app is not exempt at all, since adding
     *         it always works.
     */
    public static boolean isExemptionRemovable(@NonNull String packageName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        try {
            String[] exceptIdle = getDeviceIdleController().getSystemPowerWhitelistExceptIdle();
            if (exceptIdle == null) {
                // No answer is not evidence — never hide a row on a failed read.
                return true;
            }
            for (String name : exceptIdle) {
                if (packageName.equals(name)) {
                    return false;
                }
            }
            return true;
        } catch (Throwable th) {
            return true;
        }
    }

    public static boolean isBatteryOptimizedApp(@NonNull String packageName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                IDeviceIdleController controller = getDeviceIdleController();
                return !controller.isPowerSaveWhitelistExceptIdleApp(packageName) &&
                        !controller.isPowerSaveWhitelistApp(packageName);
            } catch (RemoteException e) {
                ExUtils.rethrowFromSystemServer(e);
            }
        }
        // Not supported
        return true;
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private static IDeviceIdleController getDeviceIdleController() {
        return IDeviceIdleController.Stub.asInterface(ProxyBinder.getService("deviceidle"));
    }
}
