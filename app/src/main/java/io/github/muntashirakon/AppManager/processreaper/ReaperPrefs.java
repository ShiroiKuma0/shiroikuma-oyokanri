// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.processreaper;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Fork: user-tunable state for the process monitor / reaper. Currently the
 * user's own "protected packages" set — apps the user marked never-kill from
 * the monitor, unioned with {@link ProcessClassifier}'s built-in denylist.
 * Dedicated SharedPreferences file, so settings export/import covers it
 * automatically.
 */
public final class ReaperPrefs {
    private ReaperPrefs() {}

    private static final String PREFS_NAME = "shiroikuma_reaper";
    private static final String KEY_PROTECTED = "protected_packages";
    private static final String KEY_ALLOWED = "allowed_packages";

    @NonNull
    private static SharedPreferences sp(@NonNull Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** A defensive copy of the user-protected package set. */
    @NonNull
    public static Set<String> getProtectedPackages(@NonNull Context ctx) {
        return new HashSet<>(sp(ctx).getStringSet(KEY_PROTECTED, Collections.emptySet()));
    }

    public static boolean isProtected(@NonNull Context ctx, @NonNull String pkg) {
        return sp(ctx).getStringSet(KEY_PROTECTED, Collections.emptySet()).contains(pkg);
    }

    public static void addProtected(@NonNull Context ctx, @NonNull String pkg) {
        Set<String> set = getProtectedPackages(ctx);
        if (set.add(pkg)) sp(ctx).edit().putStringSet(KEY_PROTECTED, set).apply();
    }

    public static void removeProtected(@NonNull Context ctx, @NonNull String pkg) {
        Set<String> set = getProtectedPackages(ctx);
        if (set.remove(pkg)) sp(ctx).edit().putStringSet(KEY_PROTECTED, set).apply();
    }

    // Allowed set: packages the user has explicitly un-protected from the
    // built-in denylist (long-press → "Allow killing"). Overrides ProcessClassifier
    // .DENYLIST, EXCEPT the privilege chain, which the classifier keeps hard.

    /** A defensive copy of the user-allowed (denylist-override) package set. */
    @NonNull
    public static Set<String> getAllowedPackages(@NonNull Context ctx) {
        return new HashSet<>(sp(ctx).getStringSet(KEY_ALLOWED, Collections.emptySet()));
    }

    public static boolean isAllowed(@NonNull Context ctx, @NonNull String pkg) {
        return sp(ctx).getStringSet(KEY_ALLOWED, Collections.emptySet()).contains(pkg);
    }

    public static void addAllowed(@NonNull Context ctx, @NonNull String pkg) {
        Set<String> set = getAllowedPackages(ctx);
        if (set.add(pkg)) sp(ctx).edit().putStringSet(KEY_ALLOWED, set).apply();
    }

    public static void removeAllowed(@NonNull Context ctx, @NonNull String pkg) {
        Set<String> set = getAllowedPackages(ctx);
        if (set.remove(pkg)) sp(ctx).edit().putStringSet(KEY_ALLOWED, set).apply();
    }
}
