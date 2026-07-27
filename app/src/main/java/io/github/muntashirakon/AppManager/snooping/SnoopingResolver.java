// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.snooping;

import android.annotation.UserIdInt;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.content.pm.PermissionInfoCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.muntashirakon.AppManager.compat.AppOpsManagerCompat;
import io.github.muntashirakon.AppManager.compat.PermissionCompat;
import io.github.muntashirakon.AppManager.details.struct.AppDetailsAppOpItem;
import io.github.muntashirakon.AppManager.details.struct.AppDetailsPermissionItem;
import io.github.muntashirakon.AppManager.details.struct.AppDetailsSnoopingItem;
import io.github.muntashirakon.AppManager.permission.DevelopmentPermission;
import io.github.muntashirakon.AppManager.permission.PermUtils;
import io.github.muntashirakon.AppManager.permission.Permission;
import io.github.muntashirakon.AppManager.permission.ReadOnlyPermission;
import io.github.muntashirakon.AppManager.permission.RuntimePermission;
import io.github.muntashirakon.AppManager.self.SelfPermissions;

/**
 * Fork: turns the {@link SnoopingCatalog} into concrete, togglable rows for one
 * installed package. The single place that decides <em>what the Snooping tab
 * shows</em>, shared by the tab itself and by {@link SnoopingEnforcer} so the
 * on-install replay can never disagree with what the UI offered.
 * <p>
 * Two filters run here, in this order:
 * <ol>
 *   <li><b>Can we move it?</b> A row survives only if we hold the privileges to
 *       actually change it ({@link AppDetailsSnoopingItem#isModifiable()}).
 *       This is the whole point of the tab — no decorative switches.</li>
 *   <li><b>Is it worth showing?</b> Capabilities the app requests, and
 *       capabilities no permission gates (so the app can use them without asking),
 *       always show. Permission-gated capabilities the app never requested are
 *       held back behind "show all", where they act as pre-sets that land if a
 *       future update of the app starts asking for them.</li>
 * </ol>
 */
public final class SnoopingResolver {
    private SnoopingResolver() {
    }

    /**
     * Build the rows for a package.
     *
     * @param includeNotRequested whether to include permission-gated capabilities
     *                            the app does not request ("show all")
     */
    @WorkerThread
    @NonNull
    public static List<AppDetailsSnoopingItem> resolve(@NonNull PackageInfo packageInfo,
                                                       @UserIdInt int userId,
                                                       @NonNull AppOpsManagerCompat appOpsManager,
                                                       boolean includeNotRequested) {
        List<AppDetailsSnoopingItem> items = new ArrayList<>();
        String packageName = packageInfo.packageName;
        int uid = packageInfo.applicationInfo.uid;
        Set<String> requestedPermissions = packageInfo.requestedPermissions != null
                ? new HashSet<>(Arrays.asList(packageInfo.requestedPermissions))
                : new HashSet<>();
        boolean canGetGrantRevoke = SelfPermissions.checkGetGrantRevokeRuntimePermissions();

        // Ops that already carry an explicit mode for this package. An op set to
        // something other than its default is a decision somebody made, so the
        // row is relevant even when the permission was never requested.
        Map<Integer, AppOpsManagerCompat.OpEntry> configuredOps = new HashMap<>();
        try {
            for (AppOpsManagerCompat.OpEntry entry : AppOpsManagerCompat
                    .getConfiguredOpsForPackage(appOpsManager, packageName, uid)) {
                if (!configuredOps.containsKey(entry.getOp())) {
                    configuredOps.put(entry.getOp(), entry);
                }
            }
        } catch (Throwable ignore) {
            // No app-ops visibility; op rows fall back to their default modes.
        }

        Map<String, Boolean> stored = SnoopingPrefs.getSettings(packageName);

        for (SnoopingCatalog.Resolved capability : SnoopingCatalog.resolved()) {
            AppDetailsSnoopingItem item = build(capability, packageInfo, userId, requestedPermissions,
                    configuredOps, canGetGrantRevoke);
            if (item == null || !item.isModifiable()) {
                // Either unsupported here or we cannot actually change it.
                continue;
            }
            if (item.tier == AppDetailsSnoopingItem.TIER_NOT_REQUESTED && !includeNotRequested) {
                continue;
            }
            // Ask the platform what it actually enforces, rather than trusting
            // the stored op entry — see AppDetailsSnoopingItem#refreshEffectiveMode.
            item.refreshEffectiveMode(appOpsManager, packageInfo);
            item.storedDecision = stored.get(capability.entry.id);
            items.add(item);
        }
        return items;
    }

    @Nullable
    private static AppDetailsSnoopingItem build(@NonNull SnoopingCatalog.Resolved capability,
                                                @NonNull PackageInfo packageInfo,
                                                @UserIdInt int userId,
                                                @NonNull Set<String> requestedPermissions,
                                                @NonNull Map<Integer, AppOpsManagerCompat.OpEntry> configuredOps,
                                                boolean canGetGrantRevoke) {
        String permissionName = capability.permission;
        boolean requested = permissionName != null && requestedPermissions.contains(permissionName);

        if (capability.op != AppOpsManagerCompat.OP_NONE) {
            AppOpsManagerCompat.OpEntry opEntry = configuredOps.get(capability.op);
            boolean configured = opEntry != null
                    && opEntry.getMode() != AppOpsManagerCompat.opToDefaultMode(capability.op);
            AppDetailsAppOpItem opItem = buildOpItem(capability, opEntry, packageInfo, userId,
                    permissionName, requestedPermissions, canGetGrantRevoke);
            if (opItem == null) {
                return null;
            }
            int tier;
            if (requested || configured) {
                tier = AppDetailsSnoopingItem.TIER_REQUESTED;
            } else if (capability.isUngated()) {
                tier = AppDetailsSnoopingItem.TIER_UNGATED;
            } else {
                tier = AppDetailsSnoopingItem.TIER_NOT_REQUESTED;
            }
            return new AppDetailsSnoopingItem(capability, tier, opItem, null);
        }

        // Permission-only capability (no app-op exists for it on this platform).
        // Unlike an op-backed row, there is no lever we can pre-set here: the
        // platform refuses to grant or revoke a permission the app never
        // declared. So these appear only when the app actually requests them —
        // a switch that could not move would break the tab's one promise.
        if (permissionName == null || !requested) {
            return null;
        }
        AppDetailsPermissionItem permissionItem = buildPermissionItem(permissionName, packageInfo, userId,
                true, canGetGrantRevoke);
        if (permissionItem == null) {
            return null;
        }
        return new AppDetailsSnoopingItem(capability, AppDetailsSnoopingItem.TIER_REQUESTED, null, permissionItem);
    }

    @Nullable
    private static AppDetailsAppOpItem buildOpItem(@NonNull SnoopingCatalog.Resolved capability,
                                                   @Nullable AppOpsManagerCompat.OpEntry opEntry,
                                                   @NonNull PackageInfo packageInfo,
                                                   @UserIdInt int userId,
                                                   @Nullable String permissionName,
                                                   @NonNull Set<String> requestedPermissions,
                                                   boolean canGetGrantRevoke) {
        try {
            if (permissionName == null) {
                // Ungated op — no permission to move alongside it.
                return opEntry != null
                        ? new AppDetailsAppOpItem(opEntry)
                        : new AppDetailsAppOpItem(capability.op);
            }
            boolean isGranted = PermissionCompat.checkPermission(permissionName, packageInfo.packageName, userId)
                    == PackageManager.PERMISSION_GRANTED;
            int permissionFlags = canGetGrantRevoke
                    ? PermissionCompat.getPermissionFlags(permissionName, packageInfo.packageName, userId)
                    : PermissionCompat.FLAG_PERMISSION_NONE;
            PermissionInfo permissionInfo = PermissionCompat.getPermissionInfo(permissionName,
                    packageInfo.packageName, 0);
            if (permissionInfo == null) {
                permissionInfo = new PermissionInfo();
                permissionInfo.name = permissionName;
            }
            boolean appContainsPermission = requestedPermissions.contains(permissionName);
            return opEntry != null
                    ? new AppDetailsAppOpItem(opEntry, permissionInfo, isGranted, permissionFlags, appContainsPermission)
                    : new AppDetailsAppOpItem(capability.op, permissionInfo, isGranted, permissionFlags, appContainsPermission);
        } catch (Throwable th) {
            return null;
        }
    }

    /**
     * Mirrors {@code AppDetailsViewModel.getPermissionItem} for the permission-only
     * capabilities, which have no op to hang off.
     */
    @Nullable
    private static AppDetailsPermissionItem buildPermissionItem(@NonNull String permissionName,
                                                                @NonNull PackageInfo packageInfo,
                                                                @UserIdInt int userId,
                                                                boolean isRequested,
                                                                boolean canGetGrantRevoke) {
        try {
            PermissionInfo permissionInfo = PermissionCompat.getPermissionInfo(permissionName,
                    packageInfo.packageName, PackageManager.GET_META_DATA);
            if (permissionInfo == null) {
                // The platform does not define it — nothing to toggle.
                return null;
            }
            boolean isGranted = isRequested && PermissionCompat.checkPermission(permissionName,
                    packageInfo.packageName, userId) == PackageManager.PERMISSION_GRANTED;
            int permissionFlags = canGetGrantRevoke
                    ? PermissionCompat.getPermissionFlags(permissionName, packageInfo.packageName, userId)
                    : PermissionCompat.FLAG_PERMISSION_NONE;
            int appOp = AppOpsManagerCompat.permissionToOpCode(permissionName);
            int protection = PermissionInfoCompat.getProtection(permissionInfo);
            int protectionFlags = PermissionInfoCompat.getProtectionFlags(permissionInfo);
            Permission permission;
            if (protection == PermissionInfo.PROTECTION_DANGEROUS && PermUtils.systemSupportsRuntimePermissions()) {
                permission = new RuntimePermission(permissionName, isGranted, appOp, isGranted, permissionFlags);
            } else if ((protectionFlags & PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0) {
                permission = new DevelopmentPermission(permissionName, isGranted, appOp, isGranted, permissionFlags);
            } else {
                permission = new ReadOnlyPermission(permissionName, isGranted, appOp, isGranted, permissionFlags);
            }
            AppDetailsPermissionItem item = new AppDetailsPermissionItem(permissionInfo, permission,
                    permissionInfo.flags);
            item.name = permissionName;
            return item;
        } catch (Throwable th) {
            return null;
        }
    }
}
