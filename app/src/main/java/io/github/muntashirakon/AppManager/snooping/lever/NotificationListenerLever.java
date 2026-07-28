// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.snooping.lever;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.snooping.SnoopingComponents;

/**
 * Fork: is this app reading every notification on the phone?
 * <p>
 * A bound {@code NotificationListenerService} receives the full content of every
 * notification from every app — including the one-time codes banks and messengers
 * put there — and can dismiss them before they are seen. The gate is the
 * {@code enabled_notification_listeners} secure setting;
 * {@code ACCESS_NOTIFICATIONS}, the op the tab used to show for this, is checked
 * elsewhere and does not stop a bound listener.
 */
public class NotificationListenerLever extends SecureListLever {
    /** Not exposed as a constant by the framework, unlike the accessibility one. */
    private static final String KEY = "enabled_notification_listeners";

    public NotificationListenerLever() {
        super(KEY, SnoopingComponents.BIND_NOTIFICATION_LISTENER);
    }

    @Nullable
    @Override
    public CharSequence detail(@NonNull Context context) {
        return context.getString(R.string.snooping_detail_secure_setting, KEY);
    }
}
