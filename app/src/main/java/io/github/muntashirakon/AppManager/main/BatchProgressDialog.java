// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;
import androidx.lifecycle.Observer;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.Locale;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.batchops.BatchOpsProgressMonitor;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;

/**
 * Fork: the in-app pop-up that mirrors batch-operation progress (the same info
 * the foreground-service notification shows) in the main window, and lets the
 * user pause/continue or cancel the operation. Styled from the configurable fork
 * theme (yellow-on-black bordered by default).
 *
 * <p>Lifecycle is driven by {@link MainActivity}: it is {@link #show() shown} on
 * the {@code ACTION_BATCH_OPS_STARTED} broadcast (or on resume while an op is
 * still running) and {@link #dismiss() dismissed} on completion. While shown it
 * observes {@link BatchOpsProgressMonitor} for live counts.
 */
@UiThread
public class BatchProgressDialog {
    private final Activity mActivity;
    private final BatchOpsProgressMonitor mMonitor = BatchOpsProgressMonitor.getInstance();

    private Dialog mDialog;
    private TextView mTitleView;
    private TextView mCounterView;
    private TextView mCurrentLabelView;
    private TextView mCurrentPackageView;
    private LinearProgressIndicator mProgressBar;
    private MaterialButton mPauseButton;

    private final Observer<BatchOpsProgressMonitor.State> mObserver = this::onState;

    public BatchProgressDialog(@NonNull Activity activity) {
        mActivity = activity;
    }

    public boolean isShowing() {
        return mDialog != null && mDialog.isShowing();
    }

    public void show() {
        if (isShowing()) {
            return;
        }
        View view = mActivity.getLayoutInflater().inflate(R.layout.dialog_batch_progress_shiroikuma, null);
        mTitleView = view.findViewById(R.id.batch_progress_title);
        mCounterView = view.findViewById(R.id.batch_progress_counter);
        mCurrentLabelView = view.findViewById(R.id.batch_progress_current_label);
        mCurrentPackageView = view.findViewById(R.id.batch_progress_current_package);
        mProgressBar = view.findViewById(R.id.batch_progress_bar);
        mPauseButton = view.findViewById(R.id.batch_progress_pause);
        MaterialButton cancelButton = view.findViewById(R.id.batch_progress_cancel);

        applyTheme(view.findViewById(R.id.batch_progress_container), cancelButton);

        mPauseButton.setOnClickListener(v -> {
            if (mMonitor.isPaused()) {
                mMonitor.resume();
            } else {
                mMonitor.pause();
            }
            updatePauseLabel(mMonitor.isPaused());
        });
        cancelButton.setOnClickListener(v -> {
            mMonitor.cancel();
            dismiss();
        });

        Dialog dialog = new Dialog(mActivity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(view);
        // The user must explicitly Pause (stay) or Cancel (exit); a stray back
        // press or outside tap must not silently abandon the dialog.
        dialog.setCancelable(false);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics metrics = mActivity.getResources().getDisplayMetrics();
            window.setLayout((int) (metrics.widthPixels * 0.86f), WindowManager.LayoutParams.WRAP_CONTENT);
        }
        mDialog = dialog;

        // Seed from the current snapshot, then observe for updates.
        BatchOpsProgressMonitor.State current = mMonitor.getState().getValue();
        if (current != null) {
            onState(current);
        }
        mMonitor.getState().observeForever(mObserver);
        dialog.show();
    }

    public void dismiss() {
        mMonitor.getState().removeObserver(mObserver);
        if (mDialog != null) {
            if (mDialog.isShowing()) {
                mDialog.dismiss();
            }
            mDialog = null;
        }
    }

    private void applyTheme(@NonNull View container, @NonNull MaterialButton cancelButton) {
        int textColor = ForkThemeUtils.getTextColor();
        container.setBackground(ForkThemeUtils.makeThemedBackground(container.getContext(), 16f));
        mTitleView.setTextColor(textColor);
        mCounterView.setTextColor(textColor);
        mCurrentLabelView.setTextColor(textColor);
        mCurrentPackageView.setTextColor(textColor);
        ColorStateList accent = ColorStateList.valueOf(textColor);
        mPauseButton.setTextColor(accent);
        mPauseButton.setRippleColor(accent);
        cancelButton.setTextColor(accent);
        cancelButton.setRippleColor(accent);
        mProgressBar.setIndicatorColor(textColor);
        // Dim the track to ~20% of the indicator colour so it reads as a faint
        // groove on the themed background.
        mProgressBar.setTrackColor((textColor & 0x00FFFFFF) | 0x33000000);
    }

    private void onState(@NonNull BatchOpsProgressMonitor.State state) {
        if (mDialog == null) {
            return;
        }
        mTitleView.setText(state.title);
        int max = Math.max(state.max, 0);
        int current = Math.max(0, Math.min(state.current, max));
        mCounterView.setText(String.format(Locale.getDefault(), "%d / %d", current, max));
        mProgressBar.setMax(Math.max(max, 1));
        mProgressBar.setProgressCompat(current, true);
        // Fork: the app currently in flight — label (bold) over id (italic). Fall
        // back to the package id for the label line if the label didn't resolve;
        // hide both lines when nothing is being processed.
        CharSequence label = state.currentLabel;
        String pkg = state.currentPackage;
        boolean hasLabel = label != null && label.length() > 0;
        boolean hasPkg = pkg != null && pkg.length() > 0;
        if (hasLabel || hasPkg) {
            mCurrentLabelView.setText(hasLabel ? label : pkg);
            mCurrentLabelView.setVisibility(View.VISIBLE);
            mCurrentPackageView.setText(pkg);
            mCurrentPackageView.setVisibility(hasPkg ? View.VISIBLE : View.GONE);
        } else {
            mCurrentLabelView.setVisibility(View.GONE);
            mCurrentPackageView.setVisibility(View.GONE);
        }
        updatePauseLabel(state.paused);
    }

    private void updatePauseLabel(boolean paused) {
        mPauseButton.setText(paused ? R.string.continue_operation : R.string.pause);
    }
}
