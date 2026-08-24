// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.utils;

import android.annotation.UserIdInt;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.RemoteException;
import android.os.UserHandleHidden;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.compat.ApplicationInfoCompat;
import io.github.muntashirakon.AppManager.compat.ManifestCompat;
import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.db.AppsDb;
import io.github.muntashirakon.AppManager.db.entity.FreezeType;
import io.github.muntashirakon.AppManager.devicepolicy.DevicePolicyBridge;
import io.github.muntashirakon.AppManager.profiles.ProtectedAppsProfile;
import io.github.muntashirakon.AppManager.self.SelfPermissions;
import io.github.muntashirakon.AppManager.settings.Prefs;

public final class FreezeUtils {
    @IntDef({FREEZE_DISABLE, FREEZE_SUSPEND, FREEZE_HIDE, FREEZE_ADV_SUSPEND})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FreezeMethod {
    }

    public static final int FREEZE_DISABLE = 1;
    public static final int FREEZE_SUSPEND = 1 << 1;
    public static final int FREEZE_HIDE = 1 << 2;
    public static final int FREEZE_ADV_SUSPEND = 1 << 3;

    @WorkerThread
    public static void storeFreezeMethod(@NonNull String packageName, @FreezeMethod int freezeType) {
        AppsDb.getInstance().freezeTypeDao().insert(new FreezeType(packageName, freezeType));
    }

    @WorkerThread
    public static void deleteFreezeMethod(@NonNull String packageName) {
        AppsDb.getInstance().freezeTypeDao().delete(packageName);
    }

    @WorkerThread
    @FreezeMethod
    @Nullable
    public static Integer loadFreezeMethod(@Nullable String packageName) {
        if (packageName != null) {
            FreezeType freezeType;
            freezeType = AppsDb.getInstance().freezeTypeDao().get(packageName);
            if (freezeType != null) {
                return freezeType.type;
            }
        }
        // No package-specific freezing method exists
        return null;
    }

    public static boolean isFrozen(@NonNull ApplicationInfo applicationInfo) {
        // An app is frozen if one of the following operations return true: suspend, disable or hide
        if (!applicationInfo.enabled) {
            return true;
        }
        if (ApplicationInfoCompat.isSuspended(applicationInfo)) {
            return true;
        }
        return ApplicationInfoCompat.isHidden(applicationInfo);
    }

    /**
     * Fork: the freezing method to use for {@code packageName} — the method the user
     * remembered for this app, else the global default.
     * <p>
     * This is the resolution order App info, the 盗み見 tab, the freeze shortcuts and
     * batch freeze have always applied by open-coding it. It lives here so that the
     * two snowflakes that used to skip straight to the global default — the main list
     * and the battery panel — cannot disagree with them.
     */
    @WorkerThread
    @FreezeMethod
    public static int resolveFreezeMethod(@Nullable String packageName) {
        Integer stored = loadFreezeMethod(packageName);
        return stored != null ? stored : Prefs.Blocking.getDefaultFreezingMethod();
    }

    @Deprecated
    @WorkerThread
    public static void freeze(@NonNull String packageName, @UserIdInt int userId) throws RemoteException {
        freeze(packageName, userId, resolveFreezeMethod(packageName));
    }

    public static void freeze(@NonNull String packageName, @UserIdInt int userId, @FreezeMethod int freezeType)
            throws RemoteException {
        if (ProtectedAppsProfile.isProtected(packageName)) {
            throw new RemoteException(packageName + " is in the " + ProtectedAppsProfile.PROTECTED_PROFILE_NAME
                    + " profile and is protected from freezing.");
        }
        if (BuildConfig.APPLICATION_ID.equals(packageName) && userId == UserHandleHidden.myUserId()) {
            throw new RemoteException("Could not freeze myself.");
        }
        if (freezeType == FREEZE_HIDE) {
            if (SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.MANAGE_USERS)) {
                PackageManagerCompat.hidePackage(packageName, userId, true);
                return;
            }
            // No permission, fall-through
        } else if ((freezeType == FREEZE_SUSPEND || freezeType == FREEZE_ADV_SUSPEND) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (freezeType == FREEZE_ADV_SUSPEND) {
                // Force-stop app
                if (SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.FORCE_STOP_PACKAGES)) {
                    PackageManagerCompat.forceStopPackage(packageName, userId);
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.SUSPEND_APPS)) {
                    PackageManagerCompat.suspendPackages(new String[]{packageName}, userId, true);
                    return;
                }
                // No permission, fall-through
            } else {
                if (SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.MANAGE_USERS)) {
                    PackageManagerCompat.suspendPackages(new String[]{packageName}, userId, true);
                    return;
                }
                // No permission, fall-through
            }
        }
        PackageManagerCompat.setApplicationEnabledSetting(packageName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0, userId);
    }

    public static void unfreeze(@NonNull String packageName, @UserIdInt int userId) throws RemoteException {
        // Ignore checking preference, unfreeze for all types
        if (PackageManagerCompat.isPackageHidden(packageName, userId)) {
            PackageManagerCompat.hidePackage(packageName, userId, false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && PackageManagerCompat.isPackageSuspended(packageName, userId)) {
            PackageManagerCompat.suspendPackages(new String[]{packageName}, userId, false);
        }
        // Fork, +81: a DEVICE-POLICY suspension is a different slot. The platform
        // records suspension per suspending package, so the call above — made as
        // the shell — cannot clear one applied under 雫's admin: the snowflake
        // would appear to do nothing at all, for ever. Best-effort and deliberately
        // last, so an ordinary unfreeze on a phone with no Device Owner costs one
        // cheap refusal and nothing else.
        try {
            if (DevicePolicyBridge.isSuspended(packageName)) {
                DevicePolicyBridge.setSuspended(packageName, false);
            }
        } catch (Throwable ignore) {
            // No delegation, or no owner. Nothing to lift that we could have lifted.
        }
        if (PackageManagerCompat.getApplicationEnabledSetting(packageName, userId) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            PackageManagerCompat.setApplicationEnabledSetting(packageName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0, userId);
        }
    }
}
