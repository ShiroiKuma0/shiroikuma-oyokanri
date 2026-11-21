// SPDX-License-Identifier: GPL-3.0-or-later

package com.google.android.material.color;

import android.content.Context;
import android.content.res.loader.ResourcesLoader;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.Map;

/**
 * Fork (白い熊, +123): one class in Material's own package, because the table writer that turns
 * a map of colour resource id → colour into a {@link ResourcesLoader} is package-private there
 * ({@code ColorResourcesLoaderCreator}). Material's public door to it
 * ({@code ColorResourcesOverride.applyIfPossible}) also lays the "personalized colours" overlay
 * over the theme, which would hand the fork's palette to the wallpaper — so the loader is taken
 * bare. Used only by {@code utils/ForkPalette}. When Material is upgraded, check that
 * {@code ColorResourcesLoaderCreator.create(Context, Map)} still exists.
 */
public final class ForkColorLoader {
    private ForkColorLoader() {
    }

    /** A loader that replaces the given colour resources, or null where that cannot be done. */
    @Nullable
    @RequiresApi(Build.VERSION_CODES.R)
    public static ResourcesLoader create(@NonNull Context context, @NonNull Map<Integer, Integer> colors) {
        try {
            return ColorResourcesLoaderCreator.create(context, colors);
        } catch (Throwable t) {
            return null;
        }
    }
}
