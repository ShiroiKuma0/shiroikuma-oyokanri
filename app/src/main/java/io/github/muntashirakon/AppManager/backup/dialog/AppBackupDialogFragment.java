// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.backup.dialog;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.UserHandleHidden;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckedTextView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.resources.MaterialAttributes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.backup.BackupFlags;
import io.github.muntashirakon.AppManager.backup.struct.BackupMetadataV5;
import io.github.muntashirakon.AppManager.batchops.BatchOpsManager;
import io.github.muntashirakon.AppManager.batchops.BatchOpsService;
import io.github.muntashirakon.AppManager.batchops.BatchQueueItem;
import io.github.muntashirakon.AppManager.batchops.struct.BatchBackupOptions;
import io.github.muntashirakon.AppManager.settings.Prefs;
import io.github.muntashirakon.AppManager.types.UserPackagePair;
import io.github.muntashirakon.AppManager.users.UserInfo;
import io.github.muntashirakon.AppManager.utils.ArrayUtils;
import io.github.muntashirakon.AppManager.utils.DateUtils;
import io.github.muntashirakon.AppManager.utils.ForkDialog;
import io.github.muntashirakon.AppManager.utils.StoragePermission;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;
import io.github.muntashirakon.dialog.SearchableFlagsDialogBuilder;
import io.github.muntashirakon.dialog.SearchableMultiChoiceDialogBuilder;
import io.github.muntashirakon.dialog.TextInputDialogBuilder;

/**
 * Fork: every backup action for ONE app, in one dialog (白い熊, 2026-08-02).
 * <p>
 * The main list's backup column used to long-press into a two-line chooser -
 * "Backup" and "Restore or delete…" - where the second entry opened the
 * {@link BackupRestoreDialogFragment} bottom sheet and the first was a bare list
 * line pretending to be an action. This dialog is both of those at once: the
 * app's existing backups as a tick list, {@code Restore} / {@code Delete} /
 * freeze acting on what is ticked, and {@code Back up} as the dialog's own
 * positive button. Nothing drills down any more.
 * <p>
 * It reuses {@link BackupRestoreDialogViewModel} - the same loader, the same
 * {@code prepareForOperation} path, the same {@link BatchOpsService} hand-off -
 * so backup/restore/delete behave identically to the bottom sheet, which stays
 * in place for batch selections and for app details.
 * <p>
 * Label formatting is deliberately done off the main thread:
 * {@link BackupMetadataV5#toLocalizedString(Context)} is {@code @WorkerThread}
 * because it walks the backup directory to size it.
 */
public class AppBackupDialogFragment extends DialogFragment {
    public static final String TAG = AppBackupDialogFragment.class.getSimpleName();

    private static final String ARG_PACKAGE_NAME = "pkg";
    private static final String ARG_USER_ID = "user";
    private static final String ARG_LABEL = "label";

    @NonNull
    public static AppBackupDialogFragment getInstance(@NonNull String packageName, int userId,
                                                      @Nullable CharSequence label) {
        AppBackupDialogFragment fragment = new AppBackupDialogFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PACKAGE_NAME, packageName);
        args.putInt(ARG_USER_ID, userId);
        args.putCharSequence(ARG_LABEL, label);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    private BackupRestoreDialogFragment.ActionCompleteInterface mActionCompleteInterface;
    @Nullable
    private BackupRestoreDialogFragment.ActionBeginInterface mActionBeginInterface;
    @BackupRestoreDialogFragment.ActionMode
    private int mMode = BackupRestoreDialogFragment.MODE_BACKUP;

    private FragmentActivity mActivity;
    private BackupRestoreDialogViewModel mViewModel;
    private AlertDialog mDialog;
    private Context mDialogContext;
    private TextView mMessageView;
    private View mListContainer;
    private LinearLayout mBackupListView;
    private View mActionsView;
    private MaterialButton mRestoreButton;
    private MaterialButton mDeleteButton;
    private MaterialButton mMoreButton;
    private int mMultiChoiceItemLayout;

    /** The app's backups, newest state as last loaded. Index = position in the tick list. */
    private final List<BackupMetadataV5> mBackups = new ArrayList<>();
    /** Ticked positions in {@link #mBackups}. */
    private final Set<Integer> mSelected = new LinkedHashSet<>();
    private boolean mInstalled;
    private boolean mLoaded;

    private final StoragePermission mStoragePermission = StoragePermission.init(this);
    private final BroadcastReceiver mBatchOpsBroadCastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mActionCompleteInterface != null) {
                ArrayList<String> failedPackages = intent.getStringArrayListExtra(BatchOpsService.EXTRA_FAILED_PKG);
                mActionCompleteInterface.onActionComplete(mMode, failedPackages != null
                        ? failedPackages.toArray(new String[0]) : new String[0]);
            }
            mActivity.unregisterReceiver(mBatchOpsBroadCastReceiver);
        }
    };

    public void setOnActionCompleteListener(@NonNull BackupRestoreDialogFragment.ActionCompleteInterface listener) {
        mActionCompleteInterface = listener;
    }

    public void setOnActionBeginListener(@NonNull BackupRestoreDialogFragment.ActionBeginInterface listener) {
        mActionBeginInterface = listener;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mActivity = requireActivity();
        mStoragePermission.request();
    }

    @SuppressLint("RestrictedApi")
    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        mViewModel = new ViewModelProvider(this).get(BackupRestoreDialogViewModel.class);
        Bundle args = requireArguments();
        String packageName = Objects.requireNonNull(args.getString(ARG_PACKAGE_NAME));
        int userId = args.getInt(ARG_USER_ID, UserHandleHidden.myUserId());
        CharSequence label = args.getCharSequence(ARG_LABEL);

        MaterialAlertDialogBuilder builder = ForkDialog.builder(requireContext());
        // The builder's context carries the yellow-on-black dialog overlay; the
        // fragment's does not. Everything below must be inflated from it or the
        // pills and tick marks come out in the activity's colours.
        mDialogContext = builder.getContext();
        mMultiChoiceItemLayout = MaterialAttributes.resolveInteger(mDialogContext,
                androidx.appcompat.R.attr.multiChoiceItemLayout,
                com.google.android.material.R.layout.mtrl_alert_select_dialog_multichoice);

        View body = LayoutInflater.from(mDialogContext).inflate(R.layout.dialog_app_backup, null);
        mMessageView = body.findViewById(R.id.message);
        mListContainer = body.findViewById(R.id.list_container);
        mBackupListView = body.findViewById(R.id.backup_list);
        mActionsView = body.findViewById(R.id.backup_actions);
        mRestoreButton = body.findViewById(R.id.action_restore);
        mDeleteButton = body.findViewById(R.id.action_delete);
        mMoreButton = body.findViewById(R.id.more);
        mRestoreButton.setOnClickListener(v -> {
            List<BackupMetadataV5> selected = getSelectedBackups();
            if (selected.size() == 1) {
                handleRestore(selected.get(0));
            }
        });
        mDeleteButton.setOnClickListener(v -> {
            List<BackupMetadataV5> selected = getSelectedBackups();
            if (!selected.isEmpty()) {
                handleDelete(selected);
            }
        });
        mMoreButton.setOnClickListener(v -> showMoreActions());

        // Loading is usually a blink, but it reads the backup metadata off disk,
        // so say so rather than showing an empty box.
        mMessageView.setText(R.string.loading);
        mMessageView.setVisibility(View.VISIBLE);

        builder.setTitle(!TextUtils.isEmpty(label) ? label : packageName)
                .setView(body)
                .setNegativeButton(R.string.cancel, null)
                // Wired up in onStart(): the click must NOT dismiss - the view
                // model that carries the operation dies with this fragment.
                .setPositiveButton(R.string.back_up, null);
        mDialog = ForkDialog.bordered(builder.create());

        mViewModel.getBackupInfoStateLiveData().observe(this, state -> onInfoLoaded());
        mViewModel.getBackupOperationLiveData().observe(this, this::startOperation);
        mViewModel.getUserSelectionLiveData().observe(this, this::handleCustomUsers);
        if (savedInstanceState == null) {
            mViewModel.processPackages(Collections.singletonList(new UserPackagePair(packageName, userId)));
        }
        return mDialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        View backUpButton = mDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (backUpButton != null) {
            backUpButton.setOnClickListener(v -> handleBackupClicked());
        }
        updateBackUpButton();
    }

    // ------------------------------------------------------------------
    // Loading & rendering
    // ------------------------------------------------------------------

    @UiThread
    private void onInfoLoaded() {
        mLoaded = true;
        List<BackupInfo> backupInfoList = mViewModel.getBackupInfoList();
        if (backupInfoList.isEmpty()) {
            // Neither installed nor backed up - the view model dropped it entirely.
            mInstalled = false;
            mBackups.clear();
            mSelected.clear();
            renderRows(new CharSequence[0]);
            mMessageView.setText(R.string.backup_dialog_unavailable);
            mMessageView.setVisibility(View.VISIBLE);
            updateBackUpButton();
            return;
        }
        BackupInfo backupInfo = backupInfoList.get(0);
        mInstalled = backupInfo.isInstalled();
        mDialog.setTitle(backupInfo.getAppLabel());
        mBackups.clear();
        mBackups.addAll(backupInfo.getBackupMetadataList());
        mSelected.clear();
        for (int i = 0; i < mBackups.size(); ++i) {
            // Same default as the bottom sheet: the base backup is the one you
            // almost always mean.
            if (mBackups.get(i).isBaseBackup()) {
                mSelected.add(i);
            }
        }
        updateBackUpButton();
        reloadRows();
    }

    /** Format the row labels off the main thread, then rebuild the tick list. */
    private void reloadRows() {
        if (mBackups.isEmpty()) {
            renderRows(new CharSequence[0]);
            return;
        }
        List<BackupMetadataV5> snapshot = new ArrayList<>(mBackups);
        ThreadUtils.postOnBackgroundThread(() -> {
            CharSequence[] labels = formatLabels(snapshot);
            ThreadUtils.postOnMainThread(() -> {
                if (!isAdded()) return;
                renderRows(labels);
            });
        });
    }

    @WorkerThread
    @NonNull
    private CharSequence[] formatLabels(@NonNull List<BackupMetadataV5> backups) {
        CharSequence[] labels = new CharSequence[backups.size()];
        for (int i = 0; i < backups.size(); ++i) {
            labels[i] = backups.get(i).toLocalizedString(mDialogContext);
        }
        return labels;
    }

    @UiThread
    private void renderRows(@NonNull CharSequence[] labels) {
        mBackupListView.removeAllViews();
        boolean hasBackups = labels.length > 0;
        mListContainer.setVisibility(hasBackups ? View.VISIBLE : View.GONE);
        mActionsView.setVisibility(hasBackups ? View.VISIBLE : View.GONE);
        if (!hasBackups) {
            if (mLoaded && mInstalled) {
                mMessageView.setText(R.string.backup_dialog_no_backups);
                mMessageView.setVisibility(View.VISIBLE);
            }
            return;
        }
        mMessageView.setVisibility(View.GONE);
        LayoutInflater inflater = LayoutInflater.from(mDialogContext);
        for (int i = 0; i < labels.length; ++i) {
            final int position = i;
            View row = inflater.inflate(mMultiChoiceItemLayout, mBackupListView, false);
            CheckedTextView item = row.findViewById(android.R.id.text1);
            item.setText(labels[i]);
            item.setTextColor(ContextCompat.getColor(mDialogContext, R.color.theme_bright_yellow));
            item.setChecked(mSelected.contains(position));
            item.setOnClickListener(v -> {
                if (!mSelected.remove(position)) {
                    mSelected.add(position);
                }
                item.setChecked(mSelected.contains(position));
                updateSelectionActions();
            });
            mBackupListView.addView(row);
        }
        updateSelectionActions();
    }

    @UiThread
    private void updateSelectionActions() {
        int count = mSelected.size();
        // The style pins its colours to literals, so a disabled MaterialButton
        // would look identical to an enabled one - dim it by hand.
        setActionEnabled(mRestoreButton, count == 1);
        setActionEnabled(mDeleteButton, count > 0);
        setActionEnabled(mMoreButton, count > 0);
    }

    private static void setActionEnabled(@NonNull MaterialButton button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.4f);
    }

    /** "Back up" only means something for an installed app. */
    @UiThread
    private void updateBackUpButton() {
        if (mDialog == null) return;
        View backUpButton = mDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (backUpButton == null) return;
        backUpButton.setVisibility(mLoaded && mInstalled ? View.VISIBLE : View.GONE);
    }

    @NonNull
    private List<BackupMetadataV5> getSelectedBackups() {
        List<BackupMetadataV5> selected = new ArrayList<>(mSelected.size());
        for (int position : mSelected) {
            if (position < mBackups.size()) {
                selected.add(mBackups.get(position));
            }
        }
        return selected;
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /**
     * Back up. With "Skip backup method dialog" on this starts immediately with
     * the stored defaults, exactly as the bottom sheet's backup-only path does;
     * otherwise it asks for the options first.
     */
    private void handleBackupClicked() {
        if (!mLoaded || !mInstalled) {
            return;
        }
        BackupFlags flags = BackupFlags.fromPref();
        if (Prefs.Storage.getSkipBackupMethodDialog()) {
            handleBackup(flags);
            return;
        }
        int supportedFlags = BackupFlags.getSupportedBackupFlags();
        supportedFlags &= ~BackupFlags.BACKUP_NO_SIGNATURE_CHECK;
        if (!mViewModel.allowCustomUsersInBackup()) {
            supportedFlags &= ~BackupFlags.BACKUP_CUSTOM_USERS;
        }
        List<Integer> supported = BackupFlags.getBackupFlagsAsArray(supportedFlags);
        new SearchableFlagsDialogBuilder<>(mDialogContext, supported,
                BackupFlags.getFormattedFlagNames(mDialogContext, supported), flags.getFlags())
                .setTitle(R.string.backup_options)
                .setPositiveButton(R.string.back_up, (dialog, which, selections) -> {
                    int newFlags = 0;
                    for (int flag : selections) {
                        newFlags |= flag;
                    }
                    handleBackup(new BackupFlags(newFlags));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void handleBackup(@NonNull BackupFlags flags) {
        BackupRestoreDialogViewModel.OperationInfo operationInfo = new BackupRestoreDialogViewModel.OperationInfo();
        operationInfo.mode = BackupRestoreDialogFragment.MODE_BACKUP;
        operationInfo.flags = flags.getFlags();
        operationInfo.op = BatchOpsManager.OP_BACKUP;
        if (flags.backupMultiple()) {
            // A named backup never overwrites anything, so there is nothing to warn about.
            new TextInputDialogBuilder(mDialogContext, R.string.input_backup_name)
                    .setTitle(R.string.backup)
                    .setHelperText(R.string.input_backup_name_description)
                    .setPositiveButton(R.string.ok, (dialog, which, input, isChecked) -> {
                        String backupName = !TextUtils.isEmpty(input)
                                ? input.toString()
                                : DateUtils.formatMediumDateTime(mDialogContext, System.currentTimeMillis());
                        operationInfo.backupNames = new String[]{backupName};
                        mViewModel.prepareForOperation(operationInfo);
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        List<BackupInfo> backupInfoList = mViewModel.getBackupInfoList();
        boolean hasBaseBackup = !backupInfoList.isEmpty() && backupInfoList.get(0).hasBaseBackup();
        if (hasBaseBackup) {
            // A base backup exists and is about to be overwritten.
            ForkDialog.present(ForkDialog.builder(mDialogContext)
                    .setTitle(R.string.backup)
                    .setMessage(getResources().getQuantityString(R.plurals.backup_exists_are_you_sure, 1))
                    .setPositiveButton(R.string.yes, (dialog, which) -> mViewModel.prepareForOperation(operationInfo))
                    .setNegativeButton(R.string.no, null));
            return;
        }
        mViewModel.prepareForOperation(operationInfo);
    }

    private void handleRestore(@NonNull BackupMetadataV5 selectedBackup) {
        BackupFlags flags = selectedBackup.info.flags;
        BackupFlags enabledFlags = BackupFlags.fromPref();
        enabledFlags.setFlags(flags.getFlags() & enabledFlags.getFlags());
        List<Integer> supportedBackupFlags = BackupFlags.getBackupFlagsAsArray(flags.getFlags());
        // Inject no signatures
        supportedBackupFlags.add(BackupFlags.BACKUP_NO_SIGNATURE_CHECK);
        supportedBackupFlags.add(BackupFlags.BACKUP_CUSTOM_USERS);
        List<Integer> disabledFlags = new ArrayList<>();
        if (!mInstalled) {
            enabledFlags.addFlag(BackupFlags.BACKUP_APK_FILES);
            disabledFlags.add(BackupFlags.BACKUP_APK_FILES);
        }
        new SearchableFlagsDialogBuilder<>(mDialogContext, supportedBackupFlags,
                BackupFlags.getFormattedFlagNames(mDialogContext, supportedBackupFlags), enabledFlags.getFlags())
                .setTitle(R.string.backup_options)
                .addDisabledItems(disabledFlags)
                .setPositiveButton(R.string.restore, (dialog, which, selections) -> {
                    int newFlags = 0;
                    for (int flag : selections) {
                        newFlags |= flag;
                    }
                    enabledFlags.setFlags(newFlags);

                    BackupRestoreDialogViewModel.OperationInfo operationInfo =
                            new BackupRestoreDialogViewModel.OperationInfo();
                    operationInfo.mode = BackupRestoreDialogFragment.MODE_RESTORE;
                    operationInfo.op = BatchOpsManager.OP_RESTORE_BACKUP;
                    operationInfo.flags = enabledFlags.getFlags();
                    operationInfo.relativeDirs = new String[]{selectedBackup.info.getRelativeDir()};
                    mViewModel.prepareForOperation(operationInfo);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void handleDelete(@NonNull List<BackupMetadataV5> selectedBackups) {
        ForkDialog.present(ForkDialog.builder(mDialogContext)
                .setTitle(R.string.delete_backup)
                .setMessage(R.string.are_you_sure)
                .setNegativeButton(R.string.no, null)
                .setPositiveButton(R.string.yes, (dialog, which) -> {
                    List<String> relativeDirs = new ArrayList<>(selectedBackups.size());
                    for (BackupMetadataV5 backup : selectedBackups) {
                        relativeDirs.add(backup.info.getRelativeDir());
                    }
                    BackupRestoreDialogViewModel.OperationInfo operationInfo =
                            new BackupRestoreDialogViewModel.OperationInfo();
                    operationInfo.mode = BackupRestoreDialogFragment.MODE_DELETE;
                    operationInfo.op = BatchOpsManager.OP_DELETE_BACKUP;
                    operationInfo.relativeDirs = relativeDirs.toArray(new String[0]);
                    mViewModel.prepareForOperation(operationInfo);
                }));
    }

    /**
     * Freezing a backup drops a marker file in it that protects it from being
     * rotated away. Only the applicable entry is offered - a list of two where
     * one is greyed out says less than a list of one.
     */
    private void showMoreActions() {
        List<BackupMetadataV5> selected = getSelectedBackups();
        if (selected.isEmpty()) {
            return;
        }
        int frozen = 0;
        for (BackupMetadataV5 metadata : selected) {
            if (metadata.info.isFrozen()) {
                ++frozen;
            }
        }
        List<CharSequence> labels = new ArrayList<>(2);
        List<Boolean> freezeActions = new ArrayList<>(2);
        if (frozen < selected.size()) {
            labels.add(getString(R.string.freeze));
            freezeActions.add(true);
        }
        if (frozen > 0) {
            labels.add(getString(R.string.unfreeze));
            freezeActions.add(false);
        }
        if (labels.isEmpty()) {
            return;
        }
        ForkDialog.present(ForkDialog.builder(mDialogContext)
                .setTitle(R.string.backup_dialog_more)
                .setAdapter(new ArrayAdapter<>(mDialogContext, R.layout.item_dialog_pill, labels),
                        (dialog, which) -> setFrozen(selected, freezeActions.get(which)))
                .setNegativeButton(R.string.cancel, null));
    }

    private void setFrozen(@NonNull List<BackupMetadataV5> backups, boolean freeze) {
        ThreadUtils.postOnBackgroundThread(() -> {
            boolean failed = false;
            for (BackupMetadataV5 metadata : backups) {
                if (metadata.info.getBackupItem() == null || metadata.info.isFrozen() == freeze) {
                    continue;
                }
                try {
                    if (freeze) {
                        metadata.info.getBackupItem().freeze();
                    } else {
                        metadata.info.getBackupItem().unfreeze();
                    }
                } catch (IOException e) {
                    failed = true;
                }
            }
            boolean anyFailed = failed;
            ThreadUtils.postOnMainThread(() -> {
                if (!isAdded()) return;
                if (anyFailed) {
                    UIUtils.displayShortToast(R.string.failed);
                }
                // The "Frozen" suffix lives in the row label, so re-read it.
                reloadRows();
            });
        });
    }

    // ------------------------------------------------------------------
    // Hand-off to the batch-ops service (mirrors BackupRestoreDialogFragment)
    // ------------------------------------------------------------------

    private void handleCustomUsers(@NonNull BackupRestoreDialogViewModel.OperationInfo operationInfo) {
        // NonNull check is added because we are only here when there are more than one users
        List<UserInfo> users = Objects.requireNonNull(operationInfo.userInfoList);
        CharSequence[] userNames = new String[users.size()];
        List<Integer> userHandles = new ArrayList<>(users.size());
        int i = 0;
        for (UserInfo info : users) {
            userNames[i] = info.toLocalizedString(mDialogContext);
            userHandles.add(info.id);
            ++i;
        }
        new SearchableMultiChoiceDialogBuilder<>(mDialogContext, userHandles, userNames)
                .setTitle(R.string.select_user)
                .addSelections(Collections.singletonList(UserHandleHidden.myUserId()))
                .showSelectAll(false)
                .setPositiveButton(R.string.ok, (dialog, which, selectedUsers) -> {
                    if (!selectedUsers.isEmpty()) {
                        operationInfo.selectedUsers = ArrayUtils.convertToIntArray(selectedUsers);
                    }
                    mViewModel.prepareForOperation(operationInfo);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @UiThread
    private void startOperation(@NonNull BackupRestoreDialogViewModel.OperationInfo operationInfo) {
        mMode = operationInfo.mode;
        if (mActionBeginInterface != null) {
            mActionBeginInterface.onActionBegin(operationInfo.mode);
        }
        ContextCompat.registerReceiver(mActivity, mBatchOpsBroadCastReceiver,
                new IntentFilter(BatchOpsService.ACTION_BATCH_OPS_COMPLETED), ContextCompat.RECEIVER_NOT_EXPORTED);
        BatchBackupOptions options = new BatchBackupOptions(operationInfo.flags, operationInfo.backupNames,
                operationInfo.relativeDirs);
        BatchQueueItem queueItem = BatchQueueItem.getBatchOpQueue(operationInfo.op, operationInfo.packageList,
                operationInfo.userIdListMappedToPackageList, options);
        Intent intent = BatchOpsService.getServiceIntent(mActivity, queueItem);
        ContextCompat.startForegroundService(mActivity, intent);
        dismiss();
    }
}
