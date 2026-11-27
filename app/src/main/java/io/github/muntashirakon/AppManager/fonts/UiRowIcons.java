// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.fonts;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.content.res.AppCompatResources;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.utils.ForkPalette;
import io.github.muntashirakon.AppManager.utils.MenuPills;

/**
 * Fork (白い熊, +123): every row of the UI screen carries an icon, coloured by what the row does
 * (the palette's icon roles — the same colours as in the menus). The icon is the label's start
 * drawable, so no row layout had to grow a column.
 *
 * <p>A row built from a string gets both from that string's <em>name</em>
 * ({@link #auto}): the role through {@link MenuPills#roleForName} and the glyph from
 * {@link #GLYPHS}. A new row therefore needs nothing — unless its name says nothing, in which
 * case give it a line in {@link #GLYPHS} or call {@link #set} with what it does.
 */
final class UiRowIcons {
    private UiRowIcons() {
    }

    /** {name fragment, drawable} — first match wins. */
    private static final Object[][] GLYPHS = {
            {"purge", R.drawable.ic_trash_can}, {"reset", R.drawable.ic_undo}, {"delete", R.drawable.ic_trash_can},
            {"share", R.drawable.ic_share}, {"export", R.drawable.ic_file_export}, {"eim", R.drawable.ic_file_export},
            {"migration", R.drawable.ic_package}, {"import", R.drawable.ic_file_import},
            {"token", R.drawable.ic_shield_key}, {"automation", R.drawable.ic_autorenew},
            {"reopen", R.drawable.ic_restore}, {"roaming", R.drawable.ic_transit_connection},
            {"wifi", R.drawable.ic_wifi}, {"lookup", R.drawable.ic_eye}, {"upload", R.drawable.ic_file_export},
            {"rescan", R.drawable.ic_history}, {"vt_", R.drawable.ic_vt},
            {"stored", R.drawable.ic_database}, {"keep", R.drawable.ic_history}, {"diag", R.drawable.ic_pulse},
            {"settings", R.drawable.ic_settings}, {"color", R.drawable.ic_palette_swatch},
            {"font", R.drawable.ic_font_download},
    };

    @DrawableRes
    static int glyphFor(@NonNull String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        for (Object[] pair : GLYPHS) {
            if (n.contains((String) pair[0])) return (Integer) pair[1];
        }
        return R.drawable.ic_tune;
    }

    /** Icon and role from the name of the string the row shows. */
    static void auto(@NonNull TextView label, @StringRes int textRes) {
        try {
            String name = label.getResources().getResourceEntryName(textRes);
            set(label, glyphFor(name), roleOf(name));
        } catch (Throwable ignore) {
        }
    }

    @NonNull
    static String roleOf(@NonNull String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        // On this page "roaming" and "wifi" are about the connection, and a token guards one.
        if (n.contains("roaming") || n.contains("wifi") || n.contains("automation") || n.contains("lookup")) {
            return ForkPalette.ICON_CONNECT;
        }
        if (n.contains("token")) return ForkPalette.ICON_BLOCK;
        return MenuPills.roleForName(name);
    }

    static void set(@NonNull TextView label, @DrawableRes int icon, @NonNull String role) {
        try {
            labelIcon(label, icon, ForkPalette.get(label.getContext(), role), 18, null);
        } catch (Throwable ignore) {
        }
    }

    /**
     * Fork (白い熊, 2026-11-27): an icon as the label's start drawable, <b>sized by the label</b> —
     * 1.25 × the label's text size, never under {@code floorDp} (18 for a row, 0 for a chip); the
     * gap is half the icon. The size is read again at every layout pass, so a font pass that
     * runs after the row was built is followed. {@code gutter}, when given, hears what the icon
     * and its gap take at the label's start (a heading's underline starts after it).
     */
    static void labelIcon(@NonNull TextView label, @DrawableRes int icon, int color, int floorDp,
                          @androidx.annotation.Nullable GutterListener gutter) {
        Context context = label.getContext();
        Drawable d = AppCompatResources.getDrawable(context, icon);
        if (d == null) return;
        final Drawable glyph = d.mutate();
        glyph.setTint(color);
        final float density = context.getResources().getDisplayMetrics().density;
        final int floor = Math.round(floorDp * density);
        Runnable size = () -> {
            int px = Math.max(floor, Math.round(1.25f * label.getTextSize()));
            Object drawn = label.getTag(R.id.fork_label_icon);
            if (drawn instanceof Integer && (Integer) drawn == px) return;
            label.setTag(R.id.fork_label_icon, px);
            glyph.setBounds(0, 0, px, px);
            Drawable[] old = label.getCompoundDrawablesRelative();
            label.setCompoundDrawablesRelative(glyph, old[1], old[2], old[3]);
            label.setCompoundDrawablePadding(px / 2);
            if (gutter != null) gutter.onGutter(px + px / 2);
        };
        label.setTag(R.id.fork_label_icon, null);
        size.run();
        label.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> size.run());
    }

    interface GutterListener {
        void onGutter(int px);
    }

    /**
     * The rows that come from the shared element layouts: a font element's heading and its
     * family / weight / size captions, a slider element's heading, a colour row.
     */
    static void sweep(@NonNull View root) {
        if (root instanceof TextView) {
            TextView tv = (TextView) root;
            if (tv.getCompoundDrawablesRelative()[0] != null) return;
            int id = tv.getId();
            if (id == R.id.cr_label) set(tv, R.drawable.ic_palette_swatch, ForkPalette.ICON_VIEW);
            else if (id == R.id.cap_family) set(tv, R.drawable.ic_font_download, ForkPalette.ICON_MESSAGE);
            else if (id == R.id.cap_weight) set(tv, R.drawable.ic_form_textbox, ForkPalette.ICON_MESSAGE);
            else if (id == R.id.cap_size || id == R.id.sep_sublabel || id == R.id.frame_width_value
                    || id == R.id.frame_radius_value) {
                set(tv, R.drawable.ic_tune, ForkPalette.ICON_CONTACT);
            } else if (id == R.id.element_label) {
                set(tv, R.drawable.ic_view_list, ForkPalette.ICON_VIEW);
            }
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) sweep(group.getChildAt(i));
        }
    }
}
