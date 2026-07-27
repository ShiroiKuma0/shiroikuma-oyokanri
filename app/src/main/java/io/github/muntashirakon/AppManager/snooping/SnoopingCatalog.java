// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.snooping;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.compat.AppOpsManagerCompat;

/**
 * Fork: the curated catalogue of snooping capabilities shown on the app-details
 * <em>Snooping</em> tab.
 * <p>
 * Every entry is a capability we can <b>actually</b> turn off on a non-rooted
 * phone through ADB/Shizuku — either an app-op whose mode we can set, or (rarely)
 * a runtime permission with no matching op. Anything that would merely <i>look</i>
 * like a switch while doing nothing is deliberately absent: an entry that does not
 * resolve on this Android version is dropped at {@link #resolved()} time rather
 * than rendered as a dead row.
 * <p>
 * Entries name their app-op by its AOSP <b>name</b> ({@code "FINE_LOCATION"}),
 * never by its numeric code — codes are renumbered between Android releases,
 * names are not. {@link #opCodeOf} resolves a name against the running platform.
 * <p>
 * The {@link Entry#id} is a <b>stable wire key</b>: it is what
 * {@link SnoopingPrefs} persists per package and therefore what travels in a
 * settings export and lands on a new phone. <b>Never rename an id</b> — doing so
 * silently orphans every stored setting that used it.
 */
public final class SnoopingCatalog {
    /** Row groups, in the order they are rendered on the tab. */
    public enum Group {
        LOCATION(R.string.snooping_group_location),
        MIC_CAMERA(R.string.snooping_group_mic_camera),
        MESSAGING_CALLS(R.string.snooping_group_messaging_calls),
        PERSONAL_DATA(R.string.snooping_group_personal_data),
        STORAGE_MEDIA(R.string.snooping_group_storage_media),
        SCREEN_WATCHING(R.string.snooping_group_screen_watching),
        NEARBY(R.string.snooping_group_nearby),
        BACKGROUND(R.string.snooping_group_background);

        @StringRes
        public final int labelRes;

        Group(@StringRes int labelRes) {
            this.labelRes = labelRes;
        }
    }

    /** One catalogue entry, independent of any particular package. */
    public static final class Entry {
        /** Stable wire key — persisted and exported. NEVER rename. */
        @NonNull
        public final String id;
        @StringRes
        public final int labelRes;
        @NonNull
        public final Group group;
        /**
         * AOSP app-op name, or {@code null} for the handful of capabilities that
         * are permission-only (no op exists, so the permission is the only lever).
         */
        @Nullable
        public final String opName;
        /**
         * Manifest permission to drive directly. Only set for permission-only
         * entries; for op-backed entries the op's own permission (if any) is
         * discovered at runtime via {@link AppOpsManagerCompat#opToPermission}.
         */
        @Nullable
        public final String permission;

        private Entry(@NonNull String id, @StringRes int labelRes, @NonNull Group group,
                      @Nullable String opName, @Nullable String permission) {
            this.id = id;
            this.labelRes = labelRes;
            this.group = group;
            this.opName = opName;
            this.permission = permission;
        }

        static Entry op(@NonNull String id, @StringRes int labelRes, @NonNull Group group, @NonNull String opName) {
            return new Entry(id, labelRes, group, opName, null);
        }

        static Entry perm(@NonNull String id, @StringRes int labelRes, @NonNull Group group, @NonNull String permission) {
            return new Entry(id, labelRes, group, null, permission);
        }
    }

    /** A catalogue entry that exists on <em>this</em> device, with its op code resolved. */
    public static final class Resolved {
        @NonNull
        public final Entry entry;
        /** Resolved app-op code, or {@link AppOpsManagerCompat#OP_NONE} for permission-only entries. */
        public final int op;
        /**
         * The manifest permission this capability is gated behind, or {@code null}
         * when the app needs no permission at all to use it — which is exactly what
         * makes an entry worth showing even for an app that never requested it.
         */
        @Nullable
        public final String permission;

        Resolved(@NonNull Entry entry, int op, @Nullable String permission) {
            this.entry = entry;
            this.op = op;
            this.permission = permission;
        }

        /** True when the app needs no manifest permission to use this capability. */
        public boolean isUngated() {
            return permission == null;
        }
    }

    // Ordered so the tab reads top-down from the most invasive to the least.
    private static final Entry[] ENTRIES = {
            // ── Location ────────────────────────────────────────────────────
            Entry.op("location_precise", R.string.snooping_location_precise, Group.LOCATION, "FINE_LOCATION"),
            Entry.op("location_approximate", R.string.snooping_location_approximate, Group.LOCATION, "COARSE_LOCATION"),
            Entry.perm("location_background", R.string.snooping_location_background, Group.LOCATION,
                    "android.permission.ACCESS_BACKGROUND_LOCATION"),
            Entry.op("location_media", R.string.snooping_location_media, Group.LOCATION, "ACCESS_MEDIA_LOCATION"),
            Entry.op("location_monitor_high_power", R.string.snooping_location_monitor_high_power, Group.LOCATION,
                    "MONITOR_HIGH_POWER_LOCATION"),
            Entry.op("location_gps", R.string.snooping_location_gps, Group.LOCATION, "GPS"),

            // ── Microphone & camera ─────────────────────────────────────────
            Entry.op("microphone", R.string.snooping_microphone, Group.MIC_CAMERA, "RECORD_AUDIO"),
            Entry.op("camera", R.string.snooping_camera, Group.MIC_CAMERA, "CAMERA"),
            Entry.op("microphone_hotword", R.string.snooping_microphone_hotword, Group.MIC_CAMERA, "RECORD_AUDIO_HOTWORD"),
            Entry.op("microphone_call", R.string.snooping_microphone_call, Group.MIC_CAMERA, "PHONE_CALL_MICROPHONE"),
            Entry.op("camera_call", R.string.snooping_camera_call, Group.MIC_CAMERA, "PHONE_CALL_CAMERA"),

            // ── Messaging & calls ───────────────────────────────────────────
            Entry.op("sms_read", R.string.snooping_sms_read, Group.MESSAGING_CALLS, "READ_SMS"),
            Entry.op("sms_receive", R.string.snooping_sms_receive, Group.MESSAGING_CALLS, "RECEIVE_SMS"),
            Entry.op("sms_send", R.string.snooping_sms_send, Group.MESSAGING_CALLS, "SEND_SMS"),
            Entry.op("sms_write", R.string.snooping_sms_write, Group.MESSAGING_CALLS, "WRITE_SMS"),
            Entry.op("mms_receive", R.string.snooping_mms_receive, Group.MESSAGING_CALLS, "RECEIVE_MMS"),
            Entry.op("wap_push_receive", R.string.snooping_wap_push_receive, Group.MESSAGING_CALLS, "RECEIVE_WAP_PUSH"),
            Entry.op("cell_broadcasts_read", R.string.snooping_cell_broadcasts_read, Group.MESSAGING_CALLS, "READ_CELL_BROADCASTS"),
            Entry.op("call_log_read", R.string.snooping_call_log_read, Group.MESSAGING_CALLS, "READ_CALL_LOG"),
            Entry.op("call_log_write", R.string.snooping_call_log_write, Group.MESSAGING_CALLS, "WRITE_CALL_LOG"),
            Entry.op("calls_outgoing", R.string.snooping_calls_outgoing, Group.MESSAGING_CALLS, "PROCESS_OUTGOING_CALLS"),
            Entry.op("calls_answer", R.string.snooping_calls_answer, Group.MESSAGING_CALLS, "ANSWER_PHONE_CALLS"),
            Entry.op("calls_manage_ongoing", R.string.snooping_calls_manage_ongoing, Group.MESSAGING_CALLS, "MANAGE_ONGOING_CALLS"),
            Entry.op("phone_state", R.string.snooping_phone_state, Group.MESSAGING_CALLS, "READ_PHONE_STATE"),
            Entry.op("phone_numbers", R.string.snooping_phone_numbers, Group.MESSAGING_CALLS, "READ_PHONE_NUMBERS"),
            Entry.op("device_identifiers", R.string.snooping_device_identifiers, Group.MESSAGING_CALLS, "READ_DEVICE_IDENTIFIERS"),
            Entry.op("voicemail_add", R.string.snooping_voicemail_add, Group.MESSAGING_CALLS, "ADD_VOICEMAIL"),

            // ── Personal data ───────────────────────────────────────────────
            Entry.op("contacts_read", R.string.snooping_contacts_read, Group.PERSONAL_DATA, "READ_CONTACTS"),
            Entry.op("contacts_write", R.string.snooping_contacts_write, Group.PERSONAL_DATA, "WRITE_CONTACTS"),
            Entry.op("calendar_read", R.string.snooping_calendar_read, Group.PERSONAL_DATA, "READ_CALENDAR"),
            Entry.op("calendar_write", R.string.snooping_calendar_write, Group.PERSONAL_DATA, "WRITE_CALENDAR"),
            Entry.op("accounts_get", R.string.snooping_accounts_get, Group.PERSONAL_DATA, "GET_ACCOUNTS"),
            Entry.op("body_sensors", R.string.snooping_body_sensors, Group.PERSONAL_DATA, "BODY_SENSORS"),
            Entry.op("activity_recognition", R.string.snooping_activity_recognition, Group.PERSONAL_DATA, "ACTIVITY_RECOGNITION"),

            // ── Storage & media ─────────────────────────────────────────────
            Entry.op("storage_all_files", R.string.snooping_storage_all_files, Group.STORAGE_MEDIA, "MANAGE_EXTERNAL_STORAGE"),
            Entry.op("storage_read", R.string.snooping_storage_read, Group.STORAGE_MEDIA, "READ_EXTERNAL_STORAGE"),
            Entry.op("storage_write", R.string.snooping_storage_write, Group.STORAGE_MEDIA, "WRITE_EXTERNAL_STORAGE"),
            Entry.op("media_images_read", R.string.snooping_media_images_read, Group.STORAGE_MEDIA, "READ_MEDIA_IMAGES"),
            Entry.op("media_video_read", R.string.snooping_media_video_read, Group.STORAGE_MEDIA, "READ_MEDIA_VIDEO"),
            Entry.op("media_audio_read", R.string.snooping_media_audio_read, Group.STORAGE_MEDIA, "READ_MEDIA_AUDIO"),
            Entry.op("media_manage", R.string.snooping_media_manage, Group.STORAGE_MEDIA, "MANAGE_MEDIA"),

            // ── Watching the screen ─────────────────────────────────────────
            Entry.op("screen_capture", R.string.snooping_screen_capture, Group.SCREEN_WATCHING, "PROJECT_MEDIA"),
            Entry.op("assist_screenshot", R.string.snooping_assist_screenshot, Group.SCREEN_WATCHING, "ASSIST_SCREENSHOT"),
            Entry.op("assist_structure", R.string.snooping_assist_structure, Group.SCREEN_WATCHING, "ASSIST_STRUCTURE"),
            Entry.op("clipboard_read", R.string.snooping_clipboard_read, Group.SCREEN_WATCHING, "READ_CLIPBOARD"),
            Entry.op("overlay", R.string.snooping_overlay, Group.SCREEN_WATCHING, "SYSTEM_ALERT_WINDOW"),
            Entry.op("notifications_read", R.string.snooping_notifications_read, Group.SCREEN_WATCHING, "ACCESS_NOTIFICATIONS"),
            Entry.op("accessibility", R.string.snooping_accessibility, Group.SCREEN_WATCHING, "ACCESS_ACCESSIBILITY"),
            Entry.op("usage_stats", R.string.snooping_usage_stats, Group.SCREEN_WATCHING, "GET_USAGE_STATS"),

            // ── Nearby & network ────────────────────────────────────────────
            Entry.op("bluetooth_scan", R.string.snooping_bluetooth_scan, Group.NEARBY, "BLUETOOTH_SCAN"),
            Entry.op("bluetooth_connect", R.string.snooping_bluetooth_connect, Group.NEARBY, "BLUETOOTH_CONNECT"),
            Entry.op("bluetooth_advertise", R.string.snooping_bluetooth_advertise, Group.NEARBY, "BLUETOOTH_ADVERTISE"),
            Entry.op("wifi_scan", R.string.snooping_wifi_scan, Group.NEARBY, "WIFI_SCAN"),
            Entry.op("nearby_wifi_devices", R.string.snooping_nearby_wifi_devices, Group.NEARBY, "NEARBY_WIFI_DEVICES"),
            Entry.op("neighboring_cells", R.string.snooping_neighboring_cells, Group.NEARBY, "NEIGHBORING_CELLS"),
            Entry.op("uwb_ranging", R.string.snooping_uwb_ranging, Group.NEARBY, "UWB_RANGING"),
            Entry.op("vpn", R.string.snooping_vpn, Group.NEARBY, "ACTIVATE_VPN"),

            // ── Background activity ─────────────────────────────────────────
            Entry.op("run_in_background", R.string.snooping_run_in_background, Group.BACKGROUND, "RUN_IN_BACKGROUND"),
            Entry.op("run_any_in_background", R.string.snooping_run_any_in_background, Group.BACKGROUND, "RUN_ANY_IN_BACKGROUND"),
            Entry.op("start_foreground", R.string.snooping_start_foreground, Group.BACKGROUND, "START_FOREGROUND"),
    };

    @Nullable
    private static volatile Map<String, Integer> sOpNameToCode;
    @Nullable
    private static volatile List<Resolved> sResolved;
    @Nullable
    private static volatile Map<String, Resolved> sResolvedById;

    private SnoopingCatalog() {
    }

    /**
     * Op name → op code on the running platform. Built once by walking every op
     * and asking the platform for its name, so it stays correct across releases
     * that renumber ops.
     */
    @NonNull
    private static Map<String, Integer> opNameToCode() {
        Map<String, Integer> map = sOpNameToCode;
        if (map != null) {
            return map;
        }
        synchronized (SnoopingCatalog.class) {
            if (sOpNameToCode != null) {
                return sOpNameToCode;
            }
            Map<String, Integer> built = new HashMap<>(AppOpsManagerCompat._NUM_OP);
            for (int op = 0; op < AppOpsManagerCompat._NUM_OP; ++op) {
                try {
                    String name = AppOpsManagerCompat.opToName(op);
                    if (name != null && !built.containsKey(name)) {
                        built.put(name, op);
                    }
                } catch (Throwable ignore) {
                    // Op not present on this platform
                }
            }
            sOpNameToCode = built;
            return built;
        }
    }

    /**
     * Whether an op can hold a mode of its own, i.e. whether setting it does
     * anything at all.
     * <p>
     * <b>Verified on-device, 2026-07-27.</b> {@code AppOpsService} runs every
     * write through {@link AppOpsManagerCompat#opToSwitch} before storing it, so
     * an op whose <i>switch op</i> is some other op has no slot of its own: the
     * write silently lands on the controlling op instead. On the Mate XT
     * (Android 13), clearing {@code COARSE_LOCATION} and then writing
     * {@code GPS = ignore} produced {@code COARSE_LOCATION: ignore} — {@code GPS}
     * itself still read back "No operations. Default mode: allow". The same
     * holds for {@code MONITOR_HIGH_POWER_LOCATION}.
     * <p>
     * Such a row can never move, for us or for {@code adb} or for root, so it has
     * no business on this tab — the controlling op (Precise/Approximate location
     * here) is the real switch and is listed in its own right. Expressed as the
     * platform's own rule rather than a hardcoded list, so an op that is
     * merged on one Android version and split on another is handled correctly
     * without anyone having to notice.
     */
    private static boolean isIndependentlySettable(int op) {
        try {
            return AppOpsManagerCompat.opToSwitch(op) == op;
        } catch (Throwable th) {
            // Cannot tell — keep the row rather than hide a working lever.
            return true;
        }
    }

    /** The op code for an AOSP op name, or {@link AppOpsManagerCompat#OP_NONE} if absent here. */
    public static int opCodeOf(@Nullable String opName) {
        if (opName == null) {
            return AppOpsManagerCompat.OP_NONE;
        }
        Integer op = opNameToCode().get(opName);
        return op != null ? op : AppOpsManagerCompat.OP_NONE;
    }

    /**
     * Every catalogue entry that exists on this device, in catalogue order.
     * Entries whose op (or permission) does not exist on this Android version
     * are dropped — a switch that cannot move is worse than no switch.
     */
    @NonNull
    public static List<Resolved> resolved() {
        List<Resolved> resolved = sResolved;
        if (resolved != null) {
            return resolved;
        }
        synchronized (SnoopingCatalog.class) {
            if (sResolved != null) {
                return sResolved;
            }
            List<Resolved> built = new ArrayList<>(ENTRIES.length);
            Map<String, Resolved> byId = new LinkedHashMap<>(ENTRIES.length);
            for (Entry entry : ENTRIES) {
                Resolved r;
                if (entry.opName != null) {
                    int op = opCodeOf(entry.opName);
                    if (op == AppOpsManagerCompat.OP_NONE || op >= AppOpsManagerCompat._NUM_OP) {
                        // Not an op on this Android version — nothing to toggle.
                        continue;
                    }
                    if (!isIndependentlySettable(op)) {
                        // Governed by another op — see isIndependentlySettable.
                        continue;
                    }
                    String permission;
                    try {
                        permission = AppOpsManagerCompat.opToPermission(op);
                    } catch (Throwable th) {
                        permission = null;
                    }
                    r = new Resolved(entry, op, permission);
                } else {
                    // Permission-only entry: the op lever does not exist, so the
                    // permission itself is what we grant/revoke.
                    r = new Resolved(entry, AppOpsManagerCompat.OP_NONE, entry.permission);
                }
                built.add(r);
                byId.put(entry.id, r);
            }
            sResolvedById = byId;
            sResolved = Collections.unmodifiableList(built);
            return sResolved;
        }
    }

    /** The resolved catalogue entry with this wire id, or {@code null} if unknown here. */
    @Nullable
    public static Resolved byId(@NonNull String id) {
        resolved();
        Map<String, Resolved> byId = sResolvedById;
        return byId != null ? byId.get(id) : null;
    }
}
