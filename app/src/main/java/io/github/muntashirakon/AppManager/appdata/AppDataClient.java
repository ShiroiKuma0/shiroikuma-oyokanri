// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.appdata;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.github.muntashirakon.AppManager.logs.Log;

/**
 * Fork: the caller side of the sister-app data contract (v2).
 * <p>
 * Two channels, and the split is load-bearing. A synchronous {@code ContentProvider.call()}
 * carries the handshake — it identifies us to the callee through the framework, which is what
 * removes the need for a shared secret, and it answers immediately, which is what a list the
 * user is looking at needs. The <b>payload never travels inside that call</b>: an export can be
 * tens of megabytes over minutes, and a binder call that long blocks the caller, reports no
 * progress and cannot be cancelled. Completion arrives instead as the plain broadcast the family
 * already proved on EMUI.
 */
public class AppDataClient {
    public static final String TAG = AppDataClient.class.getSimpleName();

    /**
     * §3's rule: an app silent for two minutes is presumed dead.
     * <p>
     * This is a <b>silence</b> watchdog and deliberately not a duration cap. A multi-gigabyte
     * export from the map app can legitimately run for twenty minutes, and a total-time limit
     * would kill it mid-write; what actually indicates death is an app that has stopped saying
     * anything at all.
     */
    private static final long SILENCE_TIMEOUT_MS = 2 * 60 * 1000L;
    private static final long POLL_MS = 5000L;

    /** Progress is real counts, never a percentage — 白い熊's explicit requirement. */
    public interface ProgressListener {
        void onProgress(@Nullable String label, long current, long total, @Nullable String unit);
    }

    /** Outcome of a transfer. {@code ok} plus the raw reply, so a refusal keeps its reason. */
    public static class Result {
        public final boolean ok;
        public final String message;

        Result(boolean ok, @NonNull String message) {
            this.ok = ok;
            this.message = message;
        }

        @NonNull
        @Override
        public String toString() {
            return (ok ? "OK" : "FAILED") + ": " + message;
        }
    }

    private final Context mContext;

    public AppDataClient(@NonNull Context context) {
        mContext = context.getApplicationContext();
    }

    /**
     * Ask an app to describe its data. Reads state and exports nothing, so it is safe to call
     * while building a list — but it does start the app, so it is not a substitute for the
     * manifest discovery in {@link AppDataContract}.
     */
    @WorkerThread
    @Nullable
    public AppDataHeader describe(@NonNull String packageName) {
        Bundle out = call(packageName, AppDataContract.METHOD_DESCRIBE, new Bundle());
        if (out == null) {
            return null;
        }
        String result = out.getString(AppDataContract.EXTRA_RESULT);
        if (result == null || !result.startsWith(AppDataContract.OK_PREFIX)) {
            Log.w(TAG, "describe refused by %s: %s", packageName, result);
            return null;
        }
        return AppDataHeader.parse(result.substring(AppDataContract.OK_PREFIX.length()));
    }

    /**
     * Run an export or an import to completion.
     * <p>
     * The descriptor is the whole payload mechanism: we open the destination and hand over an
     * fd, so the app writes bytes and nothing else. It is a capability that expires when it is
     * closed — no path, no URI, no grant and no revoke.
     */
    @WorkerThread
    @NonNull
    public Result transfer(@NonNull String packageName, @NonNull String method,
                           @NonNull ParcelFileDescriptor fd, @Nullable List<String> items,
                           @Nullable ProgressListener listener) {
        String jobId = UUID.randomUUID().toString();
        LinkedBlockingQueue<String> terminal = new LinkedBlockingQueue<>(1);
        AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) {
                    return;
                }
                // Correlate on the job id: several transfers may be in flight across a batch.
                String id = intent.getStringExtra(AppDataContract.EXTRA_JOB_ID);
                if (id == null) {
                    id = intent.getStringExtra(AppDataContract.EXTRA_REPLY_ID);
                }
                if (!jobId.equals(id)) {
                    return;
                }
                lastActivity.set(System.currentTimeMillis());
                if (AppDataContract.ACTION_PROGRESS.equals(intent.getAction())) {
                    if (listener != null) {
                        listener.onProgress(intent.getStringExtra(AppDataContract.EXTRA_RESULT),
                                intent.getLongExtra(AppDataContract.EXTRA_CURRENT, -1),
                                intent.getLongExtra(AppDataContract.EXTRA_TOTAL, -1),
                                intent.getStringExtra(AppDataContract.EXTRA_UNIT));
                    }
                    return;
                }
                String result = intent.getStringExtra(AppDataContract.EXTRA_RESULT);
                terminal.offer(result != null ? result : AppDataContract.ERROR_PREFIX + "empty reply");
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(AppDataContract.ACTION_REPLY);
        filter.addAction(AppDataContract.ACTION_PROGRESS);
        // The reply comes from another app, so the receiver has to be exported.
        ContextCompat.registerReceiver(mContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
        try {
            Bundle extras = new Bundle();
            extras.putParcelable(AppDataContract.EXTRA_FD, fd);
            extras.putString(AppDataContract.EXTRA_JOB_ID, jobId);
            extras.putString(AppDataContract.EXTRA_REPLY_ACTION, AppDataContract.ACTION_REPLY);
            extras.putString(AppDataContract.EXTRA_REPLY_PACKAGE, mContext.getPackageName());
            extras.putString(AppDataContract.EXTRA_PROGRESS_ACTION, AppDataContract.ACTION_PROGRESS);
            if (items != null && !items.isEmpty()) {
                // Only ever sent when 白い熊 has actually chosen for this app. Sending nothing is
                // NOT "everything" — it means the app's own recommended default set, which is the
                // right behaviour for an app nobody has customised.
                extras.putString(AppDataContract.EXTRA_ITEMS, TextUtils.join(",", items));
            }

            Bundle out = call(packageName, method, extras);
            if (out == null) {
                // "Could not find provider" is overwhelmingly the freeze, not a broken door —
                // 270 packages are frozen on this phone. The caller thaws before getting here.
                return new Result(false, "no answer from " + packageName + " (frozen, or no door)");
            }
            String accepted = out.getString(AppDataContract.EXTRA_RESULT);
            if (accepted == null || !accepted.startsWith(AppDataContract.OK_PREFIX)) {
                // A refusal, in the contract's own grammar. Includes the foreground-service start
                // failure, whose cure is a battery-optimisation exemption for that app rather
                // than any change to its code.
                return new Result(false, accepted != null ? accepted : "no result in reply");
            }
            return await(packageName, jobId, terminal, lastActivity);
        } finally {
            try {
                mContext.unregisterReceiver(receiver);
            } catch (Throwable ignore) {
            }
        }
    }

    @NonNull
    private Result await(@NonNull String packageName, @NonNull String jobId,
                         @NonNull LinkedBlockingQueue<String> terminal,
                         @NonNull AtomicLong lastActivity) {
        while (true) {
            String result;
            try {
                result = terminal.poll(POLL_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancel(packageName, jobId);
                return new Result(false, "interrupted");
            }
            if (result != null) {
                if (result.startsWith(AppDataContract.OK_PREFIX)) {
                    return new Result(true, result.substring(AppDataContract.OK_PREFIX.length()));
                }
                return new Result(false, result);
            }
            if (System.currentTimeMillis() - lastActivity.get() > SILENCE_TIMEOUT_MS) {
                // Presumed dead. Tell it to stop before giving up, so a job that is merely wedged
                // does not carry on writing into a descriptor nobody is waiting for.
                cancel(packageName, jobId);
                return new Result(false, "no progress for " + (SILENCE_TIMEOUT_MS / 1000) + "s");
            }
        }
    }

    /** Signal a running job to stop. Best effort by nature — the app may already be gone. */
    @WorkerThread
    public void cancel(@NonNull String packageName, @NonNull String jobId) {
        Bundle extras = new Bundle();
        extras.putString(AppDataContract.EXTRA_JOB_ID, jobId);
        call(packageName, AppDataContract.METHOD_CANCEL, extras);
    }

    /**
     * One provider call. Never throws: a frozen app, a missing door, a dead process and a
     * misbehaving callee all arrive here as exceptions of various kinds, and every one of them
     * means the same thing to us — this app cannot be used right now.
     */
    @Nullable
    private Bundle call(@NonNull String packageName, @NonNull String method, @NonNull Bundle extras) {
        Uri uri = Uri.parse("content://" + AppDataContract.authorityFor(packageName));
        try {
            return mContext.getContentResolver().call(uri, method, null, extras);
        } catch (Throwable th) {
            Log.w(TAG, "%s failed for %s", th, method, packageName);
            return null;
        }
    }
}
