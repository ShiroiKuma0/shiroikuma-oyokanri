// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.backup;

import static io.github.muntashirakon.AppManager.backup.BackupManager.CERT_PREFIX;
import static io.github.muntashirakon.AppManager.backup.BackupManager.KEYSTORE_PLACEHOLDER;
import static io.github.muntashirakon.AppManager.backup.BackupManager.KEYSTORE_PREFIX;
import static io.github.muntashirakon.AppManager.backup.BackupManager.MASTER_KEY;
import static io.github.muntashirakon.AppManager.backup.BackupManager.getExt;
import static io.github.muntashirakon.AppManager.backup.BackupUtils.TAR_TYPES;
import static io.github.muntashirakon.AppManager.compat.PackageManagerCompat.GET_SIGNING_CERTIFICATES;

import android.annotation.UserIdInt;
import android.app.INotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.text.style.ForegroundColorSpan;
import android.text.Spanned;
import android.text.SpannableStringBuilder;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.WorkerThread;
import androidx.core.content.pm.PackageInfoCompat;
import androidx.core.content.pm.PermissionInfoCompat;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.apk.ApkFile;
import io.github.muntashirakon.AppManager.apk.ApkSource;
import io.github.muntashirakon.AppManager.backup.struct.BackupMetadataV5;
import io.github.muntashirakon.AppManager.compat.AppOpsManagerCompat;
import io.github.muntashirakon.AppManager.compat.BackupCompat;
import io.github.muntashirakon.AppManager.compat.DeviceIdleManagerCompat;
import io.github.muntashirakon.AppManager.compat.ManifestCompat;
import io.github.muntashirakon.AppManager.compat.NetworkPolicyManagerCompat;
import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.compat.PermissionCompat;
import io.github.muntashirakon.AppManager.crypto.CryptoException;
import io.github.muntashirakon.AppManager.ipc.ProxyBinder;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.misc.OsEnvironment;
import io.github.muntashirakon.AppManager.progress.ProgressHandler;
import io.github.muntashirakon.AppManager.rules.PseudoRules;
import io.github.muntashirakon.AppManager.rules.compontents.ComponentUtils;
import io.github.muntashirakon.AppManager.rules.compontents.ComponentsBlocker;
import io.github.muntashirakon.AppManager.self.SelfPermissions;
import io.github.muntashirakon.AppManager.settings.Prefs;
import io.github.muntashirakon.AppManager.uri.UriManager;
import io.github.muntashirakon.AppManager.utils.ArrayUtils;
import io.github.muntashirakon.AppManager.utils.BitmapRandomizer;
import io.github.muntashirakon.AppManager.utils.ContextUtils;
import io.github.muntashirakon.AppManager.utils.DigestUtils;
import io.github.muntashirakon.AppManager.utils.ExUtils;
import io.github.muntashirakon.AppManager.utils.FileUtils;
import io.github.muntashirakon.AppManager.utils.FreezeUtils;
import io.github.muntashirakon.AppManager.utils.KeyStoreUtils;
import io.github.muntashirakon.AppManager.utils.PackageUtils;
import io.github.muntashirakon.AppManager.utils.ParcelFileDescriptorUtil;
import io.github.muntashirakon.AppManager.utils.TarUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;
import io.github.muntashirakon.AppManager.utils.Utils;
import io.github.muntashirakon.AppManager.appdata.AppDataTransfer;
import io.github.muntashirakon.AppManager.batchops.BatchOpsProgressMonitor;
import io.github.muntashirakon.AppManager.batchops.OpLog;
import io.github.muntashirakon.AppManager.fonts.ColorPrefs;
import io.github.muntashirakon.io.IoUtils;
import io.github.muntashirakon.io.Path;
import io.github.muntashirakon.io.Paths;

@WorkerThread
class BackupOp implements Closeable {
    static final String TAG = BackupOp.class.getSimpleName();


    @NonNull
    private final String mPackageName;
    @NonNull
    private final BackupItems.BackupItem mBackupItem;
    @NonNull
    private final BackupFlags mBackupFlags;
    @NonNull
    private final BackupMetadataV5 mMetadata;
    @NonNull
    private final PackageInfo mPackageInfo;
    @NonNull
    private final ApplicationInfo mApplicationInfo;
    @UserIdInt
    private final int mUserId;
    @NonNull
    private final BackupItems.Checksum mChecksum;
    // We don't need privileged package manager here
    @NonNull
    private final PackageManager mPm;
    // Fork (白い熊, +133): a one-run override of the App-supplied category choice, so narrowing
    // one backup does not narrow every future one.
    @Nullable
    private final String[] mAppDataCategories;
    /** Fork (白い熊, +140): the stages this run will announce; see {@link #planStages()}. */
    @Nullable
    private List<Integer> mStagePlan;
    /** The stage currently running, so its number can be repeated on the lines beneath it. */
    @StringRes
    private int mCurrentStageRes;

    BackupOp(@NonNull String packageName, @NonNull BackupFlags backupFlags,
             @NonNull BackupItems.BackupItem backupItem, @UserIdInt int userId)
            throws BackupException {
        this(packageName, backupFlags, backupItem, userId, null);
    }

    /**
     * @param appDataCategories the App-supplied categories to export this once, or {@code null}
     *                          to use the app's stored choice — see BackupOpOptions.
     */
    BackupOp(@NonNull String packageName, @NonNull BackupFlags backupFlags,
             @NonNull BackupItems.BackupItem backupItem, @UserIdInt int userId,
             @Nullable String[] appDataCategories)
            throws BackupException {
        mAppDataCategories = appDataCategories;
        mPackageName = packageName;
        mBackupItem = backupItem;
        mUserId = userId;
        mBackupFlags = backupFlags;
        mPm = ContextUtils.getContext().getPackageManager();
        try {
            mPackageInfo = PackageManagerCompat.getPackageInfo(mPackageName,
                    PackageManager.GET_META_DATA | GET_SIGNING_CERTIFICATES | PackageManager.GET_PERMISSIONS
                            | PackageManagerCompat.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES, userId);
            Objects.requireNonNull(mPackageInfo);
            mApplicationInfo = Objects.requireNonNull(mPackageInfo.applicationInfo);
            // Override existing metadata
            mMetadata = setupMetadataAndCrypto();
        } catch (Throwable e) {
            mBackupItem.cleanup();
            throw new BackupException("Failed to setup metadata.", e);
        }
        try {
            mChecksum = mBackupItem.getChecksum();
            String[] certChecksums = PackageUtils.getSigningCertChecksums(mMetadata.info.checksumAlgo, mPackageInfo, false);
            for (int i = 0; i < certChecksums.length; ++i) {
                mChecksum.add(CERT_PREFIX + i, certChecksums[i]);
            }
        } catch (Throwable e) {
            mBackupItem.cleanup();
            throw new BackupException("Failed to create checksum file.", e);
        }
    }

    @Override
    public void close() {
        mBackupItem.cleanup();
    }

    @NonNull
    public BackupMetadataV5 getMetadata() {
        return mMetadata;
    }

    void runBackup(@Nullable ProgressHandler progressHandler) throws BackupException {
        runBackup(progressHandler, null);
    }

    // Fork: the listener narrates the stages within this one app. Each stage is
    // announced BEFORE the work it names, so a slow step (a multi-gigabyte data
    // directory) is visible for the whole time it is running rather than named
    // only once it is over.
    void runBackup(@Nullable ProgressHandler progressHandler, @Nullable BackupProgressListener listener)
            throws BackupException {
        mStagePlan = planStages();
        try {
            // Fail backup if the app has items in Android KeyStore and backup isn't enabled
            if (mBackupFlags.backupData() && mMetadata.metadata.keyStore && !Prefs.BackupRestore.backupAppsWithKeyStore()) {
                throw new BackupException("The app has keystore items and KeyStore backup isn't enabled.");
            }
            incrementProgress(progressHandler);
            // Backup icon
            stage(listener, null, R.string.backup_stage_preparing);
            backupIcon();
            // Backup source
            if (mBackupFlags.backupApkFiles()) {
                stage(listener, mMetadata.metadata.apkName, R.string.backup_stage_apk);
                backupApkFiles(listener);
                incrementProgress(progressHandler);
            }
            // Backup data
            if (mBackupFlags.backupData()) {
                backupData(listener);
                // Backup KeyStore
                if (mMetadata.metadata.keyStore) {
                    stage(listener, null, R.string.backup_stage_keystore);
                    backupKeyStore(listener);
                }
                incrementProgress(progressHandler);
            }
            // Backup extras
            if (mBackupFlags.backupExtras()) {
                stage(listener, null, R.string.backup_stage_extras);
                backupExtras(listener);
                incrementProgress(progressHandler);
            }
            // Fork: app-supplied data, fetched from the app itself. Skipped silently for every
            // app that does not implement the contract, which is most of them.
            if (mBackupFlags.backupAppData()) {
                backupAppData(listener);
                incrementProgress(progressHandler);
            }
            // Export rules
            if (mMetadata.metadata.hasRules) {
                stage(listener, null, R.string.backup_stage_rules);
                backupRules();
                incrementProgress(progressHandler);
            }
            stage(listener, null, R.string.backup_stage_finalising);
            // Write modified metadata
            try {
                Map<String, String> filenameChecksumMap = MetadataManager.writeMetadata(mMetadata, mBackupItem);
                for (Map.Entry<String, String> entry : filenameChecksumMap.entrySet()) {
                    mChecksum.add(entry.getKey(), entry.getValue());
                }
            } catch (IOException e) {
                throw new BackupException("Failed to write metadata.", e);
            }
            mChecksum.close();
            // Encrypt checksum
            try {
                mBackupItem.encrypt(new Path[]{mChecksum.getFile()});
            } catch (IOException e) {
                throw new BackupException("Failed to write checksums.txt", e);
            }
            // Replace current backup
            stage(listener, null, R.string.backup_stage_committing);
            try {
                mBackupItem.commit();
            } catch (IOException e) {
                throw new BackupException("Could not finalise backup.", e);
            }
        } catch (BackupException e) {
            throw e;
        } catch (BatchOpsProgressMonitor.OperationCancelledException e) {
            // Fork (白い熊, +143): a cancel is an answer, not an error. Wrapped as a
            // BackupException it would be reported as "could not back up" and counted as a
            // failure by the batch that asked for it to stop.
            throw e;
        } catch (Throwable th) {
            throw new BackupException("Unknown error occurred.", th);
        }
    }

    private static void incrementProgress(@Nullable ProgressHandler progressHandler) {
        if (progressHandler == null) {
            return;
        }
        float current = progressHandler.getLastProgress() + 1;
        progressHandler.postUpdate(current);
    }

    // Fork: name the stage this app's backup has reached. Formatted here rather
    // than at the listener, since only this class knows the counts involved.
    /**
     * Fork (白い熊, +140): every stage says which of how many it is.
     *
     * <p>A backup that spends twenty-three minutes inside one app said only "0 / 1" — the app
     * count — and the stage name, with nothing to say whether that was the second step of eight
     * or the last one. The number is composed here rather than counted as we go, so a stage
     * announced repeatedly (the data directories) keeps ONE number instead of walking the total.
     *
     * <p>The counter is drawn in its own colour, so it reads as position rather than as part of
     * the stage's name — in the log and, because the same CharSequence feeds it, in the header
     * line naming the app currently in flight.
     */
    private void stage(@Nullable BackupProgressListener listener, @Nullable CharSequence detail,
                       @StringRes int stageRes, @Nullable Object... args) {
        if (listener == null) {
            return;
        }
        Context context = ContextUtils.getContext();
        checkCancelled();
        mCurrentStageRes = stageRes;
        CharSequence stage = args == null || args.length == 0
                ? context.getText(stageRes)
                : context.getString(stageRes, args);
        listener.onStage(numbered(context, stage, stageRes), detail);
    }

    /**
     * Fork (白い熊, +143): the stage number, repeated on a line beneath it.
     *
     * <p>Putting it on the stage heading alone was enough for a short backup and useless for the
     * one that needed it: a 6 GB export writes thousands of progress lines, and by the time you
     * look, the heading that said which stage this is has scrolled hours out of reach. Every
     * line that reports progress carries the number, so the answer is wherever you happen to be
     * looking rather than somewhere you have to scroll back to.
     */
    @NonNull
    private CharSequence marked(@Nullable CharSequence text) {
        if (text == null || mStagePlan == null || mCurrentStageRes == 0) {
            return text == null ? "" : text;
        }
        return numbered(ContextUtils.getContext(), text, mCurrentStageRes);
    }

    /** The stages this run will actually announce, in order. Built once, before any of them. */
    @NonNull
    private List<Integer> planStages() {
        List<Integer> plan = new ArrayList<>();
        plan.add(R.string.backup_stage_preparing);
        if (mBackupFlags.backupApkFiles()) {
            plan.add(R.string.backup_stage_apk);
        }
        if (mBackupFlags.backupData()) {
            plan.add(R.string.backup_stage_data);
            if (mMetadata.metadata.keyStore) {
                plan.add(R.string.backup_stage_keystore);
            }
        }
        if (mBackupFlags.backupExtras()) {
            plan.add(R.string.backup_stage_extras);
        }
        // Planned only when the app really has a door: an app without one returns before it
        // announces anything, and a plan that counted it would leave a gap in the numbering.
        if (mBackupFlags.backupAppData() && io.github.muntashirakon.AppManager.appdata.AppDataContract
                .isSupported(ContextUtils.getContext(), mPackageName)) {
            plan.add(R.string.backup_stage_app_data);
            // The three passes that follow the app handing its archive over. They sit here, in
            // the order they run, so the numbering stays monotone as it climbs.
            plan.add(R.string.backup_stage_storing);
            if (isEncrypted()) {
                plan.add(R.string.backup_stage_encrypting);
            }
            plan.add(R.string.backup_stage_checksumming);
        }
        if (mMetadata.metadata.hasRules) {
            plan.add(R.string.backup_stage_rules);
        }
        plan.add(R.string.backup_stage_finalising);
        plan.add(R.string.backup_stage_committing);
        return plan;
    }

    /**
     * Fork (白い熊, +143): the cancel checkpoint <em>inside</em> one app's backup.
     *
     * <p>{@code BatchOpsManager.updateProgress} checks once per app, before that app's work —
     * which is the right place for a batch and no place at all for a single app that takes
     * twenty minutes. Cancel had nothing to reach until the app finished, so on a one-app batch
     * it did nothing whatever and the only way out was to kill the process.
     */
    private void checkCancelled() {
        if (BatchOpsProgressMonitor.getInstance().isCancelled()) {
            throw new BatchOpsProgressMonitor.OperationCancelledException();
        }
    }

    /** Whether this backup is being written encrypted, so the pass is worth announcing. */
    private boolean isEncrypted() {
        String crypto = mMetadata.info.crypto;
        return crypto != null && !CryptoUtils.MODE_NO_ENCRYPTION.equals(crypto);
    }

    @NonNull
    private CharSequence numbered(@NonNull Context context, @NonNull CharSequence stage,
                                  @StringRes int stageRes) {
        List<Integer> plan = mStagePlan;
        if (plan == null) {
            return stage;
        }
        int index = plan.indexOf(stageRes);
        if (index < 0) {
            return stage;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder(stage);
        int start = sb.length();
        sb.append("  ").append(String.valueOf(index + 1)).append("/").append(String.valueOf(plan.size()));
        try {
            sb.setSpan(new ForegroundColorSpan(ColorPrefs.getColor(context, ColorPrefs.OPLOG_BATCH)),
                    start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } catch (Throwable ignore) {
            // A colour we could not read is not a reason to lose the number.
        }
        return sb;
    }

    // Fork: report the bytes a step just produced. Sizes come from the archive
    // members themselves, so what is reported is what actually landed on disk —
    // compressed, encrypted, and after exclusions — rather than the size of the
    // source directory, which would over-report by a wide and varying margin.
    /**
     * A copy that can be given up on. {@link IoUtils#copy} cannot: it is one call over gigabytes,
     * and a cancel raised during it is not seen until it returns.
     */
    private void copyCancellable(@NonNull InputStream is, @NonNull OutputStream os) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        int sinceCheck = 0;
        while ((read = is.read(buffer)) > 0) {
            os.write(buffer, 0, read);
            // Every few megabytes: often enough to feel immediate, rarely enough to cost nothing.
            if (++sinceCheck >= 64) {
                sinceCheck = 0;
                checkCancelled();
            }
        }
    }

    private void reportBytes(@Nullable BackupProgressListener listener, @Nullable Path[] files) {
        if (listener == null || files == null) {
            return;
        }
        long total = 0;
        for (Path file : files) {
            long length = 0;
            try {
                length = file.length();
            } catch (Throwable ignore) {
            }
            total += length;
            // Fork (+116): every archive member is named as it lands, with the size it actually
            // occupies. This is the one place that knows both, so the log is built here rather
            // than guessed at by the listener.
            listener.onItem(marked(file.getName()), length > 0 ? OpLog.formatSize(length) : null);
        }
        listener.onBytesWritten(total);
    }

    public BackupMetadataV5 setupMetadataAndCrypto() throws CryptoException {
        // We don't need to backup custom users or multiple backup flags
        mBackupFlags.removeFlag(BackupFlags.BACKUP_CUSTOM_USERS | BackupFlags.BACKUP_MULTIPLE);
        String backupName = mBackupItem.getBackupName();
        long backupTime = System.currentTimeMillis();
        String tarType = Prefs.BackupRestore.getCompressionMethod();
        // Verify tar type
        if (ArrayUtils.indexOf(TAR_TYPES, tarType) == -1) {
            // Unknown tar type, set default
            tarType = TarUtils.TAR_GZIP;
        }
        String crypto = CryptoUtils.getMode();
        BackupCryptSetupHelper cryptoHelper = new BackupCryptSetupHelper(crypto, MetadataManager.getCurrentBackupMetaVersion());
        mBackupItem.setCrypto(cryptoHelper.crypto);
        BackupMetadataV5.Info backupInfo = new BackupMetadataV5.Info(backupTime, mBackupFlags,
                mUserId, tarType, DigestUtils.SHA_256, crypto, cryptoHelper.getIv(),
                cryptoHelper.getAes(), cryptoHelper.getKeyIds());
        backupInfo.setBackupItem(mBackupItem);
        BackupMetadataV5.Metadata metadata = new BackupMetadataV5.Metadata(backupName);
        metadata.keyStore = KeyStoreUtils.hasKeyStore(mApplicationInfo.uid);
        metadata.label = mApplicationInfo.loadLabel(mPm).toString();
        metadata.packageName = mPackageName;
        metadata.versionName = mPackageInfo.versionName;
        metadata.versionCode = PackageInfoCompat.getLongVersionCode(mPackageInfo);
        metadata.apkName = new File(mApplicationInfo.sourceDir).getName();
        String[] dataDirs = null;
        if (mBackupFlags.backupAdbData()) {
            if (BackupCompat.isAppEligibleForBackupForUser(mUserId, mPackageName)) {
                mBackupFlags.removeFlag(BackupFlags.BACKUP_INT_DATA);
                mBackupFlags.removeFlag(BackupFlags.BACKUP_EXT_DATA);
                List<String> defaultDirs = BackupUtils.getDataDirectories(mApplicationInfo, false,
                        false, mBackupFlags.backupMediaObb());
                dataDirs = new String[defaultDirs.size() + 1];
                for (int i = 0; i < defaultDirs.size(); ++i) {
                    dataDirs[i] = defaultDirs.get(i);
                }
                dataDirs[defaultDirs.size()] = BackupManager.DATA_BACKUP_SPECIAL_ADB;
            } else {
                // ADB backup cannot be used.
                mBackupFlags.removeFlag(BackupFlags.BACKUP_ADB_DATA);
                mBackupFlags.addFlag(BackupFlags.BACKUP_INT_DATA);
                mBackupFlags.addFlag(BackupFlags.BACKUP_EXT_DATA);
            }
        }
        if (dataDirs == null) {
            // Non-ADB backup: default
            dataDirs = BackupUtils.getDataDirectories(mApplicationInfo, mBackupFlags.backupInternalData(),
                    mBackupFlags.backupExternalData(), mBackupFlags.backupMediaObb()).toArray(new String[0]);
        }
        metadata.dataDirs = dataDirs;
        metadata.isSystem = (mApplicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        metadata.isSplitApk = false;
        try (ApkFile apkFile = ApkSource.getApkSource(mApplicationInfo).resolve()) {
            if (apkFile.isSplit()) {
                List<ApkFile.Entry> apkEntries = apkFile.getEntries();
                int splitCount = apkEntries.size() - 1;
                metadata.isSplitApk = splitCount > 0;
                metadata.splitConfigs = new String[splitCount];
                for (int i = 0; i < splitCount; ++i) {
                    metadata.splitConfigs[i] = apkEntries.get(i + 1).getFileName();
                }
            }
        } catch (ApkFile.ApkFileException e) {
            e.printStackTrace();
        }
        metadata.splitConfigs = ArrayUtils.defeatNullable(metadata.splitConfigs);
        metadata.hasRules = false;
        if (mBackupFlags.backupRules()) {
            try (ComponentsBlocker cb = ComponentsBlocker.getInstance(mPackageInfo.packageName, mUserId, false)) {
                metadata.hasRules = cb.entryCount() > 0;
            }
        }
        metadata.installer = PackageManagerCompat.getInstallerPackageName(mPackageInfo.packageName, mUserId);
        return new BackupMetadataV5(backupInfo, metadata);
    }

    private void backupIcon() {
        try {
            Path iconFile = mBackupItem.getIconFile();
            try (OutputStream outputStream = iconFile.openOutputStream()) {
                Bitmap bitmap = UIUtils.getMutableBitmapFromDrawable(mApplicationInfo.loadIcon(mPm));
                BitmapRandomizer.randomizePixel(bitmap);
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not back up icon.");
        }
    }

    private void backupApkFiles(@Nullable BackupProgressListener listener) throws BackupException {
        Path dataAppPath = OsEnvironment.getDataAppDirectory();
        final String sourceBackupFilePrefix = BackupUtils.getSourceFilePrefix(getExt(mMetadata.info.tarType));
        Path sourceDir = Paths.get(PackageUtils.getSourceDir(mApplicationInfo));
        if (dataAppPath.equals(sourceDir)) {
            // APK located inside /data/app directory
            // Backup only the apk file (no split apk support for this type of apk)
            try {
                sourceDir = sourceDir.findFile(mMetadata.metadata.apkName);
            } catch (FileNotFoundException e) {
                throw new BackupException(mMetadata.metadata.apkName + " not found at " + sourceDir);
            }
        }
        Path[] sourceFiles;
        try {
            sourceFiles = TarUtils.create(mMetadata.info.tarType, sourceDir, mBackupItem.getUnencryptedBackupPath(), sourceBackupFilePrefix,
                    /* language=regexp */ new String[]{".*\\.apk"}, null, null, false).toArray(new Path[0]);
        } catch (Throwable th) {
            throw new BackupException("APK files backup is requested but no source directory has been backed up.", th);
        }
        try {
            sourceFiles = mBackupItem.encrypt(sourceFiles);
        } catch (IOException e) {
            throw new BackupException("Failed to encrypt " + Arrays.toString(sourceFiles), e);
        }
        reportBytes(listener, sourceFiles);
        for (Path file : sourceFiles) {
            mChecksum.add(file.getName(), DigestUtils.getHexDigest(mMetadata.info.checksumAlgo, file));
        }
    }

    private void backupData(@Nullable BackupProgressListener listener) throws BackupException {
        int dirCount = mMetadata.metadata.dataDirs.length;
        for (int i = 0; i < dirCount; ++i) {
            Path[] dataFiles;
            String backupDataDir = mMetadata.metadata.dataDirs[i];
            stage(listener, backupDataDir, R.string.backup_stage_data, i + 1, dirCount);
            if (backupDataDir.equals(BackupManager.DATA_BACKUP_SPECIAL_ADB)) {
                // ADB backup
                dataFiles = backupAdb(i);
            } else {
                // Regular directory backup
                dataFiles = backupDirectory(backupDataDir, i);
            }
            reportBytes(listener, dataFiles);
            try {
                dataFiles = mBackupItem.encrypt(dataFiles);
            } catch (IOException e) {
                throw new BackupException("Failed to encrypt " + Arrays.toString(dataFiles));
            }
            for (Path file : dataFiles) {
                mChecksum.add(file.getName(), DigestUtils.getHexDigest(mMetadata.info.checksumAlgo, file));
            }
        }
    }

    @NonNull
    private Path[] backupDirectory(@NonNull String dir, int index) throws BackupException {
        String filePrefix = BackupUtils.getDataFilePrefix(index, getExt(mMetadata.info.tarType));
        try {
            return TarUtils.create(mMetadata.info.tarType, Paths.get(dir),
                            mBackupItem.getUnencryptedBackupPath(),
                            filePrefix, null, null,
                            BackupUtils.getExcludeDirs(!mBackupFlags.backupCache()), false)
                    .toArray(new Path[0]);
        } catch (Throwable th) {
            throw new BackupException("Failed to backup data directory at " + dir, th);
        }
    }

    @NonNull
    private Path[] backupAdb(int index) throws BackupException {
        try {
            String filePrefix = BackupUtils.getDataFilePrefix(index, ".ab");
            Path abFile = mBackupItem.getUnencryptedBackupPath().createNewFile(filePrefix, null);
            try (OutputStream os = abFile.openOutputStream()) {
                ParcelFileDescriptor fd = ParcelFileDescriptorUtil.pipeTo(os);
                BackupCompat.adbBackup(mUserId, fd, false, false, false,
                        false, false, false, false, true,
                        new String[]{mPackageName});
            }
            return new Path[]{abFile};
        } catch (Throwable th) {
            throw new BackupException("Failed to backup ADB data.", th);
        }
    }

    private void backupKeyStore(@Nullable BackupProgressListener listener) throws BackupException {  // Called only when the app has an keystore item
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // keystore v2 is not supported.
            Log.w(TAG, "Ignoring KeyStore backups for %s", mPackageName);
            return;
        }
        Path keyStorePath = KeyStoreUtils.getKeyStorePath(mUserId);
        try {
            Path masterKeyFile = KeyStoreUtils.getMasterKey(mUserId);
            // Master key exists, so take its checksum to verify it during the restore
            mChecksum.add(MASTER_KEY, DigestUtils.getHexDigest(mMetadata.info.checksumAlgo,
                    masterKeyFile.getContentAsString().getBytes()));
        } catch (FileNotFoundException ignore) {
        }
        // Store the KeyStore files
        Path cachePath = Paths.get(FileUtils.getCachePath());
        List<String> cachedKeyStoreFileNames = new ArrayList<>();
        List<String> keyStoreFilters = new ArrayList<>();
        for (String keyStoreFileName : KeyStoreUtils.getKeyStoreFiles(mApplicationInfo.uid, mUserId)) {
            try {
                String newFileName = Utils.replaceOnce(keyStoreFileName, String.valueOf(mApplicationInfo.uid),
                        String.valueOf(KEYSTORE_PLACEHOLDER));
                IoUtils.copy(keyStorePath.findFile(keyStoreFileName), cachePath.findOrCreateFile(newFileName, null));
                cachedKeyStoreFileNames.add(newFileName);
                keyStoreFilters.add(Pattern.quote(newFileName));
            } catch (Throwable e) {
                throw new BackupException("Could not cache " + keyStoreFileName, e);
            }
        }
        if (cachedKeyStoreFileNames.isEmpty()) {
            throw new BackupException("There were some KeyStore items but they couldn't be cached before taking a backup.");
        }
        String keyStorePrefix = KEYSTORE_PREFIX + getExt(mMetadata.info.tarType);
        Path[] backedUpKeyStoreFiles;
        try {
            backedUpKeyStoreFiles = TarUtils.create(mMetadata.info.tarType, cachePath, mBackupItem.getUnencryptedBackupPath(), keyStorePrefix,
                            keyStoreFilters.toArray(new String[0]), null, null, false)
                    .toArray(new Path[0]);
        } catch (Throwable th) {
            throw new BackupException("Could not backup KeyStore item.", th);
        }
        // Remove cache
        for (String name : cachedKeyStoreFileNames) {
            try {
                cachePath.findFile(name).delete();
            } catch (FileNotFoundException ignore) {
            }
        }
        try {
            backedUpKeyStoreFiles = mBackupItem.encrypt(backedUpKeyStoreFiles);
        } catch (IOException e) {
            throw new BackupException("Failed to encrypt " + Arrays.toString(backedUpKeyStoreFiles), e);
        }
        reportBytes(listener, backedUpKeyStoreFiles);
        for (Path file : backedUpKeyStoreFiles) {
            mChecksum.add(file.getName(), DigestUtils.getHexDigest(mMetadata.info.checksumAlgo, file));
        }
    }

    // Fork: ask the app for its own data through the sister-app contract, then fold the result
    // into this backup the same way every other file is folded in — checksummed, then encrypted,
    // then committed. The app never writes into the backup directory itself: that directory is a
    // temporary path about to be renamed, encryption is applied per known file, and checksums.txt
    // is built per known file, so a foreign file dropped in would be unencrypted, unverified, and
    // about to move.
    private void backupAppData(@Nullable BackupProgressListener listener) throws BackupException {
        Context context = ContextUtils.getContext();
        if (!io.github.muntashirakon.AppManager.appdata.AppDataContract.isSupported(context, mPackageName)) {
            // Not an error: an app that declares nothing is simply not offered.
            return;
        }
        stage(listener, null, R.string.backup_stage_app_data);
        // Fork (白い熊, +136): say what was asked for. A run-scoped choice that gets dropped on
        // the way here is otherwise invisible — the export simply uses the app's stored set and
        // the log reads exactly as it would have anyway, which is how +133's choice went missing
        // for a whole build. Nothing is logged when there is no override: the stored choice
        // applying is the ordinary case, not news.
        if (mAppDataCategories != null && listener != null) {
            listener.onItem(context.getString(R.string.appdata_categories_chosen,
                    mAppDataCategories.length), null);
        }
        File staging = new File(new File(context.getCacheDir(), "appdata"), mPackageName + ".bin");
        // [0] = the highest count seen, [1] = how many times it has started over.
        final long[] pass = {0, 0};
        try {
            AppDataTransfer.Outcome outcome = new AppDataTransfer(context).export(mPackageName, mUserId,
                    staging, mAppDataCategories, (label, current, total, unit) -> {
                        // Fork (+116): a progress tick is a leaf, not a new stage. Re-announcing
                        // the stage per tick filled the log with identical headings.
                        // Fork (白い熊, +140): except when the app takes its own counter back to
                        // the start. That is a second pass over the same data — packing, then
                        // streaming, or a verify — and from here the passes are indistinguishable
                        // because the app sends no label with these ticks. Twenty-three minutes
                        // of a counter climbing to 6 GB and starting again with nothing said
                        // about it is the case this line exists for.
                        if (current >= 0 && current < pass[0]) {
                            ++pass[1];
                            if (listener != null) {
                                listener.onItem(ContextUtils.getContext().getString(
                                        R.string.appdata_second_pass, pass[1]), null);
                            }
                        }
                        pass[0] = Math.max(current, 0);
                        CharSequence detail = progressDetail(label, current, total, unit);
                        if (listener != null && detail != null) {
                            listener.onItem(marked(detail), null);
                        }
                    }, () -> BatchOpsProgressMonitor.getInstance().isCancelled());
            if (outcome.skipped) {
                // Not a failure: every category was unticked for this app, so there is nothing to
                // write and nothing to complain about.
                if (listener != null) {
                    listener.onItem(outcome.message, null);
                }
                return;
            }
            if (!outcome.ok || outcome.header == null) {
                throw new BackupException("App-supplied data export failed: " + outcome.message);
            }
            // Fork (白い熊, +141): three more full passes over the archive, and until now every
            // one of them was silent. For a 6 GB export that is minutes each — copying it into
            // the backup directory, encrypting it, hashing it — with the log frozen on the last
            // byte count the app sent. Each is announced with the size it is about to walk, so a
            // long quiet stretch is a stated amount of work rather than a suspected hang.
            CharSequence archiveSize = OpLog.formatSize(staging.length());
            stage(listener, archiveSize, R.string.backup_stage_storing);
            checkCancelled();
            Path dataFile = mBackupItem.getAppDataFile();
            try (InputStream is = new FileInputStream(staging); OutputStream os = dataFile.openOutputStream()) {
                copyCancellable(is, os);
            }
            Path headerFile = mBackupItem.getAppDataHeaderFile();
            try (OutputStream os = headerFile.openOutputStream()) {
                os.write(outcome.header.toJson().getBytes(StandardCharsets.UTF_8));
            }
            if (isEncrypted()) {
                stage(listener, archiveSize, R.string.backup_stage_encrypting);
            }
            Path[] files = mBackupItem.encrypt(new Path[]{dataFile, headerFile});
            reportBytes(listener, files);
            stage(listener, archiveSize, R.string.backup_stage_checksumming);
            for (Path file : files) {
                mChecksum.add(file.getName(), DigestUtils.getHexDigest(mMetadata.info.checksumAlgo, file));
            }
        } catch (BackupException e) {
            throw e;
        } catch (Throwable th) {
            throw new BackupException("App-supplied data backup failed.", th);
        } finally {
            // The staging copy holds a second copy of the whole archive; never leave it behind.
            staging.delete();
        }
    }

    // Fork: real counts, never a percentage — 白い熊's standing requirement for progress.
    @Nullable
    private static CharSequence progressDetail(@Nullable String label, long current, long total,
                                               @Nullable String unit) {
        if (current < 0 || total < 0) {
            return label;
        }
        // Fork (白い熊, +132): grouped digits — see OpLog#formatCount.
        String counts = OpLog.formatCount(current) + "/" + OpLog.formatCount(total)
                + (unit != null ? " " + unit : "");
        return label != null ? label + " " + counts : counts;
    }

    private void backupExtras(@Nullable BackupProgressListener listener) throws BackupException {
        PseudoRules rules = new PseudoRules(mPackageName, mUserId);
        Path miscFile;
        try {
            miscFile = mBackupItem.getMiscFile();
        } catch (IOException e) {
            throw new BackupException("Couldn't get misc.am.tsv", e);
        }
        // Backup permissions
        @NonNull String[] permissions = ArrayUtils.defeatNullable(mPackageInfo.requestedPermissions);
        int[] permissionFlags = ArrayUtils.defeatNullable(mPackageInfo.requestedPermissionsFlags);
        List<AppOpsManagerCompat.OpEntry> opEntries = new ArrayList<>();
        try {
            List<AppOpsManagerCompat.PackageOps> packageOpsList = new AppOpsManagerCompat()
                    .getOpsForPackage(mApplicationInfo.uid, mPackageName, null);
            if (packageOpsList.size() == 1) opEntries.addAll(packageOpsList.get(0).getOps());
        } catch (Exception ignore) {
        }
        PermissionInfo info;
        int basePermissionType;
        int protectionLevels;
        int permissionCount = 0;
        for (int i = 0; i < permissions.length; ++i) {
            try {
                info = mPm.getPermissionInfo(permissions[i], 0);
                basePermissionType = PermissionInfoCompat.getProtection(info);
                protectionLevels = PermissionInfoCompat.getProtectionFlags(info);
                if (basePermissionType != PermissionInfo.PROTECTION_DANGEROUS
                        && (protectionLevels & PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) == 0) {
                    // Don't include permissions that are neither dangerous nor development
                    continue;
                }
                boolean isGranted = (permissionFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                int permFlags;
                if (SelfPermissions.checkGetGrantRevokeRuntimePermissions()) {
                    permFlags = PermissionCompat.getPermissionFlags(info.name, mPackageName, mUserId);
                } else permFlags = PermissionCompat.FLAG_PERMISSION_NONE;
                rules.setPermission(permissions[i], isGranted, permFlags);
                ++permissionCount;
            } catch (PackageManager.NameNotFoundException ignore) {
            }
        }
        // Backup app ops
        for (AppOpsManagerCompat.OpEntry entry : opEntries) {
            rules.setAppOp(entry.getOp(), entry.getMode());
        }
        // Fork (+116): say what was actually collected. "Extras" alone says nothing about
        // whether anything was found, and an app with no recordable permission looks identical
        // to one whose collection failed.
        if (listener != null) {
            listener.onItem(ContextUtils.getContext().getString(R.string.backup_item_permissions,
                    permissionCount, opEntries.size()), null);
        }
        // Fork (白い熊, +109): MagiskHide and the Magisk DenyList are no longer collected. Both
        // are root-only features of a root manager this phone does not have, so every backup was
        // paying two package queries to record nothing.
        // Backup allowed notification listeners aka BIND_NOTIFICATION_LISTENER_SERVICE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && SelfPermissions.checkNotificationListenerAccess()) {
            try {
                INotificationManager notificationManager = INotificationManager.Stub.asInterface(ProxyBinder.getService(Context.NOTIFICATION_SERVICE));
                List<ComponentName> notificationComponents = notificationManager.getEnabledNotificationListeners(mUserId);
                List<String> componentsForThisPkg = new ArrayList<>();
                for (ComponentName componentName : notificationComponents) {
                    if (mPackageName.equals(componentName.getPackageName())) {
                        componentsForThisPkg.add(componentName.getClassName());
                    }
                }
                for (String component : componentsForThisPkg) {
                    rules.setNotificationListener(component, true);
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        // Backup battery optimization
        boolean batteryOptimized = DeviceIdleManagerCompat.isBatteryOptimizedApp(mPackageName);
        if (!batteryOptimized) {
            rules.setBatteryOptimization(false);
        }
        // Backup net policy
        if (SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.MANAGE_NETWORK_POLICY)) {
            int policies = ExUtils.requireNonNullElse(() -> NetworkPolicyManagerCompat.getUidPolicy(mApplicationInfo.uid), 0);
            if (policies > 0) {
                // Store only if there is a policy
                rules.setNetPolicy(policies);
            }
        }
        // Backup URI grants
        List<UriManager.UriGrant> uriGrants = new UriManager().getGrantedUris(mPackageName);
        if (uriGrants != null) {
            for (UriManager.UriGrant uriGrant : uriGrants) {
                if (uriGrant.targetUserId == mUserId) {
                    rules.setUriGrant(uriGrant);
                }
            }
        }
        // Fork (白い熊, +109): the SSAID is no longer collected. It lives in
        // /data/system/users/<id>/settings_ssaid.xml, which is -rw------- system:system — measured
        // unreadable as the shell, so under Shizuku this only ever logged a failure.
        // Backup freezeType
        Integer freezeType = FreezeUtils.loadFreezeMethod(mPackageName);
        if (freezeType != null) {
            rules.setFreezeType(freezeType);
        }
        // Commit
        rules.commitExternal(miscFile);
        if (!miscFile.exists()) return;
        try {
            miscFile = mBackupItem.encrypt(new Path[]{miscFile})[0];
            // Store checksum
            mChecksum.add(miscFile.getName(), DigestUtils.getHexDigest(mMetadata.info.checksumAlgo, miscFile));
        } catch (IOException | IndexOutOfBoundsException e) {
            throw new BackupException("Couldn't get misc.am.tsv for generating checksum", e);
        }
    }

    private void backupRules() throws BackupException {
        try {
            Path rulesFile = mBackupItem.getRulesFile();
            try (OutputStream outputStream = rulesFile.openOutputStream();
                 ComponentsBlocker cb = ComponentsBlocker.getInstance(mPackageName, mUserId)) {
                ComponentUtils.storeRules(outputStream, cb.getAll(), true);
            }
            if (!rulesFile.exists()) return;
            rulesFile = mBackupItem.encrypt(new Path[]{rulesFile})[0];
            // Store checksum
            mChecksum.add(rulesFile.getName(), DigestUtils.getHexDigest(mMetadata.info.checksumAlgo, rulesFile));
        } catch (IOException | IndexOutOfBoundsException e) {
            throw new BackupException("Rules backup is requested but encountered an error during fetching rules.", e);
        }
    }
}
