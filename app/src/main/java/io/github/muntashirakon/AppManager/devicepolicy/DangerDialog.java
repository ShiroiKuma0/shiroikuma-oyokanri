// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.devicepolicy;

import android.content.Context;
import android.content.DialogInterface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.utils.UIUtils;

/**
 * Fork: the one way a device-policy lock is ever applied from the UI.
 * <p>
 * Every power behind {@link DevicePolicyBridge} and {@link PolicyApiClient} is
 * built so the <em>user cannot reverse it from Settings</em>. That is what makes a
 * lock a lock, and it is also what makes a mistake expensive: only 応用管理 or 白い熊
 * 雫 can release one, a lock outlives the app that set it, and a couple of them can
 * cut the very channel you would use to fix them.
 * <p>
 * So the confirmation is not a formality and is deliberately awkward (白い熊,
 * 2026-08-01):
 * <ul>
 *   <li><b>Cancel sits in the positive slot</b> — where the thumb lands and the
 *       emphasis sits — and the destructive choice in the quiet negative slot.
 *       Android has no OS-level default button, so placement is the only way to
 *       make cancelling the default. The asymmetry in the buttons matches the
 *       asymmetry in the consequences.</li>
 *   <li>The destructive button is <b>red</b>.</li>
 *   <li>The message says <b>what it does, what it breaks, and how to undo it</b>,
 *       in that order — the last part is the one people need and never get.</li>
 * </ul>
 * The same shape as {@code DeviceOwnerHelper.confirmAndClear} in the 雫 repo, on
 * purpose: these two apps hand out the same kind of power and should ask in the
 * same voice.
 */
public final class DangerDialog {
    /** The red of the "Allowed" state — the page's existing colour for "this is the bad one". */
    @ColorInt
    public static final int DANGER_RED = 0xFFFF0028;

    private DangerDialog() {
    }

    /**
     * @param what   one line: the action, plainly.
     * @param breaks what stops working, and for whom.
     * @param undo   how it is released. Never omit this — a lock with no stated
     *               way back reads as permanent, and people either avoid the
     *               feature or get stranded by it.
     */
    public static void confirm(@NonNull Context context, @StringRes int title,
                               @NonNull CharSequence what, @NonNull CharSequence breaks,
                               @NonNull CharSequence undo, @StringRes int confirmLabel,
                               @NonNull Runnable onConfirm) {
        SpannableStringBuilder message = new SpannableStringBuilder();
        appendDangerHeading(context, message);
        message.append(what).append("\n\n").append(breaks).append("\n\n").append(undo);
        AlertDialog dialog = UIUtils.presentWithYellowBorder(context, UIUtils.yellowOnBlackDialog(context)
                .setTitle(title)
                .setMessage(message)
                // Cancel in the POSITIVE slot: see the class comment. Do not
                // "fix" this by swapping them back.
                .setPositiveButton(R.string.cancel, null)
                .setNegativeButton(confirmLabel, (d, which) -> onConfirm.run()));
        markDestructive(dialog);
    }

    /**
     * The confirmation for a lock that <b>this page releases in one tap</b>.
     * <p>
     * 白い熊, 2026-08-01: suspension, an uninstall block, a force-stop block and a
     * permission lock are all undone by the same control that applied them, so
     * calling them 危険 was simply wrong — the property they share is that
     * <em>Settings</em> cannot undo them and that they outlive this app, which is
     * what the {@code undo} line says. No red heading, and the action sits in the
     * positive slot like any ordinary confirmation; the asymmetric button
     * placement of {@link #confirm} is reserved for the powers that can leave the
     * phone hard to operate or hard to recover.
     */
    public static void confirmReversible(@NonNull Context context, @StringRes int title,
                                         @NonNull CharSequence what, @NonNull CharSequence breaks,
                                         @NonNull CharSequence undo, @StringRes int confirmLabel,
                                         @NonNull Runnable onConfirm) {
        CharSequence message = what + "\n\n" + breaks + "\n\n" + undo;
        UIUtils.presentWithYellowBorder(context, UIUtils.yellowOnBlackDialog(context)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(confirmLabel, (d, which) -> onConfirm.run()));
    }

    /** The 危険 heading the message opens with, in the same red as the button. */
    private static void appendDangerHeading(@NonNull Context context,
                                            @NonNull SpannableStringBuilder out) {
        int start = out.length();
        out.append(context.getString(R.string.policy_danger_tag));
        out.setSpan(new ForegroundColorSpan(DANGER_RED), start, out.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, out.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append("\n\n");
    }

    /**
     * Paint the destructive button red. Must run <em>after</em> {@code show()} —
     * the buttons do not exist before it, the same reason
     * {@link UIUtils#presentWithYellowBorder} has to re-apply the window
     * background there.
     */
    public static void markDestructive(@Nullable AlertDialog dialog) {
        if (dialog == null) return;
        try {
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setTextColor(DANGER_RED);
        } catch (Throwable ignore) {
            // A dialog without that button is not worth a crash.
        }
    }

    /** Report the outcome of a policy write, naming the reason when there is one. */
    @NonNull
    public static CharSequence describe(@NonNull Context context, boolean ok, @Nullable String error) {
        if (ok) return context.getString(R.string.policy_applied);
        if (error == null) return context.getString(R.string.policy_failed);
        return context.getString(R.string.policy_failed_reason, error);
    }
}
