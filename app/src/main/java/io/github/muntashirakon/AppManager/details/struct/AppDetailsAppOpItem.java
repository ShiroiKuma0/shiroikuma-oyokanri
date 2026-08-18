// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.details.struct;

import android.app.AppOpsManager;
import android.content.pm.PackageInfo;
import android.content.pm.PermissionInfo;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.annotation.WorkerThread;
import androidx.core.content.pm.PermissionInfoCompat;

import java.util.List;
import java.util.Objects;

import io.github.muntashirakon.AppManager.compat.AppOpsManagerCompat;
import io.github.muntashirakon.AppManager.compat.ManifestCompat;
import io.github.muntashirakon.AppManager.permission.DevelopmentPermission;
import io.github.muntashirakon.AppManager.permission.PermUtils;
import io.github.muntashirakon.AppManager.permission.Permission;
import io.github.muntashirakon.AppManager.permission.PermissionException;
import io.github.muntashirakon.AppManager.permission.ReadOnlyPermission;
import io.github.muntashirakon.AppManager.permission.RuntimePermission;

public class AppDetailsAppOpItem extends AppDetailsItem<Integer> {
    @Nullable
    public final Permission permission;
    @Nullable
    public final PermissionInfo permissionInfo;
    public final boolean isDangerous;
    public final boolean hasModifiablePermission;
    /**
     * Whether the permission is part of the app.
     */
    public final boolean appContainsPermission;

    @Nullable
    private AppOpsManagerCompat.OpEntry mOpEntry;

    public AppDetailsAppOpItem(@NonNull AppOpsManagerCompat.OpEntry opEntry) {
        this(opEntry.getOp());
        name = opEntry.getName();
        mOpEntry = opEntry;
    }

    public AppDetailsAppOpItem(int op) {
        super(op);
        name = AppOpsManagerCompat.opToName(op);
        mOpEntry = null;
        permissionInfo = null;
        permission = null;
        isDangerous = false;
        hasModifiablePermission = false;
        appContainsPermission = false;
    }

    public AppDetailsAppOpItem(@NonNull AppOpsManagerCompat.OpEntry opEntry, @NonNull PermissionInfo permissionInfo,
                               boolean isGranted, int permissionFlags, boolean appContainsPermission) {
        super(opEntry.getOp());
        name = opEntry.getName();
        mOpEntry = opEntry;
        this.permissionInfo = permissionInfo;
        this.appContainsPermission = appContainsPermission;
        isDangerous = PermissionInfoCompat.getProtection(permissionInfo) == PermissionInfo.PROTECTION_DANGEROUS;
        int protectionFlags = PermissionInfoCompat.getProtectionFlags(permissionInfo);
        if (isDangerous && PermUtils.systemSupportsRuntimePermissions()) {
            permission = new RuntimePermission(permissionInfo.name, isGranted, opEntry.getOp(), isAllowed(), permissionFlags);
        } else if ((protectionFlags & PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0) {
            permission = new DevelopmentPermission(permissionInfo.name, isGranted, opEntry.getOp(), isAllowed(), permissionFlags);
        } else {
            permission = new ReadOnlyPermission(permissionInfo.name, isGranted, opEntry.getOp(), isAllowed(), permissionFlags);
        }
        hasModifiablePermission = PermUtils.isModifiable(permission);
    }

    public AppDetailsAppOpItem(int op, @NonNull PermissionInfo permissionInfo,
                               boolean isGranted, int permissionFlags, boolean appContainsPermission) {
        super(op);
        name = AppOpsManagerCompat.opToName(op);
        mOpEntry = null;
        this.permissionInfo = permissionInfo;
        this.appContainsPermission = appContainsPermission;
        isDangerous = PermissionInfoCompat.getProtection(permissionInfo) == PermissionInfo.PROTECTION_DANGEROUS;
        int protectionFlags = PermissionInfoCompat.getProtectionFlags(permissionInfo);
        if (isDangerous && PermUtils.systemSupportsRuntimePermissions()) {
            permission = new RuntimePermission(permissionInfo.name, isGranted, op, isAllowed(), permissionFlags);
        } else if ((protectionFlags & PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0) {
            permission = new DevelopmentPermission(permissionInfo.name, isGranted, op, isAllowed(), permissionFlags);
        } else {
            permission = new ReadOnlyPermission(permissionInfo.name, isGranted, op, isAllowed(), permissionFlags);
        }
        hasModifiablePermission = PermUtils.isModifiable(permission);
    }

    public int getOp() {
        return item;
    }

    @AppOpsManagerCompat.Mode
    public int getMode() {
        if (mOpEntry != null) {
            return mOpEntry.getMode();
        }
        return AppOpsManagerCompat.opToDefaultMode(getOp());
    }

    public long getDuration() {
        if (mOpEntry != null) {
            return mOpEntry.getDuration();
        }
        return 0L;
    }

    public long getTime() {
        if (mOpEntry != null) {
            return mOpEntry.getTime();
        }
        return 0L;
    }

    public long getRejectTime() {
        if (mOpEntry != null) {
            return mOpEntry.getRejectTime();
        }
        return 0L;
    }

    /**
     * Fork: whether the platform holds a record for this op at all.
     * <p>
     * Load-bearing for the Snooping tab's "used / denied" line: with an entry, a
     * zero time means <i>never</i>; without one it may mean never or it may mean we
     * never got to ask. The two must not render the same — see
     * {@code SnoopingActivityTimes}.
     */
    public boolean hasOpEntry() {
        return mOpEntry != null;
    }

    /**
     * Fork: whether foreground and background can be told apart for this op.
     * <p>
     * Below Android P an {@code OpEntry} carries one undifferentiated timestamp and
     * {@link AppOpsManagerCompat.OpEntry#getLastAccessBackgroundTime} answers with
     * it, so a "(background)" marker there would be invented rather than measured.
     */
    public boolean hasBackgroundTimeSplit() {
        return mOpEntry != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
    }

    /** Fork: last use while the app was in a background uid state. See {@link #hasBackgroundTimeSplit}. */
    public long getLastAccessBackgroundTime() {
        if (hasBackgroundTimeSplit()) {
            return mOpEntry.getLastAccessBackgroundTime(AppOpsManagerCompat.OP_FLAGS_ALL);
        }
        return 0L;
    }

    /** Fork: last denial while the app was in a background uid state. */
    public long getLastRejectBackgroundTime() {
        if (hasBackgroundTimeSplit()) {
            return mOpEntry.getLastRejectBackgroundTime(AppOpsManagerCompat.OP_FLAGS_ALL);
        }
        return 0L;
    }

    public boolean isRunning() {
        return mOpEntry != null && mOpEntry.isRunning();
    }

    public boolean isAllowed() {
        boolean isAllowed = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isAllowed = getMode() == AppOpsManager.MODE_FOREGROUND;
        }
        isAllowed |= getMode() == AppOpsManager.MODE_ALLOWED;
        // Special case for default
        if (getMode() == AppOpsManager.MODE_DEFAULT) {
            isAllowed |= (permission != null && permission.isGranted());
        }
        return isAllowed;
    }

    /**
     * Allow the app op.
     *
     * <p>This also automatically grants the permission associated with the app op.
     */
    @RequiresPermission(allOf = {
            "android.permission.MANAGE_APP_OPS_MODES",
            ManifestCompat.permission.GRANT_RUNTIME_PERMISSIONS,
    })
    @WorkerThread
    public void allowAppOp(@NonNull PackageInfo packageInfo, @NonNull AppOpsManagerCompat appOpsManager)
            throws PermissionException {
        if (hasModifiablePermission && permission != null) {
            PermUtils.grantPermission(packageInfo, permission, appOpsManager, true, true);
        } else {
            PermUtils.allowAppOp(appOpsManager, getOp(), packageInfo.packageName, packageInfo.applicationInfo.uid);
        }
        invalidate(appOpsManager, packageInfo);
    }

    /**
     * Disallow the app op.
     *
     * <p>This also revokes the permission associated with the app op.
     */
    @RequiresPermission(allOf = {
            "android.permission.MANAGE_APP_OPS_MODES",
            ManifestCompat.permission.REVOKE_RUNTIME_PERMISSIONS,
    })
    @WorkerThread
    public void disallowAppOp(@NonNull PackageInfo packageInfo, @NonNull AppOpsManagerCompat appOpsManager)
            throws PermissionException {
        if (hasModifiablePermission && permission != null) {
            PermUtils.revokePermission(packageInfo, permission, appOpsManager, true);
        } else {
            PermUtils.disallowAppOp(appOpsManager, getOp(), packageInfo.packageName, packageInfo.applicationInfo.uid);
        }
        invalidate(appOpsManager, packageInfo);
    }

    /**
     * Set mode for app op.
     *
     * <p>This also grants/revoke the permission associated with the app op.
     */
    @RequiresPermission(allOf = {
            "android.permission.MANAGE_APP_OPS_MODES",
            ManifestCompat.permission.GRANT_RUNTIME_PERMISSIONS,
            ManifestCompat.permission.REVOKE_RUNTIME_PERMISSIONS,
    })
    @WorkerThread
    public void setAppOp(@NonNull PackageInfo packageInfo, @NonNull AppOpsManagerCompat appOpsManager,
                         @AppOpsManagerCompat.Mode int mode) throws PermissionException {
        if (hasModifiablePermission && permission != null) {
            boolean isAllowed = mode == AppOpsManager.MODE_ALLOWED;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isAllowed |= mode == AppOpsManager.MODE_FOREGROUND;
            }
            if (isAllowed) {
                PermUtils.grantPermission(packageInfo, permission, appOpsManager, true, true);
            } else {
                PermUtils.revokePermission(packageInfo, permission, appOpsManager, true);
            }
        }
        PermUtils.setAppOpMode(appOpsManager, getOp(), packageInfo.packageName, packageInfo.applicationInfo.uid, mode);
        invalidate(appOpsManager, packageInfo);
    }

    @RequiresPermission("android.permission.MANAGE_APP_OPS_MODES")
    public void invalidate(@NonNull AppOpsManagerCompat appOpsManager, @NonNull PackageInfo packageInfo)
            throws PermissionException {
        try {
            List<AppOpsManagerCompat.OpEntry> opEntryList = appOpsManager.getOpsForPackage(packageInfo.applicationInfo.uid,
                    packageInfo.packageName, new int[]{getOp()}).get(0).getOps();
            mOpEntry = !opEntryList.isEmpty() ? opEntryList.get(0) : null;
        } catch (Exception e) {
            throw new PermissionException(e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AppDetailsAppOpItem)) return false;
        if (!super.equals(o)) return false;
        AppDetailsAppOpItem that = (AppDetailsAppOpItem) o;
        return Objects.equals(item, that.item);
    }

    @Override
    public int hashCode() {
        return Objects.hash(item);
    }
}
