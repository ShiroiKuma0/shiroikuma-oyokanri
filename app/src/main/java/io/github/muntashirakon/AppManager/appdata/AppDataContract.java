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
    private static volatile Set<String> sSupported;
    private static volatile long sSupportedAt;

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
