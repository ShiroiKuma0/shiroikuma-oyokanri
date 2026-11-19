// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.utils;

import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.ipc.LocalServices;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.runner.Runner;
import io.github.muntashirakon.AppManager.settings.Ops;

/**
 * Fork (白い熊, +121): carries an enabled-state change of a system app through the death of
 * {@code system_server}.
 *
 * <p><b>Why.</b> Measured 2026-11-19: any {@code PACKAGE_CHANGED} for {@code com.huawei.lbs}
 * makes EMUI's {@code HwLocationProviderProxy} register a service watcher twice, which throws
 * inside the system process — a soft reboot on every tap, and the write is lost because the
 * crash comes before the package restrictions are flushed. An unfreeze could never succeed.
 * Nothing says which other system packages have a watcher like that.
 *
 * <p><b>How.</b> What does succeed is the same change made in the first instant of the
 * <i>restarted</i> system, before the watcher is listening. The app cannot do that — it dies
 * with the system — so before the change a detached shell script is started on the privileged
 * side (shell uid, outside the app world, so it survives). It watches the system process for a
 * few seconds; if it survives, the script exits and nothing was different. If it dies, the
 * script spins on {@code cmd package <verb>} until the new package service accepts it, and
 * leaves a report for the next start of the app.
 *
 * <p>Two files per package in {@code <external files>/state-guard/}, never named {@code *.log}
 * (EMUI deletes those): {@code pending-<pkg>.txt}, written by the app before the change and
 * removed when the call comes back — so one that is still there means the system died under
 * it — and {@code <pkg>.txt}, written by the script only when it had to step in. A report that
 * has been shown is kept as {@code seen-<pkg>.txt}: that is the record of which packages are
 * known to bring the system down.
 */
public final class EnabledStateGuard {
    private static final String TAG = "EnabledStateGuard";
    private static final String DIR = "state-guard";
    private static final String PENDING = "pending-";
    private static final String SEEN = "seen-";
    private static final String EXT = ".txt";
    /** How long a pending marker waits for the script's report before it is called a failure. */
    private static final long REPORT_GRACE_MS = 3 * 60 * 1000L;

    /** Proven on the phone, so known before the first report exists. */
    private static final String[] BUILT_IN = {"com.huawei.lbs"};

    /*
     * $1 package  $2 user  $3 verb  $4 the answer that means "accepted"  $5 report file
     * No single quote may appear in here: the whole script travels inside one.
     */
    private static final String SCRIPT = ""
            + "old=$(pidof system_server); i=0; "
            + "while [ $i -lt 60 ]; do "
            + "[ \"$(pidof system_server)\" = \"$old\" ] || break; sleep 0.1; i=$((i+1)); "
            + "done; "
            + "[ \"$(pidof system_server)\" = \"$old\" ] && exit 0; "
            + "n=0; ok=0; "
            + "while [ $n -lt 30000 ]; do n=$((n+1)); "
            + "case \"$(cmd package $3 --user $2 $1 2>&1)\" in *\"$4\"*) ok=1; break;; esac; "
            + "done; "
            + "at=$(date +%H:%M:%S); sleep 20; "
            + "now=$(pidof system_server); "
            + "st=$(dumpsys package $1 | grep -m1 -o \"enabled=[0-9]*\"); "
            + "j=0; "
            + "until printf \"package=%s\\nverb=%s\\naccepted=%s\\ntries=%s\\nat=%s\\nstate=%s\\n\" "
            + "\"$1\" \"$3\" \"$ok\" \"$n\" \"$at\" \"$st\" > \"$5\" 2>/dev/null; do "
            + "j=$((j+1)); [ $j -gt 150 ] && exit 1; sleep 2; "
            + "done";

    private EnabledStateGuard() {
    }

    /** What the guard found after a restart, for the app to tell. */
    public static final class Report {
        @NonNull
        public final String packageName;
        /** Whether the script wrote a report at all. False: the system died and nothing caught it. */
        public final boolean guarded;
        /** Whether the change was accepted by the restarted system. */
        public final boolean accepted;
        /** Whether the change was an enable (a thaw) rather than a disable. */
        public final boolean enable;

        Report(@NonNull String packageName, boolean guarded, boolean accepted, boolean enable) {
            this.packageName = packageName;
            this.guarded = guarded;
            this.accepted = accepted;
            this.enable = enable;
        }
    }

    /**
     * Arm the guard for one enabled-state change. Never throws and never blocks the change: a
     * guard that could not be armed leaves things exactly as they were before it existed.
     *
     * @return a token for {@link #disarm}, or {@code null} when nothing was armed.
     */
    @WorkerThread
    @Nullable
    public static File arm(@NonNull String packageName, @UserIdInt int userId, int newState) {
        try {
            String verb;
            String answer;
            if (newState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                verb = "enable";
                answer = "new state: enabled";
            } else if (newState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                verb = "disable-user";
                answer = "new state: disabled-user";
            } else {
                return null;
            }
            if (!Ops.isDirectRoot() && !LocalServices.alive()) {
                // No privileged side to run the script on — and then no way to make the change.
                return null;
            }
            if (!isSystemApp(packageName, userId) || !isSafeForShell(packageName)) {
                return null;
            }
            File dir = dir();
            if (dir == null) {
                return null;
            }
            File pending = new File(dir, PENDING + packageName + EXT);
            File report = new File(dir, packageName + EXT);
            //noinspection ResultOfMethodCallIgnored
            report.delete();
            try (FileOutputStream os = new FileOutputStream(pending)) {
                os.write(("package=" + packageName + "\nverb=" + verb + "\n").getBytes(StandardCharsets.UTF_8));
            }
            // setsid: its own session, so it is not taken down with whatever started it.
            String command = "setsid sh -c '" + SCRIPT + "' guard " + packageName + " " + userId
                    + " " + verb + " \"" + answer + "\" \"" + report.getAbsolutePath() + "\""
                    + " >/dev/null 2>&1 </dev/null &";
            Runner.runCommand(command);
            // Let the script read the system's pid before the change can kill it.
            SystemClock.sleep(250);
            return pending;
        } catch (Throwable th) {
            Log.e(TAG, "Could not arm for " + packageName, th);
            return null;
        }
    }

    /**
     * The call came back. That alone proves nothing — the crash follows the call by a fraction
     * of a second — so the marker is withdrawn a little later, and without holding up the
     * caller: a batch over a hundred system apps must not wait three seconds for each. If the
     * system dies in between, so does this process, and the marker stays to say so.
     */
    public static void disarm(@Nullable File token) {
        if (token != null) {
            //noinspection ResultOfMethodCallIgnored
            new Handler(Looper.getMainLooper()).postDelayed(token::delete, 3000);
        }
    }

    /**
     * What happened while the app was dead. Each report is returned once and then kept as the
     * record of a package known to need the guard.
     */
    @WorkerThread
    @NonNull
    public static List<Report> collectReports() {
        List<Report> out = new ArrayList<>();
        try {
            File dir = dir();
            File[] files = dir != null ? dir.listFiles() : null;
            if (files == null) {
                return out;
            }
            for (File pending : files) {
                String name = pending.getName();
                if (!name.startsWith(PENDING) || !name.endsWith(EXT)) {
                    continue;
                }
                String pkg = name.substring(PENDING.length(), name.length() - EXT.length());
                File report = new File(dir, pkg + EXT);
                boolean enable = !"disable-user".equals(read(pending).get("verb"));
                if (report.isFile()) {
                    Map<String, String> fields = read(report);
                    out.add(new Report(pkg, true, "1".equals(fields.get("accepted")), enable));
                    File seen = new File(dir, SEEN + pkg + EXT);
                    //noinspection ResultOfMethodCallIgnored
                    seen.delete();
                    if (!report.renameTo(seen)) {
                        //noinspection ResultOfMethodCallIgnored
                        report.delete();
                    }
                } else if (System.currentTimeMillis() - pending.lastModified() < REPORT_GRACE_MS) {
                    // The script writes only once storage is back; ask again on the next look.
                    continue;
                } else {
                    out.add(new Report(pkg, false, false, enable));
                    try (FileOutputStream os = new FileOutputStream(new File(dir, SEEN + pkg + EXT))) {
                        os.write(("package=" + pkg + "\naccepted=0\nguarded=0\n").getBytes(StandardCharsets.UTF_8));
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                pending.delete();
            }
        } catch (Throwable th) {
            Log.e(TAG, "Could not read the reports", th);
        }
        return out;
    }

    /** Whether changing this package's enabled state is known to restart the system. */
    public static boolean isKnownToRestartSystem(@NonNull String packageName) {
        for (String known : BUILT_IN) {
            if (known.equals(packageName)) {
                return true;
            }
        }
        try {
            File dir = dir();
            return dir != null && new File(dir, SEEN + packageName + EXT).isFile();
        } catch (Throwable th) {
            return false;
        }
    }

    private static boolean isSystemApp(@NonNull String packageName, @UserIdInt int userId) {
        try {
            ApplicationInfo info = PackageManagerCompat.getApplicationInfo(packageName,
                    PackageManagerCompat.MATCH_UNINSTALLED_PACKAGES
                            | PackageManagerCompat.MATCH_DISABLED_COMPONENTS, userId);
            return info != null && (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Throwable th) {
            return false;
        }
    }

    /** The name is spliced into a shell command; a package name is letters, digits, dots, underscores. */
    private static boolean isSafeForShell(@NonNull String packageName) {
        return packageName.matches("[A-Za-z0-9._]+");
    }

    @Nullable
    private static File dir() {
        Context context = ContextUtils.getContext();
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            return null;
        }
        File dir = new File(base, DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return null;
        }
        return dir;
    }

    @NonNull
    private static Map<String, String> read(@NonNull File file) {
        Map<String, String> out = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    out.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
        } catch (Throwable ignore) {
            // Unreadable is the same as empty here.
        }
        return out;
    }
}
