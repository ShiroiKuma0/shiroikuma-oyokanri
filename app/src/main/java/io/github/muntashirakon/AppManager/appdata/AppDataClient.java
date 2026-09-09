// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.appdata;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.content.ContextCompat;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.runner.Runner;

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
     * The ceiling for an app that is both silent AND doing nothing.
     * <p>
     * This is a <b>silence</b> watchdog and deliberately not a duration cap. A multi-gigabyte
     * export from the map app can legitimately run for twenty minutes, and a total-time limit
     * would kill it mid-write; what actually indicates death is an app that has stopped saying
     * anything at all.
     * <p>
     * Fork (白い熊): raised from two minutes, and no longer the only signal — see
     * {@link #CPU_PROBE_MS}. It was chosen when silence was all we measured; with CPU liveness
     * beside it this bound only bites on an app that is quiet and burning nothing, and the
     * progress page has carried a Cancel button since +143 for the case where a person can see
     * something is wrong sooner than a timer can.
     */
    private static final long SILENCE_TIMEOUT_MS = 10 * 60 * 1000L;
    /**
     * Fork (白い熊): how often the wait asks the OS whether the app is actually working.
     * <p>
     * Silence is a poor death signal on its own, and two minutes of it was killing healthy
     * imports: メッセージ, 猫管, 連絡先, FairEmail, 書籍閲覧 and 地図 — every one of them a
     * data-heavy sister app — died at exactly 120s while restoring, where the small ones passed.
     * That is the shape of a watchdog shooting work in progress, not of six broken apps.
     * <p>
     * So talking is no longer the only evidence of life. {@code /proc/<pid>/stat} is
     * world-readable and the fork already samples it for the process monitor; if the target's
     * CPU time has moved since the last probe, the app is working and the silence clock is
     * reset. An app that is genuinely wedged burns no CPU and still dies — which is what this
     * watchdog is for. Ten seconds is far below the ceiling and costs one small read per probe.
     */
    private static final long CPU_PROBE_MS = 10 * 1000L;
    /**
     * How often the wait wakes to look around. Fork (白い熊, +143): one second, not five — this
     * loop is now also where a cancel is noticed, and five seconds of a dead Cancel button is
     * long enough to conclude it does not work.
     */
    private static final long POLL_MS = 1000L;
    /**
     * Fork (白い熊): how often the wait says something even when the app has not.
     * <p>
     * 白い熊 watched a 4 GB 地図 restore print nothing between 13:18:52 and 13:28:20 — nine and a
     * half minutes of a log that looked frozen. It was not frozen; the app was unpacking and
     * simply had nothing to announce, and the CPU probe was quietly keeping the transfer alive
     * the whole time. But a log that says nothing for ten minutes is indistinguishable from a
     * hung one, and that is a real cost even when the work is fine. So the wait reports its own
     * liveness on a fixed beat, and says which of the two it is seeing.
     */
    private static final long HEARTBEAT_MS = 5 * 1000L;

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
        /**
         * Fork (白い熊, +181): this transfer was given up on, rather than answered.
         * <p>
         * The caller has to know, because an abandoned callee is still holding its own dup of the
         * descriptor and still writing. See {@code AppDataTransfer} — the writes are stopped, not
         * left to fail.
         */
        public final boolean abandoned;

        Result(boolean ok, @NonNull String message) {
            this(ok, message, false);
        }

        Result(boolean ok, @NonNull String message, boolean abandoned) {
            this.ok = ok;
            this.message = message;
            this.abandoned = abandoned;
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
        // Bumped ONLY by something the app itself said. lastActivity is also moved by the CPU
        // probe, so it cannot be used to decide whether the log has gone quiet.
        AtomicLong lastReport = new AtomicLong(System.currentTimeMillis());
        // Fork (白い熊): what this transfer actually HEARD, so a timeout can say why.
        //
        // 地図 reported 4,328,521,526 of 4,328,521,526 bytes — a completed import — and was then
        // failed by the watchdog; FairEmail reported 0/0, meaning nothing to do, and was failed
        // too. An app that has finished goes quiet and stops using CPU, so both liveness signals
        // read exactly as they would on a dead one. Which leaves one question the log could not
        // answer: did the terminal reply never arrive, or did it arrive under an id this client
        // failed to match? That is the +116 landmine's shape, and these counters separate the two.
        AtomicInteger heardProgress = new AtomicInteger();
        AtomicInteger heardTerminal = new AtomicInteger();
        AtomicInteger heardForeign = new AtomicInteger();
        Set<String> foreignIds = Collections.synchronizedSet(new LinkedHashSet<>());

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
                    heardForeign.incrementAndGet();
                    if (foreignIds.size() < 8) {
                        foreignIds.add(id);
                    }
                    Log.d(TAG, "%s: broadcast %s carried id %s, which is neither ours (%s) nor the"
                                    + " adopted one (%s).", packageName, intent.getAction(), id,
                            ourId, acceptedId.get());
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
                lastReport.set(System.currentTimeMillis());
                if (AppDataContract.ACTION_PROGRESS.equals(intent.getAction())) {
                    heardProgress.incrementAndGet();
                    if (listener != null) {
                        listener.onProgress(progressLabel(intent),
                                intent.getLongExtra(AppDataContract.EXTRA_CURRENT, -1),
                                intent.getLongExtra(AppDataContract.EXTRA_TOTAL, -1),
                                intent.getStringExtra(AppDataContract.EXTRA_UNIT));
                    }
                    return;
                }
                heardTerminal.incrementAndGet();
                String result = intent.getStringExtra(AppDataContract.EXTRA_RESULT);
                Log.d(TAG, "%s: terminal reply under id %s: %s", packageName, id, result);
                terminal.offer(result != null ? result : AppDataContract.ERROR_PREFIX + "empty reply");
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(AppDataContract.ACTION_REPLY);
        filter.addAction(AppDataContract.ACTION_PROGRESS);
        // LANDMINE (白い熊): the receiver MUST NOT run on the main thread.
        //
        // A registerReceiver() with no scheduler delivers on the main looper, and this receiver
        // is the ONLY thing that bumps lastActivity -- i.e. the main thread decides whether the
        // transfer is alive, while the watchdog counts down on a worker thread that is never
        // blocked. During a batch restore that is exactly backwards: up to one worker per core
        // are transferring at once and the progress page is appending thousands of OpLog lines,
        // so the main looper is the busiest thread in the process. Progress broadcasts then sit
        // in its queue, lastActivity goes stale while the app on the other side is working
        // perfectly well, and the worker declares "no progress for 120s".
        //
        // The tell in the log is unmistakable: a progress line timestamped the SAME SECOND as the
        // timeout that killed the transfer (白い熊 messeji, 12:09:09, 718/4,257 messages), and
        // several apps timing out together after ~120s. The bytes were moving the whole time; the
        // evidence of it was queued behind the UI.
        //
        // A private HandlerThread per transfer keeps liveness independent of the main thread.
        HandlerThread progressThread = new HandlerThread("AppDataClient-" + packageName);
        progressThread.start();
        Handler progressHandler = new Handler(progressThread.getLooper());
        // The reply comes from another app, so the receiver has to be exported.
        ContextCompat.registerReceiver(mContext, receiver, filter, null, progressHandler,
                ContextCompat.RECEIVER_EXPORTED);
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
            Log.d(TAG, "%s: sent id %s, callee answered with id %s.", packageName, ourId, theirs);
            if (!theirs.isEmpty()) {
                acceptedId.set(theirs);
                String early = unclaimed.remove(theirs);
                if (early != null) {
                    terminal.offer(early);
                }
            }
            // Fork (白い熊): a terminal reply held under an id we never adopt is dropped here, and
            // until now silently. If the callee answers call() with one id and broadcasts under
            // another, this is exactly where the completed transfer disappears.
            if (!unclaimed.isEmpty()) {
                Log.w(TAG, "%s: discarding %d held repl%s under unmatched id(s) %s — adopted was %s.",
                        packageName, unclaimed.size(), unclaimed.size() == 1 ? "y" : "ies",
                        unclaimed.keySet(), acceptedId.get());
            }
            unclaimed.clear();
            return await(packageName, ourId, acceptedId, terminal, lastActivity, lastReport,
                    listener, cancellation, heardProgress, heardTerminal, heardForeign, foreignIds);
        } finally {
            try {
                mContext.unregisterReceiver(receiver);
            } catch (Throwable ignore) {
            }
            progressThread.quitSafely();
        }
    }

    @NonNull
    private Result await(@NonNull String packageName, @NonNull String ourId,
                         @NonNull AtomicReference<String> jobId,
                         @NonNull LinkedBlockingQueue<String> terminal,
                         @NonNull AtomicLong lastActivity,
                         @NonNull AtomicLong lastReport,
                         @Nullable ProgressListener listener,
                         @Nullable Cancellation cancellation,
                         @NonNull AtomicInteger heardProgress,
                         @NonNull AtomicInteger heardTerminal,
                         @NonNull AtomicInteger heardForeign,
                         @NonNull Set<String> foreignIds) {
        long startedAt = System.currentTimeMillis();
        boolean cpuMoving = false;
        // Fork (白い熊): CPU liveness. -1 means "not sampled yet"; a probe that cannot answer
        // (no process, no privilege, unparseable) leaves both untouched, so it can only ever
        // EXTEND the deadline on positive evidence and never shorten it.
        long lastCpuTicks = -1;
        String frozenNote = null;
        long lastCpuProbe = 0;
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
            long now = System.currentTimeMillis();
            // Ask the OS whether the app is working before believing that silence means death.
            if (now - lastCpuProbe >= CPU_PROBE_MS) {
                lastCpuProbe = now;
                frozenNote = cpuMoving ? null : frozenState(packageName);
                long ticks = cpuTicks(packageName);
                if (ticks >= 0) {
                    if (lastCpuTicks >= 0) {
                        cpuMoving = ticks > lastCpuTicks;
                    }
                    if (cpuMoving) {
                        // It is running. Silent, but running — that is not death.
                        lastActivity.set(now);
                    }
                    lastCpuTicks = ticks;
                }
            }
            // Say something on a fixed beat whenever the app itself has gone quiet, so a long
            // silent stretch reads as work rather than as a hang.
            if (listener != null && now - lastReport.get() >= HEARTBEAT_MS) {
                lastReport.set(now);
                // Fork (白い熊, +181): three states, not two. "Waiting for the app" is right for an
                // app that is merely slow and wrong for one the OS has stopped dead -- and the
                // second is the common case on this phone off the charger, where EMUI puts a
                // sister app in a freezer cgroup while 応用管理 sits in cpuset:/vip. Probed on the
                // CPU cadence, not the heartbeat's, so it costs one extra file read per 10s.
                String state = cpuMoving ? null : frozenNote;
                listener.onProgress(mContext.getString(cpuMoving
                                ? R.string.appdata_still_working
                                : (state != null ? R.string.appdata_still_frozen
                                        : R.string.appdata_still_waiting),
                        elapsed(now - startedAt)), -1, -1, null);
            }
            if (System.currentTimeMillis() - lastActivity.get() > SILENCE_TIMEOUT_MS) {
                // Presumed dead. Tell it to stop before giving up, so a job that is merely wedged
                // does not carry on writing into a descriptor nobody is waiting for.
                cancel(packageName, jobId.get());
                // Fork (白い熊): carry the evidence into the failure itself. This message lands in
                // the closing Failures block, which is the one surface readable on a phone with no
                // cable attached — "0 replies, 1 unheard id" and "0 replies, 0 unheard ids" mean
                // completely different bugs, and the log could not previously tell them apart.
                // Fork (白い熊): SHORT. Op-log rows are single-line and middle-ellipsized (+157),
                // and the first version of this message was long enough that the ellipsis ate the
                // counts — the one part worth reading. So the visible message carries only what
                // changes the diagnosis, and the id list goes to logcat.
                //
                // "0 progress, 0 replies" while other transfers' broadcasts WERE arriving means
                // the app accepted the job and never ran it. Anything non-zero means the opposite.
                StringBuilder heard = new StringBuilder();
                heard.append("silent ").append(SILENCE_TIMEOUT_MS / 60000).append("m · heard ")
                        .append(heardProgress.get()).append(" progress, ")
                        .append(heardTerminal.get()).append(" replies");
                if (ourId.equals(jobId.get())) {
                    // Abnormal, and worth the extra words: we are still filtering on the id we
                    // invented, so anything the app sends under its own id cannot be matched.
                    heard.append(" · callee gave no id");
                }
                Log.w(TAG, "%s: %s; %d broadcasts for other ids %s; sent %s, adopted %s",
                        packageName, heard, heardForeign.get(), foreignIds, ourId, jobId.get());
                // Fork (白い熊, +181): name the OS as the cause when it IS the cause. A frozen
                // process is silent AND burns no CPU, so it fails both halves of the liveness test
                // and lands here looking exactly like an app that has wedged -- which is what sent
                // 白い熊 hunting through PowerGenie and iAware for an evening.
                String frozen = frozenState(packageName);
                if (frozen != null) {
                    heard.append(" · ").append(frozen);
                }
                Log.w(TAG, "%s: %s", packageName, heard);
                return new Result(false, heard.toString(), true);
            }
        }
    }

    /**
     * Fork (白い熊, +181): the progress label, read from the extra the family actually sends.
     *
     * <p><b>{@code "text"} is the convention and this app was the one deviating.</b> 自由作業盤's
     * {@code StateExportReceiver} — the reference implementation every sister app mirrors — defines
     * {@code EXTRA_PROGRESS_TEXT = "text"} and uses {@code "result"} only for the terminal reply,
     * and a sweep of all 57 sister repos found 26 of the 31 that report progress sending
     * {@code "text"} alone. 応用管理's OWN {@code StateExportReceiver} sends {@code "text"} too. So
     * this client emitted one key and read another, and silently discarded the progress label of
     * essentially the whole family — which is why long operations looked mute for months, and why
     * two sister chats were sent to "fix" code that was correct.
     *
     * <p>{@code "result"} is still accepted as a fallback: three apps now send both keys, and one
     * of them adopted that shape on my own bad advice. Reading either costs nothing and can never
     * regress them.
     */
    @Nullable
    private static String progressLabel(@NonNull Intent intent) {
        String text = intent.getStringExtra(AppDataContract.EXTRA_TEXT);
        return text != null ? text : intent.getStringExtra(AppDataContract.EXTRA_RESULT);
    }

    /** Fork (白い熊): enough of a job id to compare two of them by eye in a screenshot. */
    @NonNull
    private static String shortId(@Nullable String id) {
        if (id == null) {
            return "<none>";
        }
        return id.length() <= 8 ? id : id.substring(0, 8) + "…";
    }

    /** Fork (白い熊): m:ss for the heartbeat. Deliberately local — this layer knows no op log. */
    @NonNull
    private static String elapsed(long ms) {
        long total = Math.max(0, ms) / 1000L;
        long minutes = total / 60;
        long seconds = total % 60;
        return minutes > 0 ? minutes + "m " + seconds + "s" : seconds + "s";
    }

    /**
     * Fork (白い熊): total CPU time (utime + stime, in clock ticks) of every process of
     * {@code packageName}, or {@code -1} when that cannot be established.
     * <p>
     * {@code /proc/<pid>/stat} is world-readable — unlike {@code smaps_rollup} and {@code io},
     * which are ptrace-gated and unreadable cross-process for the shell uid — so this needs no
     * privilege beyond what a running batch already has. The field layout after
     * {@code pid (comm)} is the one the process monitor already parses: [11] = utime,
     * [12] = stime.
     * <p>
     * Note what this can and cannot see: an app pegged on the CPU is obvious, an app blocked on
     * slow storage accumulates no ticks at all. That second case is exactly why the silence
     * ceiling above was raised rather than removed — the two signals cover different failures.
     */
    @WorkerThread
    /**
     * Fork (白い熊, +181): whether the OS has parked this app, read from its own cgroup placement.
     *
     * <p>Measured by the 辞書 chat on this phone: off the charger EMUI puts a sister app in
     * {@code freezer:/Group_…} with {@code cpuset:/background} while 応用管理 sits in
     * {@code cpuset:/vip}, and every thread of the app goes to D state until the cable goes back
     * in. A foreground service with an ongoing notification does <b>not</b> prevent it — that was
     * measured too, with {@code isForeground=true} and a live notification throughout.
     *
     * <p>Such an app is silent and burns no CPU, so it fails both halves of the liveness test and
     * is abandoned after ten minutes looking exactly like one that has wedged. We cannot move it —
     * cgroup placement is not ours to change without root, and {@code preflight}'s levers
     * (unsuspend, thaw, Doze exemption) are all AOSP ones that do not touch it. What we can do is
     * stop reporting a mystery: this turns "it went quiet" into "the OS stopped it", which is a
     * different problem with a different fix (plug the phone in).
     *
     * <p>{@code /proc/<pid>/cgroup} is world-readable — verified on-device, unlike
     * {@code smaps_rollup} beside it, which is ptrace-gated and returns EPERM for the shell.
     *
     * @return a short note for the log, or null when the app is not parked or cannot be read
     */
    @Nullable
    private static String frozenState(@NonNull String packageName) {
        try {
            Runner.Result r = Runner.runCommand("for p in $(pidof " + packageName
                    + " 2>/dev/null); do cat /proc/$p/cgroup 2>/dev/null; done");
            if (r == null || !r.isSuccessful()) {
                return null;
            }
            boolean frozen = false;
            boolean background = false;
            for (String line : r.getOutputAsList()) {
                int colon = line.lastIndexOf(':');
                if (colon < 0 || colon + 1 >= line.length()) {
                    continue;
                }
                String path = line.substring(colon + 1).trim();
                // A freezer path deeper than the root IS a freezer group; the root is the normal,
                // unfrozen placement and must not be reported.
                if (line.contains(":freezer:") && !path.equals("/") && !path.isEmpty()) {
                    frozen = true;
                } else if (line.contains(":cpuset:") && path.endsWith("/background")) {
                    background = true;
                }
            }
            if (frozen) {
                return "frozen by the OS";
            }
            return background ? "backgrounded by the OS" : null;
        } catch (Throwable th) {
            return null;
        }
    }

    private static long cpuTicks(@NonNull String packageName) {
        try {
            Runner.Result r = Runner.runCommand("for p in $(pidof " + packageName
                    + " 2>/dev/null); do cat /proc/$p/stat 2>/dev/null; done");
            if (r == null || !r.isSuccessful()) {
                return -1;
            }
            long total = 0;
            boolean any = false;
            for (String line : r.getOutputAsList()) {
                int close = line.lastIndexOf(')');
                if (close < 0) {
                    continue;
                }
                String[] f = line.substring(close + 1).trim().split("\\s+");
                if (f.length < 13) {
                    continue;
                }
                try {
                    total += Long.parseLong(f[11]) + Long.parseLong(f[12]);
                    any = true;
                } catch (NumberFormatException ignore) {
                }
            }
            return any ? total : -1;
        } catch (Throwable th) {
            return -1;
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
