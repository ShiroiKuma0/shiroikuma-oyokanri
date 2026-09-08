// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.utils;

import android.os.Build;
import android.text.TextUtils;

import io.github.muntashirakon.AppManager.misc.SystemProperties;

public final class HuaweiUtils {
    public static boolean isHuaweiDevice() {
        String manufacturer = Build.MANUFACTURER;
        String brand = Build.BRAND;
        return manufacturer.equalsIgnoreCase("HUAWEI") || brand.equalsIgnoreCase("HUAWEI");
    }

    // Fork (白い熊, +161): these three read ANDROID system properties, and used to be written as
    // java.lang.System.getProperty(), which reads the JVM's own property table and can never see a
    // "ro.*" build property. Every one of them therefore returned null, isStockHuawei() was
    // permanently false, and the Huawei guard it exists for -- the one in
    // PackageInstallerCompat.openSession, commented "Changing package installer in stock Huawei with
    // UID 2000 does not work" -- has never once fired on a Huawei phone. That is what let a restore
    // attribute its install session to 応用管理, which EMUI's own SilentInstallPolicy then refused:
    //     W/SilentInstallPolicy: installer: shiroikuma.oyokanri, ... allowed: false
    // Measured on the Mate XT: getprop ro.build.version.emui = "EmotionUI_14.2.0", while
    // System.getProperty("ro.build.version.emui") = null. MiuiUtils.isMiui() had it right all along.
    public static boolean isEmui() {
        return !TextUtils.isEmpty(SystemProperties.get("ro.build.version.emui", ""));
    }

    public static boolean isHarmonyOs() {
        return !TextUtils.isEmpty(SystemProperties.get("ro.harmony.version", ""))
                || !TextUtils.isEmpty(SystemProperties.get("ro.build.version.harmony", ""));
    }

    /**
     * Fork: HarmonyOS 4 and later ship as "MagicOS"/"HarmonyOS NEXT" on some SKUs and drop the emui
     * property; accept either rather than tying the answer to one vendor string.
     */
    public static boolean isMagicOs() {
        return !TextUtils.isEmpty(SystemProperties.get("ro.build.version.magic", ""));
    }

    public static boolean isStockHuawei() {
        return isHuaweiDevice() && (isHarmonyOs() || isEmui() || isMagicOs());
    }
}
