// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.snooping.lever;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Fork: capability id → the lever that moves it.
 * <p>
 * The ids are the same stable wire keys the rest of the tab uses, so a lever row
 * is stored, exported and replayed exactly like an app-op row. <b>Never rename
 * one</b> — it would orphan every decision recorded under it.
 */
public final class SnoopingLevers {
    private static final Map<String, SnoopingLever> LEVERS = new HashMap<>();

    static {
        LEVERS.put("network_internet", new NetworkLever());
        LEVERS.put("accessibility_service", new AccessibilityLever());
        LEVERS.put("notification_listener", new NotificationListenerLever());
        LEVERS.put("assistant_role", new AssistantRoleLever());
        LEVERS.put("battery_exemption", new BatteryExemptionLever());
    }

    private SnoopingLevers() {
    }

    @Nullable
    public static SnoopingLever byId(@NonNull String capabilityId) {
        return LEVERS.get(capabilityId);
    }
}
