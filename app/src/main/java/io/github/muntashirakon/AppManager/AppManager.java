// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager;

import android.app.Application;

import io.github.muntashirakon.AppManager.permission.PermissionOverrideManager;
import android.content.Context;
import android.os.Build;
import android.sun.security.provider.JavaKeyStoreProvider;

import androidx.annotation.Keep;

import com.topjohnwu.superuser.Shell;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.security.Security;

import dalvik.system.ZipPathValidator;
import io.github.muntashirakon.AppManager.main.AppPanePrefs;
import io.github.muntashirakon.AppManager.main.LastScreenPrefs;
import io.github.muntashirakon.AppManager.misc.AMExceptionHandler;
import io.github.muntashirakon.AppManager.settings.FeatureController;
import io.github.muntashirakon.AppManager.settings.Prefs;
import io.github.muntashirakon.AppManager.settings.PrivilegeWatchdog;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.Utils;
import io.github.muntashirakon.AppManager.utils.appearance.AppearanceUtils;

public class AppManager extends Application {
    static {
        Shell.enableVerboseLogging = BuildConfig.DEBUG;
        Shell.setDefaultBuilder(Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // We don't rely on the system to detect a zip slip attack
            ZipPathValidator.clearCallback();
        }
    }

    @Keep
    @Override
    public void onCreate() {
        super.onCreate();
        PermissionOverrideManager.reconcileAll();
        Thread.setDefaultUncaughtExceptionHandler(new AMExceptionHandler(this));
        AppearanceUtils.init(this);
        // Fork: AppPref writes every default into preferences.xml on first run, so a
        // changed default never reaches an existing install on its own.
        Prefs.Blocking.migrateDefaultFreezingMethod();
        Prefs.Blocking.migrateDefaultFreezingMethodToTotal();
        // Fork (白い熊, +038): same trap one layer up — a key added to the pane's visible set is
        // inert on an install whose pane has already been edited.
        AppPanePrefs.migrateClearDataVisible(this);
        AppPanePrefs.migrateFreezeLevelPills(this);
        // Fork: a Shizuku server pushes its binder whenever it likes, including while no activity of
        // ours exists, so the listeners that notice a lost/returned server have to be registered for
        // the life of the process rather than by a screen.
        PrivilegeWatchdog.install(this);
        // Fork (白い熊, +146): remember which screen is on top, so the launcher can return to it
        // even after EMUI has dropped the task. One recorder for every screen at once.
        LastScreenPrefs.install(this);
        // Fork (白い熊): a feature's flag travels in a settings export, its component's enabled state
        // does not — so an import onto a fresh install leaves the flag off and the manifest entry on,
        // and a switched-off Interceptor keeps answering every http/https link. Re-assert the disables
        // the flags already record (never an enable). Off the main thread: these are binder calls.
        ThreadUtils.postOnBackgroundThread(FeatureController::reassertDisabledComponents);
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
        Security.addProvider(new JavaKeyStoreProvider());
        Security.addProvider(new BouncyCastleProvider());
    }

    @Keep
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !Utils.isRoboUnitTest()) {
            HiddenApiBypass.addHiddenApiExemptions("L");
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL) {
            StaticDataset.cleanup();
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        StaticDataset.cleanup();
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        StaticDataset.cleanup();
    }
}
