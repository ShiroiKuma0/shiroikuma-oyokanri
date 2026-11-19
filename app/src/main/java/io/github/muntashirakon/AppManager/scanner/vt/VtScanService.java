// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.scanner.vt;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.PendingIntentCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.db.entity.VtFile;
import io.github.muntashirakon.AppManager.db.entity.VtScanAttempt;
import io.github.muntashirakon.AppManager.progress.NotificationProgressHandler;
import io.github.muntashirakon.AppManager.progress.ProgressHandler;
import io.github.muntashirakon.AppManager.settings.Prefs;
import io.github.muntashirakon.AppManager.types.ForegroundService;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.NotificationUtils;
import io.github.muntashirakon.io.Path;

public class VtScanService extends Service {
    public static final String ACTION_RUN = BuildConfig.APPLICATION_ID + ".action.VT_RUN";
    public static final String ACTION_CANCEL = BuildConfig.APPLICATION_ID + ".action.VT_CANCEL";
    public static final String EXTRA_ATTEMPT_ID = BuildConfig.APPLICATION_ID + ".extra.VT_ATTEMPT_ID";
    private static final String CHANNEL_ID = BuildConfig.APPLICATION_ID + ".channel.VT_SCAN";
    private static final String RESULT_CHANNEL_ID = BuildConfig.APPLICATION_ID + ".channel.VT_SCAN_RESULTS";
    private static final int NOTIFICATION_ID = 0x5654;
    private static final long POLL_DELAY_MS = 30_000L;
    private static final int MAX_POLLS = 20;
    // Fork (白い熊, +081): a large APK routinely sits in VirusTotal's queue for longer than ten
    // minutes, and timing out there threw away an upload that had already succeeded. Half an hour.
    private static final int MAX_ANALYSIS_POLLS = 60;
    private static final int MAX_CONCURRENT_SCANS = 3;
    private static final long MAX_UPLOAD_BYTES = 650_000_000L;
    private static final long LARGE_FILE_BYTES = 32_000_000L;
    // Fork (白い熊, APK-vetting chain, item 2): how long the service stays alive waiting on a parked
    // attempt. A minute-quota back-off (60 s) is waited out here; a daily-quota park (hours) exceeds
    // the window, so the service stops and the attempt resumes on the next start (screen open, scan).
    private static final long PARK_KEEPALIVE_WINDOW_MS = 5 * 60_000L;
    private static final long MINUTE_BACKOFF_MS = 60_000L;
    private static final long WIFI_RETRY_MS = 10 * 60_000L;
    // Fork (白い熊, APK-vetting chain, item 7): the one-shot post-upload refresh fires ~4 days on.
    private static final long AUTO_REFRESH_DELAY_MS = 4 * 24 * 60 * 60_000L;

    // Fork (白い熊, APK-vetting chain, item 5): live upload percentage per attempt, so the report
    // screen can show the upload bar (the DB only carries the coarse status, not the byte count).
    private static final Map<Long, Integer> sUploadPercent = new ConcurrentHashMap<>();

    public static int getUploadPercent(long attemptId) {
        Integer p = sUploadPercent.get(attemptId);
        return p == null ? -1 : p;
    }

    private final ExecutorService mDispatcher = Executors.newSingleThreadExecutor();
    private final ExecutorService mScanExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_SCANS);
    private final ScheduledExecutorService mScheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<Long, Future<?>> mRunningScans = new ConcurrentHashMap<>();
    // Fork (白い熊, +117): the progress of each running scan, so a cancel can withdraw its entry at once.
    private final Map<Long, ScanProgress> mProgressByAttempt = new ConcurrentHashMap<>();
    private volatile boolean mStopping;
    // Fork (白い熊, +118): scans cancelled but whose thread has not returned yet, and whether the
    // "VirusTotal — running" entry is up. See cancelAttempt.
    private final java.util.Set<Long> mCancelledIds = ConcurrentHashMap.newKeySet();
    private volatile boolean mForeground;
    @Nullable
    private VtScanRepository mRepository;
    @Nullable
    private NotificationProgressHandler mProgressHandler;

    @NonNull
    public static Intent getIntent(@NonNull Context context) {
        return new Intent(context, VtScanService.class).setAction(ACTION_RUN);
    }

    public static void start(@NonNull Context context) {
        ContextCompat.startForegroundService(context, getIntent(context));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mRepository = new VtScanRepository();
        NotificationUtils.getNewNotificationManager(this, CHANNEL_ID, "VirusTotal",
                NotificationManagerCompat.IMPORTANCE_LOW);
        NotificationUtils.getNewNotificationManager(this, RESULT_CHANNEL_ID, "VirusTotal results",
                NotificationManagerCompat.IMPORTANCE_DEFAULT);
        mProgressHandler = new NotificationProgressHandler(this,
                new NotificationProgressHandler.NotificationManagerInfo(CHANNEL_ID, "VirusTotal",
                        NotificationManagerCompat.IMPORTANCE_LOW),
                new NotificationProgressHandler.NotificationManagerInfo(RESULT_CHANNEL_ID, "VirusTotal results",
                        NotificationManagerCompat.IMPORTANCE_DEFAULT), null);
        startForeground();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelAttempt(intent.getLongExtra(EXTRA_ATTEMPT_ID, 0));
        } else if (!mForeground) {
            // Fork (白い熊, +118): the entry was taken down by a cancel; a new scan puts it back
            // (and a startForegroundService must be answered with startForeground in any case).
            startForeground();
        }
        scheduleDispatch();
        return START_STICKY;
    }

    private void scheduleDispatch() {
        if (!mStopping) {
            mDispatcher.submit(this::dispatch);
        }
    }

    private void scheduleDispatchAfter(long delayMs) {
        if (mStopping) return;
        try {
            mScheduler.schedule(this::scheduleDispatch, Math.max(500L, delayMs), TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            // Scheduler shutting down.
        }
    }

    private void dispatch() {
        if (mStopping || mRepository == null) {
            return;
        }
        VtScanRepository repository = mRepository;
        boolean hasKey = VirusTotal.getInstance() != null;
        // A key now exists: resume everything parked for a missing/invalid key (e.g. after restart).
        if (hasKey) {
            repository.resumeKeyParkedAttempts();
        }
        // Item 7: enqueue any due post-upload auto-refreshes.
        enqueueDueAutoRefreshes(repository);
        List<VtScanAttempt> attempts = repository.getActiveAttempts();
        long now = System.currentTimeMillis();
        boolean hasRunnable = false;
        long earliestPark = Long.MAX_VALUE;
        for (VtScanAttempt attempt : attempts) {
            if (VtScanAttempt.STATUS_PENDING_CONSENT.equals(attempt.status)) {
                continue;
            }
            if (VtScanAttempt.STATUS_PENDING_KEY.equals(attempt.status) && !hasKey) {
                continue;
            }
            if (attempt.nextAttemptAt > now) {
                earliestPark = Math.min(earliestPark, attempt.nextAttemptAt);
                continue;
            }
            if (!hasKey) {
                // No key: park as needs-key rather than running (which would only fail).
                repository.parkAttempt(attempt.id, VtScanAttempt.STATUS_PENDING_KEY, 0);
                continue;
            }
            hasRunnable = true;
            if (mRunningScans.containsKey(attempt.id)) {
                continue;
            }
            FutureTask<Void> future = new FutureTask<>(() -> {
                ScanProgress progress = new ScanProgress(attempt);
                mProgressByAttempt.put(attempt.id, progress);
                try {
                    execute(attempt, progress);
                } finally {
                    mProgressByAttempt.remove(attempt.id);
                    mCancelledIds.remove(attempt.id);
                    progress.detach();
                    mRunningScans.remove(attempt.id);
                    sUploadPercent.remove(attempt.id);
                    scheduleDispatch();
                }
                return null;
            });
            if (mRunningScans.putIfAbsent(attempt.id, future) == null) {
                if (!mForeground) ThreadUtils.postOnMainThread(this::startForeground);
                mScanExecutor.execute(future);
            }
        }
        if (!hasRunnable && mRunningScans.isEmpty()) {
            if (earliestPark != Long.MAX_VALUE && earliestPark - now <= PARK_KEEPALIVE_WINDOW_MS) {
                scheduleDispatchAfter(earliestPark - now + 500L);
            } else {
                stopSelf();
            }
        }
    }

    private void enqueueDueAutoRefreshes(@NonNull VtScanRepository repository) {
        try {
            for (VtFile file : repository.getFilesDueForAutoRefresh()) {
                repository.setAutoRefreshAt(file.id, 0);
                if (file.latestReportJson != null) {
                    repository.createRescanAttempt(file.id);
                }
            }
        } catch (Throwable ignored) {
            // Best-effort maintenance; never block the dispatch.
        }
    }

    private void cancelAttempt(long attemptId) {
        if (attemptId == 0) return;
        // Fork (白い熊, +088): onStartCommand runs on the MAIN thread, and Room refuses a database
        // write there — "Cancel scan" crashed the app (and crashed it again when the sticky service
        // was restarted). Mark it cancelled on the dispatcher thread, then interrupt the scan; in
        // that order, because the interrupted scan reads the status to tell a cancel from a failure.
        VtScanRepository repository = mRepository;
        mDispatcher.submit(() -> {
            try {
                if (repository != null) repository.cancelAttempt(attemptId);
            } catch (Throwable th) {
                th.printStackTrace();
            }
            // Fork (白い熊, +117): the entry goes NOW. The scan thread may sit in a network call
            // that an interrupt does not end, and until it returned the entry kept saying the
            // scan was running.
            ScanProgress progress = mProgressByAttempt.get(attemptId);
            if (progress != null) progress.cancelled();
            Future<?> future = mRunningScans.get(attemptId);
            if (future != null) {
                mCancelledIds.add(attemptId);
                future.cancel(true);
            }
            // Fork (白い熊, +118): "VirusTotal — running" goes with the cancel too, unless another
            // scan is really running. The cancelled thread may still sit in a network request; the
            // service waits for it without an entry and stops when it returns.
            if (mCancelledIds.containsAll(mRunningScans.keySet())) {
                ThreadUtils.postOnMainThread(() -> {
                    if (mStopping || !mCancelledIds.containsAll(mRunningScans.keySet())) return;
                    mForeground = false;
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
                });
            }
        });
    }

    private void execute(@NonNull VtScanAttempt attempt, @NonNull ScanProgress progress) {
        VirusTotal virusTotal = VirusTotal.getInstance();
        if (virusTotal == null) {
            // Item 10: park as needs-key instead of failing; resumes when a key is saved.
            requireRepository().parkAttempt(attempt.id, VtScanAttempt.STATUS_PENDING_KEY, 0);
            progress.needsKey(attempt.fileId);
            return;
        }
        boolean uploaded = false;
        try {
            VtScanRepository repository = requireRepository();
            VtFile file = repository.getFile(attempt.fileId);
            if (file == null) {
                throw new IOException("VirusTotal file record no longer exists.");
            }
            progress.setTitle(file.displayName == null ? file.sha256 : file.displayName);
            String analysisId = attempt.analysisId;
            VtFileReport report;
            if (VtScanAttempt.STATUS_ANALYSING.equals(attempt.status) && analysisId != null) {
                report = pollAnalysis(virusTotal, file.sha256, analysisId, attempt.id, progress);
            } else if (VtScanAttempt.TYPE_RESCAN.equals(attempt.type)) {
                repository.setAttemptStatus(attempt.id, VtScanAttempt.STATUS_ANALYSING);
                progress.update(getString(R.string.vt_checking));
                VirusTotal.ResponseV3<String> response = virusTotal.requestRescan(file.sha256);
                if (!response.isSuccessful()) throw error(response);
                analysisId = response.response;
                repository.setAnalysisId(attempt.id, analysisId);
                report = pollAnalysis(virusTotal, file.sha256, analysisId, attempt.id, progress);
            } else if (VtScanAttempt.TYPE_UPLOAD.equals(attempt.type)) {
                report = uploadAndPoll(virusTotal, file, attempt, progress);
                uploaded = true;
                VtScanAttempt updated = repository.getAttempt(attempt.id);
                analysisId = updated == null ? null : updated.analysisId;
            } else {
                repository.setAttemptStatus(attempt.id, VtScanAttempt.STATUS_LOOKING_UP);
                progress.update(getString(R.string.vt_checking));
                VirusTotal.ResponseV3<VtFileReport> response = virusTotal.fetchFileReport(file.sha256);
                if (response.isSuccessful() && response.response.hasReport()) {
                    report = response.response;
                } else if (response.isSuccessful()) {
                    // Fork (白い熊, +081): VirusTotal knows the file (an earlier upload landed) but
                    // has not finished analysing it. That is not an error — wait for the report.
                    // It used to fall through to error(response) with no error, i.e. the
                    // meaningless "VirusTotal request failed."
                    repository.setAttemptStatus(attempt.id, VtScanAttempt.STATUS_ANALYSING);
                    report = pollReport(virusTotal, file.sha256, attempt.id, progress, MAX_ANALYSIS_POLLS, false);
                } else if (VtScanAttempt.TYPE_LOOKUP.equals(attempt.type)
                        && response.error != null && "NotFoundError".equals(response.error.code)
                        && Prefs.VirusTotal.promptBeforeUpload()) {
                    // Fork (白い熊, +084): VirusTotal really does not know this file, and 白い熊 wants
                    // to be asked before anything is uploaded. Park for consent; approval turns the
                    // attempt into an upload and re-queues it.
                    repository.parkAttempt(attempt.id, VtScanAttempt.STATUS_PENDING_CONSENT, 0);
                    progress.needsConsent();
                    return;
                } else if (VtScanAttempt.TYPE_LOOKUP.equals(attempt.type)
                        && response.error != null && "NotFoundError".equals(response.error.code)) {
                    report = uploadAndPoll(virusTotal, file, attempt, progress);
                    uploaded = true;
                    VtScanAttempt updated = repository.getAttempt(attempt.id);
                    analysisId = updated == null ? null : updated.analysisId;
                } else {
                    throw error(response);
                }
            }
            VtScanAttempt latest = repository.getAttempt(attempt.id);
            if (latest != null && VtScanAttempt.STATUS_CANCELLED.equals(latest.status)) {
                // Fork (白い熊, +117): cancelled while the last request was under way.
                progress.cancelled();
                return;
            }
            repository.completeAttempt(attempt.id, report, analysisId);
            if (uploaded) {
                // Item 7: a freshly uploaded file re-reads better a few days on, when more engines
                // have weighed in — schedule the one-shot post-upload auto-refresh.
                repository.setAutoRefreshAt(attempt.fileId, System.currentTimeMillis() + AUTO_REFRESH_DELAY_MS);
            }
            progress.complete(getString(R.string.vt_success, report.getPositives(), report.getTotal()),
                    file.displayName == null ? file.sha256 : file.displayName);
        } catch (VtAuthException e) {
            // Item 10: the key stopped working — park as needs-key, do not fail.
            requireRepository().parkAttempt(attempt.id, VtScanAttempt.STATUS_PENDING_KEY, 0);
            progress.needsKey(attempt.fileId);
        } catch (VtQuotaException e) {
            // Item 2: back off and park, keeping the current status so a mid-poll scan resumes.
            requireRepository().setNextAttemptAt(attempt.id, e.nextAttemptAt, getString(R.string.vt_quota_waiting));
            progress.parked(getString(R.string.vt_quota_waiting));
            scheduleDispatchAfter(e.nextAttemptAt - System.currentTimeMillis());
        } catch (VtDeferException e) {
            requireRepository().setNextAttemptAt(attempt.id, e.nextAttemptAt, getString(R.string.vt_waiting_wifi));
            progress.parked(getString(R.string.vt_waiting_wifi));
            scheduleDispatchAfter(e.nextAttemptAt - System.currentTimeMillis());
        } catch (VtTooLargeException e) {
            // Item 9: too large for VirusTotal — local analysis only, no upload.
            fail(attempt.id, "TooLarge", getString(R.string.vt_too_large), progress);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            VtScanAttempt current = requireRepository().getAttempt(attempt.id);
            if (current != null && VtScanAttempt.STATUS_CANCELLED.equals(current.status)) {
                progress.cancelled();
            } else if (!mStopping) {
                fail(attempt.id, "Interrupted", "Scan interrupted.", progress);
            }
        } catch (Throwable e) {
            VtScanAttempt current = requireRepository().getAttempt(attempt.id);
            if (current != null && VtScanAttempt.STATUS_CANCELLED.equals(current.status)) {
                progress.cancelled();
            } else if (!mStopping) {
                fail(attempt.id, e.getClass().getSimpleName(), e.getMessage(), progress);
            }
        }
    }

    @NonNull
    private VtFileReport uploadAndPoll(@NonNull VirusTotal virusTotal, @NonNull VtFile file,
                                       @NonNull VtScanAttempt attempt, @NonNull ScanProgress progress)
            throws IOException, InterruptedException {
        VtScanRepository repository = requireRepository();
        Path path = repository.resolveSource(file.id);
        long length = path.length();
        if (length > MAX_UPLOAD_BYTES) {
            throw new VtTooLargeException("File is larger than 650 MB.");
        }
        // Item 8/10: honour the Wi-Fi-only upload setting — defer off an unmetered network.
        if (Prefs.VirusTotal.uploadOnWifiOnly() && isMeteredOrOffline()) {
            throw new VtDeferException("Waiting for Wi-Fi.", System.currentTimeMillis() + WIFI_RETRY_MS);
        }
        repository.setAttemptStatus(attempt.id, VtScanAttempt.STATUS_UPLOADING);
        progress.update(getString(R.string.vt_uploading));
        long total = length;
        VirusTotal.UploadProgressListener listener = (written, t) -> {
            int pct = total > 0 ? (int) Math.min(100, written * 100 / Math.max(1, t)) : 0;
            sUploadPercent.put(attempt.id, pct);
            progress.update(getString(R.string.vt_uploading_pct, pct), pct, 100);
        };
        VirusTotal.ResponseV3<String> response;
        try (InputStream inputStream = path.openInputStream()) {
            response = length > LARGE_FILE_BYTES
                    ? virusTotal.uploadLargeFile(path.getName(), inputStream, length, listener)
                    : virusTotal.uploadFile(path.getName(), inputStream, length, listener);
        }
        if (!response.isSuccessful()) throw error(response);
        String analysisId = response.response;
        repository.setAnalysisId(attempt.id, analysisId);
        return pollAnalysis(virusTotal, file.sha256, analysisId, attempt.id, progress);
    }

    @NonNull
    private VtFileReport pollAnalysis(@NonNull VirusTotal virusTotal, @NonNull String fileId,
                                      @NonNull String analysisId, long attemptId,
                                      @NonNull ScanProgress progress)
            throws IOException, InterruptedException {
        requireRepository().setAttemptStatus(attemptId, VtScanAttempt.STATUS_ANALYSING);
        for (int i = 0; i < MAX_ANALYSIS_POLLS; ++i) {
            VirusTotal.ResponseV3<VtAnalysis> analysis = virusTotal.fetchAnalysis(analysisId);
            if (analysis.isSuccessful() && analysis.response.isCompleted()) {
                return pollReport(virusTotal, fileId, attemptId, progress);
            }
            if (!analysis.isSuccessful()) {
                // Item 2/10: park on quota or auth instead of looping; resumes with the analysisId.
                if (VirusTotal.isQuotaError(analysis.error) || VirusTotal.isAuthError(analysis.error)) {
                    throw error(analysis);
                }
                if (!analysis.shouldRetry()) throw error(analysis);
            }
            progress.update(getString(R.string.vt_analysing), i + 1, MAX_ANALYSIS_POLLS);
            waitForNextPoll();
        }
        throw new IOException(getString(R.string.vt_error_analysis_timeout));
    }

    @NonNull
    private VtFileReport pollReport(@NonNull VirusTotal virusTotal, @NonNull String fileId,
                                    long attemptId, @NonNull ScanProgress progress)
            throws IOException, InterruptedException {
        return pollReport(virusTotal, fileId, attemptId, progress, MAX_POLLS, true);
    }

    @NonNull
    private VtFileReport pollReport(@NonNull VirusTotal virusTotal, @NonNull String fileId,
                                    long attemptId, @NonNull ScanProgress progress, int maxPolls,
                                    boolean markLookingUp)
            throws IOException, InterruptedException {
        if (markLookingUp) {
            requireRepository().setAttemptStatus(attemptId, VtScanAttempt.STATUS_LOOKING_UP);
        }
        for (int i = 0; i < maxPolls; ++i) {
            VirusTotal.ResponseV3<VtFileReport> response = virusTotal.fetchFileReport(fileId);
            if (response.isSuccessful() && response.response.hasReport()) return response.response;
            if (!response.isSuccessful()) {
                if (VirusTotal.isQuotaError(response.error) || VirusTotal.isAuthError(response.error)) {
                    throw error(response);
                }
                if (!response.shouldRetry()) throw error(response);
            }
            progress.update(getString(R.string.vt_checking), i + 1, maxPolls);
            waitForNextPoll();
        }
        throw new IOException(getString(R.string.vt_error_analysis_timeout));
    }

    private boolean isMeteredOrOffline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        try {
            if (cm.getActiveNetworkInfo() == null || !cm.getActiveNetworkInfo().isConnected()) {
                return true;
            }
            return cm.isActiveNetworkMetered();
        } catch (Throwable e) {
            return false;
        }
    }

    private void waitForNextPoll() throws InterruptedException {
        Thread.sleep(POLL_DELAY_MS);
    }

    @NonNull
    private IOException error(@NonNull VirusTotal.ResponseV3<?> response) {
        if (response.error == null) return new IOException("VirusTotal request failed.");
        String message = response.error.message != null ? response.error.message : response.error.toString();
        if (VirusTotal.isAuthError(response.error)) {
            return new VtAuthException(message);
        }
        if (VirusTotal.isQuotaError(response.error)) {
            return new VtQuotaException(message, computeBackoff(response.error));
        }
        return new IOException(response.error.code + ": " + message);
    }

    private static long computeBackoff(@NonNull VtError error) {
        long now = System.currentTimeMillis();
        String message = error.message != null ? error.message.toLowerCase(Locale.ROOT) : "";
        if (message.contains("day")) {
            // Daily quota — back off until the next UTC midnight (when VirusTotal resets it).
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            c.setTimeInMillis(now);
            c.add(Calendar.DAY_OF_MONTH, 1);
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        }
        return now + MINUTE_BACKOFF_MS;
    }

    private void fail(long attemptId, @NonNull String code, @Nullable String message,
                      @NonNull ScanProgress progress) {
        if (mRepository != null) mRepository.failAttempt(attemptId, code, message);
        progress.failed(message == null ? code : message);
    }

    @NonNull
    private VtScanRepository requireRepository() {
        if (mRepository == null) {
            throw new IllegalStateException("Service is not initialized.");
        }
        return mRepository;
    }

    private void startForeground() {
        mForeground = true;
        ForegroundService.start(this, NOTIFICATION_ID, new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_default_notification)
                        .setContentTitle("VirusTotal")
                        .setContentText(getString(R.string.operation_running))
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setSilent(true)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .build(),
                ForegroundService.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        | ForegroundService.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }

    // ── Typed outcomes that park rather than fail (白い熊, APK-vetting chain) ─────────────────────

    private static class VtAuthException extends IOException {
        VtAuthException(@Nullable String message) {
            super(message == null ? "VirusTotal rejected the API key." : message);
        }
    }

    private static class VtQuotaException extends IOException {
        final long nextAttemptAt;

        VtQuotaException(@Nullable String message, long nextAttemptAt) {
            super(message == null ? "VirusTotal quota exceeded." : message);
            this.nextAttemptAt = nextAttemptAt;
        }
    }

    private static class VtDeferException extends IOException {
        final long nextAttemptAt;

        VtDeferException(@NonNull String message, long nextAttemptAt) {
            super(message);
            this.nextAttemptAt = nextAttemptAt;
        }
    }

    private static class VtTooLargeException extends IOException {
        VtTooLargeException(@NonNull String message) {
            super(message);
        }
    }

    private class ScanProgress {
        private final VtScanAttempt mAttempt;
        private final ProgressHandler mHandler;
        private final AtomicReference<CharSequence> mText = new AtomicReference<>(getString(R.string.vt_queued));
        private String mTitle;
        private volatile boolean mCancelled;

        ScanProgress(@NonNull VtScanAttempt attempt) {
            mAttempt = attempt;
            NotificationProgressHandler root = mProgressHandler;
            if (root == null) {
                throw new IllegalStateException("Progress handler is not initialized.");
            }
            mHandler = root.newSubProgressHandler(null);
            mHandler.setProgressTextInterface(handler -> mText.get());
            VtFile file = mRepository == null ? null : mRepository.getFile(attempt.fileId);
            mTitle = file == null || file.displayName == null ? "VirusTotal scan #" + attempt.id : file.displayName;
            NotificationProgressHandler.NotificationInfo info = createInfo(mText.get());
            ThreadUtils.postOnMainThread(() -> {
                mHandler.onAttach(null, info);
                mHandler.onProgressStart(-1, 0, null);
            });
        }

        void setTitle(@NonNull String title) {
            mTitle = title;
        }

        void update(@NonNull CharSequence text) {
            update(text, -1, 0);
        }

        void update(@NonNull CharSequence text, int current, int max) {
            if (mCancelled) return;
            mText.set(text);
            if (max < 0) {
                mHandler.postUpdate(-1, 0);
            } else mHandler.postUpdate(max, current);
        }

        void complete(@NonNull String body, @NonNull String resultText) {
            if (mCancelled) return;
            mText.set(body);
            postResult(isNotificationAllowed() ? createInfo(body).setBody(resultText) : null);
        }

        void failed(@NonNull String body) {
            if (mCancelled) return;
            mText.set(getString(R.string.vt_failed));
            postResult(isNotificationAllowed() ? createInfo(getString(R.string.vt_failed)).setBody(body) : null);
        }

        // Fork (白い熊, +117): a cancel is 白い熊's own act — nothing to announce. It used to post a
        // result entry titled with the file and reading "Cancel scan", with a Cancel scan button,
        // which stayed in the shade and looked like a scan still running.
        void cancelled() {
            mCancelled = true;
            postResult(null);
        }

        // Item 2: a transient parked state — keep the ongoing progress text, no result notification.
        void parked(@NonNull CharSequence text) {
            update(text);
        }

        // Item 10: the scan is parked for a missing/invalid key — post a result notification that
        // opens the report screen, which asks for the key and then resumes this same scan.
        void needsKey(long fileId) {
            mText.set(getString(R.string.vt_needs_key));
            postResult(isNotificationAllowed()
                    ? keyNeededInfo(getString(R.string.vt_needs_key)) : null);
        }

        // Fork (白い熊, +115): the scan waits for an answer only the report dialog can take. With
        // the dialog open there is nothing to announce; otherwise one dismissible notice that
        // opens it — never the ongoing progress entry, which stayed after the app was installed.
        void needsConsent() {
            mText.set(getString(R.string.vt_pending_consent));
            postResult(isNotificationAllowed()
                    ? createInfo(getString(R.string.vt_pending_consent)).setAutoCancel(true) : null);
        }

        void detach() {
            // Fork (白い熊, +115): a sub-handler's onDetach(null) removes nothing, so a scan that
            // ended without a result (parked for quota or Wi-Fi) left its progress entry behind
            // for good. onResult(null) only withdraws the progress entry; posted after a real
            // result it is a no-op.
            ThreadUtils.postOnMainThread(() -> {
                mHandler.onResult(null);
                mHandler.onDetach(null);
            });
        }

        private void postResult(@Nullable NotificationProgressHandler.NotificationInfo info) {
            ThreadUtils.postOnMainThread(() -> mHandler.onResult(info));
        }

        @NonNull
        private NotificationProgressHandler.NotificationInfo createInfo(@NonNull CharSequence body) {
            Intent openIntent = VtReportActivity.intentForAttempt(VtScanService.this, mAttempt.id, mAttempt.fileId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent open = PendingIntentCompat.getActivity(VtScanService.this,
                    (int) mAttempt.id, openIntent, PendingIntent.FLAG_UPDATE_CURRENT, false);
            Intent cancelIntent = new Intent(VtScanService.this, VtScanService.class)
                    .setAction(ACTION_CANCEL).putExtra(EXTRA_ATTEMPT_ID, mAttempt.id);
            PendingIntent cancel = PendingIntentCompat.getService(VtScanService.this,
                    (int) mAttempt.id, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT, false);
            return new NotificationProgressHandler.NotificationInfo()
                    .setTitle(mTitle).setOperationName("VirusTotal").setBody(body)
                    .setDefaultAction(open).setAutoCancel(false)
                    .addAction(R.drawable.ic_default_notification, getString(R.string.vt_cancel_scan), cancel);
        }

        @NonNull
        private NotificationProgressHandler.NotificationInfo keyNeededInfo(@NonNull CharSequence body) {
            Intent openIntent = VtReportActivity.intentForFile(VtScanService.this, mAttempt.fileId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent open = PendingIntentCompat.getActivity(VtScanService.this,
                    (int) mAttempt.id, openIntent, PendingIntent.FLAG_UPDATE_CURRENT, false);
            return new NotificationProgressHandler.NotificationInfo()
                    .setTitle(mTitle).setOperationName("VirusTotal").setBody(body)
                    .setDefaultAction(open).setAutoCancel(true);
        }

        private boolean isNotificationAllowed() {
            return !VtHistoryActivity.isVisible() && !VtReportActivity.isVisible();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        mStopping = true;
        for (Future<?> future : mRunningScans.values()) future.cancel(true);
        mDispatcher.shutdownNow();
        mScanExecutor.shutdownNow();
        mScheduler.shutdownNow();
        if (mRepository != null) mRepository.close();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
