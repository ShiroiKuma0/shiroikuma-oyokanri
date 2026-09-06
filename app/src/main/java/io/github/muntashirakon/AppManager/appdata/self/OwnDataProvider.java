// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.appdata.self;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.appdata.AppDataContract;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.settings.SettingsBackupManager;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;

/**
 * Fork (白い熊, +134): 応用管理's own door onto the sister-app data contract.
 *
 * <p>The app was the only member of the family that could back up everyone else's data and not
 * its own. It <em>did</em> implement the other family contract — the 保存復元 broadcast that
 * 自由作業盤 fires to collect a settings zip — but that is a different mechanism landing in a
 * different place on a different schedule, so its settings, profiles, notes and fonts were never
 * inside the backup you had just taken, and it never appeared under the sister-app filter.
 *
 * <p>What travels through this door is exactly what that broadcast already produces:
 * {@link SettingsBackupManager#writeExport}, the headless core, with the same
 * {@link SettingsBackupManager.Category} enum as the category list. One archive shape, one set
 * of wire ids, two ways in — never a second implementation.
 *
 * <p><b>The call returns at once and the work runs behind it.</b> That is the contract's whole
 * shape: the caller blocks on a broadcast, not on the binder, so a provider that did the export
 * inside {@code call()} would deadlock the very client that is us. Progress and completion go
 * back as broadcasts in the family's EMUI-proven form — explicit package, stopped packages
 * included, and never only the ordered-broadcast result.
 */
public class OwnDataProvider extends ContentProvider {
    public static final String TAG = "OwnDataProvider";

    /**
     * The format this app writes. Bound to the archive layout
     * ({@code shared_prefs/} + {@code profiles/} + {@code fonts/}), not to the contract version —
     * see {@link AppDataContract.Support}. Raise it only when that layout changes, and raise the
     * manifest's {@code shiroikuma.automation.format} with it.
     */
    public static final int FORMAT = 1;
    /** The oldest layout this build can read back. */
    public static final int MIN_FORMAT_READABLE = 1;

    /** Jobs in flight, so a cancel can reach one. */
    private static final Map<String, AtomicBoolean> sCancelled = new ConcurrentHashMap<>();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        Context context = getContext();
        if (context == null) {
            return reply(AppDataContract.ERROR_PREFIX + "no context");
        }
        // Identity first, then the gate, then the dispatch — in that order, so an unknown
        // caller gets the same refusal for a bogus method as for a real one and cannot learn
        // what this door answers by asking.
        String refusedCaller = AutomationCallers.refuse(context, android.os.Binder.getCallingUid(),
                getCallingPackage());
        if (refusedCaller != null) {
            return reply(AppDataContract.ERROR_PREFIX + refusedCaller);
        }
        Context appContext = context.getApplicationContext();
        if (android.os.Binder.getCallingUid() != android.os.Process.myUid()) {
            // Another app's call also passes the automation gate: "turn the switch off to close
            // this app off entirely" has to mean the door too, or the switch is a half-truth.
            // Our own calls skip it — backing ourselves up is not automation by anybody else.
            String refused = io.github.muntashirakon.AppManager.settings.AutomationAuth.refuse(
                    appContext, extras == null ? null : extras.getString("token"));
            if (refused != null) {
                // The gate speaks the receiver's grammar ("ERROR:..."), which is the contract's
                // too — pass its line through rather than inventing a second wording.
                return reply(refused);
            }
        }
        switch (method) {
            case AppDataContract.METHOD_DESCRIBE:
                return reply(AppDataContract.OK_PREFIX + describe(appContext));
            case AppDataContract.METHOD_EXPORT:
                return start(appContext, extras, true);
            case AppDataContract.METHOD_IMPORT:
                return start(appContext, extras, false);
            case AppDataContract.METHOD_CANCEL: {
                String jobId = extras == null ? null : extras.getString(AppDataContract.EXTRA_JOB_ID);
                AtomicBoolean flag = jobId == null ? null : sCancelled.get(jobId);
                if (flag != null) {
                    flag.set(true);
                }
                // Deliberately no reply: the running job answers its OWN request when it unwinds,
                // and a second reply would be one nobody asked for.
                return reply(AppDataContract.OK_PREFIX + "cancelling");
            }
            default:
                return reply(AppDataContract.ERROR_PREFIX + "unknown method " + method);
        }
    }

    /*
     * Who may come through this door now lives in AutomationCallers (白い熊, +153): our own uid,
     * plus an allowlist of exact package names each cross-checked against the uid the kernel
     * reports and a pinned signing certificate. It was self-only until 白い熊 authorised
     * 自由作業盤, which could otherwise not back 応用管理 up through the door at all.
     *
     * The provider stays <b>exported</b> either way: a non-exported one answers a stranger with a
     * {@code SecurityException}, which {@code AppDataClient} reports as "frozen, or no door" — the
     * wrong diagnosis for a deliberate policy.
     */

    /** The header the caller stores beside the archive and judges compatibility from. */
    @NonNull
    private static String describe(@NonNull Context context) {
        JSONObject o = new JSONObject();
        try {
            o.put("app_id", BuildConfig.APPLICATION_ID);
            o.put("version_code", BuildConfig.VERSION_CODE);
            o.put("version_name", BuildConfig.VERSION_NAME);
            o.put("format", FORMAT);
            o.put("min_format_readable", MIN_FORMAT_READABLE);
            // We are already running whenever this is asked, and nothing here needs a first run.
            o.put("requires_launch_first", false);
            o.put("requires_permissions", new JSONArray());
            // Rendered verbatim by the caller, so the app describes itself in its own words.
            List<String> contains = new ArrayList<>();
            for (SettingsBackupManager.Category category : SettingsBackupManager.Category.values()) {
                contains.add(context.getString(category.labelRes));
            }
            o.put("contains", new JSONArray(contains));
        } catch (Throwable ignore) {
        }
        return o.toString();
    }

    /**
     * Accept a transfer: mint the id, answer immediately, do the work on a background thread.
     *
     * <p><b>LANDMINE — the descriptor must be dup'ed before this returns.</b> The caller closes
     * its copy the moment {@code call()} comes back, so a worker holding the original would find
     * it shut. {@link ParcelFileDescriptor#dup()} is what makes the fd ours.
     */
    @Nullable
    private Bundle start(@NonNull Context context, @Nullable Bundle extras, boolean export) {
        if (extras == null) {
            return reply(AppDataContract.ERROR_PREFIX + "no extras");
        }
        ParcelFileDescriptor given = extras.getParcelable(AppDataContract.EXTRA_FD);
        if (given == null) {
            return reply(AppDataContract.ERROR_PREFIX + "no descriptor");
        }
        final ParcelFileDescriptor fd;
        try {
            fd = given.dup();
        } catch (Throwable th) {
            return reply(AppDataContract.ERROR_PREFIX + "could not take the descriptor");
        }
        String replyAction = extras.getString(AppDataContract.EXTRA_REPLY_ACTION);
        String replyPackage = extras.getString(AppDataContract.EXTRA_REPLY_PACKAGE);
        String progressAction = extras.getString(AppDataContract.EXTRA_PROGRESS_ACTION);
        String items = extras.getString(AppDataContract.EXTRA_ITEMS);
        final String jobId = UUID.randomUUID().toString();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        sCancelled.put(jobId, cancelled);

        ThreadUtils.postOnBackgroundThread(() -> {
            String result;
            try {
                result = export
                        ? runExport(context, fd, items, cancelled, progressAction, replyPackage, jobId)
                        : runImport(context, fd, items);
            } catch (Throwable th) {
                result = AppDataContract.ERROR_PREFIX + oneLine(String.valueOf(th.getMessage()));
            } finally {
                sCancelled.remove(jobId);
                closeQuietly(fd);
            }
            send(context, replyAction, replyPackage, jobId, result, null);
        });
        // The caller adopts THIS id and correlates the completion broadcast on it — see the
        // job-id landmine in AppDataClient.
        return reply(AppDataContract.OK_PREFIX + jobId);
    }

    @NonNull
    private static String runExport(@NonNull Context context, @NonNull ParcelFileDescriptor fd,
                                    @Nullable String items, @NonNull AtomicBoolean cancelled,
                                    @Nullable String progressAction, @Nullable String replyPackage,
                                    @NonNull String jobId) throws Exception {
        Set<SettingsBackupManager.Category> categories;
        try {
            categories = parseItems(items);
        } catch (IllegalArgumentException e) {
            return AppDataContract.ERROR_PREFIX + "unknown category in items: " + oneLine(String.valueOf(items));
        }
        int written;
        try (OutputStream os = new FileOutputStream(fd.getFileDescriptor())) {
            written = SettingsBackupManager.writeExport(context, categories, os,
                    (current, total, label) -> send(context, progressAction, replyPackage, jobId,
                            label, new long[]{current, total}),
                    cancelled::get);
        }
        if (cancelled.get()) {
            return AppDataContract.ERROR_PREFIX + "cancelled";
        }
        return AppDataContract.OK_PREFIX + written + " files, " + categories.size() + " categories";
    }

    /**
     * Staged, not applied — see {@link PendingStateImport}. The reply says so rather than
     * claiming a restore that has not happened yet.
     */
    @NonNull
    private static String runImport(@NonNull Context context, @NonNull ParcelFileDescriptor fd,
                                    @Nullable String items) throws Exception {
        long bytes;
        try (InputStream is = new FileInputStream(fd.getFileDescriptor())) {
            bytes = PendingStateImport.stage(context, is, items);
        }
        return AppDataContract.OK_PREFIX + bytes + " bytes staged; applied on the next start";
    }

    @NonNull
    private static Set<SettingsBackupManager.Category> parseItems(@Nullable String items) {
        if (items == null || items.trim().isEmpty()) {
            return SettingsBackupManager.allCategories();
        }
        Set<SettingsBackupManager.Category> out = EnumSet.noneOf(SettingsBackupManager.Category.class);
        for (String raw : items.split(",")) {
            String id = raw.trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) {
                continue;
            }
            SettingsBackupManager.Category category = SettingsBackupManager.Category.byId(id);
            if (category == null) {
                throw new IllegalArgumentException(id);
            }
            out.add(category);
        }
        return out.isEmpty() ? SettingsBackupManager.allCategories() : out;
    }

    /**
     * One broadcast, in the shape the family proved on EMUI: explicit package, stopped packages
     * included. Progress carries real counts and never a percentage.
     */
    private static void send(@NonNull Context context, @Nullable String action,
                             @Nullable String replyPackage, @NonNull String jobId,
                             @Nullable String result, @Nullable long[] progress) {
        if (action == null || replyPackage == null) {
            return;
        }
        try {
            Intent intent = new Intent(action);
            intent.setPackage(replyPackage);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtra(AppDataContract.EXTRA_JOB_ID, jobId);
            intent.putExtra(AppDataContract.EXTRA_REPLY_ID, jobId);
            intent.putExtra(AppDataContract.EXTRA_RESULT, result);
            if (progress != null) {
                intent.putExtra(AppDataContract.EXTRA_CURRENT, progress[0]);
                intent.putExtra(AppDataContract.EXTRA_TOTAL, progress[1]);
                intent.putExtra(AppDataContract.EXTRA_UNIT, "区分");
            }
            context.sendBroadcast(intent);
        } catch (Throwable th) {
            Log.w(TAG, "could not answer %s", th, replyPackage);
        }
    }

    @NonNull
    private static Bundle reply(@NonNull String result) {
        Bundle out = new Bundle();
        out.putString(AppDataContract.EXTRA_RESULT, result);
        return out;
    }

    @NonNull
    private static String oneLine(@NonNull String s) {
        return s.replace('\n', ' ').replace('\r', ' ');
    }

    private static void closeQuietly(@Nullable ParcelFileDescriptor fd) {
        if (fd == null) {
            return;
        }
        try {
            fd.close();
        } catch (Throwable ignore) {
        }
    }

    // ── Not a table. The contract is call()-only. ───────────────────────────

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        return 0;
    }
}
