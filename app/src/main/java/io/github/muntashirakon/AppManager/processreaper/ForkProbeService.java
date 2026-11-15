// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.processreaper;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;

import androidx.annotation.Nullable;

/**
 * Fork (白い熊, 2026-11-15): the process-start probe. An empty service in an
 * isolated process of its own: binding to it makes the system fork a fresh
 * process from zygote and install the app seccomp filter — exactly the path
 * that fails when "new processes cannot start" — so a connection proves a
 * process can be started right now, and a time-out proves it cannot.
 *
 * <p>It kills its own process as soon as it is unbound, so every probe forks
 * anew instead of reconnecting to the last one.
 */
public class ForkProbeService extends Service {
    private final IBinder mBinder = new Binder();

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Process.killProcess(Process.myPid());
        return false;
    }

    /**
     * The same probe in an ordinary (not isolated) process of this app,
     * {@code :forkprobeplain}: the path every app start takes. Probed side by side
     * with the isolated one, it shows whether only sandboxed processes fail.
     * {@code AppManager.onCreate} returns early in this process.
     */
    public static class Plain extends ForkProbeService {
        public static final String PROCESS_SUFFIX = ":forkprobeplain";
    }
}
