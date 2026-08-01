// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.details;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.UiThread;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.GridLayoutManager;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.details.struct.AppDetailsItem;
import io.github.muntashirakon.AppManager.details.struct.AppDetailsSnoopingItem;
import io.github.muntashirakon.AppManager.devicepolicy.DangerDialog;
import io.github.muntashirakon.AppManager.devicepolicy.DevicePolicyBridge;
import io.github.muntashirakon.AppManager.devicepolicy.PolicyApiClient;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.snooping.SnoopingCatalog;
import io.github.muntashirakon.AppManager.snooping.SnoopingEnforcer;
import io.github.muntashirakon.AppManager.snooping.SnoopingPrefs;
import io.github.muntashirakon.AppManager.snooping.SnoopingState;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;
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
     * …and in red when the change went the dangerous way — switched ON where the
     * platform's own default is off. Yellow marks the protective direction.
     */
    @ColorInt
    private static final int CHANGED_STROKE_ALLOWED = 0xFFFF0028;

    private SnoopingRecyclerAdapter mAdapter;
    private boolean mCanEnforce;

    // ── Device-policy state, read from the platform and from 白い熊 雫 ─────────
    /** We hold delegated scopes, i.e. hard locks are actually available. */
    private boolean mPolicyDelegate;
    /** 雫 answered and is Device Owner — so the powers exist but may not be ours yet. */
    private boolean mPolicyOwnerPresent;
    private boolean mPolicySuspended;
    private boolean mPolicyUninstallBlocked;
    /** Resolved on the worker with the rest, so no bind() ever makes a binder call. */
    private boolean mPolicyCanLock;
    private boolean mPolicyCanSuspend;
    private boolean mPolicyCanBlockUninstall;
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
        mAdapter = new SnoopingRecyclerAdapter();
        recyclerView.setAdapter(mAdapter);
        // Group headings must span the whole row when the list goes multi-column
        // (it does on the tri-fold, at 450dp per column).
        if (recyclerView.getLayoutManager() instanceof GridLayoutManager) {
            GridLayoutManager glm = (GridLayoutManager) recyclerView.getLayoutManager();
            glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
                @Override
                public int getSpanSize(int position) {
                    // Read the span count at call time: it is auto-fitted and
                    // changes when the fold state changes.
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
            ThreadUtils.postOnMainThread(() -> {
                if (isDetached()) return;
                mPolicyDelegate = delegate;
                mPolicyOwnerPresent = ownerPresent;
                mPolicySuspended = suspended;
                mPolicyUninstallBlocked = uninstallBlocked;
                mPolicyCanLock = canLock;
                mPolicyCanSuspend = canSuspend;
                mPolicyCanBlockUninstall = canBlockUninstall;
                if (mAdapter != null) mAdapter.notifyPolicyChanged();
            });
        });
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

        addLegendHeading(root, context, R.string.snooping_legend_locking, false);
        addLegendParagraph(root, context, R.string.snooping_legend_locking_1, 2f);
        addLegendParagraph(root, context, R.string.snooping_legend_locking_2, 10f);
        addLegendParagraph(root, context, R.string.snooping_legend_locking_3, 10f);

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
     * A tappable pill in the fork's language — black fill, yellow hairline, fully
     * rounded. Same silhouette as the dialog list items, built in code so it takes
     * the configurable theme's yellow rather than a fixed one.
     */
    @NonNull
    private static Drawable pillBackground(@NonNull Context context) {
        GradientDrawable pill = new GradientDrawable();
        pill.setShape(GradientDrawable.RECTANGLE);
        pill.setColor(Color.BLACK);
        pill.setCornerRadius(ForkThemeUtils.dpToPx(context, 24f));
        pill.setStroke(Math.round(ForkThemeUtils.dpToPx(context, 1f)), ForkThemeUtils.getTextColor());
        return pill;
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

    private void toggleSuspend() {
        if (viewModel == null) return;
        String packageName = viewModel.getPackageName();
        if (packageName == null) return;
        if (mPolicySuspended) {
            // Releasing only gives control back, so it needs no ceremony.
            applySuspend(packageName, false);
            return;
        }
        DangerDialog.confirmReversible(activity, R.string.policy_suspend_title,
                getString(R.string.policy_suspend_what),
                getString(R.string.policy_suspend_breaks),
                getString(R.string.policy_suspend_undo),
                R.string.policy_suspend_action,
                () -> applySuspend(packageName, true));
    }

    private void applySuspend(@NonNull String packageName, boolean suspended) {
        ProgressIndicatorCompat.setVisibility(progressIndicator, true);
        ThreadUtils.postOnBackgroundThread(() -> {
            boolean ok = DevicePolicyBridge.setSuspended(packageName, suspended);
            ThreadUtils.postOnMainThread(() -> {
                if (isDetached()) return;
                ProgressIndicatorCompat.setVisibility(progressIndicator, false);
                if (!ok) UIUtils.displayLongToast(R.string.policy_failed);
                loadPolicyState();
            });
        });
    }

    /**
     * The rest of the device-policy controls. A menu rather than more rows on the
     * card: they are rarely used, and each one is dangerous enough that having to
     * go looking for it is a feature.
     */
    private void showPolicyMenu() {
        if (viewModel == null) return;
        String packageName = viewModel.getPackageName();
        if (packageName == null) return;
        List<CharSequence> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (mPolicyCanBlockUninstall || mPolicyUninstallBlocked) {
            boolean blocked = mPolicyUninstallBlocked;
            labels.add(getString(blocked ? R.string.policy_release_action : R.string.policy_uninstall_block));
            actions.add(() -> {
                if (blocked) {
                    runPolicy(() -> DevicePolicyBridge.setUninstallBlocked(packageName, false));
                } else {
                    DangerDialog.confirmReversible(activity, R.string.policy_uninstall_title,
                            getString(R.string.policy_uninstall_what),
                            getString(R.string.policy_uninstall_breaks),
                            getString(R.string.policy_uninstall_undo),
                            R.string.policy_uninstall_block,
                            () -> runPolicy(() -> DevicePolicyBridge.setUninstallBlocked(packageName, true)));
                }
            });
        }

        // The 雫-side powers. Offered unconditionally because only 雫 can say
        // whether they apply, and it answers with a real reason when they do not.
        labels.add(getString(R.string.policy_user_control));
        actions.add(() -> DangerDialog.confirmReversible(activity, R.string.policy_user_control_title,
                getString(R.string.policy_user_control_what),
                getString(R.string.policy_user_control_breaks),
                getString(R.string.policy_user_control_undo),
                R.string.policy_user_control,
                () -> runRemotePolicy(() -> PolicyApiClient.setUserControlDisabled(packageName, true))));

        labels.add(getString(R.string.policy_accessibility_block));
        actions.add(() -> DangerDialog.confirm(activity, R.string.policy_accessibility_title,
                getString(R.string.policy_accessibility_what),
                getString(R.string.policy_accessibility_breaks),
                getString(R.string.policy_accessibility_undo),
                R.string.policy_accessibility_block,
                () -> runRemotePolicy(() -> PolicyApiClient.setAccessibilityBlocked(packageName, true))));

        // Bold: this is the way back, and the one entry someone already stuck
        // needs to find first.
        labels.add(bold(getString(R.string.policy_clear_all)));
        actions.add(() -> DangerDialog.confirm(activity, R.string.policy_clear_all_title,
                getString(R.string.policy_clear_all_what),
                getString(R.string.policy_clear_all_breaks),
                getString(R.string.policy_clear_all_undo),
                R.string.policy_clear_all_action,
                () -> clearAllLocks(packageName)));

        // Pills rather than setItems' bare lines: these entries are device-policy
        // actions sitting in a plain list, and at body-list size they read like
        // menu filler. ArrayAdapter binds onto the pill layout's TextView root, so
        // the Spannable above keeps its weight.
        UIUtils.presentWithYellowBorder(activity, UIUtils.yellowOnBlackDialog(activity)
                .setTitle(R.string.policy_more_title)
                .setAdapter(new ArrayAdapter<>(activity, R.layout.item_dialog_pill, labels),
                        (dialog, which) -> actions.get(which).run())
                .setNegativeButton(R.string.cancel, null));
    }

    /** A menu entry that should carry more weight than the ones around it. */
    @NonNull
    private static CharSequence bold(@NonNull CharSequence text) {
        SpannableString out = new SpannableString(text);
        out.setSpan(new StyleSpan(Typeface.BOLD), 0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return out;
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
        runRowPolicy(item, () ->
                DevicePolicyBridge.setPermissionLocked(packageName, permission, !item.policyLocked));
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

    /** A 雫-side write: the reason it gives is worth showing verbatim. */
    private void runRemotePolicy(@NonNull java.util.concurrent.Callable<PolicyApiClient.Result> work) {
        ProgressIndicatorCompat.setVisibility(progressIndicator, true);
        ThreadUtils.postOnBackgroundThread(() -> {
            PolicyApiClient.Result result;
            try {
                result = work.call();
            } catch (Exception e) {
                result = PolicyApiClient.Result.unavailable();
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
        if (mAdapter != null) {
            for (Row row : mAdapter.mRows) {
                if (row.item != null && row.item.policyLocked) {
                    String permission = row.item.getPermissionName();
                    if (permission != null) permissions.add(permission);
                }
            }
        }
        ThreadUtils.postOnBackgroundThread(() -> {
            PolicyApiClient.Result remote = PolicyApiClient.clearAllLocks(packageName);
            int released = 0;
            if (!remote.ok) {
                for (String permission : permissions) {
                    if (DevicePolicyBridge.setPermissionLocked(packageName, permission, false)) released++;
                }
                if (DevicePolicyBridge.isSuspended(packageName)
                        && DevicePolicyBridge.setSuspended(packageName, false)) released++;
                if (DevicePolicyBridge.isUninstallBlocked(packageName)
                        && DevicePolicyBridge.setUninstallBlocked(packageName, false)) released++;
            }
            boolean ok = remote.ok || released > 0;
            ThreadUtils.postOnMainThread(() -> {
                if (isDetached()) return;
                ProgressIndicatorCompat.setVisibility(progressIndicator, false);
                UIUtils.displayLongToast(ok ? R.string.policy_applied : R.string.policy_failed);
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
            final TextView more;

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
                more = itemView.findViewById(R.id.policy_more);
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
                more.setVisibility(mPolicyDelegate ? View.VISIBLE : View.GONE);
                if (canSuspend) {
                    suspendLabel.setText(R.string.policy_suspend);
                    suspendLabel.setTextColor(mPolicySuspended ? DangerDialog.DANGER_RED : yellow);
                    setLeadingIcon(suspendLabel, R.drawable.ic_lock,
                            mPolicySuspended ? DangerDialog.DANGER_RED : yellow);
                    // Bold, right under the row's title: what this is, not a
                    // warning about it (白い熊, 2026-08-01). Suspension is released
                    // by this same switch, so 危険 was the wrong word — what
                    // actually needs saying is that it is a harder freeze than
                    // hiding, and that nothing outside this app can lift it.
                    suspendNote.setText(R.string.policy_suspend_note);
                    suspendNote.setTextColor(yellow);
                    suspendSummary.setText(R.string.policy_suspend_summary);
                    suspendSummary.setTextColor(DETAIL_COLOR);
                    suspendSummary.setBackgroundTintList(ColorStateList.valueOf(
                            ColorUtils.setAlphaComponent(DETAIL_COLOR, DETAIL_CHIP_ALPHA)));
                    suspendToggle.setChecked(mPolicySuspended);
                    ColorStateList tint = ColorStateList.valueOf(
                            mPolicySuspended ? DangerDialog.DANGER_RED : yellow);
                    suspendToggle.setThumbTintList(tint);
                    suspendToggle.setTrackDecorationTintList(tint);
                    suspendToggle.setTrackTintList(ColorStateList.valueOf(Color.TRANSPARENT));
                    suspendRow.setOnClickListener(v -> toggleSuspend());
                }
                more.setText(R.string.policy_more);
                more.setTextColor(yellow);
                more.setBackground(pillBackground(context));
                more.setOnClickListener(v -> showPolicyMenu());
                // The card itself is not a control — only its rows are.
                card.setStrokeColor(mPolicyDelegate ? yellow : card.getStrokeColor());
                card.setOnClickListener(null);
                card.setClickable(false);
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

            ItemViewHolder(@NonNull View itemView) {
                super(itemView);
                card = (MaterialCardView) itemView;
                label = itemView.findViewById(R.id.snooping_label);
                status = itemView.findViewById(R.id.snooping_status);
                detail = itemView.findViewById(R.id.snooping_detail);
                lock = itemView.findViewById(R.id.snooping_lock);
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
                if (!item.policyLocked && !lockable) {
                    lock.setVisibility(View.GONE);
                    lock.setOnClickListener(null);
                    return;
                }
                lock.setVisibility(View.VISIBLE);
                lock.setImageResource(item.policyLocked ? R.drawable.ic_lock : R.drawable.ic_unlock);
                ImageViewCompat.setImageTintList(lock,
                        ColorStateList.valueOf(ForkThemeUtils.getTextColor()));
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
