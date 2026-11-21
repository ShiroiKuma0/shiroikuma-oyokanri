// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.AbsListView;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.view.menu.ForkMenuAccess;
import androidx.appcompat.view.menu.ListMenuItemView;
import androidx.appcompat.view.menu.MenuItemImpl;
import androidx.core.graphics.ColorUtils;

import io.github.muntashirakon.AppManager.R;

/**
 * Fork (白い熊, +104): the row of every popup menu — popup and long-press menus, submenus and the
 * toolbar overflow — drawn by the black-yellow theme's menu rule, so a menu shows at a glance
 * which lines can be pressed:
 * <ul>
 * <li>an action row is an outlined pill spanning the menu, its icon inside at the left and a
 * chevron at the right when it opens something;</li>
 * <li>a destructive action takes the warning colour, a disabled one is dimmed;</li>
 * <li>a toggle row stays plain and gets the outlined switch, never a checkbox;</li>
 * <li>a radio choice and the disabled title line a menu opens with stay plain.</li>
 * </ul>
 * It is swapped in through the app's own copies of AppCompat's
 * {@code abc_popup_menu_item_layout} and {@code abc_cascading_menu_item_layout} — an app layout
 * overrides the library layout of the same name — so no call site asks for it. Icons and flags
 * come from {@link MenuPills}.
 */
@SuppressLint("RestrictedApi")
public class PillMenuItemView extends ListMenuItemView {
    private boolean mReady;
    private LinearLayout mContent;
    private TextView mTitle;
    private ImageView mArrow;
    private View mDivider;
    private ColorStateList mTitleColors;
    private int mPadStart, mPadTop, mPadEnd, mPadBottom;
    private int mContentHeight;

    public PillMenuItemView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PillMenuItemView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        try {
            mContent = findViewById(androidx.appcompat.R.id.content);
            mTitle = findViewById(androidx.appcompat.R.id.title);
            mArrow = findViewById(androidx.appcompat.R.id.submenuarrow);
            mDivider = findViewById(androidx.appcompat.R.id.group_divider);
            mReady = mContent != null && mTitle != null;
            if (mReady) {
                mTitleColors = mTitle.getTextColors();
                mPadStart = mContent.getPaddingStart();
                mPadTop = mContent.getPaddingTop();
                mPadEnd = mContent.getPaddingEnd();
                mPadBottom = mContent.getPaddingBottom();
                mContentHeight = mContent.getLayoutParams().height;
                if (mDivider != null) {
                    // The stock divider is all but invisible on black: a clear line, as wide as the pills.
                    mDivider.setBackgroundColor(MenuPills.divider(getContext(), accent()));
                    ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) mDivider.getLayoutParams();
                    lp.leftMargin = dp(MenuPills.SIDE_MARGIN_DP);
                    lp.rightMargin = dp(MenuPills.SIDE_MARGIN_DP);
                    mDivider.setLayoutParams(lp);
                }
            }
        } catch (Throwable t) {
            mReady = false;
        }
    }

    @ColorInt
    private int accent() {
        return MenuPills.accent(getContext(), mTitleColors != null ? mTitleColors.getDefaultColor() : Color.WHITE);
    }

    @Override
    public void initialize(MenuItemImpl itemData, int menuType) {
        boolean icons = true;
        if (mReady) {
            try {
                icons = MenuPills.anyIcon(getContext(), ForkMenuAccess.menuOf(itemData));
                // Also keeps the icon column for a row whose icon is still missing.
                setForceShowIcon(icons);
            } catch (Throwable ignore) {
            }
        }
        super.initialize(itemData, menuType);
        if (!mReady) return;
        try {
            if (icons && itemData.getIcon() == null) {
                int res = MenuPills.iconFor(getContext(), itemData);
                if (res != 0) setIcon(AppCompatResources.getDrawable(getContext(), res));
            }
            style(itemData);
        } catch (Throwable ignore) {
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // The pill carries the ripple; the list's rectangular press highlight would paint over its round ends.
        ViewParent parent = getParent();
        if (mReady && parent instanceof AbsListView) {
            AbsListView list = (AbsListView) parent;
            if (!(list.getSelector() instanceof ColorDrawable)) {
                list.setSelector(new ColorDrawable(Color.TRANSPARENT));
            }
        }
    }

    private void style(@NonNull MenuItemImpl item) {
        Context context = getContext();
        boolean toggle = MenuPills.isToggle(item);
        boolean pill = MenuPills.isPill(item);
        boolean destructive = pill && MenuPills.isDestructive(context, item);

        // Fork (+123): frame, label and icon are three roles. The frame and the label are the
        // menu's own; the icon wears the colour of what the row does. A deleting row lends its
        // red to all three.
        int fallback = mTitleColors != null ? mTitleColors.getDefaultColor() : Color.WHITE;
        int color = destructive ? MenuPills.warning(context) : MenuPills.frame(context, fallback);
        int textColor = destructive ? color : MenuPills.text(context, fallback);
        int iconColor = destructive ? color : MenuPills.iconColor(context, item, fallback);
        if (!item.isEnabled()) {
            color = ColorUtils.setAlphaComponent(color, MenuPills.DISABLED_ALPHA);
            textColor = ColorUtils.setAlphaComponent(textColor, MenuPills.DISABLED_ALPHA);
            iconColor = ColorUtils.setAlphaComponent(iconColor, MenuPills.DISABLED_ALPHA);
        }

        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) mContent.getLayoutParams();
        int h = dp(MenuPills.SIDE_MARGIN_DP);
        int v = pill ? dp(MenuPills.PILL_MARGIN_DP) : 0;
        // A toggle row has nothing to frame, so it is only as tall as its switch needs.
        int height = toggle ? dp(MenuPills.TOGGLE_HEIGHT_DP) : mContentHeight;
        if (lp.leftMargin != h || lp.topMargin != v || lp.rightMargin != h || lp.bottomMargin != v
                || lp.height != height) {
            lp.height = height;
            lp.setMargins(h, v, h, v);
            mContent.setLayoutParams(lp);
        }
        mContent.setBackground(pill ? pill(color) : null);
        // The switch of a toggle row ends where the pills end.
        mContent.setPaddingRelative(mPadStart, mPadTop, toggle ? 0 : mPadEnd, mPadBottom);

        if (destructive || MenuPills.themed(context)) mTitle.setTextColor(textColor);
        else mTitle.setTextColor(mTitleColors);

        if (mArrow != null) {
            boolean opens = pill && MenuPills.opens(context, item);
            if (opens) {
                Drawable chevron = AppCompatResources.getDrawable(context, R.drawable.fork_menu_chevron);
                if (chevron != null) {
                    chevron = chevron.mutate();
                    chevron.setTint(textColor);
                    mArrow.setImageDrawable(chevron);
                }
            }
            mArrow.setVisibility(opens ? View.VISIBLE : View.GONE);
        }

        for (int i = 0; i < mContent.getChildCount(); i++) {
            View child = mContent.getChildAt(i);
            if (child instanceof CheckBox) {
                if (child.getTag() == null) {
                    CheckBox cb = (CheckBox) child;
                    int on = MenuPills.themed(context)
                            ? ForkPalette.get(context, ForkPalette.SWITCH_ON) : accent();
                    cb.setButtonDrawable(new ForkSwitchDrawable(on, MenuPills.switchOff(context, on),
                            dp(44), dp(24), dp(2)));
                    cb.setButtonTintList(null);
                    cb.setBackground(null);
                    // A long label stops short of the switch.
                    ViewGroup.MarginLayoutParams clp = (ViewGroup.MarginLayoutParams) cb.getLayoutParams();
                    clp.setMarginStart(dp(8));
                    cb.setLayoutParams(clp);
                    cb.setTag(Boolean.TRUE);
                }
            } else if (child.getId() == androidx.appcompat.R.id.icon) {
                // Clear of the round end of the pill, and not squeezed in a low row.
                ViewGroup.MarginLayoutParams ilp = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
                if (ilp.topMargin != dp(2)) {
                    ilp.setMarginStart(dp(14));
                    ilp.setMarginEnd(-dp(6));
                    ilp.topMargin = dp(2);
                    ilp.bottomMargin = dp(2);
                    child.setLayoutParams(ilp);
                }
                // The icon is coloured by what the row does.
                if (child instanceof ImageView) {
                    ((ImageView) child).setImageTintList(ColorStateList.valueOf(iconColor));
                }
            }
        }
    }

    @NonNull
    private Drawable pill(@ColorInt int color) {
        float radius = dp(100);
        GradientDrawable frame = new GradientDrawable();
        frame.setShape(GradientDrawable.RECTANGLE);
        frame.setCornerRadius(radius);
        frame.setColor(Color.TRANSPARENT);
        frame.setStroke(Math.max(1, Math.round(1.25f * getResources().getDisplayMetrics().density)), color);

        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadius(radius);
        mask.setColor(Color.WHITE);

        int ripple = ColorUtils.setAlphaComponent(color, Math.round(Color.alpha(color) * 0.3f));
        return new RippleDrawable(ColorStateList.valueOf(ripple), frame, mask);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
