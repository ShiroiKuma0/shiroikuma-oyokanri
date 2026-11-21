// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.resources.MaterialAttributes;
import com.leinardi.android.speeddial.FabWithLabelView;
import com.leinardi.android.speeddial.SpeedDialActionItem;
import com.leinardi.android.speeddial.SpeedDialOverlayLayout;
import com.leinardi.android.speeddial.SpeedDialView;

import io.github.muntashirakon.util.UiUtils;

// Based on https://github.com/zhanghai/MaterialFiles/blob/9a6db781087f9e3b6345af15c735c33b305d24c2/app/src/main/java/me/zhanghai/android/files/ui/ThemedSpeedDialView.kt
// TODO: 26/6/23 Replace this with a custom implementation by removing all those bad practices that this library follows
public class FloatingActionButtonGroup extends SpeedDialView {
    public FloatingActionButtonGroup(Context context) {
        this(context, null);
    }

    public FloatingActionButtonGroup(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FloatingActionButtonGroup(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        FloatingActionButton fab = getMainFab();
        int margin = UiUtils.dpToPx(getContext(), 16);
        MarginLayoutParams layoutParams = (MarginLayoutParams) fab.getLayoutParams();
        layoutParams.setMargins(margin, margin, margin, margin);
        fab.setLayoutParams(layoutParams);
        fab.setUseCompatPadding(false);
        // Fork (白い熊, +124): never a filled slab. The speed dial builds its buttons in code, so the
        // theme's view inflater cannot restyle them: ground inside, a ring in the accent, the glyph
        // in the accent — red while it is open and its tap means "close". libcore cannot see the
        // app's colour roles, so it asks the theme attributes those roles feed.
        int ground = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface);
        setMainFabOpenedBackgroundColor(ground);
        setMainFabOpenedIconColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError));
        setMainFabClosedBackgroundColor(ground);
        setMainFabClosedIconColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary));
        ring(fab, 3f);
    }

    /** Fork (+124): an accent ring as the button's foreground; the button itself stays the ground. */
    private void ring(@NonNull FloatingActionButton fab, float widthDp) {
        try {
            fab.setCompatElevation(0);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
                ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                ring.setColor(Color.TRANSPARENT);
                ring.setStroke(Math.max(1, UiUtils.dpToPx(getContext(), widthDp)),
                        MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary));
                fab.setForeground(ring);
            }
        } catch (Throwable ignore) {
        }
    }

    @SuppressLint("RestrictedApi")
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        SpeedDialOverlayLayout overlayLayout = getOverlayLayout();
        if (overlayLayout != null) {
            overlayLayout.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    @SuppressLint("RestrictedApi")
    @Nullable
    @Override
    public FabWithLabelView addActionItem(SpeedDialActionItem actionItem, int position, boolean animate) {
        // Fork (+124): outlined like the main button, and the label in the theme's text colour.
        int fabImageTintColor = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary);
        int fabBackgroundColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface);
        int labelColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface);
        int labelBackgroundColor = Color.TRANSPARENT;
        Context context = getContext();
        SpeedDialActionItem item = new SpeedDialActionItem.Builder(actionItem.getId(), actionItem.getFabImageDrawable(context))
                .setLabel(actionItem.getLabel(context))
                .setFabImageTintColor(fabImageTintColor)
                .setFabBackgroundColor(fabBackgroundColor)
                .setLabelColor(labelColor)
                .setLabelBackgroundColor(labelBackgroundColor)
                .setLabelClickable(actionItem.isLabelClickable())
                .setTheme(actionItem.getTheme())
                .create();
        FabWithLabelView fabWrapper = super.addActionItem(item, position, animate);
        if (fabWrapper == null) {
            return null;
        }
        FloatingActionButton fab = fabWrapper.getFab();
        int margin = UiUtils.dpToPx(getContext(), 16);
        MarginLayoutParams layoutParams = (MarginLayoutParams) fab.getLayoutParams();
        layoutParams.setMargins(margin, 0, margin, 0);
        fab.setLayoutParams(layoutParams);
        fab.setUseCompatPadding(false);
        ring(fab, 1.25f);
        CardView labelBackground = fabWrapper.getLabelBackground();
        labelBackground.setUseCompatPadding(false);
        labelBackground.setContentPadding(0, 0, 0, 0);
        labelBackground.setForeground(null);
        TextView label = (TextView) labelBackground.getChildAt(0);
        int textAppearance = MaterialAttributes.resolveOrThrow(this, com.google.android.material.R.attr.textAppearanceLabelLarge);
        TextViewCompat.setTextAppearance(label, textAppearance);
        return fabWrapper;
    }
}
