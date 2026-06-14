// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.processreaper;

import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
            "shiroikuma.oyokanri", "appmanager", "am.jar", "main.jar", "shizuku",
            // The ADB-mode bootstrap that launches our privileged server: its
            // children are sh → sh → :priv:0, so it's the true root of the chain.
            "am_local_server"
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
                                  @NonNull Set<String> userProtected, @NonNull Set<String> activePackages,
                                  @NonNull Set<Integer> ancestryProtected, @NonNull Set<String> allowedOverride) {
        String pkg = (item instanceof AppProcessItem) ? ((AppProcessItem) item).packageInfo.packageName : null;
        int uid = item.uid;
        // Package-based hard protections (only match real app packages, uid >= 10000).
        if (pkg != null) {
            if (pkg.equals(selfPkg)) return prot("self");
            if (pkg.equals(activeImePkg)) return prot("keyboard");
            // Built-in denylist — protected by default, but the user can override
            // it (long-press → "Allow killing") EXCEPT for the privilege chain
            // (matches SHELL_PROTECT_SUBSTR), which must stay un-killable.
            if (DENYLIST.contains(pkg)
                    && !(allowedOverride.contains(pkg) && !matchesShellProtect(pkg))) {
                return prot("protected");
            }
            // User-marked protected apps (reason "you" → offer to un-protect).
            if (userProtected.contains(pkg)) return prot("you");
            // Actively in use: the foreground/top app, or a process with a live
            // foreground service (media, navigation, recorder, sync). Killing
            // these is disruptive, so protect them.
            if (activePackages.contains(pkg)) return prot("in use");
        }
        // Our own process subtree — the privileged server and every shell it
        // spawned to run commands (incl. the very shell that executes the kill).
        // These arrive as bare `sh` tagged com.android.shell (uid 2000) with no
        // protective cmdline substring, so only ancestry catches them; killing one
        // severs our kill chain and fails ("Couldn't kill"). Must precede the
        // uid-2000 SIGKILL branch below.
        if (ancestryProtected.contains(item.pid)) return prot("privilege");
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

    /**
     * The pids of our own process subtree: the app, the privileged server (whose
     * name carries a {@link #SHELL_PROTECT_SUBSTR} marker), Shizuku, and — via the
     * {@code pid → ppid} map — every descendant shell they spawned. A descendant
     * `sh` has cmdline just "sh", so only ancestry (not name) can protect it; that
     * shell is what {@link io.github.muntashirakon.AppManager.runner.Runner} pipes
     * commands through, so a reaper that kills it severs its own kill ability.
     * Orphaned helpers (PPid reparented to init) are NOT descendants and stay
     * killable — the genuine-leak case is untouched.
     */
    @NonNull
    public static Set<Integer> ancestryProtectedPids(@NonNull List<ProcessItem> list,
                                                     @NonNull Map<Integer, Integer> ppidByPid,
                                                     @NonNull Set<Integer> seedRoots,
                                                     @Nullable String selfPkg) {
        int myPid = Process.myPid();
        // seedRoots come from a direct /proc/*/cmdline grep (reliable for the
        // uid-2000 :priv:0 server, which ProcessParser mislabels); the name/pkg
        // scan below is a belt-and-suspenders fallback.
        Set<Integer> roots = new HashSet<>(seedRoots);
        for (ProcessItem p : list) {
            if (p.pid == myPid) {
                roots.add(p.pid);
                continue;
            }
            String pkg = (p instanceof AppProcessItem) ? ((AppProcessItem) p).packageInfo.packageName : null;
            if (selfPkg != null && selfPkg.equals(pkg)) {
                roots.add(p.pid);
                continue;
            }
            String hay = lower(p.name) + " " + lower(p.getCommandlineArgsAsString());
            for (String s : SHELL_PROTECT_SUBSTR) {
                if (hay.contains(s)) {
                    roots.add(p.pid);
                    break;
                }
            }
        }
        // Expand UP through the private bootstrap chain. Our privileged server
        // (matched by package) sits at the BOTTOM of am_local_server → sh → sh →
        // :priv:0, so the intermediate shells are ANCESTORS of the marked pid and a
        // pure descendant-closure misses them. Walk up via PPid from each uid-2000
        // root, adding ancestors, stopping before init (pid <= 1). ONLY from
        // uid-2000 roots: the app / shizuku-api are zygote children (uid >= 10000)
        // and walking up from them would reach the shared zygote and over-protect.
        Set<Integer> shellRoots = new HashSet<>();
        for (ProcessItem p : list) {
            if (p.uid == SHELL_UID && roots.contains(p.pid)) shellRoots.add(p.pid);
        }
        for (Integer rp : shellRoots) {
            int cur = rp;
            for (int guard = 0; guard < 64; guard++) {
                Integer par = ppidByPid.get(cur);
                if (par == null || par <= 1) break;  // reached init / unknown
                roots.add(par);
                cur = par;
            }
        }
        // A pid is ours if walking its PPid chain reaches a root.
        Set<Integer> ours = new HashSet<>(roots);
        for (ProcessItem p : list) {
            if (ours.contains(p.pid)) continue;
            int cur = p.pid;
            for (int guard = 0; guard < 64; guard++) {
                Integer par = ppidByPid.get(cur);
                if (par == null || par == 0) break;
                if (roots.contains(par) || ours.contains(par)) {
                    ours.add(p.pid);
                    break;
                }
                cur = par;
            }
        }
        return ours;
    }

    /** True if the package/name carries a privilege-chain marker (never overridable). */
    private static boolean matchesShellProtect(@NonNull String pkgOrName) {
        String low = lower(pkgOrName);
        for (String s : SHELL_PROTECT_SUBSTR) {
            if (low.contains(s)) return true;
        }
        return false;
    }

    /**
     * True if {@code pkg} is on the built-in denylist AND not the privilege chain —
     * i.e. the user is allowed to override its protection (long-press → Allow).
     * The UI uses this to decide whether long-press toggles the override.
     */
    public static boolean isOverridableDenylist(@Nullable String pkg) {
        return pkg != null && DENYLIST.contains(pkg) && !matchesShellProtect(pkg);
    }

    @NonNull
    private static String lower(@Nullable String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
