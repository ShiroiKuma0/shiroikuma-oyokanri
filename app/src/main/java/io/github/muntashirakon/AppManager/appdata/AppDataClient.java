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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
    /**
     * How often the wait wakes to look around. Fork (白い熊, +143): one second, not five — this
     * loop is now also where a cancel is noticed, and five seconds of a dead Cancel button is
     * long enough to conclude it does not work.
     */
    private static final long POLL_MS = 1000L;

    /**
     * Asked whether the operation carrying this transfer has been cancelled.
     *
     * <p>Fork (白い熊, +143): a transfer can run for half an hour, and until now nothing inside
     * it ever looked. The batch's cancel checkpoint fires once per <b>app</b>, before that app's
     * work — so with one app in the batch, Cancel had nothing left to reach and the only way to
     * stop a 6 GB export was to kill the app. Passed in rather than read from a singleton, so
     * the data contract keeps knowing nothing about batch operations.
     */
    public interface Cancellation {
        boolean isCancelled();
    }

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
        return transfer(packageName, method, fd, items, listener, null);
    }

    @WorkerThread
    @NonNull
    public Result transfer(@NonNull String packageName, @NonNull String method,
                           @NonNull ParcelFileDescriptor fd, @Nullable List<String> items,
                           @Nullable ProgressListener listener,
                           @Nullable Cancellation cancellation) {
        // LANDMINE (白い熊, +116) — THE CALLEE MINTS THE JOB ID, NOT US.
        // Every sister app answers the call with `OK:<its own job id>` (AutomationJobs.begin())
        // and broadcasts its completion carrying THAT id. This client used to invent a UUID,
        // send it, and then filter replies on it — so the reply arrived, failed the correlation,
        // was dropped, and the transfer sat until the two-minute silence watchdog killed it.
        // A 22 MB ArcaneChat export that had already finished was reported as "Could not backup".
        // Our own id is still sent and still accepted (an app that echoes it costs nothing), but
        // the id the callee hands back is the one that matters.
        String ourId = UUID.randomUUID().toString();
        AtomicReference<String> acceptedId = new AtomicReference<>(ourId);
        // A terminal reply that arrives BEFORE call() returns cannot be correlated yet: at that
        // instant the callee's id is still unknown to us. Such replies are held here by id and
        // claimed the moment the id is adopted, or a fast (small) export would fail exactly
        // where a slow one now succeeds — the worse of the two bugs to ship.
        Map<String, String> unclaimed = new ConcurrentHashMap<>();
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
                if (id == null) {
                    return;
                }
                if (!ourId.equals(id) && !id.equals(acceptedId.get())) {
                    // Not (yet) ours. Another app's transfer in the same batch has its own
                    // receiver and will claim it; hold a terminal reply briefly in case this is
                    // our callee answering before we have learnt its id.
                    if (AppDataContract.ACTION_REPLY.equals(intent.getAction()) && unclaimed.size() < 32) {
                        String early = intent.getStringExtra(AppDataContract.EXTRA_RESULT);
                        unclaimed.put(id, early != null ? early : AppDataContract.ERROR_PREFIX + "empty reply");
                    }
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
            extras.putString(AppDataContract.EXTRA_JOB_ID, ourId);
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
            // Adopt the callee's job id, then claim anything that arrived under it while we were
            // still inside call().
            String theirs = accepted.substring(AppDataContract.OK_PREFIX.length()).trim();
            if (!theirs.isEmpty()) {
                acceptedId.set(theirs);
                String early = unclaimed.remove(theirs);
                if (early != null) {
                    terminal.offer(early);
                }
            }
            unclaimed.clear();
            return await(packageName, acceptedId, terminal, lastActivity, cancellation);
        } finally {
            try {
                mContext.unregisterReceiver(receiver);
            } catch (Throwable ignore) {
            }
        }
    }

    @NonNull
    private Result await(@NonNull String packageName, @NonNull AtomicReference<String> jobId,
                         @NonNull LinkedBlockingQueue<String> terminal,
                         @NonNull AtomicLong lastActivity,
                         @Nullable Cancellation cancellation) {
        while (true) {
            if (cancellation != null && cancellation.isCancelled()) {
                // Tell it to stop before we walk away, or it carries on writing into a
                // descriptor nobody is waiting for — the same courtesy the watchdog pays.
                cancel(packageName, jobId.get());
                return new Result(false, "cancelled");
            }
            String result;
            try {
                result = terminal.poll(POLL_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancel(packageName, jobId.get());
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
                cancel(packageName, jobId.get());
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
