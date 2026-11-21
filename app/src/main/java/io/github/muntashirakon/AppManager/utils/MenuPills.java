// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.view.menu.MenuBuilder;
import androidx.appcompat.view.menu.MenuItemImpl;
import androidx.appcompat.view.menu.MenuPopupHelper;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.graphics.ColorUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.fonts.ColorPrefs;
import io.github.muntashirakon.AppManager.utils.appearance.AppearanceUtils;

/**
 * Fork (白い熊, +104): what a menu row needs to know to be drawn by the black-yellow theme's
 * menu rule — see {@link PillMenuItemView}. Menu items carry no "opens something" and no
 * "destructive" flag, and many carry no icon, so these tables supply them: by item id for the
 * menu resources, and by title for the menus built in code ({@code menu.add(R.string.x)} gives
 * every item the id 0).
 *
 * <p>A new menu item: give it an icon in its XML, or a line in {@link #ICONS} /
 * {@link #TITLE_ICONS}; add it to {@link #OPENS} when it opens a screen, dialog or picker
 * (a title ending in "…" is taken as that by itself) and to {@link #DESTRUCTIVE} when it deletes.
 */
@SuppressLint("RestrictedApi")
public final class MenuPills {
    private MenuPills() {
    }

    static final int DISABLED_ALPHA = 97;   // 38 %
    static final int TOGGLE_HEIGHT_DP = 36; // a toggle row: the switch and little more
    static final int PILL_MARGIN_DP = 3;    // above and below a pill
    static final int SIDE_MARGIN_DP = 8;    // from the pill to the edge of the menu

    private static final SparseIntArray ICONS = new SparseIntArray();
    /** {title string, drawable} pairs for the menus built in code. */
    private static final int[][] TITLE_ICONS = {
            {R.string.delete, R.drawable.ic_trash_can},
            {R.string.vt_view_report, R.drawable.ic_vt},
            {R.string.mark_as_read, R.drawable.ic_eye},
            {R.string.mark_as_unread, R.drawable.ic_eye},
            {R.string.vt_confirm_upload_and_scan, R.drawable.ic_file_export},
            {R.string.vt_cancel_scan, R.drawable.ic_close_24dp},
            {R.string.vt_update_report, R.drawable.ic_refresh},
            {R.string.vt_rescan, R.drawable.ic_replay},
            {R.string.vt_install, R.drawable.ic_get_app},
            {R.string.vt_retry, R.drawable.ic_replay},
            {R.string.freeze, R.drawable.ic_snowflake},
            {R.string.unfreeze, R.drawable.ic_snowflake_off},
            {R.string.copy_this_path, R.drawable.ic_content_copy},
            {R.string.item_remove, R.drawable.ic_trash_can},
            {R.string.item_edit, R.drawable.ic_edit},
            {R.string.file_properties, R.drawable.ic_information_circle},
            {R.string.open_in_new_window, R.drawable.ic_open_in_new},
            {R.string.add_to_favorites, R.drawable.ic_star},
            {R.string.filter_choice, R.drawable.ic_filter},
            {R.string.copy_to_clipboard, R.drawable.ic_content_copy},
            {R.string.item_select, R.drawable.ic_check_circle},
    };
    private static final Set<Integer> OPENS = new HashSet<>(Arrays.asList(
            R.id.action_instructions, R.id.action_process_monitor, R.id.action_battery_history, R.id.action_app_usage,
            R.id.action_list_options, R.id.action_sort, R.id.action_layout_columns, R.id.action_one_click_ops,
            R.id.action_finder, R.id.action_profiles, R.id.action_apk_updater, R.id.action_debloater,
            R.id.action_labs, R.id.action_settings, R.id.action_new_window, R.id.action_shortcut,
            R.id.action_add_to_profile, R.id.action_backup, R.id.action_restore, R.id.action_net_policy,
            R.id.action_export_blocking_rules, R.id.action_export_app_list, R.id.action_export, R.id.action_import,
            R.id.action_routine_ops, R.id.action_image_metadata, R.id.action_edit, R.id.action_print,
            R.id.action_print_pdf, R.id.action_export_pdf, R.id.action_share, R.id.action_share_pdf,
            R.id.action_share_apk, R.id.action_share_backup, R.id.action_share_log, R.id.action_save_log,
            R.id.action_battery_window, R.id.action_battery_sort, R.id.action_battery_columns, R.id.action_battery_alerts,
            R.id.action_battery_retention, R.id.action_battery_status, R.id.action_battery_search, R.id.action_battery_detail_app_info,
            R.id.action_detail_app_info, R.id.action_custom_app_op, R.id.action_view_settings, R.id.action_open_in_termux,
            R.id.action_run_in_termux, R.id.action_battery_opt, R.id.action_sensor, R.id.action_magisk_hide,
            R.id.action_magisk_denylist, R.id.action_install, R.id.action_open_with, R.id.action_rename,
            R.id.action_properties, R.id.action_change_log_level, R.id.action_show_saved_filters, R.id.action_open,
            R.id.action_search, R.id.action_snooping_missing_ops, R.id.action_snooping_legend, R.id.action_save_apk,
            R.id.action_separate_window, R.id.action_image_wallpaper, R.id.action_extract_icon, R.id.action_replace,
            R.id.action_monitor_filter, R.id.action_monitor_sort, R.id.action_monitor_columns, R.id.action_monitor_more,
            R.id.action_monitor_search));
    private static final int[] OPENS_TITLES = {
            R.string.vt_view_report, R.string.vt_install, R.string.item_edit,
            R.string.file_properties, R.string.open_in_new_window, R.string.filter_choice
    };
    private static final Set<Integer> DESTRUCTIVE = new HashSet<>(Arrays.asList(
            R.id.action_delete, R.id.action_uninstall, R.id.action_clear_data, R.id.action_delete_backup,
            R.id.action_clear_history, R.id.action_battery_clear, R.id.action_clean_backups, R.id.action_snooping_forget,
            R.id.action_clear, R.id.action_discard, R.id.action_remove_from_profile));
    private static final int[] DESTRUCTIVE_TITLES = {
            R.string.delete, R.string.item_remove
    };

    static {
        ICONS.put(R.id.action_copy_as_default, R.drawable.ic_content_copy);
        ICONS.put(R.id.action_copy_as_command, R.drawable.ic_content_copy);
        ICONS.put(R.id.action_scroll_latest, R.drawable.ic_expand_more);
        ICONS.put(R.id.action_copy_log, R.drawable.ic_content_copy);
        ICONS.put(R.id.action_save_log, R.drawable.ic_content_save);
        ICONS.put(R.id.action_share_log, R.drawable.ic_share);
        ICONS.put(R.id.action_retry, R.drawable.ic_replay);
        ICONS.put(R.id.action_java_smali_toggle, R.drawable.ic_code);
        ICONS.put(R.id.action_font_text_size_decrease, R.drawable.ic_expand_more);
        ICONS.put(R.id.action_font_text_size_increase, R.drawable.ic_expand_less);
        ICONS.put(R.id.action_font_text_size_reset, R.drawable.ic_restore);
        ICONS.put(R.id.action_image_reset, R.drawable.ic_restore);
        ICONS.put(R.id.action_image_rotate_left, R.drawable.ic_undo);
        ICONS.put(R.id.action_image_rotate_right, R.drawable.ic_redo);
        ICONS.put(R.id.action_clean_backups, R.drawable.ic_clear_cache);
        ICONS.put(R.id.action_refresh, R.drawable.ic_refresh);
        ICONS.put(R.id.action_fit_width, R.drawable.ic_wrap_text);
        ICONS.put(R.id.action_clear_history, R.drawable.ic_trash_can);
        ICONS.put(R.id.action_battery_screen_off, R.drawable.ic_power_settings);
        ICONS.put(R.id.action_battery_alerts, R.drawable.ic_alert_24dp);
        ICONS.put(R.id.action_battery_sampling, R.drawable.ic_autorenew);
        ICONS.put(R.id.action_battery_retention, R.drawable.ic_history);
        ICONS.put(R.id.action_battery_status, R.drawable.ic_information_circle);
        ICONS.put(R.id.action_battery_clear, R.drawable.ic_trash_can);
        ICONS.put(R.id.action_battery_detail_app_info, R.drawable.ic_information_circle);
        ICONS.put(R.id.action_ifw_and_disable, R.drawable.ic_block);
        ICONS.put(R.id.action_ifw, R.drawable.ic_security);
        ICONS.put(R.id.action_disable, R.drawable.ic_block);
        ICONS.put(R.id.action_enable, R.drawable.ic_check_circle);
        ICONS.put(R.id.action_default, R.drawable.ic_restore);
        ICONS.put(R.id.action_snooping_show_all, R.drawable.ic_eye);
        ICONS.put(R.id.action_snooping_auto_apply, R.drawable.ic_autorenew);
        ICONS.put(R.id.action_snooping_forget, R.drawable.ic_filter_clear);
        ICONS.put(R.id.action_snooping_missing_ops, R.drawable.ic_list_status);
        ICONS.put(R.id.action_snooping_legend, R.drawable.ic_information_circle);
        ICONS.put(R.id.action_crazy_logger_service, R.drawable.ic_record_rec);
        ICONS.put(R.id.action_sort_by_name, R.drawable.ic_sort);
        ICONS.put(R.id.action_sort_by_app_ops_values, R.drawable.ic_sort);
        ICONS.put(R.id.action_sort_by_denied_app_ops, R.drawable.ic_sort);
        ICONS.put(R.id.action_sort_by_blocked_components, R.drawable.ic_sort);
        ICONS.put(R.id.action_sort_by_tracker_components, R.drawable.ic_sort);
        ICONS.put(R.id.action_sort_by_priority, R.drawable.ic_sort);
        ICONS.put(R.id.action_sort_by_dangerous_permissions, R.drawable.ic_sort);
        ICONS.put(R.id.action_sort_by_denied_permissions, R.drawable.ic_sort);
        ICONS.put(R.id.action_search, io.github.muntashirakon.ui.R.drawable.ic_search);
    }

    /** Black-yellow screens take the theme's roles; any other screen keeps its own text colour. */
    static boolean themed(@NonNull Context context) {
        return AppearanceUtils.isYellowOnBlack(context);
    }

    @ColorInt
    static int accent(@NonNull Context context, @ColorInt int fallback) {
        return themed(context) ? ForkThemeUtils.getTextColor() : fallback;
    }

    /** Fork (+123): delete has a red role of its own — the monitor's leak orange would collide with share. */
    @ColorInt
    static int warning(@NonNull Context context) {
        return ForkPalette.get(context, ForkPalette.DELETE);
    }

    @ColorInt
    static int frame(@NonNull Context context, @ColorInt int fallback) {
        return themed(context) ? ForkPalette.get(context, ForkPalette.MENU_FRAME) : fallback;
    }

    @ColorInt
    static int text(@NonNull Context context, @ColorInt int fallback) {
        return themed(context) ? ForkPalette.get(context, ForkPalette.MENU_TEXT) : fallback;
    }

    /**
     * Fork (白い熊, +123): what a row does, as one of the palette's icon roles — a menu is read by
     * colour before it is read by word. Told from the item's id name (or, for a menu built in
     * code, from the name of the string it shows), first match wins; anything else is "view".
     * {word fragment, role} — delete first, so "clear_data" is never taken for something milder.
     */
    private static final String[][] ROLE_WORDS = {
            {"delete", ForkPalette.DELETE}, {"uninstall", ForkPalette.DELETE}, {"clear", ForkPalette.DELETE},
            {"remove", ForkPalette.DELETE}, {"discard", ForkPalette.DELETE}, {"forget", ForkPalette.DELETE},
            {"clean", ForkPalette.DELETE}, {"purge", ForkPalette.DELETE}, {"wipe", ForkPalette.DELETE},
            {"unfreeze", ForkPalette.ICON_CONNECT},
            {"freeze", ForkPalette.ICON_BLOCK}, {"block", ForkPalette.ICON_BLOCK}, {"stop", ForkPalette.ICON_BLOCK},
            {"kill", ForkPalette.ICON_BLOCK}, {"disable", ForkPalette.ICON_BLOCK}, {"deny", ForkPalette.ICON_BLOCK},
            {"cancel", ForkPalette.ICON_BLOCK}, {"net_policy", ForkPalette.ICON_BLOCK},
            {"magisk", ForkPalette.ICON_BLOCK}, {"close", ForkPalette.ICON_BLOCK},
            {"share", ForkPalette.ICON_SHARE}, {"export", ForkPalette.ICON_SHARE}, {"copy", ForkPalette.ICON_SHARE},
            {"save", ForkPalette.ICON_SHARE}, {"print", ForkPalette.ICON_SHARE}, {"extract", ForkPalette.ICON_SHARE},
            {"shortcut", ForkPalette.ICON_SHARE}, {"upload", ForkPalette.ICON_SHARE},
            {"backup", ForkPalette.ICON_CONNECT}, {"restore", ForkPalette.ICON_CONNECT},
            {"install", ForkPalette.ICON_CONNECT}, {"import", ForkPalette.ICON_CONNECT},
            {"run", ForkPalette.ICON_CONNECT}, {"launch", ForkPalette.ICON_CONNECT},
            {"refresh", ForkPalette.ICON_CONNECT}, {"reload", ForkPalette.ICON_CONNECT},
            {"rescan", ForkPalette.ICON_CONNECT}, {"retry", ForkPalette.ICON_CONNECT},
            {"update", ForkPalette.ICON_CONNECT}, {"sync", ForkPalette.ICON_CONNECT},
            {"record", ForkPalette.ICON_CONNECT}, {"termux", ForkPalette.ICON_CONNECT},
            {"one_click", ForkPalette.ICON_CONNECT}, {"routine", ForkPalette.ICON_CONNECT},
            {"edit", ForkPalette.ICON_MESSAGE}, {"rename", ForkPalette.ICON_MESSAGE}, {"note", ForkPalette.ICON_MESSAGE},
            {"mark", ForkPalette.ICON_MESSAGE}, {"replace", ForkPalette.ICON_MESSAGE},
            {"undo", ForkPalette.ICON_MESSAGE}, {"redo", ForkPalette.ICON_MESSAGE}, {"favorite", ForkPalette.ICON_MESSAGE},
            {"select", ForkPalette.ICON_MESSAGE}, {"wrap", ForkPalette.ICON_MESSAGE}, {"log_level", ForkPalette.ICON_MESSAGE},
            {"profile", ForkPalette.ICON_CONTACT}, {"app_info", ForkPalette.ICON_CONTACT},
            {"user", ForkPalette.ICON_CONTACT}, {"app_op", ForkPalette.ICON_CONTACT},
            {"sensor", ForkPalette.ICON_CONTACT}, {"battery_opt", ForkPalette.ICON_CONTACT},
    };

    /** The palette role of a name such as {@code action_share_apk} or {@code vt_cancel_scan}. */
    @NonNull
    public static String roleForName(@Nullable String name) {
        if (name != null) {
            String n = name.toLowerCase(java.util.Locale.ROOT);
            for (String[] pair : ROLE_WORDS) {
                if (n.contains(pair[0])) return pair[1];
            }
        }
        return ForkPalette.ICON_VIEW;
    }

    @NonNull
    static String roleOf(@NonNull Context context, @NonNull MenuItem item) {
        try {
            if (isDestructive(context, item)) return ForkPalette.DELETE;
            int id = item.getItemId();
            if (id != 0 && id != View.NO_ID) {
                return roleForName(context.getResources().getResourceEntryName(id));
            }
            CharSequence title = item.getTitle();
            for (int[] pair : TITLE_ICONS) {
                if (titleIs(context, title, pair[0])) {
                    return roleForName(context.getResources().getResourceEntryName(pair[0]));
                }
            }
            return roleForName(title != null ? title.toString().replace(' ', '_') : null);
        } catch (Throwable t) {
            return ForkPalette.ICON_VIEW;
        }
    }

    /** The colour of a row's icon: its role, whatever the frame and the label wear. */
    @ColorInt
    static int iconColor(@NonNull Context context, @NonNull MenuItem item, @ColorInt int fallback) {
        return themed(context) ? ForkPalette.get(context, roleOf(context, item)) : fallback;
    }

    /** The icon colour of a hand-built row, from a role name such as {@link ForkPalette#ICON_SHARE}. */
    @ColorInt
    public static int roleColor(@NonNull Context context, @NonNull String role) {
        return ForkPalette.get(context, role);
    }

    /** A switch that is off: an opaque darker accent on a themed screen, never a faded one. */
    @ColorInt
    static int switchOff(@NonNull Context context, @ColorInt int accent) {
        return themed(context) ? ForkPalette.get(context, ForkPalette.SWITCH_OFF)
                : ColorUtils.setAlphaComponent(accent, 140);
    }

    /** The line between two groups: clearly there, quieter than a pill frame. */
    @ColorInt
    static int divider(@NonNull Context context, @ColorInt int accent) {
        return themed(context) ? ForkPalette.get(context, ForkPalette.MENU_DIVIDER)
                : ColorUtils.setAlphaComponent(accent, 178);
    }

    private static boolean titleIs(@NonNull Context context, @Nullable CharSequence title, int res) {
        try {
            return title != null && context.getString(res).contentEquals(title);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The table icon of an item that brought none, or 0. */
    static int iconFor(@NonNull Context context, @NonNull MenuItem item) {
        int id = item.getItemId();
        if (id != 0 && id != View.NO_ID) {
            int res = ICONS.get(id);
            if (res != 0) return res;
        }
        CharSequence title = item.getTitle();
        for (int[] pair : TITLE_ICONS) {
            if (titleIs(context, title, pair[0])) return pair[1];
        }
        return 0;
    }

    static boolean opens(@NonNull Context context, @NonNull MenuItem item) {
        if (item.hasSubMenu()) return true;
        CharSequence title = item.getTitle();
        if (title != null && title.toString().trim().endsWith("…")) return true;
        if (OPENS.contains(item.getItemId())) return true;
        // One id serves two menus: "Delete" in most, "Manage saved logs" in the log viewers.
        if (item.getItemId() == R.id.action_delete && titleIs(context, title, R.string.manage_saved_logs)) return true;
        for (int res : OPENS_TITLES) {
            if (titleIs(context, title, res)) return true;
        }
        return false;
    }

    static boolean isDestructive(@NonNull Context context, @NonNull MenuItem item) {
        CharSequence title = item.getTitle();
        if (DESTRUCTIVE.contains(item.getItemId())) {
            return !(item.getItemId() == R.id.action_delete && titleIs(context, title, R.string.manage_saved_logs));
        }
        if (item.getItemId() != 0) return false;
        for (int res : DESTRUCTIVE_TITLES) {
            if (titleIs(context, title, res)) return true;
        }
        return false;
    }

    /** The disabled title line a menu opens with. */
    static boolean isHeader(@NonNull MenuItem item) {
        return !item.isEnabled() && item.getItemId() == 0 && !item.hasSubMenu();
    }

    /** A row with a switch: checkable, and not one of an exclusive (radio) group. */
    static boolean isToggle(@NonNull MenuItemImpl item) {
        return item.isCheckable() && !item.isExclusiveCheckable();
    }

    /** An action row: everything that is not a toggle, a radio choice or a header. */
    static boolean isPill(@NonNull MenuItemImpl item) {
        return !item.isCheckable() && !isHeader(item);
    }

    /**
     * Whether the rows of this menu keep an icon column. A list of pure choices with no icon
     * anywhere stays iconless; as soon as one row has an icon, every row keeps the column, so the
     * labels share one left edge.
     */
    static boolean anyIcon(@NonNull Context context, @Nullable MenuBuilder menu) {
        if (menu == null) return true;
        try {
            for (MenuItemImpl item : menu.getVisibleItems()) {
                if (item.getIcon() != null || iconFor(context, item) != 0) return true;
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    /**
     * A menu row for a popup the app builds by hand (no {@code Menu} behind it): the same
     * full-width outlined pill, its icon inside at the left and a chevron at the right when it
     * opens something. Add it to a vertical layout with {@code MATCH_PARENT} width.
     */
    @NonNull
    public static View pillRow(@NonNull Context context, int iconRes, @NonNull CharSequence label, boolean opens,
                               @ColorInt int color, @NonNull View.OnClickListener onClick) {
        return pillRow(context, iconRes, label, opens, color, color, onClick);
    }

    /** As above, with the icon in the colour of what the row does ({@link #roleColor}). */
    @NonNull
    public static View pillRow(@NonNull Context context, int iconRes, @NonNull CharSequence label, boolean opens,
                               @ColorInt int color, @ColorInt int iconColor,
                               @NonNull View.OnClickListener onClick) {
        float d = context.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout row = new android.widget.LinearLayout(context);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Math.round(44 * d));
        row.setPadding(Math.round(14 * d), 0, Math.round(12 * d), 0);
        android.graphics.drawable.GradientDrawable frame = new android.graphics.drawable.GradientDrawable();
        frame.setCornerRadius(100 * d);
        frame.setColor(android.graphics.Color.TRANSPARENT);
        frame.setStroke(Math.max(1, Math.round(1.25f * d)), color);
        android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
        mask.setCornerRadius(100 * d);
        mask.setColor(android.graphics.Color.WHITE);
        row.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(color, 77)), frame, mask));
        android.widget.ImageView icon = new android.widget.ImageView(context);
        if (iconRes != 0) icon.setImageResource(iconRes);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(iconColor));
        android.widget.LinearLayout.LayoutParams ilp = new android.widget.LinearLayout.LayoutParams(
                Math.round(24 * d), Math.round(24 * d));
        ilp.setMarginEnd(Math.round(12 * d));
        row.addView(icon, ilp);
        android.widget.TextView text = new android.widget.TextView(context);
        text.setText(label);
        text.setTextColor(color);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        text.setSingleLine(true);
        row.addView(text, new android.widget.LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (opens) {
            android.widget.ImageView chevron = new android.widget.ImageView(context);
            chevron.setImageResource(R.drawable.fork_menu_chevron);
            chevron.setImageTintList(android.content.res.ColorStateList.valueOf(color));
            android.widget.LinearLayout.LayoutParams clp = new android.widget.LinearLayout.LayoutParams(
                    Math.round(20 * d), Math.round(20 * d));
            clp.setMarginStart(Math.round(8 * d));
            row.addView(chevron, clp);
        }
        row.setOnClickListener(onClick);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(PILL_MARGIN_DP * d);
        lp.bottomMargin = Math.round(PILL_MARGIN_DP * d);
        row.setLayoutParams(lp);
        return row;
    }

    /**
     * Show a popup menu so that it never scrolls while the screen has room. A stock popup is
     * given only the larger of the room above and the room below its anchor; one that needs more
     * than there is below is shown shifted up by what is missing.
     */
    public static void show(@NonNull PopupMenu popup, @NonNull View anchor) {
        try {
            Menu menu = popup.getMenu();
            Integer offset = verticalOffset(anchor, menu);
            if (offset != null && menu instanceof MenuBuilder) {
                MenuPopupHelper helper = new MenuPopupHelper(anchor.getContext(), (MenuBuilder) menu, anchor,
                        false, androidx.appcompat.R.attr.popupMenuStyle, 0);
                helper.show(0, offset);
                return;
            }
        } catch (Throwable ignore) {
        }
        popup.show();
    }

    /** The vertical offset that gives the menu its full height, or null when it fits below its anchor. */
    @Nullable
    private static Integer verticalOffset(@NonNull View anchor, @Nullable Menu menu) {
        try {
            if (!(menu instanceof MenuBuilder)) return null;
            Context context = anchor.getContext();
            float density = context.getResources().getDisplayMetrics().density;
            TypedValue tv = new TypedValue();
            if (!context.getTheme().resolveAttribute(
                    androidx.appcompat.R.attr.dropdownListPreferredItemHeight, tv, true)) {
                return null;
            }
            float row = tv.getDimension(context.getResources().getDisplayMetrics());
            MenuBuilder builder = (MenuBuilder) menu;
            float needed = 24 * density;
            Integer group = null;
            for (MenuItemImpl item : builder.getVisibleItems()) {
                if (isToggle(item)) needed += TOGGLE_HEIGHT_DP * density;
                else if (isPill(item)) needed += row + 2 * PILL_MARGIN_DP * density;
                else needed += row;
                if (group != null && group != item.getGroupId() && builder.isGroupDividerEnabled()) {
                    needed += 9 * density;
                }
                group = item.getGroupId();
            }
            Rect frame = new Rect();
            anchor.getWindowVisibleDisplayFrame(frame);
            int[] pos = new int[2];
            anchor.getLocationOnScreen(pos);
            int base = pos[1] + anchor.getHeight();
            float below = frame.bottom - base;
            if (needed <= below) return null;
            return Math.round(Math.max(frame.top - base, below - needed));
        } catch (Throwable t) {
            return null;
        }
    }
}
