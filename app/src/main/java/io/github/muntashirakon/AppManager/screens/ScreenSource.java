// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.screens;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/**
 * Fork (白い熊, +118): what one sibling screen is.
 *
 * <p>A screen earns its place only when its right-hand column and its underlying data genuinely
 * differ from the main list. "Recently changed" and "biggest apps" do not — they are this list with
 * a sort, and they live on the shelf as saved views instead. The three that do differ are backups
 * (which must include apps that are no longer installed), the snooping overview (which asks the
 * resolver rather than the package manager) and the sister apps (which read a manifest contract).
 */
public interface ScreenSource {
    @StringRes
    int titleRes();

    /**
     * Build every row. Always called on a background thread — one of these walks the backup
     * directory and another runs a resolver pass per app.
     */
    @WorkerThread
    @NonNull
    List<ScreenRow> load(@NonNull Context context);

    /** Labels for the orders this screen offers, in menu order. */
    @NonNull
    List<CharSequence> sortLabels(@NonNull Context context);

    /** Order {@code rows} in place by the given index into {@link #sortLabels}. */
    void applySort(@NonNull List<ScreenRow> rows, int sortMode);

    /** What a tap on a row does. Each screen leads somewhere its own rows imply. */
    void onRowClicked(@NonNull AppCompatActivity activity, @NonNull ScreenRow row);

    /** Shown in place of the list when {@link #load} returns nothing. */
    @StringRes
    int emptyTextRes();
}
