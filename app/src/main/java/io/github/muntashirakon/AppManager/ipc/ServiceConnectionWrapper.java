// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.ipc;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.misc.NoOps;
import io.github.muntashirakon.AppManager.settings.Ops;
import io.github.muntashirakon.AppManager.settings.ShizukuOps;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;

class ServiceConnectionWrapper {
    public static final String TAG = ServiceConnectionWrapper.class.getSimpleName();

    @Nullable
    private volatile IBinder mIBinder;
    @Nullable
    private volatile CountDownLatch mServiceBoundWatcher;
    @Nullable
    private final Runnable mDeathCallback;

    private class ServiceConnectionImpl implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "service onServiceConnected: %s", name);
            synchronized (this) {
                // The callback may arrive after stopDaemon() cancelled the bind.
                if (mServiceBoundWatcher == null) {
                    if (mBoundViaShizuku) {
                        ShizukuOps.unbindUserService(mShizukuComponentName, this);
                    } else {
                        RootService.stop(new Intent().setComponent(mComponentName));
                    }
                    return;
                }
                mIBinder = service;
            }
            try {
                service.linkToDeath(() -> {
                    boolean binderDied;
                    synchronized (ServiceConnectionImpl.this) {
                        binderDied = mIBinder == service;
                        if (binderDied) {
                            mIBinder = null;
                        }
                    }
                    if (binderDied && mDeathCallback != null) {
                        mDeathCallback.run();
                    }
                }, 0);
            } catch (RemoteException e) {
                synchronized (this) {
                    mIBinder = null;
                }
            }
            onResponseReceived();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "service onServiceDisconnected: %s", name);
            onBinderLost();
            onResponseReceived();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.d(TAG, "service onBindingDied: %s", name);
            onBinderLost();
            onResponseReceived();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Log.d(TAG, "service onNullBinding: %s", name);
            onBinderLost();
            onResponseReceived();
        }

        private void onBinderLost() {
            boolean binderLost;
            synchronized (this) {
                binderLost = mIBinder != null;
                mIBinder = null;
            }
            if (binderLost && mDeathCallback != null) {
                mDeathCallback.run();
            }
        }

        private void onResponseReceived() {
            synchronized (this) {
                if (mServiceBoundWatcher != null) {
                    mServiceBoundWatcher.countDown();
                }
            }
        }
    }

    @NonNull
    private final ComponentName mComponentName;
    // Fork: the same service, entered as a Shizuku user service. A RootService is bound by starting
    // a process that calls back with the binder from onBind(); Shizuku instead instantiates a named
    // class that IS the binder, so the two paths cannot share a component name.
    @NonNull
    private final ComponentName mShizukuComponentName;
    @NonNull
    private final ServiceConnectionImpl mServiceConnection;
    // Fork: how the live service was actually launched, remembered rather than re-derived. Tearing
    // down reads the current mode otherwise, and the mode has usually already been changed to the
    // one we are switching TO by the time the old service is stopped — which would send the unbind
    // down the wrong path and leave a shell-uid process behind.
    private volatile boolean mBoundViaShizuku;

    public ServiceConnectionWrapper(@NonNull String pkgName, @NonNull String className,
                                    @NonNull String shizukuClassName, @Nullable Runnable deathCallback) {
        this(new ComponentName(pkgName, className), new ComponentName(pkgName, shizukuClassName), deathCallback);
    }

    public ServiceConnectionWrapper(@NonNull ComponentName cn, @NonNull ComponentName shizukuCn,
                                    @Nullable Runnable deathCallback) {
        mComponentName = cn;
        mShizukuComponentName = shizukuCn;
        mDeathCallback = deathCallback;
        mServiceConnection = new ServiceConnectionImpl();
    }

    @NonNull
    public IBinder getService() throws RemoteException {
        if (!isBinderActive()) {
            throw new RemoteException("Binder not running.");
        }
        return Objects.requireNonNull(mIBinder);
    }

    @NonNull
    @NoOps(used = true)
    public IBinder bindService() throws RemoteException {
        if (!isBinderActive()) {
            startDaemon();
        }
        return getService();
    }

    @MainThread
    public void unbindService() {
        synchronized (mServiceConnection) {
            if (mBoundViaShizuku) {
                // Fork: Shizuku owns the process, so releasing our side is its unbind, not ours.
                ShizukuOps.unbindUserService(mShizukuComponentName, mServiceConnection);
                mBoundViaShizuku = false;
                mIBinder = null;
                return;
            }
            RootService.unbind(mServiceConnection);
        }
    }

    @WorkerThread
    private void startDaemon() {
        CountDownLatch serviceBoundWatcher;
        synchronized (mServiceConnection) {
            if (isBinderActive()) {
                Log.d(TAG, "Binder is already active?");
                return;
            }
            serviceBoundWatcher = mServiceBoundWatcher;
            if (serviceBoundWatcher == null) {
                serviceBoundWatcher = new CountDownLatch(1);
                mServiceBoundWatcher = serviceBoundWatcher;
                Log.d(TAG, "Launching service...");
                // Fork: in Shizuku mode there is no shell to write an app_process line into — Shizuku
                // performs that launch itself and delivers the binder through the very same
                // ServiceConnection, so only the launch differs and everything downstream of
                // onServiceConnected is untouched.
                if (Ops.isShizuku()) {
                    mBoundViaShizuku = true;
                    // Called straight from this worker thread, NOT posted to the main one: unlike
                    // RootService.bind (which libsu requires to be main-thread), binding a Shizuku
                    // user service is a plain synchronous binder transaction, and running it on the
                    // main thread would be a blocking IPC there for no reason. The reply still
                    // arrives on the main looper — ShizukuServiceConnection posts it — so the latch
                    // is counted down exactly as in the ADB path.
                    try {
                        ShizukuOps.bindUserService(mShizukuComponentName, mServiceConnection);
                    } catch (Throwable th) {
                        Log.e(TAG, "Could not bind the Shizuku user service.", th);
                        // A throw here means no callback is ever coming; count down now rather than
                        // stall the caller for the full 45 s.
                        serviceBoundWatcher.countDown();
                    }
                } else {
                    Intent intent = new Intent();
                    intent.setComponent(mComponentName);
                    ThreadUtils.postOnMainThread(() -> {
                        if (mIBinder != null) {
                            RootService.stop(intent);
                        }
                        RootService.bind(intent, mServiceConnection);
                    });
                }
            }
        }
        // Wait for service to be bound without holding mServiceConnection. The stop path may
        // need the same lock to cancel a bind that never receives a callback.
        try {
            serviceBoundWatcher.await(45, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e(TAG, "Service watcher interrupted.");
        } finally {
            synchronized (mServiceConnection) {
                if (mServiceBoundWatcher == serviceBoundWatcher) {
                    mServiceBoundWatcher = null;
                }
            }
        }
    }

    @WorkerThread
    public void stopDaemon() {
        if (mBoundViaShizuku) {
            ShizukuOps.unbindUserService(mShizukuComponentName, mServiceConnection);
            mBoundViaShizuku = false;
            mIBinder = null;
            return;
        }
        Intent intent = new Intent();
        intent.setComponent(mComponentName);
        CountDownLatch serviceBoundWatcher;
        synchronized (mServiceConnection) {
            mIBinder = null;
            serviceBoundWatcher = mServiceBoundWatcher;
            mServiceBoundWatcher = null;
        }
        if (serviceBoundWatcher != null) {
            serviceBoundWatcher.countDown();
        }
        ThreadUtils.postOnMainThread(() -> RootService.stop(intent));
    }

    boolean isBinderActive() {
        return mIBinder != null && mIBinder.pingBinder();
    }
}
