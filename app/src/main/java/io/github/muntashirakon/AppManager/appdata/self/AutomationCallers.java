// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.appdata.self;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.muntashirakon.AppManager.BuildConfig;

/**
 * Fork (白い熊, +153): who may come through this app's data door.
 *
 * <p>The family's rule is an <b>allowlist of exact package names</b>, and each one is checked
 * three ways, because any one of them alone is not enough:
 *
 * <ol>
 *   <li><b>The name</b> must be on the list.</li>
 *   <li><b>The uid the kernel reports</b> must actually own that name — packages that share a
 *       uid are not told apart by the name alone.</li>
 *   <li><b>The signing certificate</b> must match a pinned SHA-256. This is the one that
 *       matters most on the case this contract exists for: a <em>clean phone</em>, where an
 *       allowlisted package may not be installed yet and its name is therefore a name anyone
 *       can take.</li>
 * </ol>
 *
 * <p>The pins were derived on this machine with {@code apksigner verify --print-certs} against
 * the APKs actually installed on the phone, not copied from anywhere — re-derive them the same
 * way rather than trusting these constants if either app is ever re-signed.
 *
 * <p>Our own uid keeps a fast path above all of this: same uid is the same app, which is
 * strictly stronger than any of the three checks.
 */
public final class AutomationCallers {
    /** Package → pinned SHA-256 of its signing certificate, lower-case hex. */
    private static final Map<String, String> PINS = new LinkedHashMap<String, String>() {{
        // 白い熊 応用管理 itself — for a call that arrives from our own package under a
        // different uid (a second user, a work profile), where the fast path does not apply.
        put(BuildConfig.APPLICATION_ID,
                "9c585f4d118cb97ff653f949a8872875548403b9083ce6b9baa2e8f0c55ac6cc");
        // 白い熊 自由作業盤 — the automation app that drives the 保存復元 batch. Without this
        // entry it cannot back 応用管理 up through the door at all (白い熊, 2026-09-06).
        put("shiroikuma.jiyusagyoban",
                "efd0d352192651593a92288ecdc64fc87262ec8648c24ed8f51a5587d46ac602");
    }};

    private AutomationCallers() {
    }

    /**
     * @return {@code null} when the caller may proceed, otherwise the short reason to refuse
     *         with — in the contract's {@code ERROR:<short reason>} grammar, minus the prefix.
     */
    @Nullable
    public static String refuse(@NonNull Context context, int callingUid,
                                @Nullable String callingPackage) {
        if (callingUid == Process.myUid()) {
            // Same uid is the same app. Nothing below can be stronger than that.
            return null;
        }
        String packageName = resolve(context, callingUid, callingPackage);
        if (packageName == null) {
            return "caller not permitted: uid " + callingUid;
        }
        String pin = PINS.get(packageName);
        if (pin == null) {
            return "caller not permitted: " + packageName;
        }
        if (!signatureMatches(context, packageName, pin)) {
            return "caller signature mismatch: " + packageName;
        }
        return null;
    }

    /**
     * The caller's package name, but only if the uid really owns it. A name the framework hands
     * us is still cross-checked against {@code getPackagesForUid}, and when the caller names
     * nothing we look for an allowlisted package among the uid's own.
     */
    @Nullable
    private static String resolve(@NonNull Context context, int callingUid,
                                  @Nullable String callingPackage) {
        String[] owned = context.getPackageManager().getPackagesForUid(callingUid);
        if (owned == null) {
            return null;
        }
        for (String candidate : owned) {
            if (callingPackage != null) {
                if (callingPackage.equals(candidate)) {
                    return candidate;
                }
            } else if (PINS.containsKey(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean signatureMatches(@NonNull Context context, @NonNull String packageName,
                                            @NonNull String pinHex) {
        byte[] pin = fromHex(pinHex);
        if (pin == null) {
            return false;
        }
        PackageManager pm = context.getPackageManager();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Rotation-aware, and the platform does the comparison: it answers for the
                // certificate the package is signed with now, and for its lineage.
                return pm.hasSigningCertificate(packageName, pin, PackageManager.CERT_INPUT_SHA256);
            }
            @SuppressWarnings("deprecation")
            PackageInfo info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
            @SuppressWarnings("deprecation")
            Signature[] signatures = info.signatures;
            if (signatures == null) {
                return false;
            }
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            for (Signature signature : signatures) {
                if (MessageDigest.isEqual(sha256.digest(signature.toByteArray()), pin)) {
                    return true;
                }
            }
        } catch (Throwable th) {
            return false;
        }
        return false;
    }

    @Nullable
    private static byte[] fromHex(@NonNull String hex) {
        int length = hex.length();
        if (length % 2 != 0) {
            return null;
        }
        byte[] out = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
