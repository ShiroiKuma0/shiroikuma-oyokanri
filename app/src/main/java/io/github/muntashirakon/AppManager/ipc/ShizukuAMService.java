// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.ipc;

import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Fork: the AM service, entered as a <b>Shizuku user service</b> instead of through AM's own
 * ADB-over-TCP bootstrap.
 * <p>
 * The privileged half of 応用管理 is {@link AMService.IAMServiceImpl} — a plain
 * {@code IAMService.Stub} with no dependency on a {@link RootService} context — and how its process
 * comes into being is not its business. In ADB mode {@link RootServiceManager} writes an
 * {@code app_process} command line into a uid-2000 shell and waits for the server to broadcast the
 * binder back. Shizuku performs that same launch itself: its server runs
 * {@code app_process … af.shizuku.starter.ServiceStarter --class=<this class>}, loads our APK via
 * {@code createPackageContextAsUser}, instantiates the named class and hands the resulting
 * {@link android.os.IBinder} to the client. So the only thing needed on our side is a public class
 * with a public no-argument constructor that <i>is</i> the binder.
 * <p>
 * <b>The contract, verified against the sister repo's {@code rikka.shizuku.server.UserService}:</b>
 * the class is loaded reflectively, a {@code (Context)} constructor is preferred and a no-arg one is
 * the fallback, and the instance is cast to {@code IBinder} — so it must not be abstract, its
 * constructor must not throw, and it must not need anything from the application's own data
 * directory (the process runs as shell, which cannot read {@code /data/data/<us>}).
 */
public class ShizukuAMService extends AMService.IAMServiceImpl {
    private static final String TAG = "ShizukuAMService";

    /**
     * {@code ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy}. Spelled as a literal rather than
     * imported so this class keeps compiling if the vendored client API is ever trimmed further; it
     * is a wire constant and cannot change.
     */
    private static final int USER_SERVICE_TRANSACTION_DESTROY = 16777115;

    public ShizukuAMService() {
        super();
        Log.i(TAG, "AM service started as a Shizuku user service, uid=" + android.os.Process.myUid());
    }

    @Override
    public boolean onTransact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags)
            throws RemoteException {
        if (code == USER_SERVICE_TRANSACTION_DESTROY) {
            // Shizuku asks the service to tear itself down. There is nothing to unwind — the
            // process exists only to host this binder — so exiting is the whole of it. Returning
            // without exiting would leave an orphan shell-uid process behind on every unbind.
            Log.i(TAG, "destroy requested");
            System.exit(0);
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }
}
