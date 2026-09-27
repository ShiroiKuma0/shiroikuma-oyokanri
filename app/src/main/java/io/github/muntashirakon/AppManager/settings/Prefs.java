// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.settings;

import static io.github.muntashirakon.AppManager.backup.BackupUtils.TAR_TYPES;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;
import androidx.core.util.Pair;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Objects;

import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.apk.installer.InstallerOptions;
import io.github.muntashirakon.AppManager.apk.signing.SigSchemes;
import io.github.muntashirakon.AppManager.apk.signing.Signer;
import io.github.muntashirakon.AppManager.backup.BackupFlags;
import io.github.muntashirakon.AppManager.backup.CryptoUtils;
import io.github.muntashirakon.AppManager.compat.ManifestCompat;
import io.github.muntashirakon.AppManager.details.AppDetailsFragment;
import io.github.muntashirakon.AppManager.devicepolicy.DevicePolicyBridge;
import io.github.muntashirakon.AppManager.fm.FmActivity;
import io.github.muntashirakon.AppManager.fm.FmListOptions;
import io.github.muntashirakon.AppManager.logcat.helper.LogcatHelper;
import io.github.muntashirakon.AppManager.main.MainListOptions;
import io.github.muntashirakon.AppManager.rules.struct.ComponentRule;
import io.github.muntashirakon.AppManager.self.SelfPermissions;
import io.github.muntashirakon.AppManager.utils.AppPref;
import io.github.muntashirakon.AppManager.utils.ArrayUtils;
import io.github.muntashirakon.AppManager.utils.ContextUtils;
import io.github.muntashirakon.AppManager.utils.FileUtils;
import io.github.muntashirakon.AppManager.utils.FreezeUtils;
import io.github.muntashirakon.AppManager.utils.TarUtils;
import io.github.muntashirakon.io.Path;
import io.github.muntashirakon.io.Paths;

// Why this class?
//
// This class is just an abstract over the AppPref to make life a bit easier. In the future, however, it might be
// possible to deliver the changes to the settings using lifecycle where required. For example, in the log viewer page,
// changes to the settings are not immediately reflected unless the settings page is opened from the page itself.
public final class Prefs {
    public static final class AppDetailsPage {
        public static boolean displayDefaultAppOps() {
            return AppPref.getBoolean(AppPref.PrefKey.PREF_APP_OP_SHOW_DEFAULT_BOOL);
        }

        public static void setDisplayDefaultAppOps(boolean display) {
            AppPref.set(AppPref.PrefKey.PREF_APP_OP_SHOW_DEFAULT_BOOL, display);
        }

        @AppDetailsFragment.SortOrder
        public static int getAppOpsSortOrder() {
            return AppPref.getInt(AppPref.PrefKey.PREF_APP_OP_SORT_ORDER_INT);
        }

        public static void setAppOpsSortOrder(@AppDetailsFragment.SortOrder int sortOrder) {
            AppPref.set(AppPref.PrefKey.PREF_APP_OP_SORT_ORDER_INT, sortOrder);
        }

        @AppDetailsFragment.SortOrder
        public static int getComponentsSortOrder() {
            return AppPref.getInt(AppPref.PrefKey.PREF_COMPONENTS_SORT_ORDER_INT);
        }

        public static void setComponentsSortOrder(@AppDetailsFragment.SortOrder int sortOrder) {
            AppPref.set(AppPref.PrefKey.PREF_COMPONENTS_SORT_ORDER_INT, sortOrder);
        }

        @AppDetailsFragment.SortOrder
        public static int getPermissionsSortOrder() {
            return AppPref.getInt(AppPref.PrefKey.PREF_PERMISSIONS_SORT_ORDER_INT);
        }

        public static void setPermissionsSortOrder(@AppDetailsFragment.SortOrder int sortOrder) {
            AppPref.set(AppPref.PrefKey.PREF_PERMISSIONS_SORT_ORDER_INT, sortOrder);
        }

        @AppDetailsFragment.SortOrder
        public static int getOverlaysSortOrder() {
            return AppPref.getInt(AppPref.PrefKey.PREF_OVERLAYS_SORT_ORDER_INT);
        }

        public static void setOverlaysSortOrder(@AppDetailsFragment.SortOrder int sortOrder) {
            AppPref.set(AppPref.PrefKey.PREF_OVERLAYS_SORT_ORDER_INT, sortOrder);
        }
    }

    public static final class Appearance {
        @NonNull
        public static String getLanguage() {
            return AppPref.getString(AppPref.PrefKey.PREF_CUSTOM_LOCALE_STR);
        }

        @NonNull
        public static String getLanguage(@NonNull Context context) {
            // Required when application isn't initialised properly
            AppPref appPref = AppPref.getNewInstance(context);
            return (String) appPref.getValue(AppPref.PrefKey.PREF_CUSTOM_LOCALE_STR);
        }

        public static void setLanguage(@NonNull String language) {
            AppPref.set(AppPref.PrefKey.PREF_CUSTOM_LOCALE_STR, language);
        }

        public static int getLayoutDirection() {
            return AppPref.getInt(AppPref.PrefKey.PREF_LAYOUT_ORIENTATION_INT);
        }

        public static void setLayoutDirection(int layoutDirection) {
            AppPref.set(AppPref.PrefKey.PREF_LAYOUT_ORIENTATION_INT, layoutDirection);
        }

        @StyleRes
        public static int getAppTheme() {
            switch (AppPref.getInt(AppPref.PrefKey.PREF_APP_THEME_CUSTOM_INT)) {
                case 1: // Full black theme
                    return io.github.muntashirakon.ui.R.style.AppTheme_Black;
                default: // Normal theme
                    return io.github.muntashirakon.ui.R.style.AppTheme;
            }
        }

        @StyleRes
        public static int getTransparentAppTheme() {
            switch (AppPref.getInt(AppPref.PrefKey.PREF_APP_THEME_CUSTOM_INT)) {
                case 1: // Full black theme
                    return io.github.muntashirakon.ui.R.style.AppTheme_TransparentBackground_Black;
                default: // Normal theme
                    return io.github.muntashirakon.ui.R.style.AppTheme_TransparentBackground;
            }
        }

        public static boolean isPureBlackTheme() {
            return AppPref.getInt(AppPref.PrefKey.PREF_APP_THEME_CUSTOM_INT) == 1;
        }

        public static void setPureBlackTheme(boolean enabled) {
            AppPref.set(AppPref.PrefKey.PREF_APP_THEME_CUSTOM_INT, enabled ? 1 : 0);
        }

        public static int getNightMode() {
            return AppPref.getInt(AppPref.PrefKey.PREF_APP_THEME_INT);
        }

        public static void setNightMode(int nightMode) {
            AppPref.set(AppPref.PrefKey.PREF_APP_THEME_INT, nightMode);
        }

        public static boolean useSystemFont() {
            return AppPref.getBoolean(AppPref.PrefKey.PREF_USE_SYSTEM_FONT_BOOL);
        }

        // Fork: in-app batch-operation progress dialog + the configurable theme
        // (text/background/border colour and border width) shared by that dialog
        // and the themed toasts.
        public static boolean showBatchProgressDialog() {
            return AppPref.getBoolean(AppPref.PrefKey.PREF_BATCH_PROGRESS_DIALOG_BOOL);
        }

        public static void setShowBatchProgressDialog(boolean show) {
            AppPref.set(AppPref.PrefKey.PREF_BATCH_PROGRESS_DIALOG_BOOL, show);
        }

        public static int getThemeTextColor() {
            return AppPref.getInt(AppPref.PrefKey.PREF_THEME_TEXT_COLOR_INT);
        }

        public static void setThemeTextColor(int color) {
            AppPref.set(AppPref.PrefKey.PREF_THEME_TEXT_COLOR_INT, color);
        }

        public static int getThemeBackgroundColor() {
            return AppPref.getInt(AppPref.PrefKey.PREF_THEME_BACKGROUND_COLOR_INT);
        }

        public static void setThemeBackgroundColor(int color) {
            AppPref.set(AppPref.PrefKey.PREF_THEME_BACKGROUND_COLOR_INT, color);
        }

        public static int getThemeBorderColor() {
            return AppPref.getInt(AppPref.PrefKey.PREF_THEME_BORDER_COLOR_INT);
        }

        public static void setThemeBorderColor(int color) {
            AppPref.set(AppPref.PrefKey.PREF_THEME_BORDER_COLOR_INT, color);
        }

        public static int getThemeBorderWidthDp() {
            return AppPref.getInt(AppPref.PrefKey.PREF_THEME_BORDER_WIDTH_INT);
        }

        public static void setThemeBorderWidthDp(int widthDp) {
            AppPref.set(AppPref.PrefKey.PREF_THEME_BORDER_WIDTH_INT, widthDp);
        }
    }

    public static final class BackupRestore {
        public static boolean backupAppsWithKeyStore() {
            return AppPref.getBoolean(AppPref.PrefKey.PREF_BACKUP_ANDROID_KEYSTORE_BOOL);
        }

        @NonNull
        @TarUtils.TarType
        public static String getCompressionMethod() {
            String tarType = AppPref.getString(AppPref.PrefKey.PREF_BACKUP_COMPRESSION_METHOD_STR);
            // Verify tar type
            if (ArrayUtils.indexOf(TAR_TYPES, tarType) == -1) {
                // Unknown tar type, set default
                tarType = TarUtils.TAR_GZIP;
            }
            return tarType;
        }

        public static void setCompressionMethod(@NonNull @TarUtils.TarType String tarType) {
            AppPref.set(AppPref.PrefKey.PREF_BACKUP_COMPRESSION_METHOD_STR, tarType);
        }

        @BackupFlags.BackupFlag
        public static int getBackupFlags() {
            return AppPref.getInt(AppPref.PrefKey.PREF_BACKUP_FLAGS_INT);
        }

        public static void setBackupFlags(@BackupFlags.BackupFlag int flags) {
            AppPref.set(AppPref.PrefKey.PREF_BACKUP_FLAGS_INT, flags);
        }

        public static boolean backupDirectoryExists() {
            Uri uri = Storage.getVolumePath();
            Path path;
            if (uri.getScheme().equals(ContentResolver.SCHEME_FILE)) {
                // Append AppManager only if storage permissions are granted
                String newPath = uri.getPath();
                if (SelfPermissions.checkStoragePermission()) {
                    newPath += File.separator + "AppManager";
                }
                path = Paths.get(newPath);
            } else path = Paths.get(uri);
            return path.exists();
        }
    }

    public static final class Blocking {
        public static boolean globalBlockingEnabled() {
            return AppPref.getBoolean(AppPref.PrefKey.PREF_GLOBAL_BLOCKING_ENABLED_BOOL);
        }

        @ComponentRule.ComponentStatus
        public static String getDefaultBlockingMethod() {
            String selectedStatus = AppPref.getString(AppPref.PrefKey.PREF_DEFAULT_BLOCKING_METHOD_STR);
            if (!SelfPermissions.canBlockByIFW()) {
                if (selectedStatus.equals(ComponentRule.COMPONENT_TO_BE_BLOCKED_IFW_DISABLE)
                        || selectedStatus.equals(ComponentRule.COMPONENT_TO_BE_BLOCKED_IFW)) {
                    // Lower the status
                    return ComponentRule.COMPONENT_TO_BE_DISABLED;
                }
            }
            return selectedStatus;
        }

        public static void setDefaultBlockingMethod(@NonNull @ComponentRule.ComponentStatus String blockingMethod) {
            AppPref.set(AppPref.PrefKey.PREF_DEFAULT_BLOCKING_METHOD_STR, blockingMethod);
        }

        @FreezeUtils.FreezeMethod
        public static int getDefaultFreezingMethod() {
            int freezeType = AppPref.getInt(AppPref.PrefKey.PREF_FREEZE_TYPE_INT);
            if (freezeType == FreezeUtils.FREEZE_TOTAL) {
                // Fork (白い熊, +29): every layer of the stack guards itself and the ones
                // that cannot be applied are skipped, so there is nothing to lower it to —
                // disable-user alone is always available to us and is the floor.
                return freezeType;
            } else if (freezeType == FreezeUtils.FREEZE_HIDE) {
                // Requires MANAGE_USERS permission — or, where the shell does not hold it
                // (on this phone it does not: measured 2026-09-10, `pm hide` refused for
                // uid 2000), 雫's DELEGATION_PACKAGE_ACCESS, which reaches the same call
                // through the Device Owner. Without this second test the Hide method
                // silently became Disable on every EMUI install.
                if (!SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.MANAGE_USERS)
                        && !DevicePolicyBridge.canHide()) {
                    return FreezeUtils.FREEZE_DISABLE;
                }
            } else if (freezeType == FreezeUtils.FREEZE_SUSPEND || freezeType == FreezeUtils.FREEZE_ADV_SUSPEND) {
                // 7+ only. Requires MANAGE_USERS permission until P. Requires SUSPEND_APPS permission after that.
                if (!DevicePolicyBridge.canSuspend()
                        && (Build.VERSION.SDK_INT < Build.VERSION_CODES.N
                        || Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.SUSPEND_APPS)
                        || (Build.VERSION.SDK_INT < Build.VERSION_CODES.P && !SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.MANAGE_USERS)))) {
                    return FreezeUtils.FREEZE_DISABLE;
                }
            }
            return freezeType;
        }

        public static void setDefaultFreezingMethod(@FreezeUtils.FreezeMethod int freezeType) {
            AppPref.set(AppPref.PrefKey.PREF_FREEZE_TYPE_INT, freezeType);
        }

        /**
         * Fork: one-time move of the stored default freezing method from <b>Disable</b>
         * to <b>Advanced suspend</b>.
         * <p>
         * <b>LANDMINE</b> — changing {@link AppPref#getDefaultValue} alone would be inert
         * on every existing install. {@code AppPref.init()} materialises <i>every</i> key
         * into {@code preferences.xml} on first run, so the old default is already
         * written and the new one is never consulted again; only a fresh install would
         * see it. Hence the rewrite.
         * <p>
         * It fires once, and only while the value is still exactly the old default, so a
         * deliberate choice made from this build on is never touched. It cannot tell
         * "never changed it" from "deliberately chose Disable" <i>before</i> this build —
         * on this fork those are the same thing, Disable having been the default.
         * <p>
         * Reads the raw preference rather than {@link #getDefaultFreezingMethod()}, which
         * lowers itself to Disable while the needed privileges are down — migrating on
         * that would depend on whether the service happened to be up at launch.
         */
        public static void migrateDefaultFreezingMethod() {
            if (AppPref.getBoolean(AppPref.PrefKey.PREF_FREEZE_TYPE_MIGRATED_ADV_SUSPEND_BOOL)) {
                return;
            }
            AppPref.set(AppPref.PrefKey.PREF_FREEZE_TYPE_MIGRATED_ADV_SUSPEND_BOOL, true);
            if (AppPref.getInt(AppPref.PrefKey.PREF_FREEZE_TYPE_INT) == FreezeUtils.FREEZE_DISABLE) {
                AppPref.set(AppPref.PrefKey.PREF_FREEZE_TYPE_INT, FreezeUtils.FREEZE_ADV_SUSPEND);
            }
        }

        /**
         * Fork (白い熊, +29): one-time move of the stored default freezing method from
         * <b>Advanced suspend</b> to <b>Total freeze</b>.
         * <p>
         * Same shape and same landmine as {@link #migrateDefaultFreezingMethod()} above —
         * {@code AppPref.init()} has already written the old default into
         * {@code preferences.xml}, so changing {@link AppPref#getDefaultValue} alone would
         * only ever reach a fresh install. It fires once and only while the value is still
         * exactly the previous default, so a method deliberately chosen from this build on
         * is never touched.
         * <p>
         * Runs <i>after</i> the Disable → Advanced suspend migration, so an install that
         * never moved off upstream's Disable lands on Total in one launch.
         */
        public static void migrateDefaultFreezingMethodToTotal() {
            if (AppPref.getBoolean(AppPref.PrefKey.PREF_FREEZE_TYPE_MIGRATED_TOTAL_BOOL)) {
                return;
            }
            AppPref.set(AppPref.PrefKey.PREF_FREEZE_TYPE_MIGRATED_TOTAL_BOOL, true);
            if (AppPref.getInt(AppPref.PrefKey.PREF_FREEZE_TYPE_INT) == FreezeUtils.FREEZE_ADV_SUSPEND) {
                AppPref.set(AppPref.PrefKey.PREF_FREEZE_TYPE_INT, FreezeUtils.FREEZE_TOTAL);
            }
        }

        public static boolean getSkipFreezeMethodDialog() {
            return AppPref.getBoolean(AppPref.PrefKey.PREF_SKIP_FREEZE_METHOD_DIALOG_BOOL);
        }

        public static void setSkipFreezeMethodDialog(boolean skip) {
            AppPref.set(AppPref.PrefKey.PREF_SKIP_FREEZE_METHOD_DIALOG_BOOL, skip);
        }
    }

    public static final class Encryption {
        @NonNull
        @CryptoUtils.Mode
        public static String getEncryptionMode() {
            return AppPref.getString(AppPref.PrefKey.PREF_ENCRYPTION_STR);
        }

        public static void setEncryptionMode(@NonNull @CryptoUtils.Mode String mode) {
            AppPref.set(AppPref.PrefKey.PREF_ENCRYPTION_STR, mode);
        }

        @NonNull
        public static String getOpenPgpProvider() {
            return AppPref.getString(AppPref.PrefKey.PREF_OPEN_PGP_PACKAGE_STR);
        }

        public static void setOpenPgpProvider(@NonNull String providerPackage) {
            AppPref.set(AppPref.PrefKey.PREF_OPEN_PGP_PACKAGE_STR, providerPackage);
        }

        @NonNull
        public static String getOpenPgpKeyIds() {
            return AppPref.getString(AppPref.PrefKey.PREF_OPEN_PGP_USER_ID_STR);
        }

        public static void setOpenPgpKeyIds(@NonNull String keyIds) {
            AppPref.set(AppPref.PrefKey.PREF_OPEN_PGP_USER_ID_STR, keyIds);
        }
    }

    public static final class FileManager {
        public static boolean displayInLauncher() {
            ComponentName componentName = new ComponentName(BuildConfig.APPLICATION_ID, FmActivity.LAUNCHER_ALIAS);
            int state = ContextUtils.getContext().getPackageManager().getComponentEnabledSetting(componentName);
            return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        }

        public static Uri getHome() {
            return Uri.parse(AppPref.getString(AppPref.PrefKey.PREF_FM_HOME_STR));
        }

        public static void setHome(@NonNull Uri uri) {
            AppPref.set(AppPref.PrefKey.PREF_FM_HOME_STR, uri.toString());
        }

        public static boolean isRememberLastOpenedPath() {
            return AppPref.getBoolean(AppPref.PrefKey.PREF_FM_REMEMBER_LAST_PATH_BOOL);
        }

        @Nullable
        public static Pair<FmActivity.Options, Pair<Uri, Integer>> getLastOpenedPath() {
            String jsonString = AppPref.getString(AppPref.PrefKey.PREF_FM_LAST_PATH_STR);
            try {
                JSONObject object = new JSONObject(jsonString);
                if (object.has("path") && object.has("pos")) {
                    boolean vfs = object.has("vfs") && object.getBoolean("vfs");
                    FmActivity.Options options = new FmActivity.Options(Uri.parse(object.getString("path")),
                            vfs, false, false);
                    if (!Paths.getStrict(options.uri).exists()) {
                        // Do not bother if path does not exist
                        return null;
                    }
                    Uri initUri;
                    if (vfs && object.has("init")) {
                        initUri = Uri.parse(object.getString("init"));
                    } else initUri = null;
                    Pair<Uri, Integer> uriPositionPair = new Pair<>(initUri, object.getInt("pos"));
                    return new Pair<>(options, uriPositionPair);
                }
            } catch (JSONException | FileNotFoundException e) {
                e.printStackTrace();
            }
            return null;
        }

        public static void setLastOpenedPath(@NonNull FmActivity.Options options, @NonNull Uri initUri, int position) {
            try {
                if (options.isVfs()) {
                    // Ignore VFS for now
                    return;
                }
                JSONObject object = new JSONObject();
                object.put("pos", position);
                if (options.isVfs()) {
                    object.put("vfs", true);
                    object.put("path", options.uri.toString());
                    object.put("init", initUri.toString());
                } else {
                    object.put("path", initUri.toString());
                }
                AppPref.set(AppPref.PrefKey.PREF_FM_LAST_PATH_STR, object.toString());
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @FmListOptions.Options
        public static int getOptions() {
            return AppPref.getInt(AppPref.PrefKey.PREF_FM_OPTIONS_INT);
        }

        public static void setOptions(@FmListOptions.Options int options) {
            AppPref.set(AppPref.PrefKey.PREF_FM_OPTIONS_INT, options);
        }

        @FmListOptions.SortOrder
        public static int getSortOrder() {
            return AppPref.getInt(AppPref.PrefKey.PREF_FM_SORT_ORDER_INT);
        }

        public static void setSortOrder(@FmListOptions.SortOrder int sortOrder) {
            AppPref.set(AppPref.PrefKey.PREF_FM_SORT_ORDER_INT, sortOrder);
        }

        public static boolean isReverseSort() {
            return AppPref.getBoolean(AppPref.PrefKey.PREF_FM_SORT_REVERSE_BOOL);
        }

        public static void setReverseSort(boolean reverseSort) {
            AppPref.set(AppPref.PrefKey.PREF_FM_SORT_REVERSE_BOOL, reverseSort);
        }
    }

    public static final class Installer {
        public static boolean installInBackground() {
            return AppPref.getBoolean(AppPref.PrefKey.PREF_INSTALLER_ALWAYS_ON_BACKGROUND_BOOL);
        }

        public static boolean displayChanges() {
            return AppPref.getBoolean(AppPref.PrefKey.PREF_INSTALLER_DISPLAY_CHANGES_BOOL);
        }

        public static boolean blockTrackers() {
            return AppPref.getBoolean(AppPref.PrefKey.PREF_INSTALLER_BLOCK_TRACKERS_BOOL);
        }

        public static boolean forceDexOpt() {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    && AppPref.getBoolean(AppPref.PrefKey.PREF_INSTALLER_FORCE_DEX_OPT_BOOL);
        }

        public static boolean canSignApk() {
            if (!AppPref.getBoolean(AppPref.PrefKey.PREF_INSTALLER_SIGN_APK_BOOL)) {
                // Signing not enabled
                return false;
            }
            return Signer.canSign();
        }

        public static int getInstallLocation() {
            return InstallerOptions.normalizeInstallLocation(
                    AppPref.getInt(AppPref.PrefKey.PREF_INSTALLER_INSTALL_LOCATION_INT));
        }

        public static void setInstallLocation(int installLocation) {
            AppPref.set(AppPref.PrefKey.PREF_INSTALLER_INSTALL_LOCATION_INT,
                    InstallerOptions.normalizeInstallLocation(installLocation));
        }

        @NonNull
        public static String getInstallerPackageName() {
            if (!SelfPermissions.checkSelfOrRemotePermission(Manifest.permission.INSTALL_PACKAGES)) {
                return BuildConfig.APPLICATION_ID;
            }
            return AppPref.getString(AppPref.PrefKey.PREF_INSTALLER_INSTALLER_APP_STR);
        }

        public static void setInstallerPackageName(@NonNull String packageName) {
            AppPref.set(AppPref.PrefKey.PREF_INSTALLER_INSTALLER_APP_STR, packageName);
        }

        public static boolean isSetOriginatingPackage() {
            return AppPref.getBoolean(AppPref.PrefKey.PREF_INSTALLER_SET_ORIGIN_BOOL);
        }

        public static int getPackageSource() {
            return InstallerOptions.normalizePackageSource(
                    AppPref.getInt(AppPref.PrefKey.PREF_INSTALLER_DEFAULT_PKG_SOURCE_INT));
        }

        public static void setPackageSource(int source) {
            AppPref.set(AppPref.PrefKey.PREF_INSTALLER_DEFAULT_PKG_SOURCE_INT,
                    InstallerOptions.normalizePackageSource(source));
        }

        public static boolean requestUpdateOwnership() {
            // Shell default is false
            return AppPref.getBoolean(AppPref.PrefKey.PREF_INSTALLER_UPDATE_OWNERSHIP_BOOL);
        }

        public static boolean isDisableApkVerification() {
            return AppPref.getBoolean(AppPref.PrefKey.PREF_INSTALLER_DISABLE_VERIFICATION_BOOL);
        }
    }

    public static final class LogViewer {
        @LogcatHelper.LogBufferId
        public static int getBuffers() {
            return AppPref.getInt(AppPref.PrefKey.PREF_LOG_VIEWER_BUFFER_INT);
        }

        public static void setBuffers(@LogcatHelper.LogBufferId int buffers) {
            AppPref.set(AppPref.PrefKey.PREF_LOG_VIEWER_BUFFER_INT, buffers);
        }

        public static int getLogLevel() {
            return AppPref.getInt(AppPref.PrefKey.PREF_LOG_VIEWER_DEFAULT_LOG_LEVEL_INT);
        }

        public static void setLogLevel(int logLevel) {
            AppPref.set(AppPref.PrefKey.PREF_LOG_VIEWER_DEFAULT_LOG_LEVEL_INT, logLevel);
        }

        public static int getDisplayLimit() {
            return AppPref.getInt(AppPref.PrefKey.PREF_LOG_VIEWER_DISPLAY_LIMIT_INT);
        }

        public static void setDisplayLimit(int displayLimit) {
            AppPref.set(AppPref.PrefKey.PREF_LOG_VIEWER_DISPLAY_LIMIT_INT, displayLimit);
        }

        @NonNull
        public static String getFilterPattern() {
            return AppPref.getString(AppPref.PrefKey.PREF_LOG_VIEWER_FILTER_PATTERN_STR);
        }

        public static void setFilterPattern(@NonNull String filterPattern) {
            AppPref.set(AppPref.PrefKey.PREF_LOG_VIEWER_FILTER_PATTERN_STR, filterPattern);
        }

        public static int getLogWritingInterval() {
            return AppPref.getInt(AppPref.PrefKey.PREF_LOG_VIEWER_WRITE_PERIOD_INT);
        }

        public static void setLogWritingInterval(int logWritingInterval) {
            AppPref.set(AppPref.PrefKey.PREF_LOG_VIEWER_WRITE_PERIOD_INT, logWritingInterval);
        }

        public static boolean expandByDefault() {
            return AppPref.getBoolean(AppPref.PrefKey.PREF_LOG_VIEWER_EXPAND_BY_DEFAULT_BOOL);
        }

        public static boolean omitSensitiveInfo() {
            return AppPref.getBoolean(AppPref.PrefKey.PREF_LOG_VIEWER_OMIT_SENSITIVE_INFO_BOOL);
        }

        public static boolean showPidTidTimestamp() {
            return AppPref.getBoolean(AppPref.PrefKey.PREF_LOG_VIEWER_SHOW_PID_TID_TIMESTAMP_BOOL);
        }
    }

    public static final class MainPage {
        @MainListOptions.SortOrder
        public static int getSortOrder() {
            return AppPref.getInt(AppPref.PrefKey.PREF_MAIN_WINDOW_SORT_ORDER_INT);
        }

        public static void setSortOrder(@MainListOptions.SortOrder int sortOrder) {
            AppPref.set(AppPref.PrefKey.PREF_MAIN_WINDOW_SORT_ORDER_INT, sortOrder);
        }

        public static boolean isReverseSort() {
            return AppPref.getBoolean(AppPref.PrefKey.PREF_MAIN_WINDOW_SORT_REVERSE_BOOL);
        }

        public static void setReverseSort(boolean reverseSort) {
            AppPref.set(AppPref.PrefKey.PREF_MAIN_WINDOW_SORT_REVERSE_BOOL, reverseSort);
        }

        @MainListOptions.Filter
        public static int getFilters() {
            return AppPref.getInt(AppPref.PrefKey.PREF_MAIN_WINDOW_FILTER_FLAGS_INT);
        }

        public static void setFilters(@MainListOptions.Filter int filters) {
            AppPref.set(AppPref.PrefKey.PREF_MAIN_WINDOW_FILTER_FLAGS_INT, filters);
        }

        @Nullable
        public static String getFilteredProfileName() {
            String profileName = AppPref.getString(AppPref.PrefKey.PREF_MAIN_WINDOW_FILTER_PROFILE_STR);
            if (TextUtils.isEmpty(profileName)) {
                return null;
            }
            return profileName;
        }

        public static void setFilteredProfileName(@Nullable String profileName) {
            AppPref.set(AppPref.PrefKey.PREF_MAIN_WINDOW_FILTER_PROFILE_STR, profileName == null ? "" : profileName);
        }

        public static boolean getFilteredProfileNegate() {
            return AppPref.getBoolean(AppPref.PrefKey.PREF_MAIN_WINDOW_FILTER_PROFILE_NEGATE_BOOL);
        }

        public static void setFilteredProfileNegate(boolean negate) {
            AppPref.set(AppPref.PrefKey.PREF_MAIN_WINDOW_FILTER_PROFILE_NEGATE_BOOL, negate);
        }
    }

    public static final class Misc {
        @Nullable
        public static int[] getSelectedUsers() {
            String usersStr = AppPref.getString(AppPref.PrefKey.PREF_SELECTED_USERS_STR);
            if (usersStr.isEmpty()) return null;
            String[] usersSplitStr = usersStr.split(",");
            int[] users = new int[usersSplitStr.length];
            for (int i = 0; i < users.length; ++i) {
                users[i] = Integer.decode(usersSplitStr[i]);
            }
            return users;
        }

        public static void setSelectedUsers(@Nullable int[] users) {
            if (users == null) {
                AppPref.set(AppPref.PrefKey.PREF_SELECTED_USERS_STR, "");
                return;
            }
            String[] userString = new String[users.length];
            for (int i = 0; i < users.length; ++i) {
                userString[i] = String.valueOf(users[i]);
            }
            AppPref.set(AppPref.PrefKey.PREF_SELECTED_USERS_STR, TextUtils.join(",", userString));
        }

        public static boolean sendNotificationsToConnectedDevices() {
            return AppPref.getBoolean(AppPref.PrefKey.PREF_SEND_NOTIFICATIONS_TO_CONNECTED_DEVICES_BOOL);
        }

        public static void setAdbLocalServerPort(int port) {
            AppPref.set(AppPref.PrefKey.PREF_ADB_LOCAL_SERVER_PORT_INT, port);
        }

        public static int getAdbLocalServerPort() {
            return AppPref.getInt(AppPref.PrefKey.PREF_ADB_LOCAL_SERVER_PORT_INT);
        }
    }

    public static final class Privacy {
        public static boolean isScreenLockEnabled() {
            return AppPref.getBoolean(AppPref.PrefKey.PREF_ENABLE_SCREEN_LOCK_BOOL);
        }

        public static boolean isAutoLockEnabled() {
            return AppPref.getBoolean(AppPref.PrefKey.PREF_ENABLE_AUTO_LOCK_BOOL);
        }

        public static boolean isPersistentSessionAllowed() {
            return AppPref.getBoolean(AppPref.PrefKey.PREF_ENABLE_PERSISTENT_SESSION_BOOL);
        }
    }

    public static final class Signing {
        @NonNull
        public static SigSchemes getSigSchemes() {
            SigSchemes sigSchemes = new SigSchemes(AppPref.getInt(AppPref.PrefKey.PREF_SIGNATURE_SCHEMES_INT));
            if (sigSchemes.isEmpty()) {
                // Use default if no flag is set
                return new SigSchemes(SigSchemes.DEFAULT_SCHEMES);
            }
            return sigSchemes;
        }

        public static void setSigSchemes(int flags) {
            AppPref.set(AppPref.PrefKey.PREF_SIGNATURE_SCHEMES_INT, flags);
        }

        public static boolean zipAlign() {
            return AppPref.getBoolean(AppPref.PrefKey.PREF_ZIP_ALIGN_BOOL);
        }
    }

    public static final class Storage {
        // Fork: an optional regular filesystem directory used for backup and
        // restore. When set, it fully overrides the SAF "backup volume" below
        // (SAF is slow; with all-files access a direct path is far faster).
        // Stored in a dedicated SharedPreferences so it does not touch the
        // AppPref PrefKey enum. Empty string = unset (fall back to volume).
        private static final String PREFS_BACKUP_DIR = "am_backup_directory";
        private static final String KEY_BACKUP_DIR = "path";

        @NonNull
        public static String getBackupDirectory() {
            return ContextUtils.getContext()
                    .getSharedPreferences(PREFS_BACKUP_DIR, Context.MODE_PRIVATE)
                    .getString(KEY_BACKUP_DIR, "");
        }

        public static boolean hasBackupDirectory() {
            return !getBackupDirectory().isEmpty();
        }

        /** Set the override directory (absolute filesystem path); pass "" to clear. */
        public static void setBackupDirectory(@NonNull String path) {
            ContextUtils.getContext()
                    .getSharedPreferences(PREFS_BACKUP_DIR, Context.MODE_PRIVATE)
                    .edit().putString(KEY_BACKUP_DIR, path).apply();
        }

        // Fork: when set, selecting Backup uses the default backup options
        // directly (no "Backup options" dialog) and starts the backup right
        // away — analogous to the Rules "Skip freeze method dialog" toggle.
        // Applies only when Backup is the sole action (no existing backup to
        // restore/delete); the backup+restore picker is unaffected.
        private static final String PREFS_BACKUP_OPTS = "am_backup_options";
        private static final String KEY_SKIP_BACKUP_DIALOG = "skip_method_dialog";

        public static boolean getSkipBackupMethodDialog() {
            return ContextUtils.getContext()
                    .getSharedPreferences(PREFS_BACKUP_OPTS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_SKIP_BACKUP_DIALOG, false);
        }

        public static void setSkipBackupMethodDialog(boolean skip) {
            ContextUtils.getContext()
                    .getSharedPreferences(PREFS_BACKUP_OPTS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_SKIP_BACKUP_DIALOG, skip).apply();
        }

        // Fork: directory where App Manager's own settings (not app backups)
        // are exported to / imported from. Stored in the same dedicated prefs
        // file. Empty string = unset.
        private static final String KEY_SETTINGS_EXPORT_DIR = "settings_export_dir";

        @NonNull
        public static String getSettingsExportDirectory() {
            return ContextUtils.getContext()
                    .getSharedPreferences(PREFS_BACKUP_OPTS, Context.MODE_PRIVATE)
                    .getString(KEY_SETTINGS_EXPORT_DIR, "");
        }

        public static boolean hasSettingsExportDirectory() {
            return !getSettingsExportDirectory().isEmpty();
        }

        /** Set the settings export/import directory (absolute path); "" clears. */
        public static void setSettingsExportDirectory(@NonNull String path) {
            ContextUtils.getContext()
                    .getSharedPreferences(PREFS_BACKUP_OPTS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_SETTINGS_EXPORT_DIR, path).apply();
        }

        // Fork (白い熊, +046): where the batch operation log was last saved.
        // Only the seed for the directory chooser that Save log now opens —
        // 白い熊 still picks a directory every time, so a path carried over
        // from another phone costs nothing (the chooser falls back to the
        // storage root when it does not exist). Empty string = never saved.
        private static final String KEY_OP_LOG_DIR = "op_log_dir";

        @NonNull
        public static String getOpLogDirectory() {
            return ContextUtils.getContext()
                    .getSharedPreferences(PREFS_BACKUP_OPTS, Context.MODE_PRIVATE)
                    .getString(KEY_OP_LOG_DIR, "");
        }

        public static void setOpLogDirectory(@NonNull String path) {
            ContextUtils.getContext()
                    .getSharedPreferences(PREFS_BACKUP_OPTS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_OP_LOG_DIR, path).apply();
        }

        @NonNull
        public static Path getAppManagerDirectory() {
            // Fork: a configured backup directory takes precedence over the
            // SAF volume and is used as the base directory directly (AppManager
            // creates its "backups"/"apks"/.nomedia structure inside it).
            String dir = getBackupDirectory();
            if (!dir.isEmpty()) {
                Path dirPath = Paths.get(dir);
                if (!dirPath.exists()) dirPath.mkdirs();
                return dirPath;
            }
            Uri uri = getVolumePath();
            Path path;
            if (Objects.equals(uri.getScheme(), ContentResolver.SCHEME_FILE)) {
                // Append AppManager
                String newPath = uri.getPath() + File.separator + "AppManager";
                path = Paths.get(newPath);
            } else path = Paths.get(uri);
            if (!path.exists()) path.mkdirs();
            return path;
        }

        public static Uri getVolumePath() {
            String uriOrBareFile = AppPref.getString(AppPref.PrefKey.PREF_BACKUP_VOLUME_STR);
            if (uriOrBareFile.startsWith("/")) {
                // A good URI starts with file:// or content://, if not, migrate
                Uri uri = new Uri.Builder().scheme(ContentResolver.SCHEME_FILE).path(uriOrBareFile).build();
                AppPref.set(AppPref.PrefKey.PREF_BACKUP_VOLUME_STR, uri.toString());
                return uri;
            }
            return Uri.parse(uriOrBareFile);
        }


        public static void setVolumePath(@NonNull String path) {
            AppPref.set(AppPref.PrefKey.PREF_BACKUP_VOLUME_STR, path);
        }

        @NonNull
        public static Path getTempPath() {
            // This path is intended for storing temporary data for backup/restore and similar operations
            return Paths.get(FileUtils.getCachePath());
        }
    }

    public static final class VirusTotal {
        @Nullable
        public static String getApiKey() {
            String apiKey = AppPref.getString(AppPref.PrefKey.PREF_VIRUS_TOTAL_API_KEY_STR);
            if (TextUtils.isEmpty(apiKey)) {
                return null;
            }
            return apiKey;
        }


        public static void setApiKey(@Nullable String apiKey) {
            AppPref.set(AppPref.PrefKey.PREF_VIRUS_TOTAL_API_KEY_STR, apiKey);
        }

        public static boolean promptBeforeUpload() {
            return AppPref.getBoolean(AppPref.PrefKey.PREF_VIRUS_TOTAL_PROMPT_BEFORE_UPLOADING_BOOL);
        }
    }
}
