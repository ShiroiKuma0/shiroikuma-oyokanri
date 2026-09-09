// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.appdata;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fork: which categories 白い熊 has chosen for each app, remembered per package.
 * <p>
 * This is what makes per-app control work inside a <b>bulk</b> backup without any bulk UI existing:
 * the picker is only ever shown for a single app, and a batch silently applies whatever was last
 * chosen for each of its apps. An app that has never been customised has no entry here and is sent
 * no {@code items} at all, which means it exports its own recommended default set.
 * <p>
 * Its own preferences file, so it travels in Settings export/import with no serialisation of its
 * own — the same reason the notes and snooping stores are separate files.
 * <p>
 * <b>Two sets are stored, not one.</b> {@code chosen} is what was ticked; {@code seen} is what the
 * app offered at the time. Without {@code seen}, a category the app <i>added in a later version</i>
 * would be indistinguishable from one deliberately unticked, and would stay silently out of every
 * backup for ever. With it, an unknown id is new and takes the app's own default.
 */
public class AppDataSelection {
    private static final String PREF_FILE = "shiroikuma_appdata_items";
    private static final String KEY_CHOSEN = "chosen";
    private static final String KEY_SEEN = "seen";

    /** A remembered choice: what was ticked, and what was on offer when it was made. */
    public static class Stored {
        public final Set<String> chosen;
        public final Set<String> seen;

        Stored(Set<String> chosen, Set<String> seen) {
            this.chosen = chosen;
            this.seen = seen;
        }
    }

    private AppDataSelection() {
    }

    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    /** {@code null} when this app has never been customised — which is not the same as "nothing". */
    @Nullable
    public static Stored get(@NonNull Context context, @NonNull String packageName) {
        String raw = prefs(context).getString(packageName, null);
        if (raw == null) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(raw);
            return new Stored(toSet(o.optJSONArray(KEY_CHOSEN)), toSet(o.optJSONArray(KEY_SEEN)));
        } catch (Throwable th) {
            return null;
        }
    }

    public static void set(@NonNull Context context, @NonNull String packageName,
                           @NonNull List<String> chosen, @NonNull List<String> seen) {
        try {
            JSONObject o = new JSONObject();
            o.put(KEY_CHOSEN, new JSONArray(chosen));
            o.put(KEY_SEEN, new JSONArray(seen));
            prefs(context).edit().putString(packageName, o.toString()).apply();
        } catch (Throwable ignore) {
        }
    }

    /** Forget a choice, so the app goes back to exporting its own default set. */
    public static void clear(@NonNull Context context, @NonNull String packageName) {
        prefs(context).edit().remove(packageName).apply();
    }

    public static boolean isCustomised(@NonNull Context context, @NonNull String packageName) {
        return prefs(context).contains(packageName);
    }

    /**
     * Resolve a remembered choice against what the app offers <i>now</i>.
     * <ul>
     * <li>offered and chosen → on;</li>
     * <li>offered, not chosen, but seen before → off, because that was deliberate;</li>
     * <li>offered but never seen → the app's own default, because it is new.</li>
     * </ul>
     * An id that has disappeared from the app is dropped rather than sent back to it, which would
     * earn an {@code ERROR:unknown category in items}.
     */
    /**
     * Fork (白い熊): what this app would actually export right now, stored choice or not.
     *
     * <p>{@link #reconcile} answers only half the question — it needs a {@link Stored}, and the
     * commonest case by far is an app that has never been customised, where the answer is the
     * app's own defaults. A caller that wants to <b>say</b> whether anything is being left out
     * needs both halves, and two callers working that out separately is how they come to
     * disagree.
     */
    @NonNull
    public static List<String> effective(@Nullable Stored stored,
                                         @NonNull List<AppDataCategory> offered) {
        if (stored != null) {
            return reconcile(stored, offered);
        }
        List<String> defaults = new ArrayList<>();
        for (AppDataCategory category : offered) {
            if (category.defaultOn) {
                defaults.add(category.id);
            }
        }
        return defaults;
    }

    @NonNull
    public static List<String> reconcile(@NonNull Stored stored, @NonNull List<AppDataCategory> offered) {
        List<String> effective = new ArrayList<>();
        for (AppDataCategory category : offered) {
            boolean on;
            if (stored.chosen.contains(category.id)) {
                on = true;
            } else if (stored.seen.contains(category.id)) {
                on = false;
            } else {
                on = category.defaultOn;
            }
            if (on) {
                effective.add(category.id);
            }
        }
        return effective;
    }

    @NonNull
    private static Set<String> toSet(@Nullable JSONArray array) {
        Set<String> out = new HashSet<>();
        if (array == null) {
            return out;
        }
        for (int i = 0; i < array.length(); ++i) {
            String s = array.optString(i, null);
            if (s != null && !s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }
}
