// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.diag;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.fm.FmProvider;
import io.github.muntashirakon.io.Paths;

/**
 * Fork (白い熊, 2026-11-15): the app's diagnostic recordings, per the family's
 * Diagnostics convention — every recording behind its own switch, all OFF by
 * default; one file per kind per day in the app's own diag directory
 * ({@code Android/data/<package>/files/diag}); export, share, purge and
 * keep-N-days on the UI page; an ongoing notification with Mark while anything
 * records.
 *
 * <p><b>File rules.</b> Never a {@code .log} extension — EMUI's nightly clean-up
 * deletes those under {@code Android/data}. Every write opens, appends and
 * closes, so a file deleted under us is simply recreated by the next line
 * rather than written into an unlinked inode.
 */
public final class ForkDiag {
    private ForkDiag() {}

    /** Device-local (listed in {@code SettingsBackupManager.EXCLUDED_PREFS}): a new phone records nothing. */
    public static final String PREF_FILE = "shiroikuma_diag";
    private static final String KEY_ON_PREFIX = "on_";
    private static final String KEY_KEEP_DAYS = "keep_days";
    private static final String KEY_MARK = "mark";
    private static final String KEY_WATCH_BACKGROUND = "watch_background";

    /** Process-start failures seen by the process monitor: episodes, probes and fix steps. */
    public static final String KIND_FORKSTART = "forkstart";
    /** Kernel memory that cannot be reclaimed: its growth, and what the phone was doing meanwhile. */
    public static final String KIND_KERNELMEM = "kernelmem";
    /** Fork (+105): the kernel's room for filter programs, measured regularly — see {@code FilterPool}. */
    public static final String KIND_FILTERPOOL = "filterpool";
    public static final String[] KINDS = {KIND_FORKSTART, KIND_KERNELMEM, KIND_FILTERPOOL};

    public static final int MAX_KEEP_DAYS = 90;
    private static final long DAY_FILE_CAP_BYTES = 16L << 20;
    private static final String FILE_PREFIX = "oyokanri-";
    private static final String EXT = ".txt";
    private static final String CHANNEL_ID = "shiroikuma.diag";
    private static final int NOTIFICATION_ID = 0x44494147;

    private static final Object LOCK = new Object();

    @NonNull
    private static SharedPreferences sp(@NonNull Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    public static boolean isOn(@NonNull Context ctx, @NonNull String kind) {
        return sp(ctx).getBoolean(KEY_ON_PREFIX + kind, false);
    }

    public static boolean anyOn(@NonNull Context ctx) {
        for (String k : KINDS) if (isOn(ctx, k)) return true;
        return false;
    }

    public static void setOn(@NonNull Context ctx, @NonNull String kind, boolean on) {
        if (isOn(ctx, kind) == on) return;
        if (!on) write(ctx, kind, "diag " + kind + " off");
        sp(ctx).edit().putBoolean(KEY_ON_PREFIX + kind, on).apply();
        if (on) {
            // The file says what recorded it.
            sp(ctx).edit().putBoolean(KEY_ON_PREFIX + kind, true).commit();
            write(ctx, kind, "diag " + kind + " on, " + BuildConfig.VERSION_NAME);
        }
        updateNotification(ctx);
    }

    /**
     * The background process-start watch ({@code ForkWatchService}). Kept here because it is
     * device-local like the recordings, but it is a feature, not a recording: "Turn all off"
     * leaves it alone.
     */
    public static boolean isWatchOn(@NonNull Context ctx) {
        return sp(ctx).getBoolean(KEY_WATCH_BACKGROUND, false);
    }

    public static void setWatchOn(@NonNull Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(KEY_WATCH_BACKGROUND, on).commit();
    }

    /** Device-local bookkeeping of a recording (last boot seen, last full snapshot). */
    public static long getLong(@NonNull Context ctx, @NonNull String key, long def) {
        return sp(ctx).getLong("state_" + key, def);
    }

    public static void putLong(@NonNull Context ctx, @NonNull String key, long value) {
        sp(ctx).edit().putLong("state_" + key, value).apply();
    }

    /**
     * Every stored line of {@code kind}, oldest first, the newest {@code max} at most. For the
     * reports that read a recording back. Touches the disk — off the main thread.
     */
    @NonNull
    public static List<String> readLines(@NonNull Context ctx, @NonNull String kind, int max) {
        java.util.ArrayDeque<String> out = new java.util.ArrayDeque<>();
        synchronized (LOCK) {
            for (File f : list(ctx)) {
                String[] kd = parseName(f.getName());
                if (kd == null || !kind.equals(kd[0])) continue;
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                        new FileInputStream(f), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        out.addLast(line);
                        if (out.size() > max) out.pollFirst();
                    }
                } catch (Throwable ignore) {
                }
            }
        }
        return new ArrayList<>(out);
    }

    public static void turnAllOff(@NonNull Context ctx) {
        for (String k : KINDS) setOn(ctx, k, false);
    }

    public static int getKeepDays(@NonNull Context ctx) {
        return sp(ctx).getInt(KEY_KEEP_DAYS, 0);
    }

    public static void setKeepDays(@NonNull Context ctx, int days) {
        sp(ctx).edit().putInt(KEY_KEEP_DAYS, Math.max(0, Math.min(MAX_KEEP_DAYS, days))).apply();
    }

    @Nullable
    public static File dir(@NonNull Context ctx) {
        File base = ctx.getApplicationContext().getExternalFilesDir(null);
        if (base == null) return null;
        File d = new File(base, "diag");
        if (!d.isDirectory() && !d.mkdirs()) return null;
        return d;
    }

    /** Append one timestamped line to today's file of {@code kind}; a no-op while its switch is off. */
    public static void write(@NonNull Context ctx, @NonNull String kind, @NonNull String line) {
        if (!isOn(ctx, kind)) return;
        synchronized (LOCK) {
            try {
                File d = dir(ctx);
                if (d == null) return;
                Date now = new Date();
                File f = new File(d, FILE_PREFIX + kind + "_"
                        + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now) + EXT);
                if (f.length() > DAY_FILE_CAP_BYTES) return;  // a runaway writer must not fill the phone
                try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
                    w.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(now));
                    w.write('\t');
                    w.write(line.replace('\n', ' '));
                    w.write('\n');
                }
            } catch (Throwable ignore) {
            }
        }
    }

    /** Write a numbered mark into every recording that is on; returns its number. */
    public static int mark(@NonNull Context ctx) {
        int n = sp(ctx).getInt(KEY_MARK, 0) + 1;
        sp(ctx).edit().putInt(KEY_MARK, n).apply();
        for (String k : KINDS) write(ctx, k, "mark #" + n);
        return n;
    }

    // ---- Inventory, retention, purge ---------------------------------------

    public static final class Inventory {
        public int files;
        public long bytes;
        @Nullable
        public String firstDay;
        @Nullable
        public String lastDay;
        /** kind → "day (size), day (size) …", for the purge warning. */
        public final TreeMap<String, List<String>> perKind = new TreeMap<>();
    }

    @NonNull
    private static File[] list(@NonNull Context ctx) {
        File d = dir(ctx);
        File[] fs = d == null ? null : d.listFiles((dir, name) -> name.startsWith(FILE_PREFIX) && name.endsWith(EXT));
        if (fs == null) return new File[0];
        Arrays.sort(fs);
        return fs;
    }

    /** {kind, day} from {@code oyokanri-<kind>_<yyyy-MM-dd>.txt}, or null. */
    @Nullable
    private static String[] parseName(@NonNull String name) {
        String core = name.substring(FILE_PREFIX.length(), name.length() - EXT.length());
        int us = core.lastIndexOf('_');
        if (us <= 0 || core.length() - us - 1 != 10) return null;
        return new String[]{core.substring(0, us), core.substring(us + 1)};
    }

    /** Counts the stored files. Touches the disk — call off the main thread. */
    @NonNull
    public static Inventory inventory(@NonNull Context ctx) {
        Inventory inv = new Inventory();
        for (File f : list(ctx)) {
            String[] kd = parseName(f.getName());
            if (kd == null) continue;
            inv.files++;
            inv.bytes += f.length();
            if (inv.firstDay == null || kd[1].compareTo(inv.firstDay) < 0) inv.firstDay = kd[1];
            if (inv.lastDay == null || kd[1].compareTo(inv.lastDay) > 0) inv.lastDay = kd[1];
            List<String> days = inv.perKind.get(kd[0]);
            if (days == null) {
                days = new ArrayList<>();
                inv.perKind.put(kd[0], days);
            }
            days.add(kd[1] + " (" + android.text.format.Formatter.formatShortFileSize(ctx, f.length()) + ")");
        }
        return inv;
    }

    /** Delete files older than the keep-N-days setting (0 keeps everything). Off the main thread. */
    public static void cleanUp(@NonNull Context ctx) {
        int keep = getKeepDays(ctx);
        if (keep <= 0) return;
        String cutoff = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(new Date(System.currentTimeMillis() - keep * 86_400_000L));
        synchronized (LOCK) {
            for (File f : list(ctx)) {
                String[] kd = parseName(f.getName());
                if (kd != null && kd[1].compareTo(cutoff) < 0) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
    }

    /** Delete every stored file. Returns how many went. Off the main thread. */
    public static int purge(@NonNull Context ctx) {
        int n = 0;
        synchronized (LOCK) {
            for (File f : list(ctx)) if (f.delete()) n++;
        }
        return n;
    }

    // ---- Export and share ---------------------------------------------------

    /** Copy every file into a new dated subdirectory of the picked tree. Returns its name, or null. */
    @Nullable
    public static String exportTo(@NonNull Context ctx, @NonNull Uri tree) {
        try {
            DocumentFile root = DocumentFile.fromTreeUri(ctx, tree);
            if (root == null) return null;
            String name = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date()) + "_oyokanri-diag";
            DocumentFile out = root.createDirectory(name);
            if (out == null) return null;
            byte[] buf = new byte[64 * 1024];
            for (File f : list(ctx)) {
                DocumentFile dst = out.createFile("text/plain", f.getName());
                if (dst == null) return null;
                try (InputStream in = new FileInputStream(f);
                     OutputStream os = ctx.getContentResolver().openOutputStream(dst.getUri())) {
                    if (os == null) return null;
                    int r;
                    while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
                }
            }
            return name;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Where the export picker first opens: {@code /sdcard/tmp}. */
    @Nullable
    public static Uri exportStartUri() {
        try {
            return android.provider.DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents", "primary:tmp");
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    public static Intent shareIntent(@NonNull Context ctx) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (File f : list(ctx)) uris.add(FmProvider.getContentUri(Paths.get(f)));
        if (uris.isEmpty()) return null;
        Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("text/plain")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return Intent.createChooser(i, null);
    }

    // ---- Recording notification ----------------------------------------------

    /** Show the ongoing notification while any recording is on; remove it otherwise. */
    public static void updateNotification(@NonNull Context context) {
        Context ctx = context.getApplicationContext();
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (!anyOn(ctx)) {
                nm.cancel(NOTIFICATION_ID);
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                        ctx.getString(R.string.diag_group), NotificationManager.IMPORTANCE_LOW));
            }
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_default_notification)
                    .setContentTitle(ctx.getString(R.string.diag_notification_title))
                    .setContentText(ctx.getString(R.string.diag_notification_text))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .addAction(0, ctx.getString(R.string.diag_mark), action(ctx, ForkDiagReceiver.ACTION_MARK, 1))
                    .addAction(0, ctx.getString(R.string.diag_all_off), action(ctx, ForkDiagReceiver.ACTION_ALL_OFF, 2));
            nm.notify(NOTIFICATION_ID, b.build());
        } catch (Throwable ignore) {
            // No notification permission — the recording itself is unaffected.
        }
    }

    @NonNull
    private static PendingIntent action(@NonNull Context ctx, @NonNull String action, int requestCode) {
        Intent i = new Intent(ctx, ForkDiagReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(ctx, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
