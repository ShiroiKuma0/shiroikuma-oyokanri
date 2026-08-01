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
import io.github.muntashirakon.AppManager.snooping.lever.SnoopingLever;
import io.github.muntashirakon.AppManager.snooping.lever.SnoopingLevers;

/**
 * Fork: turns the {@link SnoopingCatalog} into concrete, togglable rows for one
 * installed package. The single place that decides <em>what the Snooping tab
 * shows</em>, shared by the tab itself and by {@link SnoopingEnforcer} so the
 * on-install replay can never disagree with what the UI offered.
 * <p>
 * Four filters run here, in this order:
 * <ol>
 *   <li><b>Can we move it?</b> A row survives only if we hold the privileges to
 *       actually change it ({@link AppDetailsSnoopingItem#isModifiable()}) and,
 *       for a permission-only row, only if the write can land on this particular
 *       app at all ({@link #canWritePermission}) — privileges are not the same
 *       question as whether the platform will act on them. This is the whole
 *       point of the tab — no decorative switches.</li>
 *   <li><b>Does it exist for this app?</b> A lever row (network policy,
 *       accessibility service, notification listener, assistant role, doze
 *       exemption) is dropped when the app has nothing for it to act on.</li>
 *   <li><b>Is it worth showing?</b> Capabilities the app requests, and
 *       capabilities no permission gates (so the app can use them without asking),
 *       always show. Permission-gated capabilities the app never requested are
 *       held back behind "show all", where they act as pre-sets that land if a
 *       future update of the app starts asking for them.</li>
 *   <li><b>Can it still snoop?</b> A capability that is blocked <em>and</em> has
 *       proven it cannot be turned on here ({@link SnoopingImmovable}) is
 *       permanently safe — neither snooping nor able to be made to — so it is
 *       dropped as well.</li>
 * </ol>
 */
public final class SnoopingResolver {
    private SnoopingResolver() {
    }

    /**
     * Build the rows for a package.
     *
     * @param includeNotRequested whether to include permission-gated capabilities
     *                            the app does not request, and capabilities known
     *                            to be stuck off ("show all")
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

        Map<String, Integer> stored = SnoopingPrefs.getSettings(packageName);
        SnoopingReachability reachability = SnoopingReachability.forPackage(packageInfo, userId);

        for (SnoopingCatalog.Resolved capability : SnoopingCatalog.resolved()) {
            if (!includeNotRequested && !reachability.canEverUse(capability)) {
                // The app's own manifest rules this capability out — no
                // permission, no bound service, or privileged-only — so it is
                // neither snooping nor able to be made to, whatever the op says.
                // Cheaper and more honest than SnoopingImmovable: no failed write
                // to learn from, and an update that adds the missing declaration
                // brings the row back on the next load, by itself.
                continue;
            }
            AppDetailsSnoopingItem item = build(capability, packageInfo, userId, requestedPermissions,
                    configuredOps, canGetGrantRevoke);
            if (item == null || !item.isModifiable(packageInfo, userId)) {
                // Either unsupported here, or we cannot actually change it —
                // asked per app and per device, not merely per privilege.
                continue;
            }
            if (item.tier == AppDetailsSnoopingItem.TIER_NOT_REQUESTED && !includeNotRequested) {
                continue;
            }
            // Ask the platform what it actually enforces, rather than trusting
            // the stored op entry — see AppDetailsSnoopingItem#refreshEffectiveMode.
            item.refreshEffectiveMode(appOpsManager, packageInfo);
            item.refreshEffectivePermission(appOpsManager, packageInfo);
            item.refreshLeverState(packageInfo, userId);
            item.refreshPolicyLock(packageInfo, requestedPermissions);
            item.storedState = stored.get(capability.entry.id);
            if (!includeNotRequested && !item.isAllowed()
                    && SnoopingImmovable.isMarked(packageName, capability.entry.id)) {
                // Blocked, and a previous attempt proved it cannot be turned on
                // here: it can neither snoop nor be made to, so it is not this
                // page's business. Note the isAllowed() guard — a mark never
                // hides a capability that is currently allowed, so a stale one
                // can never conceal actual snooping. "Show all" reveals them.
                continue;
            }
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

        if (capability.entry.lever) {
            // A capability with no op and no permission: a system list, a network
            // policy, a role, the doze whitelist. The lever answers for itself
            // whether it means anything for this app — an app with no
            // accessibility service can never have one enabled, and a row for it
            // would be a switch with nothing behind it.
            SnoopingLever lever = SnoopingLevers.byId(capability.entry.id);
            if (lever == null || !lever.isApplicable(packageInfo, userId)) {
                return null;
            }
            return new AppDetailsSnoopingItem(capability, AppDetailsSnoopingItem.TIER_REQUESTED, null, null, lever);
        }

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
        if (!canWritePermission(packageInfo, permissionItem)) {
            return null;
        }
        return new AppDetailsSnoopingItem(capability, AppDetailsSnoopingItem.TIER_REQUESTED, null, permissionItem);
    }

    /**
     * Whether granting or revoking this permission can actually take on <em>this</em>
     * app, as opposed to merely being something we hold the privileges for.
     * <p>
     * <b>Landmine — measured on-device 2026-07-28.</b>
     * {@link AppDetailsPermissionItem#modifiable} is
     * {@link PermUtils#isModifiable(Permission)}: a check on our own privileges and
     * the permission's protection level. It says nothing about the app the write
     * lands on, and for a legacy app the write can be a guaranteed no-op.
     * {@link PermUtils#revokePermission} branches on
     * {@link PermUtils#supportsRuntimePermissions}: an app that predates runtime
     * permissions (target SDK ≤ 22) takes the compat branch, which can only act
     * <em>through the app-op</em> — it never clears the grant itself. So for a
     * permission with no app-op the branch does nothing at all, the permission is
     * still marked granted when {@code persistChanges} runs, and it is promptly
     * re-granted. Observed with {@code ACCESS_BACKGROUND_LOCATION} (no op exists
     * for it on any release) on {@code com.polyclock}, target SDK 22: the switch
     * could not be moved by us, and there is no lever that could move it.
     * <p>
     * Expressed as the platform's own rule rather than a list of ids, so a
     * permission that gains an op — or an app that raises its target SDK — comes
     * back on the next load with no maintenance.
     */
    private static boolean canWritePermission(@NonNull PackageInfo packageInfo,
                                              @NonNull AppDetailsPermissionItem permissionItem) {
        if (packageInfo.applicationInfo == null) {
            // Cannot tell — keep the row rather than hide a working lever.
            return true;
        }
        return PermUtils.supportsRuntimePermissions(packageInfo.applicationInfo)
                || permissionItem.permission.affectsAppOp();
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
