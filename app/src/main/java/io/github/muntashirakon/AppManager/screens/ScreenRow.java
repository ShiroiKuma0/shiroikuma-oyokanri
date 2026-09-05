// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.screens;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Fork (白い熊, +118): one row of a sibling screen.
 *
 * <p>The three screens answer different questions but draw the same row: an app on the left, and
 * on the right the few lines that screen is about. So there is one row model and one adapter, and
 * a screen supplies the words — which is what keeps 保存一覧, 盗み見一覧 and 仲間 looking like the
 * main list and like each other, without any of them owning a copy of the row.
 */
public class ScreenRow {
    @NonNull
    public final String packageName;
    public final int userId;
    @NonNull
    public final CharSequence label;
    /** The line under the label — normally the package id. */
    @Nullable
    public final CharSequence subtitle;
    /** Feeds {@code ImageLoader.versionedTag}, so a reinstalled app's icon is not the old one. */
    public final long iconVersion;
    /** The right-hand column, top line first. Four lines is what the row can hold. */
    @NonNull
    public final List<CharSequence> right = new ArrayList<>();
    /** Colour for the first right-hand line, or 0 for the screen's ordinary ink. */
    public int accent;
    public boolean installed = true;
    public boolean frozen;
    /**
     * The package info the icon is loaded from, when there is one. Null for an orphaned backup —
     * an app that is gone has no icon to load, and the loader falls back to its default rather
     * than leaving a hole.
     */
    @Nullable
    public android.content.pm.ApplicationInfo info;
    /** The app's uid, shown in the card's id line exactly as the main list shows it. */
    public int uid;
    /**
     * Every backup directory this row stands for — the backups screen selects an APP and acts on
     * all of them, so the row has to know which they are. Empty for a screen that has none.
     */
    @NonNull
    public final List<String> relativeDirs = new ArrayList<>();
    /** What {@link ScreenSource#applySort} orders by; meaning is the source's own. */
    public long sortKey;
    /** Secondary ordering value, for a source that needs two. */
    public long sortKey2;

    public ScreenRow(@NonNull String packageName, int userId, @NonNull CharSequence label,
                     @Nullable CharSequence subtitle, long iconVersion) {
        this.packageName = packageName;
        this.userId = userId;
        this.label = label;
        this.subtitle = subtitle;
        this.iconVersion = iconVersion;
    }

    @NonNull
    public ScreenRow add(@Nullable CharSequence line) {
        if (line != null) {
            right.add(line);
        }
        return this;
    }
}
