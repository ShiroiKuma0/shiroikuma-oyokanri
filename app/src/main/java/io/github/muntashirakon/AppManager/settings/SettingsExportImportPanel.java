// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.settings;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Environment;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.core.content.ContextCompat;
import androidx.core.util.Pair;
import androidx.core.widget.CompoundButtonCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;
import io.github.muntashirakon.io.Path;
import io.github.muntashirakon.io.Paths;

/**
 * Fork: the category-based settings Export/Import panel, opened from the top
 * of the 白い熊 応用管理 UI page (Kōjiki flow, ArcaneChat pill buttons). One
 * checklist drives both directions: Export zips the ticked categories into
 * the persisted export directory; Import applies the ticked categories a
 * chosen zip contains. The panel also owns the export directory and the
 * "last export" line.
 * <p>
 * Success chaining: the bordered info dialog's acknowledgement (OK on export,
 * "Later" on import) closes the whole chain — info dialog, this panel, and
 * the UI page beneath (via {@code closePage}). "Restart now" hard-restarts
 * the process (killProcess, so cached SharedPreferences can't clobber the
 * imported files). Failures only show a toast and leave the panel open.
 */
public final class SettingsExportImportPanel {
    private static final int DIM = 0xFFC8C800;
    private static final int WARN = 0xFFFF5252;

    private final Activity mActivity;
    private final Runnable mClosePage;
    private final int mYellow;
    private final float mDensity;
    private final EnumMap<SettingsBackupManager.Category, CheckBox> mChecks =
            new EnumMap<>(SettingsBackupManager.Category.class);
    private AlertDialog mDialog;
    private TextView mDirValue;
    private TextView mStatus;

    /** Show the panel. {@code onDismiss} refreshes the UI-page row beneath. */
    public static void show(@NonNull Activity activity, @NonNull Runnable onDismiss,
                            @NonNull Runnable closePage) {
        new SettingsExportImportPanel(activity, closePage).present(onDismiss);
    }

    private SettingsExportImportPanel(@NonNull Activity activity, @NonNull Runnable closePage) {
        mActivity = activity;
        mClosePage = closePage;
        mYellow = ContextCompat.getColor(activity, R.color.theme_bright_yellow);
        mDensity = activity.getResources().getDisplayMetrics().density;
    }

    // ------------------------------------------------------------------
    // Status (also used by FontsPreferences for the row summary)
    // ------------------------------------------------------------------

    /** The newest export archive in the configured directory, or null. */
    @Nullable
    public static Path latestExport() {
        if (!Prefs.Storage.hasSettingsExportDirectory()) return null;
        Path dir = Paths.get(Prefs.Storage.getSettingsExportDirectory());
        if (!dir.exists()) return null;
        Path[] all = dir.listFiles();
        Path newest = null;
        if (all != null) {
            for (Path p : all) {
                if (p.isDirectory()) continue;
                String name = p.getName();
                // Accept the legacy prefix too, so archives written before the
                // family naming convention stay recognised as "last export".
                if (!name.startsWith(SettingsBackupManager.EXPORT_PREFIX)
                        && !name.startsWith(SettingsBackupManager.LEGACY_EXPORT_PREFIX)) continue;
                if (!name.endsWith(SettingsBackupManager.EXPORT_EXT)) continue;
                if (newest == null || p.lastModified() > newest.lastModified()) newest = p;
            }
        }
        return newest;
    }

    /** (message, isWarning) for the "last export" line. Hits the filesystem. */
    @NonNull
    public static Pair<String, Boolean> lastExportStatus(@NonNull Context context) {
        if (!Prefs.Storage.hasSettingsExportDirectory()) {
            return new Pair<>(context.getString(R.string.settings_eim_warn_nodir), true);
        }
        Path newest = latestExport();
        if (newest == null) {
            return new Pair<>(context.getString(R.string.settings_eim_warn_none), true);
        }
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                .format(new Date(newest.lastModified()));
        return new Pair<>(context.getString(R.string.settings_eim_last, ts), false);
    }

    // ------------------------------------------------------------------
    // Panel construction
    // ------------------------------------------------------------------

    private int dp(int v) {
        return Math.round(v * mDensity);
    }

    private void present(@NonNull Runnable onDismiss) {
        LinearLayout root = new LinearLayout(mActivity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(20));
        root.setBackground(box(16, 2));

        TextView title = text(mActivity.getString(R.string.settings_eim_title), 18, mYellow, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(2), 0, dp(6));
        root.addView(title);
        TextView desc = text(mActivity.getString(R.string.settings_eim_desc), 13, DIM, false);
        desc.setPadding(0, 0, 0, dp(10));
        root.addView(desc);

        // Persisted export directory — a bordered, clearly-tappable box that
        // stands out (red value) when unset.
        LinearLayout dirBox = new LinearLayout(mActivity);
        dirBox.setOrientation(LinearLayout.VERTICAL);
        dirBox.setClickable(true);
        dirBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        dirBox.setBackground(box(10, 2));
        dirBox.setOnClickListener(v -> openDirChooser());
        dirBox.addView(text(mActivity.getString(R.string.settings_eim_dir), 12, mYellow, false));
        mDirValue = text("", 15, mYellow, true);
        dirBox.addView(mDirValue);
        LinearLayout.LayoutParams dirLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dirLp.topMargin = dp(6);
        dirLp.bottomMargin = dp(6);
        root.addView(dirBox, dirLp);

        mStatus = text("", 14, mYellow, false);
        mStatus.setPadding(dp(2), 0, 0, dp(8));
        root.addView(mStatus);

        root.addView(divider(0));

        CheckBox selectAll = checkbox(mActivity.getString(R.string.settings_eim_select_all), true);
        selectAll.setChecked(true);
        root.addView(selectAll);
        for (SettingsBackupManager.Category cat : SettingsBackupManager.Category.values()) {
            CheckBox cb = checkbox(mActivity.getString(cat.labelRes), false);
            cb.setChecked(true);
            mChecks.put(cat, cb);
            root.addView(cb);
        }
        selectAll.setOnCheckedChangeListener((v, isChecked) -> {
            for (CheckBox cb : mChecks.values()) cb.setChecked(isChecked);
        });

        root.addView(divider(8));

        // ArcaneChat-style button row: round pills, Cancel alone on the left,
        // the Import / Export actions grouped on the right.
        LinearLayout buttons = new LinearLayout(mActivity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, dp(14), 0, 0);
        Button cancel = pill(mActivity.getString(R.string.cancel));
        cancel.setOnClickListener(v -> mDialog.dismiss());
        buttons.addView(cancel);
        buttons.addView(new View(mActivity), new LinearLayout.LayoutParams(0, 0, 1f));
        Button importBtn = pill(mActivity.getString(R.string.settings_eim_import));
        LinearLayout.LayoutParams impLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        impLp.setMarginEnd(dp(8));
        importBtn.setLayoutParams(impLp);
        importBtn.setOnClickListener(v -> onImportClicked());
        buttons.addView(importBtn);
        Button exportBtn = pill(mActivity.getString(R.string.settings_eim_export));
        exportBtn.setOnClickListener(v -> onExportClicked());
        buttons.addView(exportBtn);
        root.addView(buttons);

        refreshStatus();

        // Inset the bordered box from the window edges so all four borders
        // show; the dialog window itself is transparent.
        NestedScrollView scroll = new NestedScrollView(mActivity);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = dp(10);
        lp.setMargins(m, m, m, m);
        scroll.addView(root, lp);

        mDialog = new MaterialAlertDialogBuilder(mActivity,
                R.style.ThemeOverlay_AppTheme_MaterialAlertDialog_YellowOnBlack)
                .setView(scroll)
                .create();
        mDialog.setOnDismissListener(d -> onDismiss.run());
        mDialog.show();
        if (mDialog.getWindow() != null) {
            mDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    private void refreshStatus() {
        String dir = Prefs.Storage.getSettingsExportDirectory();
        boolean unset = dir.isEmpty();
        mDirValue.setText(unset ? mActivity.getString(R.string.settings_eim_dir_unset) : dir);
        mDirValue.setTextColor(unset ? WARN : mYellow);
        Pair<String, Boolean> status = lastExportStatus(mActivity);
        mStatus.setText(status.first);
        mStatus.setTextColor(Boolean.TRUE.equals(status.second) ? WARN : mYellow);
        mStatus.setAlpha(Boolean.TRUE.equals(status.second) ? 1f : 0.8f);
    }

    private void openDirChooser() {
        String start = Prefs.Storage.hasSettingsExportDirectory()
                ? Prefs.Storage.getSettingsExportDirectory()
                : Environment.getExternalStorageDirectory().getAbsolutePath();
        DirectoryChooserDialog.show(mActivity, start, R.string.backup_directory_choose,
                R.string.settings_export_dir_clear, new DirectoryChooserDialog.Callback() {
                    @Override
                    public void onChosen(@NonNull String absolutePath) {
                        Prefs.Storage.setSettingsExportDirectory(absolutePath);
                        refreshStatus();
                    }

                    @Override
                    public void onCleared() {
                        Prefs.Storage.setSettingsExportDirectory("");
                        refreshStatus();
                    }
                });
    }

    @NonNull
    private Set<SettingsBackupManager.Category> selected() {
        Set<SettingsBackupManager.Category> cats =
                EnumSet.noneOf(SettingsBackupManager.Category.class);
        for (Map.Entry<SettingsBackupManager.Category, CheckBox> e : mChecks.entrySet()) {
            if (e.getValue().isChecked()) cats.add(e.getKey());
        }
        return cats;
    }

    // ------------------------------------------------------------------
    // Export / import flows
    // ------------------------------------------------------------------

    private void onExportClicked() {
        Set<SettingsBackupManager.Category> cats = selected();
        if (cats.isEmpty()) {
            UIUtils.displayShortToast(R.string.settings_eim_none_selected);
            return;
        }
        if (!Prefs.Storage.hasSettingsExportDirectory()) {
            openDirChooser();
            return;
        }
        Context appContext = mActivity.getApplicationContext();
        String dir = Prefs.Storage.getSettingsExportDirectory();
        UIUtils.displayShortToast(R.string.settings_exporting);
        ThreadUtils.postOnBackgroundThread(() -> {
            try {
                Path destDir = Paths.get(dir);
                if (!destDir.exists()) destDir.mkdirs();
                String name = SettingsBackupManager.export(appContext, destDir, cats);
                ThreadUtils.postOnMainThread(() -> showExportDone(name));
            } catch (Exception e) {
                ThreadUtils.postOnMainThread(() ->
                        UIUtils.displayLongToast(R.string.settings_export_failed, e.getMessage()));
            }
        });
    }

    private void onImportClicked() {
        Set<SettingsBackupManager.Category> cats = selected();
        if (cats.isEmpty()) {
            UIUtils.displayShortToast(R.string.settings_eim_none_selected);
            return;
        }
        if (!Prefs.Storage.hasSettingsExportDirectory()) {
            openDirChooser();
            return;
        }
        Path dir = Paths.get(Prefs.Storage.getSettingsExportDirectory());
        Path[] all = dir.exists() ? dir.listFiles() : null;
        final List<Path> zips = new ArrayList<>();
        if (all != null) {
            // Newest first: export names are timestamped, so reverse-name sort works.
            Arrays.sort(all, (a, b) -> b.getName().compareToIgnoreCase(a.getName()));
            for (Path p : all) {
                if (!p.isDirectory() && p.getName().endsWith(SettingsBackupManager.EXPORT_EXT)) {
                    zips.add(p);
                }
            }
        }
        if (zips.isEmpty()) {
            UIUtils.displayLongToast(R.string.settings_no_exports_found);
            return;
        }
        CharSequence[] names = new CharSequence[zips.size()];
        for (int i = 0; i < zips.size(); i++) {
            names[i] = zips.get(i).getName();
        }
        AlertDialog picker = new MaterialAlertDialogBuilder(mActivity,
                R.style.ThemeOverlay_AppTheme_MaterialAlertDialog_YellowOnBlack)
                .setTitle(R.string.settings_import)
                .setItems(names, (d, which) -> importFrom(zips.get(which)))
                .setNegativeButton(R.string.cancel, null)
                .create();
        picker.show();
        DirectoryChooserDialog.applyYellowBorder(mActivity, picker);
    }

    private void importFrom(@NonNull Path zip) {
        Set<SettingsBackupManager.Category> cats = selected();
        Context appContext = mActivity.getApplicationContext();
        UIUtils.displayShortToast(R.string.settings_importing);
        ThreadUtils.postOnBackgroundThread(() -> {
            try {
                int n = SettingsBackupManager.importFrom(appContext, zip, cats);
                ThreadUtils.postOnMainThread(() -> {
                    if (n <= 0) {
                        UIUtils.displayLongToast(R.string.settings_import_empty);
                    } else {
                        showImportDone(n);
                    }
                });
            } catch (Exception e) {
                ThreadUtils.postOnMainThread(() ->
                        UIUtils.displayLongToast(R.string.settings_import_failed, e.getMessage()));
            }
        });
    }

    // ------------------------------------------------------------------
    // Result dialogs (bordered, closing the whole chain on acknowledgement)
    // ------------------------------------------------------------------

    /** OK closes the info dialog, the panel and the UI page. */
    private void showExportDone(@NonNull String fileName) {
        refreshStatus();
        LinearLayout box = infoBox(mActivity.getString(R.string.settings_eim_export_done_title),
                mActivity.getString(R.string.settings_eim_export_done_body, fileName));
        AlertDialog info = infoDialog(box, true);
        LinearLayout btns = infoButtonRow(box);
        Button ok = pill(mActivity.getString(R.string.ok));
        ok.setOnClickListener(v -> closeChain(info));
        btns.addView(ok);
        info.show();
        if (info.getWindow() != null) {
            info.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    /** "Later" closes the whole chain; "Restart now" hard-restarts the app. */
    private void showImportDone(int fileCount) {
        LinearLayout box = infoBox(mActivity.getString(R.string.settings_eim_import_done_title),
                mActivity.getString(R.string.settings_eim_import_done_body, fileCount));
        AlertDialog info = infoDialog(box, false);
        LinearLayout btns = infoButtonRow(box);
        Button later = pill(mActivity.getString(R.string.settings_eim_later));
        LinearLayout.LayoutParams laterLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        laterLp.setMarginEnd(dp(10));
        later.setLayoutParams(laterLp);
        later.setOnClickListener(v -> closeChain(info));
        btns.addView(later);
        Button restart = pill(mActivity.getString(R.string.settings_eim_restart_now));
        restart.setOnClickListener(v -> restartProcess());
        btns.addView(restart);
        info.show();
        if (info.getWindow() != null) {
            info.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    private void closeChain(@NonNull AlertDialog info) {
        info.dismiss();
        mDialog.dismiss();
        mClosePage.run();
    }

    @NonNull
    private LinearLayout infoBox(@NonNull String title, @NonNull String body) {
        LinearLayout box = new LinearLayout(mActivity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(20), dp(22), dp(16));
        box.setBackground(box(16, 2));
        box.addView(text(title, 19, mYellow, true));
        TextView bodyTv = text(body, 14, mYellow, false);
        bodyTv.setPadding(0, dp(10), 0, 0);
        box.addView(bodyTv);
        return box;
    }

    @NonNull
    private AlertDialog infoDialog(@NonNull LinearLayout box, boolean cancelable) {
        NestedScrollView scroll = new NestedScrollView(mActivity);
        scroll.addView(box);
        return new MaterialAlertDialogBuilder(mActivity,
                R.style.ThemeOverlay_AppTheme_MaterialAlertDialog_YellowOnBlack)
                .setView(scroll)
                .setCancelable(cancelable)
                .create();
    }

    @NonNull
    private LinearLayout infoButtonRow(@NonNull LinearLayout box) {
        LinearLayout btns = new LinearLayout(mActivity);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        btns.setPadding(0, dp(16), 0, 0);
        box.addView(btns);
        return btns;
    }

    /** Hard-restart so SharedPreferences are re-read from the imported files. */
    private void restartProcess() {
        Intent intent = mActivity.getPackageManager().getLaunchIntentForPackage(mActivity.getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            mActivity.startActivity(intent);
        }
        // Hard-kill rather than Runtime.exit(0): an orderly shutdown lets the
        // still-cached SharedPreferences instances flush their in-memory maps
        // back to disk, overwriting the files we just imported and silently
        // reverting the import. killProcess sends SIGKILL to ourselves so
        // nothing rewrites them; the relaunched activity comes up in a fresh
        // process and re-reads every prefs file from disk.
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    // ------------------------------------------------------------------
    // View helpers (Kōjiki / ArcaneChat idiom)
    // ------------------------------------------------------------------

    /** Black box with a yellow stroke and rounded corners. */
    @NonNull
    private GradientDrawable box(int cornerDp, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF000000);
        g.setCornerRadius(cornerDp * mDensity);
        g.setStroke(Math.round(strokeDp * mDensity), mYellow);
        return g;
    }

    @NonNull
    private TextView text(@NonNull String s, int sizeSp, int color, boolean bold) {
        TextView tv = new TextView(mActivity);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    @NonNull
    private CheckBox checkbox(@NonNull String label, boolean bold) {
        CheckBox cb = new AppCompatCheckBox(mActivity);
        cb.setText(label);
        cb.setTextColor(mYellow);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        if (bold) cb.setTypeface(Typeface.DEFAULT_BOLD);
        CompoundButtonCompat.setButtonTintList(cb, ColorStateList.valueOf(mYellow));
        cb.setPadding(dp(8), dp(7), 0, dp(7));
        return cb;
    }

    /** A 40%-alpha yellow hairline. */
    @NonNull
    private View divider(int topMarginDp) {
        View v = new View(mActivity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(topMarginDp);
        v.setLayoutParams(lp);
        v.setBackgroundColor(mYellow);
        v.setAlpha(0.4f);
        return v;
    }

    /** A round-pill outline button (ArcaneChat-style dialog action). */
    @NonNull
    private Button pill(@NonNull String label) {
        GradientDrawable bg = box(100, 0);
        bg.setStroke(Math.round(1.5f * mDensity), mYellow);
        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf((mYellow & 0x00FFFFFF) | 0x33000000), bg, null);
        Button b = new Button(mActivity);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(mYellow);
        b.setBackground(ripple);
        b.setStateListAnimator(null);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(20), dp(8), dp(20), dp(8));
        return b;
    }
}
