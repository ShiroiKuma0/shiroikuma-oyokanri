// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.logs.Log;

/**
 * Fork (白い熊, +118): the pill shelf under the main toolbar — what is on it, in what order, and
 * what each pill does.
 *
 * <p><b>Why it exists.</b> Filters, sorts and profile filters were all there and all good, and
 * every one of them had to be re-chosen from a dialog each time. A view worth returning to — the
 * frozen apps, the ones whose backup is older than the app, the 必要 profile — had nowhere to
 * live. A pill is that place.
 *
 * <p><b>Two kinds, and only two.</b> A {@link #KIND_VIEW} pill carries a whole view of the main
 * list: the filter flags, the sort, the profile filters and the search query, captured together
 * because they only mean anything together. A {@link #KIND_SCREEN} pill opens a screen that answers
 * a question this list cannot — the backups, the snooping overview, the sister apps — or one of the
 * screens the fork already has.
 *
 * <p>Stored as JSON in its own preferences file, classified TOOLBAR in
 * {@code SettingsBackupManager}, so the shelf travels in Export/Import with no serialisation of its
 * own. The stored {@code kind} strings and preset ids are a <b>wire format</b> — an exported shelf
 * from an older build must still load, so never rename one.
 */
public final class ShelfPrefs {
    public static final String TAG = ShelfPrefs.class.getSimpleName();

    public static final String PREF_FILE = "shiroikuma_shelf";
    private static final String KEY_PILLS = "pills";

    /** A saved view of the main list. */
    public static final String KIND_VIEW = "view";
    /** A screen to open. */
    public static final String KIND_SCREEN = "screen";

    // Screen ids — wire format, shared with ListScreenActivity.
    public static final String SCREEN_BACKUPS = "backups";
    public static final String SCREEN_SNOOPING = "snooping";
    public static final String SCREEN_SISTER = "sister";
    public static final String SCREEN_BATTERY = "battery";
    public static final String SCREEN_MONITOR = "monitor";

    /** One pill. Mutable only in its name, which is the one thing a person edits. */
    public static final class Pill {
        @NonNull
        public final String id;
        @NonNull
        public String name;
        @NonNull
        public final String kind;
        /** A screen id, or the JSON of a captured view. */
        @NonNull
        public final String payload;

        public Pill(@NonNull String id, @NonNull String name, @NonNull String kind,
                    @NonNull String payload) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.payload = payload;
        }

        public boolean isScreen() {
            return KIND_SCREEN.equals(kind);
        }
    }

    /**
     * A view of the main list, captured whole.
     *
     * <p>The parts are stored together because they are only meaningful together: "frozen, sorted
     * by name" and "frozen, sorted by size" are two different answers to two different questions,
     * and a pill that restored the filter but not the sort would give you neither.
     */
    public static final class ViewState {
        public final int filterFlags;
        public final int sortBy;
        public final boolean reverseSort;
        @NonNull
        public final Set<String> profilesInclude;
        @NonNull
        public final Set<String> profilesExclude;
        @Nullable
        public final String query;
        public final int queryType;

        public ViewState(int filterFlags, int sortBy, boolean reverseSort,
                         @NonNull Set<String> profilesInclude, @NonNull Set<String> profilesExclude,
                         @Nullable String query, int queryType) {
            this.filterFlags = filterFlags;
            this.sortBy = sortBy;
            this.reverseSort = reverseSort;
            this.profilesInclude = profilesInclude;
            this.profilesExclude = profilesExclude;
            this.query = query;
            this.queryType = queryType;
        }

        @NonNull
        public String toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("flags", filterFlags);
                o.put("sort", sortBy);
                o.put("reverse", reverseSort);
                o.put("include", new JSONArray(profilesInclude));
                o.put("exclude", new JSONArray(profilesExclude));
                if (!TextUtils.isEmpty(query)) {
                    o.put("query", query);
                    o.put("query_type", queryType);
                }
                return o.toString();
            } catch (Throwable th) {
                return "{}";
            }
        }

        @NonNull
        public static ViewState fromJson(@Nullable String json) {
            int flags = 0;
            int sort = MainListOptions.SORT_BY_APP_LABEL;
            boolean reverse = false;
            Set<String> include = new HashSet<>();
            Set<String> exclude = new HashSet<>();
            String query = null;
            int queryType = 0;
            try {
                JSONObject o = new JSONObject(json == null ? "{}" : json);
                flags = o.optInt("flags", 0);
                sort = o.optInt("sort", sort);
                reverse = o.optBoolean("reverse", false);
                readInto(o.optJSONArray("include"), include);
                readInto(o.optJSONArray("exclude"), exclude);
                query = o.has("query") ? o.optString("query", null) : null;
                queryType = o.optInt("query_type", 0);
            } catch (Throwable th) {
                Log.w(TAG, "unreadable view payload; falling back to the unfiltered list", th);
            }
            return new ViewState(flags, sort, reverse, include, exclude, query, queryType);
        }

        private static void readInto(@Nullable JSONArray array, @NonNull Set<String> out) {
            if (array == null) return;
            for (int i = 0; i < array.length(); ++i) {
                String s = array.optString(i, null);
                if (!TextUtils.isEmpty(s)) out.add(s);
            }
        }

        /** Whether this view narrows the list at all — an empty one is the plain list. */
        public boolean isNarrowing() {
            return filterFlags != 0 || !profilesInclude.isEmpty() || !profilesExclude.isEmpty()
                    || !TextUtils.isEmpty(query);
        }
    }

    /** A ready-made view offered when adding a pill. */
    public static final class Preset {
        @StringRes
        public final int labelRes;
        public final int filterFlags;

        Preset(@StringRes int labelRes, int filterFlags) {
            this.labelRes = labelRes;
            this.filterFlags = filterFlags;
        }
    }

    /**
     * The ready-made views. Two of these — recently changed and biggest apps — are deliberately
     * NOT screens: they are this list with a sort, and a screen that differs from the main list
     * only in its ORDER would be a second copy of the same page to maintain.
     */
    @NonNull
    public static List<Preset> presets() {
        return Arrays.asList(
                new Preset(R.string.filter_frozen_apps, MainListOptions.FILTER_FROZEN_APPS),
                new Preset(R.string.filter_unfrozen_apps, MainListOptions.FILTER_UNFROZEN_APPS),
                new Preset(R.string.filter_running_apps, MainListOptions.FILTER_RUNNING_APPS),
                new Preset(R.string.filter_apps_with_backups, MainListOptions.FILTER_APPS_WITH_BACKUPS),
                new Preset(R.string.filter_apps_without_backups, MainListOptions.FILTER_APPS_WITHOUT_BACKUPS),
                new Preset(R.string.filter_apps_with_outdated_backups, MainListOptions.FILTER_APPS_WITH_OUTDATED_BACKUPS),
                new Preset(R.string.filter_apps_with_notes, MainListOptions.FILTER_APPS_WITH_NOTES),
                new Preset(R.string.filter_sister_apps, MainListOptions.FILTER_SISTER_APPS),
                new Preset(R.string.uninstalled_apps, MainListOptions.FILTER_UNINSTALLED_APPS),
                new Preset(R.string.filter_user_apps, MainListOptions.FILTER_USER_APPS),
                new Preset(R.string.filter_system_apps, MainListOptions.FILTER_SYSTEM_APPS));
    }

    /** The screens a pill may open, in the order they are offered. */
    @NonNull
    public static List<String> screenIds() {
        return Arrays.asList(SCREEN_BACKUPS, SCREEN_SNOOPING, SCREEN_SISTER, SCREEN_BATTERY,
                SCREEN_MONITOR);
    }

    @StringRes
    public static int screenTitle(@NonNull String screenId) {
        switch (screenId) {
            case SCREEN_BACKUPS:
                return R.string.screen_backups;
            case SCREEN_SNOOPING:
                return R.string.screen_snooping;
            case SCREEN_SISTER:
                return R.string.screen_sister;
            case SCREEN_BATTERY:
                return R.string.battery_title;
            case SCREEN_MONITOR:
                return R.string.monitor_title;
            default:
                return R.string.app_name;
        }
    }

    public static int screenIcon(@NonNull String screenId) {
        switch (screenId) {
            case SCREEN_BACKUPS:
                return R.drawable.ic_backup_restore;
            case SCREEN_SNOOPING:
                return R.drawable.ic_cctv_off;
            case SCREEN_SISTER:
                return R.drawable.ic_file_plus;
            case SCREEN_BATTERY:
                return R.drawable.ic_battery_history;
            case SCREEN_MONITOR:
                return R.drawable.ic_monitor;
            default:
                return 0;
        }
    }

    private ShelfPrefs() {
    }

    @NonNull
    private static SharedPreferences sp(@NonNull Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    /**
     * The shelf, in order. Empty on a fresh install — an empty shelf shows only the {@code +},
     * which is the honest state: nothing has been saved yet, and inventing a default set would
     * put someone else's idea of a useful view on 白い熊's screen.
     */
    @NonNull
    public static List<Pill> load(@NonNull Context ctx) {
        List<Pill> pills = new ArrayList<>();
        String raw = sp(ctx).getString(KEY_PILLS, null);
        if (TextUtils.isEmpty(raw)) {
            return pills;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); ++i) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", UUID.randomUUID().toString());
                String name = o.optString("name", "");
                String kind = o.optString("kind", KIND_VIEW);
                String payload = o.optString("payload", "");
                if (TextUtils.isEmpty(name)) continue;
                pills.add(new Pill(id, name, kind, payload));
            }
        } catch (Throwable th) {
            // A shelf that will not parse is not worth taking the main window down for.
            Log.w(TAG, "could not read the shelf; starting from an empty one", th);
        }
        return pills;
    }

    public static void save(@NonNull Context ctx, @NonNull List<Pill> pills) {
        JSONArray array = new JSONArray();
        for (Pill p : pills) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                o.put("kind", p.kind);
                o.put("payload", p.payload);
                array.put(o);
            } catch (Throwable ignore) {
            }
        }
        sp(ctx).edit().putString(KEY_PILLS, array.toString()).apply();
    }

    @NonNull
    public static Pill newView(@NonNull String name, @NonNull ViewState state) {
        return new Pill(UUID.randomUUID().toString(), name, KIND_VIEW, state.toJson());
    }

    @NonNull
    public static Pill newScreen(@NonNull String name, @NonNull String screenId) {
        return new Pill(UUID.randomUUID().toString(), name, KIND_SCREEN, screenId);
    }

    /** Append and persist. */
    public static void add(@NonNull Context ctx, @NonNull Pill pill) {
        List<Pill> pills = load(ctx);
        pills.add(pill);
        save(ctx, pills);
    }

    public static void remove(@NonNull Context ctx, @NonNull String id) {
        List<Pill> pills = load(ctx);
        for (int i = 0; i < pills.size(); ++i) {
            if (pills.get(i).id.equals(id)) {
                pills.remove(i);
                break;
            }
        }
        save(ctx, pills);
    }

    public static void rename(@NonNull Context ctx, @NonNull String id, @NonNull String name) {
        List<Pill> pills = load(ctx);
        for (Pill p : pills) {
            if (p.id.equals(id)) {
                p.name = name;
                break;
            }
        }
        save(ctx, pills);
    }

    /** Move a pill within the shelf. Used by the drag handler, which persists on drop. */
    public static void move(@NonNull List<Pill> pills, int from, int to) {
        if (from < 0 || to < 0 || from >= pills.size() || to >= pills.size()) {
            return;
        }
        Collections.swap(pills, from, to);
    }
}
