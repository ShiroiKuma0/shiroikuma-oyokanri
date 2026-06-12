// SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.content.res.ColorStateList;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.PluralsRes;
import androidx.annotation.StringRes;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.SearchView;
import androidx.collection.ArrayMap;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.muntashirakon.AppManager.BaseActivity;
import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.apk.behavior.FreezeUnfreeze;
import io.github.muntashirakon.AppManager.apk.dexopt.DexOptDialog;
import io.github.muntashirakon.AppManager.apk.list.ListExporter;
import io.github.muntashirakon.AppManager.fonts.ColorPrefs;
import io.github.muntashirakon.AppManager.fonts.FontPrefs;
import io.github.muntashirakon.AppManager.fonts.FontUtil;
import io.github.muntashirakon.AppManager.fonts.SelectionFramePrefs;
import io.github.muntashirakon.AppManager.fonts.SeparatorPrefs;
import io.github.muntashirakon.AppManager.backup.dialog.BackupRestoreDialogFragment;
import io.github.muntashirakon.AppManager.batchops.BatchOpsManager;
import io.github.muntashirakon.AppManager.batchops.BatchOpsProgressMonitor;
import io.github.muntashirakon.AppManager.batchops.BatchOpsService;
import io.github.muntashirakon.AppManager.batchops.BatchQueueItem;
import io.github.muntashirakon.AppManager.batchops.struct.BatchFreezeOptions;
import io.github.muntashirakon.AppManager.batchops.struct.BatchNetPolicyOptions;
import io.github.muntashirakon.AppManager.batchops.struct.IBatchOpOptions;
import io.github.muntashirakon.AppManager.changelog.Changelog;
import io.github.muntashirakon.AppManager.changelog.ChangelogParser;
import io.github.muntashirakon.AppManager.changelog.ChangelogRecyclerAdapter;
import io.github.muntashirakon.AppManager.compat.NetworkPolicyManagerCompat;
import io.github.muntashirakon.AppManager.debloat.DebloaterActivity;
import io.github.muntashirakon.AppManager.filters.FinderActivity;
import io.github.muntashirakon.AppManager.misc.AdvancedSearchView;
import io.github.muntashirakon.AppManager.misc.HelpActivity;
import io.github.muntashirakon.AppManager.misc.LabsActivity;
import io.github.muntashirakon.AppManager.misc.SearchViewDebouncer;
import io.github.muntashirakon.AppManager.oneclickops.OneClickOpsActivity;
import io.github.muntashirakon.AppManager.profiles.AddToProfileDialogFragment;
import io.github.muntashirakon.AppManager.profiles.RemoveFromProfileDialogFragment;
import io.github.muntashirakon.AppManager.profiles.ProfilesActivity;
import io.github.muntashirakon.AppManager.profiles.ProtectedAppsProfile;
import io.github.muntashirakon.AppManager.rules.RulesTypeSelectionDialogFragment;
import io.github.muntashirakon.AppManager.runningapps.RunningAppsActivity;
import io.github.muntashirakon.AppManager.self.life.FundingCampaignChecker;
import io.github.muntashirakon.AppManager.settings.FeatureController;
import io.github.muntashirakon.AppManager.settings.Prefs;
import io.github.muntashirakon.AppManager.settings.SettingsActivity;
import io.github.muntashirakon.AppManager.usage.AppUsageActivity;
import io.github.muntashirakon.AppManager.users.Users;
import io.github.muntashirakon.AppManager.utils.AppPref;
import io.github.muntashirakon.AppManager.utils.DateUtils;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;
import io.github.muntashirakon.AppManager.utils.StoragePermission;
import io.github.muntashirakon.AppManager.utils.ClipboardUtils;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;
import io.github.muntashirakon.dialog.AlertDialogBuilder;
import io.github.muntashirakon.dialog.ScrollableDialogBuilder;
import io.github.muntashirakon.dialog.SearchableFlagsDialogBuilder;
import io.github.muntashirakon.dialog.SearchableSingleChoiceDialogBuilder;
import io.github.muntashirakon.io.Paths;
import io.github.muntashirakon.multiselection.MultiSelectionActionsView;
import io.github.muntashirakon.util.UiUtils;
import io.github.muntashirakon.widget.MultiSelectionView;
import io.github.muntashirakon.widget.SwipeRefreshLayout;

public class MainActivity extends BaseActivity implements SwipeRefreshLayout.OnRefreshListener,
        MultiSelectionActionsView.OnItemSelectedListener,
        MultiSelectionView.OnSelectionModeChangeListener {
    private static final String PACKAGE_NAME_APK_UPDATER = "com.apkupdater";
    private static final String ACTIVITY_NAME_APK_UPDATER = "com.apkupdater.activity.MainActivity";

    private static boolean SHOW_DISCLAIMER = true;

    MainViewModel viewModel;

    private MainRecyclerAdapter mAdapter;
    private AdvancedSearchView mSearchView;
    private SearchViewDebouncer mSearchDebouncer;
    private LinearProgressIndicator mProgressIndicator;
    private SwipeRefreshLayout mSwipeRefresh;
    // Fork: kept so the layout picker can swap the layout manager at runtime.
    private RecyclerView mRecyclerView;
    // Fork: separator grid between the edge-to-edge list cells.
    private MainSeparatorDecoration mSeparatorDecoration;
    private MultiSelectionView mMultiSelectionView;
    MainBatchOpsHandler mBatchOpsHandler;
    private MenuItem mAppUsageMenu;
    // Fork: floating selection reminder (count + hidden) above the action toolbar,
    // and the "Selected apps" sheet it opens.
    private View mSelectionReminder;
    private TextView mSelectionReminderText;
    @Nullable
    private SelectionListBottomSheet mSelectionSheet;

    private final StoragePermission mStoragePermission = StoragePermission.init(this);

    private final ActivityResultLauncher<String> mBatchExportRules = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("text/tab-separated-values"),
            uri -> {
                if (uri == null) {
                    // Back button pressed.
                    return;
                }
                if (viewModel == null) {
                    // Invalid state
                    return;
                }
                RulesTypeSelectionDialogFragment dialogFragment = new RulesTypeSelectionDialogFragment();
                Bundle args = new Bundle();
                args.putInt(RulesTypeSelectionDialogFragment.ARG_MODE, RulesTypeSelectionDialogFragment.MODE_EXPORT);
                args.putParcelable(RulesTypeSelectionDialogFragment.ARG_URI, uri);
                args.putStringArrayList(RulesTypeSelectionDialogFragment.ARG_PKG, new ArrayList<>(viewModel.getSelectedPackages().keySet()));
                args.putIntArray(RulesTypeSelectionDialogFragment.ARG_USERS, Users.getUsersIds());
                dialogFragment.setArguments(args);
                dialogFragment.show(getSupportFragmentManager(), RulesTypeSelectionDialogFragment.TAG);
                clearSelection();
            });

    private final ActivityResultLauncher<String> mExportAppListCsv = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("text/csv"),
            uri -> {
                if (uri == null) {
                    // Back button pressed.
                    return;
                }
                mProgressIndicator.show();
                viewModel.saveExportedAppList(ListExporter.EXPORT_TYPE_CSV, Paths.get(uri));
                clearSelection();
            });
    private final ActivityResultLauncher<String> mExportAppListJson = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"),
            uri -> {
                if (uri == null) {
                    // Back button pressed.
                    return;
                }
                mProgressIndicator.show();
                viewModel.saveExportedAppList(ListExporter.EXPORT_TYPE_JSON, Paths.get(uri));
                clearSelection();
            });
    private final ActivityResultLauncher<String> mExportAppListXml = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("text/xml"),
            uri -> {
                if (uri == null) {
                    // Back button pressed.
                    return;
                }
                mProgressIndicator.show();
                viewModel.saveExportedAppList(ListExporter.EXPORT_TYPE_XML, Paths.get(uri));
                clearSelection();
            });
    private final ActivityResultLauncher<String> mExportAppListMarkdown = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("text/markdown"),
            uri -> {
                if (uri == null) {
                    // Back button pressed.
                    return;
                }
                mProgressIndicator.show();
                viewModel.saveExportedAppList(ListExporter.EXPORT_TYPE_MARKDOWN, Paths.get(uri));
                clearSelection();
            });

    // Fork: the in-app batch-operation progress dialog (mirrors the foreground
    // notification in the main window, with Pause/Continue + Cancel).
    @Nullable
    private BatchProgressDialog mBatchProgressDialog;

    private final BroadcastReceiver mBatchOpsBroadCastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Fork: a batch op has started — surface it in the progress dialog.
            if (BatchOpsService.ACTION_BATCH_OPS_STARTED.equals(intent.getAction())) {
                showBatchProgressDialog();
                return;
            }
            // ACTION_BATCH_OPS_COMPLETED
            showProgressIndicator(false);
            if (mBatchProgressDialog != null) {
                mBatchProgressDialog.dismiss();
            }
            int op = intent.getIntExtra(BatchOpsService.EXTRA_OP, BatchOpsManager.OP_NONE);
            // Fork: show the completion as our themed toast (the un-themeable
            // system heads-up is suppressed while we're foreground — see
            // BatchOpsService.sendNotification). Failures keep their detailed
            // notification instead, so they get no toast here.
            int result = intent.getIntExtra(BatchOpsService.EXTRA_RESULT, Activity.RESULT_OK);
            String opTitle = BatchOpsService.getDesiredOpTitle(context, op);
            if (result == Activity.RESULT_OK) {
                UIUtils.displayShortToast(opTitle + " — " + context.getString(R.string.the_operation_was_successful));
            } else if (result == Activity.RESULT_CANCELED) {
                UIUtils.displayShortToast(opTitle + " — " + context.getString(R.string.operation_cancelled));
            }
            // Snap the list to its final state in one atomic pass instead of
            // letting the system's throttled per-package change broadcasts repaint
            // it a couple of rows per second after the operation already finished.
            if (viewModel != null) {
                viewModel.applyBatchOpResult(op, result,
                        intent.getStringArrayExtra(BatchOpsService.EXTRA_OP_PKG),
                        intent.getStringArrayListExtra(BatchOpsService.EXTRA_FAILED_PKG));
            }
        }
    };

    private final OnBackPressedCallback mOnBackPressedCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            if (mAdapter != null && mMultiSelectionView != null && mAdapter.isInSelectionMode()) {
                mMultiSelectionView.cancel();
                return;
            }
            setEnabled(false);
            getOnBackPressedDispatcher().onBackPressed();
        }
    };

    @SuppressLint("RestrictedApi")
    @Override
    protected void onAuthenticated(Bundle savedInstanceState) {
        setContentView(R.layout.activity_main);
        setSupportActionBar(findViewById(R.id.toolbar));
        getOnBackPressedDispatcher().addCallback(this, mOnBackPressedCallback);
        viewModel = new ViewModelProvider(this).get(MainViewModel.class);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayShowCustomEnabled(true);
            actionBar.setDisplayOptions(0, ActionBar.DISPLAY_SHOW_TITLE);
            mSearchView = new AdvancedSearchView(actionBar.getThemedContext());
            mSearchView.setId(R.id.action_search);
            // Set layout params
            ActionBar.LayoutParams layoutParams = new ActionBar.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            layoutParams.gravity = Gravity.CENTER;
            actionBar.setCustomView(mSearchView, layoutParams);
            mSearchView.setIconifiedByDefault(false);
            // --- Custom theme: yellow-pill search bar with muted-yellow internals.
            // See app/src/main/res/values/colors.xml for the palette.
            final int themeYellow = ContextCompat.getColor(this, R.color.theme_bright_yellow);
            final int themeMutedYellow = ContextCompat.getColor(this, R.color.theme_muted_yellow);
            mSearchView.setBackground(ContextCompat.getDrawable(this, R.drawable.main_search_bar_bg));
            // The parent Widget.AppTheme.SearchView style applies a
            // ?attr/colorSurfaceVariant backgroundTint, which would otherwise
            // tint our black-filled drawable to dark grey. Clear it so the
            // search bar interior renders pure black, matching the cards.
            mSearchView.setBackgroundTintList(null);
            // Magnifier icon inside the search bar
            ImageView magIcon = mSearchView.findViewById(androidx.appcompat.R.id.search_mag_icon);
            if (magIcon != null) {
                magIcon.setColorFilter(themeMutedYellow, PorterDuff.Mode.SRC_IN);
            }
            // Close (X) button
            ImageView closeBtn = mSearchView.findViewById(androidx.appcompat.R.id.search_close_btn);
            if (closeBtn != null) {
                closeBtn.setColorFilter(themeYellow, PorterDuff.Mode.SRC_IN);
            }
            // Hint text + typed text
            TextView searchText = mSearchView.findViewById(androidx.appcompat.R.id.search_src_text);
            if (searchText != null) {
                searchText.setHintTextColor(themeMutedYellow);
                searchText.setTextColor(themeYellow);
            }
            // The search-type selection button (filter-style icon at the left)
            mSearchView.setSearchTypeButtonTint(themeYellow);
            // --- end custom theme
            mSearchDebouncer = new SearchViewDebouncer(SearchViewDebouncer.DELAY_STANDARD);
            mSearchDebouncer.bindAdvanced(mSearchView, (query, type) -> {
                if (viewModel != null) viewModel.setSearchQuery(query, type);
            });
            // Check for market://search/?q=<query>
            Uri marketUri = getIntent().getData();
            if (marketUri != null && "market".equals(marketUri.getScheme()) && "search".equals(marketUri.getHost())) {
                String query = marketUri.getQueryParameter("q");
                if (query != null) {
                    mSearchView.setQuery(query, true);
                }
            }
        }

        mProgressIndicator = findViewById(R.id.progress_linear);
        mProgressIndicator.setVisibilityAfterHide(View.GONE);
        mRecyclerView = findViewById(R.id.item_list);
        mRecyclerView.requestFocus(); // Initially (the view isn't actually focusable)
        mSwipeRefresh = findViewById(R.id.swipe_refresh);
        mSwipeRefresh.setOnRefreshListener(this);

        mAdapter = new MainRecyclerAdapter(MainActivity.this);
        mAdapter.setHasStableIds(true);
        applyListLayout();
        mRecyclerView.setAdapter(mAdapter);
        // Fork: configurable separator lines between cells (widths in
        // SeparatorPrefs, colours in ColorPrefs).
        mSeparatorDecoration = new MainSeparatorDecoration(this);
        mRecyclerView.addItemDecoration(mSeparatorDecoration);
        // Refresh the per-row profile pills immediately after an app is added to
        // a profile via the "+" dialog (the dialog doesn't pause the activity,
        // so onResume wouldn't fire).
        getSupportFragmentManager().setFragmentResultListener(
                AddToProfileDialogFragment.RESULT_KEY, this, (key, bundle) -> {
                    if (mAdapter != null) mAdapter.reloadProfileMembership();
                });
        // Same refresh after a batch remove-from-profile.
        getSupportFragmentManager().setFragmentResultListener(
                RemoveFromProfileDialogFragment.RESULT_KEY, this, (key, bundle) -> {
                    if (mAdapter != null) mAdapter.reloadProfileMembership();
                });
        mMultiSelectionView = findViewById(R.id.selection_view);
        // The MultiSelectionView constructor hardcodes setCardElevation(8dp)
        // after any XML attributes are read, so app:cardElevation="0dp" in
        // activity_main.xml is overridden during inflation. Doing it here,
        // post-construction, sticks: M3 composites its tonal elevation
        // overlay with an alpha derived from elevation Z, so at Z=0 the
        // overlay contributes nothing and the outer card surface stays
        // pure black instead of olive (colorPrimary yellow over black).
        mMultiSelectionView.setCardElevation(0f);
        mMultiSelectionView.setOnItemSelectedListener(this);
        mMultiSelectionView.setOnSelectionModeChangeListener(this);
        mMultiSelectionView.setAdapter(mAdapter);
        mMultiSelectionView.updateCounter(true);
        mBatchOpsHandler = new MainBatchOpsHandler(mMultiSelectionView, viewModel);
        // Fork: also refresh the floating selection reminder on every selection
        // change. setDefaultList() fires this too, so a filter/search change that
        // hides selected apps updates the "· N hidden" count as well.
        mMultiSelectionView.setOnSelectionChangeListener(count -> {
            boolean refresh = mBatchOpsHandler.onSelectionChange(count);
            updateSelectionReminder();
            return refresh;
        });
        setupSelectionReminder();
        // Override the XML-inflated selection toolbar with the user's
        // customised order from MainToolbarPrefs, and wire each visible
        // toolbar button to open the same prefs screen on long-press.
        rebuildSelectionToolbarFromPrefs();

        if (SHOW_DISCLAIMER && AppPref.getBoolean(AppPref.PrefKey.PREF_SHOW_DISCLAIMER_BOOL)) {
            // Disclaimer will only be shown the first time it is loaded.
            SHOW_DISCLAIMER = false;
            View view = View.inflate(this, R.layout.dialog_disclaimer, null);
            new MaterialAlertDialogBuilder(this)
                    .setView(view)
                    .setCancelable(false)
                    .setPositiveButton(R.string.disclaimer_agree, (dialog, which) -> {
                        if (((MaterialCheckBox) view.findViewById(R.id.agree_forever)).isChecked()) {
                            AppPref.set(AppPref.PrefKey.PREF_SHOW_DISCLAIMER_BOOL, false);
                        }
                        displayChangelogIfRequired();
                    })
                    .setNegativeButton(R.string.disclaimer_exit, (dialog, which) -> finishAndRemoveTask())
                    .show();
        } else {
            displayChangelogIfRequired();
        }

        // Set observer
        viewModel.getApplicationItems().observe(this, applicationItems -> {
            if (mAdapter != null) {
                mAdapter.setDefaultList(applicationItems);
            }
            showProgressIndicator(false);
            // Keep the list-options icon's active state in sync with filters.
            invalidateOptionsMenu();
        });
        viewModel.getOperationStatus().observe(this, status -> {
            mProgressIndicator.hide();
            if (status) {
                UIUtils.displayShortToast(R.string.done);
            } else {
                UIUtils.displayLongToast(R.string.failed);
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_main_actions, menu);
        mAppUsageMenu = menu.findItem(R.id.action_app_usage);
        MenuItem apkUpdaterMenu = menu.findItem(R.id.action_apk_updater);
        try {
            if (!getPackageManager().getApplicationInfo(PACKAGE_NAME_APK_UPDATER, 0).enabled)
                throw new PackageManager.NameNotFoundException();
            apkUpdaterMenu.setVisible(true);
        } catch (PackageManager.NameNotFoundException e) {
            apkUpdaterMenu.setVisible(false);
        }
        MenuItem finderMenu = menu.findItem(R.id.action_finder);
        finderMenu.setVisible(true);
        // --- Custom theme: tint every action icon (including the overflow menu)
        // yellow, to match the main-screen palette. Also wrap each title in a
        // SpannableString with a yellow ForegroundColorSpan because the
        // app:popupTheme overlay's text-color attributes do not propagate to
        // M3 overflow menu item TextViews — only the popup background and
        // icon-tint attributes do.
        final int themeYellow = ContextCompat.getColor(this, R.color.theme_bright_yellow);
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            Drawable icon = item.getIcon();
            if (icon != null) {
                icon = icon.mutate();
                icon.setColorFilter(themeYellow, PorterDuff.Mode.SRC_IN);
                item.setIcon(icon);
            }
            CharSequence title = item.getTitle();
            if (title != null) {
                SpannableString span = new SpannableString(title);
                span.setSpan(new ForegroundColorSpan(themeYellow), 0, span.length(),
                        Spanned.SPAN_INCLUSIVE_INCLUSIVE);
                item.setTitle(span);
            }
        }
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null && toolbar.getOverflowIcon() != null) {
            Drawable overflow = toolbar.getOverflowIcon().mutate();
            overflow.setColorFilter(themeYellow, PorterDuff.Mode.SRC_IN);
            toolbar.setOverflowIcon(overflow);
        }
        // --- end custom theme
        // Fork: long-press the overflow (hamburger) button opens the
        // 白い熊 応用管理 UI settings page directly. Posted because the menu
        // views are laid out after this method returns.
        if (toolbar != null) {
            final androidx.appcompat.widget.Toolbar tb = toolbar;
            tb.post(() -> attachOverflowLongPress(tb));
        }
        return super.onCreateOptionsMenu(menu);
    }

    /**
     * Fork: wire a long-press on the toolbar's overflow (hamburger) button to
     * open the 白い熊 応用管理 UI settings page. The overflow button is the
     * only ImageView child of the toolbar's ActionMenuView (the visible
     * action items are ActionMenuItemViews, which extend TextView).
     */
    private void attachOverflowLongPress(@NonNull androidx.appcompat.widget.Toolbar toolbar) {
        for (int i = 0; i < toolbar.getChildCount(); ++i) {
            View child = toolbar.getChildAt(i);
            if (!(child instanceof androidx.appcompat.widget.ActionMenuView)) continue;
            androidx.appcompat.widget.ActionMenuView menuView = (androidx.appcompat.widget.ActionMenuView) child;
            for (int j = 0; j < menuView.getChildCount(); ++j) {
                View button = menuView.getChildAt(j);
                if (button instanceof ImageView) {
                    button.setOnLongClickListener(v -> {
                        startActivity(SettingsActivity.getSettingsIntent(this, "fonts_prefs"));
                        return true;
                    });
                }
            }
        }
    }

    @Override
    public boolean onPrepareOptionsMenu(@NonNull Menu menu) {
        super.onPrepareOptionsMenu(menu);
        mAppUsageMenu.setVisible(FeatureController.isUsageAccessEnabled());
        updateFilterIcon(menu);
        return true;
    }

    /**
     * Custom theme: the list-options icon doubles as the "filter" affordance,
     * so reflect whether any filter is active. Inactive = the normal yellow
     * icon; active = an orange tint plus a small dot badge in the top-right
     * corner (option B). Rebuilt from a fresh drawable each call so repeated
     * invalidateOptionsMenu() calls do not stack badges.
     */
    private void updateFilterIcon(@NonNull Menu menu) {
        MenuItem item = menu.findItem(R.id.action_list_options);
        if (item == null) return;
        Drawable base = ContextCompat.getDrawable(this, R.drawable.ic_filter);
        if (base == null) return;
        base = base.mutate();
        boolean active = viewModel != null && viewModel.isFilterActive();
        if (!active) {
            base.setColorFilter(ContextCompat.getColor(this, R.color.theme_bright_yellow),
                    PorterDuff.Mode.SRC_IN);
            item.setIcon(base);
            return;
        }
        int accent = ContextCompat.getColor(this, R.color.theme_bright_orange);
        base.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
        float d = getResources().getDisplayMetrics().density;
        int box = Math.round(24 * d);
        int dot = Math.round(9 * d);
        GradientDrawable badge = new GradientDrawable();
        badge.setShape(GradientDrawable.OVAL);
        badge.setColor(accent);
        badge.setSize(dot, dot);
        LayerDrawable layer = new LayerDrawable(new Drawable[]{base, badge});
        // setLayerInset is API 1-safe (setLayerGravity would need API 23).
        layer.setLayerInset(0, 0, 0, 0, 0);
        layer.setLayerInset(1, box - dot, 0, 0, box - dot);
        item.setIcon(layer);
    }

    @SuppressLint("InflateParams")
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_instructions) {
            Intent helpIntent = new Intent(this, HelpActivity.class);
            helpIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(helpIntent);
        } else if (id == R.id.action_list_options) {
            MainListOptions listOptions = new MainListOptions();
            listOptions.setListOptionActions(viewModel);
            listOptions.show(getSupportFragmentManager(), MainListOptions.TAG);
        } else if (id == R.id.action_export_displayed_ids) {
            copyDisplayedAppIds();
        } else if (id == R.id.action_layout_columns) {
            showLayoutPicker();
        } else if (id == R.id.action_clear_filters) {
            if (viewModel != null) {
                viewModel.clearAllFilters();
                // Sync the search box UI to the now-cleared query.
                if (mSearchView != null && !mSearchView.isIconified()) {
                    mSearchView.setQuery("", false);
                }
                invalidateOptionsMenu();
                UIUtils.displayShortToast(R.string.filters_cleared);
            }
        } else if (id == R.id.action_refresh) {
            if (viewModel != null) {
                showProgressIndicator(true);
                viewModel.loadApplicationItems();
            }
        } else if (id == R.id.action_settings) {
            Intent settingsIntent = SettingsActivity.getSettingsIntent(this);
            startActivity(settingsIntent);
        } else if (id == R.id.action_app_usage) {
            Intent usageIntent = new Intent(this, AppUsageActivity.class);
            startActivity(usageIntent);
        } else if (id == R.id.action_one_click_ops) {
            Intent onClickOpsIntent = new Intent(this, OneClickOpsActivity.class);
            startActivity(onClickOpsIntent);
        } else if (id == R.id.action_finder) {
            Intent intent = new Intent(this, FinderActivity.class);
            startActivity(intent);
        } else if (id == R.id.action_apk_updater) {
            try {
                if (!getPackageManager().getApplicationInfo(PACKAGE_NAME_APK_UPDATER, 0).enabled)
                    throw new PackageManager.NameNotFoundException();
                Intent intent = new Intent();
                intent.setClassName(PACKAGE_NAME_APK_UPDATER, ACTIVITY_NAME_APK_UPDATER);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception ignored) {
            }
        } else if (id == R.id.action_running_apps) {
            Intent runningAppsIntent = new Intent(this, RunningAppsActivity.class);
            startActivity(runningAppsIntent);
        } else if (id == R.id.action_profiles) {
            Intent profilesIntent = new Intent(this, ProfilesActivity.class);
            startActivity(profilesIntent);
        } else if (id == R.id.action_labs) {
            Intent intent = new Intent(getApplicationContext(), LabsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } else if (id == R.id.action_debloater) {
            Intent intent = new Intent(getApplicationContext(), DebloaterActivity.class);
            startActivity(intent);
        } else return super.onOptionsItemSelected(item);
        return true;
    }

    /**
     * Fork: if any per-element font, colour, separator or selection-frame
     * setting was changed in the UI settings screen, re-bind the list and/or
     * refresh the separator decoration so the change renders. Flag-guarded,
     * so calls on unchanged state are free. Called from onResume AND from
     * onTopResumedActivityChanged — on the tri-fold the settings screen and
     * the main list can be resumed side by side (multi-window), where
     * switching windows never goes through onResume.
     */
    private void refreshForkAppearanceIfChanged() {
        boolean fontsChanged = FontPrefs.consumeChanged();
        boolean colorsChanged = ColorPrefs.consumeChanged();
        boolean separatorsChanged = SeparatorPrefs.consumeChanged();
        boolean framesChanged = SelectionFramePrefs.consumeChanged();
        if (mAdapter != null && (fontsChanged || colorsChanged || framesChanged)) {
            if (fontsChanged) FontUtil.clearCache();
            if (colorsChanged) mAdapter.reloadColors();
            mAdapter.notifyDataSetChanged();
        }
        // The separator grid reads both ColorPrefs (colours) and
        // SeparatorPrefs (widths) — refresh it when either changed.
        if ((colorsChanged || separatorsChanged) && mSeparatorDecoration != null && mRecyclerView != null) {
            mSeparatorDecoration.reload(this);
            mRecyclerView.invalidateItemDecorations();
        }
    }

    @Override
    public void onTopResumedActivityChanged(boolean isTopResumedActivity) {
        super.onTopResumedActivityChanged(isTopResumedActivity);
        // Fork: in multi-window, regaining top-resumed is the only signal that
        // the user came back from a side-by-side settings window.
        if (isTopResumedActivity) {
            refreshForkAppearanceIfChanged();
        }
    }

    /**
     * Fork: apply the persisted main-list layout. Adaptive (the original
     * auto-fit grid, one column per 450dp) when no fixed column count is set,
     * otherwise a fixed 2/3/4-column grid.
     */
    private void applyListLayout() {
        int columns = MainLayoutPrefs.getColumns(this);
        if (columns <= MainLayoutPrefs.COLUMNS_ADAPTIVE) {
            mRecyclerView.setLayoutManager(UIUtils.getGridLayoutAt450Dp(this));
        } else {
            mRecyclerView.setLayoutManager(new GridLayoutManager(this, columns));
        }
    }

    /**
     * Fork: single-choice picker behind the 3x3 grid toolbar icon. Selecting
     * an entry persists it and swaps the layout manager immediately.
     */
    private void showLayoutPicker() {
        String[] choices = new String[]{
                getString(R.string.layout_adaptive),
                getString(R.string.layout_2_columns),
                getString(R.string.layout_3_columns),
                getString(R.string.layout_4_columns)};
        int columns = MainLayoutPrefs.getColumns(this);
        int checked = (columns >= 2 && columns <= 4) ? columns - 1 : 0;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.list_layout)
                .setSingleChoiceItems(choices, checked, (dialog, which) -> {
                    int newColumns = which == 0 ? MainLayoutPrefs.COLUMNS_ADAPTIVE : which + 1;
                    MainLayoutPrefs.setColumns(this, newColumns);
                    applyListLayout();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * Copy the package names of every app currently shown in the list (after
     * search + all active filters), one per line, to the clipboard, and flash
     * a count toast. Writing the primary clip from a foreground activity needs
     * no IME; only background clipboard reads are restricted on Android 10+.
     */
    private void copyDisplayedAppIds() {
        if (mAdapter == null) return;
        List<String> ids = mAdapter.getDisplayedPackageNames();
        if (ids.isEmpty()) {
            UIUtils.displayShortToast(R.string.no_apps_displayed_to_copy);
            return;
        }
        ClipboardUtils.copyToClipboard(this, "App IDs", TextUtils.join("\n", ids));
        UIUtils.displayShortToast(getResources().getQuantityString(
                R.plurals.copied_n_app_ids, ids.size(), ids.size()));
    }

    @Override
    public void onSelectionModeEnabled() {
        mOnBackPressedCallback.setEnabled(true);
        updateSelectionReminder();
    }

    @Override
    public void onSelectionModeDisabled() {
        mOnBackPressedCallback.setEnabled(false);
        // Selection cleared/exited — drop the reminder and close its sheet.
        updateSelectionReminder();
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_backup) {
            if (viewModel != null) {
                BackupRestoreDialogFragment fragment = BackupRestoreDialogFragment.getInstance(viewModel.getSelectedPackagesWithUsers());
                fragment.setOnActionBeginListener(mode -> showProgressIndicator(true));
                fragment.setOnActionCompleteListener((mode, failedPackages) -> showProgressIndicator(false));
                fragment.show(getSupportFragmentManager(), BackupRestoreDialogFragment.TAG);
                clearSelection();
            }
        } else if (id == R.id.action_save_apk) {
            mStoragePermission.request(granted -> {
                if (granted) handleBatchOp(BatchOpsManager.OP_BACKUP_APK);
            });
        } else if (id == R.id.action_block_unblock_trackers) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.block_unblock_trackers)
                    .setMessage(R.string.choose_what_to_do)
                    .setPositiveButton(R.string.block, (dialog, which) ->
                            handleBatchOp(BatchOpsManager.OP_BLOCK_TRACKERS))
                    .setNegativeButton(R.string.cancel, null)
                    .setNeutralButton(R.string.unblock, (dialog, which) ->
                            handleBatchOp(BatchOpsManager.OP_UNBLOCK_TRACKERS))
                    .show();
        } else if (id == R.id.action_clear_data_cache) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.clear)
                    .setMessage(R.string.choose_what_to_do)
                    .setPositiveButton(R.string.clear_cache, (dialog, which) ->
                            handleBatchOp(BatchOpsManager.OP_CLEAR_CACHE))
                    .setNegativeButton(R.string.cancel, null)
                    .setNeutralButton(R.string.clear_data, (dialog, which) ->
                            handleBatchOp(BatchOpsManager.OP_CLEAR_DATA))
                    .show();
        } else if (id == R.id.action_freeze_unfreeze) {
            warnIfSelectionHasProtectedApps();
            int freezeType = Prefs.Blocking.getDefaultFreezingMethod();
            if (Prefs.Blocking.getSkipFreezeMethodDialog()) {
                // Fork: "Skip freeze method dialog" — freeze the selected apps
                // immediately with the default freeze method, no picker (matches
                // the single-app behaviour in AppInfoFragment). The per-app
                // "prefer remembered method" option follows the dialog default
                // (off). Batch unfreeze still goes through the dialog, so it
                // remains available by turning the toggle off.
                BatchFreezeOptions options = new BatchFreezeOptions(freezeType, false);
                confirmBatchOp(R.plurals.confirm_freeze_count, R.string.freeze, false,
                        () -> handleBatchOp(BatchOpsManager.OP_ADVANCED_FREEZE, options));
            } else {
                showFreezeUnfreezeDialog(freezeType);
            }
        } else if (id == R.id.action_disable_background) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.are_you_sure)
                    .setMessage(R.string.disable_background_run_description)
                    .setPositiveButton(R.string.yes, (dialog, which) ->
                            handleBatchOp(BatchOpsManager.OP_DISABLE_BACKGROUND))
                    .setNegativeButton(R.string.no, null)
                    .show();
        } else if (id == R.id.action_net_policy) {
            ArrayMap<Integer, String> netPolicyMap = NetworkPolicyManagerCompat.getAllReadablePolicies(this);
            Integer[] polices = new Integer[netPolicyMap.size()];
            String[] policyStrings = new String[netPolicyMap.size()];
            Collection<ApplicationItem> applicationItems = viewModel.getSelectedPackages().values();
            Iterator<ApplicationItem> it = applicationItems.iterator();
            int selectedPolicies = applicationItems.size() == 1 && it.hasNext() ?
                    NetworkPolicyManagerCompat.getUidPolicy(it.next().uid) : 0;
            for (int i = 0; i < netPolicyMap.size(); ++i) {
                polices[i] = netPolicyMap.keyAt(i);
                policyStrings[i] = netPolicyMap.valueAt(i);
            }
            new SearchableFlagsDialogBuilder<>(this, polices, policyStrings, selectedPolicies)
                    .setTitle(R.string.net_policy)
                    .showSelectAll(false)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.apply, (dialog, which, selections) -> {
                        int flags = 0;
                        for (int flag : selections) {
                            flags |= flag;
                        }
                        BatchNetPolicyOptions options = new BatchNetPolicyOptions(flags);
                        handleBatchOp(BatchOpsManager.OP_NET_POLICY, options);
                    })
                    .show();
        } else if (id == R.id.action_optimize) {
            DexOptDialog dialog = DexOptDialog.getInstance(viewModel.getSelectedPackages().keySet().toArray(new String[0]));
            dialog.show(getSupportFragmentManager(), DexOptDialog.TAG);
            clearSelection();
        } else if (id == R.id.action_export_blocking_rules) {
            final String fileName = "app_manager_rules_export-"
                    + DateUtils.formatDateTimeForFilename(this, System.currentTimeMillis()) + ".am.tsv";
            mBatchExportRules.launch(fileName);
        } else if (id == R.id.action_export_app_list) {
            List<Integer> exportTypes = Arrays.asList(ListExporter.EXPORT_TYPE_CSV,
                    ListExporter.EXPORT_TYPE_JSON,
                    ListExporter.EXPORT_TYPE_XML,
                    ListExporter.EXPORT_TYPE_MARKDOWN);
            new SearchableSingleChoiceDialogBuilder<>(this, exportTypes, R.array.export_app_list_options)
                    .setTitle(R.string.export_app_list_select_format)
                    .setOnSingleChoiceClickListener((dialog, which, item1, isChecked) -> {
                        if (!isChecked) {
                            return;
                        }
                        String filename = "app_manager_app_list-"
                                + DateUtils.formatLongDateTimeForFilename(this, System.currentTimeMillis()) + ".am";
                        switch (item1) {
                            case ListExporter.EXPORT_TYPE_CSV:
                                mExportAppListCsv.launch(filename + ".csv");
                                break;
                            case ListExporter.EXPORT_TYPE_JSON:
                                mExportAppListJson.launch(filename + ".json");
                                break;
                            case ListExporter.EXPORT_TYPE_XML:
                                mExportAppListXml.launch(filename + ".xml");
                                break;
                            case ListExporter.EXPORT_TYPE_MARKDOWN:
                                mExportAppListMarkdown.launch(filename + ".md");
                                break;
                        }
                    })
                    .setNegativeButton(R.string.close, null)
                    .show();
        } else if (id == R.id.action_force_stop) {
            handleBatchOp(BatchOpsManager.OP_FORCE_STOP);
        } else if (id == R.id.action_uninstall) {
            warnIfSelectionHasProtectedApps();
            confirmBatchOp(R.plurals.confirm_uninstall_count, R.string.uninstall, true,
                    () -> handleBatchOp(BatchOpsManager.OP_UNINSTALL));
        } else if (id == R.id.action_install_existing) {
            // Fork: reinstall (install-existing) the selection — restores
            // uninstalled system apps. Reviewed through the themed confirm
            // dialog like the other batch ops (non-destructive, so no undo
            // warning).
            confirmBatchOp(R.plurals.confirm_reinstall_count, R.string.reinstall, false,
                    () -> handleBatchOp(BatchOpsManager.OP_INSTALL_EXISTING));
        } else if (id == R.id.action_unfreeze) {
            // Fork: dedicated batch unfreeze (the freeze_unfreeze entry opens
            // the combined dialog; this one unfreezes the selection directly).
            confirmBatchOp(R.plurals.confirm_unfreeze_count, R.string.unfreeze, false,
                    () -> handleBatchOp(BatchOpsManager.OP_UNFREEZE));
        } else if (id == R.id.action_add_to_profile) {
            AddToProfileDialogFragment dialog = AddToProfileDialogFragment.getInstance(viewModel.getSelectedPackages()
                    .keySet().toArray(new String[0]));
            dialog.show(getSupportFragmentManager(), AddToProfileDialogFragment.TAG);
            clearSelection();
        } else if (id == R.id.action_remove_from_profile) {
            // Fork: batch counterpart to add-to-profile — removes the selection
            // from one or more chosen profiles.
            RemoveFromProfileDialogFragment dialog = RemoveFromProfileDialogFragment.getInstance(viewModel.getSelectedPackages()
                    .keySet().toArray(new String[0]));
            dialog.show(getSupportFragmentManager(), RemoveFromProfileDialogFragment.TAG);
            clearSelection();
        } else {
            return false;
        }
        return true;
    }

    @Override
    public void onRefresh() {
        showProgressIndicator(true);
        if (viewModel != null) viewModel.loadApplicationItems();
        // Profile membership (the per-row profile pills) is cached in the
        // adapter; rebuild it on refresh so newly-added profile memberships show.
        if (mAdapter != null) mAdapter.reloadProfileMembership();
        mSwipeRefresh.setRefreshing(false);
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Set filter
        if (viewModel != null && mSearchView != null && !TextUtils.isEmpty(viewModel.getSearchQuery())) {
            if (mSearchView.isIconified()) {
                mSearchView.setIconified(false);
            }
            mSearchView.setQuery(viewModel.getSearchQuery(), false);
        }
        // Show/hide app usage menu
        if (mAppUsageMenu != null) {
            mAppUsageMenu.setVisible(FeatureController.isUsageAccessEnabled());
        }
        // Fork: the startup backup-volume availability check was removed. It ran
        // on the main thread in onStart() and, with a custom backup directory
        // set, the path lookup could block before the file-system op-mode was
        // ready and hang the app on "Initializing...". The warning is not needed
        // at startup; missing-volume situations surface at backup/restore time.
    }

    private boolean mImsVisibleBeforeLeave = false;

    @Override
    protected void onResume() {
        super.onResume();
        if (viewModel != null) viewModel.onResume();
        // Profile membership may have changed elsewhere (e.g. the profiles
        // editor); rebuild the cached per-row pill data.
        if (mAdapter != null) mAdapter.reloadProfileMembership();
        if (mAdapter != null && mBatchOpsHandler != null && mAdapter.isInSelectionMode()) {
            mBatchOpsHandler.updateConstraints();
            mMultiSelectionView.updateCounter(false);
        }
        if (mSearchView != null) {
            mSearchView.post(() -> {
                if (mImsVisibleBeforeLeave) {
                    mSearchView.requestFocus();
                    UiUtils.showKeyboard(mSearchView);
                }
            });
        }
        // Re-apply selection-toolbar prefs in case they were changed in
        // the settings screen while we were paused. Idempotent on no
        // change (the clear+rebuild path always runs but produces an
        // identical menu, which the widget renders without flicker).
        rebuildSelectionToolbarFromPrefs();
        refreshForkAppearanceIfChanged();
        // Fork: also listen for batch-op START so the in-app progress dialog can
        // open; COMPLETED dismisses it.
        IntentFilter batchOpsFilter = new IntentFilter();
        batchOpsFilter.addAction(BatchOpsService.ACTION_BATCH_OPS_STARTED);
        batchOpsFilter.addAction(BatchOpsService.ACTION_BATCH_OPS_COMPLETED);
        ContextCompat.registerReceiver(this, mBatchOpsBroadCastReceiver,
                batchOpsFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
        // Fork: mark the main window foreground so the service suppresses the
        // system completion heads-up (the themed toast covers it here).
        BatchOpsProgressMonitor.getInstance().setHostForeground(true);
        // Re-attach the progress dialog if an op is still running (e.g. the user
        // left and came back mid-operation).
        if (BatchOpsProgressMonitor.getInstance().isActive()) {
            showBatchProgressDialog();
        }
    }

    /**
     * Replace the XML-inflated selection-action menu with one rebuilt
     * from {@link MainToolbarPrefs}. Items are emitted in user-chosen
     * order: visible first, then hidden, so the widget shows the
     * visible block in the bar and dumps the hidden block into the
     * auto-overflow ("More…") menu at the right edge.
     *
     * After the rebuild, posts a runnable to attach a long-press
     * listener on each child action button so a long-press on any of
     * them opens the customisation screen. The post is needed because
     * the widget's presenter rebuilds child Views asynchronously when
     * the menu changes; long-press attached now would land on the
     * pre-rebuild Views.
     */
    private void rebuildSelectionToolbarFromPrefs() {
        if (mMultiSelectionView == null) return;
        android.view.Menu menu = mMultiSelectionView.getMenu();
        menu.clear();
        java.util.List<String> visible = MainToolbarPrefs.loadVisibleOrder(this);
        java.util.List<String> hidden = MainToolbarPrefs.loadHiddenOrder(this);
        int order = 0;
        for (String key : visible) addToolbarItem(menu, key, order++);
        for (String key : hidden) addToolbarItem(menu, key, order++);
        // Rebind any constraint state (enabled/disabled per current
        // selection) and rewire long-press once the widget has had a
        // chance to recreate child views from the new menu.
        if (mBatchOpsHandler != null && mAdapter != null && mAdapter.isInSelectionMode()) {
            mBatchOpsHandler.updateConstraints();
        }
        mMultiSelectionView.post(this::attachToolbarLongPress);
    }

    private void addToolbarItem(@NonNull android.view.Menu menu,
                                @NonNull String key, int order) {
        int id = MainToolbarPrefs.idForKey(key);
        int titleRes = MainToolbarPrefs.titleForKey(key);
        int iconRes = MainToolbarPrefs.iconForKey(key);
        if (id == 0 || titleRes == 0) return;
        android.view.MenuItem item = menu.add(android.view.Menu.NONE, id, order,
                titleRes);
        if (iconRes != 0) item.setIcon(iconRes);
    }

    /**
     * Find the inner LinearLayoutCompat that hosts the toolbar buttons
     * and put the same long-press listener on every child. Long-press
     * on any button opens the customisation screen. The listener is
     * re-attached after every {@link #rebuildSelectionToolbarFromPrefs}
     * call because the widget recreates child views on menu change.
     */
    private void attachToolbarLongPress() {
        if (mMultiSelectionView == null) return;
        android.view.View actionsView = mMultiSelectionView
                .findViewById(io.github.muntashirakon.ui.R.id.selection_actions);
        if (!(actionsView instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup vg = (android.view.ViewGroup) actionsView;
        android.view.View.OnLongClickListener openEditor = v -> {
            Intent intent = SettingsActivity.getSettingsIntent(this, "main_toolbar_prefs");
            startActivity(intent);
            return true;
        };
        for (int i = 0; i < vg.getChildCount(); i++) {
            vg.getChildAt(i).setOnLongClickListener(openEditor);
        }
    }

    @Override
    protected void onPause() {
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(getWindow().getDecorView());
        mImsVisibleBeforeLeave = insets != null && insets.isVisible(WindowInsetsCompat.Type.ime());
        View focusedView = getCurrentFocus();
        if (focusedView instanceof SearchView.SearchAutoComplete) {
            focusedView.clearFocus();
        }
        super.onPause();
        unregisterReceiver(mBatchOpsBroadCastReceiver);
        // Fork: no longer foreground — let the service post the system completion
        // notification again (no themed toast while backgrounded).
        BatchOpsProgressMonitor.getInstance().setHostForeground(false);
        // Fork: drop the progress dialog while backgrounded; it is re-shown on
        // resume if the operation is still running. The op itself keeps going in
        // the foreground service.
        if (mBatchProgressDialog != null) {
            mBatchProgressDialog.dismiss();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mSearchDebouncer != null) {
            mSearchDebouncer.unbind();
        }
    }

    private void displayChangelogIfRequired() {
        // Disabled in this fork. Each rebuild bumped customBuildNumber and
        // re-tripped the upstream first-run snackbar, which is noise in
        // rapid-iteration cycles. The changelog dialog itself is still
        // available from the About preferences screen if wanted.
    }

    private void showFreezeUnfreezeDialog(int freezeType) {
        View view = View.inflate(this, R.layout.item_checkbox, null);
        MaterialCheckBox checkBox = view.findViewById(R.id.checkbox);
        checkBox.setText(R.string.freeze_prefer_per_app_option);
        FreezeUnfreeze.getFreezeDialog(this, freezeType)
                .setIcon(R.drawable.ic_snowflake)
                .setTitle(R.string.freeze_unfreeze)
                .setView(view)
                .setPositiveButton(R.string.freeze, (dialog, which, selectedItem) -> {
                    if (selectedItem == null) {
                        return;
                    }
                    BatchFreezeOptions options = new BatchFreezeOptions(selectedItem, checkBox.isChecked());
                    confirmBatchOp(R.plurals.confirm_freeze_count, R.string.freeze, false,
                            () -> handleBatchOp(BatchOpsManager.OP_ADVANCED_FREEZE, options));
                })
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.unfreeze, (dialog, which, selectedItem) ->
                        confirmBatchOp(R.plurals.confirm_unfreeze_count, R.string.unfreeze, false,
                                () -> handleBatchOp(BatchOpsManager.OP_UNFREEZE)))
                .show();
    }

    // Fork: when a batch freeze/uninstall includes apps in the protected
    // "必要" profile, warn concretely, naming each protected app. Those apps
    // are refused at the freeze/uninstall chokepoints, so the rest of the
    // batch still proceeds. Computed off the main thread because it reads the
    // profiles from disk.
    private void warnIfSelectionHasProtectedApps() {
        Map<String, ApplicationItem> selected = new LinkedHashMap<>(viewModel.getSelectedPackages());
        if (selected.isEmpty()) {
            return;
        }
        ThreadUtils.postOnBackgroundThread(() -> {
            Set<String> protectedPackages = ProtectedAppsProfile.getProtectedPackages();
            if (protectedPackages.isEmpty()) {
                return;
            }
            List<String> labels = new ArrayList<>();
            for (Map.Entry<String, ApplicationItem> entry : selected.entrySet()) {
                if (protectedPackages.contains(entry.getKey())) {
                    ApplicationItem item = entry.getValue();
                    labels.add(item != null && item.label != null ? item.label : entry.getKey());
                }
            }
            if (labels.isEmpty()) {
                return;
            }
            ThreadUtils.postOnMainThread(() -> {
                if (labels.size() == 1) {
                    UIUtils.displayLongToast(R.string.protected_profile_block, labels.get(0));
                } else {
                    UIUtils.displayLongToast(R.string.protected_profile_block_multiple, TextUtils.join("、", labels));
                }
            });
        });
    }

    private void handleBatchOp(@BatchOpsManager.OpType int op) {
        handleBatchOp(op, null);
    }

    private void handleBatchOp(@BatchOpsManager.OpType int op, @Nullable IBatchOpOptions options) {
        if (viewModel == null) return;
        showProgressIndicator(true);
        BatchOpsManager.Result input = new BatchOpsManager.Result(viewModel.getSelectedPackagesWithUsers());
        BatchQueueItem item = BatchQueueItem.getBatchOpQueue(op, input.getFailedPackages(), input.getAssociatedUsers(), options);
        Intent intent = BatchOpsService.getServiceIntent(this, item);
        ContextCompat.startForegroundService(this, intent);
        // Fork: the packages are now captured in the queue item, so drop the
        // selection immediately. Critical: a selection left alive after an op
        // silently merges into the next one — e.g. apps frozen here (then hidden
        // by a filter) would still be selected and get uninstalled by a later
        // action the user thought was a fresh selection.
        clearSelection();
    }

    // Fork: large, themed confirmation before a batch operation (uninstall,
    // reinstall, freeze, unfreeze). Replaces the old generic "Are you sure?"
    // prompt: it states how many apps will be affected and lists every one
    // (label in bold, package id in italic, column-aligned) in a scrollable area
    // covering most of the window, so the full set can be reviewed before
    // confirming. Built from the live selection (still alive here — onConfirm
    // runs handleBatchOp, which captures the selection and clears it). The undo
    // warning subtitle is shown only for destructive ops (uninstall).
    private void confirmBatchOp(@PluralsRes int titlePluralRes, @StringRes int okLabelRes,
                                boolean destructive, @NonNull Runnable onConfirm) {
        if (viewModel == null) return;
        Collection<ApplicationItem> items = viewModel.getSelectedApplicationItems();
        if (items.isEmpty()) return;
        List<String[]> apps = new ArrayList<>(items.size());
        for (ApplicationItem item : items) {
            String label = item.label != null ? item.label : item.packageName;
            apps.add(new String[]{label, item.packageName});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        CharSequence title = getResources().getQuantityString(titlePluralRes, apps.size(), apps.size());
        CharSequence subtitle = destructive ? getText(R.string.this_action_cannot_be_undone) : null;
        new BatchConfirmDialog(this, title, subtitle, okLabelRes, apps, onConfirm::run).show();
    }

    // Fork: clear the multi-selection and exit selection mode once an action has
    // captured the packages it needs. Selections must never survive an action —
    // a stale selection merging into the next one is destructive (see the note
    // in handleBatchOp). Routed through MultiSelectionView.cancel() so the model
    // (MainViewModel.mSelectedPackageApplicationItemMap), the per-row isSelected
    // flags, and the selection toolbar are all cleared together. No-op when not
    // in selection mode.
    private void clearSelection() {
        if (mMultiSelectionView != null && mAdapter != null && mAdapter.isInSelectionMode()) {
            mMultiSelectionView.cancel();
        }
    }

    // Fork: one-time setup of the floating selection reminder pill — theme it from
    // the fork palette and wire its two tap targets (body → list sheet, ✕ → clear
    // all). Its bottom margin tracks the action toolbar's height (below).
    private void setupSelectionReminder() {
        mSelectionReminder = findViewById(R.id.selection_reminder);
        mSelectionReminderText = findViewById(R.id.selection_reminder_text);
        if (mSelectionReminder == null) {
            return;
        }
        View body = mSelectionReminder.findViewById(R.id.selection_reminder_body);
        ImageView icon = mSelectionReminder.findViewById(R.id.selection_reminder_icon);
        ImageView clear = mSelectionReminder.findViewById(R.id.selection_reminder_clear);
        int textColor = ForkThemeUtils.getTextColor();
        ForkThemeUtils.applyThemedBackground(mSelectionReminder, 18f);
        mSelectionReminderText.setTextColor(textColor);
        icon.setImageTintList(ColorStateList.valueOf(textColor));
        clear.setImageTintList(ColorStateList.valueOf(textColor));
        body.setOnClickListener(v -> showSelectionList());
        clear.setOnClickListener(v -> clearSelection());
        // Keep the pill sitting just above the action toolbar as the toolbar's
        // height changes (it can minimise/expand or grow with the nav-bar inset).
        if (mMultiSelectionView != null) {
            mMultiSelectionView.addOnLayoutChangeListener(
                    (v, l, t, r, b, ol, ot, or, ob) -> positionSelectionReminder());
        }
    }

    // Fork: recompute and repaint the selection reminder. Shown whenever the
    // selection is non-empty; appends "· N hidden" when some selected apps aren't
    // in the current filtered/searched view (the actual danger signal).
    private void updateSelectionReminder() {
        if (viewModel == null || mSelectionReminder == null) {
            return;
        }
        List<String> selected = viewModel.getSelectedPackageNames();
        int count = selected.size();
        if (count <= 0) {
            if (mSelectionReminder.getVisibility() != View.GONE) {
                mSelectionReminder.setVisibility(View.GONE);
            }
            if (mSelectionSheet != null) {
                mSelectionSheet.dismiss();
            }
            return;
        }
        int hidden = 0;
        if (mAdapter != null) {
            Set<String> displayed = new HashSet<>(mAdapter.getDisplayedPackageNames());
            for (String pkg : selected) {
                if (!displayed.contains(pkg)) hidden++;
            }
        }
        String text = getString(R.string.selection_reminder_count, count);
        if (hidden > 0) {
            text += getString(R.string.selection_reminder_hidden_suffix, hidden);
        }
        mSelectionReminderText.setText(text);
        positionSelectionReminder();
        if (mSelectionReminder.getVisibility() != View.VISIBLE) {
            mSelectionReminder.setVisibility(View.VISIBLE);
        }
    }

    // Fork: keep the pill's bottom margin equal to the action toolbar's height
    // (plus a small gap), so it floats just above it regardless of toolbar height.
    private void positionSelectionReminder() {
        if (mSelectionReminder == null || mMultiSelectionView == null) {
            return;
        }
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) mSelectionReminder.getLayoutParams();
        int gap = (int) ForkThemeUtils.dpToPx(this, 8f);
        int toolbarHeight = mMultiSelectionView.getVisibility() == View.VISIBLE
                ? mMultiSelectionView.getHeight() : 0;
        int desired = toolbarHeight + gap;
        if (lp.bottomMargin != desired) {
            lp.bottomMargin = desired;
            mSelectionReminder.setLayoutParams(lp);
        }
    }

    // Fork: open the "Selected apps" sheet from the reminder pill.
    private void showSelectionList() {
        if (viewModel == null) {
            return;
        }
        if (mSelectionSheet == null) {
            mSelectionSheet = new SelectionListBottomSheet(this, new SelectionListBottomSheet.Host() {
                @NonNull
                @Override
                public List<ApplicationItem> getSelectedItems() {
                    return viewModel.getSelectedApplicationItemsSnapshot();
                }

                @NonNull
                @Override
                public Set<String> getDisplayedPackages() {
                    return mAdapter != null ? new HashSet<>(mAdapter.getDisplayedPackageNames())
                            : new HashSet<>();
                }

                @Override
                public void deselect(@NonNull ApplicationItem item) {
                    viewModel.deselect(item);
                }

                @Override
                public void clearAll() {
                    clearSelection();
                }

                @Override
                public void onSelectionChanged() {
                    onSelectionMutatedExternally();
                }
            });
        }
        mSelectionSheet.show();
    }

    // Fork: after the selection is changed from the sheet (per-app deselect or
    // clear-all), repaint the visible row highlights, the action-toolbar counter
    // (which also exits selection mode when the count hits zero), and the pill.
    private void onSelectionMutatedExternally() {
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
        if (mMultiSelectionView != null) {
            mMultiSelectionView.updateCounter(true);
        }
        updateSelectionReminder();
    }

    // Fork: open the in-app batch-progress dialog if the user has it enabled.
    // Safe to call repeatedly — the dialog ignores a show() while already shown.
    private void showBatchProgressDialog() {
        if (!Prefs.Appearance.showBatchProgressDialog()) {
            return;
        }
        if (mBatchProgressDialog == null) {
            mBatchProgressDialog = new BatchProgressDialog(this);
        }
        mBatchProgressDialog.show();
    }

    void showProgressIndicator(boolean show) {
        if (show) mProgressIndicator.show();
        else mProgressIndicator.hide();
    }
}
