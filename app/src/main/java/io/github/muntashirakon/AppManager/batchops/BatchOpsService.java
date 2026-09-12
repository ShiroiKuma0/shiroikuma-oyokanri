// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.batchops;

import static io.github.muntashirakon.AppManager.history.ops.OpHistoryManager.HISTORY_TYPE_BATCH_OPS;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.PendingIntentCompat;
import androidx.core.app.ServiceCompat;

import java.util.ArrayList;
import java.util.Objects;

import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.appdata.self.MigrationKit;
import io.github.muntashirakon.AppManager.types.UserPackagePair;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.batchops.BatchOpsManager.BatchOpsInfo;
import io.github.muntashirakon.AppManager.history.ops.OpHistoryManager;
import io.github.muntashirakon.AppManager.intercept.IntentCompat;
import io.github.muntashirakon.AppManager.main.MainActivity;
import io.github.muntashirakon.AppManager.settings.Prefs;
import io.github.muntashirakon.AppManager.progress.NotificationProgressHandler;
import io.github.muntashirakon.AppManager.progress.NotificationProgressHandler.NotificationManagerInfo;
import io.github.muntashirakon.AppManager.progress.ProgressHandler;
import io.github.muntashirakon.AppManager.progress.QueuedProgressHandler;
import io.github.muntashirakon.AppManager.types.ForegroundService;
import io.github.muntashirakon.AppManager.utils.CpuUtils;
import io.github.muntashirakon.AppManager.utils.NotificationUtils;

public class BatchOpsService extends ForegroundService {
    public static final String EXTRA_QUEUE_ITEM = "queue_item";

    /**
     * Name of the batch operation, {@link Integer} value. One of the {@link BatchOpsManager.OpType}.
     */
    public static final String EXTRA_OP = "EXTRA_OP";
    /**
     * An {@link ArrayList} of package names (string value) on which operations will be carried out.
     */
    public static final String EXTRA_OP_PKG = "EXTRA_OP_PKG";
    /**
     * An {@link ArrayList} of package name (string value) which are failed after the batch
     * operation is complete.
     */
    public static final String EXTRA_FAILED_PKG = "EXTRA_FAILED_PKG_ARR";
    /**
     * The failure message.
     */
    public static final String EXTRA_FAILURE_MESSAGE = "EXTRA_FAILURE_MESSAGE";
    /**
     * Boolean value to describe whether a reboot is required.
     */
    public static final String EXTRA_REQUIRES_RESTART = "requires_restart";

    /**
     * Fork: the integer result code of the operation ({@link Activity#RESULT_OK},
     * {@link Activity#RESULT_CANCELED}, {@link Activity#RESULT_FIRST_USER}),
     * included in the {@link #ACTION_BATCH_OPS_COMPLETED} broadcast so the main
     * window can show the right themed-toast message.
     */
    public static final String EXTRA_RESULT = "fork_result";

    /**
     * Send to the appropriate broadcast receiver denoting that the batch operation is completed. It
     * includes the following extras:
     * <ul>
     *     <li>
     *         {@link #EXTRA_OP} is the integer value denoting the type of operation.
     *     </li>
     *     <li>
     *         {@link #EXTRA_OP_PKG} is the array of packages on which operations were carried out. Never null.
     *     </li>
     *     <li>
     *         {@link #EXTRA_FAILED_PKG} is the array of failed packages. Never null.
     *     </li>
     * </ul>
     */
    public static final String ACTION_BATCH_OPS_COMPLETED = BuildConfig.APPLICATION_ID + ".action.BATCH_OPS_COMPLETED";
    public static final String ACTION_BATCH_OPS_STARTED = BuildConfig.APPLICATION_ID + ".action.BATCH_OPS_STARTED";

    /**
     * Notification channel ID
     */
    public static final String CHANNEL_ID = BuildConfig.APPLICATION_ID + ".channel.BATCH_OPS";

    @NonNull
    public static Intent getServiceIntent(@NonNull Context context, @NonNull BatchQueueItem queueItem) {
        Intent intent = new Intent(context, BatchOpsService.class);
        IntentCompat.putWrappedParcelableExtra(intent, EXTRA_QUEUE_ITEM, queueItem);
        return intent;
    }

    private QueuedProgressHandler mProgressHandler;
    private NotificationProgressHandler.NotificationInfo mNotificationInfo;
    private PowerManager.WakeLock mWakeLock;

    public BatchOpsService() {
        super("BatchOpsService");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mWakeLock = CpuUtils.getPartialWakeLock("batch_ops");
        mWakeLock.acquire();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (isWorking()) return super.onStartCommand(intent, flags, startId);
        BatchQueueItem item = getQueueItem(intent);
        NotificationManagerInfo notificationManagerInfo = new NotificationManagerInfo(CHANNEL_ID,
                "Batch Ops Progress", NotificationManagerCompat.IMPORTANCE_LOW);
        mProgressHandler = new NotificationProgressHandler(this,
                notificationManagerInfo,
                NotificationUtils.HIGH_PRIORITY_NOTIFICATION_INFO,
                NotificationUtils.HIGH_PRIORITY_NOTIFICATION_INFO);
        mProgressHandler.setProgressTextInterface(ProgressHandler.PROGRESS_REGULAR);
        // Fork (+116): the running notification opens the progress page, not the app list —
        // what a person taps it for is to see how the batch is getting on.
        Intent notificationIntent = BatchOpsProgressActivity.getIntent(this);
        PendingIntent pendingIntent = PendingIntentCompat.getActivity(this, 0, notificationIntent, 0, false);
        mNotificationInfo = new NotificationProgressHandler.NotificationInfo()
                .setOperationName(getHeader(item))
                .setBody(getString(R.string.operation_running))
                .setDefaultAction(pendingIntent);
        mProgressHandler.onAttach(this, mNotificationInfo);
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        BatchQueueItem item = getQueueItem(intent);
        if (item == null || item.getOp() == BatchOpsManager.OP_NONE) {
            sendResults(Activity.RESULT_CANCELED, item, null);
            return;
        }
        // Fork: feed the in-app batch-progress dialog (op title + item count) and
        // reset its pause/cancel state for this run, before announcing the start
        // so the dialog opens against a populated monitor.
        BatchOpsProgressMonitor monitor = BatchOpsProgressMonitor.getInstance();
        CharSequence opTitle = getDesiredOpTitle(this, item.getOp());
        // Fork (白い熊): the monitor is told WHAT this run is, not merely how big it is, so the
        // finished page can offer to send the backup without re-deriving which apps were in the
        // batch — by then the in-flight list has been emptied and nothing else remembers.
        ArrayList<String> opPackages = item.getPackages();
        ArrayList<Integer> opUsers = item.getUsers();  // guaranteed parallel to the packages
        List<UserPackagePair> opTargets = new ArrayList<>(opPackages.size());
        for (int i = 0; i < opPackages.size(); ++i) {
            opTargets.add(new UserPackagePair(opPackages.get(i), opUsers.get(i)));
        }
        monitor.begin(opTitle, opPackages.size(), item.getOp(), opTargets);
        // Fork (+116): open the log for this run. Started BEFORE the announcement, so the page
        // finds a populated log the instant it opens rather than an empty one that fills in.
        OpLog.getInstance().begin(opTitle,
                getString(R.string.op_log_app_count, item.getPackages().size()));
        sendStarted(item);
        // Fork (+116): open the page from HERE as well as from the main window's broadcast
        // receiver. That receiver only exists while the app list is resumed, so a backup started
        // from app details, or from the backup dialog of another screen, would otherwise run
        // with nothing on screen but a notification. The launch is a no-op when the platform
        // refuses it (no foreground app, i.e. an automated run), which is the right outcome:
        // the notification still opens the page.
        openProgressPage(item);
        // Update progress
        if (mProgressHandler != null) {
            mProgressHandler.postUpdate(item.getPackages().size(), 0);
        }
        BatchOpsManager batchOpsManager = new BatchOpsManager();
        BatchOpsManager.Result result = batchOpsManager.performOp(BatchOpsInfo.fromQueue(item), mProgressHandler);
        batchOpsManager.conclude();
        // Fork: read the cancelled flag before finish() clears it, so a
        // user-cancelled run is reported as cancelled rather than failed.
        boolean cancelled = monitor.isCancelled();
        monitor.finish();
        writeLogSummary(item, result, cancelled);
        OpHistoryManager.addHistoryItem(HISTORY_TYPE_BATCH_OPS, item, result.isSuccessful() && !cancelled);
        if (cancelled) {
            sendResults(Activity.RESULT_CANCELED, item, result);
        } else if (result.isSuccessful()) {
            sendResults(Activity.RESULT_OK, item, result);
        } else {
            sendResults(Activity.RESULT_FIRST_USER, item, result);
        }
    }

    @Override
    protected void onQueued(@Nullable Intent intent) {
        BatchQueueItem item = getQueueItem(intent);
        if (item == null) {
            return;
        }
        String opTitle = getDesiredOpTitle(this, item.getOp());
        Object notificationInfo = new NotificationProgressHandler.NotificationInfo()
                .setAutoCancel(true)
                .setTime(System.currentTimeMillis())
                .setOperationName(getHeader(item))
                .setTitle(opTitle)
                .setBody(getString(R.string.added_to_queue));
        mProgressHandler.onQueue(notificationInfo);
    }

    @Override
    protected void onStartIntent(@Nullable Intent intent) {
        BatchQueueItem item = getQueueItem(intent);
        int op = item != null ? item.getOp() : BatchOpsManager.OP_NONE;
        mNotificationInfo.setTitle(getDesiredOpTitle(this, op)).setOperationName(getHeader(item));
        mProgressHandler.onProgressStart(-1, 0, mNotificationInfo);
    }

    @Override
    public void onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        if (mProgressHandler != null) {
            mProgressHandler.onDetach(this);
        }
        CpuUtils.releaseWakeLock(mWakeLock);
        super.onDestroy();
    }

    @Nullable
    private BatchQueueItem getQueueItem(@Nullable Intent intent) {
        if (intent == null) {
            return null;
        }
        return IntentCompat.getUnwrappedParcelableExtra(intent, EXTRA_QUEUE_ITEM, BatchQueueItem.class);
    }

    private void openProgressPage(@NonNull BatchQueueItem item) {
        if (!Prefs.Appearance.showBatchProgressDialog()
                || !BatchOpsProgressActivity.shouldAutoOpen(item.getOp(), item.getPackages().size())) {
            return;
        }
        try {
            startActivity(BatchOpsProgressActivity.getIntent(this));
        } catch (Throwable ignore) {
        }
    }

    /**
     * Fork (+116): the closing block of the log — what got through, what did not, and each
     * failed package by name. Written after {@code finish()} so the page is already showing its
     * finished state when the summary lands.
     */
    private void writeLogSummary(@NonNull BatchQueueItem queueItem,
                                 @Nullable BatchOpsManager.Result result, boolean cancelled) {
        int total = queueItem.getPackages().size();
        List<String> failed = result != null ? result.getFailedPackages() : null;
        int failedCount = failed != null ? failed.size() : 0;
        OpLog log = OpLog.getInstance();
        log.end(getString(cancelled ? R.string.op_log_end_cancelled : R.string.op_log_end,
                total - failedCount, total), null);
        // Fork (白い熊): the FULL failure report, at the very end of the log.
        //
        // This block used to print the failed package names and stop there, which on a run with
        // twenty or thirty failures meant going back through thousands of interleaved lines to
        // find out what happened to each one. Every failure's reason and archive path were
        // recorded as they occurred; they are reprinted here together, so the end of the log is
        // the complete account. Ops with no stages record no reason and are still named.
        log.writeFailureReport(getString(R.string.op_log_failures, failedCount),
                getString(R.string.op_log_failure_no_reason), failed);
    }

    private void sendStarted(@NonNull BatchQueueItem queueItem) {
        Intent broadcastIntent = new Intent(ACTION_BATCH_OPS_STARTED);
        broadcastIntent.setPackage(getPackageName());
        broadcastIntent.putExtra(EXTRA_OP, queueItem.getOp());
        broadcastIntent.putExtra(EXTRA_OP_PKG, queueItem.getPackages().toArray(new String[0]));
        sendBroadcast(broadcastIntent);
    }

    private void sendResults(int result, @Nullable BatchQueueItem queueItem, @Nullable BatchOpsManager.Result opResult) {
        Intent broadcastIntent = new Intent(ACTION_BATCH_OPS_COMPLETED);
        broadcastIntent.setPackage(getPackageName());
        broadcastIntent.putExtra(EXTRA_OP, queueItem != null ? queueItem.getOp() : BatchOpsManager.OP_NONE);
        broadcastIntent.putExtra(EXTRA_OP_PKG, queueItem != null ? queueItem.getPackages().toArray(new String[0]) : new String[0]);
        broadcastIntent.putStringArrayListExtra(EXTRA_FAILED_PKG, opResult != null ? opResult.getFailedPackages() : null);
        broadcastIntent.putExtra(EXTRA_RESULT, result);  // Fork: let the main window theme the completion toast
        sendBroadcast(broadcastIntent);
        sendNotification(result, queueItem, opResult);
        // Fork (白い熊, +154): the migration kit is refreshed after every backup run, not only
        // when 応用管理 backs itself up — a kit older than the archives beside it is a kit that
        // restores the wrong phone. Never on the caller's thread, and never fatal.
        int op = queueItem != null ? queueItem.getOp() : BatchOpsManager.OP_NONE;
        if (op == BatchOpsManager.OP_BACKUP || op == BatchOpsManager.OP_BACKUP_APK) {
            Context appContext = getApplicationContext();
            ThreadUtils.postOnBackgroundThread(() -> MigrationKit.writeQuietly(appContext));
        }
    }

    private void sendNotification(int result, @Nullable BatchQueueItem queueItem, @Nullable BatchOpsManager.Result opResult) {
        String contentTitle = getDesiredOpTitle(this, queueItem != null ? queueItem.getOp() : BatchOpsManager.OP_NONE);
        NotificationProgressHandler.NotificationInfo notificationInfo = new NotificationProgressHandler.NotificationInfo()
                .setAutoCancel(true)
                .setTime(System.currentTimeMillis())
                .setOperationName(getHeader(queueItem))
                .setTitle(contentTitle);
        switch (result) {
            case Activity.RESULT_CANCELED:  // Cancelled
                break;
            case Activity.RESULT_OK:  // Successful
                notificationInfo.setBody(getString(R.string.the_operation_was_successful));
                break;
            case Activity.RESULT_FIRST_USER:  // Failed
                Objects.requireNonNull(opResult);
                Objects.requireNonNull(queueItem);
                queueItem.setPackages(opResult.getFailedPackages());
                queueItem.setUsers(opResult.getAssociatedUsers());
                String detailsMessage = getString(R.string.full_stop_tap_to_see_details);
                String message = getDesiredErrorString(queueItem.getOp(), opResult.getFailedPackages().size());
                Intent intent = new Intent(this, BatchOpsResultsActivity.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    intent.setIdentifier(String.valueOf(System.currentTimeMillis()));
                }
                intent.putExtra(EXTRA_FAILURE_MESSAGE, message);
                IntentCompat.putWrappedParcelableExtra(intent, EXTRA_QUEUE_ITEM, queueItem);
                PendingIntent pendingIntent = PendingIntentCompat.getActivity(this, 0, intent,
                        PendingIntent.FLAG_ONE_SHOT, false);
                notificationInfo.setDefaultAction(pendingIntent);
                notificationInfo.setBody(message + detailsMessage);
        }
        if (opResult != null && opResult.requiresRestart()) {
            Intent intent = new Intent(this, BatchOpsResultsActivity.class);
            intent.putExtra(EXTRA_REQUIRES_RESTART, true);
            PendingIntent pendingIntent = PendingIntentCompat.getActivity(this, 0, intent,
                    PendingIntent.FLAG_ONE_SHOT, false);
            notificationInfo.addAction(0, getString(R.string.restart_device), pendingIntent);
        }
        // Fork: while the main window is in the foreground, the in-app themed
        // toast covers success/cancelled completions, so skip the un-themeable
        // system heads-up. Failures (and restart-required results) still post so
        // their "tap to see details" / "restart" actions stay reachable.
        boolean keepNotification = result == Activity.RESULT_FIRST_USER
                || (opResult != null && opResult.requiresRestart());
        if (BatchOpsProgressMonitor.getInstance().isHostForeground() && !keepNotification) {
            return;
        }
        mProgressHandler.onResult(notificationInfo);
    }

    @NonNull
    public String getHeader(@Nullable BatchQueueItem item) {
        if (item != null) {
            String title = item.getTitle();
            if (title != null) {
                return title;
            }
        }
        return getString(R.string.batch_ops);
    }

    @NonNull
    public static String getDesiredOpTitle(@NonNull Context context,
                                           @BatchOpsManager.OpType int op) {
        switch (op) {
            case BatchOpsManager.OP_BACKUP:
            case BatchOpsManager.OP_DELETE_BACKUP:
            case BatchOpsManager.OP_RESTORE_BACKUP:
                return context.getString(R.string.backup_restore);
            case BatchOpsManager.OP_BACKUP_APK:
                return context.getString(R.string.save_apk);
            case BatchOpsManager.OP_BLOCK_TRACKERS:
                return context.getString(R.string.block_trackers);
            case BatchOpsManager.OP_CLEAR_DATA:
                return context.getString(R.string.clear_data);
            case BatchOpsManager.OP_CLEAR_CACHE:
                return context.getString(R.string.clear_cache);
            case BatchOpsManager.OP_FREEZE:
            case BatchOpsManager.OP_ADVANCED_FREEZE:
                return context.getString(R.string.freeze);
            // Fork (白い熊, +038): four ops rather than one carrying a level, precisely so the
            // notification and the operation bar can say which rung was asked for.
            case BatchOpsManager.OP_FREEZE_LEVEL_1:
                return context.getString(R.string.freeze_level_1);
            case BatchOpsManager.OP_FREEZE_LEVEL_2:
                return context.getString(R.string.freeze_level_2);
            case BatchOpsManager.OP_FREEZE_LEVEL_3:
                return context.getString(R.string.freeze_level_3);
            case BatchOpsManager.OP_FREEZE_LEVEL_4:
                return context.getString(R.string.freeze_level_4);
            case BatchOpsManager.OP_DISABLE_BACKGROUND:
                return context.getString(R.string.disable_background);
            case BatchOpsManager.OP_UNFREEZE:
                return context.getString(R.string.unfreeze);
            case BatchOpsManager.OP_EXPORT_RULES:
                return context.getString(R.string.export_blocking_rules);
            case BatchOpsManager.OP_FORCE_STOP:
                return context.getString(R.string.force_stop);
            case BatchOpsManager.OP_NET_POLICY:
                return context.getString(R.string.net_policy);
            case BatchOpsManager.OP_UNINSTALL:
                return context.getString(R.string.uninstall);
            case BatchOpsManager.OP_INSTALL_EXISTING:
                return context.getString(R.string.reinstall);
            case BatchOpsManager.OP_UNBLOCK_TRACKERS:
                return context.getString(R.string.unblock_trackers);
            case BatchOpsManager.OP_BLOCK_COMPONENTS:
                return context.getString(R.string.block_components_dots);
            case BatchOpsManager.OP_UNBLOCK_COMPONENTS:
                return context.getString(R.string.unblock_components_dots);
            case BatchOpsManager.OP_SET_APP_OPS:
                return context.getString(R.string.set_mode_for_app_ops_dots);
            case BatchOpsManager.OP_IMPORT_BACKUPS:
                return context.getString(R.string.pref_import_backups);
            case BatchOpsManager.OP_DEXOPT:
                return context.getString(R.string.batch_ops_runtime_optimization);
            case BatchOpsManager.OP_NONE:
                break;
        }
        return context.getString(R.string.batch_ops);
    }

    private String getDesiredErrorString(int op, int failedCount) {
        switch (op) {
            case BatchOpsManager.OP_BACKUP:
                return getResources().getQuantityString(R.plurals.alert_failed_to_backup, failedCount, failedCount);
            case BatchOpsManager.OP_DELETE_BACKUP:
                return getResources().getQuantityString(R.plurals.alert_failed_to_delete_backup, failedCount, failedCount);
            case BatchOpsManager.OP_RESTORE_BACKUP:
                return getResources().getQuantityString(R.plurals.alert_failed_to_restore, failedCount, failedCount);
            case BatchOpsManager.OP_EXPORT_RULES:
            case BatchOpsManager.OP_NONE:
                break;
            case BatchOpsManager.OP_BACKUP_APK:
                return getResources().getQuantityString(R.plurals.failed_to_backup_some_apk_files, failedCount, failedCount);
            case BatchOpsManager.OP_BLOCK_TRACKERS:
                return getResources().getQuantityString(R.plurals.alert_failed_to_disable_trackers, failedCount, failedCount);
            case BatchOpsManager.OP_CLEAR_DATA:
                return getResources().getQuantityString(R.plurals.alert_failed_to_clear_data, failedCount, failedCount);
            case BatchOpsManager.OP_FREEZE:
            case BatchOpsManager.OP_ADVANCED_FREEZE:
                return getResources().getQuantityString(R.plurals.alert_failed_to_freeze, failedCount, failedCount);
            case BatchOpsManager.OP_FREEZE_LEVEL_1:
            case BatchOpsManager.OP_FREEZE_LEVEL_2:
            case BatchOpsManager.OP_FREEZE_LEVEL_3:
            case BatchOpsManager.OP_FREEZE_LEVEL_4:
                return getResources().getQuantityString(R.plurals.alert_failed_to_set_freeze_level, failedCount, failedCount);
            case BatchOpsManager.OP_UNFREEZE:
                return getResources().getQuantityString(R.plurals.alert_failed_to_unfreeze, failedCount, failedCount);
            case BatchOpsManager.OP_DISABLE_BACKGROUND:
                return getResources().getQuantityString(R.plurals.alert_failed_to_disable_background, failedCount, failedCount);
            case BatchOpsManager.OP_FORCE_STOP:
                return getResources().getQuantityString(R.plurals.alert_failed_to_force_stop, failedCount, failedCount);
            case BatchOpsManager.OP_UNINSTALL:
                return getResources().getQuantityString(R.plurals.alert_failed_to_uninstall, failedCount, failedCount);
            case BatchOpsManager.OP_UNBLOCK_TRACKERS:
                return getResources().getQuantityString(R.plurals.alert_failed_to_unblock_trackers, failedCount, failedCount);
            case BatchOpsManager.OP_BLOCK_COMPONENTS:
                return getResources().getQuantityString(R.plurals.alert_failed_to_block_components, failedCount, failedCount);
            case BatchOpsManager.OP_UNBLOCK_COMPONENTS:
                return getResources().getQuantityString(R.plurals.alert_failed_to_unblock_components, failedCount, failedCount);
            case BatchOpsManager.OP_SET_APP_OPS:
                return getResources().getQuantityString(R.plurals.alert_failed_to_set_app_ops, failedCount, failedCount);
            case BatchOpsManager.OP_IMPORT_BACKUPS:
                return getResources().getQuantityString(R.plurals.alert_failed_to_import_backups, failedCount, failedCount);
            case BatchOpsManager.OP_DEXOPT:
                return getResources().getQuantityString(R.plurals.alert_failed_to_optimize_apps, failedCount, failedCount);
        }
        return getString(R.string.error);
    }
}
