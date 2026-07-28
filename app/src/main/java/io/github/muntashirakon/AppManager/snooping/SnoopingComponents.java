// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.snooping;

import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.utils.ContextUtils;

/**
 * Fork: the concrete manifest components behind a snooping capability.
 * <p>
 * Several of the worst capabilities are not app-ops at all — they are a
 * <em>component</em> the system binds, gated by a system list the user (or we)
 * can edit: an accessibility service, a notification listener, a VPN service, a
 * voice-interaction service. Two parts of the tab need to know which components
 * those are: {@link SnoopingReachability} (to decide whether the row belongs on
 * the page at all) and the levers that add and remove them from the system
 * lists. This is the one place that answers.
 * <p>
 * There was a third caller — a row action that disabled the component outright —
 * until the platform proved that shell may not change component state for
 * anything but a test-only app (4.1.0+22). Nothing here needs to know that; it
 * simply has one fewer caller.
 * <p>
 * <b>Landmine (cost a build, +11):</b> {@link PackageInfo#services} is
 * {@code null} in two entirely different situations — the caller did not ask for
 * {@code GET_SERVICES}, and the app declares no services at all. The first is
 * "no data" and must fail open, the second is the strongest evidence of
 * unreachability there is, and a {@code PackageInfo} cannot tell you which. So a
 * null is always re-queried here with the flag set explicitly, and only a thrown
 * query counts as unknown.
 */
public final class SnoopingComponents {
    public static final String BIND_ACCESSIBILITY = "android.permission.BIND_ACCESSIBILITY_SERVICE";
    public static final String BIND_NOTIFICATION_LISTENER = "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE";
    public static final String BIND_VPN = "android.permission.BIND_VPN_SERVICE";
    public static final String BIND_VOICE_INTERACTION = "android.permission.BIND_VOICE_INTERACTION";

    /** The app's services, plus whether "none" was an answer or a refusal. */
    public static final class Services {
        @Nullable
        public final ServiceInfo[] services;
        /** True when the platform would not answer — callers must fail open. */
        public final boolean unknown;

        Services(@Nullable ServiceInfo[] services, boolean unknown) {
            this.services = services;
            this.unknown = unknown;
        }
    }

    /**
     * One resolve pass asks about the same app's services once per component-bearing
     * capability — eight or so times, each potentially a fresh package-manager
     * query. Memoised against the {@link PackageInfo} instance the pass is working
     * from: same object, same answer, and the entry goes when that object does.
     */
    private static final Map<PackageInfo, Services> sCache = new WeakHashMap<>();

    private SnoopingComponents() {
    }

    @WorkerThread
    @NonNull
    public static Services services(@NonNull PackageInfo packageInfo, @UserIdInt int userId) {
        synchronized (sCache) {
            Services cached = sCache.get(packageInfo);
            if (cached != null) {
                return cached;
            }
        }
        Services resolved = resolveServices(packageInfo, userId);
        synchronized (sCache) {
            sCache.put(packageInfo, resolved);
        }
        return resolved;
    }

    @WorkerThread
    @NonNull
    private static Services resolveServices(@NonNull PackageInfo packageInfo, @UserIdInt int userId) {
        if (packageInfo.services != null) {
            return new Services(packageInfo.services, false);
        }
        try {
            PackageInfo withServices = PackageManagerCompat.getPackageInfo(packageInfo.packageName,
                    PackageManager.GET_SERVICES | PackageManagerCompat.MATCH_DISABLED_COMPONENTS
                            | PackageManagerCompat.MATCH_UNINSTALLED_PACKAGES, userId);
            return new Services(withServices != null ? withServices.services : null, false);
        } catch (Throwable th) {
            return new Services(null, true);
        }
    }

    /**
     * Every service of this app the system would bind with {@code bindPermission}.
     * Disabled components are included — they can be switched back on, so they
     * still count as a capability the app has.
     */
    @WorkerThread
    @NonNull
    public static List<ComponentName> servicesBoundWith(@NonNull PackageInfo packageInfo, @UserIdInt int userId,
                                                        @NonNull String bindPermission) {
        Services services = services(packageInfo, userId);
        if (services.services == null) {
            return Collections.emptyList();
        }
        List<ComponentName> components = new ArrayList<>(1);
        for (ServiceInfo service : services.services) {
            if (service != null && bindPermission.equals(service.permission)) {
                components.add(new ComponentName(service.packageName, service.name));
            }
        }
        return components;
    }

    /** Activities of this app that answer {@link Intent#ACTION_ASSIST}. */
    @WorkerThread
    @NonNull
    public static List<ComponentName> assistActivities(@NonNull PackageInfo packageInfo) {
        List<ComponentName> components = new ArrayList<>(1);
        try {
            List<ResolveInfo> handlers = ContextUtils.getContext().getPackageManager()
                    .queryIntentActivities(new Intent(Intent.ACTION_ASSIST), 0);
            for (ResolveInfo info : handlers) {
                ActivityInfo activity = info.activityInfo;
                if (activity != null && packageInfo.packageName.equals(activity.packageName)) {
                    components.add(new ComponentName(activity.packageName, activity.name));
                }
            }
        } catch (Throwable ignore) {
            // No package-manager answer; the caller simply gets nothing to disable.
        }
        return components;
    }

}
