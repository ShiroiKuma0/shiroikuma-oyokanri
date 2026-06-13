// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.processreaper;

import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import io.github.muntashirakon.AppManager.runningapps.AppProcessItem;
import io.github.muntashirakon.AppManager.runningapps.ProcessItem;

/**
 * Fork: PROTECTED-vs-KILLABLE classifier for the process monitor / reaper, per
 * the reaper hand-off. Fail-safe: when in doubt, PROTECTED.
 *
 * <p>Kill routing (verified as uid 2000 shell): an app package → PM force-stop;
 * a package-less <b>shell-owned</b> (uid 2000) process → raw {@code kill -9}
 * (shell can signal its own uid); uid &lt; 10000 non-shell → needs root.
 *
 * <p>Critical safety: AppManager's own privileged server ALSO runs as uid 2000,
 * as does Shizuku's — a naive "shell process = killable" would let the reaper
 * sever its own privilege. Those cmdlines are hard-protected here.
 */
public final class ProcessClassifier {
    private ProcessClassifier() {}

    public static final int METHOD_NONE = 0;        // protected — no kill offered
    public static final int METHOD_FORCE_STOP = 1;  // app package → PM force-stop (works as shell)
    public static final int METHOD_SIGKILL = 2;     // package-less shell pid → kill -9

    private static final int SHELL_UID = 2000;      // android.os.Process.SHELL_UID (@hide)

    // Killing any of these (as a package) breaks the device or our own privilege chain.
    private static final Set<String> DENYLIST = new HashSet<>(Arrays.asList(
            "net.dinglisch.android.taskerm",
            "com.joaomgcd.autonotification", "com.joaomgcd.autoshare", "com.joaomgcd.autoappshub",
            "com.joaomgcd.autotools", "com.joaomgcd.taskersettings",
            "com.termux", "com.termux.tasker",
            "moe.shizuku.privileged.api",
            "io.github.sds100.keymapper",
            "com.twofasapp",
            "com.huawei.android.launcher", "com.android.systemui"
    ));

    // A uid-2000 process whose cmdline contains any of these IS the privilege
    // chain (our server / Shizuku) — never offer to kill it.
    private static final String[] SHELL_PROTECT_SUBSTR = {
            "shiroikuma.oyokanri", "appmanager", "am.jar", "main.jar", "shizuku"
    };

    public static final class Result {
        public final boolean killable;
        public final int method;
        @NonNull
        public final String reason;   // short badge / explanation tag
        Result(boolean killable, int method, @NonNull String reason) {
            this.killable = killable;
            this.method = method;
            this.reason = reason;
        }
    }

    private static Result prot(@NonNull String reason) {
        return new Result(false, METHOD_NONE, reason);
    }

    @NonNull
    public static Result classify(@NonNull ProcessItem item, @Nullable String selfPkg,
                                  @Nullable String activeImePkg, boolean workingUidRoot,
                                  @NonNull Set<String> userProtected) {
        String pkg = (item instanceof AppProcessItem) ? ((AppProcessItem) item).packageInfo.packageName : null;
        int uid = item.uid;
        // Package-based hard protections (only match real app packages, uid >= 10000).
        if (pkg != null) {
            if (pkg.equals(selfPkg)) return prot("self");
            if (pkg.equals(activeImePkg)) return prot("keyboard");
            if (DENYLIST.contains(pkg)) return prot("protected");
            // User-marked protected apps (reason "you" → offer to un-protect).
            if (userProtected.contains(pkg)) return prot("you");
        }
        // Shell-owned (uid 2000): the orphaned-helper case — killable via raw
        // SIGKILL, EXCEPT the privilege chain (our server / Shizuku).
        if (uid == SHELL_UID) {
            String cmd = lower(item.name);
            for (String s : SHELL_PROTECT_SUBSTR) {
                if (cmd.contains(s)) return prot("privilege");
            }
            return new Result(true, METHOD_SIGKILL, "shell");
        }
        // Other system/native (root/system/media/HALs/vendor). Not killable as
        // shell; kept protected even under root for now.
        if (uid < Process.FIRST_APPLICATION_UID) return prot("system");
        // App range (uid >= 10000).
        if (pkg != null) return new Result(true, METHOD_FORCE_STOP, "app");
        // Package-less app-uid process — can't address as shell.
        if (workingUidRoot) return new Result(true, METHOD_SIGKILL, "root");
        return prot("needs root");
    }

    @NonNull
    private static String lower(@Nullable String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
