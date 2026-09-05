// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.backup.dialog;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.graphics.ColorUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.appdata.AppDataCategory;
import io.github.muntashirakon.AppManager.appdata.AppDataContract;
import io.github.muntashirakon.AppManager.appdata.AppDataSelection;
import io.github.muntashirakon.AppManager.appdata.AppDataTransfer;
import io.github.muntashirakon.AppManager.backup.BackupFlags;
import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.self.imagecache.ImageLoader;
import io.github.muntashirakon.AppManager.types.UserPackagePair;
import io.github.muntashirakon.AppManager.utils.ForkDialog;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;
import io.github.muntashirakon.widget.FlowLayout;

/**
 * Fork (白い熊, +121): the batch backup table — every app in the selection, each with its own
 * parts ticked.
 *
 * <p>A batch used to be one set of flags applied to everything, which is only right when every
 * app is the same kind of thing. They are not: a messenger's own exported data is the whole point
 * of backing it up, a game's OBB is gigabytes you may not want, and a system app has no external
 * data at all. So the parts are per app, on one screen, where the differences are visible while
 * you decide.
 *
 * <p><b>App-supplied data folds out per app.</b> Asking every app what it can export costs a
 * thaw and a round trip each, so nothing is asked until the row is opened — and then only that
 * app is asked. What comes back is ticked according to the app's own defaults, and changing it
 * writes straight to {@link AppDataSelection}, which is where the exporter already reads from:
 * the table sets the same preference the picker does rather than inventing a parallel one.
 */
public class BatchBackupTableDialog {
    /** Told the per-app selections when Back up is pressed. */
    public interface Listener {
        /**
         * @param perPackageAppData the App-supplied categories chosen here, per app, for
         *                          <b>this backup only</b> (白い熊, +133)
         */
        void onBackup(@NonNull Map<String, Integer> perPackageFlags, int fallbackFlags,
                      @NonNull Map<String, String[]> perPackageAppData);

        /**
         * Restore and delete are a different question about the same apps, and they belong to the
         * dialog that already answers it well. The table hands them straight over rather than
         * growing a second copy.
         */
        void onRestoreOrDelete();
    }

    private final AppCompatActivity mActivity;
    private final List<UserPackagePair> mPairs;
    private final Listener mListener;
    private final Map<String, Integer> mFlags = new LinkedHashMap<>();
    /**
     * Fork (白い熊, +133): the categories ticked here, held in memory and <b>never written to
     * {@link AppDataSelection}</b>.
     *
     * <p>The table is a working surface: you sweep across forty apps opening folds and adjusting
     * ticks to shape one backup. Writing each of those to the app's stored preference made every
     * future backup of every app you touched inherit a decision that was only ever about this
     * run — silently, and forty times over. The choice travels with the operation instead.
     */
    private final Map<String, String[]> mAppData = new LinkedHashMap<>();
    private final int mSupportedFlags;
    private final int mDefaultFlags;

    public BatchBackupTableDialog(@NonNull AppCompatActivity activity,
                                  @NonNull List<UserPackagePair> pairs,
                                  @NonNull Listener listener) {
        mActivity = activity;
        mPairs = pairs;
        mListener = listener;
        int supported = BackupFlags.getSupportedBackupFlags();
        supported &= ~BackupFlags.BACKUP_NO_SIGNATURE_CHECK;
        mSupportedFlags = supported;
        mDefaultFlags = BackupFlags.fromPref().getFlags();
        for (UserPackagePair pair : pairs) {
            mFlags.put(pair.getPackageName(), mDefaultFlags);
        }
    }

    public void show() {
        Context context = mActivity;
        float d = context.getResources().getDisplayMetrics().density;
        int ink = ForkThemeUtils.getTextColor();
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(12 * d);
        body.setPadding(pad, Math.round(4 * d), pad, 0);

        // One row of pills that sets every app at once — with a hundred apps selected, ticking
        // the APK on each of them by hand is not a thing anybody would do.
        body.addView(sectionLabel(context, context.getString(R.string.batch_backup_all_apps),
                ColorUtils.setAlphaComponent(ink, 0x99)));
        FlowLayout allFlow = flow(context);
        List<BackupParts.Part> parts = BackupParts.allParts(mSupportedFlags);
        for (BackupParts.Part part : parts) {
            allFlow.addView(BackupPartRows.partPill(context, part,
                    (mDefaultFlags & part.flag) != 0, on -> {
                        for (String packageName : mFlags.keySet()) {
                            int current = value(packageName);
                            mFlags.put(packageName, on ? (current | part.flag) : (current & ~part.flag));
                        }
                        rebuildRows();
                    }));
        }
        body.addView(allFlow);

        mRowsContainer = new LinearLayout(context);
        mRowsContainer.setOrientation(LinearLayout.VERTICAL);
        body.addView(mRowsContainer);
        rebuildRows();

        ScrollView scroller = new ScrollView(context);
        scroller.addView(body);
        ForkDialog.present(ForkDialog.builder(context)
                .setTitle(context.getString(R.string.batch_backup_table_title, mPairs.size()))
                .setView(scroller)
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.batch_backup_restore_delete, (dialog, which) ->
                        mListener.onRestoreOrDelete())
                .setPositiveButton(R.string.back_up, (dialog, which) ->
                        mListener.onBackup(new LinkedHashMap<>(mFlags), mDefaultFlags,
                                new LinkedHashMap<>(mAppData))));
    }

    private LinearLayout mRowsContainer;

    private int value(@NonNull String packageName) {
        Integer v = mFlags.get(packageName);
        return v == null ? mDefaultFlags : v;
    }

    private void rebuildRows() {
        if (mRowsContainer == null) {
            return;
        }
        mRowsContainer.removeAllViews();
        for (UserPackagePair pair : mPairs) {
            mRowsContainer.addView(appRow(pair));
        }
    }

    /** One app: its icon and name, its parts, and — when it has a door — its categories. */
    @NonNull
    private View appRow(@NonNull UserPackagePair pair) {
        Context context = mActivity;
        float d = context.getResources().getDisplayMetrics().density;
        int ink = ForkThemeUtils.getTextColor();
        String packageName = pair.getPackageName();

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int padH = Math.round(10 * d);
        int padV = Math.round(8 * d);
        box.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxLp.bottomMargin = Math.round(8 * d);
        box.setLayoutParams(boxLp);
        BackupPartRows.applyBoxStyle(box, ink, false);

        // Header: icon + label.
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new AppCompatImageView(context);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                Math.round(28 * d), Math.round(28 * d));
        iconLp.setMarginEnd(Math.round(10 * d));
        icon.setLayoutParams(iconLp);
        ApplicationInfo info = null;
        try {
            info = PackageManagerCompat.getApplicationInfo(packageName,
                    PackageManagerCompat.MATCH_UNINSTALLED_PACKAGES
                            | PackageManagerCompat.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES,
                    pair.getUserId());
        } catch (Throwable ignore) {
        }
        ImageLoader.getInstance().displayImage(packageName, info, icon);
        header.addView(icon);
        AppCompatTextView label = new AppCompatTextView(context);
        label.setText(info != null ? info.loadLabel(context.getPackageManager()) : packageName);
        label.setTextColor(ink);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        label.setSingleLine(true);
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(label);
        box.addView(header);

        // The parts, per app.
        FlowLayout partFlow = flow(context);
        for (BackupParts.Part part : BackupParts.allParts(mSupportedFlags)) {
            partFlow.addView(BackupPartRows.partPill(context, part,
                    (value(packageName) & part.flag) != 0, on -> {
                        int current = value(packageName);
                        mFlags.put(packageName, on ? (current | part.flag) : (current & ~part.flag));
                    }));
        }
        box.addView(partFlow);

        // The app's own categories, folded until asked for.
        if (AppDataContract.isSupported(context, packageName)) {
            box.addView(appDataSection(pair));
        }
        return box;
    }

    /**
     * The fold-out for one app's own data. Closed it costs nothing; opened it thaws the app,
     * asks it what it can export, and puts it back — which is why it is not opened for you.
     */
    @NonNull
    private View appDataSection(@NonNull UserPackagePair pair) {
        Context context = mActivity;
        float d = context.getResources().getDisplayMetrics().density;
        int green = 0xFF4CD07A;
        LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(4 * d);
        section.setLayoutParams(lp);

        AppCompatTextView toggle = new AppCompatTextView(context);
        toggle.setTextColor(green);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        toggle.setTypeface(Typeface.DEFAULT_BOLD);
        toggle.setText("▸ " + context.getString(R.string.backup_app_data));
        toggle.setPadding(0, Math.round(4 * d), 0, Math.round(4 * d));
        section.addView(toggle);

        FlowLayout categories = flow(context);
        categories.setVisibility(View.GONE);
        section.addView(categories);

        final boolean[] open = {false};
        final boolean[] loaded = {false};
        toggle.setOnClickListener(v -> {
            open[0] = !open[0];
            toggle.setText((open[0] ? "▾ " : "▸ ") + context.getString(R.string.backup_app_data));
            categories.setVisibility(open[0] ? View.VISIBLE : View.GONE);
            if (!open[0] || loaded[0]) {
                return;
            }
            loaded[0] = true;
            UIUtils.displayShortToast(R.string.appdata_categories_asking);
            ThreadUtils.postOnBackgroundThread(() -> {
                List<AppDataCategory> offered = new AppDataTransfer(context)
                        .listCategories(pair.getPackageName(), pair.getUserId());
                ThreadUtils.postOnMainThread(() -> {
                    if (offered == null || offered.isEmpty()) {
                        AppCompatTextView none = new AppCompatTextView(context);
                        none.setText(R.string.appdata_categories_unavailable);
                        none.setTextColor(ColorUtils.setAlphaComponent(green, 0xAA));
                        none.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                        categories.addView(none);
                        return;
                    }
                    bindCategories(categories, pair, offered, green);
                });
            });
        });
        return section;
    }

    /**
     * The categories as pills, ticked from what has been chosen before — or from the app's own
     * defaults the first time. Every change is written to {@link AppDataSelection}, storing both
     * what is ticked and what was offered: without the second, a category the app adds later is
     * indistinguishable from one deliberately unticked.
     */
    private void bindCategories(@NonNull ViewGroup container, @NonNull UserPackagePair pair,
                                @NonNull List<AppDataCategory> offered, int color) {
        Context context = mActivity;
        String packageName = pair.getPackageName();
        AppDataSelection.Stored stored = AppDataSelection.get(context, packageName);
        // Order is the app's own offered order, which is the order it will export in.
        List<String> chosen = new ArrayList<>();
        for (AppDataCategory category : offered) {
            boolean on = stored != null ? stored.chosen.contains(category.id) : category.defaultOn;
            if (on && !chosen.contains(category.id)) {
                chosen.add(category.id);
            }
        }
        // Recorded as soon as the fold is opened, not only when something is changed: opening it
        // and leaving the ticks alone still means "these, for this backup" — and the stored
        // choice it started from may not be what the app would default to.
        mAppData.put(packageName, chosen.toArray(new String[0]));
        for (AppDataCategory category : offered) {
            container.addView(BackupPartRows.plainPill(context, category.label, color,
                    chosen.contains(category.id), on -> {
                        if (on) {
                            if (!chosen.contains(category.id)) chosen.add(category.id);
                        } else {
                            chosen.remove(category.id);
                        }
                        // In memory only. See mAppData — this must never touch the app's
                        // stored preference.
                        mAppData.put(packageName, chosen.toArray(new String[0]));
                    }));
        }
    }

    @NonNull
    private static View sectionLabel(@NonNull Context context, @NonNull CharSequence text, int color) {
        float d = context.getResources().getDisplayMetrics().density;
        AppCompatTextView label = new AppCompatTextView(context);
        label.setText(text);
        label.setTextColor(color);
        label.setAllCaps(true);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        label.setPadding(0, Math.round(4 * d), 0, Math.round(6 * d));
        return label;
    }

    @NonNull
    private static FlowLayout flow(@NonNull Context context) {
        float d = context.getResources().getDisplayMetrics().density;
        FlowLayout flow = new FlowLayout(context);
        flow.setChildSpacing(Math.round(6 * d));
        flow.setRowSpacing(Math.round(5 * d));
        return flow;
    }
}
