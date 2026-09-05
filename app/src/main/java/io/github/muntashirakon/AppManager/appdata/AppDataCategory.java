// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.appdata;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Fork: one exportable category as an app reports it from {@code LIST_CATEGORIES}.
 * <p>
 * Wire format is one line per category, tab separated:
 * <pre>id &lt;TAB&gt; label &lt;TAB&gt; parent-id &lt;TAB&gt; default</pre>
 * The third and fourth fields are optional and <b>positional</b> — a top-level entry that is off by
 * default still carries an empty third field. {@code default} is {@code on}/{@code off}, and absent
 * means on.
 * <p>
 * The defaults are the app's own recommendation, not ours: it is told to mark off anything large,
 * derived and re-creatable (cover caches, generated thumbnails, downloaded media) while everything
 * authored stays on. That is why <b>an absent {@code items} means the app's default set and not
 * everything</b>, and why this list is re-read from the app every time rather than remembered.
 */
public class AppDataCategory {
    public final String id;
    public final String label;
    @Nullable
    public final String parentId;
    public final boolean defaultOn;

    private AppDataCategory(@NonNull String id, @NonNull String label, @Nullable String parentId,
                            boolean defaultOn) {
        this.id = id;
        this.label = label;
        this.parentId = parentId;
        this.defaultOn = defaultOn;
    }

    public boolean isChild() {
        return parentId != null && !parentId.isEmpty();
    }

    /**
     * Parse a whole {@code OK:}-prefixed reply. Returns {@code null} when the reply is a refusal or
     * is unparseable — never throws, because this is another app's output.
     */
    @Nullable
    public static List<AppDataCategory> parseReply(@Nullable String result) {
        if (result == null || !result.startsWith(AppDataContract.OK_PREFIX)) {
            return null;
        }
        String body = result.substring(AppDataContract.OK_PREFIX.length());
        List<AppDataCategory> out = new ArrayList<>();
        for (String line : body.split("\n")) {
            AppDataCategory category = parseLine(line);
            if (category != null) {
                out.add(category);
            }
        }
        return out.isEmpty() ? null : out;
    }

    @Nullable
    private static AppDataCategory parseLine(@Nullable String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // -1 keeps trailing empty fields, which matters: the third field is empty for a top-level
        // entry that still carries a fourth.
        String[] parts = trimmed.split("\t", -1);
        if (parts.length < 2) {
            return null;
        }
        String id = parts[0].trim();
        String label = parts[1].trim();
        if (id.isEmpty() || label.isEmpty()) {
            return null;
        }
        String parentId = parts.length > 2 && !parts[2].trim().isEmpty() ? parts[2].trim() : null;
        boolean defaultOn = parts.length <= 3 || !"off".equalsIgnoreCase(parts[3].trim());
        return new AppDataCategory(id, label, parentId, defaultOn);
    }

    /** The ids an app recommends when nothing has been chosen for it. */
    @NonNull
    public static List<String> defaultIds(@NonNull List<AppDataCategory> categories) {
        List<String> ids = new ArrayList<>();
        for (AppDataCategory category : categories) {
            if (category.defaultOn) {
                ids.add(category.id);
            }
        }
        return ids;
    }
}
