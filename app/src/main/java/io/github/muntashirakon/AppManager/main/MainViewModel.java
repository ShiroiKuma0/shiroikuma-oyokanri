// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.os.UserHandleHidden;
import android.text.TextUtils;
import android.util.Pair;

import androidx.annotation.AnyThread;
import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.json.JSONException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Future;

import io.github.muntashirakon.AppManager.apk.list.ListExporter;
import io.github.muntashirakon.AppManager.backup.BackupUtils;
import io.github.muntashirakon.AppManager.compat.ActivityManagerCompat;
import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.db.entity.App;
import io.github.muntashirakon.AppManager.db.utils.AppDb;
import io.github.muntashirakon.AppManager.filters.FilterItem;
import io.github.muntashirakon.AppManager.filters.options.FilterOption;
import io.github.muntashirakon.AppManager.filters.options.PackageNameOption;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.misc.AdvancedSearchView;
import io.github.muntashirakon.AppManager.misc.ListOptions;
import io.github.muntashirakon.AppManager.profiles.ProfileManager;
import io.github.muntashirakon.AppManager.profiles.struct.AppsFilterProfile;
import io.github.muntashirakon.AppManager.profiles.struct.AppsProfile;
import io.github.muntashirakon.AppManager.profiles.struct.BaseProfile;
import io.github.muntashirakon.AppManager.self.SelfPermissions;
import io.github.muntashirakon.AppManager.settings.FeatureController;
import io.github.muntashirakon.AppManager.settings.Prefs;
import io.github.muntashirakon.AppManager.batchops.BatchOpsManager;
import io.github.muntashirakon.AppManager.types.PackageChangeReceiver;
import io.github.muntashirakon.AppManager.types.UserPackagePair;
import io.github.muntashirakon.AppManager.usage.AppUsageStatsManager;
import io.github.muntashirakon.AppManager.usage.PackageUsageInfo;
import io.github.muntashirakon.AppManager.usage.TimeInterval;
import io.github.muntashirakon.AppManager.usage.UsageUtils;
import io.github.muntashirakon.AppManager.users.Users;
import io.github.muntashirakon.AppManager.utils.ArrayUtils;
import io.github.muntashirakon.AppManager.utils.ExUtils;
import io.github.muntashirakon.AppManager.utils.FreezeUtils;
import io.github.muntashirakon.AppManager.utils.MultithreadedExecutor;
import io.github.muntashirakon.AppManager.utils.PackageUtils;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.Utils;
import io.github.muntashirakon.io.Path;

public class MainViewModel extends AndroidViewModel implements ListOptions.ListOptionActions {
    private final PackageManager mPackageManager;
    private final PackageIntentReceiver mPackageObserver;
    @MainListOptions.SortOrder
    private int mSortBy;
    private boolean mReverseSort;
    @MainListOptions.Filter
    private int mFilterFlags;
    /**
     * Profiles whose packages an app MUST belong to in order to pass the filter.
     * Empty set means "no include constraint". Multiple entries are ANDed
     * (intersection) — the app must be in all listed profiles.
     */
    @NonNull
    private final LinkedHashSet<String> mProfileFiltersInclude = new LinkedHashSet<>();
    /**
     * Profiles whose packages an app MUST NOT belong to. Empty set means "no
     * exclude constraint". Multiple entries are unioned for the exclusion set
     * — the app is rejected if it's in any of the listed profiles.
     */
    @NonNull
    private final LinkedHashSet<String> mProfileFiltersExclude = new LinkedHashSet<>();
    @Nullable
    private int[] mSelectedUsers;
    private String mSearchQuery;
    @AdvancedSearchView.SearchType
    private int mSearchType;
    private Future<?> mFilterResult;
    private final Map<String, ApplicationItem> mSelectedPackageApplicationItemMap = Collections.synchronizedMap(new LinkedHashMap<>());
    final MultithreadedExecutor executor = MultithreadedExecutor.getNewInstance();

    /** SharedPreferences file holding the multi-profile filter state. */
    private static final String PREFS_PROFILE_FILTER = "am_main_page_profile_filter";
    private static final String PREF_KEY_INCLUDE = "include";
    private static final String PREF_KEY_EXCLUDE = "exclude";

    public MainViewModel(@NonNull Application application) {
        super(application);
        Log.d("MVM", "New instance created");
        mPackageManager = application.getPackageManager();
        mPackageObserver = new PackageIntentReceiver(this);
        mSortBy = Prefs.MainPage.getSortOrder();
        mReverseSort = Prefs.MainPage.isReverseSort();
        mFilterFlags = Prefs.MainPage.getFilters();
        // Load multi-profile filter state from our own SharedPreferences file
        // (kept separate from libcore Prefs.MainPage so we don't have to thread
        // new keys through that class). Sorted alphabetically on load so the
        // iteration order is deterministic across runs - getStringSet does not
        // preserve insertion order.
        android.content.SharedPreferences sp = application.getSharedPreferences(
                PREFS_PROFILE_FILTER, android.content.Context.MODE_PRIVATE);
        List<String> includeLoad = new ArrayList<>(sp.getStringSet(
                PREF_KEY_INCLUDE, Collections.emptySet()));
        List<String> excludeLoad = new ArrayList<>(sp.getStringSet(
                PREF_KEY_EXCLUDE, Collections.emptySet()));
        Collections.sort(includeLoad);
        Collections.sort(excludeLoad);
        mProfileFiltersInclude.addAll(includeLoad);
        mProfileFiltersExclude.addAll(excludeLoad);
        // Legacy migration: if our new prefs are empty but the upstream
        // single-profile pref has a value, move it across so the user does
        // not lose a filter they set under the old UI.
        if (mProfileFiltersInclude.isEmpty() && mProfileFiltersExclude.isEmpty()) {
            String legacyName = Prefs.MainPage.getFilteredProfileName();
            if (legacyName != null && !legacyName.isEmpty()) {
                if (Prefs.MainPage.getFilteredProfileNegate()) {
                    mProfileFiltersExclude.add(legacyName);
                } else {
                    mProfileFiltersInclude.add(legacyName);
                }
            }
        }
        mSelectedUsers = null; // TODO: 5/6/23 Load from prefs?
    }

    private final MutableLiveData<Boolean> mOperationStatus = new MutableLiveData<>();
    @NonNull
    private final MutableLiveData<List<ApplicationItem>> mApplicationItemsLiveData = new MutableLiveData<>();
    private final List<ApplicationItem> mApplicationItems = new ArrayList<>();

    public int getApplicationItemCount() {
        return mApplicationItems.size();
    }

    @NonNull
    public LiveData<List<ApplicationItem>> getApplicationItems() {
        if (mApplicationItemsLiveData.getValue() == null) {
            loadApplicationItems();
        }
        return mApplicationItemsLiveData;
    }

    public LiveData<Boolean> getOperationStatus() {
        return mOperationStatus;
    }

    @GuardedBy("applicationItems")
    public ApplicationItem deselect(@NonNull ApplicationItem item) {
        synchronized (mApplicationItems) {
            int i = mApplicationItems.indexOf(item);
            if (i == -1) return item;
            item = mApplicationItems.get(i);
            mSelectedPackageApplicationItemMap.remove(item.packageName);
            item.isSelected = false;
            mApplicationItems.set(i, item);
            return item;
        }
    }

    @GuardedBy("applicationItems")
    public ApplicationItem select(@NonNull ApplicationItem item) {
        synchronized (mApplicationItems) {
            int i = mApplicationItems.indexOf(item);
            if (i == -1) return item;
            item = mApplicationItems.get(i);
            // Removal is needed because LinkedHashMap insertion-oriented
            mSelectedPackageApplicationItemMap.remove(item.packageName);
            mSelectedPackageApplicationItemMap.put(item.packageName, item);
            item.isSelected = true;
            mApplicationItems.set(i, item);
            return item;
        }
    }

    public void cancelSelection() {
        synchronized (mApplicationItems) {
            for (ApplicationItem item : getSelectedApplicationItems()) {
                int i = mApplicationItems.indexOf(item);
                if (i != -1) {
                    mApplicationItems.get(i).isSelected = false;
                }
            }
            mSelectedPackageApplicationItemMap.clear();
        }
    }

    @Nullable
    public ApplicationItem getLastSelectedPackage() {
        // Last selected package is the same as the last added package.
        Iterator<ApplicationItem> it = mSelectedPackageApplicationItemMap.values().iterator();
        ApplicationItem lastItem = null;
        while (it.hasNext()) {
            lastItem = it.next();
        }
        return lastItem;
    }

    public Map<String, ApplicationItem> getSelectedPackages() {
        return mSelectedPackageApplicationItemMap;
    }

    @NonNull
    public ArrayList<UserPackagePair> getSelectedPackagesWithUsers() {
        ArrayList<UserPackagePair> userPackagePairs = new ArrayList<>();
        int myUserId = UserHandleHidden.myUserId();
        int[] userIds = Users.getUsersIds();
        for (String packageName : mSelectedPackageApplicationItemMap.keySet()) {
            int[] userIds1 = Objects.requireNonNull(mSelectedPackageApplicationItemMap.get(packageName)).userIds;
            if (userIds1.length == 0) {
                // Could be a backup only item
                // Assign current user in it
                userPackagePairs.add(new UserPackagePair(packageName, myUserId));
            } else {
                for (int userHandle : userIds1) {
                    if (!ArrayUtils.contains(userIds, userHandle)) continue;
                    userPackagePairs.add(new UserPackagePair(packageName, userHandle));
                }
            }
        }
        return userPackagePairs;
    }

    public Collection<ApplicationItem> getSelectedApplicationItems() {
        return mSelectedPackageApplicationItemMap.values();
    }

    public String getSearchQuery() {
        return mSearchQuery;
    }

    public void setSearchQuery(String searchQuery, @AdvancedSearchView.SearchType int searchType) {
        this.mSearchQuery = searchType != AdvancedSearchView.SEARCH_TYPE_REGEX ? searchQuery.toLowerCase(Locale.ROOT) : searchQuery;
        this.mSearchType = searchType;
        cancelIfRunning();
        mFilterResult = executor.submit(this::filterItemsByFlags);
    }

    @Override
    public int getSortBy() {
        return mSortBy;
    }

    @Override
    public void setReverseSort(boolean reverseSort) {
        cancelIfRunning();
        mFilterResult = executor.submit(() -> {
            sortApplicationList(mSortBy, mReverseSort);
            filterItemsByFlags();
        });
        mReverseSort = reverseSort;
        Prefs.MainPage.setReverseSort(mReverseSort);
    }

    @Override
    public boolean isReverseSort() {
        return mReverseSort;
    }

    @Override
    public void setSortBy(int sortBy) {
        if (mSortBy != sortBy) {
            cancelIfRunning();
            mFilterResult = executor.submit(() -> {
                sortApplicationList(sortBy, mReverseSort);
                filterItemsByFlags();
            });
        }
        mSortBy = sortBy;
        Prefs.MainPage.setSortOrder(mSortBy);
    }

    @Override
    public boolean hasFilterFlag(@MainListOptions.Filter int flag) {
        return (mFilterFlags & flag) != 0;
    }

    @Override
    public void addFilterFlag(@MainListOptions.Filter int filterFlag) {
        mFilterFlags |= filterFlag;
        Prefs.MainPage.setFilters(mFilterFlags);
        cancelIfRunning();
        mFilterResult = executor.submit(this::filterItemsByFlags);
    }

    @Override
    public void removeFilterFlag(@MainListOptions.Filter int filterFlag) {
        mFilterFlags &= ~filterFlag;
        Prefs.MainPage.setFilters(mFilterFlags);
        cancelIfRunning();
        mFilterResult = executor.submit(this::filterItemsByFlags);
    }

    /**
     * True if anything is narrowing the displayed list: a flag filter, a
     * profile filter (include or exclude), or a search query. Drives the
     * "filter active" state of the list-options toolbar icon.
     */
    public boolean isFilterActive() {
        return mFilterFlags != 0
                || !mProfileFiltersInclude.isEmpty()
                || !mProfileFiltersExclude.isEmpty()
                || !TextUtils.isEmpty(mSearchQuery);
    }

    /**
     * Reset every filter to its unfiltered default in one shot: clears the flag
     * filters, both profile-filter sets (persisting the empty state), and the
     * search query, then re-runs the filter once. Sort order is intentionally
     * left untouched (it is not a filter).
     */
    public void clearAllFilters() {
        mFilterFlags = 0;
        Prefs.MainPage.setFilters(0);
        mProfileFiltersInclude.clear();
        mProfileFiltersExclude.clear();
        getApplication().getSharedPreferences(PREFS_PROFILE_FILTER, android.content.Context.MODE_PRIVATE)
                .edit()
                .putStringSet(PREF_KEY_INCLUDE, new HashSet<>())
                .putStringSet(PREF_KEY_EXCLUDE, new HashSet<>())
                .apply();
        mSearchQuery = null;
        cancelIfRunning();
        mFilterResult = executor.submit(this::filterItemsByFlags);
    }

    /**
     * Replace the entire profile filter with the given include and exclude
     * sets. The filter pipeline ANDs all include profiles (intersection) and
     * subtracts the union of all exclude profiles' packages from the result.
     * Persists to SharedPreferences and re-runs the filter on a background
     * thread.
     */
    public void setProfileFilters(@NonNull java.util.Set<String> include,
                                  @NonNull java.util.Set<String> exclude) {
        if (mProfileFiltersInclude.equals(include) && mProfileFiltersExclude.equals(exclude)) {
            return;
        }
        mProfileFiltersInclude.clear();
        mProfileFiltersInclude.addAll(include);
        mProfileFiltersExclude.clear();
        mProfileFiltersExclude.addAll(exclude);
        android.content.SharedPreferences sp = getApplication().getSharedPreferences(
                PREFS_PROFILE_FILTER, android.content.Context.MODE_PRIVATE);
        sp.edit()
                .putStringSet(PREF_KEY_INCLUDE, new HashSet<>(mProfileFiltersInclude))
                .putStringSet(PREF_KEY_EXCLUDE, new HashSet<>(mProfileFiltersExclude))
                .apply();
        cancelIfRunning();
        mFilterResult = executor.submit(this::filterItemsByFlags);
    }

    @NonNull
    public java.util.Set<String> getProfileFiltersInclude() {
        return Collections.unmodifiableSet(mProfileFiltersInclude);
    }

    @NonNull
    public java.util.Set<String> getProfileFiltersExclude() {
        return Collections.unmodifiableSet(mProfileFiltersExclude);
    }

    public boolean hasProfileFilters() {
        return !mProfileFiltersInclude.isEmpty() || !mProfileFiltersExclude.isEmpty();
    }

    /**
     * Backward-compat shim for the original single-profile API. Used by the
     * pill click and pill long-click handlers in MainRecyclerAdapter, and by
     * any caller predating the multi-profile filter. Replaces the entire
     * filter with this one profile in the include set (or clears the filter
     * if name is null).
     */
    public void setFilterProfileName(@Nullable String filterProfileName) {
        java.util.Set<String> include = new LinkedHashSet<>();
        java.util.Set<String> exclude = new LinkedHashSet<>();
        if (filterProfileName != null) {
            include.add(filterProfileName);
        }
        setProfileFilters(include, exclude);
    }

    /**
     * Backward-compat shim. Returns the first include profile, or the first
     * exclude profile, or null if no filter is active. Callers use it as a
     * boolean "filter active?" check (via != null), which is preserved.
     */
    @Nullable
    public String getFilterProfileName() {
        if (!mProfileFiltersInclude.isEmpty()) {
            return mProfileFiltersInclude.iterator().next();
        }
        if (!mProfileFiltersExclude.isEmpty()) {
            return mProfileFiltersExclude.iterator().next();
        }
        return null;
    }

    /**
     * Backward-compat shim. Only does anything if there's exactly one profile
     * in the filter — flips it between include and exclude. With multiple
     * profiles the call is ignored because there's no single polarity to
     * meaningfully toggle.
     */
    public void setFilterProfileNegate(boolean negate) {
        int total = mProfileFiltersInclude.size() + mProfileFiltersExclude.size();
        if (total != 1) return;
        String profile;
        if (!mProfileFiltersInclude.isEmpty()) {
            if (!negate) return;
            profile = mProfileFiltersInclude.iterator().next();
            java.util.Set<String> empty = Collections.emptySet();
            java.util.Set<String> just = new LinkedHashSet<>();
            just.add(profile);
            setProfileFilters(empty, just);
        } else {
            if (negate) return;
            profile = mProfileFiltersExclude.iterator().next();
            java.util.Set<String> just = new LinkedHashSet<>();
            just.add(profile);
            java.util.Set<String> empty = Collections.emptySet();
            setProfileFilters(just, empty);
        }
    }

    public boolean getFilterProfileNegate() {
        return mProfileFiltersInclude.isEmpty() && !mProfileFiltersExclude.isEmpty();
    }

    public void setSelectedUsers(@Nullable int[] selectedUsers) {
        if (selectedUsers == null) {
            if (mSelectedUsers == null) {
                // No change
                return;
            }
        } else if (mSelectedUsers != null) {
            if (mSelectedUsers.length == selectedUsers.length) {
                boolean differs = false;
                for (int user : selectedUsers) {
                    if (!ArrayUtils.contains(mSelectedUsers, user)) {
                        differs = true;
                        break;
                    }
                }
                if (!differs) {
                    // No change detected
                    return;
                }
            }
        }
        mSelectedUsers = selectedUsers;
        // TODO: 5/6/23 Store value to prefs
        cancelIfRunning();
        mFilterResult = executor.submit(this::filterItemsByFlags);
    }

    @Nullable
    public int[] getSelectedUsers() {
        return mSelectedUsers;
    }

    @AnyThread
    public void onResume() {
        if ((mFilterFlags & MainListOptions.FILTER_RUNNING_APPS) != 0) {
            // Reload filters to get running apps again
            cancelIfRunning();
            mFilterResult = executor.submit(this::filterItemsByFlags);
        }
    }

    public void saveExportedAppList(@ListExporter.ExportType int exportType, @NonNull Path path) {
        // Fork: snapshot the selection synchronously so the caller can clear it
        // right after launching the export (selections are cleared after every
        // action) without racing this background task.
        Map<String, ApplicationItem> selectedSnapshot = new LinkedHashMap<>(getSelectedPackages());
        executor.submit(() -> {
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(path.openOutputStream(), StandardCharsets.UTF_8))) {
                List<PackageInfo> packageInfoList = new ArrayList<>();
                for (String packageName : selectedSnapshot.keySet()) {
                    int[] userIds = Objects.requireNonNull(selectedSnapshot.get(packageName)).userIds;
                    for (int userId : userIds) {
                        packageInfoList.add(PackageManagerCompat.getPackageInfo(packageName,
                                PackageManagerCompat.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES, userId));
                        break;
                    }
                }
                ListExporter.export(getApplication(), writer, exportType, packageInfoList);
                mOperationStatus.postValue(true);
            } catch (IOException | RemoteException | PackageManager.NameNotFoundException e) {
                e.printStackTrace();
                mOperationStatus.postValue(false);
            }
        });
    }

    @GuardedBy("applicationItems")
    public void loadApplicationItems() {
        cancelIfRunning();
        mFilterResult = executor.submit(() -> {
            List<ApplicationItem> updatedApplicationItems = PackageUtils
                    .getInstalledOrBackedUpApplicationsFromDb(getApplication(), true, true);
            synchronized (mApplicationItems) {
                mApplicationItems.clear();
                mApplicationItems.addAll(updatedApplicationItems);
                // select apps again
                for (ApplicationItem item : getSelectedApplicationItems()) {
                    select(item);
                }
                sortApplicationList(mSortBy, mReverseSort);
                filterItemsByFlags();
            }
        });
    }

    private void cancelIfRunning() {
        if (mFilterResult != null) {
            mFilterResult.cancel(true);
        }
    }

    @WorkerThread
    private void filterItemsByQuery(@NonNull List<ApplicationItem> applicationItems) {
        List<ApplicationItem> filteredApplicationItems;
        if (mSearchType == AdvancedSearchView.SEARCH_TYPE_REGEX) {
            filteredApplicationItems = AdvancedSearchView.matches(mSearchQuery, applicationItems,
                    (AdvancedSearchView.ChoicesGenerator<ApplicationItem>) item -> new ArrayList<String>() {{
                        add(item.packageName);
                        add(item.label);
                    }}, AdvancedSearchView.SEARCH_TYPE_REGEX);
            mApplicationItemsLiveData.postValue(filteredApplicationItems);
            return;
        }
        // Others
        filteredApplicationItems = new ArrayList<>();
        for (ApplicationItem item : applicationItems) {
            if (ThreadUtils.isInterrupted()) {
                return;
            }
            if (AdvancedSearchView.matches(mSearchQuery, item.packageName.toLowerCase(Locale.ROOT), mSearchType)) {
                filteredApplicationItems.add(item);
            } else if (mSearchType == AdvancedSearchView.SEARCH_TYPE_CONTAINS) {
                if (Utils.containsOrHasInitials(mSearchQuery, item.label)) {
                    filteredApplicationItems.add(item);
                }
            } else if (AdvancedSearchView.matches(mSearchQuery, item.label.toLowerCase(Locale.ROOT), mSearchType)) {
                filteredApplicationItems.add(item);
            }
        }
        mApplicationItemsLiveData.postValue(filteredApplicationItems);
    }

    @WorkerThread
    @GuardedBy("applicationItems")
    private void filterItemsByFlags() {
        synchronized (mApplicationItems) {
            List<ApplicationItem> candidateApplicationItems = new ArrayList<>();
            // Multi-profile filter: include profiles are ANDed into one
            // FilterItem (intersection); exclude profiles union their packages
            // into excludePackages, which is subtracted from the result.
            // AppsFilterProfile excludes are deferred until the candidate list
            // exists (their matched-package set depends on it).
            FilterItem profileFilterItem = new FilterItem();
            List<AppsFilterProfile> excludeAppsFilterProfiles = new ArrayList<>();
            HashSet<String> excludePackages = new HashSet<>();

            // Resolve every include profile. AppsProfile -> add a
            // PackageNameOption "eq_any" with that profile's package list;
            // multiple PackageNameOptions in the same FilterItem are ANDed,
            // giving the intersection semantics the user asked for. An
            // AppsFilterProfile contributes its own filter options the same
            // way - again ANDed against everything else.
            for (String includeName : mProfileFiltersInclude) {
                String profileId = ProfileManager.getProfileIdCompat(includeName);
                Path profilePath = ProfileManager.findProfilePathById(profileId);
                if (profilePath == null) continue;
                try {
                    BaseProfile profile = BaseProfile.fromPath(profilePath);
                    if (profile instanceof AppsProfile) {
                        AppsProfile appsProfile = (AppsProfile) profile;
                        PackageNameOption option = new PackageNameOption();
                        option.setKeyValue("eq_any", TextUtils.join("\n", appsProfile.packages));
                        profileFilterItem.addFilterOption(option);
                    } else if (profile instanceof AppsFilterProfile) {
                        AppsFilterProfile filterProfile = (AppsFilterProfile) profile;
                        FilterItem filterItem = filterProfile.getFilterItem();
                        for (int i = 0; i < filterItem.getSize(); ++i) {
                            profileFilterItem.addFilterOption(filterItem.getFilterOptionAt(i));
                        }
                    }
                } catch (IOException | JSONException e) {
                    e.printStackTrace();
                }
            }

            // Resolve every exclude profile. AppsProfile -> union its
            // packages into excludePackages directly. AppsFilterProfile is
            // deferred because its matched packages depend on the candidate
            // set (built right below).
            for (String excludeName : mProfileFiltersExclude) {
                String profileId = ProfileManager.getProfileIdCompat(excludeName);
                Path profilePath = ProfileManager.findProfilePathById(profileId);
                if (profilePath == null) continue;
                try {
                    BaseProfile profile = BaseProfile.fromPath(profilePath);
                    if (profile instanceof AppsProfile) {
                        Collections.addAll(excludePackages, ((AppsProfile) profile).packages);
                    } else if (profile instanceof AppsFilterProfile) {
                        excludeAppsFilterProfiles.add((AppsFilterProfile) profile);
                    }
                } catch (IOException | JSONException e) {
                    e.printStackTrace();
                }
            }

            for (ApplicationItem item : mApplicationItems) {
                if (ThreadUtils.isInterrupted()) {
                    return;
                }
                if (isAmongSelectedUsers(item)) {
                    candidateApplicationItems.add(item);
                }
            }
            // Now resolve any deferred AppsFilterProfile exclusions against
            // the candidate set.
            for (AppsFilterProfile filterProfile : excludeAppsFilterProfiles) {
                FilterItem profileFilter = filterProfile.getFilterItem();
                List<FilterItem.FilteredItemInfo<ApplicationItem>> matched =
                        profileFilter.getFilteredList(candidateApplicationItems);
                for (FilterItem.FilteredItemInfo<ApplicationItem> m : matched) {
                    excludePackages.add(m.info.packageName);
                }
            }

            // Other filters
            boolean hasInclude = profileFilterItem.getSize() > 0;
            boolean hasExclusion = !excludePackages.isEmpty();
            if (!hasInclude && !hasExclusion && mFilterFlags == MainListOptions.FILTER_NO_FILTER) {
                if (!TextUtils.isEmpty(mSearchQuery)) {
                    filterItemsByQuery(candidateApplicationItems);
                } else {
                    mApplicationItemsLiveData.postValue(candidateApplicationItems);
                }
            } else {
                List<ApplicationItem> filteredApplicationItems = new ArrayList<>();
                FilterItem filterItem = MainListOptions.getFilterItemFromFlags(mFilterFlags);
                boolean needUsage = (hasInclude && profileFilterItem.getTimesUsageInfoUsed() > 0) || (filterItem.getTimesUsageInfoUsed() > 0);
                boolean needRunning = (hasInclude && profileFilterItem.getTimesRunningOptionUsed() > 0) || (filterItem.getTimesRunningOptionUsed() > 0);
                Map<String, PackageUsageInfo> packageUsageInfoList = new HashMap<>();
                if (needUsage) {
                    boolean hasUsageAccess = FeatureController.isUsageAccessEnabled() && SelfPermissions.checkUsageStatsPermission();
                    if (hasUsageAccess) {
                        TimeInterval interval = UsageUtils.getLastWeek();
                        for (int userId : Users.getUsersIds()) {
                            List<PackageUsageInfo> usageInfoList;
                            usageInfoList = ExUtils.exceptionAsNull(() -> AppUsageStatsManager
                                    .getInstance().getUsageStats(interval, userId));
                            if (usageInfoList != null) {
                                for (PackageUsageInfo info : usageInfoList) {
                                    if (ThreadUtils.isInterrupted()) return;
                                    PackageUsageInfo oldInfo = packageUsageInfoList.get(info.packageName);
                                    if (oldInfo != null) {
                                        oldInfo.screenTime += info.screenTime;
                                        oldInfo.lastUsageTime += info.lastUsageTime;
                                        oldInfo.timesOpened += info.timesOpened;
                                        oldInfo.mobileData = AppUsageStatsManager.DataUsage.fromDataUsage(oldInfo.mobileData, info.mobileData);
                                        oldInfo.wifiData = AppUsageStatsManager.DataUsage.fromDataUsage(oldInfo.wifiData, info.wifiData);
                                        if (info.entries != null) {
                                            if (oldInfo.entries == null) {
                                                oldInfo.entries = info.entries;
                                            } else oldInfo.entries.addAll(info.entries);
                                        }
                                    } else packageUsageInfoList.put(info.packageName, info);
                                }
                            }
                        }
                    }
                }
                HashSet<String> runningPackages = new HashSet<>();
                if (needRunning) {
                    for (ActivityManager.RunningAppProcessInfo info : ActivityManagerCompat.getRunningAppProcesses()) {
                        if (info.pkgList != null) {
                            runningPackages.addAll(Arrays.asList(info.pkgList));
                        }
                    }
                }
                for (ApplicationItem item : candidateApplicationItems) {
                    item.setPackageUsageInfo(packageUsageInfoList.get(item.packageName));
                    item.setRunning(runningPackages.contains(item.packageName));
                }
                List<ApplicationItem> result = filterItem.getFilteredAppInfoList(candidateApplicationItems);
                // Include intersection: keep only apps matching all include profiles.
                if (hasInclude) {
                    result = profileFilterItem.getFilteredAppInfoList(result);
                }
                // Exclude subtraction: drop apps belonging to any exclude profile.
                if (hasExclusion) {
                    List<ApplicationItem> kept = new ArrayList<>();
                    for (ApplicationItem item : result) {
                        if (!excludePackages.contains(item.packageName)) kept.add(item);
                    }
                    result = kept;
                }
                for (ApplicationItem item : result) {
                    if ((mFilterFlags & MainListOptions.FILTER_APPS_WITH_SPLITS) != 0 && !item.hasSplits) {
                        continue;
                    }
                    if ((mFilterFlags & MainListOptions.FILTER_APPS_WITH_SAF) != 0 && !item.usesSaf) {
                        continue;
                    }
                    filteredApplicationItems.add(item);
                }
                if (!TextUtils.isEmpty(mSearchQuery)) {
                    filterItemsByQuery(filteredApplicationItems);
                } else {
                    mApplicationItemsLiveData.postValue(filteredApplicationItems);
                }
            }
        }
    }

    private boolean isAmongSelectedUsers(@NonNull ApplicationItem applicationItem) {
        if (mSelectedUsers == null) {
            // All users
            return true;
        }
        for (int userId : mSelectedUsers) {
            if (ArrayUtils.contains(applicationItem.userIds, userId)) {
                return true;
            }
        }
        return false;
    }

    @GuardedBy("applicationItems")
    private void sortApplicationList(@MainListOptions.SortOrder int sortBy, boolean reverse) {
        synchronized (mApplicationItems) {
            if (sortBy != MainListOptions.SORT_BY_APP_LABEL) {
                sortApplicationList(MainListOptions.SORT_BY_APP_LABEL, false);
            }
            int mode = reverse ? -1 : 1;
            Collator collator = Collator.getInstance();
            Collections.sort(mApplicationItems, (o1, o2) -> {
                switch (sortBy) {
                    case MainListOptions.SORT_BY_APP_LABEL:
                        return mode * collator.compare(o1.label, o2.label);
                    case MainListOptions.SORT_BY_PACKAGE_NAME:
                        return mode * o1.packageName.compareTo(o2.packageName);
                    case MainListOptions.SORT_BY_DOMAIN:
                        boolean isSystem1 = (o1.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        boolean isSystem2 = (o2.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        return mode * Boolean.compare(isSystem1, isSystem2);
                    case MainListOptions.SORT_BY_LAST_UPDATE:
                        // Sort in decreasing order
                        return -mode * o1.lastUpdateTime.compareTo(o2.lastUpdateTime);
                    case MainListOptions.SORT_BY_TOTAL_SIZE:
                        // Sort in decreasing order
                        return -mode * o1.totalSize.compareTo(o2.totalSize);
                    case MainListOptions.SORT_BY_DATA_USAGE:
                        // Sort in decreasing order
                        return -mode * o1.dataUsage.compareTo(o2.dataUsage);
                    case MainListOptions.SORT_BY_OPEN_COUNT:
                        // Sort in decreasing order
                        return -mode * Integer.compare(o1.openCount, o2.openCount);
                    case MainListOptions.SORT_BY_INSTALLATION_DATE:
                        // Sort in decreasing order
                        return -mode * Long.compare(o1.firstInstallTime, o2.firstInstallTime);
                    case MainListOptions.SORT_BY_SCREEN_TIME:
                        // Sort in decreasing order
                        return -mode * Long.compare(o1.screenTime, o2.screenTime);
                    case MainListOptions.SORT_BY_LAST_USAGE_TIME:
                        // Sort in decreasing order
                        return -mode * Long.compare(o1.lastUsageTime, o2.lastUsageTime);
                    case MainListOptions.SORT_BY_TARGET_SDK:
                        // null on top
                        if (o1.targetSdk == null) return -mode;
                        else if (o2.targetSdk == null) return +mode;
                        return mode * o1.targetSdk.compareTo(o2.targetSdk);
                    case MainListOptions.SORT_BY_SHARED_ID:
                        return mode * Integer.compare(o1.uid, o2.uid);
                    case MainListOptions.SORT_BY_SHA:
                        // null on top
                        if (o1.sha == null) {
                            return -mode;
                        } else if (o2.sha == null) {
                            return +mode;
                        } else {  // Both aren't null
                            int i = o1.sha.first.compareToIgnoreCase(o2.sha.first);
                            if (i == 0) {
                                return mode * o1.sha.second.compareToIgnoreCase(o2.sha.second);
                            } else return mode * i;
                        }
                    case MainListOptions.SORT_BY_BLOCKED_COMPONENTS:
                        return -mode * o1.blockedCount.compareTo(o2.blockedCount);
                    case MainListOptions.SORT_BY_FROZEN_APP:
                        return -mode * Boolean.compare(o1.isDisabled, o2.isDisabled);
                    case MainListOptions.SORT_BY_BACKUP:
                        return -mode * Boolean.compare(o1.backup != null, o2.backup != null);
                    case MainListOptions.SORT_BY_LAST_ACTION:
                        return -mode * o1.lastActionTime.compareTo(o2.lastActionTime);
                    case MainListOptions.SORT_BY_TRACKERS:
                        return -mode * o1.trackerCount.compareTo(o2.trackerCount);
                }
                return 0;
            });
        }
    }

    @WorkerThread
    // Fork: snap the list to the post-batch-operation state in a single pass,
    // driven by BatchOpsService.ACTION_BATCH_OPS_COMPLETED. For freeze/unfreeze
    // this flips the affected items' freeze state in memory (no per-package
    // system re-read, which takes many seconds for a large batch) and re-filters
    // once — so the list snaps immediately instead of letting the system's
    // throttled per-package change broadcasts repaint it a couple of rows per
    // second. Those trickle broadcasts still arrive and reconcile the exact
    // state in the background, but re-filter to the same result, so there is no
    // visible movement. Other ops fall back to the targeted re-read.
    public void applyBatchOpResult(@BatchOpsManager.OpType int op, int result, @Nullable String[] packages,
                                   @Nullable List<String> failedPackages) {
        if (packages == null || packages.length == 0) {
            return;
        }
        // Fork: the cheap in-memory snaps below assume every queued package was
        // actually processed (RESULT_OK = all succeeded, RESULT_FIRST_USER = ran to
        // completion with some failed-and-listed). On RESULT_CANCELED the op stopped
        // early: the package list still names every queued app but the failed list is
        // empty, so an optimistic flip would mislabel the unprocessed ones. Fall back
        // to the true-state re-read for the whole batch in that case.
        boolean ranToCompletion = result != Activity.RESULT_CANCELED;
        HashSet<String> targets = new HashSet<>(Arrays.asList(packages));
        if (failedPackages != null) {
            // Don't touch packages the operation failed on — they kept their state.
            targets.removeAll(failedPackages);
        }
        if (targets.isEmpty()) {
            return;
        }
        Boolean frozenTarget = freezeTargetForOp(op);
        if (ranToCompletion && frozenTarget != null) {
            boolean frozen = frozenTarget;
            snapApplicationItems(targets, item -> item.setFrozenStateForBatchOp(frozen));
            return;
        }
        if (ranToCompletion && op == BatchOpsManager.OP_INSTALL_EXISTING) {
            // Fork: reinstall (install-existing) restores an uninstalled system app
            // to installed. Flip the cached installed flag in memory and re-filter
            // once, so the rows drop out of the "Uninstalled apps" filter at once —
            // the same instant snap freeze/unfreeze gets — instead of trickling out
            // one-by-one as the system's throttled per-package PACKAGE_ADDED
            // broadcasts arrive. The trailing re-reads then confirm the same state.
            snapApplicationItems(targets, ApplicationItem::setInstalledStateForBatchOp);
            return;
        }
        if (ranToCompletion && op == BatchOpsManager.OP_UNINSTALL) {
            snapUninstalledApplicationItems(targets);
            return;
        }
        // Other non-freeze ops: targeted re-read of just these packages.
        executor.submit(() -> updateInfoForPackages(packages, PackageChangeReceiver.ACTION_PACKAGE_ALTERED));
    }

    // Fork: snap the main list to its final state after a batch uninstall
    // (keepData=false) in one pass — the same instant treatment freeze/unfreeze
    // and reinstall get — instead of letting the system's throttled per-package
    // PACKAGE_REMOVED broadcasts repaint it row-by-row. Each affected row resolves
    // to exactly what the per-package re-read (AppDb.updateApplicationInternal,
    // which re-queries PM with MATCH_UNINSTALLED_PACKAGES) would produce:
    //   - an updated system app: uninstall only reverts the update, so it stays
    //     installed — left untouched here, its version is refreshed by the re-read;
    //   - a pure system app: uninstalled-for-user, survives as an "uninstalled"
    //     entry → flip isInstalled=false (the inverse of reinstall);
    //   - a user app with a backup: survives as a backup entry → flip likewise;
    //   - a user app with no backup: gone → remove the row outright.
    // The trailing broadcasts then merely confirm the same state, so there is no
    // visible row-by-row churn.
    private void snapUninstalledApplicationItems(@NonNull Set<String> targets) {
        executor.submit(() -> {
            boolean modified = false;
            synchronized (mApplicationItems) {
                ListIterator<ApplicationItem> it = mApplicationItems.listIterator();
                while (it.hasNext()) {
                    ApplicationItem item = it.next();
                    if (!targets.contains(item.packageName)) {
                        continue;
                    }
                    if (item.isUpdatedSystemApp()) {
                        // Reverts to the factory version but stays installed; leave
                        // the installed flag alone and let the re-read refresh it.
                        continue;
                    }
                    if (item.isSystem || item.backup != null) {
                        item.setUninstalledStateForBatchOp();
                    } else {
                        mSelectedPackageApplicationItemMap.remove(item.packageName);
                        it.remove();
                    }
                    modified = true;
                }
            }
            if (modified) {
                sortApplicationList(mSortBy, mReverseSort);
                filterItemsByFlags();
            }
        });
    }

    // Fork: apply an in-memory mutation to every list row whose package is in
    // `targets`, then re-sort and re-filter once on the model executor. Lets a
    // completed batch op snap the main list to its final state in a single pass
    // instead of waiting on the system's throttled per-package change broadcasts.
    private void snapApplicationItems(@NonNull Set<String> targets, @NonNull ItemMutator mutator) {
        executor.submit(() -> {
            boolean modified = false;
            synchronized (mApplicationItems) {
                for (ApplicationItem item : mApplicationItems) {
                    if (targets.contains(item.packageName)) {
                        mutator.mutate(item);
                        modified = true;
                    }
                }
            }
            if (modified) {
                sortApplicationList(mSortBy, mReverseSort);
                filterItemsByFlags();
            }
        });
    }

    // Fork: SAM for the in-memory row mutation applied by snapApplicationItems.
    private interface ItemMutator {
        void mutate(@NonNull ApplicationItem item);
    }

    @Nullable
    private static Boolean freezeTargetForOp(@BatchOpsManager.OpType int op) {
        switch (op) {
            case BatchOpsManager.OP_FREEZE:
            case BatchOpsManager.OP_ADVANCED_FREEZE:
                return Boolean.TRUE;
            case BatchOpsManager.OP_UNFREEZE:
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    private void updateInfoForUid(int uid, String action) {
        Log.d("updateInfoForUid", "Uid: %d", uid);
        String[] packages;
        if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) packages = getPackagesForUid(uid);
        else packages = mPackageManager.getPackagesForUid(uid);
        updateInfoForPackages(packages, action);
    }

    @WorkerThread
    private void updateInfoForPackages(@Nullable String[] packages, @NonNull String action) {
        Log.d("updateInfoForPackages", "packages: %s", Arrays.toString(packages));
        if (packages == null || packages.length == 0) return;
        boolean modified = false;
        switch (action) {
            case PackageChangeReceiver.ACTION_DB_PACKAGE_REMOVED:
            case PackageChangeReceiver.ACTION_DB_PACKAGE_ALTERED:
            case PackageChangeReceiver.ACTION_DB_PACKAGE_ADDED: {
                AppDb appDb = new AppDb();
                for (String packageName : packages) {
                    ApplicationItem item = getNewApplicationItem(packageName, appDb.getAllApplications(packageName));
                    modified |= item != null ? insertOrAddApplicationItem(item) : deleteApplicationItem(packageName);
                }
                break;
            }
            case PackageChangeReceiver.ACTION_PACKAGE_REMOVED:
            case PackageChangeReceiver.ACTION_PACKAGE_ALTERED:
            case PackageChangeReceiver.ACTION_PACKAGE_ADDED:
                // case BatchOpsService.ACTION_BATCH_OPS_COMPLETED:
            case Intent.ACTION_PACKAGE_REMOVED:
            case Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE:
            case Intent.ACTION_PACKAGE_ADDED:
            case Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE:
            case Intent.ACTION_PACKAGE_CHANGED: {
                List<App> appList = new AppDb().updateApplications(getApplication(), packages);
                for (String packageName : packages) {
                    ApplicationItem item = getNewApplicationItem(packageName, appList);
                    modified |= item != null ? insertOrAddApplicationItem(item) : deleteApplicationItem(packageName);
                }
                break;
            }
            default:
                return;
        }
        if (modified) {
            sortApplicationList(mSortBy, mReverseSort);
            filterItemsByFlags();
        }
    }

    @GuardedBy("applicationItems")
    private boolean insertOrAddApplicationItem(@Nullable ApplicationItem item) {
        if (item == null) return false;
        synchronized (mApplicationItems) {
            if (insertApplicationItem(item)) {
                return true;
            }
            boolean inserted = mApplicationItems.add(item);
            if (mSelectedPackageApplicationItemMap.containsKey(item.packageName)) {
                select(item);
            }
            return inserted;
        }
    }

    @GuardedBy("applicationItems")
    private boolean insertApplicationItem(@NonNull ApplicationItem item) {
        synchronized (mApplicationItems) {
            boolean isInserted = false;
            for (int i = 0; i < mApplicationItems.size(); ++i) {
                ApplicationItem oldItem = mApplicationItems.get(i);
                if (item.equals(oldItem)) {
                    if (oldItem.getItemVersion() == item.getItemVersion()) {
                        // Just changing the item version is enough
                        item.incItemVersion();
                    }
                    mApplicationItems.set(i, item);
                    isInserted = true;
                    if (mSelectedPackageApplicationItemMap.containsKey(item.packageName)) {
                        select(item);
                    }
                }
            }
            return isInserted;
        }
    }

    private boolean deleteApplicationItem(@NonNull String packageName) {
        synchronized (mApplicationItems) {
            ListIterator<ApplicationItem> it = mApplicationItems.listIterator();
            while (it.hasNext()) {
                ApplicationItem item = it.next();
                if (item.packageName.equals(packageName)) {
                    mSelectedPackageApplicationItemMap.remove(packageName);
                    it.remove();
                    return true;
                }
            }
            return false;
        }
    }

    @WorkerThread
    @Nullable
    private ApplicationItem getNewApplicationItem(@NonNull String packageName, @NonNull List<App> apps) {
        ApplicationItem item = new ApplicationItem();
        int thisUser = UserHandleHidden.myUserId();
        for (App app : apps) {
            if (!packageName.equals(app.packageName)) {
                // Package name didn't match
                continue;
            }
            if (app.isInstalled) {
                boolean newItem = item.packageName == null || !item.isInstalled;
                if (item.packageName == null) {
                    item.packageName = app.packageName;
                }
                item.userIds = ArrayUtils.appendInt(item.userIds, app.userId);
                item.isInstalled = true;
                item.isOnlyDataInstalled = false;
                item.openCount += app.openCount;
                item.screenTime += app.screenTime;
                if (item.lastUsageTime == 0L || item.lastUsageTime < app.lastUsageTime) {
                    item.lastUsageTime = app.lastUsageTime;
                }
                item.hasKeystore |= app.hasKeystore;
                item.usesSaf |= app.usesSaf;
                if (app.ssaid != null) {
                    item.ssaid = app.ssaid;
                }
                item.totalSize += app.codeSize + app.dataSize;
                item.dataUsage += app.wifiDataUsage + app.mobileDataUsage;
                if (!newItem && app.userId != thisUser) {
                    // This user has the highest priority
                    continue;
                }
            } else {
                // App not installed but may be installed in other profiles
                if (item.packageName != null) {
                    // Item exists, use the previous status
                    continue;
                } else {
                    item.packageName = app.packageName;
                    item.isInstalled = false;
                    item.isOnlyDataInstalled = app.isOnlyDataInstalled;
                    item.hasKeystore |= app.hasKeystore;
                }
            }
            item.flags = app.flags;
            item.uid = app.uid;
            item.debuggable = app.isDebuggable();
            item.isUser = !app.isSystemApp();
            item.isDisabled = !app.isEnabled;
            // Frozen / disabled state: prefer live PackageManager data over the DB
            // cache. The cache CAN be stale even after updateApplications() ran
            // (the freeze action may not have fully propagated when the broadcast
            // handler queried PM; or this branch may have been entered through
            // ACTION_DB_PACKAGE_ALTERED which skips updateApplications entirely).
            // Without this enrichment, a delayed broadcast can overwrite a correct
            // list state — see the project skill's freeze-indicator notes.
            // Fork: read lastUpdateTime live here too. The main-list icon-cache key folds in
            // lastUpdateTime (ImageLoader.versionedTag, used by MainRecyclerAdapter) so a
            // reinstall busts the stale cached icon — but the DB-cached app.lastUpdateTime can
            // lag a reinstall, leaving the key unchanged and the old icon on screen. getPackageInfo
            // yields both the live ApplicationInfo (for freeze state) and the live lastUpdateTime
            // in one query, replacing the former getApplicationInfo call at no extra cost.
            long liveLastUpdateTime = app.lastUpdateTime;
            try {
                PackageInfo livePi = getApplication().getPackageManager().getPackageInfo(packageName,
                        PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_UNINSTALLED_PACKAGES);
                ApplicationInfo liveAi = livePi.applicationInfo;
                item.isDisabled = !liveAi.enabled;
                item.isFrozen = FreezeUtils.isFrozen(liveAi);
                liveLastUpdateTime = livePi.lastUpdateTime;
            } catch (Throwable e) {
                // Not installed / not accessible — fall back to cached values
                item.isFrozen = !app.isEnabled;
            }
            item.label = app.packageLabel;
            item.targetSdk = app.sdk;
            item.versionName = app.versionName;
            item.versionCode = app.versionCode;
            item.sharedUserId = app.sharedUserId;
            item.sha = new Pair<>(app.certName, app.certAlgo);
            item.firstInstallTime = app.firstInstallTime;
            item.lastUpdateTime = liveLastUpdateTime; // Fork: live (see freeze/lastUpdateTime block above)
            item.hasActivities = app.hasActivities;
            item.hasSplits = app.hasSplits;
            item.blockedCount = app.rulesCount;
            item.trackerCount = app.trackerCount;
            item.lastActionTime = app.lastActionTime;
            if (item.backup == null) {
                item.backup = BackupUtils.getLatestBackupMetadataFromDbNoLockValidate(packageName);
            }
            item.generateOtherInfo();
        }
        if (item.packageName == null) {
            return null;
        }
        return item;
    }

    @GuardedBy("applicationItems")
    @NonNull
    private String[] getPackagesForUid(int uid) {
        synchronized (mApplicationItems) {
            List<String> packages = new LinkedList<>();
            for (ApplicationItem item : mApplicationItems) {
                if (item.uid == uid) packages.add(item.packageName);
            }
            return packages.toArray(new String[0]);
        }
    }

    @Override
    protected void onCleared() {
        if (mPackageObserver != null) getApplication().unregisterReceiver(mPackageObserver);
        executor.shutdownNow();
        super.onCleared();
    }

    public static class PackageIntentReceiver extends PackageChangeReceiver {
        private final MainViewModel mModel;

        public PackageIntentReceiver(@NonNull MainViewModel model) {
            super(model.getApplication());
            mModel = model;
        }

        @Override
        @WorkerThread
        protected void onPackageChanged(Intent intent, @Nullable Integer uid, @Nullable String[] packages) {
            mModel.cancelIfRunning();
            if (uid != null) {
                mModel.updateInfoForUid(uid, intent.getAction());
            } else if (packages != null) {
                mModel.updateInfoForPackages(packages, intent.getAction());
            } else {
                mModel.loadApplicationItems();
            }
        }
    }
}
