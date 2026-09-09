// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.appdata;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

import android.os.SystemClock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.db.utils.AppDb;
import io.github.muntashirakon.AppManager.db.entity.Backup;

/**
 * Fork: capability discovery and wire vocabulary for the sister-app data contract (v2).
 * <p>
 * This is the caller half. A sister app that implements the contract exposes a
 * {@code ContentProvider} at {@code <pkg>.automation} and declares three manifest
 * {@code <meta-data>} entries; an app that declares nothing is simply never offered for data
 * backup, which is the contract's opt-out.
 * <p>
 * Nothing here wakes the target app. Discovery is a manifest read, deliberately, because
 * 白い熊 freezes aggressively (270 packages on the phone at the time of writing) and a frozen
 * app cannot be asked anything — yet it must still be listed as backup-capable.
 */
public final class AppDataContract {
    /** Contract revision this client speaks. */
    public static final int CONTRACT_VERSION = 2;

    // ── Manifest <meta-data> keys ────────────────────────────────────────────
    public static final String META_CONTRACT = "shiroikuma.automation.contract";
    public static final String META_FORMAT = "shiroikuma.automation.format";
    public static final String META_MIN_FORMAT = "shiroikuma.automation.min_format";

    /** Provider authority suffix: the authority is {@code <packageName> + AUTHORITY_SUFFIX}. */
    public static final String AUTHORITY_SUFFIX = ".automation";

    /**
     * The category listing lives on the §1 BROADCAST channel, not on the data door — the provider
     * has only describe/export/import/cancel. So a picker costs one broadcast round trip per app,
     * which is why it is fetched on demand and never eagerly across a list.
     */
    public static final String ACTION_LIST_CATEGORIES_SUFFIX = ".action.LIST_CATEGORIES";

    /** Comma-separated category ids. ABSENT MEANS THE APP'S DEFAULT SET, never "everything". */
    public static final String EXTRA_ITEMS = "items";

    // ── Methods ─────────────────────────────────────────────────────────────
    public static final String METHOD_DESCRIBE = "describe";
    public static final String METHOD_EXPORT = "export";
    public static final String METHOD_IMPORT = "import";
    public static final String METHOD_CANCEL = "cancel";

    // ── Extras in ───────────────────────────────────────────────────────────
    public static final String EXTRA_FD = "fd";
    public static final String EXTRA_JOB_ID = "job_id";
    public static final String EXTRA_REPLY_ACTION = "reply_action";
    public static final String EXTRA_REPLY_PACKAGE = "reply_package";
    public static final String EXTRA_PROGRESS_ACTION = "progress_action";

    // ── Extras out / reply ──────────────────────────────────────────────────
    /**
     * The progress label. <b>This, not {@link #EXTRA_RESULT}, is what a sister app sends</b> — see
     * {@code AppDataClient#progressLabel}. {@code EXTRA_RESULT} carries the terminal reply.
     */
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_RESULT = "result";
    public static final String EXTRA_REPLY_ID = "reply_id";
    public static final String EXTRA_CURRENT = "current";
    public static final String EXTRA_TOTAL = "total";
    public static final String EXTRA_UNIT = "unit";

    /** Our own reply/progress actions. Registered dynamically; never in the manifest. */
    public static final String ACTION_REPLY = BuildConfig.APPLICATION_ID + ".action.APP_DATA_REPLY";
    public static final String ACTION_PROGRESS = BuildConfig.APPLICATION_ID + ".action.APP_DATA_PROGRESS";

    /** Reply grammar, shared with the §1 broadcast contract so there is one vocabulary. */
    public static final String OK_PREFIX = "OK:";
    public static final String ERROR_PREFIX = "ERROR:";

    /**
     * An app's declared support, read from its manifest without starting it.
     */
    public static class Support {
        public final int contract;
        public final int format;
        public final int minFormat;

        Support(int contract, int format, int minFormat) {
            this.contract = contract;
            this.format = format;
            this.minFormat = minFormat;
        }

        /**
         * Whether this build can talk to that app at all. A future app declaring contract 3 is
         * not assumed compatible — it says what it speaks and we believe it.
         */
        public boolean isUsable() {
            return contract == CONTRACT_VERSION;
        }
    }

    private AppDataContract() {
    }

    @NonNull
    public static String authorityFor(@NonNull String packageName) {
        return packageName + AUTHORITY_SUFFIX;
    }

    /**
     * Read an app's declared support. Returns {@code null} when the app declares nothing, which
     * is the opt-out and never an error.
     * <p>
     * LANDMINE — the three values must be read with {@link Bundle#getInt(String, int)} and never
     * {@code getString}. {@code aapt2} stores a bare numeric {@code android:value} as an
     * <b>int</b>, so {@code getString} answers null for every correctly-built app and the whole
     * discovery mechanism silently empties — which looks exactly like "no app implements this
     * yet" rather than like a bug. Confirmed in five sister APKs.
     */
    @Nullable
    public static Support read(@NonNull Context context, @NonNull String packageName) {
        ApplicationInfo info;
        try {
            // MATCH_UNINSTALLED_PACKAGES | MATCH_DISABLED_COMPONENTS: without these a frozen app
            // vanishes from the query entirely, and frozen apps are exactly what must stay listed.
            info = context.getPackageManager().getApplicationInfo(packageName,
                    PackageManager.GET_META_DATA
                            | PackageManager.MATCH_UNINSTALLED_PACKAGES
                            | PackageManager.MATCH_DISABLED_COMPONENTS);
        } catch (Throwable th) {
            return null;
        }
        return fromMetaData(info == null ? null : info.metaData);
    }

    /**
     * The same read, for a caller that already holds the {@code metaData} bundle.
     * <p>
     * Fork (+118): 仲間 lists every app that implements the contract, and querying each package
     * again one at a time would be hundreds of package-manager calls to re-read a bundle it
     * already has. Sharing this method rather than copying four lines is what keeps the
     * {@code getInt} landmine below in one place.
     */
    @Nullable
    public static Support fromMetaData(@Nullable Bundle metaData) {
        if (metaData == null) {
            return null;
        }
        int contract = metaData.getInt(META_CONTRACT, -1);
        if (contract < 0) {
            // Declares nothing: not offered. This is the opt-out, not a failure.
            return null;
        }
        // format is PER-APP and not a contract constant — sister apps ship 1, 2 and 3, each bound
        // to their own export version so the door cannot drift from the file. Never assume 1.
        int format = metaData.getInt(META_FORMAT, -1);
        int minFormat = metaData.getInt(META_MIN_FORMAT, -1);
        if (format < 0 || minFormat < 0) {
            return null;
        }
        return new Support(contract, format, minFormat);
    }

    /** Convenience: whether this app can be offered for contract-based data backup. */
    public static boolean isSupported(@NonNull Context context, @NonNull String packageName) {
        Support support = read(context, packageName);
        return support != null && support.isUsable();
    }

    // ── Every supported app at once (白い熊, +124) ────────────────────────────
    //
    // The main-list filter asks this of several hundred apps in one pass, and one package-manager
    // query per app turns a list refresh into a visible stall. One query answers for all of them.
    // Cached only for seconds: installing or updating an app changes the answer, and a filter that
    // needed a restart to notice a new sister app would be worse than a slow one.
    private static final long SUPPORTED_CACHE_MS = 5_000L;
    @Nullable
    private static volatile Set<String> sEverSister;
    private static volatile long sEverSisterAt;
    @Nullable
    private static volatile Set<String> sSupported;
    private static volatile long sSupportedAt;

    /**
     * Fork (白い熊, +175): every app that <b>is</b> a sister app, on either of the two witnesses.
     *
     * <p>This is a deliberately wider question than {@link #supportedPackages}, which asks "can I
     * talk to it right now" and therefore requires a readable manifest AND a contract version this
     * build speaks. That is the right test before a transfer and the wrong one for a list: it
     * silently drops the two cases you most want to see.
     *
     * <ul>
     * <li><b>Known only from a backup.</b> A backup that carried app-supplied data is proof the
     * app is a sister app, and on the phone this whole contract exists for — a freshly wiped one —
     * it is the ONLY proof available, because the manifest of an app that is not installed yet
     * cannot be read.</li>
     * <li><b>Declared but unusable.</b> An app whose contract version we do not speak is still a
     * sister app; hiding it makes a version mismatch look like the app never having a door.</li>
     * </ul>
     *
     * <p>This is the same rule {@code SisterAppsLens.prepare} applies (manifest OR a backup with
     * app data), which is what lets the two agree — see the note there.
     */
    @NonNull
    public static Set<String> everSisterPackages(@NonNull Context context) {
        Set<String> cached = sEverSister;
        if (cached != null && SystemClock.elapsedRealtime() - sEverSisterAt < SUPPORTED_CACHE_MS) {
            return cached;
        }
        Set<String> sister = new HashSet<>();
        // Every package whose manifest we can read at all. For these the manifest is the ANSWER,
        // present or absent -- see the backup pass below for why that matters.
        Set<String> manifestReadable = new HashSet<>();
        try {
            List<ApplicationInfo> apps = context.getPackageManager().getInstalledApplications(
                    PackageManager.GET_META_DATA
                            | PackageManager.MATCH_UNINSTALLED_PACKAGES
                            | PackageManager.MATCH_DISABLED_COMPONENTS);
            for (ApplicationInfo app : apps) {
                manifestReadable.add(app.packageName);
                // Declared is enough here; usability is a separate question -- see above.
                if (fromMetaData(app.metaData) != null) {
                    sister.add(app.packageName);
                }
            }
        } catch (Throwable ignore) {
        }
        try {
            // The backup witness, and it applies ONLY where there is no manifest to read.
            //
            // LANDMINE (白い熊, +022 -- it shipped wrong in +020): Backup.flags records what the
            // backup was ASKED for, not what it got. App-supplied data is ticked by default, and
            // BackupOp.backupAppData returns early for an app that declares no contract WITHOUT
            // clearing the flag -- so essentially every backup on the phone claims backupAppData()
            // and trusting it made the Sister apps filter match everything with a backup.
            //
            // Two guards. First, only consider a package whose manifest could not be read at all:
            // for anything installed the manifest is authoritative in both directions, and this is
            // the case the backup witness exists for anyway (a wiped phone, app not installed
            // yet). Second, confirm the archive really holds the file -- getAppDataFile throws
            // when it does not, which is how RestoreOp tells "no app data" from an error. That is
            // a filesystem check, kept affordable by the manifest gate above reducing it to the
            // handful of backed-up-but-absent packages.
            for (Backup backup : new AppDb().getAllBackups()) {
                if (backup == null || backup.packageName == null
                        || sister.contains(backup.packageName)
                        || manifestReadable.contains(backup.packageName)
                        || !backup.getFlags().backupAppData()) {
                    continue;
                }
                try {
                    backup.getItem().getAppDataFile();
                    sister.add(backup.packageName);
                } catch (Throwable ignore) {
                    // No app-supplied data in this archive after all.
                }
            }
        } catch (Throwable ignore) {
        }
        sEverSister = sister;
        sEverSisterAt = SystemClock.elapsedRealtime();
        return sister;
    }

    /**
     * Every installed package declaring a contract this build can speak.
     * <p>
     * Frozen and disabled apps are included, for the same reason {@link #read} includes them:
     * they are exactly the ones that must stay listed.
     */
    @NonNull
    public static Set<String> supportedPackages(@NonNull Context context) {
        Set<String> cached = sSupported;
        if (cached != null && SystemClock.elapsedRealtime() - sSupportedAt < SUPPORTED_CACHE_MS) {
            return cached;
        }
        Set<String> supported = new HashSet<>();
        try {
            List<ApplicationInfo> apps = context.getPackageManager().getInstalledApplications(
                    PackageManager.GET_META_DATA
                            | PackageManager.MATCH_UNINSTALLED_PACKAGES
                            | PackageManager.MATCH_DISABLED_COMPONENTS);
            for (ApplicationInfo app : apps) {
                Support support = fromMetaData(app.metaData);
                if (support != null && support.isUsable()) {
                    supported.add(app.packageName);
                }
            }
        } catch (Throwable ignore) {
            // An unanswerable query is not an empty answer, but there is nothing better to
            // return; the cache below keeps it from being retried on every one of 700 rows.
        }
        sSupported = supported;
        sSupportedAt = SystemClock.elapsedRealtime();
        return supported;
    }
}
