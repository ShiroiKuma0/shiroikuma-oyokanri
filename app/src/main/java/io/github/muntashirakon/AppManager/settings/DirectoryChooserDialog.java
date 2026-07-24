// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.settings;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Environment;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.io.Path;
import io.github.muntashirakon.io.Paths;

/**
 * Fork: a minimal built-in filesystem directory browser (no SAF). Navigate
 * into subdirectories or up via "..", then the positive button reports the
 * current directory and the neutral button (if a label is given) clears.
 * Extracted from BackupRestorePreferences so the settings Export/Import
 * panel on the 白い熊 応用管理 UI page can reuse it.
 */
public final class DirectoryChooserDialog {
    public interface Callback {
        void onChosen(@NonNull String absolutePath);

        void onCleared();
    }

    private DirectoryChooserDialog() {
    }

    public static void show(@NonNull Context context, @NonNull String startDir,
                            @StringRes int chooseLabelRes, @StringRes int clearLabelRes,
                            @NonNull Callback cb) {
        Path startPath = Paths.get(startDir);
        if (!startPath.exists()) {
            startPath = Paths.get(Environment.getExternalStorageDirectory().getAbsolutePath());
        }
        final Path[] current = {startPath};
        final List<Path> rows = new ArrayList<>();
        ListView listView = new ListView(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_list_item_1);
        listView.setAdapter(adapter);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context,
                R.style.ThemeOverlay_AppTheme_MaterialAlertDialog_YellowOnBlack)
                .setView(listView)
                .setPositiveButton(chooseLabelRes, (d, w) -> cb.onChosen(current[0].getFilePath()))
                .setNegativeButton(R.string.cancel, null);
        if (clearLabelRes != 0) {
            builder.setNeutralButton(clearLabelRes, (d, w) -> cb.onCleared());
        }
        AlertDialog dialog = builder.create();
        final Runnable refresh = () -> {
            dialog.setTitle(current[0].getFilePath());
            rows.clear();
            adapter.setNotifyOnChange(false);
            adapter.clear();
            Path parent = current[0].getParent();
            if (parent != null) {
                rows.add(parent);
                adapter.add("..");
            }
            Path[] children = current[0].listFiles();
            if (children != null) {
                Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (Path c : children) {
                    if (c.isDirectory()) {
                        rows.add(c);
                        adapter.add(c.getName() + "/");
                    }
                }
            }
            adapter.notifyDataSetChanged();
        };
        listView.setOnItemClickListener((p, v, position, id) -> {
            current[0] = rows.get(position);
            refresh.run();
        });
        refresh.run();
        dialog.show();
        applyYellowBorder(context, dialog);
    }

    /**
     * Force the yellow-bordered window background on a shown dialog. The
     * explicit overlay passed to the builder colours the text/controls, but
     * MaterialAlertDialogBuilder.create() replaces the window background with
     * its own borderless MaterialShapeDrawable — so the border must be set on
     * the window AFTER show() (same trick as the process monitor's dialogs).
     */
    public static void applyYellowBorder(@NonNull Context context, @NonNull AlertDialog dialog) {
        Window w = dialog.getWindow();
        if (w != null) {
            Drawable bg = ContextCompat.getDrawable(context, R.drawable.alert_dialog_bg_yellow_on_black);
            if (bg != null) {
                int inset = Math.round(context.getResources().getDisplayMetrics().density * 16);
                w.setBackgroundDrawable(new InsetDrawable(bg, inset));
            }
        }
    }
}
