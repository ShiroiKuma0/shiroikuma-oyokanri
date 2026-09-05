// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.appdata;

import android.annotation.UserIdInt;
import android.content.Context;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.utils.ContextUtils;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;
import io.github.muntashirakon.dialog.SearchableMultiChoiceDialogBuilder;

/**
 * Fork: the per-app category picker for App-supplied data.
 * <p>
 * Lives here rather than in one dialog because two different backup dialogs offer it — the bottom
 * sheet's options list and the single-app dialog — and a second copy would drift.
 * <p>
 * What is chosen here is also what a <b>bulk</b> backup applies for this app, which is the whole
 * point of remembering it: per-app control inside a batch, with no batch UI.
 */
public class AppDataCategoryPicker {
    private AppDataCategoryPicker() {
    }

    /** Whether this app can be asked at all — a manifest read, so it never starts anything. */
    public static boolean isAvailable(@NonNull Context context, @Nullable String packageName) {
        return packageName != null && AppDataContract.isSupported(context, packageName);
    }

    /**
     * Ask the app what it can export, then let 白い熊 tick a subset.
     * <p>
     * The listing is a broadcast round trip and may have to thaw the app, so this happens only
     * when the picker is opened — never while drawing a list of apps.
     *
     * @param onDone run on the main thread once the picker closes, however it closed.
     */
    @MainThread
    public static void show(@NonNull Context context, @NonNull String packageName,
                            @UserIdInt int userId, @Nullable Runnable onDone) {
        UIUtils.displayShortToast(R.string.appdata_categories_asking);
        ThreadUtils.postOnBackgroundThread(() -> {
            List<AppDataCategory> categories = new AppDataTransfer(ContextUtils.getContext())
                    .listCategories(packageName, userId);
            ThreadUtils.postOnMainThread(() -> {
                if (categories == null || categories.isEmpty()) {
                    UIUtils.displayLongToast(R.string.appdata_categories_unavailable);
                    if (onDone != null) {
                        onDone.run();
                    }
                    return;
                }
                showDialog(context, packageName, categories, onDone);
            });
        });
    }

    @MainThread
    private static void showDialog(@NonNull Context context, @NonNull String packageName,
                                   @NonNull List<AppDataCategory> categories, @Nullable Runnable onDone) {
        List<String> ids = new ArrayList<>(categories.size());
        List<CharSequence> labels = new ArrayList<>(categories.size());
        List<String> offered = new ArrayList<>(categories.size());
        for (AppDataCategory category : categories) {
            ids.add(category.id);
            offered.add(category.id);
            // Children indented under their parent; the app sends parents first.
            labels.add(category.isChild() ? "    " + category.label : category.label);
        }
        AppDataSelection.Stored stored = AppDataSelection.get(context, packageName);
        List<String> ticked = stored != null
                ? AppDataSelection.reconcile(stored, categories)
                : AppDataCategory.defaultIds(categories);
        new SearchableMultiChoiceDialogBuilder<>(context, ids, labels)
                .setTitle(R.string.appdata_categories)
                .addSelections(ticked)
                .setPositiveButton(R.string.save, (dialog, which, selections) -> {
                    // Both sets are stored: what was ticked, and what was on offer at the time.
                    // Without the second, a category the app adds later is indistinguishable from
                    // one deliberately unticked and would stay out of every backup for ever.
                    AppDataSelection.set(context, packageName, selections, offered);
                    if (onDone != null) {
                        onDone.run();
                    }
                })
                // Forget the choice, so the app exports what IT recommends — deliberately not the
                // same as ticking everything.
                .setNeutralButton(R.string.appdata_categories_use_defaults, (dialog, which, selections) -> {
                    AppDataSelection.clear(context, packageName);
                    if (onDone != null) {
                        onDone.run();
                    }
                })
                .setNegativeButton(R.string.cancel, (dialog, which, selections) -> {
                    if (onDone != null) {
                        onDone.run();
                    }
                })
                .show();
    }
}
