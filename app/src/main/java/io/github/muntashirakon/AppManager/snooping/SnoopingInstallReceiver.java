// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.snooping;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.UserHandleHidden;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.muntashirakon.AppManager.devicepolicy.PolicyEnforcer;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;

/**
 * Fork: lands the stored anti-snooping decisions the moment a package shows up.
 * <p>
 * Registered in the manifest rather than at runtime on purpose —
 * {@code ACTION_PACKAGE_ADDED} is one of the implicit broadcasts still delivered
 * to manifest receivers on Android 8+, and the whole point is to react to an
 * install that happens while 応用管理 is not running.
 * <p>
 * We deliberately handle the replace case too: an app update can reset an op or
 * re-request a permission the user had blocked, so a stored decision is
 * re-asserted on every update, not only on first install. Enforcement needs
 * ADB/Shizuku; when it is unavailable here, {@link SnoopingEnforcer#enforceAll}
 * picks the package up on the next app start.
 */
public class SnoopingInstallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(@NonNull Context context, @Nullable Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_PACKAGE_ADDED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // We were updated: re-assert everything, since anything could have
            // drifted while this build was not installed. Also forget every
            // "cannot be turned on" mark — a new build may write app-ops
            // differently from the one that recorded them (4.1.0+9 did), so they
            // are re-tested rather than trusted.
            SnoopingImmovable.clearAll();
            SnoopingEnforcer.enforceAllAsync(context);
            // Fork, +80: our own update takes no device-policy lock with it (they
            // are held under 雫's admin, not ours) — but this is a cheap, certain
            // moment to notice one that went missing for any other reason.
            PolicyEnforcer.replayAllAsync(appContext);
            return;
        }
        String packageName = packageNameOf(intent);
        if (packageName == null) {
            return;
        }
        // A new version of the app may well behave differently, so its marks go.
        SnoopingImmovable.clearPackage(packageName);
        int userId = userIdOf(intent);
        // Fork, +80: ONE runnable, not two posts. A fresh install has every
        // permission back at its default, so the snooping replay certainly writes
        // here — and it writes the same grant state a device-policy lock pins. The
        // lock has to land after it, and ThreadUtils' background executor is a
        // shared pool that would not have ordered two posts. See PolicyEnforcer.
        ThreadUtils.postOnBackgroundThread(() -> {
            if (SnoopingPrefs.isAutoApplyEnabled()) {
                SnoopingEnforcer.enforcePackage(appContext, packageName, userId);
            }
            PolicyEnforcer.replayPackage(packageName);
        });
    }

    @Nullable
    private static String packageNameOf(@NonNull Intent intent) {
        Uri data = intent.getData();
        if (data == null) {
            return null;
        }
        String pkg = data.getSchemeSpecificPart();
        return pkg != null && !pkg.isEmpty() ? pkg : null;
    }

    private static int userIdOf(@NonNull Intent intent) {
        // Intent.EXTRA_USER_HANDLE, hidden but stable since API 17.
        int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
        if (uid >= 0) {
            return UserHandleHidden.getUserId(uid);
        }
        return UserHandleHidden.myUserId();
    }
}
