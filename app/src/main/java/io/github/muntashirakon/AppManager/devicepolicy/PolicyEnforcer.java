// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.devicepolicy;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;

/**
 * Fork, +80: puts remembered device-policy locks back when the platform has lost
 * them.
 * <p>
 * <b>Why a hard lock needs a replay at all.</b> The Snooping page's soft
 * decisions are replayed by {@code SnoopingEnforcer} because an app-op drifts —
 * Settings, the app, or an update can put it back. A device-policy lock cannot
 * drift; that is the entire point of it. But it is stored by {@code system_server}
 * against the package's <em>installation</em> and against 雫's admin, so there
 * are exactly two ways it disappears, and neither is drift:
 * <ol>
 *   <li>the app is uninstalled and installed again — an update preserves grant
 *       state, a full uninstall does not;</li>
 *   <li>白い熊 雫 stops being Device Owner, which takes everything it holds with
 *       it, for every package at once.</li>
 * </ol>
 * Both leave a lock the user set silently absent. This class is what notices.
 * <p>
 * <b>It re-applies, it never records.</b> {@link PolicyLockState} is the only
 * store; a replay that failed changes nothing there, so a lock the platform
 * refuses stays remembered and is tried again next time rather than being quietly
 * forgotten. Nor does it ever <em>create</em> a memory: only the padlock does
 * that, and only when a write came back {@code true}.
 * <p>
 * <b>Ordering — it must run last.</b> {@code SnoopingEnforcer} replays permissions
 * and app-ops onto the same package, and a permission write lands on the very
 * grant state a policy lock pins. {@code ThreadUtils}' background executor is a
 * shared pool, so two posts are <em>not</em> ordered: the acute path
 * ({@code SnoopingInstallReceiver}, where a fresh install has every permission at
 * its default and the plain replay certainly runs) therefore chains both halves
 * inside one runnable rather than posting twice.
 */
public final class PolicyEnforcer {
    public static final String TAG = "PolicyEnforcer";

    /** Guards against two sweeps overlapping (app start + an install landing together). */
    private static final AtomicBoolean sSweepRunning = new AtomicBoolean(false);

    private PolicyEnforcer() {
    }

    /**
     * Re-apply the remembered locks for one package, on a background thread.
     * <p>
     * Prefer calling {@link #replayPackage} directly when the caller is already on
     * a worker and needs it ordered after something else — see the class comment.
     */
    public static void replayPackageAsync(@NonNull String packageName) {
        if (PolicyLockState.getRememberedPermissionLocks(packageName).isEmpty()) {
            // The common case, kept off the pool entirely.
            return;
        }
        ThreadUtils.postOnBackgroundThread(() -> replayPackage(packageName));
    }

    /** Re-apply every remembered lock, for every package that has one. */
    public static void replayAllAsync(@NonNull Context context) {
        ThreadUtils.postOnBackgroundThread(PolicyEnforcer::replayAll);
    }

    /**
     * @return the number of locks actually put back.
     */
    @WorkerThread
    public static int replayPackage(@NonNull String packageName) {
        Set<String> permissions = PolicyLockState.getRememberedPermissionLocks(packageName);
        if (permissions.isEmpty()) {
            return 0;
        }
        // Asked of the platform, not assumed: the delegation is granted in another
        // app and can have been revoked since this process started.
        DevicePolicyBridge.invalidate();
        if (!DevicePolicyBridge.canLockPermissions()) {
            // No powers right now — the memory waits for the next chance rather
            // than being dropped. Exactly how SnoopingEnforcer treats a phone
            // whose ADB server is not up.
            return 0;
        }
        int restored = 0;
        for (String permission : permissions) {
            if (DevicePolicyBridge.isPermissionLocked(packageName, permission)) {
                // Still in force: the overwhelmingly common case, and the reason
                // this is cheap enough to run on every install.
                continue;
            }
            if (DevicePolicyBridge.setPermissionLocked(packageName, permission, true)) {
                ++restored;
                Log.d(TAG, "%s: put back the device-policy lock on %s", packageName, permission);
            } else {
                Log.w(TAG, "%s: could not put back the lock on %s", packageName, permission);
            }
        }
        return restored;
    }

    /**
     * @return the number of locks actually put back across every package.
     */
    @WorkerThread
    public static int replayAll() {
        if (!sSweepRunning.compareAndSet(false, true)) {
            return 0;
        }
        try {
            Set<String> packages = PolicyLockState.getPackagesWithRememberedLocks();
            if (packages.isEmpty()) {
                return 0;
            }
            int restored = 0;
            for (String packageName : packages) {
                restored += replayPackage(packageName);
            }
            if (restored > 0) {
                Log.d(TAG, "Put back %d device-policy lock(s) across %d package(s)", restored,
                        packages.size());
            }
            return restored;
        } finally {
            sSweepRunning.set(false);
        }
    }
}
