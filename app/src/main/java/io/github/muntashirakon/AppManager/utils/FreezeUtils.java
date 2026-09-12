// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.utils;

import android.Manifest;
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
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.devicepolicy.DevicePolicyBridge;
import io.github.muntashirakon.AppManager.profiles.ProtectedAppsProfile;
import io.github.muntashirakon.AppManager.self.SelfPermissions;
import io.github.muntashirakon.AppManager.settings.Prefs;

public final class FreezeUtils {
    public static final String TAG = FreezeUtils.class.getSimpleName();

    @IntDef({FREEZE_DISABLE, FREEZE_SUSPEND, FREEZE_HIDE, FREEZE_ADV_SUSPEND, FREEZE_TOTAL})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FreezeMethod {
    }

    public static final int FREEZE_DISABLE = 1;
    public static final int FREEZE_SUSPEND = 1 << 1;
    public static final int FREEZE_HIDE = 1 << 2;
    public static final int FREEZE_ADV_SUSPEND = 1 << 3;
    /**
     * Fork (白い熊, +29): every gate this phone allows, at once — force-stop, suspend,
     * disable and hide.
     * <p>
     * These values are bit flags, but every consumer compares them with {@code ==},
     * so this is a <b>distinct method</b> rather than a mask of the others. That is
     * deliberate: the value is the wire format of {@code FreezeRule} and of the
     * per-app remembered method, and a mask would have every {@code ==} in the app
     * quietly stop matching.
     */
    public static final int FREEZE_TOTAL = 1 << 4;

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
            // Fork (白い熊, +139): say which of the two blocks it is. Reporting the profile for
            // an app that is not in it sends the reader to edit a list that would not have
            // helped.
            throw new RemoteException(packageName + (ProtectedAppsProfile.isAlwaysProtected(packageName)
                    ? " is one of the apps 白い熊 応用管理 cannot work without and is protected from freezing."
                    : " is in the " + ProtectedAppsProfile.PROTECTED_PROFILE_NAME
                    + " profile and is protected from freezing."));
        }
        if (BuildConfig.APPLICATION_ID.equals(packageName) && userId == UserHandleHidden.myUserId()) {
            throw new RemoteException("Could not freeze myself.");
        }
        if (freezeType == FREEZE_TOTAL) {
            freezeTotal(packageName, userId);
            return;
        }
        if (freezeType == FREEZE_HIDE) {
            if (hideBestEffort(packageName, userId)) {
                return;
            }
            // Neither MANAGE_USERS nor a delegation, fall-through
        } else if ((freezeType == FREEZE_SUSPEND || freezeType == FREEZE_ADV_SUSPEND) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (freezeType == FREEZE_ADV_SUSPEND) {
                // Force-stop app. Fork (白い熊, measured 2026-09-10): NOT redundant beside
                // the suspension. A `pm suspend` of a running app does kill the process
                // by itself, but leaves stopped=false; only the force-stop sets the
                // stopped flag, which is what withholds implicit broadcasts afterwards.
                if (SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.FORCE_STOP_PACKAGES)) {
                    PackageManagerCompat.forceStopPackage(packageName, userId);
                }
            }
            if (suspendBestEffort(packageName, userId)) {
                return;
            }
            // No permission, fall-through
        }
        PackageManagerCompat.setApplicationEnabledSetting(packageName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0, userId);
    }

    /**
     * Fork (白い熊, +29): every gate at once, each one best-effort.
     * <p>
     * <b>Order is load-bearing.</b> Force-stop first, so the app is not left running
     * until it dies of its own accord; <b>hide last</b>, because a hidden package is
     * reported as not installed and the suspend and enabled-state calls would then have
     * nothing to act on.
     * <p>
     * Each layer closes a different hole, which is why this is a stack rather than a
     * choice (all measured 2026-09-10 on the phone reporting {@code HUAWEI GRL-LX9},
     * {@code SDK_INT 31}; behaviour confirmed by 白い熊 on their second phone):
     * <ul>
     * <li><b>Suspend</b> blocks the launch and — surprisingly — an explicit broadcast
     *     too, even one carrying {@code FLAG_INCLUDE_STOPPED_PACKAGES}; and it is the
     *     one flag EMUI does <i>not</i> restore at boot.</li>
     * <li><b>Disable</b> removes the components from <i>resolution</i>. The cleanest
     *     proof is a content provider: suspended it answers "not exported from UID
     *     …", disabled it answers "Could not find provider".</li>
     * <li><b>Hide</b> reports the package as not installed for this user.</li>
     * </ul>
     * A layer that cannot be applied is skipped, never fatal — but if <i>none</i>
     * landed the caller must hear about it, or the row would go on claiming a freeze
     * that never happened.
     */
    @WorkerThread
    private static void freezeTotal(@NonNull String packageName, @UserIdInt int userId) throws RemoteException {
        boolean any = false;
        if (SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.FORCE_STOP_PACKAGES)) {
            try {
                PackageManagerCompat.forceStopPackage(packageName, userId);
            } catch (Throwable ignore) {
                // A process that would not die is not a reason to skip the gates.
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                any |= suspendBestEffort(packageName, userId);
            } catch (Throwable ignore) {
            }
        }
        try {
            PackageManagerCompat.setApplicationEnabledSetting(packageName,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0, userId);
            any = true;
        } catch (Throwable ignore) {
        }
        try {
            any |= hideBestEffort(packageName, userId);
        } catch (Throwable ignore) {
        }
        if (!any) {
            throw new RemoteException("Could not freeze " + packageName
                    + ": no freezing method is available right now.");
        }
    }

    /**
     * Fork (白い熊, +29): suspend through the <b>admin's</b> slot where we can.
     * <p>
     * The platform records a suspension per <i>suspending package</i> — measured on the
     * phone: ours through the shell reads {@code suspendingPackage=com.android.shell},
     * one applied through 雫's delegation reads {@code suspendingPackage=android}. Only
     * the second is beyond the reach of {@code adb shell pm unsuspend}, so it is the
     * harder lock and is preferred whenever the delegation is live. {@link #unfreeze}
     * already lifts both, so nothing becomes unrecoverable by choosing it.
     * <p>
     * Device-policy calls act on the calling user, hence the user check before one.
     */
    @WorkerThread
    private static boolean suspendBestEffort(@NonNull String packageName, @UserIdInt int userId)
            throws RemoteException {
        if (userId == UserHandleHidden.myUserId() && DevicePolicyBridge.canSuspend()
                && DevicePolicyBridge.setSuspended(packageName, true)) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.SUSPEND_APPS)) {
                PackageManagerCompat.suspendPackages(new String[]{packageName}, userId, true);
                return true;
            }
        } else if (SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.MANAGE_USERS)) {
            PackageManagerCompat.suspendPackages(new String[]{packageName}, userId, true);
            return true;
        }
        return false;
    }

    /**
     * Fork (白い熊, +29): hide through whichever door is open.
     * <p>
     * On this phone only the second one is: the shell holds no {@code MANAGE_USERS}, so
     * {@link PackageManagerCompat#hidePackage} throws and hiding is reachable solely
     * through 雫's {@code DELEGATION_PACKAGE_ACCESS} — see
     * {@link DevicePolicyBridge#setHidden}. The shell path is tried first anyway,
     * because a rooted phone or another OEM may well grant it.
     */
    @WorkerThread
    private static boolean hideBestEffort(@NonNull String packageName, @UserIdInt int userId) {
        boolean hidden = false;
        if (SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.MANAGE_USERS)) {
            try {
                PackageManagerCompat.hidePackage(packageName, userId, true);
                hidden = true;
            } catch (Throwable ignore) {
                // Fall through to the delegate.
            }
        }
        if (!hidden && userId == UserHandleHidden.myUserId()) {
            hidden = DevicePolicyBridge.setHidden(packageName, true);
        }
        if (hidden && !isVisibleToOurList(packageName, userId)) {
            // Fork (白い熊, +29): PARAMOUNT — a hidden app we cannot list is an app that
            // can never be thawed again from this app, because the row carrying the
            // snowflake is the only way back. Every reading of the platform says the row
            // survives (hiding sets PRIVATE_FLAG_HIDDEN and leaves FLAG_INSTALLED alone,
            // and our enumeration passes MATCH_UNINSTALLED_PACKAGES), but this phone has
            // never once executed a hide — the shell holds no MANAGE_USERS, so the method
            // has silently been Disable — so the assumption is untested here and is not
            // one to be wrong about. Undo it and report the layer as not applied; the
            // other three gates still stand.
            revealBestEffort(packageName, userId);
            Log.w(TAG, "Hiding %s left it invisible to our own list; reverted.", packageName);
            return false;
        }
        return hidden;
    }

    /**
     * Fork (白い熊, +29): reveal through whichever door is open — the mirror of
     * {@link #hideBestEffort}, and the only place that knows both doors.
     */
    @WorkerThread
    private static boolean revealBestEffort(@NonNull String packageName, @UserIdInt int userId) {
        boolean revealed = false;
        try {
            PackageManagerCompat.hidePackage(packageName, userId, false);
            revealed = true;
        } catch (Throwable ignore) {
            // Fall through to the delegate, which is what applied it on this phone.
        }
        if (!revealed && userId == UserHandleHidden.myUserId()) {
            revealed = DevicePolicyBridge.setHidden(packageName, false);
        }
        return revealed;
    }

    /**
     * Fork (白い熊, +29): would the main list still carry a row for this package?
     * <p>
     * Deliberately asks the <b>exact predicate the list itself uses</b> rather than a
     * proxy for it: {@code AppDb} enumerates with {@code MATCH_UNINSTALLED_PACKAGES |
     * MATCH_DISABLED_COMPONENTS} and then sets {@code App.isInstalled} from
     * {@link ApplicationInfoCompat#isInstalled}, so a package that answers both is a
     * package that gets a row — and a row is a snowflake, which is the way back.
     */
    @WorkerThread
    private static boolean isVisibleToOurList(@NonNull String packageName, @UserIdInt int userId) {
        try {
            ApplicationInfo info = PackageManagerCompat.getApplicationInfo(packageName,
                    PackageManagerCompat.MATCH_UNINSTALLED_PACKAGES
                            | PackageManagerCompat.MATCH_DISABLED_COMPONENTS, userId);
            return ApplicationInfoCompat.isInstalled(info);
        } catch (Throwable th) {
            return false;
        }
    }

    public static void unfreeze(@NonNull String packageName, @UserIdInt int userId) throws RemoteException {
        // Ignore checking preference, unfreeze for all types
        if (PackageManagerCompat.isPackageHidden(packageName, userId)) {
            // Fork (白い熊, +29): hidePackage THROWS when MANAGE_USERS is missing, which on
            // this phone is always. Uncaught, that throw abandoned the rest of this method
            // and left the app suspended AND disabled with no way back through it — a bug
            // that could not fire while nothing here was able to hide anything, and fires
            // on the first Total freeze. Try the shell, then the delegate that applied it.
            revealBestEffort(packageName, userId);
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

    // ------------------------------------------------------------------
    // Fork (白い熊, +032): the four gates, one at a time.
    //
    // FREEZE_TOTAL applies force-stop, suspend, disable and hide together and is
    // the right thing for a snowflake — one tap, everything the phone allows. It
    // is the wrong thing for finding out WHICH gate an outside app trips over:
    // Android Auto refuses to run against a Maps that is totally frozen, and runs
    // against the same Maps carrying only the disable gate (measured 2026-09-12 on
    // the second phone, where `dumpsys package` reads `installed=true hidden=false
    // suspended=false enabled=3`). Nothing in the app could express that state,
    // because every freeze was a method rather than a set of switches.
    //
    // So the gates get names, states, availability and a release each. They are
    // NOT a new freezing method and nothing persists them: `FreezeUtils.isFrozen`
    // is still the app-wide truth, the FREEZE_* values are still the wire format,
    // and this is a second door onto the same platform calls.
    //
    // The numbers are the step numbers the Snooping page shows, in the order
    // freezeTotal applies them — mild to severe — and are used only for that.
    // ------------------------------------------------------------------

    @IntDef({GATE_FORCE_STOP, GATE_SUSPEND, GATE_DISABLE, GATE_HIDE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FreezeGate {
    }

    public static final int GATE_FORCE_STOP = 1;
    public static final int GATE_SUSPEND = 2;
    public static final int GATE_DISABLE = 3;
    public static final int GATE_HIDE = 4;

    /** The gates in the order {@link #freezeTotal} applies them. */
    public static final int[] GATES = {GATE_FORCE_STOP, GATE_SUSPEND, GATE_DISABLE, GATE_HIDE};

    /**
     * Whether {@code gate} is in force right now, read from a live
     * {@link ApplicationInfo}.
     * <p>
     * <b>Landmine.</b> The caller must have resolved that info with
     * {@code MATCH_UNINSTALLED_PACKAGES | MATCH_DISABLED_COMPONENTS}, or a hidden
     * package — precisely the one the hide gate is about — resolves to nothing and
     * every gate reads false.
     */
    public static boolean isGateApplied(@NonNull ApplicationInfo info, @FreezeGate int gate) {
        switch (gate) {
            case GATE_FORCE_STOP:
                return ApplicationInfoCompat.isStopped(info);
            case GATE_SUSPEND:
                // The FLAG, not DevicePolicyBridge.isSuspended: the platform records a
                // suspension per suspending package, so asking the admin slot alone
                // answers "no" for one we applied through the shell. The flag is set
                // whichever slot holds it.
                return ApplicationInfoCompat.isSuspended(info);
            case GATE_DISABLE:
                return !info.enabled;
            case GATE_HIDE:
                return ApplicationInfoCompat.isHidden(info);
            default:
                return false;
        }
    }

    /**
     * How deep this app is shut, as a gate number (白い熊, +034).
     * <p>
     * Zero when nothing is in force, otherwise the <b>highest</b> gate standing. The
     * gates are independent switches rather than a dial, so an app can carry 1 and 3
     * with 2 released; the number a row shows is the strongest thing done to it,
     * because that is what governs how the rest of the phone sees it.
     * <p>
     * Note that level 1 is <em>not</em> a freeze: {@link #isFrozen} stays false for a
     * package that is merely stopped, and the row must stay an ordinary row. The
     * number is still worth showing — force-stopped is invisible otherwise.
     */
    @FreezeGate
    public static int levelOf(@NonNull ApplicationInfo info) {
        int level = 0;
        for (int gate : GATES) {
            if (isGateApplied(info, gate)) level = gate;
        }
        return level;
    }

    /**
     * Whether this phone lets us operate {@code gate} at all, asked of the privileges
     * we hold right now.
     * <p>
     * Judged live rather than cached: 雫's delegation is granted in another app and
     * can appear or vanish while the page is open, and the hide gate exists on this
     * phone only because of it — the shell holds no {@code MANAGE_USERS}.
     */
    public static boolean canOperateGate(@FreezeGate int gate) {
        switch (gate) {
            case GATE_FORCE_STOP:
                return SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.FORCE_STOP_PACKAGES);
            case GATE_SUSPEND:
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                    return false;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        ? SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.SUSPEND_APPS)
                        : SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.MANAGE_USERS)) {
                    return true;
                }
                return DevicePolicyBridge.canSuspend();
            case GATE_DISABLE:
                return SelfPermissions.checkSelfOrRemotePermission(Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE);
            case GATE_HIDE:
                return SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.MANAGE_USERS)
                        || DevicePolicyBridge.canHide();
            default:
                return false;
        }
    }

    /**
     * Apply or release one gate.
     * <p>
     * Applying goes through the same protection the whole-app freeze does — a gate is
     * a freeze in every sense that matters to the 必要 profile, and letting one
     * through here would be a hole straight past {@link #freeze}. <b>Releasing is
     * never blocked</b>, the rule the suspend and uninstall chokepoints already
     * follow: the way back must always exist.
     *
     * @return whether the platform actually did it, re-read afterwards rather than
     * inferred from the call returning. Several of these APIs accept a write and
     * discard it.
     */
    @WorkerThread
    public static boolean setGate(@NonNull String packageName, @UserIdInt int userId,
                                  @FreezeGate int gate, boolean apply) throws RemoteException {
        if (apply) {
            if (ProtectedAppsProfile.isProtected(packageName)) {
                throw new RemoteException(packageName + (ProtectedAppsProfile.isAlwaysProtected(packageName)
                        ? " is one of the apps 白い熊 応用管理 cannot work without and is protected from freezing."
                        : " is in the " + ProtectedAppsProfile.PROTECTED_PROFILE_NAME
                        + " profile and is protected from freezing."));
            }
            if (BuildConfig.APPLICATION_ID.equals(packageName) && userId == UserHandleHidden.myUserId()) {
                throw new RemoteException("Could not freeze myself.");
            }
        }
        switch (gate) {
            case GATE_FORCE_STOP:
                if (apply) {
                    PackageManagerCompat.forceStopPackage(packageName, userId);
                } else {
                    // No ordinary way back from stopped except launching the app, which
                    // is not something a switch may do on the user's behalf. See the
                    // note on setPackageStoppedState.
                    PackageManagerCompat.setPackageStoppedState(packageName, false, userId);
                }
                break;
            case GATE_SUSPEND:
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                    return false;
                }
                if (apply) {
                    suspendBestEffort(packageName, userId);
                    break;
                }
                // Both slots, in the order unfreeze lifts them: ours as the shell, then
                // the admin's, which a shell unsuspend cannot touch.
                try {
                    PackageManagerCompat.suspendPackages(new String[]{packageName}, userId, false);
                } catch (Throwable ignore) {
                    // No privilege for the shell slot; the delegate may still hold it.
                }
                try {
                    if (DevicePolicyBridge.isSuspended(packageName)) {
                        DevicePolicyBridge.setSuspended(packageName, false);
                    }
                } catch (Throwable ignore) {
                }
                break;
            case GATE_DISABLE:
                PackageManagerCompat.setApplicationEnabledSetting(packageName, apply
                        ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                        : PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0, userId);
                break;
            case GATE_HIDE:
                if (apply) {
                    hideBestEffort(packageName, userId);
                } else {
                    revealBestEffort(packageName, userId);
                }
                break;
            default:
                return false;
        }
        // Ask the platform what it actually did. Half of these APIs accept a write
        // and discard it — a suspension lifted in the wrong slot returns looking
        // like success, hideBestEffort reverts itself when the row would vanish,
        // and a PERSISTENT process is back before the force-stop returns. The page
        // records nothing on a false, so this must be measured, never inferred.
        ApplicationInfo after = resolveApplicationInfo(packageName, userId);
        return after != null && isGateApplied(after, gate) == apply;
    }

    /**
     * Fork (白い熊, +038): bring this app to <b>exactly</b> {@code level} — gates 1‥level
     * applied, every gate above it released.
     *
     * <p>The four gates got their own switches in +032 so one app at a time could be walked
     * down the ladder; this is the same ladder applied to a selection. The workflow it serves
     * is 白い熊's: put the apps that belong at one depth into a profile, filter the list to
     * that profile, select all, tap the level. That is also the only way a phone's freezing can
     * be <i>reproduced</i> — the level itself is never persisted anywhere (it is read live from
     * {@link ApplicationInfo} on every bind), so a profile plus a level is the record.
     *
     * <p><b>Order is load-bearing, twice over.</b> Releasing runs first and from the top down,
     * because a hidden package is reported as not installed and nothing below gate 4 can be read
     * or written while that stands. Applying then runs bottom-up, the order {@link #freezeTotal}
     * uses and for the same reason: hide last, or the suspend and enabled-state calls have
     * nothing left to act on.
     *
     * <p>A gate already in the wanted state is skipped rather than re-written — {@code setGate}
     * would happily re-issue it, but a force-stop of an already-stopped app is a real kill and a
     * re-suspend rewrites the suspending-package slot.
     *
     * <p><b>The per-app remembered freezing method is deliberately left alone</b>, the rule the
     * gate box already follows: the method is what the main list's snowflake will do next, and
     * silently rewriting it here would change an unrelated control.
     *
     * <p><b>Level 0 is a release and is never blocked</b> — it applies no gate at all, so the
     * 必要 guard does not apply to it, the rule the suspend and uninstall chokepoints already
     * follow: the way back must always exist. It is a little more than {@link #unfreeze}, which
     * deliberately leaves {@code stopped} alone; here every gate including the force-stop is
     * lifted, because the caller asked for rung zero by name.
     *
     * @throws RemoteException if the app is protected, or if the level was not reached — judged
     *                         by {@link #levelOf} afterwards, never inferred from the calls
     *                         returning. Note that this is a test of the <b>top</b> gate: a
     *                         PERSISTENT process that survives the force-stop under a disable
     *                         gate still reads as level 3, which is the truth.
     */
    @WorkerThread
    public static void setLevel(@NonNull String packageName, @UserIdInt int userId, int level)
            throws RemoteException {
        if (level < 0 || level > GATE_HIDE) {
            throw new RemoteException("Not a freeze level: " + level);
        }
        // One check, stated once. setGate re-checks on every apply, which is cheap (the profile
        // lookup is cached) and harmless, but the message a caller reports should come from here.
        if (level > 0 && ProtectedAppsProfile.isProtected(packageName)) {
            throw new RemoteException(packageName + (ProtectedAppsProfile.isAlwaysProtected(packageName)
                    ? " is one of the apps 白い熊 応用管理 cannot work without and is protected from freezing."
                    : " is in the " + ProtectedAppsProfile.PROTECTED_PROFILE_NAME
                    + " profile and is protected from freezing."));
        }
        if (level > 0 && BuildConfig.APPLICATION_ID.equals(packageName)
                && userId == UserHandleHidden.myUserId()) {
            throw new RemoteException("Could not freeze myself.");
        }
        for (int gate = GATE_HIDE; gate > level; --gate) {
            ApplicationInfo info = resolveApplicationInfo(packageName, userId);
            if (info == null || !isGateApplied(info, gate)) {
                continue;
            }
            try {
                setGate(packageName, userId, gate, false);
            } catch (Throwable th) {
                // Releasing is best-effort: a gate we cannot lift is reported by the level
                // check below, and abandoning the walk here would leave the app part-way.
                Log.w(TAG, "Could not release gate %d of %s", th, gate, packageName);
            }
        }
        for (int gate = GATE_FORCE_STOP; gate <= level; ++gate) {
            ApplicationInfo info = resolveApplicationInfo(packageName, userId);
            if (info != null && isGateApplied(info, gate)) {
                continue;
            }
            if (!canOperateGate(gate)) {
                // Not fatal in itself — freezeTotal skips what it cannot apply too. Whether it
                // mattered is settled by the level check below.
                continue;
            }
            try {
                setGate(packageName, userId, gate, true);
            } catch (Throwable th) {
                Log.w(TAG, "Could not apply gate %d of %s", th, gate, packageName);
            }
        }
        ApplicationInfo after = resolveApplicationInfo(packageName, userId);
        int reached = after != null ? levelOf(after) : -1;
        if (reached != level) {
            throw new RemoteException("Could not bring " + packageName + " to level " + level
                    + " (it is at level " + reached + ").");
        }
    }

    /**
     * This package as the platform sees it now.
     * <p>
     * <b>Landmine.</b> The match flags are not optional: a package frozen by
     * <em>hiding</em> is reported as not installed, so without them the one gate
     * whose result most needs checking resolves to nothing at all.
     */
    @Nullable
    @WorkerThread
    private static ApplicationInfo resolveApplicationInfo(@NonNull String packageName,
                                                          @UserIdInt int userId) {
        int flags = PackageManagerCompat.MATCH_UNINSTALLED_PACKAGES
                | PackageManager.MATCH_DISABLED_COMPONENTS;
        try {
            return PackageManagerCompat.getApplicationInfo(packageName, flags, userId);
        } catch (Throwable th) {
            return null;
        }
    }
}
