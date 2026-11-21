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
