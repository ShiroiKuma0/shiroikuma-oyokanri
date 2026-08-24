// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.filters.options;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import io.github.muntashirakon.AppManager.filters.IFilterableAppInfo;
import io.github.muntashirakon.AppManager.utils.AppNotesManager;
import io.github.muntashirakon.AppManager.utils.ContextUtils;

/**
 * Fork: filter on the free-text per-app note ({@link AppNotesManager}).
 *
 * <p>A note is written precisely because the app needed remembering — "keep
 * frozen", "breaks banking", "needed by the launcher" — so the set of apps
 * carrying one is a list worth being able to summon, and until now there was no
 * way to see it except by scrolling for the pill.
 *
 * <p>The store is read live rather than snapshotted onto the option, because a
 * filter option outlives one pass: an {@code AppsFilterProfile} holds its
 * options between runs, and a cached key set would go stale the moment a note
 * was edited. {@code SharedPreferences} keeps the whole file in memory after the
 * first read, so per-app lookups cost a map hit.
 */
public class NoteOption extends FilterOption {
    private final Map<String, Integer> mKeysWithType = new LinkedHashMap<String, Integer>() {{
        put(KEY_ALL, TYPE_NONE);
        put("with_note", TYPE_NONE);
        put("without_note", TYPE_NONE);
        put("contains", TYPE_STR_SINGLE);
        put("regex", TYPE_REGEX);
    }};

    public NoteOption() {
        super("note");
    }

    @NonNull
    @Override
    public Map<String, Integer> getKeysWithType() {
        return mKeysWithType;
    }

    @NonNull
    @Override
    public TestResult test(@NonNull IFilterableAppInfo info, @NonNull TestResult result) {
        Context context = ContextUtils.getContext();
        String note = AppNotesManager.getNote(context, info.getPackageName());
        // A note trimmed to nothing is no note: AppNotesManager deletes on save,
        // but a value written by an older build (or restored from an export made
        // by one) can still be blank, and it must not count as "has a note".
        String trimmed = note != null ? note.trim() : "";
        boolean has = !trimmed.isEmpty();
        switch (key) {
            case KEY_ALL:
                return result.setMatched(true);
            case "with_note":
                return result.setMatched(has);
            case "without_note":
                return result.setMatched(!has);
            case "contains":
                return result.setMatched(has && value != null
                        && trimmed.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT)));
            case "regex":
                return result.setMatched(has && regexValue != null && regexValue.matcher(trimmed).find());
            default:
                throw new UnsupportedOperationException("Invalid key " + key);
        }
    }

    @NonNull
    @Override
    public CharSequence toLocalizedString(@NonNull Context context) {
        switch (key) {
            case KEY_ALL:
                return "Apps with or without a note";
            case "with_note":
                return "Only the apps carrying a note";
            case "without_note":
                return "Only the apps with no note";
            case "contains":
                return "Only the apps whose note contains " + value;
            case "regex":
                return "Only the apps whose note matches " + value;
            default:
                throw new UnsupportedOperationException("Invalid key " + key);
        }
    }
}
