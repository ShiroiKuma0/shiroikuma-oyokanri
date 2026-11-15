// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.utils;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.theme.MaterialComponentsViewInflater;

/**
 * Fork (白い熊, +104): named by {@code viewInflaterClass} in {@code AppTheme.YellowOnBlack}, so
 * every {@link MaterialSwitch} a black-yellow activity inflates — from a layout or through
 * {@code View.inflate} — is the theme's outlined switch ({@link ForkSwitch}) without each layout
 * and call site having to ask for it.
 */
@Keep
public class ForkViewInflater extends MaterialComponentsViewInflater {
    @Nullable
    @Override
    protected View createView(@NonNull Context context, @NonNull String name, @NonNull AttributeSet attrs) {
        if ("com.google.android.material.materialswitch.MaterialSwitch".equals(name)) {
            try {
                MaterialSwitch sw = new MaterialSwitch(context, attrs);
                ForkSwitch.outline(sw);
                return sw;
            } catch (Throwable ignore) {
            }
        }
        return super.createView(context, name, attrs);
    }
}
