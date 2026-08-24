// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.battery;

import android.app.AppOpsManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import io.github.muntashirakon.AppManager.compat.AppOpsManagerCompat;
import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.profiles.ProtectedAppsProfile;
import io.github.muntashirakon.AppManager.snooping.SnoopingCatalog;
import io.github.muntashirakon.AppManager.snooping.lever.SnoopingLever;
import io.github.muntashirakon.AppManager.snooping.lever.SnoopingLevers;
import io.github.muntashirakon.AppManager.utils.FreezeUtils;

/**
 * Fork: the actions the battery panel can take, in one place.
 *
 * <p>Everything here is a thin facade over machinery the fork already owns —
 * {@link FreezeUtils}, {@link PackageManagerCompat}, the 盗み見 levers and app
 * ops. It exists so the panel does not grow its own second implementation of
 * rules that are already enforced elsewhere (the 必要 protected-profile guard
 * in particular, which must keep working no matter which screen calls freeze).
 */
public final class BatteryControls {
    public static final String TAG = BatteryControls.class.getSimpleName();

    /** App-op names for the background levers this panel offers. */
    public static final String OP_RUN_ANY_IN_BACKGROUND = "RUN_ANY_IN_BACKGROUND";
    public static final String OP_RUN_IN_BACKGROUND = "RUN_IN_BACKGROUND";
    public static final String OP_START_FOREGROUND = "START_FOREGROUND";
    public static final String OP_WAKE_LOCK = "WAKE_LOCK";
    public static final String OP_FINE_LOCATION = "FINE_LOCATION";
    public static final String OP_COARSE_LOCATION = "COARSE_LOCATION";

    public static final String LEVER_NETWORK = "network_internet";
    public static final String LEVER_BATTERY_EXEMPTION = "battery_exemption";

    private BatteryControls() {}

    public static boolean isProtected(@Nullable String packageName) {
        return ProtectedAppsProfile.isProtected(packageName);
    }

    public static boolean isFrozen(@Nullable ApplicationInfo info) {
        return info != null && FreezeUtils.isFrozen(info);
    }

    /**
     * Freeze or thaw. The 必要 guard lives inside {@link FreezeUtils#freeze},
     * which throws for a protected package — that throw is the contract, so it
     * is surfaced rather than pre-empted here.
     */
    @WorkerThread
    public static void setFrozen(@NonNull String packageName, int userId, boolean frozen)
            throws RemoteException {
        if (frozen) {
            FreezeUtils.freeze(packageName, userId, FreezeUtils.resolveFreezeMethod(packageName));
        } else {
            FreezeUtils.unfreeze(packageName, userId);
        }
    }

    @WorkerThread
    public static boolean forceStop(@NonNull String packageName, int userId) {
        try {
            PackageManagerCompat.forceStopPackage(packageName, userId);
            return true;
        } catch (Throwable th) {
            Log.w(TAG, "Could not force-stop " + packageName, th);
            return false;
        }
    }

    @Nullable
    public static SnoopingLever lever(@NonNull String leverId) {
        return SnoopingLevers.byId(leverId);
    }

    /**
     * Current mode of an op.
     *
     * @return true when allowed, false when blocked, {@code null} when this
     * platform has no such op (the name is resolved against the running
     * platform — op <em>codes</em> are renumbered between releases, so a
     * hard-coded number would silently address the wrong capability).
     */
    @WorkerThread
    @Nullable
    public static Boolean isOpAllowed(@NonNull String opName, int uid, @NonNull String packageName) {
        int op = SnoopingCatalog.opCodeOf(opName);
        if (op < 0) return null;
        try {
            int mode = new AppOpsManagerCompat().checkOperation(op, uid, packageName);
            return mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_FOREGROUND;
        } catch (Throwable th) {
            return null;
        }
    }

    /**
     * Moves an op, then <b>re-reads it</b> and reports whether the platform
     * actually complied. {@code AppOpsService} accepts writes it silently
     * discards — a permission-backed op is re-derived from the grant by
     * {@code PermissionPolicyService} — so a write that returned without
     * throwing proves nothing on its own.
     */
    @WorkerThread
    public static boolean setOpAllowed(@NonNull String opName, int uid, @NonNull String packageName,
                                       boolean allowed) {
        int op = SnoopingCatalog.opCodeOf(opName);
        if (op < 0) return false;
        try {
            AppOpsManagerCompat appOps = new AppOpsManagerCompat();
            appOps.setModeBothLevels(op, uid, packageName,
                    allowed ? AppOpsManager.MODE_ALLOWED : AppOpsManager.MODE_IGNORED);
            Boolean now = isOpAllowed(opName, uid, packageName);
            return now == null || now == allowed;
        } catch (Throwable th) {
            Log.w(TAG, "Could not set " + opName + " for " + packageName, th);
            return false;
        }
    }

    /** True when this platform has the named op at all. */
    public static boolean hasOp(@NonNull String opName) {
        return SnoopingCatalog.opCodeOf(opName) >= 0;
    }

    @WorkerThread
    public static boolean isLeverAllowed(@NonNull String leverId, @NonNull PackageInfo packageInfo,
                                         int userId) {
        SnoopingLever lever = lever(leverId);
        if (lever == null) return false;
        try {
            return lever.isApplicable(packageInfo, userId) && lever.isAllowed(packageInfo, userId);
        } catch (Throwable th) {
            return false;
        }
    }

    @WorkerThread
    public static boolean setLeverAllowed(@NonNull String leverId, @NonNull PackageInfo packageInfo,
                                          int userId, boolean allowed) {
        SnoopingLever lever = lever(leverId);
        if (lever == null) return false;
        try {
            return lever.setAllowed(packageInfo, userId, allowed);
        } catch (Throwable th) {
            Log.w(TAG, "Lever " + leverId + " refused", th);
            return false;
        }
    }
}
