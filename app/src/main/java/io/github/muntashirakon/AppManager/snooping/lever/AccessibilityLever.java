// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.snooping.lever;

import android.content.Context;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.snooping.SnoopingComponents;

/**
 * Fork: is this app's accessibility service actually switched on?
 * <p>
 * The one capability that subsumes nearly every other on this page — an enabled
 * accessibility service can read the text of every window, watch every keystroke
 * and press buttons on the user's behalf, with no app-op and no runtime
 * permission involved. The gate is
 * {@link Settings.Secure#ENABLED_ACCESSIBILITY_SERVICES}, and
 * {@code accessibility_enabled} follows it down when the list empties.
 */
public class AccessibilityLever extends SecureListLever {
    public AccessibilityLever() {
        super(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, SnoopingComponents.BIND_ACCESSIBILITY);
    }

    @Nullable
    @Override
    String masterSwitchKey() {
        return Settings.Secure.ACCESSIBILITY_ENABLED;
    }

    @Nullable
    @Override
    public CharSequence detail(@NonNull Context context) {
        return context.getString(R.string.snooping_detail_secure_setting,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
    }
}
