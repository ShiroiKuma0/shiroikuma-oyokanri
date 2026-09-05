// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.profiles;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.muntashirakon.AppManager.profiles.struct.AppsProfile;

/**
 * Fork: support for a "protected" apps profile. Any app that is a member of an
 * apps profile named {@link #PROTECTED_PROFILE_NAME} is hard-blocked from being
 * frozen (see {@code FreezeUtils.freeze}) or uninstalled (see
 * {@code PackageInstallerCompat.uninstall}), regardless of which UI path
 * requests the operation.
 *
 * <p>Membership is resolved by reading the apps profiles and collecting the
 * package list of every profile whose name matches. The result is cached for a
 * short interval so that a batch operation (which queries once per package)
 * does not re-parse the profiles for every app. The cache is intentionally
 * short-lived rather than explicitly invalidated, so edits to the profile take
 * effect on their own shortly after.</p>
 *
 * <p>Resolution fails <i>open</i>: if the profiles cannot be read, no app is
 * treated as protected. A stuck block that refused every freeze/uninstall would
 * be far more harmful than briefly missing the protection.</p>
 *
 * <p><b>Three apps are protected without asking the profiles at all</b> (白い熊, +139) - see
 * {@link #ALWAYS_PROTECTED}. Fail-open is the right rule for a membership list somebody edits;
 * it is the wrong rule for the apps that make this one work, because the moment the profiles
 * cannot be read is exactly the moment a mistake would be unrecoverable.</p>
 */
public final class ProtectedAppsProfile {
    /** Name of the apps profile whose members are protected. */
    public static final String PROTECTED_PROFILE_NAME = "必要";

    /**
     * Fork (白い熊, +139): the three that are never frozen, suspended or uninstalled, whatever
     * the profiles say and whether or not they can be read.
     *
     * <p>Each is load-bearing for the others, and each failure is self-inflicted and hard to
     * undo from the phone:
     *
     * <ul>
     *   <li><b>白い熊 雫</b> is the Device Owner and the Shizuku server. Freezing it takes the
     *       privileged binder with it, so the app that did the freezing can no longer unfreeze
     *       anything - including 雫.</li>
     *   <li><b>白い熊 応用管理</b> is this app. Freezing yourself is a door locked from the
     *       inside; uninstalling yourself takes every backup decision, note and snooping setting
     *       with it.</li>
     *   <li><b>白い熊 自由作業盤</b> drives the cross-app automation batches - it is what fires
     *       the family's backups. A frozen automation app fails silently, and silently is the
     *       worst way for a backup schedule to stop.</li>
     * </ul>
     *
     * <p>Membership in 必要 is a decision that can be edited; this is not offered as one. The
     * profile remains the right place for everything else.
     */
    public static final Set<String> ALWAYS_PROTECTED = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    "shiroikuma.shizuku",
                    io.github.muntashirakon.AppManager.BuildConfig.APPLICATION_ID,
                    "shiroikuma.jiyusagyoban")));

    private static final long CACHE_TTL_MS = 3_000L;

    @Nullable
    private static volatile Set<String> sCache;
    private static volatile long sCacheTimeMs;

    private ProtectedAppsProfile() {
    }

    /**
     * @return the (possibly empty) set of package names belonging to the
     * protected profile. Never {@code null}.
     */
    @NonNull
    public static Set<String> getProtectedPackages() {
        long now = SystemClock.elapsedRealtime();
        Set<String> cache = sCache;
        if (cache != null && (now - sCacheTimeMs) < CACHE_TTL_MS) {
            return cache;
        }
        Set<String> result = new HashSet<>();
        try {
            List<AppsProfile> profiles = ProfileManager.getProfiles(AppsProfile.PROFILE_TYPE_APPS);
            for (AppsProfile profile : profiles) {
                if (profile != null && PROTECTED_PROFILE_NAME.equals(profile.name) && profile.packages != null) {
                    result.addAll(Arrays.asList(profile.packages));
                }
            }
        } catch (Throwable th) {
            // Fail open: keep the last good cache if we have one, otherwise treat
            // nothing as protected rather than blocking everything.
            return cache != null ? cache : Collections.emptySet();
        }
        sCache = result;
        sCacheTimeMs = now;
        return result;
    }

    /**
     * @return {@code true} if {@code packageName} is a member of the protected
     * profile and must not be frozen or uninstalled.
     */
    public static boolean isProtected(@Nullable String packageName) {
        if (packageName == null) {
            return false;
        }
        // Asked BEFORE the profiles, so this answer never depends on them being readable.
        if (ALWAYS_PROTECTED.contains(packageName)) {
            return true;
        }
        return getProtectedPackages().contains(packageName);
    }

    /** Whether the block on this package is the unconditional one rather than a profile. */
    public static boolean isAlwaysProtected(@Nullable String packageName) {
        return packageName != null && ALWAYS_PROTECTED.contains(packageName);
    }

    /** Drop the cached membership set so the next query re-reads the profiles. */
    public static void invalidate() {
        sCache = null;
    }
}
