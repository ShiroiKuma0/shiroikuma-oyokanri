// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.appdata;

import android.annotation.UserIdInt;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.muntashirakon.AppManager.compat.DeviceIdleManagerCompat;
import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.compat.PermissionCompat;
import io.github.muntashirakon.AppManager.devicepolicy.DevicePolicyBridge;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.settings.StateExportReceiver;
import io.github.muntashirakon.AppManager.utils.FreezeUtils;

/**
 * Fork: everything the contract puts on 応用管理's side of the line, so that forty-two sister
 * apps do not each have to remember it.
 * <p>
 * Three guarantees live here:
 * <ul>
 * <li><b>Thaw, export, refreeze exactly what was thawed.</b> 270 packages are frozen on this
 *     phone, so this is the common path rather than an exception, and "Could not find provider"
 *     is overwhelmingly the freeze rather than a broken door.</li>
 * <li><b>Force-stop strictly after a successful import reply, never before it.</b> A running
 *     process writes its cached {@code SharedPreferences} back out at orderly shutdown and
 *     silently undoes the import that just happened. Ten sister repos now flush synchronously
 *     before replying {@code OK}, and that only works because the kill lands afterwards.</li>
 * <li><b>A battery-optimisation exemption around the call.</b> A foreground service started from
 *     a binder call is a background start and can be refused outright on API 31+ unless the app
 *     is exempt. That refusal is cured here, before the export, rather than discovered during
 *     one.</li>
 * </ul>
 * Every change is recorded <b>before</b> it is made, in its own preferences file, and reconciled
 * at next launch — a crash mid-export must not leave a batch of 白い熊's apps quietly running.
 */
public class AppDataTransfer {
    public static final String TAG = AppDataTransfer.class.getSimpleName();

    private static final String PREF_FILE = "shiroikuma_appdata_state";
    private static final int CHANGED_THAWED = 1;
    private static final int CHANGED_UNSUSPENDED = 1 << 1;
    private static final int CHANGED_EXEMPTED = 1 << 2;

    public static class Outcome {
        public final boolean ok;
        /**
         * Nothing was asked of the app and nothing should be written — distinct from a failure.
         * Currently means every category was unticked for this app, which is 白い熊 saying "no app
         * data for this one", not an error to abort the whole backup over.
         */
        public final boolean skipped;
        public final String message;
        @Nullable
        public final AppDataHeader header;

        Outcome(boolean ok, boolean skipped, @NonNull String message, @Nullable AppDataHeader header) {
            this.ok = ok;
            this.skipped = skipped;
            this.message = message;
            this.header = header;
        }

        Outcome(boolean ok, @NonNull String message, @Nullable AppDataHeader header) {
            this(ok, false, message, header);
        }
    }

    private final Context mContext;
    private final AppDataClient mClient;

    public AppDataTransfer(@NonNull Context context) {
        mContext = context.getApplicationContext();
        mClient = new AppDataClient(mContext);
    }

    /**
     * Ask an app to write its data into {@code destination}.
     */
    @WorkerThread
    @NonNull
    public Outcome export(@NonNull String packageName, @UserIdInt int userId,
                          @NonNull File destination, @Nullable AppDataClient.ProgressListener listener) {
        return export(packageName, userId, destination, null, listener);
    }

    /**
     * @param requested the categories to export this once, or {@code null} to use whatever is
     *                  stored for the app. An <b>empty</b> array means "none of it" and produces
     *                  the same skip an empty stored choice does — see
     *                  {@link io.github.muntashirakon.AppManager.appdata.AppDataCategoryPicker}.
     */
    @WorkerThread
    @NonNull
    public Outcome export(@NonNull String packageName, @UserIdInt int userId,
                          @NonNull File destination, @Nullable String[] requested,
                          @Nullable AppDataClient.ProgressListener listener) {
        return export(packageName, userId, destination, requested, listener, null);
    }

    /** @param cancellation asked, while waiting, whether the caller has given up — see AppDataClient. */
    @WorkerThread
    @NonNull
    public Outcome export(@NonNull String packageName, @UserIdInt int userId,
                          @NonNull File destination, @Nullable String[] requested,
                          @Nullable AppDataClient.ProgressListener listener,
                          @Nullable AppDataClient.Cancellation cancellation) {
        AppDataContract.Support support = AppDataContract.read(mContext, packageName);
        if (support == null || !support.isUsable()) {
            return new Outcome(false, "does not implement the data contract", null);
        }
        int changed = preflight(packageName, userId);
        try {
            AppDataHeader header = mClient.describe(packageName);
            if (header == null) {
                return new Outcome(false, "did not describe its data", null);
            }
            // Resolved BEFORE the descriptor is opened, so an "export nothing" choice leaves no
            // empty file behind.
            List<String> items = requested != null
                    ? resolveRequested(packageName, requested)
                    : resolveItems(packageName);
            if (items != null && items.isEmpty()) {
                return new Outcome(false, true, "every category unticked for this app", null);
            }
            ParcelFileDescriptor fd = open(destination, ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
            if (fd == null) {
                return new Outcome(false, "could not open a destination descriptor", null);
            }
            AppDataClient.Result result;
            try {
                result = mClient.transfer(packageName, AppDataContract.METHOD_EXPORT, fd, items,
                        listener, cancellation);
            } finally {
                // Our copy must be closed or the file stays open and cannot be checksummed or
                // encrypted. The callee dups before the call returns, so this is safe.
                closeQuietly(fd);
            }
            return new Outcome(result.ok, result.message, result.ok ? header : null);
        } finally {
            restore(packageName, userId, changed);
        }
    }

    /**
     * Hand an archive back to an app. The caller must have installed the APK already and must
     * <b>not</b> have launched it.
     */
    @WorkerThread
    @NonNull
    public Outcome importData(@NonNull String packageName, @UserIdInt int userId,
                              @NonNull File source, @NonNull AppDataHeader header,
                              @Nullable AppDataClient.ProgressListener listener) {
        return importData(packageName, userId, source, header, listener, null);
    }

    @WorkerThread
    @NonNull
    public Outcome importData(@NonNull String packageName, @UserIdInt int userId,
                              @NonNull File source, @NonNull AppDataHeader header,
                              @Nullable AppDataClient.ProgressListener listener,
                              @Nullable AppDataClient.Cancellation cancellation) {
        AppDataContract.Support support = AppDataContract.read(mContext, packageName);
        if (support == null || !support.isUsable()) {
            return new Outcome(false, "does not implement the data contract", null);
        }
        // Version skew has a direction: old data into a newer app migrates, newer data into an
        // older app does not. Refused here rather than halfway through a stream.
        if (!header.isRestorableInto(support)) {
            return new Outcome(false, "backup format " + header.format
                    + " is newer than this build can read (min " + support.minFormat + ")", null);
        }
        // Asked BEFORE streaming, never after: a freshly installed, never-launched app holds no
        // runtime permissions, and an import writing through a permission-guarded provider would
        // otherwise fail only once the whole archive had been sent.
        List<String> missing = grantRequired(packageName, userId, header.requiresPermissions);
        if (!missing.isEmpty()) {
            return new Outcome(false, "missing permissions it needs to import: " + missing, null);
        }
        int changed = preflight(packageName, userId);
        try {
            // A clean process for the import. The provider call restarts it.
            forceStop(packageName, userId);
            ParcelFileDescriptor fd = open(source, ParcelFileDescriptor.MODE_READ_ONLY);
            if (fd == null) {
                return new Outcome(false, "could not open the archive", null);
            }
            AppDataClient.Result result;
            try {
                result = mClient.transfer(packageName, AppDataContract.METHOD_IMPORT, fd, null,
                        listener, cancellation);
            } finally {
                closeQuietly(fd);
            }
            if (result.ok) {
                // STRICTLY after the reply. See the class comment.
                forceStop(packageName, userId);
            }
            return new Outcome(result.ok, result.message, header);
        } finally {
            restore(packageName, userId, changed);
        }
    }

    /**
     * Ask the app what it can export, thawing it if necessary and putting it back afterwards.
     * This is what the picker calls, and the only place a category listing is ever fetched for a
     * user-facing list — never eagerly across many apps.
     */
    @WorkerThread
    @Nullable
    public List<AppDataCategory> listCategories(@NonNull String packageName, @UserIdInt int userId) {
        AppDataContract.Support support = AppDataContract.read(mContext, packageName);
        if (support == null || !support.isUsable()) {
            return null;
        }
        if (isSelf(packageName)) {
            // Fork (白い熊, +134): our own categories, answered directly.
            //
            // The listing travels on the §1 BROADCAST channel, and this app's receiver for that
            // action is token-gated (AutomationAuth) — deliberately, so no other app can
            // enumerate us without being authorised. Broadcasting to ourselves to get past our
            // own gate would be theatre, and weakening the gate to allow it would be worse. The
            // renderer is shared with the receiver, so the two answers cannot drift.
            return AppDataCategory.parseReply(StateExportReceiver.listCategories(mContext));
        }
        int changed = preflight(packageName, userId);
        try {
            return new AppDataCategoryClient(mContext).list(packageName);
        } finally {
            restore(packageName, userId, changed);
        }
    }

    /**
     * The {@code items} to send for an export, or {@code null} to send none.
     * <p>
     * Null is the common and correct case: an app 白い熊 has never customised exports its own
     * recommended default set, and asking it for a category list would be a round trip spent to
     * arrive back at the same answer. Only a customised app pays for the listing — and by then the
     * app is already thawed and running for the export itself, so the extra round trip is cheap.
     */
    /**
     * What an app says it can export. For ourselves this is answered directly rather than by
     * broadcasting past our own token gate — see {@link #listCategories}.
     */
    @Nullable
    private List<AppDataCategory> offeredCategories(@NonNull String packageName) {
        if (isSelf(packageName)) {
            return AppDataCategory.parseReply(StateExportReceiver.listCategories(mContext));
        }
        return new AppDataCategoryClient(mContext).list(packageName);
    }

    /**
     * A run-scoped choice, checked against what the app offers right now. The ids came from a
     * listing taken moments ago, but an app that has been updated in between could refuse the
     * whole export with "unknown category", and losing a backup over that would be absurd — so
     * anything it no longer offers is dropped rather than sent.
     */
    @Nullable
    private List<String> resolveRequested(@NonNull String packageName, @NonNull String[] requested) {
        List<AppDataCategory> offered = offeredCategories(packageName);
        List<String> effective = new ArrayList<>(requested.length);
        if (offered == null) {
            // It would not say; send what was asked for and let the app judge.
            Collections.addAll(effective, requested);
            return effective;
        }
        for (String id : requested) {
            for (AppDataCategory category : offered) {
                if (category.id.equals(id)) {
                    effective.add(id);
                    break;
                }
            }
        }
        return effective;
    }

    @Nullable
    private List<String> resolveItems(@NonNull String packageName) {
        AppDataSelection.Stored stored = AppDataSelection.get(mContext, packageName);
        if (stored == null) {
            return null;
        }
        List<AppDataCategory> offered = offeredCategories(packageName);
        if (offered == null) {
            // It would not say. Sending the remembered ids blind risks
            // "ERROR:unknown category in items" and losing the whole export, so fall back to the
            // app's default set — a backup of slightly the wrong shape beats no backup.
            Log.w(TAG, "%s would not list categories; exporting its default set", packageName);
            return null;
        }
        List<String> effective = AppDataSelection.reconcile(stored, offered);
        // An empty list is meaningful here and is NOT passed to the client: an empty items extra
        // would be read as "the default set", which is the opposite of what was asked. The caller
        // turns it into a skip.
        return effective;
    }

    // ── Pre-flight and its undo ─────────────────────────────────────────────

    /**
     * Fork (白い熊, +134): we are the ones doing the work, so we are neither frozen nor
     * suspended — and {@link #restore} would otherwise be entitled to freeze this app the moment
     * its own backup finished.
     */
    private boolean isSelf(@NonNull String packageName) {
        return mContext.getPackageName().equals(packageName);
    }

    private int preflight(@NonNull String packageName, @UserIdInt int userId) {
        if (isSelf(packageName)) {
            return 0;
        }
        int changed = 0;
        // Record what we are about to lift BEFORE lifting it. setPackagesSuspended answers only
        // with what it could NOT change, so afterwards there is nothing to read back.
        boolean suspended = false;
        try {
            suspended = DevicePolicyBridge.canSuspend() && DevicePolicyBridge.isSuspended(packageName);
        } catch (Throwable ignore) {
        }
        boolean frozen = isFrozen(packageName);
        boolean optimised = false;
        try {
            optimised = DeviceIdleManagerCompat.isBatteryOptimizedApp(packageName)
                    && DeviceIdleManagerCompat.isExemptionRemovable(packageName);
        } catch (Throwable ignore) {
        }
        if (suspended) changed |= CHANGED_UNSUSPENDED;
        if (frozen) changed |= CHANGED_THAWED;
        if (optimised) changed |= CHANGED_EXEMPTED;
        if (changed != 0) {
            note(packageName, changed);
        }
        if (suspended) {
            try {
                DevicePolicyBridge.setSuspended(packageName, false);
            } catch (Throwable th) {
                Log.w(TAG, "could not unsuspend %s", th, packageName);
            }
        }
        if (frozen) {
            try {
                FreezeUtils.unfreeze(packageName, userId);
            } catch (Throwable th) {
                Log.w(TAG, "could not thaw %s", th, packageName);
            }
        }
        if (optimised) {
            try {
                DeviceIdleManagerCompat.disableBatteryOptimization(packageName);
            } catch (Throwable th) {
                Log.w(TAG, "could not exempt %s", th, packageName);
            }
        }
        return changed;
    }

    private void restore(@NonNull String packageName, @UserIdInt int userId, int changed) {
        if ((changed & CHANGED_EXEMPTED) != 0) {
            // Only ever remove an exemption WE added; one the app already held is not ours.
            try {
                DeviceIdleManagerCompat.enableBatteryOptimization(packageName);
            } catch (Throwable th) {
                Log.w(TAG, "could not re-optimise %s", th, packageName);
            }
        }
        if ((changed & CHANGED_THAWED) != 0) {
            try {
                FreezeUtils.freeze(packageName, userId);
            } catch (Throwable th) {
                // Includes the 必要 profile guard, which refuses to freeze a protected app.
                Log.w(TAG, "could not refreeze %s", th, packageName);
            }
        }
        if ((changed & CHANGED_UNSUSPENDED) != 0) {
            try {
                DevicePolicyBridge.setSuspended(packageName, true);
            } catch (Throwable th) {
                Log.w(TAG, "could not re-suspend %s", th, packageName);
            }
        }
        clear(packageName);
    }

    /**
     * Put back anything a crash left changed. Called once privileges are settled.
     */
    @WorkerThread
    public static void reconcile(@NonNull Context context) {
        AppDataTransfer transfer = new AppDataTransfer(context);
        SharedPreferences prefs = prefs(context);
        Map<String, ?> all = new HashMap<>(prefs.getAll());
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            Object value = entry.getValue();
            if (!(value instanceof Integer)) {
                continue;
            }
            Log.w(TAG, "reconciling %s left changed by an interrupted transfer", entry.getKey());
            // v1 of this contract is user 0 only, as a ContentResolver.call() goes to the
            // calling user and nothing here ever reached another one.
            transfer.restore(entry.getKey(), 0, (Integer) value);
        }
    }

    // ── Small helpers ───────────────────────────────────────────────────────

    @NonNull
    private List<String> grantRequired(@NonNull String packageName, @UserIdInt int userId,
                                       @NonNull List<String> permissions) {
        List<String> missing = new ArrayList<>();
        for (String permission : permissions) {
            try {
                if (PermissionCompat.checkPermission(permission, packageName, userId)
                        == PackageManager.PERMISSION_GRANTED) {
                    continue;
                }
                PermissionCompat.grantPermission(packageName, permission, userId);
                if (PermissionCompat.checkPermission(permission, packageName, userId)
                        != PackageManager.PERMISSION_GRANTED) {
                    missing.add(permission);
                }
            } catch (Throwable th) {
                Log.w(TAG, "could not grant %s to %s", th, permission, packageName);
                missing.add(permission);
            }
        }
        return missing;
    }

    private boolean isFrozen(@NonNull String packageName) {
        try {
            ApplicationInfo info = mContext.getPackageManager().getApplicationInfo(packageName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES | PackageManager.MATCH_DISABLED_COMPONENTS);
            return info != null && FreezeUtils.isFrozen(info);
        } catch (Throwable th) {
            return false;
        }
    }

    private void forceStop(@NonNull String packageName, @UserIdInt int userId) {
        try {
            PackageManagerCompat.forceStopPackage(packageName, userId);
        } catch (Throwable th) {
            Log.w(TAG, "could not force-stop %s", th, packageName);
        }
    }

    @Nullable
    private static ParcelFileDescriptor open(@NonNull File file, int mode) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return null;
            }
            return ParcelFileDescriptor.open(file, mode);
        } catch (Throwable th) {
            Log.w(TAG, "could not open %s", th, file);
            return null;
        }
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

    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    private void note(@NonNull String packageName, int changed) {
        prefs(mContext).edit().putInt(packageName, changed).commit();
    }

    private void clear(@NonNull String packageName) {
        prefs(mContext).edit().remove(packageName).commit();
    }
}
