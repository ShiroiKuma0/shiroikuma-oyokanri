// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.utils;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.theme.MaterialComponentsViewInflater;

/**
 * Fork (白い熊, +104; buttons, floating buttons and top bars +123): named by
 * {@code viewInflaterClass} in {@code AppTheme.YellowOnBlack}, which since +122 is the theme of
 * every screen and — through the dialog overlay's parent context — of every dialog. Each widget
 * the theme has a rule for is restyled here as it is inflated, so no layout and no call site has
 * to ask:
 * <ul>
 * <li>{@link MaterialSwitch} → the outlined switch ({@link ForkSwitch});</li>
 * <li>every button → an outlined pill ({@link Pills}); a filled one in the layout, a button with
 *     no style, and a dialog's positive button are the <em>main</em> pill;</li>
 * <li>floating buttons → a ring, top bars → their own colour roles.</li>
 * </ul>
 */
@Keep
public class ForkViewInflater extends MaterialComponentsViewInflater {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    /** Whether the layout marks this button as the block's main action. */
    private static boolean isMain(@NonNull Context context, @NonNull AttributeSet attrs) {
        try {
            if (attrs.getAttributeResourceValue(ANDROID, "id", 0) == android.R.id.button1) return true;
            int style = attrs.getStyleAttribute();
            if (style == 0) {
                // "?attr/buttonBar…ButtonStyle" and the like are not main; a bare <Button> is M3's filled one.
                String raw = attrs.getAttributeValue(null, "style");
                return raw == null;
            }
            String name = context.getResources().getResourceEntryName(style);
            return name.contains("FilledButton") || name.endsWith("Dialog.YellowOutlined.Main")
                    || name.contains("Button.EFAB");
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isGlyphStyle(@NonNull Context context, @NonNull AttributeSet attrs) {
        try {
            int style = attrs.getStyleAttribute();
            return style != 0 && context.getResources().getResourceEntryName(style).contains("IconButton");
        } catch (Throwable t) {
            return false;
        }
    }

    @NonNull
    @Override
    protected AppCompatButton createButton(@NonNull Context context, @NonNull AttributeSet attrs) {
        AppCompatButton button = super.createButton(context, attrs);
        if (button instanceof MaterialButton && !isGlyphStyle(context, attrs)) {
            Pills.onInflated((MaterialButton) button, isMain(context, attrs));
        }
        return button;
    }

    @Nullable
    @Override
    protected View createView(@NonNull Context context, @NonNull String name, @NonNull AttributeSet attrs) {
        try {
            switch (name) {
                case "com.google.android.material.materialswitch.MaterialSwitch": {
                    MaterialSwitch sw = new MaterialSwitch(context, attrs);
                    ForkSwitch.outline(sw);
                    return sw;
                }
                case "com.google.android.material.button.MaterialButton": {
                    MaterialButton b = new MaterialButton(context, attrs);
                    if (!isGlyphStyle(context, attrs)) Pills.onInflated(b, isMain(context, attrs));
                    return b;
                }
                case "com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton": {
                    ExtendedFloatingActionButton b = new ExtendedFloatingActionButton(context, attrs);
                    Pills.onInflated(b, true);
                    return b;
                }
                case "com.google.android.material.floatingactionbutton.FloatingActionButton": {
                    FloatingActionButton b = new FloatingActionButton(context, attrs);
                    Pills.floating(b);
                    return b;
                }
                case "com.google.android.material.appbar.MaterialToolbar": {
                    Toolbar bar = new com.google.android.material.appbar.MaterialToolbar(context, attrs);
                    Pills.toolbar(bar);
                    return bar;
                }
                case "androidx.appcompat.widget.Toolbar": {
                    Toolbar bar = new Toolbar(context, attrs);
                    Pills.toolbar(bar);
                    return bar;
                }
                case "io.github.muntashirakon.widget.AppBarLayout": {
                    // libcore's own subclass — the one nearly every layout of the app uses.
                    AppBarLayout bar = new io.github.muntashirakon.widget.AppBarLayout(context, attrs);
                    Pills.appBar(bar);
                    return bar;
                }
                case "com.google.android.material.appbar.AppBarLayout": {
                    AppBarLayout bar = new AppBarLayout(context, attrs);
                    Pills.appBar(bar);
                    return bar;
                }
                default:
            }
        } catch (Throwable ignore) {
        }
        return super.createView(context, name, attrs);
    }
}
