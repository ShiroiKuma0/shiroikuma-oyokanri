// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.Window;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.UiThread;

import com.google.android.material.button.MaterialButton;

import java.util.List;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;

/**
 * Fork: large, themed confirmation dialog shown before a batch operation
 * (uninstall, reinstall, freeze, unfreeze). It states up front how many apps the
 * operation will touch ("Will uninstall N applications! Are you sure?") and lists
 * every one of them — app label in bold, the package id in italic next to it,
 * both column-aligned — in a scrollable area so the full set can be reviewed
 * before confirming.
 *
 * <p>The title, the optional warning subtitle (e.g. "cannot be undone", shown
 * only for destructive ops) and the OK-button label are all supplied per
 * operation by the caller, so the same dialog serves every batch action.
 *
 * <p>The dialog is sized to cover most of the window while still floating as a
 * dialog (the underlying app list peeks out at the edges); the app list scrolls
 * within it. Colours and border come from the configurable fork theme
 * ({@link ForkThemeUtils}), so the border is the configurable yellow by default
 * and everything is adjustable from the appearance settings.
 */
@UiThread
public class BatchConfirmDialog {
    public interface OnConfirmListener {
        void onConfirm();
    }

    private final Activity mActivity;
    private final CharSequence mTitle;
    @Nullable
    private final CharSequence mSubtitle;
    @StringRes
    private final int mOkLabelRes;
    // Each entry is { label, packageName }.
    private final List<String[]> mApps;
    private final OnConfirmListener mOnConfirm;

    public BatchConfirmDialog(@NonNull Activity activity, @NonNull CharSequence title,
                              @Nullable CharSequence subtitle, @StringRes int okLabelRes,
                              @NonNull List<String[]> apps, @NonNull OnConfirmListener onConfirm) {
        mActivity = activity;
        mTitle = title;
        mSubtitle = subtitle;
        mOkLabelRes = okLabelRes;
        mApps = apps;
        mOnConfirm = onConfirm;
    }

    public void show() {
        View view = mActivity.getLayoutInflater().inflate(R.layout.dialog_batch_confirm_shiroikuma, null);
        View container = view.findViewById(R.id.batch_confirm_container);
        TextView titleView = view.findViewById(R.id.batch_confirm_title);
        TextView subtitleView = view.findViewById(R.id.batch_confirm_subtitle);
        TableLayout table = view.findViewById(R.id.batch_confirm_table);
        MaterialButton cancelButton = view.findViewById(R.id.batch_confirm_cancel);
        MaterialButton okButton = view.findViewById(R.id.batch_confirm_ok);

        int textColor = ForkThemeUtils.getTextColor();
        // Dim the package-id column to ~70% of the text colour so the bold label
        // reads as primary and the italic id as a quieter secondary line.
        int idColor = (textColor & 0x00FFFFFF) | 0xB3000000;

        titleView.setText(mTitle);
        titleView.setTextColor(textColor);
        if (TextUtils.isEmpty(mSubtitle)) {
            subtitleView.setVisibility(View.GONE);
        } else {
            subtitleView.setText(mSubtitle);
            subtitleView.setTextColor(textColor);
        }
        okButton.setText(mOkLabelRes);

        container.setBackground(ForkThemeUtils.makeThemedBackground(container.getContext(), 16f));
        ColorStateList accent = ColorStateList.valueOf(textColor);
        cancelButton.setTextColor(accent);
        cancelButton.setRippleColor(accent);
        okButton.setTextColor(accent);
        okButton.setRippleColor(accent);

        int idPadStart = Math.round(ForkThemeUtils.dpToPx(mActivity, 16f));
        int rowPadBottom = Math.round(ForkThemeUtils.dpToPx(mActivity, 6f));
        Typeface labelFace = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
        Typeface idFace = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC);
        for (String[] app : mApps) {
            TableRow row = new TableRow(mActivity);

            TextView labelView = new TextView(mActivity);
            labelView.setText(app[0]);
            labelView.setTextColor(textColor);
            labelView.setTypeface(labelFace);
            labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            labelView.setPadding(0, 0, 0, rowPadBottom);

            TextView idView = new TextView(mActivity);
            idView.setText(app[1]);
            idView.setTextColor(idColor);
            idView.setTypeface(idFace);
            idView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            // Top-aligned so a wrapped id stays in line with the top of the label.
            idView.setPadding(idPadStart, 0, 0, rowPadBottom);

            row.addView(labelView);
            row.addView(idView);
            table.addView(row);
        }

        Dialog dialog = new Dialog(mActivity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(view);
        // A back press / outside tap just dismisses (same as choosing Cancel);
        // nothing happens until the user taps the positive button.
        dialog.setCancelable(true);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics metrics = mActivity.getResources().getDisplayMetrics();
            // Cover most of the window but leave a margin so it still reads as a
            // floating dialog with the list peeking out behind it.
            window.setLayout((int) (metrics.widthPixels * 0.92f), (int) (metrics.heightPixels * 0.9f));
        }

        cancelButton.setOnClickListener(v -> dialog.dismiss());
        okButton.setOnClickListener(v -> {
            dialog.dismiss();
            mOnConfirm.onConfirm();
        });

        dialog.show();
    }
}
