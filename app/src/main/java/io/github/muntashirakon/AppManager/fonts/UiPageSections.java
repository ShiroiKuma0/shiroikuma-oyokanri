// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.fonts;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.appcompat.widget.LinearLayoutCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.utils.ForkPalette;
import io.github.muntashirakon.AppManager.utils.Pills;

/**
 * Fork (白い熊, +123): the UI screen as the black-yellow skill wants every UI page — a folding
 * accordion under a draggable chip index.
 * <ul>
 * <li>Every top-level section folds on a tap on its heading (› folded, ⌄ open) and is
 *     <b>folded on a fresh install</b>.</li>
 * <li>The index is a wrapping grid of small outlined chips, one per section. A tap unfolds the
 *     section and scrolls to it; a long-press drags the chip, the chips reorder <em>live</em>
 *     under the finger, and the sections take the new order on drop.</li>
 * <li>Order and fold state are two lists in the colours' preference file
 *     ({@code shiroikuma_colors}), so they travel in the export with the colours and fonts.</li>
 * </ul>
 * {@link FontsPreferences} still builds each section as a {@code view_font_group} and tags it
 * with an id ({@code R.id.fork_section_id} — the entry name of its title string, <b>never
 * renamed</b>: it is what the stored order is made of); this class only arranges them.
 */
final class UiPageSections {
    /**
     * Fork (白い熊, 2026-11-27): the section registry — id (the entry name of the title string),
     * icon, and which of the seven section colours the section is ({@code ForkPalette.SECTION_*}:
     * base yellow, controls green, words light blue, frame heavy blue, reach orange, warning
     * red, flash rose). Heading and chip both read it, so they cannot drift apart.
     *
     * <p><b>The order of this table is the default order of the page.</b> "App language" is
     * first, and no two neighbours share a colour — keep both true when adding a section. A
     * section that is not listed goes to the end, in the base colour.
     */
    private static final Object[][] REGISTRY = {
            {"ui_language_group", R.drawable.ic_globe, ForkPalette.SECTION_WORDS},
            {"pref_font_group_default", R.drawable.ic_font_download, ForkPalette.SECTION_BASE},
            {"palette_group_buttons", R.drawable.ic_touch_app, ForkPalette.SECTION_CONTROLS},
            {"palette_group_base", R.drawable.ic_palette_swatch, ForkPalette.SECTION_BASE},
            {"palette_group_topbar", R.drawable.ic_tab, ForkPalette.SECTION_FRAME},
            {"palette_group_dialogs", R.drawable.ic_information_circle, ForkPalette.SECTION_REACH},
            {"palette_group_switches", R.drawable.ic_tune, ForkPalette.SECTION_CONTROLS},
            {"palette_group_menus", R.drawable.ic_menu, ForkPalette.SECTION_REACH},
            {"palette_group_theme", R.drawable.ic_code, ForkPalette.SECTION_BASE},
            {"palette_group_shapes", R.drawable.ic_view_agenda, ForkPalette.SECTION_FRAME},
            {"palette_group_icons", R.drawable.ic_brush, ForkPalette.SECTION_BASE},
            {"pref_font_group_main_list", R.drawable.ic_view_list, ForkPalette.SECTION_FRAME},
            {"pref_color_group_indicators", R.drawable.ic_flag, ForkPalette.SECTION_BASE},
            {"pref_sep_group", R.drawable.ic_view_agenda, ForkPalette.SECTION_FRAME},
            {"pref_selframe_group", R.drawable.ic_cursor_default_click, ForkPalette.SECTION_BASE},
            {"pref_font_group_app_details", R.drawable.ic_package, ForkPalette.SECTION_FRAME},
            {"pref_runbox_group", R.drawable.ic_run_fast, ForkPalette.SECTION_BASE},
            {"pref_monitor_group", R.drawable.ic_monitor, ForkPalette.SECTION_FRAME},
            {"pref_detail_group", R.drawable.ic_list_status, ForkPalette.SECTION_BASE},
            {"pref_oplog_group", R.drawable.ic_history, ForkPalette.SECTION_FRAME},
            {"palette_group_settings_screens", R.drawable.ic_settings, ForkPalette.SECTION_BASE},
            {"palette_group_fm", R.drawable.ic_folder, ForkPalette.SECTION_FRAME},
            {"palette_group_logs", R.drawable.ic_file_document, ForkPalette.SECTION_BASE},
            {"palette_group_other_screens", R.drawable.ic_note_24dp, ForkPalette.SECTION_FRAME},
            {"pref_screens_group", R.drawable.ic_phone_android, ForkPalette.SECTION_BASE},
            {"pref_legend_group", R.drawable.ic_book, ForkPalette.SECTION_WORDS},
            {"pref_settings_page_group", R.drawable.ic_settings, ForkPalette.SECTION_BASE},
            {"settings_eim_title", R.drawable.ic_share, ForkPalette.SECTION_REACH},
            {"diag_group", R.drawable.ic_pulse, ForkPalette.SECTION_WARNING},
            {"virus_total", R.drawable.ic_vt, ForkPalette.SECTION_REACH},
            {"ui_maintenance_group", R.drawable.ic_hammer_wrench, ForkPalette.SECTION_WARNING},
    };

    private static int registryIndex(@NonNull String id) {
        for (int i = 0; i < REGISTRY.length; i++) {
            if (REGISTRY[i][0].equals(id)) return i;
        }
        return Integer.MAX_VALUE;
    }

    @androidx.annotation.DrawableRes
    private static int iconOf(@NonNull String id) {
        int i = registryIndex(id);
        return i == Integer.MAX_VALUE ? R.drawable.ic_tune : (Integer) REGISTRY[i][1];
    }

    private int colorOf(@NonNull String id) {
        int i = registryIndex(id);
        return ForkPalette.get(mContext, i == Integer.MAX_VALUE ? ForkPalette.SECTION_BASE : (String) REGISTRY[i][2]);
    }

    private static final String PREFS = "shiroikuma_colors";
    private static final String KEY_ORDER = "ui_section_order";
    private static final String KEY_OPEN = "ui_sections_open";

    private final LinearLayoutCompat mContainer;
    private final ScrollView mScroll;
    private final Context mContext;
    private final Map<String, View> mSections = new LinkedHashMap<>();
    private final List<String> mDefaultOrder = new ArrayList<>();
    private final Set<String> mOpen = new HashSet<>();
    private UiFlowLayout mIndex;
    private int mFixed;     // children of the container that come before the sections
    @Nullable
    private View mDragged;
    private int mLastTarget = -1;

    private UiPageSections(@NonNull LinearLayoutCompat container, @NonNull ScrollView scroll) {
        mContainer = container;
        mScroll = scroll;
        mContext = container.getContext();
    }

    @NonNull
    private static SharedPreferences sp(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @NonNull
    private static List<String> list(@NonNull Context context, @NonNull String key) {
        String raw = sp(context).getString(key, "");
        List<String> out = new ArrayList<>();
        if (!TextUtils.isEmpty(raw)) out.addAll(Arrays.asList(raw.split(",")));
        return out;
    }

    static void resetOrder(@NonNull Context context) {
        sp(context).edit().remove(KEY_ORDER).apply();
    }

    /**
     * Take the sections {@link FontsPreferences} has built into {@code container} and lay the
     * page out: {@code top} (the settings pill), the chip index, then the sections in the stored
     * order.
     */
    static void arrange(@NonNull LinearLayoutCompat container, @NonNull ScrollView scroll, @NonNull View top) {
        new UiPageSections(container, scroll).arrange(top);
    }

    private void arrange(@NonNull View top) {
        for (int i = 0; i < mContainer.getChildCount(); i++) {
            View child = mContainer.getChildAt(i);
            Object id = child.getTag(R.id.fork_section_id);
            if (id instanceof String) {
                mSections.put((String) id, child);
                mDefaultOrder.add((String) id);
            }
        }
        mContainer.removeAllViews();
        // The registry's order is the default order, whatever order the sections were built in.
        // (A stable sort: sections the registry does not know keep their built order, at the end.)
        java.util.Collections.sort(mDefaultOrder, (a, b) -> Integer.compare(registryIndex(a), registryIndex(b)));
        mOpen.addAll(list(mContext, KEY_OPEN));

        mContainer.addView(top);
        mIndex = buildIndex();
        mContainer.addView(mIndex);
        mFixed = mContainer.getChildCount();

        for (String id : order()) {
            View section = mSections.get(id);
            if (section == null) continue;
            wire(id, section);
            mContainer.addView(section);
        }
        hairlines();
    }

    /** The stored order, without ids that no longer exist and with new sections at their default place. */
    @NonNull
    private List<String> order() {
        List<String> order = new ArrayList<>();
        for (String id : list(mContext, KEY_ORDER)) {
            if (mSections.containsKey(id) && !order.contains(id)) order.add(id);
        }
        if (order.isEmpty()) return new ArrayList<>(mDefaultOrder);
        for (int i = 0; i < mDefaultOrder.size(); i++) {
            String id = mDefaultOrder.get(i);
            if (order.contains(id)) continue;
            // After the section it follows by default, when that one is known; else at the end.
            int at = i > 0 ? order.indexOf(mDefaultOrder.get(i - 1)) : -1;
            if (i == 0) order.add(0, id);
            else if (at >= 0) order.add(at + 1, id);
            else order.add(id);
        }
        return order;
    }

    private void wire(@NonNull String id, @NonNull View section) {
        View row = section.findViewById(R.id.group_header_row);
        row.setOnClickListener(v -> setOpen(id, !mOpen.contains(id)));
        // The section's icon in the section's colour, sized by the heading; the underline stays
        // as wide as the words, so it starts after the icon.
        final View underline = section.findViewById(R.id.group_underline);
        UiRowIcons.labelIcon(section.findViewById(R.id.group_header), iconOf(id), colorOf(id), 18, px -> {
            if (underline != null && underline.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) underline.getLayoutParams();
                if (lp.getMarginStart() != px) {
                    lp.setMarginStart(px);
                    underline.setLayoutParams(lp);
                }
            }
        });
        render(id, section);
    }

    private void render(@NonNull String id, @NonNull View section) {
        boolean open = mOpen.contains(id);
        section.findViewById(R.id.group_content).setVisibility(open ? View.VISIBLE : View.GONE);
        ((TextView) section.findViewById(R.id.group_chevron)).setText(open ? "⌄" : "›");
    }

    private void setOpen(@NonNull String id, boolean open) {
        if (open) mOpen.add(id);
        else mOpen.remove(id);
        sp(mContext).edit().putString(KEY_OPEN, TextUtils.join(",", mOpen)).apply();
        View section = mSections.get(id);
        if (section != null) render(id, section);
    }

    /** The 1 px line above every section but the first — folded or open. */
    private void hairlines() {
        boolean first = true;
        for (int i = mFixed; i < mContainer.getChildCount(); i++) {
            View line = mContainer.getChildAt(i).findViewById(R.id.group_hairline);
            if (line != null) line.setVisibility(first ? View.GONE : View.VISIBLE);
            first = false;
        }
    }

    private int dp(float v) {
        return Math.round(v * mContext.getResources().getDisplayMetrics().density);
    }

    @NonNull
    private UiFlowLayout buildIndex() {
        UiFlowLayout flow = new UiFlowLayout(mContext, dp(6));
        flow.setPadding(dp(16), dp(8), dp(16), dp(10));
        flow.setLayoutParams(new LinearLayoutCompat.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int ink = ForkPalette.get(mContext, ForkPalette.BUTTON_TEXT);
        int frame = ForkPalette.get(mContext, ForkPalette.BUTTON_FRAME);
        for (String id : order()) {
            View section = mSections.get(id);
            if (section == null) continue;
            AppCompatTextView chip = new AppCompatTextView(mContext);
            chip.setText(((TextView) section.findViewById(R.id.group_header)).getText());
            chip.setTextColor(ink);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            chip.setSingleLine(true);
            chip.setPadding(dp(10), dp(4), dp(10), dp(4));
            chip.setBackground(Pills.frame(mContext, false, frame));
            // The same icon in the same colour as the section's heading, sized by the chip's label.
            UiRowIcons.labelIcon(chip, iconOf(id), colorOf(id), 0, null);
            chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
            chip.setTag(id);
            chip.setOnClickListener(v -> jump(id));
            chip.setOnLongClickListener(v -> {
                mDragged = v;
                mLastTarget = -1;
                v.setAlpha(0.3f);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    v.startDragAndDrop(null, new View.DragShadowBuilder(v), v, 0);
                } else {
                    v.startDrag(null, new View.DragShadowBuilder(v), v, 0);
                }
                return true;
            });
            flow.addView(chip, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        flow.setOnDragListener((v, event) -> onDrag(event));
        return flow;
    }

    private void jump(@NonNull String id) {
        setOpen(id, true);
        View section = mSections.get(id);
        if (section != null) mScroll.post(() -> mScroll.smoothScrollTo(0, section.getTop()));
    }

    private boolean onDrag(@NonNull DragEvent event) {
        if (mDragged == null) return false;
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return true;
            case DragEvent.ACTION_DRAG_LOCATION: {
                // Live: the chip moves to the slot under the finger. The drag itself is carried
                // by the shadow, so moving the view in the tree does not end it.
                int target = mIndex.childAt(event.getX(), event.getY());
                int from = mIndex.indexOfChild(mDragged);
                if (target < 0 || target == from) {
                    mLastTarget = -1;
                    return true;
                }
                // Chips differ in width, so a swap can put the neighbour back under the finger:
                // without this guard the two would flap on every event.
                if (target == mLastTarget) return true;
                mIndex.removeView(mDragged);
                mIndex.addView(mDragged, target);
                mLastTarget = from;
                return true;
            }
            case DragEvent.ACTION_DRAG_ENDED: {
                mDragged.setAlpha(1f);
                mDragged = null;
                List<String> order = new ArrayList<>();
                for (int i = 0; i < mIndex.getChildCount(); i++) {
                    order.add((String) mIndex.getChildAt(i).getTag());
                }
                sp(mContext).edit().putString(KEY_ORDER, TextUtils.join(",", order)).apply();
                // The sections follow; their fold state is untouched.
                while (mContainer.getChildCount() > mFixed) mContainer.removeViewAt(mFixed);
                for (String id : order) {
                    View section = mSections.get(id);
                    if (section != null) mContainer.addView(section);
                }
                hairlines();
                return true;
            }
            default:
                return true;
        }
    }
}
