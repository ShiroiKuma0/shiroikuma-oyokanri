// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.Manifest;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.view.Menu;
import android.view.MenuItem;

import java.util.Collection;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.compat.ManifestCompat;
import io.github.muntashirakon.AppManager.self.SelfPermissions;
import io.github.muntashirakon.widget.MultiSelectionView;

public class MainBatchOpsHandler implements MultiSelectionView.OnSelectionChangeListener {
    private final MainViewModel mViewModel;
    private final MenuItem mUninstallMenu;
    private final MenuItem mReinstallMenu;
    private final MenuItem mFreezeUnfreezeMenu;
    private final MenuItem mUnfreezeMenu;
    private final MenuItem mForceStopMenu;
    private final MenuItem mClearDataCacheMenu;
    private final MenuItem mSaveApkMenu;
    private final MenuItem mBackupRestoreMenu;
    private final MenuItem mRestoreMenu;
    private final MenuItem mDeleteBackupMenu;
    private final MenuItem mShareBackupMenu;
    private final MenuItem mPreventBackgroundMenu;
    private final MenuItem mBlockUnblockTrackersMenu;
    private final MenuItem mNetPolicyMenu;
    private final MenuItem mExportRulesMenu;
    private final MenuItem mExportAppListMenu;
    private final MenuItem mOptimizeMenu;
    private final MenuItem mAddToProfileMenu;
    private final MenuItem mRemoveFromProfileMenu;

    private boolean mCanFreezeUnfreezePackages;
    private boolean mCanInstallExistingPackages;
    private boolean mCanForceStopPackages;
    private boolean mCanClearData;
    private boolean mCanClearCache;
    private boolean mCanModifyAppOpMode;
    private boolean mCanModifyNetPolicy;
    private boolean mCanModifyComponentState;

    public MainBatchOpsHandler(MultiSelectionView multiSelectionView, MainViewModel viewModel) {
        Menu selectionMenu = multiSelectionView.getMenu();
        mViewModel = viewModel;
        mUninstallMenu = selectionMenu.findItem(R.id.action_uninstall);
        mReinstallMenu = selectionMenu.findItem(R.id.action_install_existing);
        mFreezeUnfreezeMenu = selectionMenu.findItem(R.id.action_freeze_unfreeze);
        mUnfreezeMenu = selectionMenu.findItem(R.id.action_unfreeze);
        mForceStopMenu = selectionMenu.findItem(R.id.action_force_stop);
        mClearDataCacheMenu = selectionMenu.findItem(R.id.action_clear_data_cache);
        mSaveApkMenu = selectionMenu.findItem(R.id.action_save_apk);
        mBackupRestoreMenu = selectionMenu.findItem(R.id.action_backup);
        mRestoreMenu = selectionMenu.findItem(R.id.action_restore);
        mDeleteBackupMenu = selectionMenu.findItem(R.id.action_delete_backup);
        mShareBackupMenu = selectionMenu.findItem(R.id.action_share_backup);
        mPreventBackgroundMenu = selectionMenu.findItem(R.id.action_disable_background);
        mBlockUnblockTrackersMenu = selectionMenu.findItem(R.id.action_block_unblock_trackers);
        mNetPolicyMenu = selectionMenu.findItem(R.id.action_net_policy);
        mExportRulesMenu = selectionMenu.findItem(R.id.action_export_blocking_rules);
        mExportAppListMenu = selectionMenu.findItem(R.id.action_export_app_list);
        mOptimizeMenu = selectionMenu.findItem(R.id.action_optimize);
        mAddToProfileMenu = selectionMenu.findItem(R.id.action_add_to_profile);
        mRemoveFromProfileMenu = selectionMenu.findItem(R.id.action_remove_from_profile);
        updateConstraints();
    }

    public void updateConstraints() {
        mCanFreezeUnfreezePackages = SelfPermissions.canFreezeUnfreezePackages();
        mCanInstallExistingPackages = SelfPermissions.canInstallExistingPackages();
        mCanForceStopPackages = SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.FORCE_STOP_PACKAGES);
        mCanClearData = SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.CLEAR_APP_USER_DATA);
        mCanClearCache = SelfPermissions.canClearAppCache();
        mCanModifyAppOpMode = SelfPermissions.canModifyAppOpMode();
        mCanModifyNetPolicy = SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.MANAGE_NETWORK_POLICY);
        mCanModifyComponentState = SelfPermissions.checkSelfOrRemotePermission(Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE);
    }

    @Override
    public boolean onSelectionChange(int selectionCount) {
        Collection<ApplicationItem> selectedItems = mViewModel.getSelectedApplicationItems();
        boolean nonZeroSelection = !selectedItems.isEmpty();
        // It was ensured that the algorithm is greedy
        // Best case: O(1)
        // Worst case: O(n)
        boolean areAllInstalled = true;
        boolean areAllUninstalledWithoutData = true;
        boolean areAllUninstalledSystem = true;
        boolean doAllUninstalledhaveBackup = true;
        for (ApplicationItem item : selectedItems) {
            if (item.isInstalled) continue;
            areAllInstalled = false;
            if (areAllUninstalledWithoutData) {
                areAllUninstalledWithoutData = item.isOnlyDataInstalled;
            }
            if (!doAllUninstalledhaveBackup && !areAllUninstalledSystem) {
                // No need to check further
                break;
            }
            if (areAllUninstalledSystem && item.isUser) {
                areAllUninstalledSystem = false;
            }
            if (doAllUninstalledhaveBackup && item.backup == null) {
                doAllUninstalledhaveBackup = false;
            }
        }
        /* === Enable/Disable === */
        // Enable “Uninstall” action iff all selections are installed
        mUninstallMenu.setEnabled(nonZeroSelection && (areAllInstalled || areAllUninstalledWithoutData));
        // Fork: reinstall (install-existing) — for restoring uninstalled system
        // apps, so enable only when there is at least one uninstalled item and
        // every uninstalled item is a system app.
        if (mReinstallMenu != null) {
            mReinstallMenu.setEnabled(nonZeroSelection && !areAllInstalled && areAllUninstalledSystem);
        }
        mFreezeUnfreezeMenu.setEnabled(nonZeroSelection && areAllInstalled);
        // Fork: dedicated batch unfreeze — only meaningful for installed apps.
        if (mUnfreezeMenu != null) {
            mUnfreezeMenu.setEnabled(nonZeroSelection && areAllInstalled);
        }
        mForceStopMenu.setEnabled(nonZeroSelection && areAllInstalled);
        mClearDataCacheMenu.setEnabled(nonZeroSelection && areAllInstalled);
        mPreventBackgroundMenu.setEnabled(nonZeroSelection && areAllInstalled);
        mNetPolicyMenu.setEnabled(nonZeroSelection && areAllInstalled);
        mBlockUnblockTrackersMenu.setEnabled(nonZeroSelection && areAllInstalled);
        // Enable “Save APK” action iff all selections are installed or the uninstalled apps are all system apps
        mSaveApkMenu.setEnabled(nonZeroSelection && (areAllInstalled || areAllUninstalledSystem));
        // Enable “Back up” action iff all selections are installed or all the uninstalled apps have backups
        mBackupRestoreMenu.setEnabled(nonZeroSelection && (areAllInstalled || doAllUninstalledhaveBackup));
        // Fork (白い熊): Restore and Delete backup ask a DIFFERENT question from Back up. The rule
        // above is a backup rule -- it is about whether every selected app can be WRITTEN. What
        // restoring or deleting needs is only whether anything selected HAS a backup: installed
        // apps have backups too, and a selected app without one is simply not offered.
        boolean anyHasBackup = false;
        for (ApplicationItem item : selectedItems) {
            if (item.backup != null) {
                anyHasBackup = true;
                break;
            }
        }
        if (mRestoreMenu != null) {
            mRestoreMenu.setEnabled(nonZeroSelection && anyHasBackup);
        }
        if (mDeleteBackupMenu != null) {
            mDeleteBackupMenu.setEnabled(nonZeroSelection && anyHasBackup);
        }
        // Fork (白い熊): sharing a backup asks the same question as restoring one — is there a
        // backup to act on at all.
        if (mShareBackupMenu != null) {
            mShareBackupMenu.setEnabled(nonZeroSelection && anyHasBackup);
        }
        // Rests are enabled by default
        mExportRulesMenu.setEnabled(nonZeroSelection);
        mExportAppListMenu.setEnabled(nonZeroSelection);
        mOptimizeMenu.setEnabled(nonZeroSelection);
        mAddToProfileMenu.setEnabled(nonZeroSelection);
        if (mRemoveFromProfileMenu != null) {
            mRemoveFromProfileMenu.setEnabled(nonZeroSelection);
        }
        /* === Visible/Invisible === */
        mFreezeUnfreezeMenu.setVisible(mCanFreezeUnfreezePackages);
        if (mUnfreezeMenu != null) {
            mUnfreezeMenu.setVisible(mCanFreezeUnfreezePackages);
        }
        if (mReinstallMenu != null) {
            mReinstallMenu.setVisible(mCanInstallExistingPackages);
        }
        mForceStopMenu.setVisible(mCanForceStopPackages);
        mClearDataCacheMenu.setVisible(mCanClearData || mCanClearCache);
        mPreventBackgroundMenu.setVisible(mCanModifyAppOpMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N);
        mNetPolicyMenu.setVisible(mCanModifyNetPolicy);
        mBlockUnblockTrackersMenu.setVisible(mCanModifyComponentState);
        mOptimizeMenu.setVisible(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N);
        return true;
    }
}
