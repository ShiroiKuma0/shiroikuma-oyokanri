// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main.lens;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.WorkerThread;

import java.util.List;

import io.github.muntashirakon.AppManager.main.ApplicationItem;

/**
 * Fork (白い熊, +162): a <b>lens</b> — a named state of the main list that changes <em>what some row
 * elements show</em>, and nothing else.
 *
 * <p>保存一覧, 盗み見一覧 and 仲間 began as separate screens ({@code screens/ListScreenActivity}), and
 * each one paid for that separateness: no search, no filters, no profile filters, no saved views, no
 * pill shelf, no app pane, no general batch operations. They had to reimplement selection, a
 * selection bar, sorting and a column picker, and they still drew the main list's own row through
 * {@code MainCardBinder} — so the row was shared and everything around it was duplicated.
 *
 * <p>A lens inverts that. The list, its filters, its sort, its selection, its panes, its frames, its
 * per-geometry column count and its batch operations are all the main list's, unchanged; the lens
 * supplies only the right-hand column's lines and says which apps belong on the page. The plain list
 * is simply "no lens", and the way out is the way in — tapping the lit pill again.
 *
 * <p><b>What a lens deliberately cannot do.</b> It does not take over the row's click, because a tap
 * unrolls the pane (+118) and 白い熊's whole point was to keep that. It does not contribute filter
 * flags either: those are a persisted bitmask and a wire format for saved views, and a lens is
 * neither persisted nor saved into one. It narrows through {@link #includes} instead.
 *
 * <p>Not everything should be a lens. Battery and Process stay real screens because their rows are
 * not apps at all — one is per-uid with its own counters and history chart, the other per-pid with
 * process grouping and leak clusters. Neither is an {@link ApplicationItem}, so neither would gain
 * anything from this pipeline.
 */
public interface MainLens {
    /**
     * Stable wire key. It is what the pill shelf stores, so <b>never rename one</b>: the ids match
     * {@code ShelfPrefs.SCREEN_*} exactly, which is what lets pills created before lenses existed
     * keep working with no migration.
     */
    @NonNull
    String id();

    @StringRes
    int titleRes();

    /**
     * Whether this app belongs on the page at all. Runs for every candidate on every pipeline pass,
     * so it must be cheap and must not touch the disk — anything expensive belongs in
     * {@link #prepare}.
     */
    boolean includes(@NonNull ApplicationItem item);

    /**
     * The right-hand column, top line first. The first line is the headline and is drawn at the
     * version's weight; the second gets its own row; everything after that is joined beneath.
     *
     * <p>Return a {@code CharSequence}, not a {@code String}, where a span is wanted — the renderer
     * keeps spans, and {@code main/PillSpan} is how a pill is drawn inside one of these lines.
     */
    @NonNull
    List<CharSequence> rightLines(@NonNull Context context, @NonNull ApplicationItem item);

    /** Colour for the headline, or 0 for the theme's ordinary ink. */
    int accent(@NonNull ApplicationItem item);

    /**
     * Fork (白い熊): whether this lens only <b>narrows</b> the page and never draws it.
     * <p>
     * 仲間 is the case: "show me only my sister apps" is a question you ask <em>about</em> whatever
     * you are already looking at. With 保存 lit the page is still the backups page and 仲間 merely
     * restricts it to sister apps; with 盗み見 lit it is still the snooping page; with neither, it
     * is the plain list, filtered. So a filter lens supplies no right-hand column and never
     * competes for it — which is what lets several lenses be on at once without the row having to
     * decide whose lines win. A display lens (保存, 盗み見) owns the column and stays exclusive
     * with the other display lenses, because there is only one column to own.
     */
    default boolean filterOnly() {
        return false;
    }

    /**
     * Optional off-thread enrichment, run once per pipeline pass before the rows are published.
     * A lens whose lines are already in {@link ApplicationItem} needs nothing here; one that has to
     * resolve something per app caches it here rather than in {@link #rightLines}, which is called
     * at bind time on the main thread.
     */
    @WorkerThread
    default void prepare(@NonNull Context context, @NonNull List<ApplicationItem> items) {
    }

    /**
     * Whether the right column should be given the wider proportions (the ones the battery screen
     * and the sibling screens use), rather than the list's default narrow block sized against a
     * signature algorithm.
     */
    default boolean wideRight() {
        return true;
    }

    /**
     * Throw away whatever {@link #prepare} cached. Called on pull-to-refresh, which is the one
     * gesture that means "read it again from the device" — a lens whose facts are cheap can ignore
     * it, and one whose facts come off the disk or the package manager must not.
     */
    default void invalidate() {
    }

    /** Convenience for callers holding a possibly-null lens. */
    static boolean isLens(@Nullable MainLens lens) {
        return lens != null;
    }
}
