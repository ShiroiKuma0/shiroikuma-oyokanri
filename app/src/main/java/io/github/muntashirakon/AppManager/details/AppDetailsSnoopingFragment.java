// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.details;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandleHidden;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.GridLayoutManager;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;

import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.apk.behavior.FreezeUnfreeze;
import io.github.muntashirakon.AppManager.apk.installer.PackageInstallerCompat;
import io.github.muntashirakon.AppManager.compat.ActivityManagerCompat;
import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.details.struct.AppDetailsItem;
import io.github.muntashirakon.AppManager.details.struct.AppDetailsSnoopingItem;
import io.github.muntashirakon.AppManager.devicepolicy.DangerDialog;
import io.github.muntashirakon.AppManager.devicepolicy.DevicePolicyBridge;
import io.github.muntashirakon.AppManager.devicepolicy.PolicyApiClient;
import io.github.muntashirakon.AppManager.devicepolicy.PolicyLockState;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.profiles.ProtectedAppsProfile;
import io.github.muntashirakon.AppManager.self.SelfPermissions;
import io.github.muntashirakon.AppManager.settings.Prefs;
import io.github.muntashirakon.AppManager.snooping.SnoopingCatalog;
import io.github.muntashirakon.AppManager.snooping.SnoopingEnforcer;
import io.github.muntashirakon.AppManager.snooping.SnoopingPrefs;
import io.github.muntashirakon.AppManager.snooping.SnoopingState;
import io.github.muntashirakon.AppManager.utils.BroadcastUtils;
import io.github.muntashirakon.AppManager.utils.ContextUtils;
import io.github.muntashirakon.AppManager.utils.ForkDialog;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;
import io.github.muntashirakon.AppManager.utils.FreezeUtils;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;
import io.github.muntashirakon.dialog.ScrollableDialogBuilder;
import io.github.muntashirakon.view.ProgressIndicatorCompat;
import io.github.muntashirakon.widget.MaterialAlertView;
import io.github.muntashirakon.widget.RecyclerView;

/**
 * Fork: the <em>Snooping</em> tab — every privacy-invasive capability of this app
 * that we can <b>actually</b> switch off through ADB/Shizuku, grouped and in one
 * place, instead of scattered across the App Ops and Permissions tabs among
 * hundreds of rows that mostly cannot be moved.
 * <p>
 * Switch semantics match the rest of the app: <b>on = the app is allowed</b>,
 * off = blocked. Every flip is also <i>recorded</i> against the package name, so
 * the decision survives an uninstall, travels in a settings export, and is
 * replayed by {@link SnoopingEnforcer} when the package appears on another phone
 * — see {@link SnoopingPrefs}.
 */
public class AppDetailsSnoopingFragment extends AppDetailsFragment {
    // Fork: the shared success/failure colours (#1b8654 salem_green / #ff0028
    // electric_red) are meant for a light-ish surface; at body-small size on the
    // pure-black app-details page they are close to illegible — the red worst of
    // all. These rows therefore use brightened foregrounds on a chip tinted with
    // the same hue, which lifts the text off the black without shouting.
    /**
     * "Allowed" is the state worth flinching at, so its pill is filled blood red
     * rather than washed — with near-white text, since the point of the pill was
     * legibility in the first place.
     */
    @ColorInt
    private static final int STATUS_CHIP_ALLOWED = 0xFF6E0B14;
    @ColorInt
    private static final int STATUS_TEXT_ALLOWED = 0xFFFFD9DC;
    @ColorInt
    private static final int STATUS_COLOR_BLOCKED = 0xFF7FE3A5;
    /**
     * "Only while in use" is a narrowing <em>we</em> chose, so it takes the same
     * yellow as every other decision of ours rather than an amber of its own
     * (白い熊: the fork has one yellow, and it is the configurable theme's).
     * Which of the two yellow states a row is in is read from the pill's words —
     * a narrowed row names its own rung, e.g. "No background mobile data".
     */
    @ColorInt
    private static int statusColorForeground() {
        return ForkThemeUtils.getTextColor();
    }
    @ColorInt
    private static final int DETAIL_COLOR = 0xFFD0D6DC;
    /** Blocked chip fill = its text colour at this alpha, i.e. a wash of its own hue. */
    private static final int STATUS_CHIP_ALPHA = 0x3D;
    private static final int DETAIL_CHIP_ALPHA = 0x1F;
    /** Outline of a row whose live state is not the default one. */
    private static final float CHANGED_STROKE_DP = 3f;
    /**
     * How far the padlock's hit rect grows past its own bounds — towards the
     * label on one side and, by exactly the switch's start margin, towards the
     * switch on the other. Asymmetric on purpose: the end side is the one that
     * was losing taps, and the start side has a selectable detail chip that
     * should keep its own.
     */
    private static final float LOCK_TOUCH_GROW_START_DP = 4f;
    private static final float LOCK_TOUCH_GROW_END_DP = 6f;
    /**
     * …and in red when the change went the dangerous way — switched ON where the
     * platform's own default is off. Yellow marks the protective direction.
     */
    @ColorInt
    private static final int CHANGED_STROKE_ALLOWED = 0xFFFF0028;

    private static final String TAG = AppDetailsSnoopingFragment.class.getSimpleName();

    private SnoopingRecyclerAdapter mAdapter;
    private boolean mCanEnforce;

    // ── Device-policy state, read from the platform and from 白い熊 雫 ─────────
    /** We hold delegated scopes, i.e. hard locks are actually available. */
    private boolean mPolicyDelegate;
    /** 雫 answered and is Device Owner — so the powers exist but may not be ours yet. */
    private boolean mPolicyOwnerPresent;
    private boolean mPolicySuspended;
    private boolean mPolicyUninstallBlocked;
    /**
     * The two 雫-side locks, read from {@link PolicyLockState} because the
     * platform offers this side no getter for either — see that class for why
     * that is honest rather than lazy.
     */
    private boolean mPolicyUserControlDisabled;
    private boolean mPolicyAccessibilityBlocked;
    /** Resolved on the worker with the rest, so no bind() ever makes a binder call. */
    private boolean mPolicyCanLock;
    private boolean mPolicyCanSuspend;
    private boolean mPolicyCanBlockUninstall;

    // ── The two ordinary verdicts on the whole app (白い熊) ───────────────────
    /**
     * Frozen by any method — disabled, suspended or hidden. Read <b>live</b> from
     * the package manager rather than from the view model's cached
     * {@code PackageInfo}: the model reloads asynchronously after a freeze, so a
     * bind driven off its copy would draw the state we just left behind.
     */
    private boolean mAppFrozen;
    private boolean mCanFreeze;
    /** External APKs have nothing to freeze or uninstall — the row is withheld. */
    private boolean mCanUninstall;
    /** Resolved on the worker so the uninstall dialog needs no lookup of its own. */
    @Nullable
    private CharSequence mAppLabel;
    private boolean mAppIsSystem;
    private boolean mAppIsUpdatedSystemApp;
    /**
     * Fork: the {@link AppDetailsViewModel#getStateEpoch() state epoch} this list
     * was built from. Tabs are loaded once and never again as you page between
     * them, so a switch flipped on the App ops tab would otherwise leave this
     * page showing — and acting on — the state from when the screen opened.
     */
    private int mRenderedEpoch;

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        emptyView.setText(R.string.snooping_no_capabilities);
        // Fork LANDMINE — the auto-fit grid decides once, and it decided while we
        // were nothing (白い熊, +92). AutoFitGridLayoutManager, which the base
        // fragment installs, computes its span count on its FIRST onLayoutChildren
        // from getWidth() and then clears the flag for ever; getWidth() is 0 until
        // the view has actually been laid out. That never showed while this tab was
        // second — an offscreen page is created after the pager's own first pass,
        // so its first layout already had a width — and it appeared the moment
        // Snooping became the page the activity opens on: two cards per row on the
        // unfolded tri-fold collapsed to one, permanently, because nothing ever
        // recomputes it. The configuration knows the window's width before any view
        // is measured, so take the count from there instead: same 450dp per column,
        // no dependence on when we happen to be laid out, and correct in multi-window
        // because screenWidthDp describes the window rather than the display.
        //
        // 450dp was also simply too wide to ever split this screen (白い熊, +93):
        // the tri-fold unfolds to a 840dp window (2048px at density 390), so
        // 840/450 = 1 and the page could not have gone two-column on the very
        // device it was meant for. 380dp is chosen against the two real
        // geometries rather than as a round number — 840/380 = 2 unfolded, and
        // the folded cover panel's 413dp stays at 1 with room to spare.
        int columnDp = 380;
        int spanCount = Math.max(1,
                activity.getResources().getConfiguration().screenWidthDp / columnDp);
        recyclerView.setLayoutManager(new GridLayoutManager(activity, spanCount));
        mAdapter = new SnoopingRecyclerAdapter();
        recyclerView.setAdapter(mAdapter);
        // Group headings must span the whole row when the list goes multi-column
        // (it does on the tri-fold, at 450dp per column).
        if (recyclerView.getLayoutManager() instanceof GridLayoutManager) {
            GridLayoutManager glm = (GridLayoutManager) recyclerView.getLayoutManager();
            glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
                @Override
                public int getSpanSize(int position) {
                    // Read the span count at call time: a fold recreates the
                    // activity, and the new one resolves a count of its own.
                    return mAdapter != null && mAdapter.isHeader(position) ? glm.getSpanCount() : 1;
                }
            });
        }
        mCanEnforce = SnoopingEnforcer.canEnforce();
        alertView.setEndIconOnClickListener(v -> alertView.hide());
        if (!mCanEnforce) {
            alertView.setAlertType(MaterialAlertView.ALERT_TYPE_WARN);
            alertView.setText(R.string.snooping_needs_privileges);
            alertView.show();
        } else {
            alertView.setVisibility(View.GONE);
        }
        if (viewModel == null) return;
        mRenderedEpoch = viewModel.getStateEpoch();
        viewModel.get(AppDetailsFragment.SNOOPING).observe(getViewLifecycleOwner(), items -> {
            if (items != null && mAdapter != null && viewModel.isPackageExist()) {
                mAdapter.setItems(items);
            }
            ProgressIndicatorCompat.setVisibility(progressIndicator, false);
        });
        loadPolicyState();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (viewModel == null) {
            return;
        }
        // Fork: re-read only when something actually moved — see mRenderedEpoch.
        int epoch = viewModel.getStateEpoch();
        if (epoch != mRenderedEpoch) {
            mRenderedEpoch = epoch;
            ProgressIndicatorCompat.setVisibility(progressIndicator, true);
            viewModel.load(AppDetailsFragment.SNOOPING);
            loadPolicyState();
        }
    }

    /**
     * Fork: what device policy can do for this app, asked fresh.
     * <p>
     * Both halves are read every time rather than cached across screens: the
     * delegation is granted in another app entirely (白い熊 雫), so it can appear
     * or vanish while this page is open, and a stale "no powers" would be a
     * padlock that silently stopped being offered.
     */
    private void loadPolicyState() {
        if (viewModel == null) return;
        String packageName = viewModel.getPackageName();
        boolean externalApk = viewModel.isExternalApk();
        int userId = viewModel.getUserId();
        ThreadUtils.postOnBackgroundThread(() -> {
            DevicePolicyBridge.invalidate();
            boolean delegate = DevicePolicyBridge.isDelegate();
            boolean suspended = packageName != null && DevicePolicyBridge.isSuspended(packageName);
            boolean uninstallBlocked = packageName != null
                    && DevicePolicyBridge.isUninstallBlocked(packageName);
            // Only ask 雫 when we have nothing: the answer is only needed to tell
            // "no Device Owner on this phone" from "not authorised yet", and a
            // provider call per page open is worth skipping when it cannot matter.
            boolean ownerPresent = delegate || PolicyApiClient.isDeviceOwnerPresent();
            boolean canLock = DevicePolicyBridge.canLockPermissions();
            boolean canSuspend = DevicePolicyBridge.canSuspend();
            boolean canBlockUninstall = DevicePolicyBridge.canBlockUninstall();
            boolean userControlDisabled = packageName != null
                    && PolicyLockState.isUserControlDisabled(packageName);
            boolean accessibilityBlocked = packageName != null
                    && PolicyLockState.isAccessibilityBlocked(packageName);
            // The freeze/uninstall rows' own state, read live from the platform.
            ApplicationInfo appInfo = packageName != null && !externalApk
                    ? resolveApplicationInfo(packageName, userId) : null;
            boolean frozen = appInfo != null && FreezeUtils.isFrozen(appInfo);
            boolean canFreeze = appInfo != null && SelfPermissions.canFreezeUnfreezePackages();
            boolean canUninstall = appInfo != null;
            CharSequence label = appInfo != null
                    ? appInfo.loadLabel(ContextUtils.getContext().getPackageManager()) : null;
            boolean isSystem = appInfo != null
                    && (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean isUpdatedSystem = appInfo != null
                    && (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            ThreadUtils.postOnMainThread(() -> {
                if (isDetached()) return;
                mPolicyDelegate = delegate;
                mPolicyOwnerPresent = ownerPresent;
                mPolicySuspended = suspended;
                mPolicyUninstallBlocked = uninstallBlocked;
                mPolicyUserControlDisabled = userControlDisabled;
                mPolicyAccessibilityBlocked = accessibilityBlocked;
                mPolicyCanLock = canLock;
                mPolicyCanSuspend = canSuspend;
                mPolicyCanBlockUninstall = canBlockUninstall;
                mAppFrozen = frozen;
                mCanFreeze = canFreeze;
                mCanUninstall = canUninstall;
                mAppLabel = label;
                mAppIsSystem = isSystem;
                mAppIsUpdatedSystemApp = isUpdatedSystem;
                if (mAdapter != null) mAdapter.notifyPolicyChanged();
            });
        });
    }

    /**
     * This app's {@link ApplicationInfo}, asked of the platform rather than of the
     * view model.
     * <p>
     * <b>Landmine.</b> A package frozen by <i>hiding</i> is invisible to a plain
     * lookup, so the match flags are not optional here: without them the very app
     * whose freeze switch we are about to draw resolves to nothing, and the row
     * would be withheld exactly when it is needed. The privileged path is tried
     * first because it is the one that can see another user's packages at all;
     * the plain package manager is the fallback for the window before privileges
     * are up.
     */
    @Nullable
    @WorkerThread
    private static ApplicationInfo resolveApplicationInfo(@NonNull String packageName, int userId) {
        int flags = PackageManagerCompat.MATCH_UNINSTALLED_PACKAGES
                | PackageManager.MATCH_DISABLED_COMPONENTS;
        try {
            return PackageManagerCompat.getApplicationInfo(packageName, flags, userId);
        } catch (Throwable th) {
            try {
                return ContextUtils.getContext().getPackageManager().getApplicationInfo(packageName, flags);
            } catch (Throwable th2) {
                return null;
            }
        }
    }

    @Override
    public void onRefresh() {
        refreshDetails();
        swipeRefresh.setRefreshing(false);
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.fragment_app_details_snooping_actions, menu);
    }

    @Override
    public void onPrepareMenu(@NonNull Menu menu) {
        MenuItem showAll = menu.findItem(R.id.action_snooping_show_all);
        if (showAll != null) {
            showAll.setChecked(SnoopingPrefs.isShowAllEnabled());
        }
        MenuItem autoApply = menu.findItem(R.id.action_snooping_auto_apply);
        if (autoApply != null) {
            autoApply.setChecked(SnoopingPrefs.isAutoApplyEnabled());
        }
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_refresh_details) {
            refreshDetails();
            return true;
        }
        if (id == R.id.action_snooping_show_all) {
            SnoopingPrefs.setShowAllEnabled(!SnoopingPrefs.isShowAllEnabled());
            item.setChecked(SnoopingPrefs.isShowAllEnabled());
            refreshDetails();
            return true;
        }
        if (id == R.id.action_snooping_auto_apply) {
            SnoopingPrefs.setAutoApplyEnabled(!SnoopingPrefs.isAutoApplyEnabled());
            item.setChecked(SnoopingPrefs.isAutoApplyEnabled());
            return true;
        }
        if (id == R.id.action_snooping_block_all) {
            confirmBlockAll();
            return true;
        }
        if (id == R.id.action_snooping_forget) {
            confirmForget();
            return true;
        }
        if (id == R.id.action_snooping_missing_ops) {
            showMissingOps();
            return true;
        }
        if (id == R.id.action_snooping_legend) {
            showLegend();
            return true;
        }
        return false;
    }

    /**
     * Fork: the legend, laid out rather than streamed (白い熊, +70).
     * <p>
     * It used to be one {@code setMessage} string — every mark, every colour and
     * two screens of explanation in a single grey block, which is unreadable
     * exactly when it is needed: you open it to answer "what is this red frame",
     * and you have to read the whole thing to find out. Built as views instead,
     * in the fork's kxkb language — a bold heading over a <b>text-width</b> yellow
     * rule (a {@code match_parent} rule inside a {@code wrap_content} vertical
     * box, which is the whole trick), bulleted marks whose lead-in is bold and
     * drawn <em>in the colour it is describing</em>, and hairlines between
     * sections. The mark's own colour is the point: the eye finds the red bullet
     * without reading a word.
     */
    private void showLegend() {
        Context context = activity;
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int side = legendDp(context, 24f);
        root.setPadding(side, legendDp(context, 4f), side, legendDp(context, 8f));

        addLegendParagraph(root, context, R.string.snooping_legend_intro, 0f);

        addLegendHeading(root, context, R.string.snooping_legend_pill, true);
        addLegendMark(root, context, R.string.snooping_legend_pill_allowed_mark, STATUS_TEXT_ALLOWED,
                R.string.snooping_legend_pill_allowed);
        addLegendMark(root, context, R.string.snooping_legend_pill_blocked_mark, STATUS_COLOR_BLOCKED,
                R.string.snooping_legend_pill_blocked);
        addLegendMark(root, context, R.string.snooping_legend_pill_narrowed_mark, statusColorForeground(),
                R.string.snooping_legend_pill_narrowed);

        addLegendHeading(root, context, R.string.snooping_legend_frame, false);
        addLegendMark(root, context, R.string.snooping_legend_frame_red_mark, CHANGED_STROKE_ALLOWED,
                R.string.snooping_legend_frame_red);
        addLegendMark(root, context, R.string.snooping_legend_frame_yellow_mark, ForkThemeUtils.getTextColor(),
                R.string.snooping_legend_frame_yellow);
        addLegendMark(root, context, R.string.snooping_legend_frame_none_mark, DETAIL_COLOR,
                R.string.snooping_legend_frame_none);

        addLegendHeading(root, context, R.string.snooping_legend_box, false);
        addLegendMark(root, context, R.string.snooping_legend_box_yellow_mark, ForkThemeUtils.getTextColor(),
                R.string.snooping_legend_box_yellow);
        addLegendMark(root, context, R.string.snooping_legend_box_red_mark, CHANGED_STROKE_ALLOWED,
                R.string.snooping_legend_box_red);
        addLegendMark(root, context, R.string.snooping_legend_box_none_mark, DETAIL_COLOR,
                R.string.snooping_legend_box_none);
        addLegendParagraph(root, context, R.string.snooping_legend_box_note, 10f);

        addLegendHeading(root, context, R.string.snooping_legend_padlock, false);
        addLegendParagraph(root, context, R.string.snooping_legend_padlock_sub, 2f);
        addLegendMark(root, context, R.string.snooping_legend_padlock_hollow_mark, ForkThemeUtils.getTextColor(),
                R.string.snooping_legend_padlock_hollow);
        addLegendMark(root, context, R.string.snooping_legend_padlock_filled_mark, ForkThemeUtils.getTextColor(),
                R.string.snooping_legend_padlock_filled);
        addLegendMark(root, context, R.string.snooping_legend_padlock_none_mark, DETAIL_COLOR,
                R.string.snooping_legend_padlock_none);
        addLegendMark(root, context, R.string.snooping_legend_padlock_ring_yellow_mark,
                ForkThemeUtils.getTextColor(), R.string.snooping_legend_padlock_ring_yellow);
        addLegendMark(root, context, R.string.snooping_legend_padlock_ring_red_mark,
                CHANGED_STROKE_ALLOWED, R.string.snooping_legend_padlock_ring_red);
        addLegendParagraph(root, context, R.string.snooping_legend_padlock_ring_note, 10f);

        addLegendHeading(root, context, R.string.snooping_legend_locking, false);
        addLegendParagraph(root, context, R.string.snooping_legend_locking_1, 2f);
        addLegendParagraph(root, context, R.string.snooping_legend_locking_2, 10f);
        addLegendParagraph(root, context, R.string.snooping_legend_locking_3, 10f);
        addLegendParagraph(root, context, R.string.snooping_legend_policy_boxes, 10f);
        addLegendParagraph(root, context, R.string.snooping_legend_policy_suspend, 10f);
        addLegendParagraph(root, context, R.string.snooping_legend_freeze_uninstall, 10f);

        NestedScrollView scroller = new NestedScrollView(context);
        scroller.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        UIUtils.presentWithYellowBorder(activity, UIUtils.yellowOnBlackDialog(activity)
                .setTitle(R.string.snooping_legend)
                .setView(scroller)
                .setPositiveButton(R.string.ok, null));
    }

    private static int legendDp(@NonNull Context context, float dp) {
        return Math.round(ForkThemeUtils.dpToPx(context, dp));
    }

    /**
     * A section heading with the fork's text-width rule under it. The rule is
     * {@code match_parent} inside a {@code wrap_content} column, so it measures to
     * the heading's own width — never the dialog's.
     */
    private static void addLegendHeading(@NonNull LinearLayout parent, @NonNull Context context,
                                         @StringRes int titleRes, boolean first) {
        if (!first) {
            // Full-bleed hairline between sections, as on the UI page.
            View hairline = new View(context);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, legendDp(context, 0.5f)));
            hlp.topMargin = legendDp(context, 20f);
            hairline.setLayoutParams(hlp);
            hairline.setBackgroundColor(ColorUtils.setAlphaComponent(ForkThemeUtils.getTextColor(), 0x50));
            parent.addView(hairline);
        }
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = legendDp(context, first ? 18f : 14f);
        column.setLayoutParams(clp);
        TextView title = new TextView(context);
        title.setText(titleRes);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setTextColor(ForkThemeUtils.getTextColor());
        column.addView(title);
        View rule = new View(context);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, legendDp(context, 2f));
        rlp.topMargin = legendDp(context, 3f);
        rule.setLayoutParams(rlp);
        rule.setBackgroundColor(ForkThemeUtils.getTextColor());
        column.addView(rule);
        parent.addView(column);
    }

    /**
     * One bulleted mark: "· <b>Red</b> — the app can use this…", with the lead-in
     * bold and in the colour it names, and the bullet hanging so wrapped lines
     * align under the text rather than under the dot.
     */
    private static void addLegendMark(@NonNull LinearLayout parent, @NonNull Context context,
                                      @StringRes int markRes, @ColorInt int markColor,
                                      @StringRes int bodyRes) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = legendDp(context, 9f);
        row.setLayoutParams(rlp);

        TextView bullet = new TextView(context);
        bullet.setText("·");
        bullet.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        bullet.setTextColor(markColor);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                legendDp(context, 14f), ViewGroup.LayoutParams.WRAP_CONTENT);
        bullet.setLayoutParams(blp);
        row.addView(bullet);

        String mark = context.getString(markRes);
        SpannableStringBuilder text = new SpannableStringBuilder(mark);
        text.setSpan(new StyleSpan(Typeface.BOLD), 0, mark.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(markColor), 0, mark.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.append(" — ").append(context.getString(bodyRes));
        TextView body = new TextView(context);
        body.setText(text);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        body.setTextColor(DETAIL_COLOR);
        body.setLineSpacing(legendDp(context, 2f), 1f);
        body.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(body);
        parent.addView(row);
    }

    private static void addLegendParagraph(@NonNull LinearLayout parent, @NonNull Context context,
                                           @StringRes int textRes, float topDp) {
        TextView paragraph = new TextView(context);
        paragraph.setText(textRes);
        paragraph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        paragraph.setTextColor(DETAIL_COLOR);
        paragraph.setLineSpacing(legendDp(context, 2f), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = legendDp(context, topDp);
        paragraph.setLayoutParams(lp);
        parent.addView(paragraph);
    }

    private void confirmBlockAll() {
        if (viewModel == null) return;
        UIUtils.presentWithYellowBorder(activity, UIUtils.yellowOnBlackDialog(activity)
                .setTitle(R.string.snooping_block_all)
                .setMessage(R.string.snooping_block_all_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.snooping_block_all, (dialog, which) -> {
                    ProgressIndicatorCompat.setVisibility(progressIndicator, true);
                    ThreadUtils.postOnBackgroundThread(() -> {
                        int blocked = viewModel.blockAllSnooping();
                        ThreadUtils.postOnMainThread(() -> {
                            if (isDetached()) return;
                            if (blocked < 0) {
                                UIUtils.displayShortToast(R.string.snooping_block_all_failed);
                            } else {
                                UIUtils.displayShortToast(R.string.snooping_blocked_count, blocked);
                            }
                            refreshDetails();
                        });
                    });
                }));
    }

    private void confirmForget() {
        if (viewModel == null) return;
        int stored = viewModel.getStoredSnoopingCount();
        if (stored == 0) {
            UIUtils.displayShortToast(R.string.snooping_nothing_saved);
            return;
        }
        UIUtils.presentWithYellowBorder(activity, UIUtils.yellowOnBlackDialog(activity)
                .setTitle(R.string.snooping_forget)
                .setMessage(getString(R.string.snooping_forget_confirm, stored))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.snooping_forget, (dialog, which) -> {
                    viewModel.forgetSnoopingSettings();
                    refreshDetails();
                }));
    }

    /**
     * Fork: what this device has that the catalogue does not.
     * <p>
     * The catalogue is hand-written and platform constants rot quietly, so rather
     * than re-deriving "what did we forget" from memory, the platform is asked:
     * every op that can hold a mode of its own and is not already listed, with
     * the mode this package is at. Also logged, so it can be harvested over adb
     * and turned into catalogue entries.
     */
    private void showMissingOps() {
        if (viewModel == null) return;
        ProgressIndicatorCompat.setVisibility(progressIndicator, true);
        ThreadUtils.postOnBackgroundThread(() -> {
            List<String> ops = viewModel.getUncataloguedOps();
            Log.d("Snooping", "Ops not in the catalogue (%d): %s", ops.size(), TextUtils.join(", ", ops));
            ThreadUtils.postOnMainThread(() -> {
                if (isDetached()) return;
                ProgressIndicatorCompat.setVisibility(progressIndicator, false);
                UIUtils.presentWithYellowBorder(activity, UIUtils.yellowOnBlackDialog(activity)
                        .setTitle(getString(R.string.snooping_missing_ops_count, ops.size()))
                        .setMessage(ops.isEmpty()
                                ? getString(R.string.snooping_missing_ops_none)
                                : TextUtils.join("\n", ops))
                        .setPositiveButton(R.string.ok, null));
            });
        });
    }

    /**
     * The box drawn around a switch whose decision is remembered. Built in code
     * rather than as a drawable resource so it takes the configurable fork theme's
     * yellow, like everything else the fork draws itself.
     */
    @NonNull
    private static Drawable rememberedBox(@NonNull Context context, @ColorInt int color) {
        GradientDrawable box = new GradientDrawable();
        box.setShape(GradientDrawable.RECTANGLE);
        box.setColor(Color.TRANSPARENT);
        box.setCornerRadius(ForkThemeUtils.dpToPx(context, 8f));
        box.setStroke(Math.round(ForkThemeUtils.dpToPx(context, 1.5f)), color);
        return box;
    }

    /**
     * The same mark for the padlock, drawn as a <b>stadium</b> rather than a box
     * (白い熊, +80).
     * <p>
     * Deliberately a different shape from {@link #rememberedBox}: the two sit in
     * neighbouring columns of the same row and mean different things — one
     * remembers the capability's state, the other remembers a device-policy lock —
     * so two 8dp rounded rectangles side by side would have read as one control
     * with two halves. The corner radius is half the view's height, which makes it
     * a ring rather than a frame; the padlock's 4dp padding is what keeps the glyph
     * clear of the curve.
     */
    private static Drawable rememberedRing(@NonNull Context context, @ColorInt int color) {
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.RECTANGLE);
        ring.setColor(Color.TRANSPARENT);
        ring.setCornerRadius(ForkThemeUtils.dpToPx(context, 16f));
        ring.setStroke(Math.round(ForkThemeUtils.dpToPx(context, 1.5f)), color);
        return ring;
    }

    /**
     * A stadium-shaped outline in the fork's pill language — the card's black
     * behind it, a hairline in the accent, fully rounded ends — with a ripple of
     * the same hue so a tap reads as a press.
     * <p>
     * Built in code rather than as a drawable resource, like everything else the
     * fork colours itself: the yellow is the configurable theme's, and the freeze
     * pill re-draws in a different accent as its own state changes.
     */
    @NonNull
    private static Drawable pillDrawable(@NonNull Context context, @ColorInt int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(Color.TRANSPARENT);
        // The framework clamps a radius past half the height, so this is a
        // stadium at whatever height the text ends up needing.
        shape.setCornerRadius(ForkThemeUtils.dpToPx(context, 100f));
        shape.setStroke(Math.round(ForkThemeUtils.dpToPx(context, 1.5f)), color);
        return new RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 0x33)), shape, null);
    }

    /** Tint a compound drawable without depending on API-gated TextViewCompat tinting. */
    private static void setLeadingIcon(@NonNull TextView view, int drawableRes, @ColorInt int color) {
        setIcon(view, drawableRes, color, true);
    }

    private static void setIcon(@NonNull TextView view, int drawableRes, @ColorInt int color,
                                boolean leading) {
        Drawable icon = null;
        if (drawableRes != 0) {
            icon = ContextCompat.getDrawable(view.getContext(), drawableRes);
            if (icon != null) {
                // mutate() or the tint leaks into every other user of the shared
                // constant state — the same drawable is on several rows here.
                icon = DrawableCompat.wrap(icon.mutate());
                DrawableCompat.setTint(icon, color);
            }
        }
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(leading ? icon : null, null,
                leading ? null : icon, null);
    }

    // ── Device-policy actions ───────────────────────────────────────────────

    /**
     * Suspend the app, or release it — one tap either way (白い熊, +81).
     * <p>
     * The last confirmation on this card, gone the same way the three boxes' went
     * in +78: what it warned about is in the row's own description, which is read
     * before the tap rather than dismissed after it.
     */
    private void toggleSuspend() {
        if (viewModel == null) return;
        String packageName = viewModel.getPackageName();
        if (packageName == null) return;
        applySuspend(packageName, !mPolicySuspended);
    }

    private void applySuspend(@NonNull String packageName, boolean suspended) {
        ProgressIndicatorCompat.setVisibility(progressIndicator, true);
        ThreadUtils.postOnBackgroundThread(() -> {
            boolean ok = DevicePolicyBridge.setSuspended(packageName, suspended);
            ThreadUtils.postOnMainThread(() -> {
                if (isDetached()) return;
                ProgressIndicatorCompat.setVisibility(progressIndicator, false);
                if (!ok) UIUtils.displayLongToast(R.string.policy_failed);
                else notifyPackageAltered(packageName);
                loadPolicyState();
            });
        });
    }

    /**
     * Tell the rest of the app that this package's state changed (白い熊, +81).
     * <p>
     * <b>Landmine.</b> The main list is not polled: it is rebuilt from broadcasts,
     * and its {@code PackageChangeReceiver} lives on the view model, so it hears
     * them even while this screen is on top. Suspension, though, produces no
     * {@code ACTION_PACKAGE_CHANGED} of its own — the platform sends
     * {@code ACTION_PACKAGES_SUSPENDED}, which did not reach us on the Mate XT —
     * so an app suspended from here kept its <em>running</em> box and its upright
     * label on the main list until something else forced a reload. Sending our own
     * {@code ACTION_PACKAGE_ALTERED} is the mechanism the rest of the fork already
     * uses for its own writes; the receiver re-reads the package live and every
     * derived flag (frozen, suspended, running) falls out correctly.
     */
    private void notifyPackageAltered(@NonNull String packageName) {
        Context context = getContext();
        if (context == null) return;
        BroadcastUtils.sendPackageAltered(context.getApplicationContext(), new String[]{packageName});
    }

    // ── The ordinary verdicts: freeze and uninstall (白い熊) ──────────────────

    /**
     * What a pill actually does, behind its "i".
     * <p>
     * The card's other controls carry their account on their face, because they
     * are switches whose meaning is not obvious from a label. These two are
     * ordinary actions with ordinary names — the explanation is worth reading
     * once and then never again, which is exactly what a dialog is for and what
     * three permanent lines of prose on the card were not.
     */
    private void showActionInfo(@StringRes int titleRes, @StringRes int noteRes,
                                @StringRes int summaryRes) {
        ForkDialog.present(ForkDialog.builder(activity)
                .setTitle(titleRes)
                .setMessage(getString(noteRes) + "\n\n" + getString(summaryRes))
                .setPositiveButton(R.string.ok, null));
    }

    /**
     * The <em>ordinary</em> freeze, sitting under the hard one.
     * <p>
     * Deliberately routed through {@link FreezeUtils} rather than through anything
     * of this page's own: that is the chokepoint the 必要 profile guards, so an app
     * 白い熊 protected is refused here for free — and it is the same call the main
     * list's snowflake makes, so the two can never mean different things.
     * <p>
     * The freeze <i>method</i> is resolved here rather than through
     * {@code AppDetailsViewModel.loadFreezeType()}: that LiveData is shared with
     * the App info tab, which is alive in the pager beside us and would answer the
     * same event by opening its own freeze dialog.
     */
    private void toggleFreeze() {
        if (viewModel == null) return;
        String packageName = viewModel.getPackageName();
        if (packageName == null) return;
        int userId = viewModel.getUserId();
        if (mAppFrozen) {
            runAppAction(() -> {
                FreezeUtils.unfreeze(packageName, userId);
                return true;
            }, R.string.failed_to_unfreeze);
            return;
        }
        if (BuildConfig.APPLICATION_ID.equals(packageName)) {
            // Freezing ourselves is refused outright by FreezeUtils, but the
            // confirmation is what App info asks, so ask it here too.
            ForkDialog.present(ForkDialog.builder(activity)
                    .setMessage(R.string.are_you_sure)
                    .setPositiveButton(R.string.yes, (d, w) -> chooseFreezeMethod(packageName, userId))
                    .setNegativeButton(R.string.no, null));
            return;
        }
        chooseFreezeMethod(packageName, userId);
    }

    /**
     * Pick the freeze method, unless the user has already said not to be asked
     * ("Skip freeze method dialog" under Settings → Rules). Same resolution order
     * as App info: the per-app remembered method, else the global default.
     */
    private void chooseFreezeMethod(@NonNull String packageName, int userId) {
        ProgressIndicatorCompat.setVisibility(progressIndicator, true);
        ThreadUtils.postOnBackgroundThread(() -> {
            Integer stored = FreezeUtils.loadFreezeMethod(packageName);
            int freezeType = stored != null ? stored : Prefs.Blocking.getDefaultFreezingMethod();
            boolean isCustom = stored != null;
            ThreadUtils.postOnMainThread(() -> {
                if (isDetached()) return;
                ProgressIndicatorCompat.setVisibility(progressIndicator, false);
                if (Prefs.Blocking.getSkipFreezeMethodDialog()) {
                    applyFreeze(packageName, userId, freezeType, isCustom);
                    return;
                }
                View view = View.inflate(activity, R.layout.item_checkbox, null);
                MaterialCheckBox checkBox = view.findViewById(R.id.checkbox);
                checkBox.setText(R.string.remember_option_for_this_app);
                checkBox.setChecked(isCustom);
                FreezeUnfreeze.getFreezeDialog(activity, freezeType)
                        .setIcon(R.drawable.ic_snowflake)
                        .setTitle(R.string.freeze)
                        .setView(view)
                        .setPositiveButton(R.string.freeze, (dialog, which, selectedItem) -> {
                            if (selectedItem == null) return;
                            applyFreeze(packageName, userId, selectedItem, checkBox.isChecked());
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            });
        });
    }

    private void applyFreeze(@NonNull String packageName, int userId,
                             @FreezeUtils.FreezeMethod int freezeType, boolean remember) {
        runAppAction(() -> {
            if (remember) {
                FreezeUtils.storeFreezeMethod(packageName, freezeType);
            } else {
                FreezeUtils.deleteFreezeMethod(packageName);
            }
            FreezeUtils.freeze(packageName, userId, freezeType);
            return true;
        }, R.string.failed_to_freeze);
    }

    /**
     * Run a whole-app write, then tell the world and re-read our own state.
     * <p>
     * The 必要 guard throws from inside {@link FreezeUtils}, so it is reported with
     * its own message rather than as a generic failure — a protected app is not a
     * broken one.
     */
    private void runAppAction(@NonNull AppAction work, @StringRes int failureRes) {
        if (viewModel == null) return;
        String packageName = viewModel.getPackageName();
        if (packageName == null) return;
        ProgressIndicatorCompat.setVisibility(progressIndicator, true);
        ThreadUtils.postOnBackgroundThread(() -> {
            boolean protectedApp = ProtectedAppsProfile.isProtected(packageName);
            boolean ok = false;
            if (!protectedApp) {
                try {
                    ok = work.run();
                } catch (Throwable th) {
                    Log.e(TAG, th);
                }
            }
            boolean finalOk = ok;
            ThreadUtils.postOnMainThread(() -> {
                if (isDetached()) return;
                ProgressIndicatorCompat.setVisibility(progressIndicator, false);
                if (protectedApp) {
                    UIUtils.displayLongToast(R.string.protected_profile_block,
                            mAppLabel != null ? mAppLabel : packageName);
                } else if (!finalOk) {
                    UIUtils.displayLongToast(failureRes,
                            mAppLabel != null ? mAppLabel : packageName);
                } else {
                    notifyPackageAltered(packageName);
                }
                loadPolicyState();
            });
        });
    }

    private interface AppAction {
        boolean run() throws Throwable;
    }

    /**
     * Uninstall, with the same confirmation App info asks — including the
     * keep-data checkbox and, for an updated system app, the "uninstall updates"
     * third option. No shortcut of our own: an irreversible action is the one
     * place on this page where a dialog earns its keep.
     */
    private void promptUninstall() {
        if (viewModel == null) return;
        String packageName = viewModel.getPackageName();
        if (packageName == null) return;
        int userId = viewModel.getUserId();
        CharSequence label = mAppLabel != null ? mAppLabel : packageName;
        if (userId != UserHandleHidden.myUserId()
                && !SelfPermissions.checkSelfOrRemotePermission(Manifest.permission.DELETE_PACKAGES)) {
            // Another user's package and no privilege to remove it ourselves —
            // hand it to the platform's own uninstaller, as App info does.
            try {
                Intent uninstallIntent = new Intent(Intent.ACTION_DELETE);
                uninstallIntent.setData(Uri.parse("package:" + packageName));
                ActivityManagerCompat.startActivity(uninstallIntent, userId);
            } catch (Throwable th) {
                UIUtils.displayLongToast("Error: " + th.getLocalizedMessage());
            }
            return;
        }
        ScrollableDialogBuilder builder = new ScrollableDialogBuilder(activity,
                mAppIsSystem ? R.string.uninstall_system_app_message : R.string.uninstall_app_message)
                .setTitle(label)
                .setCheckboxLabel(R.string.keep_data_and_app_signing_signatures)
                .setPositiveButton(R.string.uninstall, (dialog, which, keepData) ->
                        uninstall(packageName, userId, keepData, label))
                .setNegativeButton(R.string.cancel, (dialog, which, keepData) -> {
                    if (dialog != null) dialog.cancel();
                });
        if (mAppIsUpdatedSystemApp) {
            builder.setNeutralButton(R.string.uninstall_updates, (dialog, which, keepData) ->
                    uninstall(packageName, UserHandleHidden.USER_ALL, keepData, label));
        }
        builder.show();
    }

    private void uninstall(@NonNull String packageName, int userId, boolean keepData,
                           @NonNull CharSequence label) {
        ProgressIndicatorCompat.setVisibility(progressIndicator, true);
        ThreadUtils.postOnBackgroundThread(() -> {
            // The 必要 guard also sits inside PackageInstallerCompat.uninstall, which
            // simply returns false; asking first is what turns that into a message
            // that says why.
            boolean protectedApp = ProtectedAppsProfile.isProtected(packageName);
            PackageInstallerCompat installer = PackageInstallerCompat.getNewInstance();
            installer.setAppLabel(label);
            boolean uninstalled = !protectedApp && installer.uninstall(packageName, userId, keepData);
            ThreadUtils.postOnMainThread(() -> {
                if (isDetached()) return;
                ProgressIndicatorCompat.setVisibility(progressIndicator, false);
                if (protectedApp) {
                    UIUtils.displayLongToast(R.string.protected_profile_block, label);
                } else if (uninstalled) {
                    UIUtils.displayLongToast(R.string.uninstalled_successfully, label);
                    activity.finish();
                } else {
                    UIUtils.displayLongToast(R.string.failed_to_uninstall, label);
                    loadPolicyState();
                }
            });
        });
    }

    /**
     * Block this app's uninstall, or release it — one tap either way (白い熊, +78).
     * <p>
     * The confirmation is gone, along with the other two boxes'. What it said is
     * now the box's own description, where it is read <em>before</em> the tap
     * rather than dismissed after it — the same move the per-row padlock made,
     * and for the same reason: a warning that appears every single time stops
     * being read at all. Delegated, so the platform answers for itself afterwards.
     */
    private void toggleUninstallBlock(@NonNull String packageName) {
        boolean block = !mPolicyUninstallBlocked;
        runPolicy(() -> DevicePolicyBridge.setUninstallBlocked(packageName, block));
    }

    /** Stop Settings force-stopping this app or clearing its data — a 雫-side power. */
    private void toggleUserControl(@NonNull String packageName) {
        boolean disable = !mPolicyUserControlDisabled;
        runRemotePolicy(() -> PolicyApiClient.setUserControlDisabled(packageName, disable),
                ok -> PolicyLockState.setUserControlDisabled(packageName, disable));
    }

    /**
     * Block this app's accessibility service — a 雫-side power, and the sharpest
     * one on the card: an accessibility service you rely on is how you operate the
     * phone, so shutting the wrong one is felt immediately. That warning is in the
     * box's description now rather than in a dialog.
     */
    private void toggleAccessibilityBlock(@NonNull String packageName) {
        boolean block = !mPolicyAccessibilityBlocked;
        runRemotePolicy(() -> PolicyApiClient.setAccessibilityBlocked(packageName, block),
                ok -> PolicyLockState.setAccessibilityBlocked(packageName, block));
    }

    /**
     * The one box that still asks. It is not a toggle — it releases every lock on
     * the app at once, and the only way back from a mistap is to re-apply each of
     * them by hand. {@code confirmReversible} rather than {@code confirm}: 危険 is
     * for powers that can leave the phone hard to operate, and this is the way
     * back from exactly those.
     */
    private void confirmClearAllLocks(@NonNull String packageName) {
        DangerDialog.confirmReversible(activity, R.string.policy_clear_all_title,
                getString(R.string.policy_clear_all_what),
                getString(R.string.policy_clear_all_breaks),
                getString(R.string.policy_clear_all_undo),
                R.string.policy_clear_all_action,
                () -> clearAllLocks(packageName));
    }

    /**
     * Lock this capability with device policy, or release it — the whole of what
     * the row's menu used to be.
     * <p>
     * Releasing is immediate; locking asks first, because a policy-fixed
     * permission is invisible to the app it lands on — it cannot request it and
     * Settings greys it out, so an app that assumes it can re-ask may simply
     * misbehave with nothing to explain why. The confirmation is where that is
     * said. Same asymmetry as the suspend switch: giving control back needs no
     * ceremony.
     */
    private void toggleLock(@NonNull AppDetailsSnoopingItem item) {
        if (viewModel == null) return;
        String permission = item.getPermissionName();
        String packageName = viewModel.getPackageName();
        if (permission == null || packageName == null) return;
        // No confirmation in either direction (白い熊, 2026-08-01). Locking is one
        // tap and releasing is one tap, so a dialog between them would only be in
        // the way; what it used to say lives in the legend instead, where it can
        // be read once rather than dismissed every time.
        boolean lock = !item.policyLocked;
        runRowPolicy(item, () -> {
            boolean ok = DevicePolicyBridge.setPermissionLocked(packageName, permission, lock);
            // Arm the memory with the lock, and drop it with the release (白い熊,
            // +80). Remembering by default is what makes the feature worth having
            // — a lock you had to arm separately is a lock you would forget to arm
            // — and forgetting on release is what stops the enforcer putting back,
            // at the next install, exactly what you just let go. Only on a write
            // the platform actually took: the page's standing rule.
            if (ok) PolicyLockState.setPermissionLockRemembered(packageName, permission, lock);
            return ok;
        });
    }

    /**
     * Remember this row's lock, or forget it, without touching the lock itself.
     * <p>
     * The padlock's long-press, mirroring the row's own: a tap changes the thing,
     * a long-press changes whether we will put it back. Offered only where it
     * means something — a lock that exists, or a memory of one that no longer
     * does. Arming an un-locked row would be an instruction to lock it later,
     * which is not what a long-press should quietly set up.
     */
    private void togglePolicyLockRemembered(@NonNull AppDetailsSnoopingItem item) {
        if (viewModel == null) return;
        String permission = item.getPermissionName();
        String packageName = viewModel.getPackageName();
        if (permission == null || packageName == null) return;
        boolean remembered = !item.policyLockRemembered;
        PolicyLockState.setPermissionLockRemembered(packageName, permission, remembered);
        item.policyLockRemembered = remembered;
        UIUtils.displayShortToast(remembered
                ? R.string.policy_lock_now_remembered
                : R.string.policy_lock_now_forgotten);
        if (mAdapter != null) {
            int pos = mAdapter.findPosition(item);
            if (pos != RecyclerView.NO_POSITION) {
                mAdapter.notifyItemChanged(pos);
            }
        }
    }

    /**
     * A delegated write that changes <b>one row's</b> lock, and then re-reads that
     * row.
     * <p>
     * {@link #runPolicy} alone was not enough: it ends in {@link #loadPolicyState},
     * which refreshes the card's own fields and calls {@code notifyPolicyChanged},
     * and that invalidates <em>row 0 only</em>. A row's {@code policyLocked} is
     * filled in by {@link io.github.muntashirakon.AppManager.snooping.SnoopingResolver},
     * i.e. only on a full reload — so the write landed, the toast said "Applied",
     * and the padlock kept drawing its stale hollow self until the page was left
     * and re-entered.
     */
    private void runRowPolicy(@NonNull AppDetailsSnoopingItem item,
                              @NonNull java.util.concurrent.Callable<Boolean> work) {
        if (viewModel == null) return;
        String packageName = viewModel.getPackageName();
        ProgressIndicatorCompat.setVisibility(progressIndicator, true);
        ThreadUtils.postOnBackgroundThread(() -> {
            boolean ok;
            try {
                ok = Boolean.TRUE.equals(work.call());
            } catch (Exception e) {
                ok = false;
            }
            if (packageName != null) {
                // Re-read rather than assume the write's return value: the same
                // rule the rest of this page follows.
                item.refreshPolicyLockState(packageName);
            }
            boolean finalOk = ok;
            ThreadUtils.postOnMainThread(() -> {
                if (isDetached()) return;
                ProgressIndicatorCompat.setVisibility(progressIndicator, false);
                UIUtils.displayLongToast(finalOk ? R.string.policy_applied : R.string.policy_failed);
                if (mAdapter != null) {
                    int pos = mAdapter.findPosition(item);
                    if (pos != RecyclerView.NO_POSITION) {
                        mAdapter.notifyItemChanged(pos);
                    }
                }
            });
        });
    }

    /** A delegated write: runs in our own process, reports what the platform did. */
    private void runPolicy(@NonNull java.util.concurrent.Callable<Boolean> work) {
        ProgressIndicatorCompat.setVisibility(progressIndicator, true);
        ThreadUtils.postOnBackgroundThread(() -> {
            boolean ok;
            try {
                ok = Boolean.TRUE.equals(work.call());
            } catch (Exception e) {
                ok = false;
            }
            boolean finalOk = ok;
            ThreadUtils.postOnMainThread(() -> {
                if (isDetached()) return;
                ProgressIndicatorCompat.setVisibility(progressIndicator, false);
                UIUtils.displayLongToast(finalOk ? R.string.policy_applied : R.string.policy_failed);
                loadPolicyState();
            });
        });
    }

    /**
     * A 雫-side write: the reason it gives is worth showing verbatim.
     * <p>
     * {@code onApplied} runs on the worker, and <b>only when 雫 reports the real
     * {@code DevicePolicyManager} call succeeded</b> — it is where the two
     * unreadable locks record themselves in {@link PolicyLockState}. A refusal
     * must never become a tick, so it is deliberately not called on
     * {@code ok == false}, nor when 雫 did not answer at all.
     */
    private void runRemotePolicy(@NonNull java.util.concurrent.Callable<PolicyApiClient.Result> work,
                                 @Nullable androidx.core.util.Consumer<Boolean> onApplied) {
        ProgressIndicatorCompat.setVisibility(progressIndicator, true);
        ThreadUtils.postOnBackgroundThread(() -> {
            PolicyApiClient.Result result;
            try {
                result = work.call();
            } catch (Exception e) {
                result = PolicyApiClient.Result.unavailable();
            }
            if (result.ok && onApplied != null) {
                onApplied.accept(true);
            }
            PolicyApiClient.Result finalResult = result;
            ThreadUtils.postOnMainThread(() -> {
                if (isDetached()) return;
                ProgressIndicatorCompat.setVisibility(progressIndicator, false);
                if (!finalResult.reachable()) {
                    UIUtils.displayLongToast(R.string.policy_unavailable);
                } else {
                    UIUtils.displayLongToast(DangerDialog.describe(activity, finalResult.ok,
                            finalResult.error).toString());
                }
                loadPolicyState();
            });
        });
    }

    /**
     * The escape hatch, as far as this side can reach it.
     * <p>
     * 雫 owns the complete one — it can walk every permission of any package and
     * reset anything not at the default, which needs no ledger and works when
     * 応用管理 is gone. When it does not answer we still release what we can see:
     * the locks on this page's own rows, plus suspension and the uninstall block.
     * Better a partial release with an honest report than nothing at all.
     */
    private void clearAllLocks(@NonNull String packageName) {
        ProgressIndicatorCompat.setVisibility(progressIndicator, true);
        List<String> permissions = new ArrayList<>();
        List<String> remembered = new ArrayList<>();
        if (mAdapter != null) {
            for (Row row : mAdapter.mRows) {
                if (row.item == null) continue;
                String permission = row.item.getPermissionName();
                if (permission == null) continue;
                if (row.item.policyLocked) permissions.add(permission);
                // Collected separately: a row can be remembered without being
                // locked — that is exactly the state this feature exists to show
                // — and its memory has to go with everything else.
                if (row.item.policyLockRemembered) remembered.add(permission);
            }
        }
        ThreadUtils.postOnBackgroundThread(() -> {
            PolicyApiClient.Result remote = PolicyApiClient.clearAllLocks(packageName);
            int released = 0;
            if (!remote.ok) {
                for (String permission : permissions) {
                    if (DevicePolicyBridge.setPermissionLocked(packageName, permission, false)) released++;
                }
                // Forget unconditionally, even where the release itself failed
                // (白い熊, +80). The platform may refuse us, but our own memory is
                // always ours to honour — and leaving one behind would put back,
                // at the next install, precisely what this button was pressed to
                // let go.
                for (String permission : remembered) {
                    PolicyLockState.setPermissionLockRemembered(packageName, permission, false);
                }
                if (DevicePolicyBridge.isSuspended(packageName)
                        && DevicePolicyBridge.setSuspended(packageName, false)) released++;
                if (DevicePolicyBridge.isUninstallBlocked(packageName)
                        && DevicePolicyBridge.setUninstallBlocked(packageName, false)) released++;
            }
            boolean ok = remote.ok || released > 0;
            // 雫's clear_all_locks releases the two locks this side cannot read,
            // so our record of them has to go with it — otherwise their switches
            // would keep claiming a lock that no longer exists. Only when 雫
            // actually did it: a partial local release did not touch them.
            if (remote.ok) {
                PolicyLockState.clearPackage(packageName);
            }
            ThreadUtils.postOnMainThread(() -> {
                if (isDetached()) return;
                ProgressIndicatorCompat.setVisibility(progressIndicator, false);
                UIUtils.displayLongToast(ok ? R.string.policy_applied : R.string.policy_failed);
                // Clearing the locks can have lifted a suspension, so the main
                // list has to be told the same way applySuspend tells it.
                if (ok) notifyPackageAltered(packageName);
                refreshDetails();
            });
        });
    }

    private void refreshDetails() {
        if (viewModel == null) return;
        ProgressIndicatorCompat.setVisibility(progressIndicator, true);
        viewModel.triggerPackageChange();
        loadPolicyState();
    }

    @Override
    protected void search(String query, int type) {
        // Fork: the snooping tab is a fixed, grouped catalogue — not searchable.
    }

    /** The device-policy card, a group header, or a capability row. */
    private static class Row {
        @Nullable
        final SnoopingCatalog.Group group;
        @Nullable
        final AppDetailsSnoopingItem item;
        final boolean policy;

        Row(@NonNull SnoopingCatalog.Group group) {
            this.group = group;
            this.item = null;
            this.policy = false;
        }

        Row(@NonNull AppDetailsSnoopingItem item) {
            this.group = null;
            this.item = item;
            this.policy = false;
        }

        /** The one policy card, pinned at the top. */
        Row() {
            this.group = null;
            this.item = null;
            this.policy = true;
        }

        boolean isHeader() {
            return item == null;
        }
    }

    private class SnoopingRecyclerAdapter extends RecyclerView.Adapter<SnoopingRecyclerAdapter.ViewHolderBase> {
        private static final int TYPE_HEADER = 0;
        private static final int TYPE_ITEM = 1;
        private static final int TYPE_POLICY = 2;

        private final List<Row> mRows = new ArrayList<>();

        /** The policy card is row 0 whenever it exists, so this is enough. */
        @UiThread
        void notifyPolicyChanged() {
            if (!mRows.isEmpty() && mRows.get(0).policy) {
                notifyItemChanged(0);
            }
        }

        @SuppressLint("NotifyDataSetChanged")
        @UiThread
        void setItems(@NonNull List<AppDetailsItem<?>> items) {
            mRows.clear();
            SnoopingCatalog.Group currentGroup = null;
            for (AppDetailsItem<?> raw : items) {
                if (!(raw instanceof AppDetailsSnoopingItem)) continue;
                AppDetailsSnoopingItem item = (AppDetailsSnoopingItem) raw;
                SnoopingCatalog.Group group = item.capability.entry.group;
                if (group != currentGroup) {
                    mRows.add(new Row(group));
                    currentGroup = group;
                }
                mRows.add(new Row(item));
            }
            if (!mRows.isEmpty()) {
                // Pinned above every capability: the hard freeze acts on the whole
                // app rather than on one capability, and the banner has to be read
                // before anything is tapped, not after.
                //
                // Only when there is something to sit above, though — the
                // RecyclerView's empty view is driven by the item count
                // (AppDetailsFragment: recyclerView.setEmptyView), so a row that is
                // always present would silently retire "No capabilities" for the
                // rare app that has none.
                mRows.add(0, new Row());
            }
            notifyDataSetChanged();
        }

        boolean isHeader(int position) {
            return position >= 0 && position < mRows.size() && mRows.get(position).isHeader();
        }

        @Override
        public int getItemViewType(int position) {
            Row row = mRows.get(position);
            if (row.policy) return TYPE_POLICY;
            return row.isHeader() ? TYPE_HEADER : TYPE_ITEM;
        }

        @Override
        public int getItemCount() {
            return mRows.size();
        }

        @NonNull
        @Override
        public ViewHolderBase onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_POLICY) {
                return new PolicyViewHolder(inflater.inflate(R.layout.item_app_details_snooping_policy, parent, false));
            }
            if (viewType == TYPE_HEADER) {
                return new HeaderViewHolder(inflater.inflate(R.layout.item_app_details_snooping_header, parent, false));
            }
            return new ItemViewHolder(inflater.inflate(R.layout.item_app_details_snooping, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolderBase holder, int position) {
            Row row = mRows.get(position);
            if (holder instanceof PolicyViewHolder) {
                ((PolicyViewHolder) holder).bind();
            } else if (holder instanceof HeaderViewHolder && row.group != null) {
                ((HeaderViewHolder) holder).title.setText(row.group.labelRes);
            } else if (holder instanceof ItemViewHolder && row.item != null) {
                ((ItemViewHolder) holder).bind(row.item);
            }
        }

        abstract class ViewHolderBase extends RecyclerView.ViewHolder {
            ViewHolderBase(@NonNull View itemView) {
                super(itemView);
            }
        }

        class HeaderViewHolder extends ViewHolderBase {
            final TextView title;

            HeaderViewHolder(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.snooping_group_title);
            }
        }

        /**
         * The device-policy card. Two jobs, both of which have to be visible
         * before anything on this page is tapped:
         * <ol>
         *   <li>say plainly whether hard locks are available at all, and when
         *       they are not, <em>why</em> — no Device Owner on this phone, or
         *       authorised in 白い熊 雫 but not for us;</li>
         *   <li>carry the hard freeze, which is the one control here that is a
         *       verdict on the whole app rather than on one capability.</li>
         * </ol>
         */
        class PolicyViewHolder extends ViewHolderBase {
            final MaterialCardView card;
            final TextView title;
            final TextView summary;
            final View suspendRow;
            final TextView suspendLabel;
            final TextView suspendNote;
            final TextView suspendSummary;
            final MaterialSwitch suspendToggle;
            final View appActions;
            final View freezePill;
            final ImageView freezeIcon;
            final TextView freezeLabel;
            final ImageView freezeInfo;
            final View uninstallPill;
            final ImageView uninstallIcon;
            final TextView uninstallLabel;
            final ImageView uninstallInfo;
            final View controls;
            final MaterialCardView boxUninstall;
            final MaterialCardView boxUserControl;
            final MaterialCardView boxAccessibility;
            final MaterialCardView boxClear;
            /** A box's own outline, captured before any bind overrides it. */
            final int boxStrokeColor;
            final int boxStrokeWidth;

            PolicyViewHolder(@NonNull View itemView) {
                super(itemView);
                card = (MaterialCardView) itemView;
                title = itemView.findViewById(R.id.policy_title);
                summary = itemView.findViewById(R.id.policy_summary);
                suspendRow = itemView.findViewById(R.id.policy_suspend_row);
                suspendLabel = itemView.findViewById(R.id.policy_suspend_label);
                suspendNote = itemView.findViewById(R.id.policy_suspend_note);
                suspendSummary = itemView.findViewById(R.id.policy_suspend_summary);
                suspendToggle = itemView.findViewById(R.id.policy_suspend_toggle);
                appActions = itemView.findViewById(R.id.policy_app_actions);
                freezePill = itemView.findViewById(R.id.policy_freeze_pill);
                freezeIcon = itemView.findViewById(R.id.policy_freeze_icon);
                freezeLabel = itemView.findViewById(R.id.policy_freeze_label);
                freezeInfo = itemView.findViewById(R.id.policy_freeze_info);
                uninstallPill = itemView.findViewById(R.id.policy_uninstall_pill);
                uninstallIcon = itemView.findViewById(R.id.policy_uninstall_icon);
                uninstallLabel = itemView.findViewById(R.id.policy_uninstall_label);
                uninstallInfo = itemView.findViewById(R.id.policy_uninstall_info);
                controls = itemView.findViewById(R.id.policy_controls);
                boxUninstall = itemView.findViewById(R.id.policy_box_uninstall);
                boxUserControl = itemView.findViewById(R.id.policy_box_user_control);
                boxAccessibility = itemView.findViewById(R.id.policy_box_accessibility);
                boxClear = itemView.findViewById(R.id.policy_box_clear);
                // Same as the capability rows: plain fields, safe before layout.
                boxStrokeColor = boxUninstall.getStrokeColor();
                boxStrokeWidth = boxUninstall.getStrokeWidth();
            }

            void bind() {
                Context context = itemView.getContext();
                int yellow = ForkThemeUtils.getTextColor();
                title.setText(mPolicyDelegate ? R.string.policy_title_active : R.string.policy_title_inactive);
                title.setTextColor(mPolicyDelegate ? yellow : DETAIL_COLOR);
                setLeadingIcon(title, mPolicyDelegate ? R.drawable.ic_lock : R.drawable.ic_unlock,
                        mPolicyDelegate ? yellow : DETAIL_COLOR);
                summary.setTextColor(DETAIL_COLOR);
                if (mPolicyDelegate) {
                    summary.setText(R.string.policy_summary_active);
                } else {
                    summary.setText(mPolicyOwnerPresent
                            ? R.string.policy_summary_not_authorized
                            : R.string.policy_summary_no_owner);
                }
                // Every control below needs a real delegation; without one the
                // card is a status line and nothing more. Withheld rather than
                // shown-and-refused, the same rule the capability rows follow.
                boolean canSuspend = mPolicyDelegate && mPolicyCanSuspend;
                suspendRow.setVisibility(canSuspend ? View.VISIBLE : View.GONE);
                controls.setVisibility(mPolicyDelegate ? View.VISIBLE : View.GONE);
                if (canSuspend) {
                    // Suspension is the one control on this card that improves
                    // your position rather than describing an exposure (白い熊,
                    // +79), so it takes the protective half of the page's palette
                    // and not the alarming one: OFF is grey — the state every
                    // phone ships in, nothing to look at — and ON is yellow, the
                    // colour of a shutter you closed. It was red-when-suspended,
                    // which read as a warning about the very thing you had just
                    // done to protect yourself. Unlike the boxes below it, its
                    // switch needs no inversion: the label already names the
                    // action, so ON is "suspended" and that is the yellow one.
                    int accent = mPolicySuspended ? yellow : DETAIL_COLOR;
                    suspendLabel.setText(R.string.policy_suspend);
                    suspendLabel.setTextColor(accent);
                    setLeadingIcon(suspendLabel, R.drawable.ic_lock, accent);
                    // Bold, right under the row's title: what this is, not a
                    // warning about it (白い熊, 2026-08-01). Suspension is released
                    // by this same switch, so 危険 was the wrong word — what
                    // actually needs saying is that it is a harder freeze than
                    // hiding, and that nothing outside this app can lift it. It
                    // follows the accent so the row speaks with one voice: loud
                    // once the shutter is down, quiet while it is up.
                    suspendNote.setText(R.string.policy_suspend_note);
                    suspendNote.setTextColor(accent);
                    suspendSummary.setText(R.string.policy_suspend_summary);
                    suspendSummary.setTextColor(DETAIL_COLOR);
                    suspendSummary.setBackgroundTintList(ColorStateList.valueOf(
                            ColorUtils.setAlphaComponent(DETAIL_COLOR, DETAIL_CHIP_ALPHA)));
                    suspendToggle.setChecked(mPolicySuspended);
                    ColorStateList tint = ColorStateList.valueOf(accent);
                    suspendToggle.setThumbTintList(tint);
                    suspendToggle.setTrackDecorationTintList(tint);
                    suspendToggle.setTrackTintList(ColorStateList.valueOf(Color.TRANSPARENT));
                    suspendRow.setOnClickListener(v -> toggleSuspend());
                }
                bindAppActionPills(yellow);
                if (mPolicyDelegate) bindControlBoxes();
                // The card itself is not a control — only its rows are.
                card.setStrokeColor(mPolicyDelegate ? yellow : card.getStrokeColor());
                card.setOnClickListener(null);
                card.setClickable(false);
            }

            /**
             * The two ordinary verdicts, as pills (白い熊).
             * <p>
             * They were rows with a title, a bold note and a chip each, which is
             * three quarters of a screen spent on two actions and pushed the
             * capabilities themselves below the fold. A pill states what it does
             * and nothing more; the account is behind the "i", read once instead
             * of scrolled past every time.
             * <p>
             * The freeze pill still carries state, because it is the one of the
             * two that has any: grey and reading "Freeze" while the app runs,
             * yellow and reading "Unfreeze" once it is shut — the same palette
             * and the same direction as the suspend row above it. Uninstall is
             * red in every state: it is the only thing on this page that the
             * control which did it cannot undo.
             */
            void bindAppActionPills(@ColorInt int yellow) {
                Context context = itemView.getContext();
                boolean any = mCanFreeze || mCanUninstall;
                appActions.setVisibility(any ? View.VISIBLE : View.GONE);
                if (!any) return;
                // INVISIBLE, not GONE: the two pills are weighted halves, so
                // hiding one outright would stretch the other across the card.
                freezePill.setVisibility(mCanFreeze ? View.VISIBLE : View.INVISIBLE);
                if (mCanFreeze) {
                    int accent = mAppFrozen ? yellow : DETAIL_COLOR;
                    freezeLabel.setText(mAppFrozen ? R.string.unfreeze : R.string.freeze);
                    freezeLabel.setTextColor(accent);
                    freezeIcon.setImageResource(mAppFrozen
                            ? R.drawable.ic_snowflake_off : R.drawable.ic_snowflake);
                    tintPill(freezePill, freezeIcon, freezeInfo, accent, context);
                    freezePill.setOnClickListener(v -> toggleFreeze());
                    freezeInfo.setOnClickListener(v -> showActionInfo(R.string.policy_freeze,
                            R.string.policy_freeze_note, R.string.policy_freeze_summary));
                }
                uninstallPill.setVisibility(mCanUninstall ? View.VISIBLE : View.INVISIBLE);
                if (mCanUninstall) {
                    uninstallLabel.setText(R.string.uninstall);
                    uninstallLabel.setTextColor(CHANGED_STROKE_ALLOWED);
                    tintPill(uninstallPill, uninstallIcon, uninstallInfo,
                            CHANGED_STROKE_ALLOWED, context);
                    uninstallPill.setOnClickListener(v -> promptUninstall());
                    uninstallInfo.setOnClickListener(v -> showActionInfo(R.string.policy_uninstall,
                            R.string.policy_uninstall_note, R.string.policy_uninstall_summary));
                }
            }

            /**
             * One pill's outline and glyphs, in the accent it is currently
             * wearing. The background is built in code rather than as a drawable
             * resource for the same reason everything else here is: the yellow is
             * the configurable fork theme's, and the freeze pill changes colour
             * with its own state.
             */
            private void tintPill(@NonNull View pill, @NonNull ImageView icon,
                                  @NonNull ImageView info, @ColorInt int accent,
                                  @NonNull Context context) {
                pill.setBackground(pillDrawable(context, accent));
                ColorStateList tint = ColorStateList.valueOf(accent);
                ImageViewCompat.setImageTintList(icon, tint);
                ImageViewCompat.setImageTintList(info, tint);
            }

            /**
             * The four device-policy powers, as toggle boxes (白い熊, +75).
             * <p>
             * They read exactly like a capability row below, because they are the
             * same kind of thing — a switch over something the app may or may not
             * do — and one colour language across the page is worth more than a
             * distinction nobody asked for.
             * <p>
             * <b>The switch names the capability, not the lock</b> (白い熊, +78).
             * It used to be the other way round — "Block uninstall", on when the
             * block was in force — which inverted the page's own grammar: an app
             * nothing protects showed three switches at rest and no red anywhere,
             * while a locked-down one lit up like a warning. Now the label states
             * what the phone is still open to ("Can be uninstalled"), so the switch
             * is <b>right and red</b> exactly when the row rule says it should be —
             * the app can be got at right now — and <b>left and yellow with a thick
             * frame</b> once we have shut it. The frame keeps its meaning too: not
             * locked is what every fresh install gives you, so the frame marks our
             * doing, never the platform's default.
             */
            void bindControlBoxes() {
                String packageName = viewModel != null ? viewModel.getPackageName() : null;
                if (packageName == null) return;
                Context context = itemView.getContext();
                // Uninstall blocking is a delegated scope, so unlike the other
                // three it can genuinely be missing. INVISIBLE rather than GONE:
                // the grid keeps its shape and the boxes below stay where the
                // eye left them.
                boolean canBlockUninstall = mPolicyCanBlockUninstall || mPolicyUninstallBlocked;
                boxUninstall.setVisibility(canBlockUninstall ? View.VISIBLE : View.INVISIBLE);
                if (canBlockUninstall) {
                    bindBox(boxUninstall, R.string.policy_box_uninstall,
                            context.getString(R.string.policy_box_uninstall_note),
                            mPolicyUninstallBlocked, v -> toggleUninstallBlock(packageName));
                }
                // The two 雫-side powers. Offered whenever we are a delegate,
                // because only 雫 can say whether they apply — and it answers
                // with a real reason when they do not.
                bindBox(boxUserControl, R.string.policy_box_user_control,
                        noteWithUnreadable(context, R.string.policy_box_user_control_note,
                                mPolicyUserControlDisabled),
                        mPolicyUserControlDisabled, v -> toggleUserControl(packageName));
                bindBox(boxAccessibility, R.string.policy_box_accessibility,
                        noteWithUnreadable(context, R.string.policy_box_accessibility_note,
                                mPolicyAccessibilityBlocked),
                        mPolicyAccessibilityBlocked, v -> toggleAccessibilityBlock(packageName));
                bindActionBox(boxClear, R.string.policy_box_clear, R.string.policy_box_clear_note,
                        v -> confirmClearAllLocks(packageName));
            }

            /**
             * A 雫-side box's description, plus — only once the lock is on — the
             * fact that nothing can read it back.
             * <p>
             * Appended rather than substituted (白い熊, +78): the caveat used to
             * <em>replace</em> the description, so the box explained what the
             * switch did right up until you used it, and then explained something
             * else. Both facts are true at once, so both are shown at once.
             */
            @NonNull
            private CharSequence noteWithUnreadable(@NonNull Context context, @StringRes int noteRes,
                                                    boolean locked) {
                String note = context.getString(noteRes);
                return locked ? note + "\n" + context.getString(R.string.policy_box_unreadable) : note;
            }

            /**
             * One box with a switch: label, description, accent, frame.
             * <p>
             * {@code locked} is the state of the <b>lock</b>; the switch shows its
             * negation, because the label names the capability — see
             * {@link #bindControlBoxes()}. Nothing else on this page reads its
             * switch backwards, so keep the inversion here, at the one place that
             * draws it, rather than in the four call sites.
             */
            private void bindBox(@NonNull MaterialCardView box, @StringRes int labelRes,
                                 @NonNull CharSequence noteText, boolean locked,
                                 @NonNull View.OnClickListener onClick) {
                Context context = box.getContext();
                // Scoped to the box's own root — the four includes share ids.
                TextView label = box.findViewById(R.id.policy_box_label);
                TextView note = box.findViewById(R.id.policy_box_note);
                MaterialSwitch toggle = box.findViewById(R.id.policy_box_toggle);
                View action = box.findViewById(R.id.policy_box_action);
                // Red while the capability is open — the row rule, unchanged:
                // red is "the app can be got at right now", yellow is "you closed
                // something the platform leaves open".
                int accent = locked ? ForkThemeUtils.getTextColor() : CHANGED_STROKE_ALLOWED;
                label.setText(labelRes);
                label.setTextColor(accent);
                note.setText(noteText);
                note.setTextColor(DETAIL_COLOR);
                note.setBackgroundTintList(ColorStateList.valueOf(
                        ColorUtils.setAlphaComponent(DETAIL_COLOR, DETAIL_CHIP_ALPHA)));
                action.setVisibility(View.GONE);
                toggle.setVisibility(View.VISIBLE);
                toggle.setChecked(!locked);
                ColorStateList tint = ColorStateList.valueOf(accent);
                toggle.setThumbTintList(tint);
                toggle.setTrackDecorationTintList(tint);
                toggle.setTrackTintList(ColorStateList.valueOf(Color.TRANSPARENT));
                // The frame marks OUR doing, exactly as it does on a capability
                // row — and here that is the LOCKED state, since an unlocked power
                // is what every fresh install gives you. Both branches set both
                // properties, or the next bind of a released box would keep the
                // thick outline.
                if (locked) {
                    box.setStrokeColor(accent);
                    box.setStrokeWidth(Math.round(ForkThemeUtils.dpToPx(context, CHANGED_STROKE_DP)));
                } else {
                    box.setStrokeColor(boxStrokeColor);
                    box.setStrokeWidth(boxStrokeWidth);
                }
                box.setOnClickListener(onClick);
            }

            /**
             * The odd one out: "Clear all locks" is an action, not a state. It
             * keeps the box shape so the grid reads as one block, but its switch
             * slot carries an open padlock instead — a switch that sprang back
             * would be a lie about what the control does.
             */
            private void bindActionBox(@NonNull MaterialCardView box, @StringRes int labelRes,
                                       @StringRes int noteRes, @NonNull View.OnClickListener onClick) {
                TextView label = box.findViewById(R.id.policy_box_label);
                TextView note = box.findViewById(R.id.policy_box_note);
                MaterialSwitch toggle = box.findViewById(R.id.policy_box_toggle);
                ImageView action = box.findViewById(R.id.policy_box_action);
                int yellow = ForkThemeUtils.getTextColor();
                label.setText(labelRes);
                label.setTextColor(yellow);
                note.setText(noteRes);
                note.setTextColor(DETAIL_COLOR);
                note.setBackgroundTintList(ColorStateList.valueOf(
                        ColorUtils.setAlphaComponent(DETAIL_COLOR, DETAIL_CHIP_ALPHA)));
                toggle.setVisibility(View.GONE);
                action.setVisibility(View.VISIBLE);
                action.setImageResource(R.drawable.ic_unlock);
                ImageViewCompat.setImageTintList(action, ColorStateList.valueOf(yellow));
                box.setStrokeColor(boxStrokeColor);
                box.setStrokeWidth(boxStrokeWidth);
                box.setOnClickListener(onClick);
            }
        }

        class ItemViewHolder extends ViewHolderBase {
            final MaterialCardView card;
            final TextView label;
            final TextView status;
            final TextView detail;
            final ImageView lock;
            final MaterialSwitch toggle;
            /** The card's own outline, captured before we ever override it. */
            final int defaultStrokeColor;
            final int defaultStrokeWidth;
            /**
             * The padlock's own background — the borderless ripple the layout gives
             * it. Captured because the "remembered" ring replaces it, and a bind
             * that dropped the ring would otherwise leave the glyph with no touch
             * feedback for the rest of that view's life.
             */
            @Nullable
            final Drawable lockDefaultBackground;

            ItemViewHolder(@NonNull View itemView) {
                super(itemView);
                card = (MaterialCardView) itemView;
                label = itemView.findViewById(R.id.snooping_label);
                status = itemView.findViewById(R.id.snooping_status);
                detail = itemView.findViewById(R.id.snooping_detail);
                lock = itemView.findViewById(R.id.snooping_lock);
                lockDefaultBackground = lock.getBackground();
                toggle = itemView.findViewById(R.id.snooping_toggle);
                // getStrokeWidth/getStrokeColor are plain fields, safe before
                // layout — unlike getRadius(), which resolves against bounds.
                defaultStrokeColor = card.getStrokeColor();
                defaultStrokeWidth = card.getStrokeWidth();
            }

            void bind(@NonNull AppDetailsSnoopingItem item) {
                Context context = itemView.getContext();
                label.setText(item.capability.entry.labelRes);
                int state = item.getState();
                boolean allowed = SnoopingState.isAllowed(state);
                // The switch is on for both allowed states; which one it is comes
                // from the colour and from the pill, since a switch has only two
                // positions and this row has up to three.
                toggle.setChecked(allowed);
                status.setText(statusText(context, item, state));
                int statusColor = state == SnoopingState.FOREGROUND
                        ? statusColorForeground()
                        : (allowed ? STATUS_TEXT_ALLOWED : STATUS_COLOR_BLOCKED);
                status.setTextColor(statusColor);
                bindLockGlyph(item);
                status.setBackgroundTintList(ColorStateList.valueOf(
                        state == SnoopingState.ALLOWED
                                ? STATUS_CHIP_ALLOWED
                                : ColorUtils.setAlphaComponent(statusColor, STATUS_CHIP_ALPHA)));
                CharSequence detailLine = detailText(context, item);
                detail.setText(detailLine);
                // An empty detail would otherwise render as a stray chip.
                detail.setVisibility(detailLine.length() == 0 ? View.GONE : View.VISIBLE);
                detail.setTextColor(DETAIL_COLOR);
                detail.setBackgroundTintList(ColorStateList.valueOf(
                        ColorUtils.setAlphaComponent(DETAIL_COLOR, DETAIL_CHIP_ALPHA)));
                // One colour language for the whole row:
                //   red    — the app can do this right now
                //   yellow — you closed something the platform leaves open
                //   grey   — off, and off is what a fresh install gives you, so
                //            there is nothing here to look at
                boolean changed = item.isChangedFromDefault();
                int accent;
                if (state == SnoopingState.FOREGROUND) {
                    // Narrowed on purpose — our yellow, like every other decision.
                    accent = statusColorForeground();
                } else if (allowed) {
                    accent = CHANGED_STROKE_ALLOWED;
                } else if (changed) {
                    accent = ForkThemeUtils.getTextColor();
                } else {
                    accent = defaultStrokeColor;
                }
                // The thick frame is reserved for a state you chose: it catches
                // the case with no other tell — a capability that is ON by
                // default and is off only because you turned it off. Both
                // branches set both properties (recycled views).
                if (changed) {
                    card.setStrokeColor(accent);
                    card.setStrokeWidth(Math.round(ForkThemeUtils.dpToPx(context, CHANGED_STROKE_DP)));
                } else {
                    card.setStrokeColor(defaultStrokeColor);
                    card.setStrokeWidth(defaultStrokeWidth);
                }
                // The switch says the same thing: its dot and its outline take the
                // accent, and the track stays hollow so the dot is what you read.
                ColorStateList accentTint = ColorStateList.valueOf(accent);
                toggle.setThumbTintList(accentTint);
                toggle.setTrackDecorationTintList(accentTint);
                toggle.setTrackTintList(ColorStateList.valueOf(Color.TRANSPARENT));
                // A box around the switch means the decision is REMEMBERED — it is
                // replayed when the app is reinstalled or updated and travels in a
                // settings export. No box means the live state stands on its own.
                // 白い熊, 2026-08-01: this used to be a "Forget this setting" entry
                // in the row menu, which put it next to the device-policy lock and
                // implied a relationship the two do not have.
                // Red when the decision has DRIFTED — remembered as one thing,
                // enforced as another (白い熊, 2026-08-01). The store only ever
                // holds departures from the default, so a decision that no longer
                // matches the live state means something outside this page put it
                // back: Settings, the app asking again, or a write the platform
                // discarded later. The box is the only place that can say so —
                // the switch and the pill both report the live state, faithfully,
                // and so hide the disagreement rather than showing it.
                toggle.setBackground(item.storedState != null
                        ? rememberedBox(context, item.isDrifted()
                                ? CHANGED_STROKE_ALLOWED
                                : ForkThemeUtils.getTextColor())
                        : null);
                // A tap advances to the next state this row supports — two for
                // nearly everything, three for the network row.
                card.setOnClickListener(v -> applyState(item, item.nextState()));
                card.setOnLongClickListener(v -> {
                    toggleRemembered(item);
                    return true;
                });
            }

            /**
             * The padlock, on <b>every row a lock can reach</b> — not only the
             * rows already locked (白い熊, 2026-08-01). A glyph that appeared only
             * after locking left nothing to tell you a row was lockable at all,
             * so the whole feature hid behind a long-press nobody would guess.
             * <p>
             * Filled when device policy pins the row, hollow when it merely
             * could; yellow in both states, since it is one of ours. Rows with no
             * dangerous runtime permission behind them get nothing at all —
             * {@code setPermissionGrantState} has no lever there, and an
             * affordance that cannot act is what this page exists to refuse.
             */
            void bindLockGlyph(@NonNull AppDetailsSnoopingItem item) {
                boolean lockable = item.isPolicyLockable() && mPolicyDelegate && mPolicyCanLock;
                // A remembered lock keeps its padlock even when the platform no
                // longer holds one — that combination IS the thing worth seeing,
                // and hiding the glyph would hide it (白い熊, +80).
                if (!item.policyLocked && !item.policyLockRemembered && !lockable) {
                    lock.setVisibility(View.GONE);
                    lock.setOnClickListener(null);
                    lock.setOnLongClickListener(null);
                    lock.setBackground(lockDefaultBackground);
                    applyLockTouchDelegate(false);
                    return;
                }
                lock.setVisibility(View.VISIBLE);
                applyLockTouchDelegate(true);
                lock.setImageResource(item.policyLocked ? R.drawable.ic_lock : R.drawable.ic_unlock);
                ImageViewCompat.setImageTintList(lock,
                        ColorStateList.valueOf(ForkThemeUtils.getTextColor()));
                // The ring says the lock is remembered — put back if the platform
                // ever loses it. Red when it already has: remembered but hollow
                // means the lock is gone, which only a reinstall or a Device Owner
                // that went away can do, and nothing else here could report.
                // Both branches set the background (recycled views), and the
                // no-ring branch restores the ripple the layout gave it.
                if (item.policyLockRemembered) {
                    lock.setBackground(rememberedRing(itemView.getContext(), item.policyLocked
                            ? ForkThemeUtils.getTextColor()
                            : CHANGED_STROKE_ALLOWED));
                } else {
                    lock.setBackground(lockDefaultBackground);
                }
                // Long-press changes only whether we will put it back — offered
                // where there is a lock or a memory of one, and nowhere else.
                if (item.policyLocked || item.policyLockRemembered) {
                    lock.setOnLongClickListener(v -> {
                        togglePolicyLockRemembered(item);
                        return true;
                    });
                } else {
                    lock.setOnLongClickListener(null);
                }
                // Its own view, so a plain tap is enough — no hit-testing against
                // compound padding, and the card keeps its own click (which
                // advances the capability's state) untouched.
                //
                // The tap acts directly rather than opening a menu (白い熊,
                // 2026-08-01). Once remember/forget moved to the long-press and
                // the state options became redundant with tapping the card, that
                // menu held exactly one useful entry — a list of one is a worse
                // control than the switch it was wrapping. Locking still passes
                // through its confirmation; releasing does not, exactly like the
                // suspend switch, because giving control back needs no ceremony.
                lock.setOnClickListener(v -> toggleLock(item));
            }

            /**
             * Make the padlock's hit area match what the eye sees (白い熊, +83).
             * <p>
             * The glyph is a 24dp square in a 48dp column, and the row's card
             * carries a click of its own that <b>advances the capability</b>. So
             * every pixel between the two is a trap: aim at the padlock, land a
             * few dp to its right, and you have changed a permission instead of
             * locking one. Widening the view was half the answer; this is the
             * other half — the lock's rect grows over the gap towards the switch
             * and takes the row's full height, so the whole column belongs to it.
             * <p>
             * It stops short of the switch on purpose. The switch is
             * {@code clickable="false"} and its taps fall through to the card,
             * which is how a tap there advances the state — so a delegate that
             * reached into it would silently steal that gesture instead.
             * <p>
             * Cleared when the padlock is hidden, or a row with no lock at all
             * would keep swallowing taps meant for the card. Posted, because
             * {@code getHitRect} is meaningless before layout.
             */
            void applyLockTouchDelegate(boolean visible) {
                View parent = lock.getParent() instanceof View ? (View) lock.getParent() : null;
                if (parent == null) return;
                if (!visible) {
                    parent.setTouchDelegate(null);
                    return;
                }
                parent.post(() -> {
                    if (lock.getVisibility() != View.VISIBLE) {
                        parent.setTouchDelegate(null);
                        return;
                    }
                    Rect rect = new Rect();
                    lock.getHitRect(rect);
                    Context context = parent.getContext();
                    rect.left -= Math.round(ForkThemeUtils.dpToPx(context, LOCK_TOUCH_GROW_START_DP));
                    rect.right += Math.round(ForkThemeUtils.dpToPx(context, LOCK_TOUCH_GROW_END_DP));
                    rect.top = 0;
                    rect.bottom = parent.getHeight();
                    parent.setTouchDelegate(new TouchDelegate(rect, lock));
                });
            }

            void onToggle(@NonNull AppDetailsSnoopingItem item, @SnoopingState.State int state) {
                if (viewModel == null) return;
                ProgressIndicatorCompat.setVisibility(progressIndicator, true);
                ThreadUtils.postOnBackgroundThread(() -> {
                    boolean ok = viewModel.setSnoopingState(item, state);
                    ThreadUtils.postOnMainThread(() -> {
                        if (isDetached()) return;
                        if (!ok && state == SnoopingState.ALLOWED && !item.isAllowed() && item.lever == null) {
                            // It refused to turn on, so it can never snoop: the
                            // view model has marked it and a reload drops the row
                            // from the page entirely (SnoopingImmovable).
                            UIUtils.displayLongToast(R.string.snooping_cannot_enable_removed);
                            refreshDetails();
                            return;
                        }
                        ProgressIndicatorCompat.setVisibility(progressIndicator, false);
                        if (!ok) {
                            UIUtils.displayShortToast(R.string.snooping_toggle_failed);
                        }
                        int pos = getBindingAdapterPosition();
                        if (pos != RecyclerView.NO_POSITION) {
                            notifyItemChanged(pos);
                        }
                    });
                });
            }
        }

        private int findPosition(@NonNull AppDetailsSnoopingItem item) {
            for (int i = 0; i < mRows.size(); ++i) {
                if (mRows.get(i).item == item) {
                    return i;
                }
            }
            return RecyclerView.NO_POSITION;
        }

        private void applyState(@NonNull AppDetailsSnoopingItem item, @SnoopingState.State int state) {
            if (viewModel == null) return;
            ProgressIndicatorCompat.setVisibility(progressIndicator, true);
            ThreadUtils.postOnBackgroundThread(() -> {
                boolean ok = viewModel.setSnoopingState(item, state);
                ThreadUtils.postOnMainThread(() -> {
                    if (isDetached()) return;
                    ProgressIndicatorCompat.setVisibility(progressIndicator, false);
                    if (!ok) {
                        UIUtils.displayShortToast(R.string.snooping_toggle_failed);
                    }
                    int pos = findPosition(item);
                    if (pos != RecyclerView.NO_POSITION) {
                        notifyItemChanged(pos);
                    }
                });
            });
        }

        /**
         * Remember this row's decision, or stop remembering it. The live state is
         * never touched — only whether the enforcer will put it back.
         */
        private void toggleRemembered(@NonNull AppDetailsSnoopingItem item) {
            if (viewModel == null) return;
            boolean remembered = item.storedState != null;
            if (remembered) {
                viewModel.forgetSnoopingSetting(item);
            } else {
                viewModel.rememberSnoopingSetting(item);
            }
            UIUtils.displayShortToast(remembered
                    ? R.string.snooping_now_forgotten
                    : R.string.snooping_now_remembered);
            int pos = findPosition(item);
            if (pos != RecyclerView.NO_POSITION) {
                notifyItemChanged(pos);
            }
        }
    }

    @NonNull
    private CharSequence statusText(@NonNull Context context, @NonNull AppDetailsSnoopingItem item,
                                    @SnoopingState.State int state) {
        // Deliberately says nothing about the stored decision (白い熊, +13): the
        // store now holds only departures from the default, so "saved" would
        // merely restate the switch. What the eye needs instead is which rows
        // were changed at all — that is the card's highlight, in bind().
        StringBuilder sb = new StringBuilder(stateLabel(context, item, state));
        // The one case where the stored decision DOES belong here: it disagrees
        // with what the platform enforces (白い熊, 2026-08-01). The rule above
        // holds only while the two agree — then "saved" restates the switch. When
        // they differ, the switch alone reports the live state and quietly loses
        // the fact that you asked for something else and no longer have it.
        if (item.isDrifted() && item.storedState != null) {
            sb.append(" · ").append(context.getString(R.string.snooping_status_drifted,
                    stateLabel(context, item, item.storedState)));
        }
        // "Needs no permission" is a property of the capability, NOT of the row's
        // tier: keying it off TIER_UNGATED made the label vanish the moment an op
        // was given an explicit non-default mode (which promotes the row to
        // TIER_REQUESTED), so blocking a row silently changed what it claimed
        // about itself. Ask the capability instead.
        if (item.capability.isUngated()) {
            sb.append(" · ").append(context.getString(R.string.snooping_tier_ungated));
        } else if (item.tier == AppDetailsSnoopingItem.TIER_NOT_REQUESTED) {
            sb.append(" · ").append(context.getString(R.string.snooping_tier_not_requested));
        }
        return sb;
    }

    /**
     * A state's label, preferring the row's own name for it — see
     * {@link AppDetailsSnoopingItem#stateLabelRes}, whose 0 means "use the
     * generic one" and must never reach {@code getString} (it throws).
     */
    @NonNull
    private CharSequence stateLabel(@NonNull Context context, @NonNull AppDetailsSnoopingItem item,
                                    @SnoopingState.State int state) {
        int stateRes = item.stateLabelRes(state);
        if (stateRes == 0) {
            if (state == SnoopingState.FOREGROUND) {
                stateRes = R.string.snooping_state_foreground;
            } else if (state == SnoopingState.ALLOWED) {
                stateRes = R.string.snooping_state_allowed;
            } else {
                stateRes = R.string.snooping_state_blocked;
            }
        }
        return context.getString(stateRes);
    }

    @NonNull
    private CharSequence detailText(@NonNull Context context, @NonNull AppDetailsSnoopingItem item) {
        if (item.lever != null) {
            CharSequence detail = item.lever.detail(context);
            return detail != null ? detail : "";
        }
        String permission = item.getPermissionName();
        if (permission != null) {
            return permission;
        }
        String opName = item.capability.entry.opName;
        return opName != null
                ? context.getString(R.string.snooping_app_op_only, opName)
                : "";
    }
}
