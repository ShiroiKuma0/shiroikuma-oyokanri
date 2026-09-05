// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.devicepolicy;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.Collections;
import java.util.List;

import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.profiles.ProtectedAppsProfile;
import io.github.muntashirakon.AppManager.utils.ContextUtils;

/**
 * Fork: the powers 白い熊 雫 (<code>shiroikuma.shizuku</code>) hands us as a
 * <b>device-policy delegate</b>.
 * <p>
 * Everything the Snooping tab did before this class is <em>soft</em>: an app-op we
 * write or a permission we revoke can be put back by Settings, by another tool,
 * and sometimes by the app itself. A Device Owner's policy cannot. 雫 is the owner
 * on 白い熊's phone and calls {@code setDelegatedScopes} for us once; from then on
 * every call here is the <b>public SDK</b> with a {@code null} admin — no hidden
 * API, no binder relay, and it keeps working while 雫 is stopped, because
 * {@code system_server} persists the delegation.
 * <p>
 * <b>Only what the platform will delegate lives here.</b> The Device-Owner-only
 * powers (accessibility allowlist, user restrictions, always-on VPN, camera) are
 * unreachable this way and go through {@link PolicyApiClient} instead — see its
 * class comment for why the split is worth keeping.
 * <p>
 * <b>A lock outlives us.</b> A permission we policy-fix and a package we suspend
 * are stored under <em>雫's</em> admin, so uninstalling 応用管理 releases nothing.
 * That is the whole point of a hard lock, and it is why every UI path to one is
 * gated behind {@link DangerDialog} and why {@link PolicyApiClient#clearAllLocks}
 * exists.
 */
public final class DevicePolicyBridge {
    public static final String TAG = DevicePolicyBridge.class.getSimpleName();

    /** Delegation did not exist before O, so neither does any of this. */
    private static final boolean SUPPORTED = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;

    /**
     * Scopes move only when 白い熊 flips the switch in 雫, so a short cache keeps
     * a per-row lock check from costing a binder call each time. Deliberately
     * short: the page must notice the grant within seconds of it being made,
     * without needing a restart to explain itself.
     */
    private static final long SCOPE_TTL_MS = 5_000L;

    private static List<String> sScopes = Collections.emptyList();
    private static long sScopesAt = 0L;

    private DevicePolicyBridge() {
    }

    @Nullable
    private static DevicePolicyManager dpm() {
        try {
            Context context = ContextUtils.getContext();
            return (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        } catch (Throwable th) {
            return null;
        }
    }

    /** Drop the cached scopes — call after anything that could have changed them. */
    public static void invalidate() {
        sScopesAt = 0L;
    }

    /**
     * The scopes the platform says we hold. Asked of the platform, never assumed
     * from our own state: a delegation revoked in 雫 must stop us at once, and the
     * only honest source is {@code getDelegatedScopes}.
     */
    @WorkerThread
    @NonNull
    public static List<String> getScopes() {
        if (!SUPPORTED) return Collections.emptyList();
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - sScopesAt < SCOPE_TTL_MS) {
            return sScopes;
        }
        List<String> scopes = Collections.emptyList();
        try {
            DevicePolicyManager dpm = dpm();
            if (dpm != null) {
                Context context = ContextUtils.getContext();
                List<String> got = dpm.getDelegatedScopes(null, context.getPackageName());
                if (got != null) scopes = got;
            }
        } catch (Throwable th) {
            // Not a delegate, or the platform refuses to answer. Either way we
            // hold nothing, which is the safe reading.
            Log.d(TAG, "getDelegatedScopes refused: %s", th.getMessage());
        }
        sScopes = scopes;
        sScopesAt = now;
        return scopes;
    }

    /** Whether we have any policy power at all — what the "DO powers" banner reads. */
    @WorkerThread
    public static boolean isDelegate() {
        return !getScopes().isEmpty();
    }

    @WorkerThread
    public static boolean canLockPermissions() {
        return SUPPORTED && getScopes().contains(DevicePolicyManager.DELEGATION_PERMISSION_GRANT);
    }

    @WorkerThread
    public static boolean canSuspend() {
        return SUPPORTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && getScopes().contains(DevicePolicyManager.DELEGATION_PACKAGE_ACCESS);
    }

    @WorkerThread
    public static boolean canBlockUninstall() {
        return SUPPORTED && getScopes().contains(DevicePolicyManager.DELEGATION_BLOCK_UNINSTALL);
    }

    // ── Permission hard lock ────────────────────────────────────────────────

    /**
     * Whether this permission is fixed by device policy right now.
     * <p>
     * Read from the platform rather than from anything we stored, exactly like
     * every other state on the Snooping page: a lock 雫 set directly, or one left
     * behind by an older build of ours, must show the padlock just the same.
     */
    @WorkerThread
    public static boolean isPermissionLocked(@NonNull String packageName, @NonNull String permission) {
        if (!SUPPORTED) return false;
        try {
            DevicePolicyManager dpm = dpm();
            if (dpm == null) return false;
            return dpm.getPermissionGrantState(null, packageName, permission)
                    != DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT;
        } catch (Throwable th) {
            return false;
        }
    }

    /**
     * Fix the permission denied, or release it back to the user's control.
     * <p>
     * A locked permission is {@code POLICY_FIXED}: Settings greys the switch out
     * and the app's own {@code requestPermissions} auto-denies with no dialog. It
     * also only works for <b>runtime</b> permissions — the platform returns false
     * for anything else, which is reported as failure rather than swallowed.
     *
     * @return {@code true} only when the platform actually took it, re-read
     *         afterwards. Never record a decision a write did not achieve.
     */
    @WorkerThread
    public static boolean setPermissionLocked(@NonNull String packageName, @NonNull String permission,
                                              boolean locked) {
        if (!canLockPermissions()) return false;
        try {
            DevicePolicyManager dpm = dpm();
            if (dpm == null) return false;
            int target = locked
                    ? DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
                    : DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT;
            dpm.setPermissionGrantState(null, packageName, permission, target);
            return isPermissionLocked(packageName, permission) == locked;
        } catch (Throwable th) {
            Log.w(TAG, "Locking %s for %s refused", th, permission, packageName);
            return false;
        }
    }

    // ── Suspension: the hard freeze ─────────────────────────────────────────

    @WorkerThread
    public static boolean isSuspended(@NonNull String packageName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        try {
            DevicePolicyManager dpm = dpm();
            return dpm != null && dpm.isPackageSuspended(null, packageName);
        } catch (PackageManager.NameNotFoundException | IllegalArgumentException | SecurityException e) {
            return false;
        } catch (Throwable th) {
            return false;
        }
    }

    /**
     * Suspend or release a package.
     * <p>
     * A suspended app cannot be opened at all — the system shows a stub dialog in
     * its place and silences its notifications — and Settings offers no way back.
     * Harder than the freeze the main list does, which is why it is offered only
     * behind a danger confirmation.
     * <p>
     * {@code setPackagesSuspended} returns the packages it could <b>not</b> touch
     * (some are policy-exempt on every device), so a non-empty return is a
     * failure, not a success with a note.
     */
    @WorkerThread
    public static boolean setSuspended(@NonNull String packageName, boolean suspended) {
        if (!canSuspend()) return false;
        // Fork (白い熊, +139): suspension is a harder freeze than hiding, and it reached the
        // platform without ever passing the guard FreezeUtils applies - the Snooping card's
        // switch calls this directly. Lifting one is always allowed: a protected app that
        // somehow got suspended must still be recoverable.
        if (suspended && ProtectedAppsProfile.isProtected(packageName)) {
            Log.w(TAG, "%s is protected and will not be suspended", packageName);
            return false;
        }
        try {
            DevicePolicyManager dpm = dpm();
            if (dpm == null) return false;
            String[] failed = dpm.setPackagesSuspended(null, new String[]{packageName}, suspended);
            if (failed != null && failed.length > 0) {
                Log.w(TAG, "Refused to suspend %s", packageName);
                return false;
            }
            return isSuspended(packageName) == suspended;
        } catch (Throwable th) {
            Log.w(TAG, "Suspending %s refused", th, packageName);
            return false;
        }
    }

    // ── Uninstall block ─────────────────────────────────────────────────────

    @WorkerThread
    public static boolean isUninstallBlocked(@NonNull String packageName) {
        try {
            DevicePolicyManager dpm = dpm();
            return dpm != null && dpm.isUninstallBlocked(null, packageName);
        } catch (Throwable th) {
            return false;
        }
    }

    @WorkerThread
    public static boolean setUninstallBlocked(@NonNull String packageName, boolean blocked) {
        if (!canBlockUninstall()) return false;
        try {
            DevicePolicyManager dpm = dpm();
            if (dpm == null) return false;
            dpm.setUninstallBlocked(null, packageName, blocked);
            return isUninstallBlocked(packageName) == blocked;
        } catch (Throwable th) {
            Log.w(TAG, "Blocking uninstall of %s refused", th, packageName);
            return false;
        }
    }
}
