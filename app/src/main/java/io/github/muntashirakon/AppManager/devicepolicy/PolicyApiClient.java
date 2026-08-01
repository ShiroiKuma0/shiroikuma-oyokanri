// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.devicepolicy;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.utils.ContextUtils;

/**
 * Fork: the client half of 白い熊 雫's <b>policy API</b> — the Device-Owner-only
 * powers that no {@code DELEGATION_*} scope can carry.
 * <p>
 * {@link DevicePolicyBridge} covers everything the platform will delegate, and is
 * the better path wherever it reaches: it runs in our own process and survives 雫
 * being stopped. But four powers are owner-only by construction —
 * {@code setPermittedAccessibilityServices}, {@code addUserRestriction},
 * {@code setAlwaysOnVpnPackage}, {@code setCameraDisabled}, plus
 * {@code setUserControlDisabledPackages} — and for those the call has to happen
 * <em>inside</em> 雫's process. So 雫 exposes a provider and we call it.
 * <p>
 * <b>A provider call, not a broadcast.</b> {@link ContentResolver#call} is
 * synchronous and answers with a {@link Bundle}, which is what a switch the user
 * just tapped needs; and {@code Binder.getCallingUid()} identifies us on the far
 * side, so unlike the 保存復元 broadcast contract there is no shared token to
 * carry — the binder is the identity. The gate is an allowlist 白い熊 sets in 雫.
 * <p>
 * <b>Degrades to nothing.</b> Every method answers {@link Result#unavailable} when
 * 雫 is not installed, is too old to have the provider, or has not authorized us.
 * No call here is ever load-bearing for a page rendering.
 */
public final class PolicyApiClient {
    public static final String TAG = PolicyApiClient.class.getSimpleName();

    private static final Uri AUTHORITY = Uri.parse("content://shiroikuma.shizuku.policy");

    // The wire contract. These strings are shared with 雫 — never rename one.
    public static final String METHOD_STATUS = "status";
    public static final String METHOD_ACCESSIBILITY_BLOCKED = "set_accessibility_blocked";
    public static final String METHOD_USER_CONTROL_DISABLED = "set_user_control_disabled";
    public static final String METHOD_USER_RESTRICTION = "set_user_restriction";
    public static final String METHOD_ALWAYS_ON_VPN = "set_always_on_vpn";
    public static final String METHOD_CAMERA_DISABLED = "set_camera_disabled";
    public static final String METHOD_CLEAR_ALL_LOCKS = "clear_all_locks";

    private static final String KEY_OK = "ok";
    private static final String KEY_ERROR = "error";
    private static final String KEY_PACKAGE = "package";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_BLOCKED = "blocked";
    private static final String KEY_KEY = "key";
    private static final String KEY_LOCKDOWN = "lockdown";
    private static final String KEY_IS_DEVICE_OWNER = "is_device_owner";

    private PolicyApiClient() {
    }

    /** What the far side said, including <em>why</em> when it said no. */
    public static final class Result {
        public final boolean ok;
        @Nullable
        public final String error;
        @Nullable
        public final Bundle extras;

        private Result(boolean ok, @Nullable String error, @Nullable Bundle extras) {
            this.ok = ok;
            this.error = error;
            this.extras = extras;
        }

        @NonNull
        public static Result unavailable() {
            return new Result(false, "unavailable", null);
        }

        /** True when 雫 answered at all — i.e. the provider exists and let us in. */
        public boolean reachable() {
            return extras != null;
        }
    }

    @WorkerThread
    @NonNull
    private static Result call(@NonNull String method, @Nullable Bundle extras) {
        try {
            Context context = ContextUtils.getContext();
            Bundle out = context.getContentResolver().call(AUTHORITY, method, null, extras);
            if (out == null) {
                return Result.unavailable();
            }
            return new Result(out.getBoolean(KEY_OK, false), out.getString(KEY_ERROR), out);
        } catch (Throwable th) {
            // 雫 absent, provider absent, or the call refused outright. Never fatal.
            Log.d(TAG, "policy call %s failed: %s", method, th.getMessage());
            return Result.unavailable();
        }
    }

    /**
     * Whether 雫 is reachable and is Device Owner. Answered even when we are not
     * on its allowlist, so the banner can say <em>why</em> we have no powers
     * rather than merely that we do not.
     */
    @WorkerThread
    @NonNull
    public static Result status() {
        return call(METHOD_STATUS, null);
    }

    @WorkerThread
    public static boolean isDeviceOwnerPresent() {
        Result r = status();
        return r.extras != null && r.extras.getBoolean(KEY_IS_DEVICE_OWNER, false);
    }

    /**
     * Block this app's accessibility service so nothing can enable it — not
     * Settings, not the app.
     * <p>
     * 雫 stores a <b>blocklist</b> and derives the platform's allowlist from it
     * (every installed accessibility service minus the blocked set, recomputed on
     * every package change). The platform API is an allowlist whose default means
     * "everything permitted", so used directly it bars every service installed
     * after it is set — which is why the inversion lives on that side and this
     * call is a plain per-package boolean.
     */
    @WorkerThread
    @NonNull
    public static Result setAccessibilityBlocked(@NonNull String packageName, boolean blocked) {
        Bundle in = new Bundle();
        in.putString(KEY_PACKAGE, packageName);
        in.putBoolean(KEY_BLOCKED, blocked);
        return call(METHOD_ACCESSIBILITY_BLOCKED, in);
    }

    /** Stop the app being force-stopped or having its data cleared from Settings. */
    @WorkerThread
    @NonNull
    public static Result setUserControlDisabled(@NonNull String packageName, boolean disabled) {
        Bundle in = new Bundle();
        in.putString(KEY_PACKAGE, packageName);
        in.putBoolean(KEY_ENABLED, disabled);
        return call(METHOD_USER_CONTROL_DISABLED, in);
    }

    /**
     * A device-wide user restriction. Not per-app, and 雫 refuses the keys that
     * would cut the routes needed to undo any of this (ADB, safe boot, factory
     * reset, sideloading) — so a refusal here can be a deliberate one.
     */
    @WorkerThread
    @NonNull
    public static Result setUserRestriction(@NonNull String key, boolean enabled) {
        Bundle in = new Bundle();
        in.putString(KEY_KEY, key);
        in.putBoolean(KEY_ENABLED, enabled);
        return call(METHOD_USER_RESTRICTION, in);
    }

    /**
     * Pin all traffic to one VPN app. With {@code lockdown} the phone has
     * <b>no network at all</b> whenever that app is not connected — including the
     * Wi-Fi ADB that would be used to undo it.
     */
    @WorkerThread
    @NonNull
    public static Result setAlwaysOnVpn(@Nullable String packageName, boolean lockdown) {
        Bundle in = new Bundle();
        in.putString(KEY_PACKAGE, packageName);
        in.putBoolean(KEY_LOCKDOWN, lockdown);
        return call(METHOD_ALWAYS_ON_VPN, in);
    }

    /** Device-wide camera kill switch. Instantly reversible from the same control. */
    @WorkerThread
    @NonNull
    public static Result setCameraDisabled(boolean disabled) {
        Bundle in = new Bundle();
        in.putBoolean(KEY_ENABLED, disabled);
        return call(METHOD_CAMERA_DISABLED, in);
    }

    /**
     * The escape hatch: release every device-policy lock on this package, or on
     * every package when {@code packageName} is null.
     * <p>
     * It lives in 雫 rather than here because it must work when 応用管理 is gone —
     * and because the Device Owner can undo what its delegate did, walking the
     * app's permissions for anything not at the default grant state, which needs
     * no ledger on either side.
     */
    @WorkerThread
    @NonNull
    public static Result clearAllLocks(@Nullable String packageName) {
        Bundle in = new Bundle();
        if (packageName != null) in.putString(KEY_PACKAGE, packageName);
        return call(METHOD_CLEAR_ALL_LOCKS, in);
    }
}
