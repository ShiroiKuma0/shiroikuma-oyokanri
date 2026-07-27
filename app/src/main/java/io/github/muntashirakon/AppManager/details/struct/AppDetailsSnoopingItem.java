// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.details.struct;

import android.app.AppOpsManager;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.RemoteException;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import io.github.muntashirakon.AppManager.compat.AppOpsManagerCompat;
import io.github.muntashirakon.AppManager.permission.PermUtils;
import io.github.muntashirakon.AppManager.permission.PermissionException;
import io.github.muntashirakon.AppManager.self.SelfPermissions;
import io.github.muntashirakon.AppManager.snooping.SnoopingCatalog;

/**
 * Fork: one row of the app-details <em>Snooping</em> tab.
 * <p>
 * This is a thin façade over the machinery that already exists: an op-backed
 * capability delegates to {@link AppDetailsAppOpItem} (which knows to move the
 * linked runtime permission along with the op, update permission flags, and kill
 * the app when a legacy app's op changes), and the rare permission-only
 * capability delegates to {@link AppDetailsPermissionItem}. Nothing here
 * re-implements grant/revoke — getting that subtly wrong is how a switch ends up
 * looking flipped while the app keeps its access.
 */
public class AppDetailsSnoopingItem extends AppDetailsItem<String> {
    @IntDef(value = {TIER_REQUESTED, TIER_UNGATED, TIER_NOT_REQUESTED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Tier {
    }

    /** The app asks for this, or its op is already at a non-default mode. */
    public static final int TIER_REQUESTED = 0;
    /** No manifest permission gates this op — the app can use it without asking. */
    public static final int TIER_UNGATED = 1;
    /** Permission-gated and not requested: blocking is a pre-set for a future update. */
    public static final int TIER_NOT_REQUESTED = 2;

    @NonNull
    public final SnoopingCatalog.Resolved capability;
    @Tier
    public final int tier;
    /** Set for op-backed capabilities (the overwhelming majority). */
    @Nullable
    public final AppDetailsAppOpItem opItem;
    /** Set instead of {@link #opItem} for permission-only capabilities. */
    @Nullable
    public final AppDetailsPermissionItem permissionItem;
    /**
     * Stored decision for this package: {@code true} allowed, {@code false}
     * blocked, {@code null} not managed. Distinct from the live state — a
     * capability can be blocked right now without our having asked for it.
     */
    @Nullable
    public Boolean storedDecision;

    /**
     * The mode the platform actually enforces, from
     * {@link AppOpsManagerCompat#checkOperation} — <b>not</b> the stored per-op
     * entry. The two disagree more often than is comfortable (the stored entry is
     * absent until something writes it, while the enforced mode already resolves
     * through uid modes and op switching), and a row that reports the stored one
     * can claim "Allowed" for something the system is already denying. Falls back
     * to the stored mode when the platform refuses to tell us.
     */
    private int mEffectiveMode;
    private boolean mHasEffectiveMode;

    public AppDetailsSnoopingItem(@NonNull SnoopingCatalog.Resolved capability, @Tier int tier,
                                  @Nullable AppDetailsAppOpItem opItem,
                                  @Nullable AppDetailsPermissionItem permissionItem) {
        super(capability.entry.id);
        this.capability = capability;
        this.tier = tier;
        this.opItem = opItem;
        this.permissionItem = permissionItem;
        name = capability.entry.id;
    }

    /**
     * Whether we can really move this switch on this device with the privileges
     * we currently hold. A capability that fails this check is never listed —
     * 白い熊's rule for the tab: no switches that cannot actually do anything.
     */
    public boolean isModifiable() {
        if (opItem != null) {
            // The op path either grants/revokes the linked permission (which
            // needs the permission privileges) or sets the op mode directly.
            return opItem.hasModifiablePermission || SelfPermissions.canModifyAppOpMode();
        }
        return permissionItem != null && permissionItem.modifiable;
    }

    /**
     * Re-read the mode the platform enforces for this op. Cheap enough per row
     * (one binder call), and the only honest source for what the switch should
     * show. Never throws: an unreadable op simply falls back to the stored mode.
     */
    @WorkerThread
    public void refreshEffectiveMode(@NonNull AppOpsManagerCompat appOpsManager, @NonNull PackageInfo packageInfo) {
        if (opItem == null || packageInfo.applicationInfo == null) {
            return;
        }
        try {
            mEffectiveMode = appOpsManager.checkOperation(opItem.getOp(), packageInfo.applicationInfo.uid,
                    packageInfo.packageName);
            mHasEffectiveMode = true;
        } catch (Throwable th) {
            mHasEffectiveMode = false;
        }
    }

    /** True when the app currently has this capability. */
    public boolean isAllowed() {
        if (opItem == null) {
            return permissionItem != null && permissionItem.isGranted();
        }
        if (!mHasEffectiveMode) {
            return opItem.isAllowed();
        }
        if (mEffectiveMode == AppOpsManager.MODE_DEFAULT) {
            // Nothing decided at the op level: the permission grant is the answer.
            return opItem.permission != null ? opItem.permission.isGranted() : opItem.isAllowed();
        }
        boolean allowed = mEffectiveMode == AppOpsManager.MODE_ALLOWED;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            allowed |= mEffectiveMode == AppOpsManager.MODE_FOREGROUND;
        }
        return allowed;
    }

    /** The live app-op mode, or {@link AppOpsManagerCompat#OP_NONE} for permission-only rows. */
    public int getMode() {
        if (opItem == null) {
            return AppOpsManagerCompat.OP_NONE;
        }
        return mHasEffectiveMode ? mEffectiveMode : opItem.getMode();
    }

    /** The manifest permission behind this capability, or {@code null} if none gates it. */
    @Nullable
    public String getPermissionName() {
        return capability.permission;
    }

    /** True when the live state differs from what we stored, i.e. the setting has drifted. */
    public boolean isDrifted() {
        return storedDecision != null && storedDecision != isAllowed();
    }

    /**
     * Whether this capability is allowed when nobody has touched it — the state a
     * fresh install lands in — or {@code null} when the platform's answer is not
     * unambiguous.
     * <p>
     * Callers must treat {@code null} as "assume nothing": it is better to store a
     * redundant decision, or to leave a row unmarked, than to drop a real one.
     */
    @Nullable
    public Boolean defaultAllowed() {
        if (opItem != null) {
            int mode;
            try {
                mode = AppOpsManagerCompat.opToDefaultMode(opItem.getOp());
            } catch (Throwable th) {
                return null;
            }
            if (mode == AppOpsManager.MODE_ALLOWED
                    || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mode == AppOpsManager.MODE_FOREGROUND)) {
                return Boolean.TRUE;
            }
            if (mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED) {
                return Boolean.FALSE;
            }
            // MODE_DEFAULT: the linked permission decides. A dangerous permission
            // starts out denied; for anything else we cannot say.
            return opItem.isDangerous ? Boolean.FALSE : null;
        }
        if (permissionItem != null) {
            return permissionItem.isDangerous ? Boolean.FALSE : null;
        }
        return null;
    }

    /**
     * Whether {@code allowed} is simply the untouched state. Nothing worth
     * storing: a decision that matches the default would only re-assert what a
     * fresh install does anyway, and would travel in an export as noise.
     */
    public boolean isDefaultState(boolean allowed) {
        Boolean defaultAllowed = defaultAllowed();
        return defaultAllowed != null && defaultAllowed == allowed;
    }

    /**
     * Whether the live state is a deliberate departure from the default — what
     * the row's highlight marks, so a capability that is off <em>because you
     * turned it off</em> is distinguishable at a glance from one that was never
     * on.
     */
    public boolean isChangedFromDefault() {
        Boolean defaultAllowed = defaultAllowed();
        return defaultAllowed != null && defaultAllowed != isAllowed();
    }

    /**
     * Whether the platform now enforces what {@link #setAllowed} was asked for.
     * <p>
     * Callers use this to decide whether a decision is worth recording: a stored
     * decision that never took is worse than none, because the row then reads
     * "Blocked · saved: allow" and the user has no way to tell a refused write
     * from a working one. Returns {@code true} whenever we <em>cannot</em> tell —
     * only a positive contradiction counts as failure.
     */
    public boolean matchesRequestedState(boolean requested) {
        if (opItem == null || !mHasEffectiveMode) {
            // Permission-only row, or the platform would not answer: no evidence
            // either way, so do not call a possibly-good write a failure.
            return true;
        }
        return isAllowed() == requested;
    }

    /**
     * Last resort after a write that did not move the enforced mode: force the
     * package-level entry to the target and re-read. The permission path
     * (grant/revoke, which may legitimately land on {@code MODE_FOREGROUND}) is
     * left alone unless it demonstrably failed, so nothing nuanced is clobbered.
     */
    @WorkerThread
    private void repairIfUnchanged(@NonNull PackageInfo packageInfo, @NonNull AppOpsManagerCompat appOpsManager,
                                   boolean allowed) {
        if (opItem == null || packageInfo.applicationInfo == null || matchesRequestedState(allowed)) {
            return;
        }
        try {
            appOpsManager.setPackageMode(opItem.getOp(), packageInfo.applicationInfo.uid, packageInfo.packageName,
                    allowed ? AppOpsManager.MODE_ALLOWED : AppOpsManager.MODE_IGNORED);
            opItem.invalidate(appOpsManager, packageInfo);
            refreshEffectiveMode(appOpsManager, packageInfo);
        } catch (Throwable ignore) {
            // Nothing more to try; the caller reports the failure to the user.
        }
    }

    /**
     * Allow or block the capability, moving the op and its linked permission
     * together. Callers persist the decision separately — this only touches the
     * live system state.
     * <p>
     * <b>Landmine:</b> a {@link #TIER_NOT_REQUESTED} row must move the app-op
     * <em>alone</em>. Its permission is one the app never declared, and asking
     * the platform to grant or revoke an undeclared permission throws
     * ("Unknown permission … for package …") — which would surface as a failed
     * toggle and, worse, lose the pre-set we were trying to record. App-op modes
     * carry no such restriction: they can be set for any op on any package,
     * which is exactly what makes the pre-set land the moment a future update
     * starts requesting the permission.
     */
    @WorkerThread
    public void setAllowed(@NonNull PackageInfo packageInfo, @NonNull AppOpsManagerCompat appOpsManager,
                           boolean allowed) throws RemoteException, PermissionException {
        if (opItem != null) {
            boolean movePermissionToo = tier != TIER_NOT_REQUESTED
                    && opItem.hasModifiablePermission && opItem.permission != null;
            if (movePermissionToo) {
                if (allowed) {
                    opItem.allowAppOp(packageInfo, appOpsManager);
                } else {
                    opItem.disallowAppOp(packageInfo, appOpsManager);
                }
            } else {
                // Write straight through, deliberately bypassing
                // PermUtils.setAppOpMode's "already at this mode, skip it"
                // shortcut. That shortcut compares against checkOperation — the
                // EFFECTIVE mode — and so silently swallows the write whenever
                // the effective and stored modes disagree, which is exactly what
                // left PHONE_CALL_MICROPHONE and PHONE_CALL_CAMERA stuck on
                // 4.1.0+6. A redundant write costs nothing; a skipped one looks
                // like a broken switch.
                //
                // Both levels, not just the uid one — see
                // AppOpsManagerCompat#setModeBothLevels for why a uid-only write
                // cannot lift a package-level block (4.1.0+8).
                appOpsManager.setModeBothLevels(opItem.getOp(), packageInfo.applicationInfo.uid,
                        packageInfo.packageName, allowed ? AppOpsManager.MODE_ALLOWED : AppOpsManager.MODE_IGNORED);
                opItem.invalidate(appOpsManager, packageInfo);
            }
            refreshEffectiveMode(appOpsManager, packageInfo);
            repairIfUnchanged(packageInfo, appOpsManager, allowed);
            return;
        }
        if (permissionItem == null) {
            throw new PermissionException("Snooping item " + capability.entry.id + " has no lever");
        }
        if (allowed) {
            PermUtils.grantPermission(packageInfo, permissionItem.permission, appOpsManager, true, true);
        } else {
            PermUtils.revokePermission(packageInfo, permissionItem.permission, appOpsManager, true);
        }
    }
}
