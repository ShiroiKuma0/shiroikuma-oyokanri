// SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.Color;
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
import androidx.annotation.DrawableRes;
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
import androidx.appcompat.widget.AppCompatTextView;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;
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
import io.github.muntashirakon.AppManager.fonts.MainIconPrefs;
import io.github.muntashirakon.AppManager.fonts.RunningBoxPrefs;
import io.github.muntashirakon.AppManager.fonts.SelectionFramePrefs;
import io.github.muntashirakon.AppManager.fonts.SeparatorPrefs;
import io.github.muntashirakon.AppManager.backup.dialog.BackupRestoreDialogFragment;
import io.github.muntashirakon.AppManager.batchops.BatchOpsManager;
import io.github.muntashirakon.AppManager.batchops.BatchOpsProgressActivity;
import androidx.core.graphics.ColorUtils;
import io.github.muntashirakon.AppManager.appdata.self.PendingStateImport;
import io.github.muntashirakon.AppManager.batchops.BatchOpsProgressMonitor;
import io.github.muntashirakon.AppManager.batchops.OpLog;
import io.github.muntashirakon.AppManager.batchops.BatchOpsService;
import io.github.muntashirakon.AppManager.batchops.BatchQueueItem;
import io.github.muntashirakon.AppManager.batchops.struct.BatchFreezeOptions;
import io.github.muntashirakon.AppManager.batchops.struct.BatchNetPolicyOptions;
import io.github.muntashirakon.AppManager.backup.dialog.BatchBackupTableDialog;
import io.github.muntashirakon.AppManager.batchops.struct.BatchBackupOptions;
import io.github.muntashirakon.AppManager.batchops.struct.IBatchOpOptions;
import io.github.muntashirakon.AppManager.types.UserPackagePair;
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
import io.github.muntashirakon.AppManager.battery.BatteryUsageActivity;
import io.github.muntashirakon.AppManager.processreaper.ProcessMonitorActivity;
import io.github.muntashirakon.AppManager.self.life.FundingCampaignChecker;
import io.github.muntashirakon.AppManager.settings.FeatureController;
import io.github.muntashirakon.AppManager.screens.ListScreenActivity;
import io.github.muntashirakon.dialog.TextInputDialogBuilder;
import io.github.muntashirakon.AppManager.settings.Prefs;
import io.github.muntashirakon.AppManager.settings.PrivilegeWatchdog;
import io.github.muntashirakon.AppManager.settings.SettingsActivity;
import io.github.muntashirakon.AppManager.usage.AppUsageActivity;
import io.github.muntashirakon.AppManager.users.Users;
import io.github.muntashirakon.AppManager.utils.AppPref;
import io.github.muntashirakon.AppManager.utils.DateUtils;
import io.github.muntashirakon.AppManager.utils.ForkDialog;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;
import io.github.muntashirakon.AppManager.utils.LayoutGeometry;
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
    // Fork: the geometry the current layout manager was built for (see
    // LayoutGeometry) — column counts are stored per orientation × fold state.
    private String mLayoutGeometry;
    private MultiSelectionView mMultiSelectionView;
    MainBatchOpsHandler mBatchOpsHandler;
    private MenuItem mAppUsageMenu;
    // Fork: floating selection reminder (count + hidden) above the action toolbar,
    // and the "Selected apps" sheet it opens.
    private View mSelectionReminder;
    private TextView mSelectionReminderText;
    @Nullable
    private SelectionListBottomSheet mSelectionSheet;
    // Fork: the privilege alarm — a red bar under the toolbar when the privileged
    // session has been lost (see PrivilegeWatchdog).
    private View mPrivilegeAlarm;
    private TextView mPrivilegeAlarmText;
    // Fork (白い熊, +132): the way back into the batch that is running, or the one that just
    // finished. Dismissed by hand; a new batch brings it back.
    @Nullable
    private View mOperationBar;
    @Nullable
    private TextView mOperationBarText;
    private boolean mOperationBarDismissed;
    private long mOperationBarStartedAt;
    private ImageView mPrivilegeAlarmIcon;
    @Nullable
    private ObjectAnimator mPrivilegeAlarmPulse;

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
    // Fork (白い熊, +116): that dialog is gone. A batch now opens
    // BatchOpsProgressActivity — a full page carrying the whole running log —
    // and this flag only keeps the page from being opened twice for one batch.
    private boolean mProgressPageOpened;
    // Fork (白い熊, +134): asked once per visit, not once per resume.
    private boolean mPendingImportOffered;
    // Fork (白い熊, +118): the pill shelf under the toolbar.
    @Nullable
    private ShelfView mShelf;
    // Fork (白い熊, +118): the batch pane, replacing the legacy selection bar.
    @Nullable
    private LinearLayoutCompat mBatchPane;
    @Nullable
    private io.github.muntashirakon.widget.FlowLayout mBatchActions;
    @Nullable
    private AppCompatTextView mBatchCount;
    private boolean mBatchPaneFolded;

    private final BroadcastReceiver mBatchOpsBroadCastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Fork: a batch op has started — open the progress page.
            if (BatchOpsService.ACTION_BATCH_OPS_STARTED.equals(intent.getAction())) {
                int startedOp = intent.getIntExtra(BatchOpsService.EXTRA_OP, BatchOpsManager.OP_NONE);
                String[] packages = intent.getStringArrayExtra(BatchOpsService.EXTRA_OP_PKG);
                openBatchProgressPage(startedOp, packages != null ? packages.length : 0);
                return;
            }
            // ACTION_BATCH_OPS_COMPLETED
            showProgressIndicator(false);
            // The page is NOT closed here: its whole purpose is to still be
            // there afterwards, holding what happened.
            mProgressPageOpened = false;
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
            // Fork (+118): an open pane is a state you leave with Back, before the screen is.
            if (mAdapter != null && mAdapter.collapsePane()) {
                return;
            }
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
            // Fork: the toolbar lives in a HorizontalScrollView (see
            // activity_main.xml), so when the bar is too wide for the screen it
            // is measured UNSPECIFIED and MATCH_PARENT above means nothing —
            // the search field would fall back to SearchView's 320dp preferred
            // width and push the action icons off the visible part of the bar.
            // Give it a narrower explicit width for that case only; when the
            // bar does fit, fillViewport re-measures it EXACTLY and the field
            // stretches as before.
            mSearchView.setUnconstrainedWidth(
                    getResources().getDimensionPixelSize(R.dimen.main_search_bar_scroll_width));
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
        // Fork (白い熊, +120): and GONE from the start, not just after the first hide() —
        // otherwise its band sits under the shelf until something happens to run.
        mProgressIndicator.setVisibility(View.GONE);
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
        setupShelf();
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
            updateBatchPane();
            updateSelectionReminder();
            return refresh;
        });
        setupBatchPane();
        setupSelectionReminder();
        setupPrivilegeAlarm();
        setupOperationBar();
        // Override the XML-inflated selection toolbar with the user's
        // customised order from MainToolbarPrefs, and wire each visible
        // toolbar button to open the same prefs screen on long-press.
        rebuildSelectionToolbarFromPrefs();

        if (SHOW_DISCLAIMER && AppPref.getBoolean(AppPref.PrefKey.PREF_SHOW_DISCLAIMER_BOOL)) {
            // Disclaimer will only be shown the first time it is loaded.
            SHOW_DISCLAIMER = false;
            View view = View.inflate(this, R.layout.dialog_disclaimer, null);
            ForkDialog.present(ForkDialog.builder(this)
                    .setView(view)
                    .setCancelable(false)
                    .setPositiveButton(R.string.disclaimer_agree, (dialog, which) -> {
                        if (((MaterialCheckBox) view.findViewById(R.id.agree_forever)).isChecked()) {
                            AppPref.set(AppPref.PrefKey.PREF_SHOW_DISCLAIMER_BOOL, false);
                        }
                        displayChangelogIfRequired();
                    })
                    .setNegativeButton(R.string.disclaimer_exit, (dialog, which) -> finishAndRemoveTask()));
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
        // Fork: wire the standalone process-monitor entry (custom action view,
        // left of the filter icon). Its actionLayout intercepts the tap, so the
        // click is set on the view rather than routed through onOptionsItemSelected.
        MenuItem monitorItem = menu.findItem(R.id.action_process_monitor);
        if (monitorItem != null && monitorItem.getActionView() != null) {
            View monitorView = monitorItem.getActionView();
            View monitorTarget = monitorView.findViewById(R.id.action_monitor_btn);
            if (monitorTarget == null) monitorTarget = monitorView;
            monitorTarget.setOnClickListener(v ->
                    startActivity(new Intent(this, ProcessMonitorActivity.class)));
        }
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
        boolean active = viewModel != null && viewModel.isFilterActive();
        int yellow = ContextCompat.getColor(this, R.color.theme_bright_yellow);
        // The fork's alarm red, the same one the Snooping page and the operation log use.
        int red = 0xFFFF0028;
        MenuItem filter = menu.findItem(R.id.action_list_options);
        if (filter != null) {
            // Traced when the list is showing everything, solid when something is being held
            // back — and no badge: a dot on a solid icon was a second thing saying what the
            // fill already says (白い熊, +119).
            filter.setIcon(tintedIcon(active ? R.drawable.ic_filter : R.drawable.ic_filter_traced,
                    yellow));
        }
        MenuItem clear = menu.findItem(R.id.action_clear_filters);
        if (clear != null) {
            // Red only when there is something to clear. Traced yellow otherwise, so the icon
            // says whether pressing it would do anything at all.
            clear.setIcon(tintedIcon(active ? R.drawable.ic_filter_clear
                    : R.drawable.ic_filter_clear_traced, active ? red : yellow));
        }
    }

    @Nullable
    private Drawable tintedIcon(@DrawableRes int drawableRes, int color) {
        Drawable drawable = ContextCompat.getDrawable(this, drawableRes);
        if (drawable == null) {
            return null;
        }
        // mutate() or the tint leaks into every other user of the shared constant state.
        drawable = drawable.mutate();
        drawable.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        return drawable;
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
        } else if (id == R.id.action_process_monitor) {
            // Fork: fallback — the action view normally handles the tap, but
            // route here too in case it ever surfaces without its action view.
            startActivity(new Intent(this, ProcessMonitorActivity.class));
        } else if (id == R.id.action_battery_history) {
            // Fork: per-app battery drain over time.
            startActivity(new Intent(this, BatteryUsageActivity.class));
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
        // Fork: the same two call sites are the right hook for a geometry that
        // moved without a recreation (multi-window resize, side-by-side switch).
        applyListLayoutIfGeometryChanged();
        boolean fontsChanged = FontPrefs.consumeChanged();
        boolean colorsChanged = ColorPrefs.consumeChanged();
        boolean separatorsChanged = SeparatorPrefs.consumeChanged();
        boolean framesChanged = SelectionFramePrefs.consumeChanged();
        // Fork: the running-app box width is read at bind time, so a change
        // just needs a re-bind (same as the frame).
        boolean runBoxChanged = RunningBoxPrefs.consumeChanged();
        // Fork: the main-list icon size is also read at bind time.
        boolean iconSizeChanged = MainIconPrefs.consumeChanged();
        if (mAdapter != null && (fontsChanged || colorsChanged || framesChanged || runBoxChanged || iconSizeChanged)) {
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
        applyPaneSpan();
        // Fork: the pick is per geometry, so remember which one this layout is
        // for — see applyListLayoutIfGeometryChanged().
        mLayoutGeometry = LayoutGeometry.key(this);
    }

    /**
     * Fork (白い熊, +118): an unrolled row takes the full width of a grid.
     *
     * <p>A span-size lookup is the whole mechanism, and it is why the pane is part of the row
     * rather than an item of its own: it changes how one position is laid out and touches no
     * data, so every position, every selection index and the whole
     * {@code MultiSelectionView.Adapter} contract stay exactly as they were.
     *
     * <p>{@code AutoFitGridLayoutManager} recomputes its span count on measure, so the count is
     * read inside the lookup rather than captured — a captured one would be whatever it was when
     * the phone was last folded.
     */
    private void applyPaneSpan() {
        RecyclerView.LayoutManager lm = mRecyclerView.getLayoutManager();
        if (!(lm instanceof GridLayoutManager)) {
            return;
        }
        GridLayoutManager grid = (GridLayoutManager) lm;
        grid.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return mAdapter != null && mAdapter.isExpandedAt(position) ? grid.getSpanCount() : 1;
            }
        });
    }

    /**
     * Fork: re-read the layout when the geometry changed under a surviving
     * activity. Folding or rotating normally recreates us (no configChanges is
     * declared), and then onCreate applies the right pick; this covers the paths
     * where the activity lives through it — a multi-window resize, or coming
     * back to a window whose geometry moved while we were paused.
     */
    private void applyListLayoutIfGeometryChanged() {
        if (mRecyclerView != null && !LayoutGeometry.key(this).equals(mLayoutGeometry)) {
            applyListLayout();
        }
    }

    /**
     * Fork: single-choice picker behind the 3x3 grid toolbar icon. Selecting
     * an entry persists it and swaps the layout manager immediately.
     */
    private void showLayoutPicker() {
        // Entry index maps straight onto the column count from index 1 on
        // (1 = "1 column", 2 = "2 columns", …); index 0 is adaptive.
        String[] choices = new String[]{
                getString(R.string.layout_adaptive),
                getString(R.string.layout_1_column),
                getString(R.string.layout_2_columns),
                getString(R.string.layout_3_columns),
                getString(R.string.layout_4_columns)};
        int columns = MainLayoutPrefs.getColumns(this);
        int checked = (columns >= 1 && columns <= 4) ? columns : 0;
        ForkDialog.present(ForkDialog.builder(this)
                .setTitle(R.string.list_layout)
                .setSingleChoiceItems(choices, checked, (dialog, which) -> {
                    int newColumns = which == 0 ? MainLayoutPrefs.COLUMNS_ADAPTIVE : which;
                    MainLayoutPrefs.setColumns(this, newColumns);
                    applyListLayout();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null));
    }

    /**
     * Fork (白い熊, +118): the adapter has opened or closed a row's pane.
     *
     * <p>Two things follow. The span cache must be dropped, because an open row takes the full
     * width of a grid and {@code GridLayoutManager} caches span indices; and the open row is
     * scrolled to, since a pane that unrolls below the fold looks like a tap that did nothing.
     */
    public void onPaneToggled(int position, boolean opened) {
        mOnBackPressedCallback.setEnabled(opened
                || (mAdapter != null && mAdapter.isInSelectionMode()));
        RecyclerView.LayoutManager lm = mRecyclerView == null ? null : mRecyclerView.getLayoutManager();
        if (lm instanceof GridLayoutManager) {
            ((GridLayoutManager) lm).getSpanSizeLookup().invalidateSpanIndexCache();
        }
        // Fork (白い熊, +125): the row STAYS WHERE IT IS. Scrolling the tapped row to the top was
        // meant to bring the pane into view and instead threw away the thing a tap should never
        // cost — your place in the list. The pane grows downward from where you tapped; if its
        // tail is below the fold, that is what scrolling is for.
    }

    // ── The batch pane (白い熊, +118) ────────────────────────────────────────

    /**
     * Build the panel that replaces the legacy selection bar.
     *
     * <p>The bar's actions were a menu rendered into a strip, so their labels were cut off — the
     * thing that made it unusable. These are the same actions, in the same configured order
     * ({@link MainToolbarPrefs}, so the existing settings page keeps working), as pills that wrap
     * onto as many lines as they need.
     *
     * <p><b>Every pill dispatches through {@link #onNavigationItemSelected}</b>, the menu-id
     * dispatcher that already existed. Not one line of batch logic is duplicated here.
     */
    private void setupBatchPane() {
        mBatchPane = findViewById(R.id.batch_pane);
        mBatchActions = findViewById(R.id.batch_pane_actions);
        mBatchCount = findViewById(R.id.batch_pane_count);
        if (mBatchPane == null || mBatchActions == null || mBatchCount == null) {
            return;
        }
        // The bar keeps doing the bookkeeping; only its face is gone.
        if (mMultiSelectionView != null) {
            mMultiSelectionView.setBarSuppressed(true, 0);
        }
        int ink = ForkThemeUtils.getTextColor();
        findViewById(R.id.batch_pane_rule).setBackgroundColor(RowPills.withAlpha(ink, 0.35f));
        mBatchCount.setTextColor(ink);
        AppCompatTextView selectAll = findViewById(R.id.batch_pane_select_all);
        AppCompatTextView clear = findViewById(R.id.batch_pane_clear);
        AppCompatTextView fold = findViewById(R.id.batch_pane_fold);
        RowPills.styleActionPill(selectAll, ink, false);
        RowPills.styleActionPill(clear, ink, false);
        RowPills.styleActionPill(fold, ink, false);
        selectAll.setOnClickListener(v -> {
            if (mAdapter != null) {
                mAdapter.selectAll();
            }
        });
        clear.setOnClickListener(v -> clearSelection());
        fold.setOnClickListener(v -> {
            mBatchPaneFolded = !mBatchPaneFolded;
            updateBatchPane();
        });
        rebuildBatchActions();
        // Report our height back to the widget so the last row still clears the panel — the same
        // path the bar used, rather than a second padding mechanism fighting it.
        mBatchPane.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (mMultiSelectionView != null && mBatchPane != null) {
                mMultiSelectionView.setBarSuppressed(true,
                        mBatchPane.getVisibility() == View.VISIBLE ? mBatchPane.getHeight() : 0);
            }
        });
    }

    /** Rebuild the action pills — called on start and whenever the configured order changes. */
    private void rebuildBatchActions() {
        if (mBatchActions == null || mMultiSelectionView == null) {
            return;
        }
        mBatchActions.removeAllViews();
        int ink = ForkThemeUtils.getTextColor();
        android.view.Menu menu = mMultiSelectionView.getMenu();
        List<String> placed = new ArrayList<>();
        for (String key : MainToolbarPrefs.loadVisibleOrder(this)) {
            int id = MainToolbarPrefs.idForKey(key);
            int titleRes = MainToolbarPrefs.titleForKey(key);
            if (id == 0 || titleRes == 0) {
                continue;
            }
            MenuItem menuItem = menu.findItem(id);
            if (menuItem == null) {
                continue;
            }
            TextView pill = RowPills.actionPill(this, getString(titleRes),
                    MainToolbarPrefs.iconForKey(key), ink, false);
            pill.setTag(id);
            pill.setOnClickListener(v -> onNavigationItemSelected(menuItem));
            mBatchActions.addView(pill);
            placed.add(key);
        }
        // Fork (白い熊, +121): long-press a pill and drop it on another to reorder them here,
        // rather than walking to the settings page to rearrange things you are looking at. The
        // order is the same one the settings page edits, so the two cannot disagree.
        PillDragReorder.attach(mBatchActions, placed, reordered -> {
            List<String> hidden = MainToolbarPrefs.loadHiddenOrder(this);
            MainToolbarPrefs.save(this, reordered, hidden);
            rebuildBatchActions();
            updateBatchPane();
        });
    }

    /**
     * Show, hide and refresh the panel. Each pill is enabled exactly when its menu item is —
     * {@link MainBatchOpsHandler} decides that, so a batch of uninstalled apps still cannot be
     * force-stopped from here.
     */
    private void updateBatchPane() {
        if (mBatchPane == null || mAdapter == null || mBatchActions == null || mBatchCount == null) {
            return;
        }
        int count = mAdapter.getSelectedItemCount();
        boolean visible = mAdapter.isInSelectionMode() && count > 0;
        mBatchPane.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) {
            if (mMultiSelectionView != null) {
                mMultiSelectionView.setBarSuppressed(true, 0);
            }
            return;
        }
        mBatchCount.setText(getString(R.string.batch_pane_count, count, mAdapter.getTotalItemCount()));
        AppCompatTextView fold = findViewById(R.id.batch_pane_fold);
        fold.setText(mBatchPaneFolded ? "▴" : "▾");
        mBatchActions.setVisibility(mBatchPaneFolded ? View.GONE : View.VISIBLE);
        for (int i = 0; i < mBatchActions.getChildCount(); ++i) {
            View pill = mBatchActions.getChildAt(i);
            Object tag = pill.getTag();
            if (!(tag instanceof Integer) || mMultiSelectionView == null) {
                continue;
            }
            MenuItem menuItem = mMultiSelectionView.getMenu().findItem((Integer) tag);
            boolean enabled = menuItem != null && menuItem.isEnabled() && menuItem.isVisible();
            pill.setEnabled(enabled);
            pill.setAlpha(enabled ? 1f : 0.4f);
        }
    }

    // ── The pill shelf (白い熊, +118) ────────────────────────────────────────

    private void setupShelf() {
        mShelf = findViewById(R.id.shelf);
        if (mShelf == null) {
            return;
        }
        mShelf.setListener(new ShelfView.Listener() {
            @Override
            public void onPillClicked(@NonNull ShelfPrefs.Pill pill) {
                applyShelfPill(pill);
            }

            @Override
            public void onPillLongClicked(@NonNull ShelfPrefs.Pill pill) {
                showShelfPillMenu(pill);
            }

            @Override
            public void onAddPill() {
                showAddShelfPill();
            }
        });
    }

    /**
     * A screen pill opens its screen; a view pill applies its view — and applying the view that
     * is already on screen clears it instead, so one pill is both the way in and the way out.
     * Without that, leaving a saved view means finding the filter dialog again, which is the very
     * thing the shelf exists to avoid.
     */
    private void applyShelfPill(@NonNull ShelfPrefs.Pill pill) {
        if (pill.isScreen()) {
            Intent intent = ListScreenActivity.intentFor(this, pill.payload);
            if (intent != null) {
                startActivity(intent);
            }
            return;
        }
        if (viewModel == null) {
            return;
        }
        if (pill.id.equals(mShelf != null ? mShelf.getActiveId() : null)) {
            viewModel.applyView(0, viewModel.getSortBy(), viewModel.isReverseSort(),
                    Collections.emptySet(), Collections.emptySet(), null,
                    AdvancedSearchView.SEARCH_TYPE_CONTAINS);
            if (mSearchView != null) {
                mSearchView.setQuery("", false);
            }
            if (mShelf != null) mShelf.setActiveId(null);
            return;
        }
        ShelfPrefs.ViewState state = ShelfPrefs.ViewState.fromJson(pill.payload);
        viewModel.applyView(state.filterFlags, state.sortBy, state.reverseSort,
                state.profilesInclude, state.profilesExclude, state.query, state.queryType);
        if (mSearchView != null) {
            mSearchView.setQuery(state.query == null ? "" : state.query, false);
        }
        if (mShelf != null) mShelf.setActiveId(pill.id);
    }

    /** What the list is showing right now, captured whole. */
    @NonNull
    private ShelfPrefs.ViewState currentViewState() {
        return new ShelfPrefs.ViewState(
                viewModel.getFilterFlags(),
                viewModel.getSortBy(),
                viewModel.isReverseSort(),
                new java.util.LinkedHashSet<>(viewModel.getProfileFiltersInclude()),
                new java.util.LinkedHashSet<>(viewModel.getProfileFiltersExclude()),
                viewModel.getSearchQuery(),
                AdvancedSearchView.SEARCH_TYPE_CONTAINS);
    }

    /**
     * Adding a pill. "Save this view" comes first on purpose: the list in front of you is already
     * filtered and sorted the way you want it, so capturing it is both the cheapest and the most
     * honest way to describe what the pill should do.
     */
    private void showAddShelfPill() {
        if (viewModel == null) {
            return;
        }
        // Fork (白い熊, +119): a real filter editor, opened preloaded with what the list is
        // showing — so "save this view" is simply pressing Save, and anything else is crafted.
        ShelfPillDialog.show(this, currentViewState(), this::addShelfPill);
    }

    private void addShelfPill(@NonNull ShelfPrefs.Pill pill) {
        ShelfPrefs.add(this, pill);
        if (mShelf != null) mShelf.reload();
    }

    /**
     * Rename · re-capture · remove — everything a pill can have done to it, as pills.
     *
     * <p>Fork (白い熊, +128): it was a bare list of words on black, which is what a menu looks
     * like when nobody has designed it. The actions here are the same kind of thing as every
     * other control in this app, so they are the same kind of control, and the destructive one
     * says so in the alarm red rather than by being third.
     */
    private void showShelfPillMenu(@NonNull ShelfPrefs.Pill pill) {
        int ink = ForkThemeUtils.getTextColor();
        int red = 0xFFFF0028;
        float d = getResources().getDisplayMetrics().density;
        io.github.muntashirakon.widget.FlowLayout body = new io.github.muntashirakon.widget.FlowLayout(this);
        body.setChildSpacing(Math.round(8 * d));
        body.setRowSpacing(Math.round(8 * d));
        int pad = Math.round(16 * d);
        body.setPadding(pad, Math.round(8 * d), pad, Math.round(4 * d));
        final androidx.appcompat.app.AlertDialog[] dialog = new androidx.appcompat.app.AlertDialog[1];

        TextView rename = RowPills.actionPill(this, getString(R.string.shelf_rename),
                R.drawable.ic_note_24dp, ink, false);
        rename.setOnClickListener(v -> {
            if (dialog[0] != null) dialog[0].dismiss();
            promptShelfName(pill.name, name -> {
                ShelfPrefs.rename(this, pill.id, name);
                if (mShelf != null) mShelf.reload();
            });
        });
        body.addView(rename);

        if (!pill.isScreen()) {
            TextView update = RowPills.actionPill(this, getString(R.string.shelf_update_to_current),
                    R.drawable.ic_backup_restore, ink, false);
            update.setOnClickListener(v -> {
                if (dialog[0] != null) dialog[0].dismiss();
                // Replaced in place, keeping the id, so the pill stays where it was dragged to
                // and stays the active one if it is showing.
                List<ShelfPrefs.Pill> pills = ShelfPrefs.load(this);
                for (int i = 0; i < pills.size(); ++i) {
                    if (pills.get(i).id.equals(pill.id)) {
                        pills.set(i, new ShelfPrefs.Pill(pill.id, pill.name,
                                ShelfPrefs.KIND_VIEW, currentViewState().toJson()));
                        break;
                    }
                }
                ShelfPrefs.save(this, pills);
                if (mShelf != null) mShelf.reload();
            });
            body.addView(update);
        }

        TextView remove = RowPills.actionPill(this, getString(R.string.shelf_remove),
                R.drawable.ic_trash_can, red, false);
        remove.setOnClickListener(v -> {
            if (dialog[0] != null) dialog[0].dismiss();
            ShelfPrefs.remove(this, pill.id);
            if (mShelf != null) {
                if (pill.id.equals(mShelf.getActiveId())) mShelf.setActiveId(null);
                mShelf.reload();
            }
        });
        body.addView(remove);

        dialog[0] = ForkDialog.present(ForkDialog.builder(this)
                .setTitle(pill.name)
                .setView(body)
                .setNegativeButton(R.string.cancel, null));
    }

    private void promptShelfName(@NonNull String initial, @NonNull androidx.core.util.Consumer<String> onName) {
        new TextInputDialogBuilder(this, R.string.shelf_name)
                .setTitle(R.string.shelf_name)
                .setInputText(initial)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (dialog, which, inputText, isChecked) -> {
                    String name = inputText == null ? "" : inputText.toString().trim();
                    if (name.isEmpty()) {
                        name = initial;
                    }
                    onName.accept(name);
                })
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
        updateBatchPane();
        updateSelectionReminder();
    }

    @Override
    public void onSelectionModeDisabled() {
        mOnBackPressedCallback.setEnabled(false);
        updateBatchPane();
        // Selection cleared/exited — drop the reminder and close its sheet.
        updateSelectionReminder();
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_backup) {
            if (viewModel != null) {
                // Fork (白い熊, +121): backing up a batch opens the TABLE — every selected app
                // with its own parts, and its own app-supplied categories where it has a door.
                // One set of flags for five hundred apps was only ever right when they were all
                // the same kind of thing. Restore and delete stay with the dialog that answers
                // them, reachable from the table's own button.
                List<UserPackagePair> pairs = viewModel.getSelectedPackagesWithUsers();
                new BatchBackupTableDialog(this, pairs, new BatchBackupTableDialog.Listener() {
                    @Override
                    public void onBackup(@NonNull java.util.Map<String, Integer> perPackageFlags,
                                         int fallbackFlags,
                                         @NonNull java.util.Map<String, String[]> perPackageAppData) {
                        handleBatchOp(BatchOpsManager.OP_BACKUP, new BatchBackupOptions(
                                fallbackFlags, null, null, perPackageFlags, null, perPackageAppData));
                    }

                    @Override
                    public void onRestoreOrDelete() {
                        openBackupRestoreSheet(pairs);
                    }
                }).show();
            }
        } else if (id == R.id.action_save_apk) {
            mStoragePermission.request(granted -> {
                if (granted) handleBatchOp(BatchOpsManager.OP_BACKUP_APK);
            });
        } else if (id == R.id.action_block_unblock_trackers) {
            ForkDialog.present(ForkDialog.builder(this)
                    .setTitle(R.string.block_unblock_trackers)
                    .setMessage(R.string.choose_what_to_do)
                    .setPositiveButton(R.string.block, (dialog, which) ->
                            handleBatchOp(BatchOpsManager.OP_BLOCK_TRACKERS))
                    .setNegativeButton(R.string.cancel, null)
                    .setNeutralButton(R.string.unblock, (dialog, which) ->
                            handleBatchOp(BatchOpsManager.OP_UNBLOCK_TRACKERS)));
        } else if (id == R.id.action_clear_data_cache) {
            ForkDialog.present(ForkDialog.builder(this)
                    .setTitle(R.string.clear)
                    .setMessage(R.string.choose_what_to_do)
                    .setPositiveButton(R.string.clear_cache, (dialog, which) ->
                            handleBatchOp(BatchOpsManager.OP_CLEAR_CACHE))
                    .setNegativeButton(R.string.cancel, null)
                    .setNeutralButton(R.string.clear_data, (dialog, which) ->
                            handleBatchOp(BatchOpsManager.OP_CLEAR_DATA)));
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
            ForkDialog.present(ForkDialog.builder(this)
                    .setTitle(R.string.are_you_sure)
                    .setMessage(R.string.disable_background_run_description)
                    .setPositiveButton(R.string.yes, (dialog, which) ->
                            handleBatchOp(BatchOpsManager.OP_DISABLE_BACKGROUND))
                    .setNegativeButton(R.string.no, null));
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
        // Fork (+118): the shelf and the batch pills are both configured elsewhere — the shelf
        // from its own editor dialogs, the pills from the settings page — so both are re-read
        // here rather than assumed unchanged.
        if (mShelf != null) {
            mShelf.reload();
        }
        rebuildBatchActions();
        updateBatchPane();
        refreshForkAppearanceIfChanged();
        // Fork: re-check the privileged session. The binder-death listener covers a
        // server that dies while we are on screen; this covers everything that
        // happened while we were away — including a death our process slept through.
        PrivilegeWatchdog.refresh();
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
        // Re-open the progress page if an op is still running and we have not
        // already shown it for this batch (e.g. the user left and came back
        // mid-operation). Reaching the main list mid-batch means the page was
        // dismissed deliberately, so this only fires once per run.
        if (BatchOpsProgressMonitor.getInstance().isActive() && !mProgressPageOpened) {
            openBatchProgressPage(BatchOpsManager.OP_NONE, Integer.MAX_VALUE);
        }
        offerPendingStateImport();
    }

    /**
     * Fork (白い熊, +134): a restore of this app's OWN settings, waiting to be applied.
     *
     * <p>It cannot be applied where it arrives — the restore is running inside this process, and
     * applying it means replacing preference files and then killing the process. So it is staged
     * and offered here, on the next visit to the list, as a choice: {@link PendingStateImport}
     * explains why an explicit tap is the only safe moment.
     */
    private void offerPendingStateImport() {
        if (mPendingImportOffered || !PendingStateImport.isPending(this)) {
            return;
        }
        mPendingImportOffered = true;
        ForkDialog.present(ForkDialog.builder(this)
                .setTitle(R.string.pending_state_import_title)
                .setMessage(R.string.pending_state_import_message)
                .setNegativeButton(R.string.settings_eim_later, null)
                .setNeutralButton(R.string.discard, (dialog, which) -> PendingStateImport.discard(this))
                .setPositiveButton(R.string.settings_eim_restart_now, (dialog, which) -> {
                    try {
                        PendingStateImport.apply(this);
                    } catch (Throwable th) {
                        UIUtils.displayLongToast(R.string.failed);
                        return;
                    }
                    // Hard-kill, never Runtime.exit: cached SharedPreferences would otherwise be
                    // written back over what was just imported. Same rule as the Export/Import
                    // panel's "Restart now".
                    android.os.Process.killProcess(android.os.Process.myPid());
                }));
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

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mSearchDebouncer != null) {
            mSearchDebouncer.unbind();
        }
        // Fork: an INFINITE animator holds a strong reference to its target view.
        stopPrivilegeAlarmPulse();
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

    /** The multi-tab sheet, for the questions it answers better than the table does. */
    private void openBackupRestoreSheet(@NonNull List<UserPackagePair> pairs) {
        BackupRestoreDialogFragment fragment = BackupRestoreDialogFragment.getInstance(pairs);
        fragment.setOnActionBeginListener(mode -> showProgressIndicator(true));
        fragment.setOnActionCompleteListener((mode, failedPackages) -> showProgressIndicator(false));
        fragment.show(getSupportFragmentManager(), BackupRestoreDialogFragment.TAG);
        clearSelection();
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

    // Fork: the privilege alarm's palette — the same pair the Snooping page gives an
    // allowed capability (AppDetailsSnoopingFragment): filled blood red with
    // near-white text, this fork's mark for the state that actually matters. NOT the
    // theme yellow, which here would read as one more piece of ordinary furniture.
    private static final int PRIVILEGE_ALARM_BG = 0xFF6E0B14;
    private static final int PRIVILEGE_ALARM_FG = 0xFFFFD9DC;

    @PrivilegeWatchdog.State
    private int mLastPrivilegeState = PrivilegeWatchdog.STATE_OK;

    // Fork: one-time setup of the privilege alarm — the red bar that says the
    // privileged session is gone. Before this existed, a Shizuku server restart took
    // the AM service binder with it and the app carried on silently on the no-root
    // shell: privileged operations quietly did nothing and the window looked exactly
    // like a working one. See PrivilegeWatchdog for what raises and clears this.
    /**
     * Fork (白い熊, +132): a pinned line that leads back to the batch operation.
     *
     * <p>Back on the progress page has never cancelled anything — the work runs in a foreground
     * service, and the page is only a window onto it — but once that window was closed the only
     * route back was the system notification, which is an odd place to look for something
     * happening inside this app. So the list itself says that a batch is running, and keeps
     * saying it after the batch ends until dismissed: the finished log is the more valuable of
     * the two, and it is exactly the one that used to become unreachable.
     */
    private void setupOperationBar() {
        mOperationBar = findViewById(R.id.operation_bar);
        if (mOperationBar == null) {
            return;
        }
        mOperationBarText = mOperationBar.findViewById(R.id.operation_bar_text);
        ImageView icon = mOperationBar.findViewById(R.id.operation_bar_icon);
        View dismiss = mOperationBar.findViewById(R.id.operation_bar_dismiss);
        int ink = ForkThemeUtils.getTextColor();
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(Color.BLACK);
        background.setStroke(Math.round(ForkThemeUtils.dpToPx(this, 1f)),
                ColorUtils.setAlphaComponent(ink, 0x88));
        mOperationBar.setBackground(background);
        mOperationBarText.setTextColor(ink);
        icon.setImageTintList(ColorStateList.valueOf(ink));
        ((ImageView) dismiss).setImageTintList(ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(ink, 0xAA)));
        mOperationBar.setOnClickListener(v -> {
            try {
                startActivity(BatchOpsProgressActivity.getIntent(this));
            } catch (Throwable ignore) {
            }
        });
        dismiss.setOnClickListener(v -> {
            mOperationBarDismissed = true;
            mOperationBar.setVisibility(View.GONE);
        });
        BatchOpsProgressMonitor.getInstance().getState().observe(this, this::updateOperationBar);
    }

    private void updateOperationBar(@Nullable BatchOpsProgressMonitor.State state) {
        if (mOperationBar == null || mOperationBarText == null) {
            return;
        }
        if (state == null || (!state.active && state.done == 0 && state.max == 0)) {
            // Nothing has run in this process yet — there is no log to go back to.
            mOperationBar.setVisibility(View.GONE);
            return;
        }
        if (state.active && state.startedAtRealtime != mOperationBarStartedAt) {
            // A new batch. Whatever was dismissed was about the previous one.
            mOperationBarStartedAt = state.startedAtRealtime;
            mOperationBarDismissed = false;
        }
        if (mOperationBarDismissed
                || (!state.active && BatchOpsProgressMonitor.getInstance().isResultSeen())) {
            // Dismissed by hand, or already read on the progress page — see noteResultSeen.
            mOperationBar.setVisibility(View.GONE);
            return;
        }
        CharSequence title = state.title != null ? state.title : OpLog.getInstance().getTitle();
        String counts = state.max > 0
                ? getString(R.string.op_bar_counts, state.done, state.max)
                : String.valueOf(state.done);
        String text;
        if (state.active) {
            text = getString(state.paused ? R.string.op_bar_paused : R.string.op_bar_running,
                    title, counts);
        } else if (state.failed > 0) {
            text = getString(R.string.op_bar_finished_failed, title, counts, state.failed);
        } else {
            text = getString(R.string.op_bar_finished, title, counts);
        }
        mOperationBarText.setText(text);
        mOperationBar.setVisibility(View.VISIBLE);
    }

    private void setupPrivilegeAlarm() {
        mPrivilegeAlarm = findViewById(R.id.privilege_alarm);
        if (mPrivilegeAlarm == null) {
            return;
        }
        mPrivilegeAlarmText = mPrivilegeAlarm.findViewById(R.id.privilege_alarm_text);
        mPrivilegeAlarmIcon = mPrivilegeAlarm.findViewById(R.id.privilege_alarm_icon);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(PRIVILEGE_ALARM_BG);
        background.setStroke(Math.round(ForkThemeUtils.dpToPx(this, 1f)), PRIVILEGE_ALARM_FG);
        mPrivilegeAlarm.setBackground(background);
        mPrivilegeAlarmText.setTextColor(PRIVILEGE_ALARM_FG);
        mPrivilegeAlarmIcon.setImageTintList(ColorStateList.valueOf(PRIVILEGE_ALARM_FG));
        // The one path allowed to prompt: 白い熊 asked for it by tapping. Everything
        // automatic gates itself on an already-authorised server instead.
        mPrivilegeAlarm.setOnClickListener(v -> PrivilegeWatchdog.reclaimAsync(this, true));
        PrivilegeWatchdog.getState().observe(this, state ->
                updatePrivilegeAlarm(state == null ? PrivilegeWatchdog.STATE_OK : state));
    }

    private void updatePrivilegeAlarm(@PrivilegeWatchdog.State int state) {
        if (mPrivilegeAlarm == null) {
            return;
        }
        if (state == PrivilegeWatchdog.STATE_OK) {
            if (mPrivilegeAlarm.getVisibility() != View.GONE) {
                mPrivilegeAlarm.setVisibility(View.GONE);
            }
            stopPrivilegeAlarmPulse();
            mLastPrivilegeState = state;
            return;
        }
        CharSequence mode = PrivilegeWatchdog.expectedModeLabel(this);
        if (state == PrivilegeWatchdog.STATE_RECLAIMING) {
            mPrivilegeAlarmText.setText(getString(R.string.privilege_alarm_reclaiming, mode));
            stopPrivilegeAlarmPulse();
        } else {
            mPrivilegeAlarmText.setText(getString(R.string.privilege_alarm_lost, mode));
            startPrivilegeAlarmPulse();
            if (mLastPrivilegeState == PrivilegeWatchdog.STATE_RECLAIMING) {
                // An attempt 白い熊 asked for came back empty. Say so — the bar alone
                // would look as though the tap had done nothing at all.
                UIUtils.displayLongToast(R.string.privilege_alarm_failed, mode);
            }
        }
        if (mPrivilegeAlarm.getVisibility() != View.VISIBLE) {
            mPrivilegeAlarm.setVisibility(View.VISIBLE);
        }
        mLastPrivilegeState = state;
    }

    // Fork: the icon pulses while privileges are actually lost, and only then — a
    // still bar is easy to stop seeing, and the reclaiming state is a wait rather
    // than an alarm. Cancelled on destroy: an infinite animator holds its view.
    private void startPrivilegeAlarmPulse() {
        if (mPrivilegeAlarmIcon == null || mPrivilegeAlarmPulse != null) {
            return;
        }
        ObjectAnimator pulse = ObjectAnimator.ofFloat(mPrivilegeAlarmIcon, View.ALPHA, 1f, 0.25f);
        pulse.setDuration(750L);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.start();
        mPrivilegeAlarmPulse = pulse;
    }

    private void stopPrivilegeAlarmPulse() {
        if (mPrivilegeAlarmPulse != null) {
            mPrivilegeAlarmPulse.cancel();
            mPrivilegeAlarmPulse = null;
        }
        if (mPrivilegeAlarmIcon != null) {
            mPrivilegeAlarmIcon.setAlpha(1f);
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

    /**
     * Fork (白い熊, +116): open the batch progress page.
     *
     * <p>Backup and restore always open it, whatever their size — those are the operations worth
     * watching. Everything else opens it only for more than one app: a full screen for a
     * two-second freeze of a single app is in the way rather than useful.
     */
    private void openBatchProgressPage(int op, int packageCount) {
        if (!Prefs.Appearance.showBatchProgressDialog()) {
            return;
        }
        if (op != BatchOpsManager.OP_NONE
                && !BatchOpsProgressActivity.shouldAutoOpen(op, packageCount)) {
            return;
        }
        mProgressPageOpened = true;
        try {
            startActivity(BatchOpsProgressActivity.getIntent(this));
        } catch (Throwable ignore) {
        }
    }

    void showProgressIndicator(boolean show) {
        if (show) mProgressIndicator.show();
        else mProgressIndicator.hide();
    }
}
