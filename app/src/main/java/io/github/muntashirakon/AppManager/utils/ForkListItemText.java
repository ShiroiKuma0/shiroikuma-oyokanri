// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.utils;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.AppCompatTextView;

import io.github.muntashirakon.AppManager.R;

/**
 * Fork (白い熊, +124): the label of a row in a list dialog or a picker — it carries an icon,
 * coloured by what the row does (the black-yellow skill: every row of an action list and a
 * picker has one). The role is read from the label itself, with the same word table as the
 * menus ({@link MenuPills#roleForName}); a row whose label says nothing about an action — a
 * name, a path, a value — is "view", in the accent.
 *
 * <p>Swapped in through layouts, so no call site asks for it: the app's own copy of libcore's
 * {@code m3_alert_select_dialog_item} (every {@code SearchableItemsDialogBuilder} list) uses
 * this class; {@code fork_alert_list_item} (every {@code AlertDialog.setItems} list, through
 * {@code listItemLayout} of the dialog style) uses {@link ForkListPillText}, which adds the
 * frame. Single- and multi-choice lists keep their selection marks (白い熊, 2026-11-21).
 */
public class ForkListItemText extends AppCompatTextView {
    private CharSequence mIconFor;

    public ForkListItemText(@NonNull Context context) {
        super(context);
    }

    public ForkListItemText(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ForkListItemText(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @DrawableRes
    private static int glyph(@NonNull String role) {
        switch (role) {
            case ForkPalette.DELETE:
                return R.drawable.ic_trash_can;
            case ForkPalette.ICON_SHARE:
                return R.drawable.ic_share;
            case ForkPalette.ICON_CONNECT:
                return R.drawable.ic_play_arrow;
            case ForkPalette.ICON_MESSAGE:
                return R.drawable.ic_edit;
            case ForkPalette.ICON_CONTACT:
                return R.drawable.ic_information_circle;
            case ForkPalette.ICON_BLOCK:
                return R.drawable.ic_block;
            default:
                return R.drawable.ic_arrow_outward;
        }
    }

    /** The role of this row; a subclass colours its frame by it. */
    @NonNull
    protected String role() {
        CharSequence text = getText();
        return MenuPills.roleForName(text == null ? null : text.toString().replace(' ', '_'));
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        super.setText(text, type);
        // Rows are recycled: the icon follows the label.
        if (text != null && text.equals(mIconFor)) return;
        mIconFor = text;
        try {
            String role = role();
            Drawable d = AppCompatResources.getDrawable(getContext(), glyph(role));
            if (d == null) return;
            d = d.mutate();
            d.setTint(ForkPalette.get(getContext(), role));
            float density = getResources().getDisplayMetrics().density;
            int size = Math.round(20 * density);
            d.setBounds(0, 0, size, size);
            setCompoundDrawablesRelative(d, null, null, null);
            setCompoundDrawablePadding(Math.round(12 * density));
            onRole(role);
        } catch (Throwable ignore) {
        }
    }

    protected void onRole(@NonNull String role) {
    }
}
