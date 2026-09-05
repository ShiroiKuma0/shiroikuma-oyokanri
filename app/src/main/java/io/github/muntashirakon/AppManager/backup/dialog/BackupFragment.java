// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.backup.dialog;

import android.content.Context;
import android.os.Bundle;
import android.os.UserHandleHidden;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Set;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.appdata.AppDataCategoryPicker;
import io.github.muntashirakon.AppManager.backup.BackupFlags;
import io.github.muntashirakon.AppManager.settings.Prefs;
import io.github.muntashirakon.AppManager.batchops.BatchOpsManager;
import io.github.muntashirakon.AppManager.utils.DateUtils;
import io.github.muntashirakon.AppManager.utils.ForkDialog;
import io.github.muntashirakon.dialog.TextInputDialogBuilder;
import io.github.muntashirakon.widget.MaterialAlertView;

public class BackupFragment extends Fragment {
    public static final String ARG_ALLOW_CUSTOM_USERS = "allow_custom";
    // Fork: true only when Backup is the sole action (no existing backup to
    // restore/delete). Gates the "Skip backup method dialog" auto-start so it
    // never fires from the backup tab of the backup+restore picker.
    public static final String ARG_SOLE_BACKUP_ACTION = "sole_backup";

    @NonNull
    public static BackupFragment getInstance(boolean allowCustomUsers) {
        return getInstance(allowCustomUsers, false);
    }

    @NonNull
    public static BackupFragment getInstance(boolean allowCustomUsers, boolean soleBackupAction) {
        BackupFragment fragment = new BackupFragment();
        Bundle args = new Bundle();
        args.putBoolean(ARG_ALLOW_CUSTOM_USERS, allowCustomUsers);
        args.putBoolean(ARG_SOLE_BACKUP_ACTION, soleBackupAction);
        fragment.setArguments(args);
        return fragment;
    }

    private BackupRestoreDialogViewModel mViewModel;
    private Context mContext;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dialog_backup, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mViewModel = new ViewModelProvider(requireParentFragment()).get(BackupRestoreDialogViewModel.class);
        mContext = requireContext();
        boolean allowCustomUsers = requireArguments().getBoolean(ARG_ALLOW_CUSTOM_USERS);
        // Fork: "Skip backup method dialog" — when Backup is the sole action and
        // the toggle is on, start the backup with the default options directly
        // and skip building the picker. handleBackup() still applies the
        // multiple-backup name prompt / overwrite warning where relevant.
        if (requireArguments().getBoolean(ARG_SOLE_BACKUP_ACTION, false)
                && Prefs.Storage.getSkipBackupMethodDialog()) {
            handleBackup(BackupFlags.fromPref());
            return;
        }

        MaterialAlertView messageView = view.findViewById(R.id.message);
        RecyclerView recyclerView = view.findViewById(android.R.id.list);
        recyclerView.setLayoutManager(new LinearLayoutManager(mContext, LinearLayoutManager.VERTICAL, false));
        int supportedFlags = BackupFlags.getSupportedBackupFlags();
        // Remove unsupported flags
        supportedFlags &= ~BackupFlags.BACKUP_NO_SIGNATURE_CHECK;
        if (!allowCustomUsers) {
            supportedFlags &= ~BackupFlags.BACKUP_CUSTOM_USERS;
        }
        FlagsAdapter adapter = new FlagsAdapter(mContext, BackupFlags.fromPref().getFlags(), supportedFlags);
        recyclerView.setAdapter(adapter);
        // Fork (白い熊, +112): the per-app category picker belongs ON the App-supplied data row,
        // not on a button elsewhere in the dialog. Offered only for a single app that implements
        // the contract — the choice is per package, so it is meaningless for a batch, which
        // instead applies silently whatever was chosen for each of its apps.
        List<BackupInfo> backupInfoList = mViewModel.getBackupInfoList();
        if (backupInfoList != null && backupInfoList.size() == 1) {
            BackupInfo info = backupInfoList.get(0);
            if (AppDataCategoryPicker.isAvailable(mContext, info.packageName)) {
                int userId = info.userIds.isEmpty() ? UserHandleHidden.myUserId() : info.userIds.valueAt(0);
                adapter.setFlagAction(BackupFlags.BACKUP_APP_DATA, R.string.appdata_categories,
                        flag -> AppDataCategoryPicker.show(mContext, info.packageName, userId, null));
            }
        }

        Set<CharSequence> uninstalledApps = mViewModel.getUninstalledApps();
        if (!uninstalledApps.isEmpty()) {
            SpannableStringBuilder sb = new SpannableStringBuilder(getString(R.string.backup_apps_cannot_be_backed_up));
            for (CharSequence appLabel : uninstalledApps) {
                sb.append("\n● ").append(appLabel);
            }
            messageView.setText(sb);
            messageView.setVisibility(View.VISIBLE);
        }
        view.findViewById(R.id.action_backup).setOnClickListener(v -> {
            BackupFlags newFlags = new BackupFlags(adapter.getSelectedFlags());
            handleBackup(newFlags);
        });
    }

    private void handleBackup(@NonNull BackupFlags flags) {
        BackupRestoreDialogViewModel.OperationInfo operationInfo = new BackupRestoreDialogViewModel.OperationInfo();
        operationInfo.mode = BackupRestoreDialogFragment.MODE_BACKUP;
        operationInfo.flags = flags.getFlags();
        operationInfo.op = BatchOpsManager.OP_BACKUP;
        if (flags.backupMultiple()) {
            // Multiple backup is requested, no need to warn users about backups since the
            // user has a choice between overwriting the existing backup or create a new one
            // TODO(18/9/20): Add overwrite option
            new TextInputDialogBuilder(mContext, R.string.input_backup_name)
                    .setTitle(R.string.backup)
                    .setHelperText(R.string.input_backup_name_description)
                    .setPositiveButton(R.string.ok, (dialog, which, input, isChecked) -> {
                        String backupName;
                        if (TextUtils.isEmpty(input)) {
                            backupName = DateUtils.formatMediumDateTime(mContext, System.currentTimeMillis());
                        } else {
                            backupName = input.toString();
                        }
                        operationInfo.backupNames = new String[]{backupName};
                        mViewModel.prepareForOperation(operationInfo);
                    })
                    .show();
        } else {
            // Base backup requested
            int baseBackupCount = mViewModel.getBackupInfoList().size() - mViewModel.getAppsWithoutBackups().size();
            if (baseBackupCount > 0) {
                // One or more app has backups, warn users
                // Fork: yellow-on-black + bordered like every other fork dialog.
                ForkDialog.present(ForkDialog.builder(mContext)
                        .setTitle(R.string.backup)
                        .setMessage(getResources().getQuantityString(R.plurals.backup_exists_are_you_sure, baseBackupCount))
                        .setPositiveButton(R.string.yes, (dialog, which) -> mViewModel.prepareForOperation(operationInfo))
                        .setNegativeButton(R.string.no, null));
            } else {
                // No need to warn users, proceed to back up
                mViewModel.prepareForOperation(operationInfo);
            }
        }
    }
}
