// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.details.struct;

import android.annotation.UserIdInt;
import android.app.AppOpsManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Build;
import android.os.RemoteException;
import android.os.UserHandleHidden;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.WorkerThread;
import androidx.core.content.pm.PermissionInfoCompat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Set;

import io.github.muntashirakon.AppManager.compat.AppOpsManagerCompat;
import io.github.muntashirakon.AppManager.compat.PermissionCompat;
import io.github.muntashirakon.AppManager.devicepolicy.DevicePolicyBridge;
import io.github.muntashirakon.AppManager.permission.PermUtils;
import io.github.muntashirakon.AppManager.permission.PermissionException;
import io.github.muntashirakon.AppManager.self.SelfPermissions;
import io.github.muntashirakon.AppManager.snooping.SnoopingCatalog;
import io.github.muntashirakon.AppManager.snooping.SnoopingState;
import io.github.muntashirakon.AppManager.snooping.lever.SnoopingLever;

/**
 * Fork: one row of the app-details <em>Snooping</em> tab.
 * <p>
 * A thin façade over three different mechanisms, so the tab does not have to care
 * which one a capability happens to use: an <b>app-op</b> row delegates to
 * {@link AppDetailsAppOpItem} (which knows to move the linked runtime permission
 * along with the op, update permission flags, and kill the app when a legacy
 * app's op changes), a <b>permission-only</b> row to
 * {@link AppDetailsPermissionItem}, and a <b>lever</b> row to a
 * {@link SnoopingLever} — a system list, a network policy, a role holder, the
 * doze whitelist. Nothing here re-implements grant/revoke; getting that subtly
 * wrong is how a switch ends up looking flipped while the app keeps its access.
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
    /** Set for op-backed capabilities (the majority). */
    @Nullable
    public final AppDetailsAppOpItem opItem;
    /** Set instead of {@link #opItem} for permission-only capabilities. */
    @Nullable
    public final AppDetailsPermissionItem permissionItem;
    /** Set instead of both for capabilities that are neither an op nor a permission. */
    @Nullable
    public final SnoopingLever lever;
    /**
     * Stored decision for this package: a {@link SnoopingState}, or {@code null}
     * for <i>not managed</i>. Distinct from the live state — a capability can be
     * blocked right now without our having asked for it.
     */
    @Nullable
    public Integer storedState;

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

    /**
     * The same idea for a permission-only row: what the platform reports
     * <em>now</em>, rather than the grant state captured when the row was built
     * and then mutated in memory by a write that may never have landed. Without
     * it a refused revoke reads back as a success and gets recorded as a decision.
     */
    private boolean mEffectiveGranted;
    private boolean mHasEffectiveGranted;

    /** …and for a lever row, whose state lives outside app-ops entirely. */
    @SnoopingState.State
    private int mLeverState = SnoopingState.ALLOWED;
    private boolean mHasLeverState;

    public AppDetailsSnoopingItem(@NonNull SnoopingCatalog.Resolved capability, @Tier int tier,
                                  @Nullable AppDetailsAppOpItem opItem,
                                  @Nullable AppDetailsPermissionItem permissionItem) {
        this(capability, tier, opItem, permissionItem, null);
    }

    public AppDetailsSnoopingItem(@NonNull SnoopingCatalog.Resolved capability, @Tier int tier,
                                  @Nullable AppDetailsAppOpItem opItem,
                                  @Nullable AppDetailsPermissionItem permissionItem,
                                  @Nullable SnoopingLever lever) {
        super(capability.entry.id);
        this.capability = capability;
        this.tier = tier;
        this.opItem = opItem;
        this.permissionItem = permissionItem;
        this.lever = lever;
        name = capability.entry.id;
    }

    /**
     * Whether we can really move this switch on this device with the privileges
     * we currently hold. A capability that fails this check is never listed —
     * 白い熊's rule for the tab: no switches that cannot actually do anything.
     */
    public boolean isModifiable() {
        if (lever != null) {
            return lever.isModifiable();
        }
        if (opItem != null) {
            // The op path either grants/revokes the linked permission (which
            // needs the permission privileges) or sets the op mode directly.
            return opItem.hasModifiablePermission || SelfPermissions.canModifyAppOpMode();
        }
        return permissionItem != null && permissionItem.modifiable;
    }

    /**
     * The same question asked <em>about one app</em>. A lever may hold the
     * privileges it needs and still be unable to move this particular package —
     * see {@link SnoopingLever#isModifiable(PackageInfo, int)}. The op and
     * permission paths already answer per-app elsewhere (the op's linked
     * permission, {@code SnoopingResolver#canWritePermission}), so only the lever
     * path has anything extra to say here.
     */
    @WorkerThread
    public boolean isModifiable(@NonNull PackageInfo packageInfo, int userId) {
        if (lever != null) {
            return lever.isModifiable(packageInfo, userId);
        }
        return isModifiable();
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

    /**
     * Re-read what the platform reports for a permission-only row. The grant
     * alone is not the answer: a legacy app's permission is revoked
     * <em>compat</em> style — the grant stays and the app-op is what actually
     * stops it — so where an op exists it has the last word.
     */
    @WorkerThread
    public void refreshEffectivePermission(@NonNull AppOpsManagerCompat appOpsManager,
                                           @NonNull PackageInfo packageInfo) {
        if (permissionItem == null || packageInfo.applicationInfo == null) {
            return;
        }
        try {
            int uid = packageInfo.applicationInfo.uid;
            int userId = UserHandleHidden.getUserId(uid);
            boolean granted = PermissionCompat.checkPermission(permissionItem.permission.getName(),
                    packageInfo.packageName, userId) == PackageManager.PERMISSION_GRANTED;
            if (granted && permissionItem.permission.affectsAppOp()) {
                int mode = appOpsManager.checkOperation(permissionItem.permission.getAppOp(), uid,
                        packageInfo.packageName);
                granted = mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT
                        || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mode == AppOpsManager.MODE_FOREGROUND);
            }
            mEffectiveGranted = granted;
            mHasEffectiveGranted = true;
        } catch (Throwable th) {
            mHasEffectiveGranted = false;
        }
    }

    /** Ask the lever what the system currently says. */
    @WorkerThread
    public void refreshLeverState(@NonNull PackageInfo packageInfo, @UserIdInt int userId) {
        if (lever == null) {
            return;
        }
        try {
            mLeverState = lever.getState(packageInfo, userId);
            mHasLeverState = true;
        } catch (Throwable th) {
            mHasLeverState = false;
        }
    }

    /** True when the app currently has this capability, in any degree. */
    public boolean isAllowed() {
        return SnoopingState.isAllowed(getState());
    }

    /**
     * The live state of the row, which is the one thing the UI binds to.
     * {@link SnoopingState#FOREGROUND} is reported only where the platform really
     * is enforcing {@code MODE_FOREGROUND}.
     */
    @SnoopingState.State
    public int getState() {
        if (lever != null) {
            // Unknown reads as allowed: never claim a capability is shut when we
            // could not find out.
            return mHasLeverState ? mLeverState : SnoopingState.ALLOWED;
        }
        if (opItem == null) {
            if (mHasEffectiveGranted) {
                return SnoopingState.fromAllowed(mEffectiveGranted);
            }
            return SnoopingState.fromAllowed(permissionItem != null && permissionItem.isGranted());
        }
        // LANDMINE — checkOperation never answers MODE_FOREGROUND (白い熊, +20):
        // AppOpsService runs the stored mode through UidState#evalMode before
        // returning it, which resolves foreground into ALLOWED or IGNORED
        // according to the app's process state *right now*. So the enforced mode
        // is the right source for "can it do this at this instant" and the wrong
        // one for "what did we set" — a row narrowed to foreground read back as
        // BLOCKED whenever the app happened to be in the background, which made
        // matchesRequestedState(FOREGROUND) permanently false. The stored entry
        // (uid mode first, package mode second — see getOpsForPackage) is the raw
        // value and the only place the third state is visible.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && opItem.getMode() == AppOpsManager.MODE_FOREGROUND) {
            return SnoopingState.FOREGROUND;
        }
        if (!mHasEffectiveMode) {
            return SnoopingState.fromAllowed(opItem.isAllowed());
        }
        if (mEffectiveMode == AppOpsManager.MODE_DEFAULT) {
            // Nothing decided at the op level: the permission grant is the answer.
            boolean granted = opItem.permission != null ? opItem.permission.isGranted() : opItem.isAllowed();
            return SnoopingState.fromAllowed(granted);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mEffectiveMode == AppOpsManager.MODE_FOREGROUND) {
            return SnoopingState.FOREGROUND;
        }
        return SnoopingState.fromAllowed(mEffectiveMode == AppOpsManager.MODE_ALLOWED);
    }

    /**
     * Whether this capability can be narrowed to "only while the app is on
     * screen" rather than simply allowed or blocked.
     * <p>
     * <b>Always false for an app-op row, and that is a finding, not a
     * simplification</b> (白い熊, +22, measured on-device 2026-07-28). It is not
     * possible to put a permission-backed op into {@code MODE_FOREGROUND} by
     * writing the op, because <b>the op mode of a permission-backed op is not
     * independently settable at all</b> — it is a projection of the permission
     * grant that {@code PermissionPolicyService} re-derives and re-asserts.
     * Proof, on {@code org.localsend.localsend_app}:
     * <pre>
     * cmd appops set …       READ_EXTERNAL_STORAGE ignore     → "No operations."
     * cmd appops set …       READ_EXTERNAL_STORAGE foreground → "No operations."
     * cmd appops set --uid … READ_EXTERNAL_STORAGE foreground → "ignore"
     * </pre>
     * The write is accepted and thrown away; the mode that survives is whatever
     * the permission state implies. (The same run showed writes to
     * {@code CAMERA} on a package whose permission is denied being reverted to
     * {@code ignore} within one command.) This is also why <em>blocking</em> such
     * a row works: {@link AppDetailsAppOpItem#disallowAppOp} revokes the
     * permission, and the policy service then agrees with us.
     * <p>
     * The platform does have a real "only while in use" — but it is expressed by
     * the <em>permission</em>, not the op: an app granted
     * {@code ACCESS_FINE_LOCATION} without {@code ACCESS_BACKGROUND_LOCATION} is
     * put into {@code MODE_FOREGROUND} by the policy service itself. That state
     * already has a row of its own on this tab (<i>Location in the
     * background</i>), which is the honest place for it. For camera and
     * microphone there is no such permission pair: "while using the app" is the
     * only mode they have, enforced by the process-capability machinery without
     * anything for us to set.
     * <p>
     * A lever row is unaffected — {@link SnoopingLever#supportedStates()} decides
     * for itself, and the network row's middle rung is a genuinely different
     * mechanism (a per-uid policy) rather than an op mode.
     */
    public boolean supportsForeground() {
        return false;
    }

    /** The states this row can cycle through, most permissive first. */
    @NonNull
    public int[] supportedStates() {
        int[] base;
        if (lever != null) {
            // A lever's middle position is its own business — for the network row
            // it is a narrower network policy, not a foreground restriction.
            base = lever.supportedStates();
        } else {
            base = supportsForeground()
                    ? new int[]{SnoopingState.ALLOWED, SnoopingState.FOREGROUND, SnoopingState.BLOCKED}
                    : new int[]{SnoopingState.ALLOWED, SnoopingState.BLOCKED};
            // supportsForeground() is currently always false for op rows — see
            // its comment. The branch stays because the machinery is correct and
            // the platform may yet grow a settable foreground.
        }
        return base;
    }

    /**
     * A row-specific label for a state, or {@code 0} for the generic one. Only
     * levers have these: "Only while in use" describes what {@code MODE_FOREGROUND}
     * does, and would misdescribe a lever whose middle rung means something else.
     */
    @StringRes
    public int stateLabelRes(@SnoopingState.State int state) {
        return lever != null ? lever.stateLabelRes(state) : 0;
    }

    /** The next state a tap should move to, wrapping round. */
    @SnoopingState.State
    public int nextState() {
        int[] states = supportedStates();
        int current = getState();
        for (int i = 0; i < states.length; ++i) {
            if (states[i] == current) {
                return states[(i + 1) % states.length];
            }
        }
        // Current state is not one this row offers (a mode set by something else):
        // the useful next step is always to shut it.
        return SnoopingState.BLOCKED;
    }

    /** The manifest permission behind this capability, or {@code null} if none gates it. */
    @Nullable
    public String getPermissionName() {
        return capability.permission;
    }

    /**
     * Fork: whether a <b>device-policy lock</b> pins this capability — i.e. the
     * permission behind it is {@code POLICY_FIXED} by the Device Owner, so neither
     * the app nor Settings can put it back.
     * <p>
     * Read from the platform in {@link #refreshPolicyLock}, never from anything we
     * stored: a lock set by 白い熊 雫 directly, or left behind by an older build of
     * ours, has to show the padlock just the same.
     */
    public boolean policyLocked;

    /**
     * Whether a device-policy lock could land here at all.
     * <p>
     * <b>Landmine — measured on-device 2026-08-01.</b> Device policy's only per-app
     * lever is {@code setPermissionGrantState}, and the platform accepts it for
     * <b>dangerous runtime permissions only</b>. Having <em>a</em> permission is
     * not enough: {@code GET_USAGE_STATS} is backed by
     * {@code android.permission.PACKAGE_USAGE_STATS}, whose protection level is
     * {@code signature|privileged|development|appop|retailDemo} — so the lock was
     * offered on "Read app usage", the platform refused it, and the row said so
     * after the fact instead of never offering it. The protection level is
     * therefore resolved up front, exactly like {@code SnoopingResolver}'s
     * {@code canWritePermission} does for the write path.
     * <p>
     * The app must also <em>request</em> the permission: policy state on a
     * permission a package never declared is the same class of no-op.
     */
    public boolean policyLockable;

    @WorkerThread
    public void refreshPolicyLock(@NonNull PackageInfo packageInfo,
                                  @NonNull Set<String> requestedPermissions) {
        String permission = getPermissionName();
        if (permission == null) {
            policyLocked = false;
            policyLockable = false;
            return;
        }
        policyLocked = DevicePolicyBridge.isPermissionLocked(packageInfo.packageName, permission);
        policyLockable = requestedPermissions.contains(permission)
                && isDangerous(permission, packageInfo.packageName);
    }

    /**
     * Re-read <em>only</em> whether device policy pins this row.
     * <p>
     * The cheap half of {@link #refreshPolicyLock}: it needs nothing but the
     * package name, so a single row can be brought up to date straight after a
     * lock is applied, without re-resolving the whole page. Lockability does not
     * change under us — it is a property of the permission's protection level —
     * so it is deliberately left alone here.
     */
    @WorkerThread
    public void refreshPolicyLockState(@NonNull String packageName) {
        String permission = getPermissionName();
        policyLocked = permission != null
                && DevicePolicyBridge.isPermissionLocked(packageName, permission);
    }

    @WorkerThread
    private static boolean isDangerous(@NonNull String permission, @NonNull String packageName) {
        try {
            PermissionInfo permissionInfo = PermissionCompat.getPermissionInfo(permission, packageName, 0);
            return permissionInfo != null
                    && PermissionInfoCompat.getProtection(permissionInfo) == PermissionInfo.PROTECTION_DANGEROUS;
        } catch (Throwable th) {
            // Unknown protection level: do not offer a lock we cannot vouch for.
            return false;
        }
    }

    /** Whether a lock could be applied here at all — see {@link #policyLockable}. */
    public boolean isPolicyLockable() {
        return policyLockable;
    }

    /** True when the live state differs from what we stored, i.e. the setting has drifted. */
    public boolean isDrifted() {
        return storedState != null && storedState != getState();
    }

    /**
     * The state this capability is in when nobody has touched it — what a fresh
     * install lands in — or {@code null} when the platform's answer is not
     * unambiguous.
     * <p>
     * Callers must treat {@code null} as "assume nothing": it is better to store a
     * redundant decision, or to leave a row unmarked, than to drop a real one.
     */
    @Nullable
    public Integer defaultState() {
        if (lever != null) {
            Boolean allowed = lever.defaultAllowed();
            return allowed != null ? SnoopingState.fromAllowed(allowed) : null;
        }
        if (opItem != null) {
            int mode;
            try {
                mode = AppOpsManagerCompat.opToDefaultMode(opItem.getOp());
            } catch (Throwable th) {
                return null;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mode == AppOpsManager.MODE_FOREGROUND) {
                return SnoopingState.FOREGROUND;
            }
            if (mode == AppOpsManager.MODE_ALLOWED) {
                return SnoopingState.ALLOWED;
            }
            if (mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED) {
                return SnoopingState.BLOCKED;
            }
            // MODE_DEFAULT: the linked permission decides. A dangerous permission
            // starts out denied; for anything else we cannot say.
            return opItem.isDangerous ? SnoopingState.BLOCKED : null;
        }
        if (permissionItem != null) {
            return permissionItem.isDangerous ? SnoopingState.BLOCKED : null;
        }
        return null;
    }

    /**
     * Whether {@code state} is simply the untouched state. Nothing worth storing:
     * a decision that matches the default would only re-assert what a fresh
     * install does anyway, and would travel in an export as noise.
     */
    public boolean isDefaultState(@SnoopingState.State int state) {
        Integer defaultState = defaultState();
        return defaultState != null && defaultState == state;
    }

    /**
     * Whether the live state is a deliberate departure from the default — what
     * the row's highlight marks, so a capability that is off <em>because you
     * turned it off</em> is distinguishable at a glance from one that was never
     * on.
     */
    public boolean isChangedFromDefault() {
        Integer defaultState = defaultState();
        return defaultState != null && defaultState != getState();
    }

    /**
     * Whether the platform now enforces what {@link #setState} was asked for.
     * <p>
     * Callers use this to decide whether a decision is worth recording: a stored
     * decision that never took is worse than none, because the row then reads
     * "Blocked · saved: allow" and the user has no way to tell a refused write
     * from a working one. Returns {@code true} whenever we <em>cannot</em> tell —
     * only a positive contradiction counts as failure.
     */
    public boolean matchesRequestedState(@SnoopingState.State int requested) {
        if (lever != null) {
            return !mHasLeverState || mLeverState == requested;
        }
        if (opItem == null) {
            // Permission-only row: the platform's own answer if it gave one,
            // otherwise no evidence either way — do not call a possibly-good
            // write a failure.
            return !mHasEffectiveGranted || SnoopingState.fromAllowed(mEffectiveGranted) == requested;
        }
        if (!mHasEffectiveMode) {
            return true;
        }
        return getState() == requested;
    }

    /**
     * Last resort after a write that did not move the enforced mode: force the
     * package-level entry to the target and re-read. The permission path
     * (grant/revoke, which may legitimately land on {@code MODE_FOREGROUND}) is
     * left alone unless it demonstrably failed, so nothing nuanced is clobbered.
     */
    @WorkerThread
    private void repairIfUnchanged(@NonNull PackageInfo packageInfo, @NonNull AppOpsManagerCompat appOpsManager,
                                   @SnoopingState.State int state) {
        if (opItem == null || packageInfo.applicationInfo == null || matchesRequestedState(state)) {
            return;
        }
        try {
            appOpsManager.setPackageMode(opItem.getOp(), packageInfo.applicationInfo.uid, packageInfo.packageName,
                    modeFor(state));
            opItem.invalidate(appOpsManager, packageInfo);
            refreshEffectiveMode(appOpsManager, packageInfo);
        } catch (Throwable ignore) {
            // Nothing more to try; the caller reports the failure to the user.
        }
    }

    /** Backwards-compatible two-state entry point. */
    @WorkerThread
    public void setAllowed(@NonNull PackageInfo packageInfo, @NonNull AppOpsManagerCompat appOpsManager,
                           boolean allowed) throws RemoteException, PermissionException {
        setState(packageInfo, appOpsManager, SnoopingState.fromAllowed(allowed));
    }

    /**
     * Move the capability to {@code state}, taking the op and its linked
     * permission together. Callers persist the decision separately — this only
     * touches the live system state.
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
    public void setState(@NonNull PackageInfo packageInfo, @NonNull AppOpsManagerCompat appOpsManager,
                         @SnoopingState.State int state) throws RemoteException, PermissionException {
        boolean allowed = SnoopingState.isAllowed(state);
        if (lever != null) {
            int userId = packageInfo.applicationInfo != null
                    ? UserHandleHidden.getUserId(packageInfo.applicationInfo.uid)
                    : UserHandleHidden.myUserId();
            lever.setState(packageInfo, userId, state);
            refreshLeverState(packageInfo, userId);
            return;
        }
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
                        packageInfo.packageName, modeFor(state));
                opItem.invalidate(appOpsManager, packageInfo);
            }
            if (state == SnoopingState.FOREGROUND && movePermissionToo) {
                // allowAppOp has just put the op at MODE_ALLOWED along with the
                // permission grant, which is right for "allowed" and one step too
                // far for "only while in use". The grant must stay — a revoked
                // permission is no access at all — so only the mode is narrowed,
                // after the fact and at both levels.
                appOpsManager.setModeBothLevels(opItem.getOp(), packageInfo.applicationInfo.uid,
                        packageInfo.packageName, AppOpsManager.MODE_FOREGROUND);
                opItem.invalidate(appOpsManager, packageInfo);
            }
            refreshEffectiveMode(appOpsManager, packageInfo);
            repairIfUnchanged(packageInfo, appOpsManager, state);
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
        // PermUtils mutates the in-memory Permission whether or not the platform
        // agreed, so the row would otherwise report its own intent back to us.
        refreshEffectivePermission(appOpsManager, packageInfo);
    }

    /** The app-op mode that expresses a state. */
    private static int modeFor(@SnoopingState.State int state) {
        switch (state) {
            case SnoopingState.ALLOWED:
                return AppOpsManager.MODE_ALLOWED;
            case SnoopingState.FOREGROUND:
                return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? AppOpsManager.MODE_FOREGROUND
                        : AppOpsManager.MODE_ALLOWED;
            case SnoopingState.BLOCKED:
            default:
                return AppOpsManager.MODE_IGNORED;
        }
    }
}
