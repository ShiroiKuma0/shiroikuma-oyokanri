// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.snooping;

import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.UserHandleHidden;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.muntashirakon.AppManager.compat.AppOpsManagerCompat;
import io.github.muntashirakon.AppManager.compat.ApplicationInfoCompat;
import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.details.struct.AppDetailsSnoopingItem;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.self.SelfPermissions;
import io.github.muntashirakon.AppManager.users.Users;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;

/**
 * Fork: replays the stored anti-snooping decisions onto the live system.
 * <p>
 * This is what makes the store worth having. A decision recorded on one phone,
 * carried across in a settings export and imported onto a phone that does not
 * even have the app yet, sits in {@link SnoopingPrefs} keyed by package name until
 * the package appears — then lands, unprompted:
 * <ul>
 *   <li>{@link SnoopingInstallReceiver} calls {@link #enforcePackage} when the
 *       package is installed or updated;</li>
 *   <li>{@link #enforceAll} sweeps every stored package at app start, which
 *       covers the import-onto-a-phone-that-already-has-the-apps case and any
 *       install we missed because we held no privileges at the time.</li>
 * </ul>
 * Both paths need ADB/Shizuku to be up. When it is not, the sweep is a no-op and
 * the next launch retries — a decision is never dropped, only deferred.
 */
public final class SnoopingEnforcer {
    public static final String TAG = "SnoopingEnforcer";

    /** Guards against two sweeps overlapping (startup + an install landing together). */
    private static final AtomicBoolean sSweepRunning = new AtomicBoolean(false);

    private SnoopingEnforcer() {
    }

    /** True when we currently hold enough privilege to change anything at all. */
    public static boolean canEnforce() {
        return SelfPermissions.canModifyAppOpMode() || SelfPermissions.canModifyPermissions();
    }

    /**
     * Re-apply every stored decision, for every package that has one, on a
     * background thread. Safe to call on every app start; returns immediately.
     */
    public static void enforceAllAsync(@NonNull Context context) {
        if (!SnoopingPrefs.isAutoApplyEnabled()) {
            return;
        }
        ThreadUtils.postOnBackgroundThread(() -> enforceAll(context.getApplicationContext()));
    }

    /**
     * Re-apply the stored decisions for one package on a background thread.
     * Called when a package is installed or updated.
     */
    public static void enforcePackageAsync(@NonNull Context context, @NonNull String packageName,
                                           @UserIdInt int userId) {
        if (!SnoopingPrefs.isAutoApplyEnabled()) {
            return;
        }
        if (SnoopingPrefs.getSettings(packageName).isEmpty()) {
            // Nothing stored for this package — the common case, kept cheap.
            return;
        }
        ThreadUtils.postOnBackgroundThread(() -> enforcePackage(context.getApplicationContext(), packageName, userId));
    }

    /**
     * @return the number of capabilities actually changed across all managed packages.
     */
    @WorkerThread
    public static int enforceAll(@NonNull Context context) {
        if (!sSweepRunning.compareAndSet(false, true)) {
            return 0;
        }
        try {
            List<String> packages = SnoopingPrefs.getManagedPackages();
            if (packages.isEmpty() || !canEnforce()) {
                return 0;
            }
            int[] userIds = userIds();
            int changed = 0;
            for (String packageName : packages) {
                for (int userId : userIds) {
                    changed += enforceInternal(packageName, userId);
                }
            }
            if (changed > 0) {
                Log.d(TAG, "Re-applied %d snooping setting(s) across %d package(s)", changed, packages.size());
            }
            return changed;
        } finally {
            sSweepRunning.set(false);
        }
    }

    /**
     * @return the number of capabilities actually changed for this package.
     */
    @WorkerThread
    public static int enforcePackage(@NonNull Context context, @NonNull String packageName,
                                     @UserIdInt int userId) {
        if (!canEnforce()) {
            return 0;
        }
        return enforceInternal(packageName, userId);
    }

    @WorkerThread
    private static int enforceInternal(@NonNull String packageName, @UserIdInt int userId) {
        Map<String, Boolean> stored = SnoopingPrefs.getSettings(packageName);
        if (stored.isEmpty()) {
            return 0;
        }
        PackageInfo packageInfo = getPackageInfo(packageName, userId);
        if (packageInfo == null || packageInfo.applicationInfo == null
                || !ApplicationInfoCompat.isInstalled(packageInfo.applicationInfo)) {
            // Not installed for this user (MATCH_UNINSTALLED_PACKAGES also
            // returns data-only leftovers) — the decision waits for it.
            return 0;
        }
        AppOpsManagerCompat appOpsManager = new AppOpsManagerCompat();
        // "Show all" is irrelevant here: we replay what was stored, whatever tier
        // it came from, so resolve the full catalogue and match by id.
        List<AppDetailsSnoopingItem> items = SnoopingResolver.resolve(packageInfo, userId, appOpsManager, true);
        int changed = 0;
        for (AppDetailsSnoopingItem item : items) {
            Boolean desired = stored.get(item.capability.entry.id);
            if (desired == null || desired == item.isAllowed()) {
                continue;
            }
            try {
                item.setAllowed(packageInfo, appOpsManager, desired);
                ++changed;
                Log.d(TAG, "%s: %s → %s", packageName, item.capability.entry.id,
                        desired ? "allowed" : "blocked");
            } catch (Throwable th) {
                Log.w(TAG, "%s: could not apply %s", th, packageName, item.capability.entry.id);
            }
        }
        return changed;
    }

    @Nullable
    private static PackageInfo getPackageInfo(@NonNull String packageName, @UserIdInt int userId) {
        try {
            return PackageManagerCompat.getPackageInfo(packageName,
                    PackageManager.GET_PERMISSIONS | PackageManagerCompat.MATCH_UNINSTALLED_PACKAGES, userId);
        } catch (Throwable th) {
            return null;
        }
    }

    @NonNull
    private static int[] userIds() {
        try {
            int[] ids = Users.getUsersIds();
            if (ids.length > 0) {
                return ids;
            }
        } catch (Throwable ignore) {
            // No multi-user visibility — our own user is the only one we can reach.
        }
        return new int[]{UserHandleHidden.myUserId()};
    }
}
