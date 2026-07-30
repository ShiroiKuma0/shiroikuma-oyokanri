// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.settings;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Process;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.misc.NoOps;
import rikka.shizuku.Shizuku;

/**
 * Fork: everything 応用管理 needs to know about a Shizuku server, in one place.
 * <p>
 * <b>Why this exists at all.</b> ADB-over-TCP is not the privilege — it is only the delivery van for
 * a single {@code app_process} command line, after which every privileged call in the app goes
 * through the {@code IAMService} binder ({@code Runner.getInstance()} branches on
 * {@code LocalServices.alive()} and nothing else). Shizuku performs that same launch itself, so
 * pointing the binder at a Shizuku user service gives identical privileges — uid 2000, same
 * {@code NETWORK_SETTINGS}, same app-op behaviour — while removing a listening {@code adbd} on a TCP
 * port, the port scan, the pairing keys, and the {@code INTERNET} dependency from the privilege
 * path.
 * <p>
 * <b>Which server we talk to.</b> The binder is <i>pushed</i>: whichever server is running resolves
 * our {@code ShizukuProvider} by the authority {@code <packageName>.shizuku} and calls it. So there
 * is no connection for us to aim, and the preference expressed here — 白い熊 雫
 * ({@link #MANAGER_FORK}) before stock ({@link #MANAGER_STOCK}) — governs the places where a choice
 * genuinely exists: what the status line names, and which manager gets launched when the server
 * is not up.
 * <p>
 * <b>Landmine — a client is only visible to the server if it <i>requests</i> the right permission
 * name.</b> {@code BinderSender}/{@code sendBinderToClient} filter installed packages by
 * {@code requestedPermissions}, so the manifest must declare a name the server looks for or the
 * binder is never sent and nothing else matters. 白い熊 雫 accepts three names
 * ({@code af.shizuku.plus}, the {@code af.shizuku.manager} legacy one, and stock's
 * {@code moe.shizuku.manager}); stock Shizuku accepts only its own. 応用管理 therefore declares
 * <i>both</i> {@link #PERMISSION_FORK} and {@link #PERMISSION_STOCK} — a {@code <uses-permission>}
 * naming a permission no installed package defines is simply inert, but it still shows up in
 * {@code requestedPermissions}, which is all the filter reads.
 * <p>
 * Note the OS-level grant of those names is <i>not</i> the authorization check: that is
 * {@link Shizuku#checkSelfPermission()}, answered by the server itself.
 */
public final class ShizukuOps {
    public static final String TAG = ShizukuOps.class.getSimpleName();

    /** 白い熊 雫 — our own fork of Shizuku, preferred whenever it is installed. */
    public static final String MANAGER_FORK = "shiroikuma.shizuku";
    /** Stock Shizuku (and the Compat Hub stub, which shares the id). */
    public static final String MANAGER_STOCK = "moe.shizuku.privileged.api";

    /** Defined by 白い熊 雫 itself, so it is a real grantable permission whenever that app is installed. */
    public static final String PERMISSION_FORK = "af.shizuku.plus.permission.API_V23";
    /** Stock's name. Defined by stock Shizuku or by the Compat Hub; inert when neither is installed. */
    public static final String PERMISSION_STOCK = "moe.shizuku.manager.permission.API_V23";

    private static final int PERMISSION_REQUEST_CODE = 0x5A17;
    /** How long to wait for the server to push its binder after the provider is up. */
    private static final long BINDER_TIMEOUT_MS = 10_000L;
    /** How long to wait for 白い熊 to answer the server's authorization prompt. */
    private static final long PERMISSION_TIMEOUT_MS = 120_000L;

    private ShizukuOps() {
    }

    /**
     * The installed manager, ours first. {@code null} when no Shizuku-family app is installed at
     * all, which is the one case where this mode can never work.
     */
    @AnyThread
    @NoOps
    @Nullable
    public static String getManagerPackage(@NonNull Context context) {
        PackageManager pm = context.getPackageManager();
        for (String pkg : new String[]{MANAGER_FORK, MANAGER_STOCK}) {
            try {
                pm.getPackageInfo(pkg, 0);
                return pkg;
            } catch (PackageManager.NameNotFoundException ignore) {
            }
        }
        return null;
    }

    @AnyThread
    @NoOps
    public static boolean isInstalled(@NonNull Context context) {
        return getManagerPackage(context) != null;
    }

    /** Whether a server is up and has handed us a live binder. */
    @AnyThread
    @NoOps
    public static boolean isServerRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable th) {
            return false;
        }
    }

    /** Whether the server has authorized us. Never throws — a dead binder simply means "no". */
    @AnyThread
    @NoOps
    public static boolean isAuthorized() {
        if (!isServerRunning()) {
            return false;
        }
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable th) {
            Log.w(TAG, "Could not read the Shizuku permission state", th);
            return false;
        }
    }

    /**
     * Ready to bind right now, with no user interaction needed. This is what auto-detection asks:
     * auto mode must never raise a permission prompt on its own.
     */
    @AnyThread
    @NoOps
    public static boolean isReady() {
        return isServerRunning() && isAuthorized();
    }

    /** The uid the server runs as — 2000 for a shell-started server, 0 when it was started by root. */
    @AnyThread
    @NoOps
    public static int getServerUid() {
        try {
            return Shizuku.getUid();
        } catch (Throwable th) {
            return Process.myUid();
        }
    }

    /**
     * Block until the server pushes its binder, or the timeout expires.
     * <p>
     * The push is asynchronous and driven by the server noticing our process: it resolves our
     * provider and calls it. A sticky listener covers the case where that already happened before
     * we started waiting.
     */
    @WorkerThread
    @NoOps
    public static boolean awaitBinder(long timeoutMs) {
        if (isServerRunning()) {
            return true;
        }
        CountDownLatch latch = new CountDownLatch(1);
        Shizuku.OnBinderReceivedListener listener = latch::countDown;
        Shizuku.addBinderReceivedListenerSticky(listener);
        try {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS) && isServerRunning();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            Shizuku.removeBinderReceivedListener(listener);
        }
    }

    /**
     * Ask the server for authorization and block until 白い熊 answers.
     * <p>
     * The prompt is the <i>server's</i>, raised over binder, so this needs no Activity of ours — but
     * it does need a live binder, hence the {@link #awaitBinder(long)} gate in
     * {@link #prepare(Context)}.
     */
    @WorkerThread
    @NoOps
    public static boolean requestPermissionBlocking() {
        if (isAuthorized()) {
            return true;
        }
        if (Shizuku.isPreV11()) {
            // Pre-v11 servers have no permission API at all; authorization there is the OS grant,
            // which we cannot obtain on our own. Nothing useful to do but report it.
            Log.w(TAG, "Shizuku server is pre-v11; cannot request authorization");
            return false;
        }
        if (Shizuku.isLegacyAttach()) {
            // Measured on-device 2026-07-30: this server dispatches every client through its legacy
            // switch, where code 14 is attachApplication — the same code the v13 AIDL uses for
            // requestPermission. So the call would return cleanly, show nothing, and we would sit
            // here for the whole timeout waiting on a prompt that cannot exist. Fail fast and let
            // the caller point 白い熊 at the manager's own authorisation list instead.
            Log.w(TAG, "Server attached us on the legacy transaction; the in-app prompt is"
                    + " unreachable, authorisation must come from the manager UI");
            return false;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger(PackageManager.PERMISSION_DENIED);
        Shizuku.OnRequestPermissionResultListener listener = (requestCode, grantResult) -> {
            if (requestCode == PERMISSION_REQUEST_CODE) {
                result.set(grantResult);
                latch.countDown();
            }
        };
        Shizuku.addRequestPermissionResultListener(listener);
        try {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE);
            if (!latch.await(PERMISSION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Timed out waiting for the Shizuku authorization prompt");
                return false;
            }
            return result.get() == PackageManager.PERMISSION_GRANTED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable th) {
            Log.e(TAG, "Could not request Shizuku authorization", th);
            return false;
        } finally {
            Shizuku.removeRequestPermissionResultListener(listener);
        }
    }

    /** {@link #prepare(Context, boolean)} succeeded — the user services can be bound. */
    public static final int READY = 0;
    /** No Shizuku-family app is installed at all. */
    public static final int NOT_INSTALLED = 1;
    /** Installed, but no server handed us a binder: it is almost certainly not started. */
    public static final int NOT_RUNNING = 2;
    /** A server is up but has not authorised us, and either the prompt was declined or we may not ask. */
    public static final int NOT_AUTHORISED = 3;
    /**
     * A server is up and we are attached, but it cannot raise its own prompt — 白い熊 has to allow us
     * from the manager's app list. Distinct from {@link #NOT_AUTHORISED} because the instruction is
     * different: there is no request coming, so telling anyone to "accept the request" is a dead end.
     */
    public static final int NOT_AUTHORISED_MANAGER_REQUIRED = 4;

    /**
     * Get the binder and authorization in place so the user services can be bound.
     * <p>
     * The three failures are deliberately distinguished: "install one", "start it" and "allow it"
     * are three different things for 白い熊 to go and do, and a single "Shizuku unavailable" would
     * send them to the wrong one.
     *
     * @param interactive whether 白い熊 may be prompted. Auto-detection passes {@code false}: a mode
     *                    chosen for us must not put a dialog on screen.
     */
    @WorkerThread
    @NoOps
    public static int prepare(@NonNull Context context, boolean interactive) {
        if (!isInstalled(context)) {
            Log.w(TAG, "No Shizuku-family manager is installed");
            return NOT_INSTALLED;
        }
        if (!awaitBinder(BINDER_TIMEOUT_MS)) {
            Log.w(TAG, "Shizuku server did not send a binder; is it running?");
            return NOT_RUNNING;
        }
        if (isAuthorized()) {
            return READY;
        }
        if (interactive && requestPermissionBlocking()) {
            return READY;
        }
        return Shizuku.isLegacyAttach() ? NOT_AUTHORISED_MANAGER_REQUIRED : NOT_AUTHORISED;
    }

    /**
     * Bring the manager to the front so 白い熊 can authorise us there. Best effort: a missing or
     * launcher-less manager simply means nothing happens, which is no worse than the toast alone.
     */
    @AnyThread
    @NoOps
    public static void openManager(@NonNull Context context) {
        String pkg = getManagerPackage(context);
        if (pkg == null) {
            return;
        }
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
        } catch (Throwable th) {
            Log.w(TAG, "Could not open the Shizuku manager", th);
        }
    }

    /**
     * Launch a user service. The component names a class in <i>our</i> APK that is itself an
     * {@code IBinder}; Shizuku starts {@code app_process} as the server's uid, loads our package and
     * instantiates it.
     * <p>
     * {@code version(BuildConfig.VERSION_CODE)} is what makes a stale service die after an app
     * update — Shizuku restarts a user service whose recorded version no longer matches, which
     * matters a great deal for a fork that ships a new build several times a day. {@code daemon} is
     * off so the process follows the app rather than outliving it.
     */
    @WorkerThread
    @NoOps
    public static void bindUserService(@NonNull ComponentName componentName, @NonNull ServiceConnection conn) {
        Shizuku.bindUserService(userServiceArgs(componentName), conn);
    }

    @AnyThread
    @NoOps
    public static void unbindUserService(@NonNull ComponentName componentName, @Nullable ServiceConnection conn) {
        try {
            Shizuku.unbindUserService(userServiceArgs(componentName), conn, true);
        } catch (Throwable th) {
            Log.w(TAG, "Could not unbind the Shizuku user service", th);
        }
    }

    @NonNull
    private static Shizuku.UserServiceArgs userServiceArgs(@NonNull ComponentName componentName) {
        return new Shizuku.UserServiceArgs(componentName)
                .daemon(false)
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE)
                .processNameSuffix(processNameSuffix(componentName));
    }

    /**
     * A short, stable process-name suffix, e.g. {@code shiroikuma.oyokanri:am}. Short because it
     * ends up as the visible process name of a shell-uid process — one 白い熊 will meet again in the
     * process monitor — and stable because Shizuku keys its user-service records partly on it.
     */
    @NonNull
    private static String processNameSuffix(@NonNull ComponentName componentName) {
        String cls = componentName.getClassName();
        String simpleName = cls.substring(cls.lastIndexOf('.') + 1);
        if (simpleName.startsWith("Shizuku")) {
            simpleName = simpleName.substring("Shizuku".length());
        }
        if (simpleName.endsWith("Service")) {
            simpleName = simpleName.substring(0, simpleName.length() - "Service".length());
        }
        return simpleName.isEmpty() ? "svc" : simpleName.toLowerCase(Locale.ROOT);
    }
}
