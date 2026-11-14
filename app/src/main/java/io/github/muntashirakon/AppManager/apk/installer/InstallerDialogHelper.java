// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.apk.installer;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentContainerView;

import com.google.android.material.textview.MaterialTextView;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.dialog.DialogTitleBuilder;

public final class InstallerDialogHelper {
    public interface OnClickButtonsListener {
        void triggerInstall();

        void triggerCancel();
    }

    private final Context mContext;
    private final InstallerDialogFragment mFragment;
    private final DialogTitleBuilder mTitleBuilder;
    private final FragmentContainerView mFragmentContainer;
    private final MaterialTextView mMessage;
    private final LinearLayoutCompat mLayout;
    private final int mFragmentId = R.id.fragment_container_view_tag;
    private final AlertDialog mDialog;
    private final Button mPositiveBtn;
    private final Button mNegativeBtn;
    private final Button mNeutralBtn;
    // Fork (白い熊, +091): what onParseSuccess learnt about the app, kept for the hero states.
    @Nullable
    private CharSequence mAppLabel;
    @Nullable
    private CharSequence mAppSubtitle;
    @Nullable
    private Drawable mAppIcon;

    public InstallerDialogHelper(@NonNull InstallerDialogFragment fragment, AlertDialog dialog) {
        mContext = fragment.requireContext();
        mFragment = fragment;
        mDialog = dialog;
        View view = mFragment.getDialogView();
        mTitleBuilder = mFragment.getTitleBuilder();
        mFragmentContainer = view.findViewById(mFragmentId);
        mMessage = view.findViewById(R.id.message);
        mLayout = view.findViewById(R.id.layout);
        mPositiveBtn = mDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        mNegativeBtn = mDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        mNeutralBtn = mDialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        // Fork (白い熊, +091): no grey or white text — the body takes the fork's ink.
        mMessage.setTextColor(ink());
    }

    // ── Fork (白い熊, +091): black-and-yellow hero states ─────────────────────────────────────────

    private static int ink() {
        return io.github.muntashirakon.AppManager.utils.ForkThemeUtils.getTextColor();
    }

    private int dimInk() {
        return io.github.muntashirakon.AppManager.fonts.ColorPrefs.getColor(mContext,
                io.github.muntashirakon.AppManager.fonts.ColorPrefs.SETTINGS_DESCRIPTION);
    }

    private int px(float dp) {
        return Math.round(dp * mContext.getResources().getDisplayMetrics().density);
    }

    /** The plain title row is shown for the question states and hidden while a hero is up. */
    private void setTitleVisible(boolean visible) {
        mTitleBuilder.build().setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    @NonNull
    private TextView heroText(@Nullable CharSequence text, int color, float sp, boolean bold) {
        TextView tv = new androidx.appcompat.widget.AppCompatTextView(mContext);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(sp);
        tv.setGravity(android.view.Gravity.CENTER);
        if (bold) tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return tv;
    }

    /**
     * The app's icon inside a ring — travelling while something runs, swept in once in the result's
     * colour when it is over — then its name and version, a filled pill saying what happened, and
     * the platform's own status line small beneath it.
     *
     * @param running    the ring travels (ringColor ignored) instead of closing
     * @param pill       the filled verdict pill, or null for none
     * @param pillOnDark true = white ink on the pill (a red fill), false = black
     */
    private void showHero(boolean running, int ringColor, @Nullable CharSequence badge,
                          @Nullable CharSequence pill, boolean pillOnDark,
                          @Nullable CharSequence line, @Nullable CharSequence small) {
        setTitleVisible(false);
        LinearLayoutCompat col = new LinearLayoutCompat(mContext);
        col.setOrientation(LinearLayoutCompat.VERTICAL);
        col.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        col.setPadding(px(20), px(22), px(20), px(10));

        android.widget.FrameLayout ringBox = new android.widget.FrameLayout(mContext);
        io.github.muntashirakon.AppManager.scanner.vt.VtGaugeView ring =
                new io.github.muntashirakon.AppManager.scanner.vt.VtGaugeView(mContext);
        ring.setInk(ink(), dimInk());
        if (running) ring.showScanning(-1, "", "");
        else ring.showState(ringColor, "", "");
        ringBox.addView(ring, new android.widget.FrameLayout.LayoutParams(px(132), px(132)));
        androidx.appcompat.widget.AppCompatImageView icon = new androidx.appcompat.widget.AppCompatImageView(mContext);
        if (mAppIcon != null) icon.setImageDrawable(mAppIcon);
        else {
            icon.setImageResource(R.drawable.ic_get_app);
            icon.setColorFilter(ink());
        }
        android.widget.FrameLayout.LayoutParams ip = new android.widget.FrameLayout.LayoutParams(px(76), px(76));
        ip.gravity = android.view.Gravity.CENTER;
        ringBox.addView(icon, ip);
        if (badge != null) {
            // A small disc on the ring's shoulder: ✓ or ✕ in the result's colour.
            TextView mark = heroText(badge, pillOnDark ? android.graphics.Color.WHITE : android.graphics.Color.BLACK,
                    17, true);
            android.graphics.drawable.GradientDrawable disc = new android.graphics.drawable.GradientDrawable();
            disc.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            disc.setColor(ringColor);
            disc.setStroke(px(2.5f), android.graphics.Color.BLACK);
            mark.setBackground(disc);
            android.widget.FrameLayout.LayoutParams mp = new android.widget.FrameLayout.LayoutParams(px(36), px(36));
            mp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
            ringBox.addView(mark, mp);
        }
        col.addView(ringBox, new LinearLayoutCompat.LayoutParams(px(132), px(132)));
        if (!running) {
            icon.setScaleX(0.6f);
            icon.setScaleY(0.6f);
            icon.animate().scaleX(1f).scaleY(1f).setDuration(450)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();
        }

        if (mAppLabel != null) {
            LinearLayoutCompat.LayoutParams lp = new LinearLayoutCompat.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = px(12);
            col.addView(heroText(mAppLabel, ink(), 24, true), lp);
        }
        if (mAppSubtitle != null) col.addView(heroText(mAppSubtitle, dimInk(), 13, false));
        if (pill != null) {
            TextView tag = heroText(pill, pillOnDark ? android.graphics.Color.WHITE : android.graphics.Color.BLACK,
                    17, true);
            tag.setPadding(px(18), px(6), px(18), px(6));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(ringColor);
            bg.setCornerRadius(px(100));
            tag.setBackground(bg);
            LinearLayoutCompat.LayoutParams lp = new LinearLayoutCompat.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = px(14);
            col.addView(tag, lp);
        }
        if (line != null) {
            LinearLayoutCompat.LayoutParams lp = new LinearLayoutCompat.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = px(14);
            col.addView(heroText(line, ink(), 16, false), lp);
        }
        if (small != null) {
            TextView tv = heroText(small, dimInk(), 12, false);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setTextIsSelectable(true);
            LinearLayoutCompat.LayoutParams lp = new LinearLayoutCompat.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = px(8);
            col.addView(tv, lp);
        }
        mLayout.setVisibility(View.VISIBLE);
        mLayout.removeAllViews();
        mLayout.addView(col, new LinearLayoutCompat.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        mMessage.setVisibility(View.GONE);
        mFragmentContainer.setVisibility(View.GONE);
    }

    public void initProgress(View.OnClickListener cancelListener) {
        setTitleVisible(true);
        // Title section
        mTitleBuilder.setTitle(R.string._undefined)
                .setStartIcon(R.drawable.ic_get_app)
                .setSubtitle(null)
                .setEndIcon(null, null);
        // Buttons
        mPositiveBtn.setVisibility(View.GONE);
        mNeutralBtn.setVisibility(View.GONE);
        mNegativeBtn.setVisibility(View.VISIBLE);
        mNegativeBtn.setText(R.string.cancel);
        mNegativeBtn.setOnClickListener(cancelListener);
        // Body
        View v = View.inflate(mContext, R.layout.dialog_progress2, null);
        TextView tv = v.findViewById(android.R.id.text1);
        tv.setText(R.string.staging_apk_files);
        tv.setTextColor(ink());
        mLayout.setVisibility(View.VISIBLE);
        mLayout.removeAllViews();
        mLayout.addView(v);
        mMessage.setVisibility(View.GONE);
        mFragmentContainer.setVisibility(View.GONE);
    }

    public void showParseFailedDialog(View.OnClickListener closeListener) {
        setTitleVisible(true);
        // Title section
        mTitleBuilder.setTitle(R.string._undefined)
                .setStartIcon(R.drawable.ic_get_app)
                .setSubtitle(null)
                .setEndIcon(null, null);
        // Buttons
        mPositiveBtn.setVisibility(View.GONE);
        mNeutralBtn.setVisibility(View.GONE);
        mNegativeBtn.setVisibility(View.VISIBLE);
        mNegativeBtn.setText(R.string.close);
        mNegativeBtn.setOnClickListener(closeListener);
        // Body
        mLayout.setVisibility(View.GONE);
        mMessage.setVisibility(View.VISIBLE);
        mMessage.setText(R.string.failed_to_fetch_package_info);
        mFragmentContainer.setVisibility(View.GONE);
    }

    public void onParseSuccess(CharSequence title, CharSequence subtitle, Drawable icon,
                               @Nullable View.OnClickListener optionsClickListener) {
        setTitleVisible(true);
        mAppLabel = title;
        mAppSubtitle = subtitle;
        mAppIcon = icon;
        mTitleBuilder.setTitle(title)
                .setStartIcon(icon)
                .setSubtitle(subtitle);
        if (optionsClickListener != null) {
            mTitleBuilder.setEndIcon(R.drawable.ic_settings, optionsClickListener)
                    .setEndIconContentDescription(R.string.installer_options);
        } else mTitleBuilder.setEndIcon(null, null);
    }

    public void showWhatsNewDialog(@StringRes int installButtonRes, Fragment fragment,
                                   @NonNull OnClickButtonsListener onClickButtonsListener,
                                   @NonNull View.OnClickListener appInfoButtonListener) {
        setTitleVisible(true);
        // Buttons
        mNeutralBtn.setVisibility(View.VISIBLE);
        mNeutralBtn.setText(R.string.app_info);
        mNeutralBtn.setOnClickListener(appInfoButtonListener);
        mPositiveBtn.setVisibility(View.VISIBLE);
        mPositiveBtn.setText(installButtonRes);
        mPositiveBtn.setOnClickListener(v -> onClickButtonsListener.triggerInstall());
        mNegativeBtn.setVisibility(View.VISIBLE);
        mNegativeBtn.setText(R.string.cancel);
        mNegativeBtn.setOnClickListener(v -> onClickButtonsListener.triggerCancel());
        // Body
        mLayout.setVisibility(View.GONE);
        mMessage.setVisibility(View.GONE);
        mFragmentContainer.setVisibility(View.VISIBLE);
        mFragment.getChildFragmentManager().beginTransaction().replace(mFragmentId, fragment).commit();
    }

    public void showInstallConfirmationDialog(@StringRes int installButtonRes,
                                              @NonNull OnClickButtonsListener onClickButtonsListener,
                                              @NonNull View.OnClickListener appInfoButtonListener) {
        setTitleVisible(true);
        // Buttons
        mNeutralBtn.setVisibility(View.VISIBLE);
        mNeutralBtn.setText(R.string.app_info);
        mNeutralBtn.setOnClickListener(appInfoButtonListener);
        mPositiveBtn.setVisibility(View.VISIBLE);
        mPositiveBtn.setText(installButtonRes);
        mPositiveBtn.setOnClickListener(v -> onClickButtonsListener.triggerInstall());
        mNegativeBtn.setVisibility(View.VISIBLE);
        mNegativeBtn.setText(R.string.cancel);
        mNegativeBtn.setOnClickListener(v -> onClickButtonsListener.triggerCancel());
        // Body
        mLayout.setVisibility(View.GONE);
        mMessage.setVisibility(View.VISIBLE);
        mMessage.setText(R.string.install_app_message);
        mFragmentContainer.setVisibility(View.GONE);
    }

    public void showApkChooserDialog(@StringRes int installButtonRes, Fragment fragment,
                                     @NonNull OnClickButtonsListener onClickButtonsListener,
                                     @NonNull View.OnClickListener appInfoButtonListener) {
        setTitleVisible(true);
        // Buttons
        mNeutralBtn.setVisibility(View.VISIBLE);
        mNeutralBtn.setText(R.string.app_info);
        mNeutralBtn.setOnClickListener(appInfoButtonListener);
        mPositiveBtn.setVisibility(View.VISIBLE);
        mPositiveBtn.setText(installButtonRes);
        mPositiveBtn.setOnClickListener(v -> onClickButtonsListener.triggerInstall());
        mNegativeBtn.setVisibility(View.VISIBLE);
        mNegativeBtn.setText(R.string.cancel);
        mNegativeBtn.setOnClickListener(v -> onClickButtonsListener.triggerCancel());
        // Body
        mLayout.setVisibility(View.GONE);
        mMessage.setVisibility(View.GONE);
        mFragmentContainer.setVisibility(View.VISIBLE);
        mFragment.getChildFragmentManager().beginTransaction().replace(mFragmentId, fragment).commit();
    }

    public void showDowngradeReinstallWarning(CharSequence msg,
                                              @NonNull OnClickButtonsListener onClickButtonsListener,
                                              @NonNull View.OnClickListener appInfoButtonListener) {
        setTitleVisible(true);
        // Buttons
        mNeutralBtn.setVisibility(View.VISIBLE);
        mNeutralBtn.setText(R.string.app_info);
        mNeutralBtn.setOnClickListener(appInfoButtonListener);
        mPositiveBtn.setVisibility(View.VISIBLE);
        mPositiveBtn.setText(R.string.yes);
        mPositiveBtn.setOnClickListener(v -> onClickButtonsListener.triggerInstall());
        mNegativeBtn.setVisibility(View.VISIBLE);
        mNegativeBtn.setText(R.string.cancel);
        mNegativeBtn.setOnClickListener(v -> onClickButtonsListener.triggerCancel());
        // Body
        mLayout.setVisibility(View.GONE);
        mMessage.setVisibility(View.VISIBLE);
        mMessage.setText(msg);
        mFragmentContainer.setVisibility(View.GONE);
    }

    public void showSignatureMismatchReinstallWarning(CharSequence msg,
                                                      @NonNull OnClickButtonsListener onClickButtonsListener,
                                                      @NonNull View.OnClickListener installOnlyButtonListener,
                                                      boolean isSystem) {
        setTitleVisible(true);
        // Buttons
        mNeutralBtn.setVisibility(View.VISIBLE);
        mNeutralBtn.setText(R.string.only_install);
        mNeutralBtn.setOnClickListener(installOnlyButtonListener);
        mPositiveBtn.setVisibility(isSystem ? View.GONE : View.VISIBLE);
        mPositiveBtn.setText(R.string.yes);
        mPositiveBtn.setOnClickListener(v -> onClickButtonsListener.triggerInstall());
        mNegativeBtn.setVisibility(View.VISIBLE);
        mNegativeBtn.setText(R.string.cancel);
        mNegativeBtn.setOnClickListener(v -> onClickButtonsListener.triggerCancel());
        // Body
        mLayout.setVisibility(View.GONE);
        mMessage.setVisibility(View.VISIBLE);
        mMessage.setText(msg);
        mFragmentContainer.setVisibility(View.GONE);
    }

    public void showInstallProgressDialog(@Nullable View.OnClickListener backgroundButtonListener) {
        // Disable installer options
        mTitleBuilder.setEndIcon(null, null);
        // Buttons
        mNeutralBtn.setVisibility(View.GONE);
        if (backgroundButtonListener != null) {
            mPositiveBtn.setVisibility(View.VISIBLE);
            mPositiveBtn.setText(R.string.background);
            mPositiveBtn.setOnClickListener(backgroundButtonListener);
        } else {
            mPositiveBtn.setVisibility(View.GONE);
        }
        mNegativeBtn.setVisibility(View.GONE);
        // Body: the app's icon inside a travelling ring.
        showHero(true, ink(), null, null, false, mContext.getString(R.string.install_in_progress), null);
    }

    public void showInstallFinishedDialog(CharSequence headline, @Nullable CharSequence statusMessage,
                                          boolean success, @StringRes int cancelOrNextRes,
                                          @NonNull View.OnClickListener cancelClickListener,
                                          @Nullable View.OnClickListener openButtonClickListener,
                                          @Nullable View.OnClickListener appInfoButtonClickListener) {
        // Buttons
        if (appInfoButtonClickListener != null) {
            mNeutralBtn.setVisibility(View.VISIBLE);
            mNeutralBtn.setText(R.string.app_info);
            mNeutralBtn.setOnClickListener(appInfoButtonClickListener);
        } else mNeutralBtn.setVisibility(View.GONE);
        if (openButtonClickListener != null) {
            mPositiveBtn.setVisibility(View.VISIBLE);
            mPositiveBtn.setText(R.string.open);
            mPositiveBtn.setOnClickListener(openButtonClickListener);
        } else mPositiveBtn.setVisibility(View.GONE);
        mNegativeBtn.setVisibility(View.VISIBLE);
        mNegativeBtn.setText(cancelOrNextRes);
        mNegativeBtn.setOnClickListener(cancelClickListener);
        // Body: green ring and ✓ on success; red ring, ✕ and the reason spelt out on failure.
        int color = success
                ? io.github.muntashirakon.AppManager.utils.appearance.ColorCodes.getSuccessColor(mContext)
                : io.github.muntashirakon.AppManager.utils.appearance.ColorCodes.getFailureColor(mContext);
        if (success) {
            showHero(false, color, "✓", headline, false, null, statusMessage);
        } else {
            showHero(false, color, "✕", mContext.getString(R.string.vt_state_install_failed), true,
                    headline, statusMessage);
        }
    }

    public void dismiss() {
        mDialog.dismiss();
    }
}