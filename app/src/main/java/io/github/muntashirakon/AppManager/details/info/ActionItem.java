// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.details.info;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.google.android.material.button.MaterialButton;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.utils.appearance.ColorCodes;

class ActionItem {
    @StringRes
    private final int mTitleRes;
    @DrawableRes
    private final int mIconRes;
    private View.OnClickListener mOnClickListener;
    private View.OnLongClickListener mOnLongClickListener;

    public ActionItem(@StringRes int titleRes, @DrawableRes int iconRes) {
        mTitleRes = titleRes;
        mIconRes = iconRes;
    }

    public ActionItem setOnClickListener(View.OnClickListener clickListener) {
        mOnClickListener = clickListener;
        return this;
    }

    public ActionItem setOnLongClickListener(View.OnLongClickListener longClickListener) {
        mOnLongClickListener = longClickListener;
        return this;
    }

    public MaterialButton toActionButton(@NonNull Context context, @NonNull ViewGroup parent) {
        MaterialButton button = (MaterialButton) LayoutInflater.from(context).inflate(R.layout.item_app_info_action, parent, false);
        // Fork (白い熊, +057): a rounded outlined pill in the fork's ink, not a filled connected
        // button-group segment. Half-height corner radius = a stadium; the framework clamps it.
        int ink = io.github.muntashirakon.AppManager.utils.ForkThemeUtils.getTextColor();
        float density = context.getResources().getDisplayMetrics().density;
        button.setBackgroundTintList(ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
        button.setCornerRadius(Math.round(density * 1000));
        button.setStrokeColor(ColorStateList.valueOf(ink));
        button.setStrokeWidth(Math.round(density * 1.5f));
        button.setTextColor(ink);
        button.setIconTint(ColorStateList.valueOf(ink));
        button.setRippleColor(ColorStateList.valueOf((ink & 0x00FFFFFF) | 0x33000000));
        // Fork (白い熊, +057): compact pills — the default MaterialButton is far too tall/wide here.
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        int padH = Math.round(density * 14);
        int padV = Math.round(density * 6);
        button.setPadding(padH, padV, padH, padV);
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
        button.setIconSize(Math.round(density * 18));
        button.setIconPadding(Math.round(density * 4));
        button.setText(mTitleRes);
        button.setIconResource(mIconRes);
        if (mOnClickListener != null) {
            button.setOnClickListener(mOnClickListener);
        }
        if (mOnLongClickListener != null) {
            button.setOnLongClickListener(mOnLongClickListener);
        }
        return button;
    }
}
