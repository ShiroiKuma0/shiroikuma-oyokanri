// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import static io.github.muntashirakon.AppManager.compat.PackageManagerCompat.MATCH_UNINSTALLED_PACKAGES;
import static io.github.muntashirakon.AppManager.utils.UIUtils.displayLongToast;
import static io.github.muntashirakon.AppManager.utils.UIUtils.displayShortToast;
import static io.github.muntashirakon.util.AdapterUtils.PAYLOAD_HIGHLIGHT_CHANGED;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.RemoteException;
import android.os.UserHandleHidden;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SectionIndexer;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.apk.installer.PackageInstallerActivity;
import io.github.muntashirakon.AppManager.apk.installer.PackageInstallerCompat;
import io.github.muntashirakon.AppManager.backup.dialog.BackupRestoreDialogFragment;
import io.github.muntashirakon.AppManager.compat.ApplicationInfoCompat;
import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.db.entity.Backup;
import io.github.muntashirakon.AppManager.details.AppDetailsActivity;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.self.SelfPermissions;
import io.github.muntashirakon.AppManager.self.imagecache.ImageLoader;
import io.github.muntashirakon.AppManager.settings.FeatureController;
import io.github.muntashirakon.AppManager.settings.Prefs;
import io.github.muntashirakon.AppManager.types.UserPackagePair;
import io.github.muntashirakon.AppManager.users.UserInfo;
import io.github.muntashirakon.AppManager.users.Users;
import io.github.muntashirakon.AppManager.utils.ArrayUtils;
import io.github.muntashirakon.AppManager.utils.BroadcastUtils;
import io.github.muntashirakon.AppManager.utils.DateUtils;
import io.github.muntashirakon.AppManager.utils.FreezeUtils;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;
import io.github.muntashirakon.AppManager.utils.appearance.ColorCodes;
import io.github.muntashirakon.dialog.SearchableItemsDialogBuilder;
import io.github.muntashirakon.io.Paths;
import io.github.muntashirakon.util.AccessibilityUtils;
import io.github.muntashirakon.util.AdapterUtils;
import io.github.muntashirakon.widget.MultiSelectionView;

public class MainRecyclerAdapter extends MultiSelectionView.Adapter<ApplicationItem, MainRecyclerAdapter.ViewHolder>
        implements SectionIndexer {
    private static final String TAG = MainRecyclerAdapter.class.getSimpleName();
    private static final String sSections = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private final MainActivity mActivity;
    private String mSearchQuery;

    private final int mColorGreen;
    private final int mColorOrange;
    private final int mColorPrimary;
    private final int mColorSecondary;
    private final int mQueryStringHighlight;
    // Custom-theme palette for the main list. mColorOrange is reused for non-frozen
    // system-app labels (same orange as the existing "SDK 35"/cleartext-traffic
    // highlight), so no new field is needed for that. mColorIceBlue is the unified
    // "frozen" indicator colour for both the snowflake icon and the label text;
    // it replaces the previous grey/greyish-orange split for frozen apps because
    // the outline-vs-filled drawable swap alone was too subtle to read.
    private final int mColorYellow;
    private final int mColorIceBlue;
    private final int mLabelFrozenUser;
    private final int mLabelFrozenSystem;

    private static final DiffUtil.ItemCallback<ApplicationItem> DIFF_CALLBACK = new DiffUtil.ItemCallback<ApplicationItem>() {
        @Override
        public boolean areItemsTheSame(@NonNull ApplicationItem oldItem, @NonNull ApplicationItem newItem) {
            return Objects.equals(oldItem.packageName, newItem.packageName);
        }

        @Override
        public boolean areContentsTheSame(@NonNull ApplicationItem oldItem, @NonNull ApplicationItem newItem) {
            return oldItem.getItemVersion() == newItem.getItemVersion();
        }
    };

    MainRecyclerAdapter(@NonNull MainActivity activity) {
        super(DIFF_CALLBACK);
        mActivity = activity;
        mColorGreen = ContextCompat.getColor(activity, io.github.muntashirakon.ui.R.color.stopped);
        mColorOrange = ContextCompat.getColor(activity, io.github.muntashirakon.ui.R.color.orange);
        mColorPrimary = ContextCompat.getColor(activity, io.github.muntashirakon.ui.R.color.textColorPrimary);
        mColorSecondary = ContextCompat.getColor(activity, io.github.muntashirakon.ui.R.color.textColorSecondary);
        mQueryStringHighlight = ColorCodes.getQueryStringHighlightColor(activity);
        mColorYellow = ContextCompat.getColor(activity, R.color.theme_bright_yellow);
        mColorIceBlue = ContextCompat.getColor(activity, R.color.theme_ice_blue);
        mLabelFrozenUser = ContextCompat.getColor(activity, R.color.theme_label_frozen_user);
        mLabelFrozenSystem = ContextCompat.getColor(activity, R.color.theme_label_frozen_system);
    }

    @UiThread
    void setDefaultList(List<ApplicationItem> list) {
        if (mActivity.viewModel == null) return;
        String oldSearchQuery = mSearchQuery;
        mSearchQuery = mActivity.viewModel.getSearchQuery();
        submitListWithScrollState(
                list != null ? new ArrayList<>(list) : null,
                AdapterUtils.isStartingSearch(oldSearchQuery, mSearchQuery),
                AdapterUtils.isClearingSearch(oldSearchQuery, mSearchQuery)
        );
        if (!Objects.equals(oldSearchQuery, mSearchQuery)) {
            notifyItemRangeChanged(0, getItemCount(), PAYLOAD_HIGHLIGHT_CHANGED);
        }
        notifySelectionChange();
    }

    @Override
    public void cancelSelection() {
        super.cancelSelection();
        if (mActivity.viewModel != null) {
            mActivity.viewModel.cancelSelection();
        }
    }

    @Override
    public int getSelectedItemCount() {
        if (mActivity.viewModel == null) return 0;
        return mActivity.viewModel.getSelectedPackages().size();
    }

    @Override
    protected int getTotalItemCount() {
        if (mActivity.viewModel == null) return 0;
        return mActivity.viewModel.getApplicationItemCount();
    }

    @Override
    protected boolean isSelected(int position) {
        return getItem(position).isSelected;
    }

    @Override
    protected boolean select(int position) {
        mActivity.viewModel.select(getItem(position));
        return true;
    }

    @Override
    protected boolean deselect(int position) {
        mActivity.viewModel.deselect(getItem(position));
        return true;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        final View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_main, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty()) {
            for (Object payload : payloads) {
                if (Objects.equals(payload, PAYLOAD_HIGHLIGHT_CHANGED)) {
                    updateTextHighlights(holder, getItem(position));
                }
            }
        }
        // Handle other stuff
        super.onBindViewHolder(holder, position, payloads);
    }

    private void updateTextHighlights(@NonNull ViewHolder holder, @NonNull ApplicationItem item) {
        String query = mSearchQuery;
        holder.label.setText(UIUtils.getHighlightedText(item.label, query, mQueryStringHighlight));
        holder.packageName.setText(UIUtils.getHighlightedText(item.packageName, query, mQueryStringHighlight));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final ApplicationItem item = getItem(position);
        MaterialCardView cardView = holder.itemView;
        Context context = cardView.getContext();
        // Add click listeners
        cardView.setOnClickListener(v -> {
            int currentPos = holder.getBindingAdapterPosition();
            if (currentPos == RecyclerView.NO_POSITION) return;
            // If selection mode is on, select/deselect the current item instead of the default behaviour
            if (isInSelectionMode()) {
                toggleSelection(currentPos);
                AccessibilityUtils.requestAccessibilityFocus(holder.itemView);
                return;
            }
            handleClick(item);
        });
        cardView.setOnLongClickListener(v -> {
            int currentPos = holder.getBindingAdapterPosition();
            if (currentPos == RecyclerView.NO_POSITION) return false;
            // Long click listener: Select/deselect an app.
            // 1) Turn selection mode on if this is the first item in the selection list
            // 2) Select between last selection position and this position (inclusive) if selection mode is on
            ApplicationItem lastSelectedItem = mActivity.viewModel.getLastSelectedPackage();
            int lastSelectedItemPosition = lastSelectedItem == null ? -1 : indexOf(lastSelectedItem);
            if (lastSelectedItemPosition >= 0) {
                // Select from last selection to this selection
                selectRange(lastSelectedItemPosition, currentPos);
            } else {
                toggleSelection(currentPos);
                AccessibilityUtils.requestAccessibilityFocus(holder.itemView);
            }
            return true;
        });
        // Box-stroke colors (custom theme): uninstalled > disabled > running > stopped(none)
        if (!item.isInstalled) {
            cardView.setStrokeColor(ColorCodes.getAppUninstalledIndicatorColor(context));
        } else if (item.isDisabled) {
            cardView.setStrokeColor(ColorCodes.getAppDisabledIndicatorColor(context));
        } else if (item.isStopped) {
            // Force-stopped apps lose their distinctive border in this fork
            cardView.setStrokeColor(Color.TRANSPARENT);
        } else {
            // Running apps (installed, not disabled, not stopped) get the yellow oval
            cardView.setStrokeColor(mColorYellow);
        }
        // Display yellow star if the app is in debug mode
        holder.debugIcon.setVisibility(item.debuggable ? View.VISIBLE : View.INVISIBLE);
        // Set date and (if available,) days between first installation and last update
        String lastUpdateDate = DateUtils.formatDate(context, item.lastUpdateTime);
        if (item.firstInstallTime == item.lastUpdateTime) {
            holder.date.setText(lastUpdateDate);
        } else {
            long days = item.diffInstallUpdateInDays;
            SpannableString ssDate = new SpannableString(context.getResources()
                    .getQuantityString(R.plurals.main_list_date_days, (int) days, lastUpdateDate, days));
            ssDate.setSpan(new RelativeSizeSpan(.8f), lastUpdateDate.length(),
                    ssDate.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            holder.date.setText(ssDate);
        }
        // Set date color to orange if app can read logs (and accepted)
        holder.date.setTextColor(item.canReadLogs ? mColorOrange : mColorSecondary);
        if (item.isInstalled) {
            // Set UID
            if (item.uidOrAppIds != null) {
                holder.userId.setText(item.uidOrAppIds);
            }
            // Set UID text color to orange if the package is shared
            holder.userId.setTextColor(item.sharedUserId != null ? mColorOrange : mColorSecondary);
        } else {
            holder.userId.setText("");
        }

        if (item.sha != null) {
            // Set issuer
            holder.issuer.setVisibility(View.VISIBLE);
            holder.issuer.setText(item.issuerShortName);
            // Set signature type
            holder.sha.setVisibility(View.VISIBLE);
            holder.sha.setText(item.sha.second);
        } else {
            holder.issuer.setVisibility(View.GONE);
            holder.sha.setVisibility(View.GONE);
        }
        // Load app icon
        holder.icon.setTag(item.packageName);
        ImageLoader.getInstance().displayImage(item.packageName, item, holder.icon);
        // Frozen apps: dim the icon, show a snowflake under it, italicize the label.
        // item.isFrozen covers PM-disabled, suspended, and hidden mechanisms. It is
        // populated from a live PackageManager query at list-load (see PackageUtils
        // .getInstalledOrBackedUpApplicationsFromDb) so DB-cache staleness doesn't
        // produce false negatives. ViewHolder is recycled, so every state below MUST
        // be set in both branches.
        holder.icon.setAlpha(item.isFrozen ? 0.5f : 1.0f);
        // Freeze indicator: always visible. Swap drawable between outline (not
        // frozen) and filled (frozen) AND swap the tint between yellow (not
        // frozen) and ice blue (frozen) so the state reads at a glance. The
        // drawable swap alone was too subtle on its own — Material Symbols
        // ac_unit looks similar in filled and outlined form — so the colour
        // change carries most of the signal.
        holder.freezeIndicator.setImageResource(item.isFrozen
                ? R.drawable.ic_snowflake_24dp
                : R.drawable.ic_snowflake_outline_24dp);
        holder.freezeIndicator.setImageTintList(ColorStateList.valueOf(
                item.isFrozen ? mColorIceBlue : mColorYellow));
        // Make the whole left icon column a tap target to toggle freeze, but
        // ONLY for eligible apps: user apps that are not AppManager itself.
        // System apps and our own package keep the column non-clickable so
        // taps fall through to the parent card's click handler (which opens
        // app details or toggles selection). Long-clicks on the column always
        // bubble up to the card's long-click handler, so selection-via-long-
        // press on the icon area continues to work in both branches. We
        // cannot reliably distinguish "critical" system apps from "regular"
        // ones via PackageManager metadata alone, so the conservative rule
        // is to never toggle any system app via this shortcut. (ViewHolder
        // recycling demands both branches set both properties.)
        if (item.isUser && !BuildConfig.APPLICATION_ID.equals(item.packageName)) {
            holder.iconColumn.setClickable(true);
            holder.iconColumn.setOnClickListener(v -> toggleFreeze(item));
        } else {
            holder.iconColumn.setOnClickListener(null);
            holder.iconColumn.setClickable(false);
        }
        holder.label.setTypeface(null, item.isFrozen ? Typeface.ITALIC : Typeface.NORMAL);
        // Set app label
        if (!TextUtils.isEmpty(mSearchQuery) && item.label.toLowerCase(Locale.ROOT).contains(mSearchQuery)) {
            // Highlight searched query
            holder.label.setText(UIUtils.getHighlightedText(item.label, mSearchQuery, mQueryStringHighlight));
        } else holder.label.setText(item.label);
        // Set app label color (custom theme — 3-state):
        // - frozen (user OR system)   : ice blue (same hue as the snowflake)
        // - non-frozen + user         : bright yellow
        // - non-frozen + system app   : orange (same as cleartext-traffic SDK highlight)
        // The mLabelFrozenUser / mLabelFrozenSystem fields are kept around in case
        // the user/system distinction is wanted back later, but neither is used here.
        int labelColor;
        if (item.isFrozen) {
            labelColor = mColorIceBlue;
        } else {
            labelColor = item.isUser ? mColorYellow : mColorOrange;
        }
        holder.label.setTextColor(labelColor);
        // Set package name
        if (!TextUtils.isEmpty(mSearchQuery) && item.packageName.toLowerCase(Locale.ROOT).contains(mSearchQuery)) {
            // Highlight searched query
            holder.packageName.setText(UIUtils.getHighlightedText(item.packageName, mSearchQuery, mQueryStringHighlight));
        } else {
            holder.packageName.setText(item.packageName);
        }
        // Set package name color to orange if the app has known tracker components
        if (item.trackerCount > 0) {
            holder.packageName.setTextColor(ColorCodes.getComponentTrackerIndicatorColor(context));
        } else {
            holder.packageName.setTextColor(mColorSecondary);
        }
        // Set version (along with HW accelerated, debug and test only flags)
        holder.version.setText(item.versionTag);
        // Set version color to dark cyan if the app is inactive
        holder.version.setTextColor(item.isAppInactive ? mColorGreen : mColorSecondary);
        // Set app type: system or user app (along with large heap, suspended, multi-arch,
        // has code, vm safe mode)
        if (item.isInstalled) {
            String isSystemApp = context.getString(item.isSystem ? R.string.system : R.string.user) + item.appTypePostfix;
            holder.isSystemApp.setText(isSystemApp);
        } else {
            holder.isSystemApp.setText("-");
        }
        // Set app type text color to magenta if the app is persistent
        holder.isSystemApp.setTextColor(item.isPersistent ? Color.MAGENTA : mColorSecondary);
        // Set SDK
        if (item.sdkString != null) {
            holder.size.setText(item.sdkString);
        } else {
            holder.size.setText("-");
        }
        // Set SDK color to orange if the app is using cleartext (e.g. HTTP) traffic
        holder.size.setTextColor(item.usesCleartextTraffic ? mColorOrange : mColorSecondary);
        // Check for backup
        if (item.backup != null) {
            holder.backupIndicator.setVisibility(View.VISIBLE);
            holder.backupInfo.setVisibility(View.VISIBLE);
            holder.backupInfoExt.setVisibility(View.VISIBLE);
            holder.backupIndicator.setText(R.string.backup);
            int indicatorColor;
            if (item.isInstalled) {
                if (item.backup.versionCode >= item.versionCode) {
                    // Up-to-date backup
                    indicatorColor = ColorCodes.getBackupLatestIndicatorColor(context);
                } else {
                    // Outdated backup
                    indicatorColor = ColorCodes.getBackupOutdatedIndicatorColor(context);
                }
            } else {
                // App not installed
                indicatorColor = ColorCodes.getBackupUninstalledIndicatorColor(context);
            }
            holder.backupIndicator.setTextColor(indicatorColor);
            Backup backup = item.backup;
            long days = item.lastBackupDays;
            holder.backupInfo.setText(String.format("%s: %s, %s %s",
                    context.getString(R.string.latest_backup), context.getResources()
                            .getQuantityString(R.plurals.usage_days, (int) days, days),
                    context.getString(R.string.version), backup.versionName));
            holder.backupInfoExt.setText(item.backupFlagsStr);
        } else {
            holder.backupIndicator.setVisibility(View.GONE);
            holder.backupInfo.setVisibility(View.GONE);
            holder.backupInfoExt.setVisibility(View.GONE);
        }
        super.onBindViewHolder(holder, position);
    }

    @Override
    public long getItemId(int position) {
        return getItem(position).hashCode();
    }

    @Override
    public int getPositionForSection(int section) {
        List<ApplicationItem> currentList = getCurrentList();
        for (int i = 0; i < currentList.size(); i++) {
            String item = currentList.get(i).label;
            if (!item.isEmpty()) {
                if (item.charAt(0) == sSections.charAt(section)) {
                    return i;
                }
            }
        }
        return 0;
    }

    @Override
    public int getSectionForPosition(int position) {
        return 0;
    }

    @Override
    public Object[] getSections() {
        String[] sectionsArr = new String[sSections.length()];
        for (int i = 0; i < sSections.length(); i++) {
            sectionsArr[i] = String.valueOf(sSections.charAt(i));
        }
        return sectionsArr;
    }

    private int indexOf(@Nullable ApplicationItem item) {
        if (item == null) {
            return -1;
        }
        List<ApplicationItem> list = getCurrentList();
        for (int i = 0; i < list.size(); ++i) {
            if (Objects.equals(list.get(i), item)) {
                return i;
            }
        }
        return -1;
    }

    private void handleClick(@NonNull ApplicationItem item) {
        if (!item.isInstalled || item.userIds.length == 0) {
            // The app should not be installed. But make sure this is really true. (For current user only)
            ApplicationInfo info;
            try {
                info = PackageManagerCompat.getApplicationInfo(item.packageName, MATCH_UNINSTALLED_PACKAGES
                                | PackageManagerCompat.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES,
                        UserHandleHidden.myUserId());
            } catch (RemoteException | PackageManager.NameNotFoundException e) {
                showBackupRestoreDialogOrAppNotInstalled(item);
                return;
            }
            // 1. Check if the app was really uninstalled.
            if (ApplicationInfoCompat.isInstalled(info)) {
                // The app is already installed, and we were wrong to assume that it was installed.
                // Update data before opening it.
                item.isInstalled = true;
                item.isOnlyDataInstalled = false;
                item.userIds = new int[]{UserHandleHidden.myUserId()};
                Intent intent = AppDetailsActivity.getIntent(mActivity, item.packageName, UserHandleHidden.myUserId());
                mActivity.startActivity(intent);
                return;
            }
            // 2. If the app can be installed, offer it to install again.
            if (FeatureController.isInstallerEnabled()) {
                if (ApplicationInfoCompat.isSystemApp(info) && SelfPermissions.canInstallExistingPackages()) {
                    // Install existing app instead of installing as an update
                    mActivity.startActivity(PackageInstallerActivity.getLaunchableInstance(mActivity, item.packageName));
                    return;
                }
                // Otherwise, try with APK files
                // FIXME: 1/4/23 Include splits
                if (Paths.exists(info.publicSourceDir)) {
                    mActivity.startActivity(PackageInstallerActivity.getLaunchableInstance(mActivity,
                            Uri.fromFile(new File(info.publicSourceDir))));
                    return;
                }
            }
            // 3. The app might be uninstalled without clearing data
            if (ApplicationInfoCompat.isSystemApp(info)) {
                // The app is a system app, there's no point in asking to uninstall it again
                showBackupRestoreDialogOrAppNotInstalled(item);
                return;
            }
            new MaterialAlertDialogBuilder(mActivity)
                    .setTitle(mActivity.getString(R.string.uninstall_app, item.label))
                    .setMessage(R.string.uninstall_app_again_message)
                    .setNegativeButton(R.string.no, null)
                    .setPositiveButton(R.string.yes, (dialog, which) -> ThreadUtils.postOnBackgroundThread(() -> {
                        PackageInstallerCompat installer = PackageInstallerCompat.getNewInstance();
                        installer.setAppLabel(item.label);
                        boolean uninstalled = installer.uninstall(item.packageName, UserHandleHidden.myUserId(), false);
                        ThreadUtils.postOnMainThread(() -> {
                            if (uninstalled) {
                                displayLongToast(R.string.uninstalled_successfully, item.label);
                            } else {
                                displayLongToast(R.string.failed_to_uninstall, item.label);
                            }
                        });
                    }))
                    .show();
            return;
        }
        // The app is installed
        if (item.userIds.length == 1) {
            int[] userHandles = Users.getUsersIds();
            if (ArrayUtils.contains(userHandles, item.userIds[0])) {
                Intent intent = AppDetailsActivity.getIntent(mActivity, item.packageName, item.userIds[0]);
                mActivity.startActivity(intent);
                return;
            }
            // Outside our jurisdiction
            showBackupRestoreDialogOrAppNotInstalled(item);
            return;
        }
        // More than a user, ask the user to select one
        CharSequence[] userNames = new String[item.userIds.length];
        List<UserInfo> users = Users.getUsers();
        for (UserInfo info : users) {
            for (int i = 0; i < item.userIds.length; ++i) {
                if (info.id == item.userIds[i]) {
                    userNames[i] = info.toLocalizedString(mActivity);
                }
            }
        }
        new SearchableItemsDialogBuilder<>(mActivity, userNames)
                .setTitle(R.string.select_user)
                .setOnItemClickListener((dialog, which, item1) -> {
                    Intent intent = AppDetailsActivity.getIntent(mActivity, item.packageName, item.userIds[which]);
                    mActivity.startActivity(intent);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showBackupRestoreDialogOrAppNotInstalled(@NonNull ApplicationItem item) {
        if (item.backup == null) {
            // No backups
            displayShortToast(R.string.app_not_installed);
            return;
        }
        // Has backups
        BackupRestoreDialogFragment fragment = BackupRestoreDialogFragment.getInstance(
                Collections.singletonList(new UserPackagePair(
                        item.packageName, UserHandleHidden.myUserId())));
        fragment.setOnActionBeginListener(mode -> mActivity.showProgressIndicator(true));
        fragment.setOnActionCompleteListener((mode, failedPackages) -> mActivity.showProgressIndicator(false));
        fragment.show(mActivity.getSupportFragmentManager(), BackupRestoreDialogFragment.TAG);
    }

    /**
     * Toggle the freeze state of {@code item} via the freeze-indicator click.
     * Uses {@link Prefs.Blocking#getDefaultFreezingMethod()} when freezing, and
     * the same internal unfreeze path the rest of the app uses when thawing.
     * The PM call runs on a background thread and is followed by a
     * {@code sendPackageAltered} broadcast so the main list re-binds the item
     * with the new state automatically.
     * <p>
     * Caller is responsible for the eligibility gate — this method does not
     * re-check whether the app is a system app or AppManager itself.
     */
    private void toggleFreeze(@NonNull ApplicationItem item) {
        final Context ctx = mActivity.getApplicationContext();
        final int userId = (item.userIds != null && item.userIds.length > 0)
                ? item.userIds[0]
                : UserHandleHidden.myUserId();
        final boolean wasFrozen = item.isFrozen;
        ThreadUtils.postOnBackgroundThread(() -> {
            try {
                if (wasFrozen) {
                    FreezeUtils.unfreeze(item.packageName, userId);
                } else {
                    FreezeUtils.freeze(item.packageName, userId,
                            Prefs.Blocking.getDefaultFreezingMethod());
                }
                BroadcastUtils.sendPackageAltered(ctx, new String[]{item.packageName});
            } catch (Throwable th) {
                Log.e(TAG, "Freeze toggle failed for " + item.packageName, th);
                ThreadUtils.postOnMainThread(() -> displayLongToast(
                        wasFrozen ? R.string.failed_to_unfreeze : R.string.failed_to_freeze,
                        item.label));
            }
        });
    }

    public static class ViewHolder extends MultiSelectionView.ViewHolder {
        MaterialCardView itemView;
        View iconColumn;
        AppCompatImageView icon;
        AppCompatImageView debugIcon;
        AppCompatImageView freezeIndicator;
        TextView label;
        TextView packageName;
        TextView version;
        TextView isSystemApp;
        TextView date;
        TextView size;
        TextView userId;
        TextView issuer;
        TextView sha;
        TextView backupIndicator;
        TextView backupInfo;
        TextView backupInfoExt;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            this.itemView = (MaterialCardView) itemView;
            iconColumn = itemView.findViewById(R.id.icon_column);
            icon = itemView.findViewById(R.id.icon);
            debugIcon = itemView.findViewById(R.id.favorite_icon);
            freezeIndicator = itemView.findViewById(R.id.freeze_indicator);
            label = itemView.findViewById(R.id.label);
            packageName = itemView.findViewById(R.id.packageName);
            version = itemView.findViewById(R.id.version);
            isSystemApp = itemView.findViewById(R.id.isSystem);
            date = itemView.findViewById(R.id.date);
            size = itemView.findViewById(R.id.size);
            userId = itemView.findViewById(R.id.shareid);
            issuer = itemView.findViewById(R.id.issuer);
            sha = itemView.findViewById(R.id.sha);
            backupIndicator = itemView.findViewById(R.id.backup_indicator);
            backupInfo = itemView.findViewById(R.id.backup_info);
            backupInfoExt = itemView.findViewById(R.id.backup_info_ext);
        }
    }
}