// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.io;

import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Fork: {@link FileSystemService} entered as a <b>Shizuku user service</b>.
 * <p>
 * Shizuku instantiates the class named in {@code UserServiceArgs} and casts it to
 * {@link android.os.IBinder}, so the entry point has to <i>be</i> the service rather than return
 * one — which rules out {@link FileSystemManager#getService()}, whose concrete type is package
 * private. Subclassing it here, inside its own package, is the honest way to get a public entry
 * point without a transaction-forwarding shim.
 * <p>
 * Note this deliberately does not go through {@code FileSystemManager.getService()}'s singleton:
 * this process hosts nothing else, so the instance Shizuku creates is the only one there is.
 */
public class ShizukuFileSystemService extends FileSystemService {
    private static final String TAG = "ShizukuFsService";

    /**
     * {@code ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy}. A wire constant, spelled out
     * rather than imported so this module keeps its independence from the vendored Shizuku client
     * API in the app module.
     */
    private static final int USER_SERVICE_TRANSACTION_DESTROY = 16777115;

    public ShizukuFileSystemService() {
        super();
    }

    @Override
    public boolean onTransact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags)
            throws RemoteException {
        if (code == USER_SERVICE_TRANSACTION_DESTROY) {
            Log.i(TAG, "destroy requested");
            System.exit(0);
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }
}
