// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.battery;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fork: parser for the {@code dumpsys batterystats} text dump.
 *
 * <p><b>Why the text dump and not an API.</b> {@code BatteryStatsManager}
 * reports per-app <i>mAh</i>, and on this device that number is worthless: the
 * Huawei power profile is gutted, so the dump reads {@code Capacity: 5.00} and
 * {@code Computed drain: 0} (measured on the Mate XT, 2026-07-29). What still
 * works are the raw counters — bytes, packets, wakelock ms, radio-active ms —
 * and those live in the text section.
 *
 * <p><b>Three landmines, all measured on-device (2026-07-29); do not "simplify"
 * around them:</b>
 * <ol>
 *   <li>Only the section headed {@code Statistics since last charge:} is scoped
 *       to the charge window. The {@code --checkin} network counters look like
 *       the same numbers but are <b>lifetime</b> totals — they read 5–6× higher
 *       for the same uid. Never mix the two sources.</li>
 *   <li>The per-uid {@code Proc <name>: CPU: … usr + … krn} lines accumulate
 *       over the <b>process lifetime</b>, not the charge window, so a
 *       long-lived process shows minutes of CPU inside a 4-minute window.
 *       {@code Total cpu time: u= s=} is the window-scoped field and is the one
 *       parsed here (it is barely populated on this device, hence the counters
 *       above carry the ranking).</li>
 *   <li>Durations print as {@code 1h 14m 1s 69ms}. A {@code (\d+)(h|m|s|ms)}
 *       regex matches "69ms" as 69 <i>minutes</i> — the {@code ms} alternative
 *       must come first. This cost a wrong answer once already.</li>
 * </ol>
 */
public final class BatteryStatsParser {
    private BatteryStatsParser() {}

    private static final String SECTION = "Statistics since last charge:";

    // ms BEFORE m — see the class doc.
    private static final Pattern DURATION = Pattern.compile("(\\d+)(ms|d|h|m|s)");
    private static final Pattern UID_HEADER = Pattern.compile("^ {2}(u\\d+a\\d+|\\d+):$");
    private static final Pattern NETWORK = Pattern.compile(
            "(Wi-Fi|Mobile) network: ([\\d.]+)(GB|MB|KB|B) received, ([\\d.]+)(GB|MB|KB|B) sent "
                    + "\\(packets (\\d+) received, (\\d+) sent\\)");
    private static final Pattern RADIO_ACTIVE = Pattern.compile("Mobile radio active: (.+?) \\(");
    private static final Pattern TOTAL_WAKE = Pattern.compile("TOTAL wake: (.+?) blamed partial");
    private static final Pattern CPU_TIME = Pattern.compile("Total cpu time: u=(\\S+) s=(\\S+)");
    private static final Pattern FG_SERVICE = Pattern.compile("Foreground services: (.+?) realtime");
    private static final Pattern WAKEUP_ALARM = Pattern.compile("Wakeup alarm .*?: (\\d+) times");
    private static final Pattern SENSOR = Pattern.compile("^ +Sensor .*?: (.+?) realtime");
    // "Estimated power use (mAh)" — present on every device, meaningful only
    // where power_profile.xml is real. See BatterySnapshot#isPowerModelUsable.
    private static final Pattern POWER_HEADER = Pattern.compile(
            "Capacity: ([\\d.]+), Computed drain: ([\\d.]+)");
    private static final Pattern POWER_UID = Pattern.compile(
            "^ +UID (u\\d+a\\d+|\\d+): ([\\d.]+)(?: \\((.*)\\))?");
    private static final Pattern POWER_COMPONENT = Pattern.compile("([a-z_]+)=([\\d.]+)");

    /**
     * Per-uid lines of the shape {@code Label: <duration> …}. Table-driven so a
     * counter this platform prints and another does not simply appears or
     * doesn't — the fork runs on several phones and each dumps a different
     * subset. Adding one is a single row here.
     */
    private static final String[][] DURATION_METRICS = {
            {"Foreground activities", "foreground_activity_ms"},
            {"Top for", "top_ms"},
            {"Fg Service for", "fg_service_state_ms"},
            {"Background for", "background_ms"},
            {"Cached for", "cached_ms"},
            {"Wifi Running", "wifi_running_ms"},
            {"Full Wifi Lock", "wifi_full_lock_ms"},
            {"Wifi Scan", "wifi_scan_ms"},
            {"Bluetooth Scan", "bluetooth_scan_ms"},
            {"Audio", "audio_ms"},
            {"Video", "video_ms"},
            {"Camera", "camera_ms"},
            {"Flashlight", "flashlight_ms"},
            {"Vibrator", "vibrator_ms"},
    };
    private static final Pattern[] DURATION_PATTERNS = buildDurationPatterns();
    /** {@code Job <name>: <dur> realtime (N times)} and the same for Sync. */
    private static final Pattern JOB = Pattern.compile("^ +Job \\S+: (.+?) realtime \\((\\d+) times\\)");
    private static final Pattern SYNC = Pattern.compile("^ +Sync \\S+: (.+?) realtime \\((\\d+) times\\)");
    private static final Pattern RADIO_ACTIVE_COUNT = Pattern.compile("Mobile radio active: .*?\\) (\\d+)x");

    private static Pattern[] buildDurationPatterns() {
        Pattern[] patterns = new Pattern[DURATION_METRICS.length];
        for (int i = 0; i < DURATION_METRICS.length; i++) {
            patterns[i] = Pattern.compile("^ +" + Pattern.quote(DURATION_METRICS[i][0]) + ": (.+?)(?: realtime| \\(|$)");
        }
        return patterns;
    }

    private static final Pattern START_CLOCK = Pattern.compile("Start clock time: (\\S+)");
    private static final Pattern TIME_ON_BATTERY = Pattern.compile("Time on battery: (.+?) \\(");
    private static final Pattern SCREEN_ON = Pattern.compile("^ +Screen on: (.+?) \\(");
    private static final Pattern DEEP_IDLE = Pattern.compile("Device full idling: (.+?) \\(");
    private static final Pattern LIGHT_IDLE = Pattern.compile("Device light idling: (.+?) \\(");

    /** Parses {@code 1h 14m 1s 69ms} into milliseconds. Unparseable input yields 0. */
    public static long parseDuration(@Nullable String s) {
        if (s == null) return 0;
        long total = 0;
        Matcher m = DURATION.matcher(s);
        while (m.find()) {
            long v = Long.parseLong(m.group(1));
            switch (String.valueOf(m.group(2))) {
                case "d": total += v * 86_400_000L; break;
                case "h": total += v * 3_600_000L; break;
                case "m": total += v * 60_000L; break;
                case "s": total += v * 1_000L; break;
                default: total += v; break; // ms
            }
        }
        return total;
    }

    private static long parseSize(@NonNull String value, @NonNull String unit) {
        double v;
        try {
            v = Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
        switch (unit) {
            case "GB": return (long) (v * 1024 * 1024 * 1024);
            case "MB": return (long) (v * 1024 * 1024);
            case "KB": return (long) (v * 1024);
            default: return (long) v;
        }
    }

    /**
     * Converts a dump uid label to a numeric uid: {@code u0a954} → 10954,
     * {@code u10a5} → 1010005, a bare number to itself. Returns
     * {@link Integer#MIN_VALUE} when the label is not a uid.
     */
    static int parseUid(@NonNull String label) {
        try {
            if (label.charAt(0) == 'u') {
                int a = label.indexOf('a');
                if (a < 0) return Integer.MIN_VALUE;
                int user = Integer.parseInt(label.substring(1, a));
                int appId = Integer.parseInt(label.substring(a + 1));
                return user * 100_000 + 10_000 + appId;
            }
            return Integer.parseInt(label);
        } catch (RuntimeException e) {
            return Integer.MIN_VALUE;
        }
    }

    /**
     * Parses a full {@code dumpsys batterystats} dump.
     *
     * @return the snapshot, or {@code null} when the since-last-charge section
     * is missing (which means the dump was refused or truncated — treating that
     * as "everything is zero" would write a bogus bucket).
     */
    @Nullable
    public static BatterySnapshot parse(@Nullable String dump) {
        if (dump == null) return null;
        int start = dump.indexOf(SECTION);
        if (start < 0) return null;
        BatterySnapshot snap = new BatterySnapshot();
        BatterySnapshot.UidCounters current = null;

        String[] lines = dump.substring(start).split("\n");
        for (String line : lines) {
            Matcher uidHeader = UID_HEADER.matcher(line);
            if (uidHeader.matches()) {
                int uid = parseUid(String.valueOf(uidHeader.group(1)));
                if (uid == Integer.MIN_VALUE) {
                    current = null;
                } else {
                    current = new BatterySnapshot.UidCounters();
                    // Multiple blocks for one uid would otherwise clobber each other.
                    BatterySnapshot.UidCounters existing = snap.uids.get(uid);
                    if (existing != null) current = existing;
                    else snap.uids.put(uid, current);
                }
                continue;
            }
            if (current == null) {
                // Device-level header fields and the power-use block, which all
                // precede the per-uid blocks.
                Matcher m;
                if ((m = START_CLOCK.matcher(line)).find()) snap.startClock = m.group(1);
                else if ((m = TIME_ON_BATTERY.matcher(line)).find()) snap.timeOnBattery = parseDuration(m.group(1));
                else if ((m = SCREEN_ON.matcher(line)).find()) snap.screenOnMs = parseDuration(m.group(1));
                else if ((m = DEEP_IDLE.matcher(line)).find()) snap.deepIdleMs = parseDuration(m.group(1));
                else if ((m = LIGHT_IDLE.matcher(line)).find()) snap.lightIdleMs = parseDuration(m.group(1));
                else if ((m = POWER_HEADER.matcher(line)).find()) {
                    snap.profileCapacityMah = parseDouble(m.group(1));
                    snap.computedDrainMah = parseDouble(m.group(2));
                } else if ((m = POWER_UID.matcher(line)).find()) {
                    parsePowerUid(snap, m);
                }
                continue;
            }
            parseUidLine(line, current);
        }
        return snap;
    }

    private static double parseDouble(@Nullable String s) {
        try {
            return s == null ? 0 : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * {@code UID u0a954: 394 ( screen=0.357 cpu=0.0349 wifi=394 )} — the total
     * lands in {@code powerMah}, each component under a {@code mah.} key so the
     * detail view can show the breakdown without this parser having to know
     * which components a given Android release emits.
     */
    private static void parsePowerUid(@NonNull BatterySnapshot snap, @NonNull Matcher m) {
        int uid = parseUid(String.valueOf(m.group(1)));
        if (uid == Integer.MIN_VALUE) return;
        BatterySnapshot.UidCounters c = snap.uids.get(uid);
        if (c == null) {
            c = new BatterySnapshot.UidCounters();
            snap.uids.put(uid, c);
        }
        c.powerMah = parseDouble(m.group(2));
        String components = m.group(3);
        if (components == null) return;
        Matcher cm = POWER_COMPONENT.matcher(components);
        while (cm.find()) {
            c.addExtra("mah." + cm.group(1), parseDouble(cm.group(2)));
        }
    }

    private static void parseUidLine(@NonNull String line, @NonNull BatterySnapshot.UidCounters c) {
        Matcher m = NETWORK.matcher(line);
        if (m.find()) {
            long bytes = parseSize(String.valueOf(m.group(2)), String.valueOf(m.group(3)))
                    + parseSize(String.valueOf(m.group(4)), String.valueOf(m.group(5)));
            long packets = Long.parseLong(String.valueOf(m.group(6))) + Long.parseLong(String.valueOf(m.group(7)));
            if ("Wi-Fi".equals(m.group(1))) {
                c.wifiBytes += bytes;
                c.wifiPackets += packets;
            } else {
                c.mobileBytes += bytes;
                c.mobilePackets += packets;
            }
            return;
        }
        if ((m = RADIO_ACTIVE.matcher(line)).find()) {
            c.radioActiveMs += parseDuration(m.group(1));
            // The same line carries the wake count ("… (--%) 13x @ 0 mspp"),
            // which must be read here — the branches below are never reached.
            Matcher count = RADIO_ACTIVE_COUNT.matcher(line);
            if (count.find()) c.addExtra("radio_active_count", parseDouble(count.group(1)));
            return;
        }
        if ((m = TOTAL_WAKE.matcher(line)).find()) {
            c.wakelockMs += parseDuration(m.group(1));
            return;
        }
        if ((m = CPU_TIME.matcher(line)).find()) {
            c.cpuMs += parseDuration(m.group(1)) + parseDuration(m.group(2));
            return;
        }
        if ((m = FG_SERVICE.matcher(line)).find()) {
            c.fgServiceMs += parseDuration(m.group(1));
            return;
        }
        if ((m = WAKEUP_ALARM.matcher(line)).find()) {
            c.wakeupCount += Long.parseLong(String.valueOf(m.group(1)));
            return;
        }
        if ((m = SENSOR.matcher(line)).find()) {
            c.sensorMs += parseDuration(m.group(1));
            return;
        }
        if ((m = JOB.matcher(line)).find()) {
            c.addExtra("job_ms", parseDuration(m.group(1)));
            c.addExtra("job_count", parseDouble(m.group(2)));
            return;
        }
        if ((m = SYNC.matcher(line)).find()) {
            c.addExtra("sync_ms", parseDuration(m.group(1)));
            c.addExtra("sync_count", parseDouble(m.group(2)));
            return;
        }
        for (int i = 0; i < DURATION_PATTERNS.length; i++) {
            Matcher dm = DURATION_PATTERNS[i].matcher(line);
            if (dm.find()) {
                long ms = parseDuration(dm.group(1));
                if (ms > 0) c.addExtra(DURATION_METRICS[i][1], ms);
                return;
            }
        }
    }
}
