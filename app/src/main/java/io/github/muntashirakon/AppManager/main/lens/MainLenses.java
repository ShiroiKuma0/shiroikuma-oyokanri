// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main.lens;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fork (白い熊, +162): the registry of {@link MainLens}es, keyed by the ids the pill shelf already
 * stores.
 *
 * <p>The keys are deliberately the same strings as {@code ShelfPrefs.SCREEN_*}, so a pill someone
 * made before lenses existed keeps working untouched — {@code MainActivity.applyShelfPill} asks this
 * registry first and only falls back to starting an activity when the id names a real screen
 * (battery, monitor).
 *
 * <p>Instances are <b>singletons</b>, and that is load-bearing: a lens caches what {@code prepare()}
 * worked out, and a fresh instance per pipeline pass would throw the cache away every time the
 * search box was typed in.
 */
public final class MainLenses {
    private static final Map<String, MainLens> LENSES;

    static {
        Map<String, MainLens> map = new LinkedHashMap<>();
        BackupsLens backups = new BackupsLens();
        map.put(backups.id(), backups);
        SnoopingLens snooping = new SnoopingLens();
        map.put(snooping.id(), snooping);
        SisterAppsLens sister = new SisterAppsLens();
        map.put(sister.id(), sister);
        LENSES = Collections.unmodifiableMap(map);
    }

    /** The lens with this id, or null — including for null, and for an id that is a real screen. */
    @Nullable
    public static MainLens get(@Nullable String id) {
        return id == null ? null : LENSES.get(id);
    }

    public static boolean isLensId(@Nullable String id) {
        return id != null && LENSES.containsKey(id);
    }

    @NonNull
    public static Map<String, MainLens> all() {
        return LENSES;
    }

    /**
     * Fork (白い熊): typed handles, for the sort comparators that read a lens's cached facts even
     * when that lens is not the one on screen.
     */
    @NonNull
    public static BackupsLens backups() {
        return (BackupsLens) LENSES.get(BackupsLens.ID);
    }

    @NonNull
    public static SnoopingLens snooping() {
        return (SnoopingLens) LENSES.get(SnoopingLens.ID);
    }

    @NonNull
    public static SisterAppsLens sister() {
        return (SisterAppsLens) LENSES.get(SisterAppsLens.ID);
    }

    private MainLenses() {
    }
}
