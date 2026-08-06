// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import androidx.annotation.NonNull;

/**
 * Fork (白い熊, 2026-08-06): per-<b>geometry</b> list layouts.
 * <p>
 * A column count that suits the folded cover panel is wrong on the unfolded
 * screen and wrong again in landscape, so the three list screens (main list,
 * process monitor, battery history) no longer store <i>one</i> pick — they store
 * one per geometry, and each is chosen and remembered on its own.
 * <p>
 * <b>What identifies a geometry:</b> the orientation plus
 * {@link Configuration#smallestScreenWidthDp}. That second value is the fold
 * fingerprint — it is the smaller side of the window in dp, so it changes when
 * panels unfold (Mate XT: roughly 403 folded, 819 unfolded) and, being the
 * <i>smallest</i> side, stays put when the same geometry is merely rotated. The
 * orientation covers the rotation itself. Nothing needs to know what a "fold
 * state" is, which is the point: no window-manager dependency, no OEM fold API,
 * and a device with four panels or none gets sensible keys for free.
 * <p>
 * Multi-window falls out of the same rule: since API 24 the configuration
 * describes the app's <b>window</b>, not the display, so a half-screen window is
 * its own geometry and keeps its own layout.
 * <p>
 * <b>Migration:</b> the pre-geometry pick lived under the bare {@code columns}
 * key. It is still read as the seed for any geometry that has never been set, so
 * an existing choice carries into all of them once; writes only ever touch the
 * per-geometry key, so from the first pick onwards each geometry is independent.
 */
public final class LayoutGeometry {
    private LayoutGeometry() {
    }

    /**
     * Stable identifier for the current geometry, e.g. {@code "p403"}, {@code "l819"}.
     * <p>
     * <b>Pass an activity or view context, never the application context</b> —
     * only a window context carries the window's own configuration, and the
     * application one would report the whole display, collapsing every
     * multi-window geometry onto the full-screen key.
     */
    @NonNull
    public static String key(@NonNull Context context) {
        Configuration c = context.getResources().getConfiguration();
        char orientation = c.orientation == Configuration.ORIENTATION_LANDSCAPE ? 'l' : 'p';
        return orientation + String.valueOf(c.smallestScreenWidthDp);
    }

    /** The per-geometry preference key for {@code baseKey} ({@code "columns_p403"}). */
    @NonNull
    public static String key(@NonNull Context context, @NonNull String baseKey) {
        return baseKey + '_' + key(context);
    }

    /**
     * The column count stored for this geometry, falling back to the
     * pre-geometry global pick and then to {@code defaultValue}.
     */
    public static int getColumns(@NonNull Context context, @NonNull SharedPreferences prefs,
                                 @NonNull String baseKey, int defaultValue) {
        String geometryKey = key(context, baseKey);
        if (prefs.contains(geometryKey)) {
            return prefs.getInt(geometryKey, defaultValue);
        }
        return prefs.getInt(baseKey, defaultValue);
    }

    /** Store the column count for this geometry only. */
    public static void setColumns(@NonNull Context context, @NonNull SharedPreferences prefs,
                                  @NonNull String baseKey, int columns) {
        prefs.edit().putInt(key(context, baseKey), columns).apply();
    }
}
