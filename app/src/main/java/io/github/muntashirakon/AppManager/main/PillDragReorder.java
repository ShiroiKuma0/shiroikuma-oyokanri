// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.animation.LayoutTransition;
import android.view.DragEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Fork (白い熊, +121/+122): drag one pill onto another to reorder them, in place.
 *
 * <p>The actions were configurable from a settings page three screens away. Rearranging things
 * you are looking at should not require going somewhere else to do it, so the pills are their own
 * editor: long-press one and drop it where it belongs.
 *
 * <p><b>The pill moves as you drag it</b>, which is the whole point (白い熊, +122). The first
 * version only shaded the slot you were over and did the move on release — so nothing appeared to
 * happen until it suddenly had. Now crossing a neighbour reorders the row immediately: the pill
 * is taken out and put back at the new index, every other pill reflows around it, and a
 * {@link LayoutTransition} animates that reflow. What you see while dragging is the arrangement
 * you will get.
 *
 * <p><b>Two landmines in that.</b> The reorder must not happen inside the drag-event dispatch —
 * removing a child while the view hierarchy is delivering an event to it is asking for trouble —
 * so it is posted to the next frame. And the dragged view travels as the drag's <b>local
 * state</b>, never as an index: the indices are exactly what the reordering invalidates.
 */
public final class PillDragReorder {
    /** Told the new order, keys in their new positions, once the drag ends. */
    public interface OnReordered {
        void onReordered(@NonNull List<String> keys);
    }

    /** The pill being carried is drawn back, so the row reads as the arrangement, not the drag. */
    private static final float DRAGGED_ALPHA = 0.35f;

    private PillDragReorder() {
    }

    /**
     * Make every child of {@code container} draggable onto every other.
     *
     * @param keys the key behind each child, in the same order as the children
     */
    public static void attach(@NonNull ViewGroup container, @NonNull List<String> keys,
                              @NonNull OnReordered listener) {
        if (container.getChildCount() != keys.size()) {
            // The views and the keys must correspond one to one, or a drop would reorder the
            // wrong thing. Rather than reorder something at random, do nothing.
            return;
        }
        // Animate the reflow. CHANGING is what moves the pills that are NOT being dragged, and
        // it is off by default.
        LayoutTransition transition = new LayoutTransition();
        transition.enableTransitionType(LayoutTransition.CHANGING);
        transition.setDuration(120);
        container.setLayoutTransition(transition);

        final List<String> order = new ArrayList<>(keys);
        final boolean[] moved = {false};
        View.OnDragListener dragListener = (view, event) -> {
            Object local = event.getLocalState();
            if (!(local instanceof View)) {
                return false;
            }
            View dragged = (View) local;
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return true;
                case DragEvent.ACTION_DRAG_ENTERED: {
                    if (view == dragged || view == container) {
                        return true;
                    }
                    int from = container.indexOfChild(dragged);
                    int to = container.indexOfChild(view);
                    if (from < 0 || to < 0 || from == to) {
                        return true;
                    }
                    // Next frame: never restructure the hierarchy inside its own event dispatch.
                    container.post(() -> {
                        int currentFrom = container.indexOfChild(dragged);
                        int currentTo = container.indexOfChild(view);
                        if (currentFrom < 0 || currentTo < 0 || currentFrom == currentTo) {
                            return;
                        }
                        container.removeViewAt(currentFrom);
                        container.addView(dragged, currentTo);
                        String key = order.remove(currentFrom);
                        order.add(currentTo, key);
                        moved[0] = true;
                    });
                    return true;
                }
                case DragEvent.ACTION_DROP:
                    return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    dragged.setAlpha(1f);
                    if (view == container && moved[0]) {
                        // Once, from the container — every child gets this event too, and
                        // persisting per child would write the file a dozen times.
                        moved[0] = false;
                        listener.onReordered(new ArrayList<>(order));
                    }
                    return true;
                default:
                    return true;
            }
        };
        for (int i = 0; i < container.getChildCount(); ++i) {
            View child = container.getChildAt(i);
            child.setOnLongClickListener(v -> {
                v.setAlpha(DRAGGED_ALPHA);
                boolean started = v.startDragAndDrop(null, new View.DragShadowBuilder(v), v, 0);
                if (!started) {
                    v.setAlpha(1f);
                }
                return started;
            });
            child.setOnDragListener(dragListener);
        }
        container.setOnDragListener(dragListener);
    }
}
