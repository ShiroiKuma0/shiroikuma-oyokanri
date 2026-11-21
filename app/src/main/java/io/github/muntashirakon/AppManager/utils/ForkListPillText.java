// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.utils;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Fork (白い熊, +124): a row of an {@code AlertDialog.setItems} list. Such a row always does
 * something when tapped, so it is drawn like a menu's action row: a full-width outlined pill
 * (1.25 dp) with its icon inside at the left; a deleting row is red all over.
 * See {@link ForkListItemText}.
 */
public class ForkListPillText extends ForkListItemText {
    public ForkListPillText(@NonNull Context context) {
        super(context);
    }

    public ForkListPillText(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ForkListPillText(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onRole(@NonNull String role) {
        boolean destructive = ForkPalette.DELETE.equals(role);
        int frame = ForkPalette.get(getContext(), destructive ? ForkPalette.DELETE : ForkPalette.MENU_FRAME);
        setBackground(Pills.frame(getContext(), false, frame));
        setTextColor(destructive ? frame : ForkPalette.get(getContext(), ForkPalette.DIALOG_TEXT));
        float d = getResources().getDisplayMetrics().density;
        setPaddingRelative(Math.round(14 * d), Math.round(10 * d), Math.round(14 * d), Math.round(10 * d));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // The pill carries the ripple; the list's rectangular press highlight would cut its round ends.
        try {
            android.view.ViewParent row = getParent();
            android.view.ViewParent list = row == null ? null : row.getParent();
            if (list instanceof android.widget.AbsListView) {
                android.widget.AbsListView l = (android.widget.AbsListView) list;
                if (!(l.getSelector() instanceof android.graphics.drawable.ColorDrawable)) {
                    l.setSelector(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                }
                if (l instanceof android.widget.ListView) ((android.widget.ListView) l).setDivider(null);
            }
        } catch (Throwable ignore) {
        }
    }
}
