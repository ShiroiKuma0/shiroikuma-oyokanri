// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.utils;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.util.UiUtils;

/**
 * Fork: helpers for the fork's yellow-on-black {@link MaterialAlertDialogBuilder}
 * dialogs.
 * <p>
 * Two things the M3 dialog can't do from theme alone are centralised here:
 * <ul>
 *   <li>{@link #builder(Context)} passes the yellow-on-black dialog overlay
 *       explicitly, so the dialog gets the yellow text / radio / button styling
 *       regardless of whether {@code materialAlertDialogTheme} survived on the
 *       host activity.</li>
 *   <li>{@link #present(MaterialAlertDialogBuilder)} paints the yellow window
 *       border after {@code show()}. {@code MaterialAlertDialogBuilder.create()}
 *       rebuilds the window background as a stroke-less {@code MaterialShapeDrawable},
 *       so the border can only be applied once the dialog has been built - it is
 *       not achievable with a pure-theme window background.</li>
 * </ul>
 * The border itself is shared with every libcore dialog builder via
 * {@link UiUtils#applyForkDialogBorder(AlertDialog)} (driven by the
 * {@code forkDialogBorderDrawable} theme attribute), so there is a single source
 * of truth for the frame.
 */
public final class ForkDialog {
    private ForkDialog() {
    }

    /**
     * A {@link MaterialAlertDialogBuilder} pinned to the yellow-on-black dialog
     * overlay. Pair with {@link #present(MaterialAlertDialogBuilder)} to also get
     * the yellow window border.
     */
    @NonNull
    public static MaterialAlertDialogBuilder builder(@NonNull Context context) {
        return new MaterialAlertDialogBuilder(context,
                R.style.ThemeOverlay_AppTheme_MaterialAlertDialog_YellowOnBlack);
    }

    /**
     * Create, show and paint the yellow border on a dialog built from the given
     * builder. Returns the shown dialog (e.g. to wire up button click guards).
     */
    @NonNull
    public static AlertDialog present(@NonNull MaterialAlertDialogBuilder builder) {
        AlertDialog dialog = builder.create();
        dialog.show();
        UiUtils.applyForkDialogBorder(dialog);
        return dialog;
    }

    /**
     * Paint the yellow border on an already-built dialog and return it. For the
     * call sites that cannot use {@link #present(MaterialAlertDialogBuilder)}
     * because they do not own the {@code show()} - chiefly
     * {@code DialogFragment.onCreateDialog}, which returns the dialog for the
     * fragment to show later, and dialogs whose buttons are re-wired after
     * {@code create()}.
     * <p>
     * Applying the border straight after {@code create()} is enough: M3 rebuilds
     * the window background inside {@code create()} only, never again in
     * {@code show()}.
     */
    @NonNull
    public static AlertDialog bordered(@NonNull AlertDialog dialog) {
        UiUtils.applyForkDialogBorder(dialog);
        return dialog;
    }
}
