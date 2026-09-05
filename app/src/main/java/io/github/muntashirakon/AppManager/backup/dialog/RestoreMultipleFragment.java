// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.backup.dialog;

import android.content.Context;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import java.util.Set;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.batchops.BatchOpsManager;
import io.github.muntashirakon.AppManager.utils.ForkDialog;
import io.github.muntashirakon.widget.MaterialAlertView;

/**
 * Fork (白い熊, +132): batch restore asks what to put back, per app.
 *
 * <p>What stood here was one flag list built from {@code getWorstBackupFlag()} — the
 * intersection of every selected backup — so a part that only some archives carried could not be
 * chosen at all, and the apps that had it silently did not get it back. See
 * {@link RestorePartsTable} for what replaces it.
 */
public class RestoreMultipleFragment extends Fragment {
    @NonNull
    public static RestoreMultipleFragment getInstance() {
        return new RestoreMultipleFragment();
    }

    private BackupRestoreDialogViewModel mViewModel;
    private Context mContext;
    private RestorePartsTable mTable;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dialog_restore_multiple, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mViewModel = new ViewModelProvider(requireParentFragment()).get(BackupRestoreDialogViewModel.class);
        mContext = requireContext();

        MaterialAlertView messageView = view.findViewById(R.id.message);
        LinearLayoutCompat tableContainer = view.findViewById(R.id.restore_table);
        mTable = new RestorePartsTable(mContext, mViewModel.getBackupInfoList(),
                !mViewModel.getUninstalledApps().isEmpty());
        tableContainer.removeAllViews();
        tableContainer.addView(mTable.build());

        Set<CharSequence> appsWithoutBackups = mViewModel.getAppsWithoutBackups();
        if (!appsWithoutBackups.isEmpty()) {
            SpannableStringBuilder sb = new SpannableStringBuilder(getString(R.string.backup_apps_cannot_be_restored));
            for (CharSequence appLabel : appsWithoutBackups) {
                sb.append("\n● ").append(appLabel);
            }
            messageView.setText(sb);
            messageView.setVisibility(View.VISIBLE);
        }
        view.findViewById(R.id.action_restore).setOnClickListener(v -> handleRestore());
    }

    private void handleRestore() {
        if (mTable == null || mTable.isEmpty()) {
            return;
        }
        // Fork: yellow-on-black + bordered like every other fork dialog.
        ForkDialog.present(ForkDialog.builder(mContext)
                .setTitle(R.string.restore)
                .setMessage(R.string.are_you_sure)
                .setPositiveButton(R.string.yes, (dialog, which) -> {
                    BackupRestoreDialogViewModel.OperationInfo operationInfo = new BackupRestoreDialogViewModel.OperationInfo();
                    operationInfo.mode = BackupRestoreDialogFragment.MODE_RESTORE;
                    operationInfo.op = BatchOpsManager.OP_RESTORE_BACKUP;
                    operationInfo.flags = mTable.fallbackFlags();
                    operationInfo.perPackageFlags = mTable.perPackageFlags();
                    operationInfo.perPackageRelativeDirs = mTable.perPackageRelativeDirs();
                    mViewModel.prepareForOperation(operationInfo);
                })
                .setNegativeButton(R.string.no, null));
    }
}
