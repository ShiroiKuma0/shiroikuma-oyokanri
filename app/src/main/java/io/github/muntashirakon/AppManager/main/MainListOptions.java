// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Future;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.filters.FilterItem;
import io.github.muntashirakon.AppManager.filters.options.AppTypeOption;
import io.github.muntashirakon.AppManager.filters.options.BackupOption;
import io.github.muntashirakon.AppManager.filters.options.ComponentsOption;
import io.github.muntashirakon.AppManager.filters.options.FreezeOption;
import io.github.muntashirakon.AppManager.filters.options.InstalledOption;
import io.github.muntashirakon.AppManager.filters.options.RunningAppsOption;
import io.github.muntashirakon.AppManager.ipc.LocalServices;
import io.github.muntashirakon.AppManager.misc.ListOptions;
import io.github.muntashirakon.AppManager.profiles.ProfileManager;
import io.github.muntashirakon.AppManager.settings.FeatureController;
import io.github.muntashirakon.AppManager.settings.Ops;
import io.github.muntashirakon.AppManager.users.UserInfo;
import io.github.muntashirakon.AppManager.users.Users;
import io.github.muntashirakon.AppManager.utils.ArrayUtils;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.adapters.SelectedArrayAdapter;
import io.github.muntashirakon.dialog.SearchableMultiChoiceDialogBuilder;

public class MainListOptions extends ListOptions {
    public static final String TAG = MainListOptions.class.getSimpleName();

    @IntDef(value = {
            SORT_BY_DOMAIN,
            SORT_BY_APP_LABEL,
            SORT_BY_PACKAGE_NAME,
            SORT_BY_LAST_UPDATE,
            SORT_BY_SHARED_ID,
            SORT_BY_TARGET_SDK,
            SORT_BY_SHA,
            SORT_BY_FROZEN_APP,
            SORT_BY_BLOCKED_COMPONENTS,
            SORT_BY_BACKUP,
            SORT_BY_TRACKERS,
            SORT_BY_LAST_ACTION,
            SORT_BY_INSTALLATION_DATE,
            SORT_BY_TOTAL_SIZE,
            SORT_BY_DATA_USAGE,
            SORT_BY_OPEN_COUNT,
            SORT_BY_SCREEN_TIME,
            SORT_BY_LAST_USAGE_TIME,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SortOrder {
    }

    public static final int SORT_BY_DOMAIN = 0;  // User/system app
    public static final int SORT_BY_APP_LABEL = 1;
    public static final int SORT_BY_PACKAGE_NAME = 2;
    public static final int SORT_BY_LAST_UPDATE = 3;
    public static final int SORT_BY_SHARED_ID = 4;
    public static final int SORT_BY_TARGET_SDK = 5;
    public static final int SORT_BY_SHA = 6;  // Signature
    public static final int SORT_BY_FROZEN_APP = 7;
    public static final int SORT_BY_BLOCKED_COMPONENTS = 8;
    public static final int SORT_BY_BACKUP = 9;
    public static final int SORT_BY_TRACKERS = 10;
    public static final int SORT_BY_LAST_ACTION = 11;
    public static final int SORT_BY_INSTALLATION_DATE = 12;
    public static final int SORT_BY_TOTAL_SIZE = 13;
    public static final int SORT_BY_DATA_USAGE = 14;
    public static final int SORT_BY_OPEN_COUNT = 15;
    public static final int SORT_BY_SCREEN_TIME = 16;
    public static final int SORT_BY_LAST_USAGE_TIME = 17;

    @IntDef(flag = true, value = {
            FILTER_NO_FILTER,
            FILTER_USER_APPS,
            FILTER_SYSTEM_APPS,
            FILTER_FROZEN_APPS,
            FILTER_UNFROZEN_APPS,
            FILTER_APPS_WITH_RULES,
            FILTER_APPS_WITH_ACTIVITIES,
            FILTER_APPS_WITH_BACKUPS,
            FILTER_RUNNING_APPS,
            FILTER_APPS_WITH_SPLITS,
            FILTER_INSTALLED_APPS,
            FILTER_UNINSTALLED_APPS,
            FILTER_APPS_WITHOUT_BACKUPS,
            FILTER_APPS_WITH_KEYSTORE,
            FILTER_APPS_WITH_SAF,
            FILTER_APPS_WITH_SSAID,
            FILTER_STOPPED_APPS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Filter {
    }

    public static final int FILTER_NO_FILTER = 0;
    public static final int FILTER_USER_APPS = 1;
    public static final int FILTER_SYSTEM_APPS = 1 << 1;
    public static final int FILTER_FROZEN_APPS = 1 << 2;
    public static final int FILTER_APPS_WITH_RULES = 1 << 3;
    public static final int FILTER_APPS_WITH_ACTIVITIES = 1 << 4;
    public static final int FILTER_APPS_WITH_BACKUPS = 1 << 5;
    public static final int FILTER_RUNNING_APPS = 1 << 6;
    public static final int FILTER_APPS_WITH_SPLITS = 1 << 7;
    public static final int FILTER_INSTALLED_APPS = 1 << 8;
    public static final int FILTER_UNINSTALLED_APPS = 1 << 9;
    public static final int FILTER_APPS_WITHOUT_BACKUPS = 1 << 10;
    public static final int FILTER_APPS_WITH_KEYSTORE = 1 << 11;
    public static final int FILTER_APPS_WITH_SAF = 1 << 12;
    public static final int FILTER_APPS_WITH_SSAID = 1 << 13;
    public static final int FILTER_STOPPED_APPS = 1 << 14;
    public static final int FILTER_UNFROZEN_APPS = 1 << 15;

    // For now, just generate FilterItem
    @NonNull
    public static FilterItem getFilterItemFromFlags(int flags) {
        FilterItem filterItem = new FilterItem();
        // Flags
        int appTypeWithFlags = 0;
        if ((flags & FILTER_USER_APPS) != 0) {
            appTypeWithFlags |= AppTypeOption.APP_TYPE_USER;
        }
        if ((flags & FILTER_SYSTEM_APPS) != 0) {
            appTypeWithFlags |= AppTypeOption.APP_TYPE_SYSTEM;
        }
        if ((flags & FILTER_FROZEN_APPS) != 0) {
            FreezeOption option = new FreezeOption();
            option.setKeyValue("frozen", null);
            filterItem.addFilterOption(option);
        }
        if ((flags & FILTER_UNFROZEN_APPS) != 0) {
            FreezeOption option = new FreezeOption();
            option.setKeyValue("unfrozen", null);
            filterItem.addFilterOption(option);
        }
        if ((flags & FILTER_APPS_WITH_RULES) != 0) {
            appTypeWithFlags |= AppTypeOption.APP_TYPE_WITH_RULES;
        }
        if ((flags & FILTER_APPS_WITH_ACTIVITIES) != 0) {
            ComponentsOption option = new ComponentsOption();
            option.setKeyValue("with_type", String.valueOf(ComponentsOption.COMPONENT_TYPE_ACTIVITY));
            filterItem.addFilterOption(option);
        }
        if ((flags & FILTER_APPS_WITH_BACKUPS) != 0) {
            BackupOption option = new BackupOption();
            option.setKeyValue("backups", null);
            filterItem.addFilterOption(option);
        }
        if ((flags & FILTER_RUNNING_APPS) != 0) {
            RunningAppsOption option = new RunningAppsOption();
            option.setKeyValue("running", null);
            filterItem.addFilterOption(option);
        }
        if ((flags & FILTER_APPS_WITH_SPLITS) != 0) {
            // TODO: 7/28/25
        }
        if ((flags & FILTER_INSTALLED_APPS) != 0) {
            InstalledOption option = new InstalledOption();
            option.setKeyValue("installed", null);
            filterItem.addFilterOption(option);
        }
        if ((flags & FILTER_UNINSTALLED_APPS) != 0) {
            InstalledOption option = new InstalledOption();
            option.setKeyValue("uninstalled", null);
            filterItem.addFilterOption(option);
        }
        if ((flags & FILTER_APPS_WITHOUT_BACKUPS) != 0) {
            BackupOption option = new BackupOption();
            option.setKeyValue("no_backups", null);
            filterItem.addFilterOption(option);
        }
        if ((flags & FILTER_APPS_WITH_KEYSTORE) != 0) {
            appTypeWithFlags |= AppTypeOption.APP_TYPE_KEYSTORE;
        }
        if ((flags & FILTER_APPS_WITH_SAF) != 0) {
            // TODO: 7/28/25
        }
        if ((flags & FILTER_APPS_WITH_SSAID) != 0) {
            appTypeWithFlags |= AppTypeOption.APP_TYPE_SSAID;
        }
        if ((flags & FILTER_STOPPED_APPS) != 0) {
            appTypeWithFlags |= AppTypeOption.APP_TYPE_STOPPED;
        }
        if (appTypeWithFlags > 0) {
            AppTypeOption appTypeWithFlagsOption = new AppTypeOption();
            appTypeWithFlagsOption.setKeyValue("with_flags", String.valueOf(appTypeWithFlags));
            filterItem.addFilterOption(appTypeWithFlagsOption);
        }
        return filterItem;
    }

    private final List<String> mProfileNames = new ArrayList<>();
    private Future<?> mProfileSuggestionsResult;
    @Nullable
    private com.google.android.material.button.MaterialButton mProfileFilterPicker;

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        MainActivity activity = (MainActivity) requireActivity();
        // Upstream: reactive refresh of the options sheet on privilege-state change.
        LocalServices.state().observe(getViewLifecycleOwner(), ignored -> refreshOptions());
        mProfileFilterPicker = view.findViewById(R.id.profile_filter_picker);
        refreshProfileFilterButtonText(activity);
        mProfileFilterPicker.setOnClickListener(v -> openProfileFilterPicker(activity));
        // The legacy single-profile spinner + negate checkbox in this layout
        // are now invisible (kept only so the parent ListOptions findViewById
        // does not return null). Nothing to bind on them.
        mProfileSuggestionsResult = ThreadUtils.postOnBackgroundThread(() -> {
            List<String> names = new ArrayList<>(ProfileManager.getProfileNames());
            java.util.Collections.sort(names);
            if (isDetached() || ThreadUtils.isInterrupted()) return;
            activity.runOnUiThread(() -> {
                mProfileNames.clear();
                mProfileNames.addAll(names);
                refreshProfileFilterButtonText(activity);
            });
        });
        selectUserView.setVisibility(Users.getUsersIds().length <= 1 ? View.GONE : View.VISIBLE);
        selectUserView.setOnClickListener(v -> {
            List<UserInfo> userInfoList = Users.getUsers();
            List<Integer> userIdList = new ArrayList<>(userInfoList.size());
            CharSequence[] userInfoReadable = new CharSequence[userInfoList.size()];
            int i = 0;
            for (UserInfo userInfo : userInfoList) {
                userInfoReadable[i] = userInfo.toLocalizedString(requireContext());
                userIdList.add(userInfo.id);
                ++i;
            }
            List<Integer> selections;
            if (activity.viewModel != null) {
                int[] selectedUsers = activity.viewModel.getSelectedUsers();
                if (selectedUsers != null) {
                    selections = new ArrayList<>();
                    for (int userId : selectedUsers) {
                        selections.add(userId);
                    }
                } else selections = userIdList;
            } else selections = userIdList;
            new SearchableMultiChoiceDialogBuilder<>(requireContext(), userIdList, userInfoReadable)
                    .setTitle(R.string.filter)
                    .setNegativeButton(R.string.close, null)
                    .addSelections(selections)
                    .showSelectAll(true)
                    .hideSearchBar(true)
                    .setPositiveButton(R.string.filter, (dialog, which, selectedItems) -> {
                        if (activity.viewModel != null) {
                            if (selectedItems.size() == userInfoList.size()) {
                                // All users
                                activity.viewModel.setSelectedUsers(null);
                            } else {
                                activity.viewModel.setSelectedUsers(ArrayUtils.convertToIntArray(selectedItems));
                            }
                        }
                    })
                    .show();
        });
    }

    private void refreshOptions() {
        if (getView() != null) {
            reloadUi();
        }
    }

    @Override
    public void onDestroy() {
        if (mProfileSuggestionsResult != null) {
            mProfileSuggestionsResult.cancel(true);
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public LinkedHashMap<Integer, Integer> getSortIdLocaleMap() {
        return new LinkedHashMap<Integer, Integer>() {{
            put(SORT_BY_DOMAIN, R.string.sort_by_domain);
            put(SORT_BY_APP_LABEL, R.string.sort_by_app_label);
            put(SORT_BY_PACKAGE_NAME, R.string.sort_by_package_name);
            put(SORT_BY_LAST_UPDATE, R.string.sort_by_last_update);
            put(SORT_BY_SHARED_ID, R.string.sort_by_shared_user_id);
            put(SORT_BY_TARGET_SDK, R.string.sort_by_target_sdk);
            put(SORT_BY_SHA, R.string.sort_by_sha);
            put(SORT_BY_FROZEN_APP, R.string.sort_by_frozen_app);
            put(SORT_BY_BLOCKED_COMPONENTS, R.string.sort_by_blocked_components);
            put(SORT_BY_BACKUP, R.string.sort_by_backup);
            put(SORT_BY_TRACKERS, R.string.trackers);
            put(SORT_BY_LAST_ACTION, R.string.last_actions);
            put(SORT_BY_INSTALLATION_DATE, R.string.sort_by_installation_date);
            if (FeatureController.isUsageAccessEnabled()) {
                put(SORT_BY_TOTAL_SIZE, R.string.sort_by_total_size);
                put(SORT_BY_DATA_USAGE, R.string.sort_by_data_usage);
                put(SORT_BY_OPEN_COUNT, R.string.sort_by_times_opened);
                put(SORT_BY_SCREEN_TIME, R.string.sort_by_screen_time);
                put(SORT_BY_LAST_USAGE_TIME, R.string.sort_by_last_used);
            }
        }};
    }

    @Nullable
    @Override
    public LinkedHashMap<Integer, Integer> getFilterFlagLocaleMap() {
        return new LinkedHashMap<Integer, Integer>() {{
            put(FILTER_USER_APPS, R.string.filter_user_apps);
            put(FILTER_SYSTEM_APPS, R.string.filter_system_apps);
            put(FILTER_FROZEN_APPS, R.string.filter_frozen_apps);
            put(FILTER_UNFROZEN_APPS, R.string.filter_unfrozen_apps);
            put(FILTER_STOPPED_APPS, R.string.filter_force_stopped_apps);
            put(FILTER_INSTALLED_APPS, R.string.installed_apps);
            put(FILTER_UNINSTALLED_APPS, R.string.uninstalled_apps);
            put(FILTER_APPS_WITH_RULES, R.string.filter_apps_with_rules);
            put(FILTER_APPS_WITH_ACTIVITIES, R.string.filter_apps_with_activities);
            put(FILTER_APPS_WITH_BACKUPS, R.string.filter_apps_with_backups);
            put(FILTER_APPS_WITHOUT_BACKUPS, R.string.filter_apps_without_backups);
            put(FILTER_RUNNING_APPS, R.string.filter_running_apps);
            put(FILTER_APPS_WITH_SPLITS, R.string.filter_apps_with_splits);
            if (Ops.isWorkingUidRoot()) {
                put(FILTER_APPS_WITH_KEYSTORE, R.string.filter_apps_with_keystore);
                put(FILTER_APPS_WITH_SAF, R.string.filter_apps_with_saf);
                put(FILTER_APPS_WITH_SSAID, R.string.filter_apps_with_ssaid);
            }
        }};
    }

    @Nullable
    @Override
    public LinkedHashMap<Integer, Integer> getOptionIdLocaleMap() {
        return null;
    }

    @Override
    public boolean enableProfileNameInput() {
        return true;
    }

    @Override
    public boolean enableSelectUser() {
        return true;
    }

    /**
     * Refresh the profile-filter button's label to summarise the current
     * filter (e.g., "In: A, B   Not in: C") or fall back to a neutral prompt
     * when no filter is active.
     */
    private void refreshProfileFilterButtonText(@NonNull MainActivity activity) {
        if (mProfileFilterPicker == null || activity.viewModel == null) return;
        java.util.Set<String> include = activity.viewModel.getProfileFiltersInclude();
        java.util.Set<String> exclude = activity.viewModel.getProfileFiltersExclude();
        if (include.isEmpty() && exclude.isEmpty()) {
            mProfileFilterPicker.setText(R.string.profile_filter_button_none);
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (!include.isEmpty()) {
            sb.append("∈ ").append(TextUtils.join(", ", include));
        }
        if (!exclude.isEmpty()) {
            if (sb.length() > 0) sb.append("   ");
            sb.append("∉ ").append(TextUtils.join(", ", exclude));
        }
        mProfileFilterPicker.setText(getString(R.string.profile_filter_button_summary, sb.toString()));
    }

    /**
     * Build and show the multi-profile filter picker dialog. Each known
     * profile gets a row with two mutually-exclusive checkable chips: "In"
     * (include) and "Not in" (exclude). Both unchecked means the profile is
     * absent from the filter. The current selection is preloaded; pressing
     * OK commits the new filter via viewModel.setProfileFilters and the
     * outer button's label refreshes to summarise it.
     */
    private void openProfileFilterPicker(@NonNull MainActivity activity) {
        if (activity.viewModel == null) return;
        if (mProfileNames.isEmpty()) {
            // No profiles known yet (either none defined or background load
            // not finished). Nothing meaningful to show.
            return;
        }
        // Working copies of the current filter; mutated as the user toggles
        // rows, then committed on OK.
        java.util.LinkedHashSet<String> include =
                new java.util.LinkedHashSet<>(activity.viewModel.getProfileFiltersInclude());
        java.util.LinkedHashSet<String> exclude =
                new java.util.LinkedHashSet<>(activity.viewModel.getProfileFiltersExclude());
        int yellow = androidx.core.content.ContextCompat.getColor(activity, R.color.theme_bright_yellow);
        android.widget.LinearLayout content = new android.widget.LinearLayout(activity);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (8 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);
        android.view.LayoutInflater inflater = android.view.LayoutInflater.from(activity);
        for (String name : mProfileNames) {
            android.view.View row = inflater.inflate(
                    R.layout.dialog_profile_filter_picker_row, content, false);
            final android.widget.TextView nameTv = row.findViewById(R.id.profile_name);
            final android.widget.TextView plus = row.findViewById(R.id.btn_include);
            final android.widget.TextView minus = row.findViewById(R.id.btn_exclude);
            nameTv.setText(name);
            final String profileName = name;
            // Per-row tri-state: 0 = neutral, 1 = include (+), 2 = exclude (-).
            final int[] st = {include.contains(name) ? PROFILE_ROW_INCLUDE
                    : (exclude.contains(name) ? PROFILE_ROW_EXCLUDE : PROFILE_ROW_NEUTRAL)};
            Runnable render = () -> applyProfileRowState(activity, row, nameTv, plus, minus, st[0], yellow);
            render.run();
            Runnable sync = () -> {
                include.remove(profileName);
                exclude.remove(profileName);
                if (st[0] == PROFILE_ROW_INCLUDE) include.add(profileName);
                else if (st[0] == PROFILE_ROW_EXCLUDE) exclude.add(profileName);
            };
            // Whole-row tap cycles neutral -> include -> exclude -> neutral.
            row.setOnClickListener(v -> {
                st[0] = (st[0] + 1) % 3;
                sync.run();
                render.run();
            });
            // The "+" / "-" pills toggle their own polarity directly (tapping
            // an already-selected pill returns the row to neutral). Their
            // clicks are consumed here so they don't also fire the row's
            // cycle handler.
            plus.setOnClickListener(v -> {
                st[0] = (st[0] == PROFILE_ROW_INCLUDE) ? PROFILE_ROW_NEUTRAL : PROFILE_ROW_INCLUDE;
                sync.run();
                render.run();
            });
            minus.setOnClickListener(v -> {
                st[0] = (st[0] == PROFILE_ROW_EXCLUDE) ? PROFILE_ROW_NEUTRAL : PROFILE_ROW_EXCLUDE;
                sync.run();
                render.run();
            });
            content.addView(row);
        }
        // Wrap in a ScrollView in case the profile count is large.
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.addView(content);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.profile_filter_title)
                .setView(scroll)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    activity.viewModel.setProfileFilters(include, exclude);
                    refreshProfileFilterButtonText(activity);
                })
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.profile_filter_clear, (d, w) -> {
                    activity.viewModel.setProfileFilters(
                            java.util.Collections.emptySet(),
                            java.util.Collections.emptySet());
                    refreshProfileFilterButtonText(activity);
                })
                .show();
    }

    private static final int PROFILE_ROW_NEUTRAL = 0;
    private static final int PROFILE_ROW_INCLUDE = 1;
    private static final int PROFILE_ROW_EXCLUDE = 2;

    /**
     * Paint a profile-filter row for one of the three states. Everything is
     * built from GradientDrawable / LayerDrawable at runtime because the
     * yellow-on-black look needs line-fills, thick line-borders and a
     * double-bordered pill that the Material Chip checked/unchecked states
     * can't produce.
     *
     * neutral : transparent row, yellow name, both pills yellow-outlined.
     * include : row filled yellow, name black, "+" filled yellow w/ black
     *           border, "-" black-filled with a yellow border ringed by an
     *           outer black band so it stays visible on the yellow line.
     * exclude : transparent row inside a thick yellow rounded border, yellow
     *           name, "+" yellow-outlined, "-" filled yellow w/ black text.
     */
    private void applyProfileRowState(@NonNull android.content.Context ctx,
                                      @NonNull android.view.View row,
                                      @NonNull android.widget.TextView name,
                                      @NonNull android.widget.TextView plus,
                                      @NonNull android.widget.TextView minus,
                                      int state, int yellow) {
        final int black = android.graphics.Color.BLACK;
        final int transparent = android.graphics.Color.TRANSPARENT;
        final float d = ctx.getResources().getDisplayMetrics().density;
        final int rowRadius = (int) (12 * d);
        final int pillRadius = (int) (100 * d);
        final int rowStroke = (int) (3 * d);
        final int pillStroke = Math.max(1, (int) (1.5f * d));
        final int plusBlackStroke = (int) (2 * d);
        final int ringInset = (int) (3 * d);

        // Row background + profile-name colour
        if (state == PROFILE_ROW_INCLUDE) {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(yellow);
            bg.setCornerRadius(rowRadius);
            row.setBackground(bg);
            name.setTextColor(black);
        } else if (state == PROFILE_ROW_EXCLUDE) {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(transparent);
            bg.setStroke(rowStroke, yellow);
            bg.setCornerRadius(rowRadius);
            row.setBackground(bg);
            name.setTextColor(yellow);
        } else {
            row.setBackground(null);
            name.setTextColor(yellow);
        }

        // "+" pill
        if (state == PROFILE_ROW_INCLUDE) {
            android.graphics.drawable.GradientDrawable p = new android.graphics.drawable.GradientDrawable();
            p.setColor(yellow);
            p.setStroke(plusBlackStroke, black);
            p.setCornerRadius(pillRadius);
            plus.setBackground(p);
            plus.setTextColor(black);
        } else {
            plus.setBackground(neutralPill(yellow, pillStroke, pillRadius));
            plus.setTextColor(yellow);
        }

        // "-" pill
        if (state == PROFILE_ROW_EXCLUDE) {
            android.graphics.drawable.GradientDrawable m = new android.graphics.drawable.GradientDrawable();
            m.setColor(yellow);
            m.setCornerRadius(pillRadius);
            minus.setBackground(m);
            minus.setTextColor(black);
        } else if (state == PROFILE_ROW_INCLUDE) {
            // Double border: outer solid-black band, inner black fill with a
            // yellow stroke. The band separates the yellow stroke from the
            // yellow line behind it so the pill reads clearly.
            android.graphics.drawable.GradientDrawable outer = new android.graphics.drawable.GradientDrawable();
            outer.setColor(black);
            outer.setCornerRadius(pillRadius);
            android.graphics.drawable.GradientDrawable inner = new android.graphics.drawable.GradientDrawable();
            inner.setColor(black);
            inner.setStroke(pillStroke, yellow);
            inner.setCornerRadius(pillRadius);
            android.graphics.drawable.LayerDrawable layer = new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[]{outer, inner});
            layer.setLayerInset(1, ringInset, ringInset, ringInset, ringInset);
            minus.setBackground(layer);
            minus.setTextColor(yellow);
        } else {
            minus.setBackground(neutralPill(yellow, pillStroke, pillRadius));
            minus.setTextColor(yellow);
        }
    }

    @NonNull
    private android.graphics.drawable.GradientDrawable neutralPill(int yellow, int stroke, int radius) {
        android.graphics.drawable.GradientDrawable p = new android.graphics.drawable.GradientDrawable();
        p.setColor(android.graphics.Color.TRANSPARENT);
        p.setStroke(stroke, yellow);
        p.setCornerRadius(radius);
        return p;
    }
}
