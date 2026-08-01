// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.snooping;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    /**
     * Row groups, in the order they are rendered on the tab.
     * <p>
     * Ordered by <b>consequence, not by subsystem</b> (白い熊, +18): the network
     * first, because nothing an app collects can hurt you until it can leave the
     * phone; then accessibility, which subsumes nearly every other capability on
     * this page; then the screen, then the sensors, then the data at rest. The
     * enum's own order <i>is</i> the render order, and nothing persists it — the
     * stable wire keys are the entry ids, so this may be re-ordered freely.
     */
    public enum Group {
        NETWORK(R.string.snooping_group_network),
        ACCESSIBILITY(R.string.snooping_group_accessibility),
        SCREEN_WATCHING(R.string.snooping_group_screen_watching),
        LOCATION(R.string.snooping_group_location),
        MIC_CAMERA(R.string.snooping_group_mic_camera),
        MESSAGING_CALLS(R.string.snooping_group_messaging_calls),
        PERSONAL_DATA(R.string.snooping_group_personal_data),
        STORAGE_MEDIA(R.string.snooping_group_storage_media),
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
        /**
         * True for a capability whose switch is neither an op nor a permission —
         * a system list, a network policy, a role, the doze whitelist. See
         * {@link io.github.muntashirakon.AppManager.snooping.lever.SnoopingLever}.
         */
        public final boolean lever;

        private Entry(@NonNull String id, @StringRes int labelRes, @NonNull Group group,
                      @Nullable String opName, @Nullable String permission, boolean lever) {
            this.id = id;
            this.labelRes = labelRes;
            this.group = group;
            this.opName = opName;
            this.permission = permission;
            this.lever = lever;
        }

        static Entry op(@NonNull String id, @StringRes int labelRes, @NonNull Group group, @NonNull String opName) {
            return new Entry(id, labelRes, group, opName, null, false);
        }

        static Entry perm(@NonNull String id, @StringRes int labelRes, @NonNull Group group, @NonNull String permission) {
            return new Entry(id, labelRes, group, null, permission, false);
        }

        /** A capability moved by a {@code SnoopingLever} rather than by an op. */
        static Entry lever(@NonNull String id, @StringRes int labelRes, @NonNull Group group) {
            return new Entry(id, labelRes, group, null, null, true);
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
            // A lever row says what it is in its own detail chip; calling it
            // "needs no permission" would be true but useless noise.
            return permission == null && !entry.lever;
        }
    }

    // Ordered so the tab reads top-down from the most consequential to the least,
    // within groups that are themselves ordered that way — see Group.
    private static final Entry[] ENTRIES = {
            // ── Network ─────────────────────────────────────────────────────
            // First, because every other capability on this page is only as
            // dangerous as the app's ability to send what it took.
            Entry.lever("network_internet", R.string.snooping_network_internet, Group.NETWORK),
            Entry.op("vpn", R.string.snooping_vpn, Group.NETWORK, "ACTIVATE_VPN"),
            Entry.op("vpn_establish", R.string.snooping_vpn_establish, Group.NETWORK, "ESTABLISH_VPN_SERVICE"),
            Entry.op("vpn_establish_manager", R.string.snooping_vpn_establish_manager, Group.NETWORK,
                    "ESTABLISH_VPN_MANAGER"),
            // From the on-device report, 4.1.0+18: present on Android 13, absent
            // from the hand-written catalogue.
            Entry.op("vpn_platform", R.string.snooping_vpn_platform, Group.NETWORK, "ACTIVATE_PLATFORM_VPN"),
            Entry.op("ipsec_tunnels", R.string.snooping_ipsec_tunnels, Group.NETWORK, "MANAGE_IPSEC_TUNNELS"),

            // ── Accessibility & notifications ───────────────────────────────
            // The lever rows are the real gates; the ops beside them are narrower
            // checks the framework makes elsewhere, and are listed in their own
            // right because they are separately settable.
            Entry.lever("accessibility_service", R.string.snooping_accessibility_service, Group.ACCESSIBILITY),
            Entry.op("accessibility", R.string.snooping_accessibility, Group.ACCESSIBILITY, "ACCESS_ACCESSIBILITY"),
            Entry.lever("notification_listener", R.string.snooping_notification_listener, Group.ACCESSIBILITY),
            Entry.op("notifications_read", R.string.snooping_notifications_read, Group.ACCESSIBILITY,
                    "ACCESS_NOTIFICATIONS"),
            Entry.op("restricted_settings", R.string.snooping_restricted_settings, Group.ACCESSIBILITY,
                    "ACCESS_RESTRICTED_SETTINGS"),
            // The op the system consults when binding an accessibility service —
            // a second, independent gate beside the secure-settings list.
            Entry.op("accessibility_bind", R.string.snooping_accessibility_bind, Group.ACCESSIBILITY,
                    "BIND_ACCESSIBILITY_SERVICE"),

            // ── Watching the screen ─────────────────────────────────────────
            Entry.op("screen_capture", R.string.snooping_screen_capture, Group.SCREEN_WATCHING, "PROJECT_MEDIA"),
            Entry.lever("assistant_role", R.string.snooping_assistant_role, Group.SCREEN_WATCHING),
            Entry.op("assist_structure", R.string.snooping_assist_structure, Group.SCREEN_WATCHING, "ASSIST_STRUCTURE"),
            Entry.op("assist_screenshot", R.string.snooping_assist_screenshot, Group.SCREEN_WATCHING,
                    "ASSIST_SCREENSHOT"),
            Entry.op("clipboard_read", R.string.snooping_clipboard_read, Group.SCREEN_WATCHING, "READ_CLIPBOARD"),
            Entry.op("overlay", R.string.snooping_overlay, Group.SCREEN_WATCHING, "SYSTEM_ALERT_WINDOW"),
            Entry.op("usage_stats", R.string.snooping_usage_stats, Group.SCREEN_WATCHING, "GET_USAGE_STATS"),
            Entry.op("usage_stats_loader", R.string.snooping_usage_stats_loader, Group.SCREEN_WATCHING,
                    "LOADER_USAGE_STATS"),

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
            Entry.op("microphone_ambient_trigger", R.string.snooping_microphone_ambient_trigger, Group.MIC_CAMERA,
                    "RECEIVE_AMBIENT_TRIGGER_AUDIO"),
            Entry.op("microphone_call", R.string.snooping_microphone_call, Group.MIC_CAMERA, "PHONE_CALL_MICROPHONE"),
            Entry.op("camera_call", R.string.snooping_camera_call, Group.MIC_CAMERA, "PHONE_CALL_CAMERA"),
            // Android 14+: the sandboxed variants used by the SDK runtime.
            Entry.op("microphone_sandboxed", R.string.snooping_microphone_sandboxed, Group.MIC_CAMERA,
                    "RECORD_AUDIO_SANDBOXED"),
            Entry.op("camera_sandboxed", R.string.snooping_camera_sandboxed, Group.MIC_CAMERA, "CAMERA_SANDBOXED"),
            // Recording the other side of a call, and recording what the phone
            // plays — both real on Android 13 and both were missing.
            Entry.op("microphone_incoming_call", R.string.snooping_microphone_incoming_call, Group.MIC_CAMERA,
                    "RECORD_INCOMING_PHONE_AUDIO"),
            Entry.op("audio_output_capture", R.string.snooping_audio_output_capture, Group.MIC_CAMERA,
                    "RECORD_AUDIO_OUTPUT"),

            // ── Messaging & calls ───────────────────────────────────────────
            Entry.op("sms_read", R.string.snooping_sms_read, Group.MESSAGING_CALLS, "READ_SMS"),
            Entry.op("sms_receive", R.string.snooping_sms_receive, Group.MESSAGING_CALLS, "RECEIVE_SMS"),
            Entry.op("sms_send", R.string.snooping_sms_send, Group.MESSAGING_CALLS, "SEND_SMS"),
            Entry.op("sms_write", R.string.snooping_sms_write, Group.MESSAGING_CALLS, "WRITE_SMS"),
            Entry.op("sms_icc_read", R.string.snooping_sms_icc_read, Group.MESSAGING_CALLS, "READ_ICC_SMS"),
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
            Entry.op("sms_financial", R.string.snooping_sms_financial, Group.MESSAGING_CALLS,
                    "SMS_FINANCIAL_TRANSACTIONS"),
            Entry.op("icc_auth_identifier", R.string.snooping_icc_auth_identifier, Group.MESSAGING_CALLS,
                    "USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER"),
            Entry.op("calls_place", R.string.snooping_calls_place, Group.MESSAGING_CALLS, "CALL_PHONE"),
            // A second, parallel call stack: an app that places and answers SIP
            // calls carries the same microphone and call-metadata reach as the
            // dialler ops above, and is not covered by any of them.
            Entry.op("sip_calls", R.string.snooping_sip_calls, Group.MESSAGING_CALLS, "USE_SIP"),

            // ── Personal data ───────────────────────────────────────────────
            Entry.op("contacts_read", R.string.snooping_contacts_read, Group.PERSONAL_DATA, "READ_CONTACTS"),
            Entry.op("contacts_write", R.string.snooping_contacts_write, Group.PERSONAL_DATA, "WRITE_CONTACTS"),
            Entry.op("calendar_read", R.string.snooping_calendar_read, Group.PERSONAL_DATA, "READ_CALENDAR"),
            Entry.op("calendar_write", R.string.snooping_calendar_write, Group.PERSONAL_DATA, "WRITE_CALENDAR"),
            Entry.op("accounts_get", R.string.snooping_accounts_get, Group.PERSONAL_DATA, "GET_ACCOUNTS"),
            Entry.op("body_sensors", R.string.snooping_body_sensors, Group.PERSONAL_DATA, "BODY_SENSORS"),
            Entry.op("body_sensors_wrist", R.string.snooping_body_sensors_wrist, Group.PERSONAL_DATA,
                    "BODY_SENSORS_WRIST_TEMPERATURE"),
            Entry.op("health_data", R.string.snooping_health_data, Group.PERSONAL_DATA, "READ_WRITE_HEALTH_DATA"),
            Entry.op("activity_recognition", R.string.snooping_activity_recognition, Group.PERSONAL_DATA, "ACTIVITY_RECOGNITION"),
            // Seeing the full list of installed apps is a fingerprint in itself.
            Entry.op("query_all_packages", R.string.snooping_query_all_packages, Group.PERSONAL_DATA,
                    "QUERY_ALL_PACKAGES"),
            Entry.op("cross_profile", R.string.snooping_cross_profile, Group.PERSONAL_DATA,
                    "INTERACT_ACROSS_PROFILES"),
            // Beside "see every installed app": changing the set of installed
            // apps is the other half of the same reach. Neither is snooping in
            // the sense of a sensor, and both are how an app turns knowledge of
            // the phone into a change to it (白い熊, 2026-08-01).
            Entry.op("install_packages", R.string.snooping_install_packages, Group.PERSONAL_DATA,
                    "REQUEST_INSTALL_PACKAGES"),
            Entry.op("delete_packages", R.string.snooping_delete_packages, Group.PERSONAL_DATA,
                    "REQUEST_DELETE_PACKAGES"),
            // The system log carries what every other app printed — identifiers,
            // URLs, sometimes contents. No app-op exists for it on any release,
            // so the permission itself is the lever, like location_background.
            // Its protection level is signature|privileged|**development**, and
            // that development flag is exactly what lets us revoke it.
            Entry.perm("read_logs", R.string.snooping_read_logs, Group.PERSONAL_DATA,
                    "android.permission.READ_LOGS"),

            // ── Storage & media ─────────────────────────────────────────────
            Entry.op("storage_all_files", R.string.snooping_storage_all_files, Group.STORAGE_MEDIA, "MANAGE_EXTERNAL_STORAGE"),
            Entry.op("storage_read", R.string.snooping_storage_read, Group.STORAGE_MEDIA, "READ_EXTERNAL_STORAGE"),
            Entry.op("storage_write", R.string.snooping_storage_write, Group.STORAGE_MEDIA, "WRITE_EXTERNAL_STORAGE"),
            Entry.op("storage_legacy", R.string.snooping_storage_legacy, Group.STORAGE_MEDIA, "LEGACY_STORAGE"),
            Entry.op("storage_no_isolation", R.string.snooping_storage_no_isolation, Group.STORAGE_MEDIA,
                    "NO_ISOLATED_STORAGE"),
            Entry.op("media_images_read", R.string.snooping_media_images_read, Group.STORAGE_MEDIA, "READ_MEDIA_IMAGES"),
            Entry.op("media_video_read", R.string.snooping_media_video_read, Group.STORAGE_MEDIA, "READ_MEDIA_VIDEO"),
            Entry.op("media_audio_read", R.string.snooping_media_audio_read, Group.STORAGE_MEDIA, "READ_MEDIA_AUDIO"),
            Entry.op("media_visual_user_selected", R.string.snooping_media_visual_user_selected, Group.STORAGE_MEDIA,
                    "READ_MEDIA_VISUAL_USER_SELECTED"),
            Entry.op("media_manage", R.string.snooping_media_manage, Group.STORAGE_MEDIA, "MANAGE_MEDIA"),

            // ── Nearby ──────────────────────────────────────────────────────
            Entry.op("bluetooth_scan", R.string.snooping_bluetooth_scan, Group.NEARBY, "BLUETOOTH_SCAN"),
            Entry.op("bluetooth_connect", R.string.snooping_bluetooth_connect, Group.NEARBY, "BLUETOOTH_CONNECT"),
            Entry.op("bluetooth_advertise", R.string.snooping_bluetooth_advertise, Group.NEARBY, "BLUETOOTH_ADVERTISE"),
            Entry.op("wifi_scan", R.string.snooping_wifi_scan, Group.NEARBY, "WIFI_SCAN"),
            Entry.op("nearby_wifi_devices", R.string.snooping_nearby_wifi_devices, Group.NEARBY, "NEARBY_WIFI_DEVICES"),
            Entry.op("neighboring_cells", R.string.snooping_neighboring_cells, Group.NEARBY, "NEIGHBORING_CELLS"),
            Entry.op("uwb_ranging", R.string.snooping_uwb_ranging, Group.NEARBY, "UWB_RANGING"),
            // Toggling Wi-Fi is how an app forces a fresh scan, and a scan list is
            // a location fix by another name.
            Entry.op("wifi_state_change", R.string.snooping_wifi_state_change, Group.NEARBY, "CHANGE_WIFI_STATE"),

            // ── Background activity ─────────────────────────────────────────
            Entry.lever("battery_exemption", R.string.snooping_battery_exemption, Group.BACKGROUND),
            Entry.op("run_in_background", R.string.snooping_run_in_background, Group.BACKGROUND, "RUN_IN_BACKGROUND"),
            Entry.op("run_any_in_background", R.string.snooping_run_any_in_background, Group.BACKGROUND, "RUN_ANY_IN_BACKGROUND"),
            Entry.op("start_foreground", R.string.snooping_start_foreground, Group.BACKGROUND, "START_FOREGROUND"),
            Entry.op("user_initiated_jobs", R.string.snooping_user_initiated_jobs, Group.BACKGROUND,
                    "RUN_USER_INITIATED_JOBS"),
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
                    // Permission-only entry (the op does not exist, so the
                    // permission itself is what we grant/revoke), or a lever entry,
                    // whose switch is neither and which carries no permission.
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

    /**
     * Every op this device has that the catalogue does <b>not</b> cover, with the
     * ones we could never move already filtered out.
     * <p>
     * The catalogue was written from knowledge of what ops exist, which is exactly
     * the kind of thing that silently rots: ops are added every release, renamed
     * occasionally, and OEMs add their own. Rather than re-deriving the list from
     * memory each time, this asks the running platform — walk every op, drop the
     * ones {@link #isIndependentlySettable} rules out (they have no slot of their
     * own, so a row for them would be a lie), drop the ones already listed, and
     * report the rest. ⋮ → <i>Ops not in the catalogue</i> shows it, and it also
     * goes to the log, so extending the catalogue is a matter of reading a list
     * rather than remembering one.
     *
     * @return AOSP op names, in op-code order.
     */
    @NonNull
    public static List<String> missingOpNames() {
        Set<String> covered = new HashSet<>();
        for (Entry entry : ENTRIES) {
            if (entry.opName != null) {
                covered.add(entry.opName);
            }
        }
        List<String> missing = new ArrayList<>();
        for (int op = 0; op < AppOpsManagerCompat._NUM_OP; ++op) {
            try {
                String name = AppOpsManagerCompat.opToName(op);
                if (name == null || covered.contains(name) || !isIndependentlySettable(op)) {
                    continue;
                }
                if (name.toLowerCase(Locale.ROOT).startsWith("deprecated")) {
                    // The framework keeps retired op codes as placeholders named
                    // "deprecated" so the numbering stays stable. Nothing to add.
                    continue;
                }
                missing.add(name);
            } catch (Throwable ignore) {
                // Op not present on this platform.
            }
        }
        return missing;
    }

    /** The resolved catalogue entry with this wire id, or {@code null} if unknown here. */
    @Nullable
    public static Resolved byId(@NonNull String id) {
        resolved();
        Map<String, Resolved> byId = sResolvedById;
        return byId != null ? byId.get(id) : null;
    }
}
