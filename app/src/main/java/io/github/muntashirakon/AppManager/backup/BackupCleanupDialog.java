// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.backup;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.graphics.ColorUtils;

import java.util.List;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.batchops.OpLog;
import io.github.muntashirakon.AppManager.utils.ForkDialog;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;

/**
 * Fork (白い熊, +137): what {@link BackupCleaner} found, and the one button that removes it.
 *
 * <p>It lists every folder by name with why it is being offered and what it costs, because
 * "clean up 23 folders" is not something anyone should agree to unseen — and because the one
 * mistake this feature must never make is deleting a real backup, which is best guarded by
 * showing the list.
 */
public final class BackupCleanupDialog {
    private BackupCleanupDialog() {
    }

    @MainThread
    public static void show(@NonNull Context context) {
        UIUtils.displayShortToast(R.string.backup_clean_scanning);
        ThreadUtils.postOnBackgroundThread(() -> {
            List<BackupCleaner.Finding> findings = BackupCleaner.scan();
            ThreadUtils.postOnMainThread(() -> render(context, findings));
        });
    }

    @MainThread
    private static void render(@NonNull Context context,
                               @NonNull List<BackupCleaner.Finding> findings) {
        if (findings.isEmpty()) {
            ForkDialog.present(ForkDialog.builder(context)
                    .setTitle(R.string.backup_clean_title)
                    .setMessage(R.string.backup_clean_none)
                    .setPositiveButton(R.string.ok, null));
            return;
        }
        float d = context.getResources().getDisplayMetrics().density;
        int ink = ForkThemeUtils.getTextColor();
        long total = 0;
        for (BackupCleaner.Finding finding : findings) {
            total += finding.size;
        }

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * d);
        body.setPadding(pad, Math.round(4 * d), pad, 0);

        AppCompatTextView header = new AppCompatTextView(context);
        header.setText(context.getString(R.string.backup_clean_found, findings.size(),
                OpLog.formatSize(total)));
        header.setTextColor(ColorUtils.setAlphaComponent(ink, 0xBB));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        header.setPadding(0, 0, 0, Math.round(10 * d));
        body.addView(header);

        for (BackupCleaner.Finding finding : findings) {
            body.addView(row(context, finding, ink));
        }

        ScrollView scroller = new ScrollView(context);
        scroller.addView(body);
        ForkDialog.present(ForkDialog.builder(context)
                .setTitle(R.string.backup_clean_title)
                .setView(scroller)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) ->
                        ThreadUtils.postOnBackgroundThread(() -> {
                            int removed = BackupCleaner.delete(findings);
                            ThreadUtils.postOnMainThread(() -> UIUtils.displayLongToast(
                                    context.getString(R.string.backup_clean_removed, removed,
                                            findings.size())));
                        })));
    }

    @NonNull
    private static View row(@NonNull Context context, @NonNull BackupCleaner.Finding finding,
                            int ink) {
        float d = context.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int padH = Math.round(10 * d);
        int padV = Math.round(8 * d);
        box.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(6 * d);
        box.setLayoutParams(lp);
        // The alarm red, because every row here is something about to be removed.
        int accent = 0xFFFF6E6E;
        io.github.muntashirakon.AppManager.backup.dialog.BackupPartRows.applyBoxStyle(box, accent, false);

        AppCompatTextView name = new AppCompatTextView(context);
        name.setText(finding.label);
        name.setTextColor(ink);
        name.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        box.addView(name);

        AppCompatTextView reason = new AppCompatTextView(context);
        CharSequence why = context.getString(finding.reasonRes);
        reason.setText(finding.size > 0 ? why + " · " + OpLog.formatSize(finding.size) : why);
        reason.setTextColor(ColorUtils.setAlphaComponent(accent, 0xDD));
        reason.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        box.addView(reason);
        return box;
    }
}
