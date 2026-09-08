// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.backup.dialog;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.UserHandleHidden;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckedTextView;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.format.Formatter;
import android.view.ViewGroup;
import androidx.appcompat.widget.AppCompatTextView;
import java.util.Locale;
import io.github.muntashirakon.AppManager.backup.BackupUtils;
import io.github.muntashirakon.AppManager.backup.CryptoUtils;
import io.github.muntashirakon.AppManager.main.RowPills;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;
import io.github.muntashirakon.AppManager.utils.LangUtils;
import io.github.muntashirakon.AppManager.appdata.AppDataHeader;
import io.github.muntashirakon.AppManager.backup.BackupItems;
import io.github.muntashirakon.AppManager.main.ShareBackupHandler;
import io.github.muntashirakon.io.Path;
import androidx.annotation.StringRes;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import androidx.core.graphics.ColorUtils;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.resources.MaterialAttributes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.appdata.AppDataCategory;
import io.github.muntashirakon.AppManager.appdata.AppDataContract;
import io.github.muntashirakon.AppManager.appdata.AppDataSelection;
import io.github.muntashirakon.AppManager.appdata.AppDataTransfer;
import io.github.muntashirakon.AppManager.backup.BackupFlags;
import io.github.muntashirakon.AppManager.backup.struct.BackupMetadataV5;
import io.github.muntashirakon.AppManager.batchops.BatchOpsManager;
import io.github.muntashirakon.AppManager.batchops.BatchOpsService;
import io.github.muntashirakon.AppManager.batchops.BatchQueueItem;
import io.github.muntashirakon.AppManager.batchops.struct.BatchBackupOptions;
import io.github.muntashirakon.AppManager.settings.Prefs;
import io.github.muntashirakon.AppManager.types.UserPackagePair;
import io.github.muntashirakon.AppManager.users.UserInfo;
import io.github.muntashirakon.AppManager.utils.ArrayUtils;
import io.github.muntashirakon.AppManager.utils.ContextUtils;
import io.github.muntashirakon.AppManager.utils.DateUtils;
import io.github.muntashirakon.AppManager.utils.ForkDialog;
import io.github.muntashirakon.AppManager.utils.StoragePermission;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;
import io.github.muntashirakon.dialog.SearchableMultiChoiceDialogBuilder;
import io.github.muntashirakon.dialog.TextInputDialogBuilder;

/**
 * Fork: every backup action for ONE app, in one dialog (白い熊, 2026-08-02).
 * <p>
 * The main list's backup column used to long-press into a two-line chooser -
 * "Backup" and "Restore or delete…" - where the second entry opened the
 * {@link BackupRestoreDialogFragment} bottom sheet and the first was a bare list
 * line pretending to be an action. This dialog is both of those at once: the
 * app's existing backups as a tick list, {@code Restore} / {@code Delete} /
 * freeze acting on what is ticked, and {@code Back up} as the dialog's own
 * positive button. Nothing drills down any more.
 * <p>
 * It reuses {@link BackupRestoreDialogViewModel} - the same loader, the same
 * {@code prepareForOperation} path, the same {@link BatchOpsService} hand-off -
 * so backup/restore/delete behave identically to the bottom sheet, which stays
 * in place for batch selections and for app details.
 * <p>
 * Label formatting is deliberately done off the main thread:
 * {@link BackupMetadataV5#toLocalizedString(Context)} is {@code @WorkerThread}
 * because it walks the backup directory to size it.
 */
public class AppBackupDialogFragment extends DialogFragment {
    /** Fork (+121): what marks a line as belonging to the part above it. */
    private static final String SUB_LINE_INDENT = "      ";
    /**
     * Above this the archive is not measured per category. Counting means reading every byte
     * (see {@link #measureAppDataCategories}), and half a gigabyte of it to label four lines is
     * not a trade worth making while somebody waits for a dialog.
     */
    private static final long APP_DATA_MEASURE_LIMIT = 512L * 1024 * 1024;
    public static final String TAG = AppBackupDialogFragment.class.getSimpleName();

    private static final String ARG_PACKAGE_NAME = "pkg";
    private static final String ARG_USER_ID = "user";
    private static final String ARG_LABEL = "label";

    @NonNull
    public static AppBackupDialogFragment getInstance(@NonNull String packageName, int userId,
                                                      @Nullable CharSequence label) {
        AppBackupDialogFragment fragment = new AppBackupDialogFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PACKAGE_NAME, packageName);
        args.putInt(ARG_USER_ID, userId);
        args.putCharSequence(ARG_LABEL, label);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    private BackupRestoreDialogFragment.ActionCompleteInterface mActionCompleteInterface;
    @Nullable
    private BackupRestoreDialogFragment.ActionBeginInterface mActionBeginInterface;
    @BackupRestoreDialogFragment.ActionMode
    private int mMode = BackupRestoreDialogFragment.MODE_BACKUP;

    private FragmentActivity mActivity;
    private BackupRestoreDialogViewModel mViewModel;
    private AlertDialog mDialog;
    private Context mDialogContext;
    // Fork: kept for the app-data category picker, which needs both after onCreateDialog.
    private String mPackageName;
    private int mUserId;
    private TextView mMessageView;
    private View mListContainer;
    // Fork (+119): "Existing backups" — the list needs to say what it is a list OF.
    @Nullable
    private android.widget.TextView mBackupsHeader;
    private LinearLayout mBackupListView;
    private View mActionsView;
    private MaterialButton mRestoreButton;
    private MaterialButton mDeleteButton;
    private MaterialButton mShareButton;
    private int mMultiChoiceItemLayout;

    /** The app's backups, newest state as last loaded. Index = position in the tick list. */
    private final List<BackupMetadataV5> mBackups = new ArrayList<>();
    /** Ticked positions in {@link #mBackups}. */
    private final Set<Integer> mSelected = new LinkedHashSet<>();
    private boolean mInstalled;
    private boolean mLoaded;

    private final StoragePermission mStoragePermission = StoragePermission.init(this);
    private final BroadcastReceiver mBatchOpsBroadCastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mActionCompleteInterface != null) {
                ArrayList<String> failedPackages = intent.getStringArrayListExtra(BatchOpsService.EXTRA_FAILED_PKG);
                mActionCompleteInterface.onActionComplete(mMode, failedPackages != null
                        ? failedPackages.toArray(new String[0]) : new String[0]);
            }
            mActivity.unregisterReceiver(mBatchOpsBroadCastReceiver);
        }
    };

    public void setOnActionCompleteListener(@NonNull BackupRestoreDialogFragment.ActionCompleteInterface listener) {
        mActionCompleteInterface = listener;
    }

    public void setOnActionBeginListener(@NonNull BackupRestoreDialogFragment.ActionBeginInterface listener) {
        mActionBeginInterface = listener;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mActivity = requireActivity();
        mStoragePermission.request();
    }

    @SuppressLint("RestrictedApi")
    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        mViewModel = new ViewModelProvider(this).get(BackupRestoreDialogViewModel.class);
        Bundle args = requireArguments();
        String packageName = Objects.requireNonNull(args.getString(ARG_PACKAGE_NAME));
        int userId = args.getInt(ARG_USER_ID, UserHandleHidden.myUserId());
        mPackageName = packageName;
        mUserId = userId;
        CharSequence label = args.getCharSequence(ARG_LABEL);

        MaterialAlertDialogBuilder builder = ForkDialog.builder(requireContext());
        // The builder's context carries the yellow-on-black dialog overlay; the
        // fragment's does not. Everything below must be inflated from it or the
        // pills and tick marks come out in the activity's colours.
        mDialogContext = builder.getContext();
        mMultiChoiceItemLayout = MaterialAttributes.resolveInteger(mDialogContext,
                androidx.appcompat.R.attr.multiChoiceItemLayout,
                com.google.android.material.R.layout.mtrl_alert_select_dialog_multichoice);

        View body = LayoutInflater.from(mDialogContext).inflate(R.layout.dialog_app_backup, null);
        mMessageView = body.findViewById(R.id.message);
        mBackupsHeader = body.findViewById(R.id.backups_header);
        mListContainer = body.findViewById(R.id.list_container);
        mBackupListView = body.findViewById(R.id.backup_list);
        mActionsView = body.findViewById(R.id.backup_actions);
        mRestoreButton = body.findViewById(R.id.action_restore);
        mDeleteButton = body.findViewById(R.id.action_delete);
        mShareButton = body.findViewById(R.id.action_share);
        mRestoreButton.setOnClickListener(v -> {
            List<BackupMetadataV5> selected = getSelectedBackups();
            if (selected.size() == 1) {
                handleRestore(selected.get(0));
            }
        });
        mDeleteButton.setOnClickListener(v -> {
            List<BackupMetadataV5> selected = getSelectedBackups();
            if (!selected.isEmpty()) {
                handleDelete(selected);
            }
        });
        // Fork (白い熊): hand the chosen backup's directory to 白い熊 魔法絨毯 to carry to another
        // device. The dialog already knows which backup is meant, so this goes straight to the
        // send rather than through ShareBackupHandler's picker.
        mShareButton.setOnClickListener(v -> {
            List<BackupMetadataV5> selected = getSelectedBackups();
            if (selected.size() == 1) {
                shareBackup(selected.get(0));
            }
        });

        // Loading is usually a blink, but it reads the backup metadata off disk,
        // so say so rather than showing an empty box.
        mMessageView.setText(R.string.loading);
        mMessageView.setVisibility(View.VISIBLE);

        builder.setTitle(!TextUtils.isEmpty(label) ? label : packageName)
                .setView(body)
                .setNegativeButton(R.string.cancel, null)
                // Wired up in onStart(): the click must NOT dismiss - the view
                // model that carries the operation dies with this fragment.
                .setPositiveButton(R.string.back_up, null);
        mDialog = ForkDialog.bordered(builder.create());

        mViewModel.getBackupInfoStateLiveData().observe(this, state -> onInfoLoaded());
        mViewModel.getBackupOperationLiveData().observe(this, this::startOperation);
        mViewModel.getUserSelectionLiveData().observe(this, this::handleCustomUsers);
        if (savedInstanceState == null) {
            mViewModel.processPackages(Collections.singletonList(new UserPackagePair(packageName, userId)));
        }
        return mDialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        View backUpButton = mDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (backUpButton != null) {
            backUpButton.setOnClickListener(v -> handleBackupClicked());
        }
        updateBackUpButton();
    }

    // ------------------------------------------------------------------
    // Loading & rendering
    // ------------------------------------------------------------------

    @UiThread
    private void onInfoLoaded() {
        mLoaded = true;
        List<BackupInfo> backupInfoList = mViewModel.getBackupInfoList();
        if (backupInfoList.isEmpty()) {
            // Neither installed nor backed up - the view model dropped it entirely.
            mInstalled = false;
            mBackups.clear();
            mSelected.clear();
            renderRows(new ArrayList<>());
            mMessageView.setText(R.string.backup_dialog_unavailable);
            mMessageView.setVisibility(View.VISIBLE);
            updateBackUpButton();
            return;
        }
        BackupInfo backupInfo = backupInfoList.get(0);
        mInstalled = backupInfo.isInstalled();
        mDialog.setTitle(backupInfo.getAppLabel());
        mBackups.clear();
        mBackups.addAll(backupInfo.getBackupMetadataList());
        mSelected.clear();
        for (int i = 0; i < mBackups.size(); ++i) {
            // Same default as the bottom sheet: the base backup is the one you
            // almost always mean.
            if (mBackups.get(i).isBaseBackup()) {
                mSelected.add(i);
            }
        }
        updateBackUpButton();
        reloadRows();
    }

    /** Format the row labels off the main thread, then rebuild the tick list. */
    private void reloadRows() {
        if (mBackups.isEmpty()) {
            renderRows(new ArrayList<>());
            return;
        }
        List<BackupMetadataV5> snapshot = new ArrayList<>(mBackups);
        ThreadUtils.postOnBackgroundThread(() -> {
            List<CharSequence[]> rows = formatRows(snapshot);
            ThreadUtils.postOnMainThread(() -> {
                if (!isAdded()) return;
                renderRows(rows);
            });
        });
    }

    /**
     * Fork (白い熊, +119/+120): each backup as separate lines, and each PART of it named with
     * its own size.
     *
     * <p>{@code toLocalizedString} joined everything with commas under a "Base backup" heading:
     * one run-on sentence whose most useful facts — what is actually in this archive, and how
     * much of it is the APK versus the data — were not in it at all. "APK+Ext+AppData+OBB" tells
     * you which boxes were ticked; it does not tell you that the APK is 20 MB and the app's own
     * data is 25 MB, which is what you are choosing between when there are several.
     *
     * <p>Sizes are read from the files on disk rather than from the metadata: what is there is
     * what you would get back. Worker thread, because that walks the backup directory.
     */
    @WorkerThread
    @NonNull
    private List<CharSequence[]> formatRows(@NonNull List<BackupMetadataV5> backups) {
        List<CharSequence[]> rows = new ArrayList<>(backups.size());
        for (BackupMetadataV5 backup : backups) {
            List<CharSequence> lines = new ArrayList<>();
            // 1. When — the line people pick a backup by.
            CharSequence when = DateUtils.formatDateTime(mDialogContext, backup.info.backupTime);
            if (!TextUtils.isEmpty(backup.metadata.backupName) && !backup.isBaseBackup()) {
                when = backup.metadata.backupName + "  ·  " + when;
            }
            lines.add(when);
            // 2. What is inside it, part by part, each with its size.
            lines.addAll(partLines(backup));
            // 3. Which version, and whose.
            lines.add(mDialogContext.getString(R.string.version) + LangUtils.getSeparatorString()
                    + backup.metadata.versionName + "   "
                    + mDialogContext.getString(R.string.user_id) + LangUtils.getSeparatorString()
                    + backup.info.userId);
            // 4. How it is stored, and how big the whole thing is.
            StringBuilder stored = new StringBuilder();
            if (CryptoUtils.MODE_NO_ENCRYPTION.equals(backup.info.crypto)) {
                stored.append(mDialogContext.getString(R.string.no_encryption));
            } else {
                stored.append(mDialogContext.getString(R.string.pgp_aes_rsa_encrypted,
                        backup.info.crypto.toUpperCase(Locale.ROOT)));
            }
            stored.append("   ").append(Formatter.formatFileSize(mDialogContext,
                    backup.info.getBackupSize()));
            if (backup.info.isFrozen()) {
                stored.append("   ").append(mDialogContext.getText(R.string.frozen));
            }
            lines.add(stored);
            rows.add(lines.toArray(new CharSequence[0]));
        }
        return rows;
    }

    /**
     * One line per part that is actually present: APK, each data directory, the app's own data,
     * the KeyStore, rules and extras — with what each occupies.
     *
     * <p>A part with no files is not listed. The flags say what was <em>asked</em> for; these
     * lines say what is <em>there</em>, and for an app that had nothing to back up in a category
     * those are not the same thing.
     */
    @WorkerThread
    @NonNull
    private List<CharSequence> partLines(@NonNull BackupMetadataV5 backup) {
        List<CharSequence> lines = new ArrayList<>();
        final int ink = ForkThemeUtils.getTextColor();
        BackupItems.BackupItem item = backup.info.getBackupItem();
        if (item == null) {
            // No handle on the files (an unreadable or half-written backup): fall back to the
            // flag list, which is at least true about the intent.
            lines.add(backup.info.flags.toLocalisedString(mDialogContext));
            return lines;
        }
        addPart(lines, R.string.backup_part_apk, colorOf(BackupFlags.BACKUP_APK_FILES, ink),
                sizeOf(item.getSourceFiles()), null);
        String[] dataDirs = backup.metadata.dataDirs;
        if (dataDirs != null) {
            long dataSize = 0;
            for (int i = 0; i < dataDirs.length; ++i) {
                dataSize += sizeOf(item.getDataFiles(i));
            }
            addPart(lines, R.string.backup_part_data, colorOf(BackupFlags.BACKUP_INT_DATA, ink),
                    dataSize, dataDirs.length > 1
                            ? mDialogContext.getString(R.string.backup_part_dirs, dataDirs.length) : null);
        }
        try {
            long appData = sizeOf(new Path[]{item.getAppDataFile()});
            addPart(lines, R.string.backup_part_app_data,
                    colorOf(BackupFlags.BACKUP_APP_DATA, ink), appData, null);
            // What the app actually put in there, one indented line each. The archive is a
            // single opaque blob the app wrote, so there is no size per category to report and
            // none is invented — the names and the format are what the header knows.
            lines.addAll(appDataSubLines(item));
        } catch (Throwable ignore) {
        }
        addPart(lines, R.string.keystore, ink, sizeOf(item.getKeyStoreFiles()), null);
        try {
            addPart(lines, R.string.backup_part_rules, colorOf(BackupFlags.BACKUP_RULES, ink),
                    sizeOf(new Path[]{item.getRulesFile()}), null);
        } catch (Throwable ignore) {
        }
        try {
            addPart(lines, R.string.backup_part_extras, colorOf(BackupFlags.BACKUP_EXTRAS, ink),
                    sizeOf(new Path[]{item.getMiscFile()}), null);
        } catch (Throwable ignore) {
        }
        if (lines.isEmpty()) {
            lines.add(backup.info.flags.toLocalisedString(mDialogContext));
        }
        return lines;
    }

    /**
     * Fork (白い熊, +121): a part line carries its part's COLOUR.
     *
     * <p>Every surface that shows what a backup is made of uses the same palette — the options
     * chooser, the batch table and this list — so the APK is always the same blue and the app's
     * own data always the same green. A column of identical yellow lines made you read every one
     * to find the part you cared about; a colour is read without reading.
     *
     * <p>The span wins over the row renderer's {@code setTextColor}, which is exactly why the
     * colour is attached here rather than passed alongside.
     */
    private void addPart(@NonNull List<CharSequence> lines, @StringRes int labelRes, int color,
                         long size, @Nullable CharSequence detail) {
        if (size <= 0) {
            return;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int start = sb.length();
        sb.append(mDialogContext.getString(labelRes));
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        start = sb.length();
        sb.append("   ").append(Formatter.formatFileSize(mDialogContext, size));
        if (!TextUtils.isEmpty(detail)) {
            sb.append("   ").append(detail);
        }
        sb.setSpan(new ForegroundColorSpan(ColorUtils.setAlphaComponent(color, 0xC0)), start,
                sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        lines.add(sb);
    }

    /** The colour a part is drawn in, from the one palette. */
    private static int colorOf(int flag, int fallback) {
        for (BackupParts.Part part : BackupParts.contentParts()) {
            if (part.flag == flag) {
                return part.color;
            }
        }
        return fallback;
    }

    /**
     * The app-supplied archive's contents, indented under it — <b>each with its own size</b>.
     *
     * <p>The sizes are real, not apportioned. The archive an app hands over is a zip, and the
     * entries inside it are named after the categories the app exported: {@code accounts/…},
     * {@code ui.json}, {@code app_settings.json}. Its {@code manifest.json} lists those category
     * ids in the same order as the header's human labels, so each label can be given the bytes
     * that actually belong to it.
     *
     * <p><b>LANDMINE — the sizes are not in the stream's entry headers.</b> The apps write with a
     * data descriptor (general-purpose bit 3), which means {@code ZipEntry.getSize()} answers −1
     * until the entry has been read. So each entry is drained to count it; that is a read of the
     * whole archive, which is why this is worker-thread only and skipped above
     * {@link #APP_DATA_MEASURE_LIMIT}.
     */
    @WorkerThread
    @NonNull
    private List<CharSequence> appDataSubLines(@NonNull BackupItems.BackupItem item) {
        List<CharSequence> lines = new ArrayList<>();
        AppDataHeader header;
        try {
            header = AppDataHeader.parse(item.getAppDataHeaderFile().getContentAsString(null));
        } catch (Throwable th) {
            // Encrypted, or written by a build that did not store one.
            return lines;
        }
        if (header == null) {
            return lines;
        }
        int appDataColor = colorOf(BackupFlags.BACKUP_APP_DATA, ForkThemeUtils.getTextColor());
        Map<String, Long> sizes = measureAppDataCategories(item);
        List<String> ids = sizes.isEmpty() ? Collections.emptyList()
                : new ArrayList<>(sizes.keySet());
        for (int i = 0; i < header.contains.size(); ++i) {
            SpannableStringBuilder line = new SpannableStringBuilder(SUB_LINE_INDENT);
            line.append(header.contains.get(i));
            // The ids and the labels are parallel lists in the same order — that is the contract
            // the exporters are built to. When they are not, the label still stands on its own
            // and only the size is missing.
            Long size = i < ids.size() ? sizes.get(ids.get(i)) : null;
            if (size != null && size > 0) {
                int start = line.length();
                line.append("   ").append(Formatter.formatFileSize(mDialogContext, size));
                line.setSpan(new ForegroundColorSpan(appDataColor), start, line.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            lines.add(line);
        }
        // What wrote it and what can read it — the archive's own version, which is the thing that
        // decides whether a restore into a future build will be accepted.
        StringBuilder about = new StringBuilder(SUB_LINE_INDENT)
                .append(mDialogContext.getString(R.string.backup_part_written_by,
                        TextUtils.isEmpty(header.versionName) ? "?" : header.versionName,
                        header.format));
        lines.add(about);
        return lines;
    }

    /**
     * Bytes per category id, in the archive's own order. Empty when the archive cannot be read as
     * a zip — an encrypted one, or an app that writes something else entirely.
     */
    @WorkerThread
    @NonNull
    private Map<String, Long> measureAppDataCategories(@NonNull BackupItems.BackupItem item) {
        Map<String, Long> sizes = new LinkedHashMap<>();
        Path file;
        try {
            file = item.getAppDataFile();
            if (file.length() > APP_DATA_MEASURE_LIMIT) {
                return sizes;
            }
        } catch (Throwable th) {
            return sizes;
        }
        List<String> categories = new ArrayList<>();
        Map<String, Long> byEntry = new LinkedHashMap<>();
        byte[] buffer = new byte[8192];
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(file.openInputStream()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                long counted = 0;
                int read;
                while ((read = zis.read(buffer)) > 0) {
                    counted += read;
                }
                byEntry.put(entry.getName(), counted);
            }
        } catch (Throwable th) {
            return sizes;
        }
        // The manifest names the categories and their order. A second pass just for it is cheap:
        // it is a few hundred bytes and the stream is sequential.
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(file.openInputStream()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!"manifest.json".equals(entry.getName())) {
                    continue;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int read;
                while ((read = zis.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                JSONArray array = new JSONObject(out.toString("UTF-8")).optJSONArray("categories");
                if (array != null) {
                    for (int i = 0; i < array.length(); ++i) {
                        String id = array.optString(i, null);
                        if (id != null) categories.add(id);
                    }
                }
                break;
            }
        } catch (Throwable ignore) {
        }
        for (String id : categories) {
            long total = 0;
            for (Map.Entry<String, Long> e : byEntry.entrySet()) {
                String name = e.getKey();
                // An entry belongs to a category when it IS that category — "ui.json" — or lives
                // under it — "accounts/2.tar". Prefix alone would let "app_settings" swallow
                // "app_settings_old".
                if (name.equals(id) || name.startsWith(id + "/") || name.startsWith(id + ".")) {
                    total += e.getValue();
                }
            }
            sizes.put(id, total);
        }
        return sizes;
    }

    @WorkerThread
    private static long sizeOf(@Nullable Path[] files) {
        if (files == null) {
            return 0;
        }
        long total = 0;
        for (Path file : files) {
            if (file == null) continue;
            try {
                total += file.length();
            } catch (Throwable ignore) {
            }
        }
        return total;
    }

    /**
     * Fork (白い熊, +119): one bordered box per backup, in the fork's own pill language.
     *
     * <p>The multi-choice row this replaced was a single line of text on black — nothing marked
     * where one backup ended and the next began, and a list you choose from has to be a list of
     * THINGS. Each box now carries its own frame; a ticked one is filled with a faint wash of the
     * theme colour, so the selection is visible without hunting for a check mark.
     */
    @UiThread
    private void renderRows(@NonNull List<CharSequence[]> rows) {
        mBackupListView.removeAllViews();
        boolean hasBackups = !rows.isEmpty();
        mListContainer.setVisibility(hasBackups ? View.VISIBLE : View.GONE);
        mActionsView.setVisibility(hasBackups ? View.VISIBLE : View.GONE);
        if (mBackupsHeader != null) {
            mBackupsHeader.setVisibility(hasBackups ? View.VISIBLE : View.GONE);
        }
        if (!hasBackups) {
            if (mLoaded && mInstalled) {
                mMessageView.setText(R.string.backup_dialog_no_backups);
                mMessageView.setVisibility(View.VISIBLE);
            }
            return;
        }
        mMessageView.setVisibility(View.GONE);
        int ink = ForkThemeUtils.getTextColor();
        float density = mDialogContext.getResources().getDisplayMetrics().density;
        for (int i = 0; i < rows.size(); ++i) {
            final int position = i;
            CharSequence[] lines = rows.get(i);
            LinearLayout box = new LinearLayout(mDialogContext);
            box.setOrientation(LinearLayout.VERTICAL);
            int padH = Math.round(12 * density);
            int padV = Math.round(9 * density);
            box.setPadding(padH, padV, padH, padV);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Math.round(8 * density);
            box.setLayoutParams(lp);
            for (int line = 0; line < lines.length; ++line) {
                AppCompatTextView view = new AppCompatTextView(mDialogContext);
                view.setText(lines[line]);
                if (line == 0) {
                    view.setTextSize(15f);
                    view.setTypeface(Typeface.DEFAULT_BOLD);
                    view.setTextColor(ink);
                } else {
                    view.setTextSize(12f);
                    // The contents line is the one you compare backups by, so it keeps full
                    // strength; the rest are supporting facts and step back.
                    view.setTextColor(line == 1 ? ink : RowPills.withAlpha(ink, 0.65f));
                }
                box.addView(view);
            }
            applyBackupBoxStyle(box, ink, mSelected.contains(position));
            box.setOnClickListener(v -> {
                if (!mSelected.remove(position)) {
                    mSelected.add(position);
                }
                applyBackupBoxStyle(box, ink, mSelected.contains(position));
                updateSelectionActions();
            });
            mBackupListView.addView(box);
        }
        updateSelectionActions();
    }

    /** Outline when untouched, a faint wash of the theme colour when ticked. */
    private void applyBackupBoxStyle(@NonNull View box, int ink, boolean selected) {
        float density = mDialogContext.getResources().getDisplayMetrics().density;
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(14 * density);
        shape.setColor(selected ? RowPills.withAlpha(ink, 0.16f) : Color.TRANSPARENT);
        shape.setStroke(Math.max(1, Math.round((selected ? 2f : 1.2f) * density)),
                RowPills.withAlpha(ink, selected ? 1f : 0.5f));
        box.setBackground(shape);
    }

    @UiThread
    private void updateSelectionActions() {
        int count = mSelected.size();
        // The style pins its colours to literals, so a disabled MaterialButton
        // would look identical to an enabled one - dim it by hand.
        setActionEnabled(mRestoreButton, count == 1);
        setActionEnabled(mDeleteButton, count > 0);
        setActionEnabled(mShareButton, count == 1);
    }

    private static void setActionEnabled(@NonNull MaterialButton button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.4f);
    }

    /** "Back up" only means something for an installed app. */
    @UiThread
    private void updateBackUpButton() {
        if (mDialog == null) return;
        View backUpButton = mDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (backUpButton == null) return;
        backUpButton.setVisibility(mLoaded && mInstalled ? View.VISIBLE : View.GONE);
    }

    @NonNull
    private List<BackupMetadataV5> getSelectedBackups() {
        List<BackupMetadataV5> selected = new ArrayList<>(mSelected.size());
        for (int position : mSelected) {
            if (position < mBackups.size()) {
                selected.add(mBackups.get(position));
            }
        }
        return selected;
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /**
     * Back up. With "Skip backup method dialog" on this starts immediately with
     * the stored defaults, exactly as the bottom sheet's backup-only path does;
     * otherwise it asks for the options first.
     */
    private void handleBackupClicked() {
        if (!mLoaded || !mInstalled) {
            return;
        }
        // Fork (白い熊, +120): this dialog ALWAYS asks what goes into the backup.
        //
        // "Skip backup method dialog" exists for a batch of five hundred apps, where being asked
        // once per app would be unusable. Here you have opened one app on purpose and pressed
        // Back up on purpose, and choosing what to include is the thing you came to do — so the
        // preference is deliberately not consulted on this path. The batch flow
        // (BackupRestoreDialogFragment) still honours it.
        BackupFlags flags = BackupFlags.fromPref();
        int supportedFlags = BackupFlags.getSupportedBackupFlags();
        supportedFlags &= ~BackupFlags.BACKUP_NO_SIGNATURE_CHECK;
        if (!mViewModel.allowCustomUsersInBackup()) {
            supportedFlags &= ~BackupFlags.BACKUP_CUSTOM_USERS;
        }
        // Fork (白い熊, +126): "Back up multiple" is offered again now that it no longer stops to
        // ask for a name (+125 hid it because it did). Ticked, it writes a second backup stamped
        // with the date and time beside the base one; unticked, it replaces the base one.
        // Fork (白い熊, +121): coloured part rows rather than a column of identical checkbox
        // labels — see BackupPartRows. Each part keeps its colour everywhere it appears.
        BackupPartRows.showOptions(mDialogContext, supportedFlags, flags.getFlags(),
                mPackageName, mUserId, (newFlags, categories) -> {
            BackupFlags chosen = new BackupFlags(newFlags);
            // Fork (白い熊, +145): "Back up multiple" is a decision about THIS backup and is not
            // written back to the preference. Ticking it once meant every later backup of every
            // app quietly kept its predecessor instead of replacing it — the backup directory
            // filling up from one tap made for one app. The other parts still persist: they say
            // what a backup is made of, which is a standing preference, while this one says
            // whether to keep the last one, which is a decision you make in front of an app.
            // Settings → Backup/restore → Backup options remains the one place that sets it.
            int persisted = (chosen.getFlags() & ~BackupFlags.BACKUP_MULTIPLE)
                    | (Prefs.BackupRestore.getBackupFlags() & BackupFlags.BACKUP_MULTIPLE);
            Prefs.BackupRestore.setBackupFlags(persisted);
            handleBackup(chosen, categories);
        });
    }

    private void handleBackup(@NonNull BackupFlags flags) {
        handleBackup(flags, null);
    }

    /**
     * @param appDataCategories what the category picker chose for <b>this</b> backup, or
     *                          {@code null} to use the app's stored choice (白い熊, +133)
     */
    private void handleBackup(@NonNull BackupFlags flags, @Nullable List<String> appDataCategories) {
        BackupRestoreDialogViewModel.OperationInfo operationInfo = new BackupRestoreDialogViewModel.OperationInfo();
        operationInfo.mode = BackupRestoreDialogFragment.MODE_BACKUP;
        operationInfo.flags = flags.getFlags();
        operationInfo.op = BatchOpsManager.OP_BACKUP;
        if (appDataCategories != null) {
            operationInfo.perPackageAppData = java.util.Collections.singletonMap(mPackageName,
                    appDataCategories.toArray(new String[0]));
        }
        if (flags.backupMultiple()) {
            // Fork (白い熊, +126): a named backup overwrites nothing, so there is nothing to warn
            // about — and nothing to ask, either. It is stamped with the date and time so the
            // backup directory reads chronologically when browsed from outside this app.
            operationInfo.backupNames = new String[]{BackupUtils.timestampBackupName()};
            mViewModel.prepareForOperation(operationInfo);
            return;
        }
        List<BackupInfo> backupInfoList = mViewModel.getBackupInfoList();
        boolean hasBaseBackup = !backupInfoList.isEmpty() && backupInfoList.get(0).hasBaseBackup();
        if (hasBaseBackup) {
            // A base backup exists and is about to be overwritten.
            // Fork (白い熊, +137): say what this will actually do, and where to change it.
            // "Backup already exists. Are you sure?" answered neither question — it did not say
            // that the old backup is deleted, and it did not say that "Back up multiple" is the
            // switch that would have kept both.
            ForkDialog.present(ForkDialog.builder(mDialogContext)
                    .setTitle(R.string.backup)
                    .setMessage(getString(R.string.backup_replace_warning,
                            getString(R.string.backup_multiple), getString(R.string.backup_options)))
                    .setPositiveButton(R.string.yes, (dialog, which) -> mViewModel.prepareForOperation(operationInfo))
                    .setNegativeButton(R.string.no, null));
            return;
        }
        mViewModel.prepareForOperation(operationInfo);
    }

    /**
     * Fork (白い熊, +132): choose what to put back, in the same coloured parts the backup was
     * chosen in. The flag list this replaces was the pre-+121 grey checkbox column, and the
     * decision here is the same one — only its direction differs.
     */
    private void handleRestore(@NonNull BackupMetadataV5 selectedBackup) {
        int available = selectedBackup.info.flags.getFlags();
        int checked = available & BackupFlags.fromPref().getFlags();
        int locked = 0;
        CharSequence lockedNote = null;
        if (!mInstalled && (available & BackupFlags.BACKUP_APK_FILES) != 0) {
            // Without the APK there is nothing to restore the data into.
            locked = BackupFlags.BACKUP_APK_FILES;
            lockedNote = getString(R.string.restore_apk_forced);
        }
        BackupPartRows.showRestoreOptions(mDialogContext, available, checked, locked, lockedNote,
                BackupFlags.BACKUP_NO_SIGNATURE_CHECK | BackupFlags.BACKUP_CUSTOM_USERS,
                newFlags -> {
                    BackupRestoreDialogViewModel.OperationInfo operationInfo =
                            new BackupRestoreDialogViewModel.OperationInfo();
                    operationInfo.mode = BackupRestoreDialogFragment.MODE_RESTORE;
                    operationInfo.op = BatchOpsManager.OP_RESTORE_BACKUP;
                    operationInfo.flags = newFlags;
                    operationInfo.relativeDirs = new String[]{selectedBackup.info.getRelativeDir()};
                    mViewModel.prepareForOperation(operationInfo);
                });
    }

    private void handleDelete(@NonNull List<BackupMetadataV5> selectedBackups) {
        ForkDialog.present(ForkDialog.builder(mDialogContext)
                .setTitle(R.string.delete_backup)
                .setMessage(R.string.are_you_sure)
                .setNegativeButton(R.string.no, null)
                .setPositiveButton(R.string.yes, (dialog, which) -> {
                    List<String> relativeDirs = new ArrayList<>(selectedBackups.size());
                    for (BackupMetadataV5 backup : selectedBackups) {
                        relativeDirs.add(backup.info.getRelativeDir());
                    }
                    BackupRestoreDialogViewModel.OperationInfo operationInfo =
                            new BackupRestoreDialogViewModel.OperationInfo();
                    operationInfo.mode = BackupRestoreDialogFragment.MODE_DELETE;
                    operationInfo.op = BatchOpsManager.OP_DELETE_BACKUP;
                    operationInfo.relativeDirs = relativeDirs.toArray(new String[0]);
                    mViewModel.prepareForOperation(operationInfo);
                }));
    }

    /**
     * Fork (白い熊): hand this backup's directory to 白い熊 魔法絨毯, which carries it to another
     * device. The whole directory travels, because a backup is only restorable inside its own
     * folder — see {@link ShareBackupHandler}.
     */
    private void shareBackup(@NonNull BackupMetadataV5 metadata) {
        BackupItems.BackupItem item = metadata.info.getBackupItem();
        if (item == null) {
            UIUtils.displayShortToast(R.string.share_backup_none);
            return;
        }
        ShareBackupHandler.sendDirectory(requireActivity(), item.getBackupPath());
    }

    // ------------------------------------------------------------------
    // Hand-off to the batch-ops service (mirrors BackupRestoreDialogFragment)
    // ------------------------------------------------------------------

    private void handleCustomUsers(@NonNull BackupRestoreDialogViewModel.OperationInfo operationInfo) {
        // NonNull check is added because we are only here when there are more than one users
        List<UserInfo> users = Objects.requireNonNull(operationInfo.userInfoList);
        CharSequence[] userNames = new String[users.size()];
        List<Integer> userHandles = new ArrayList<>(users.size());
        int i = 0;
        for (UserInfo info : users) {
            userNames[i] = info.toLocalizedString(mDialogContext);
            userHandles.add(info.id);
            ++i;
        }
        new SearchableMultiChoiceDialogBuilder<>(mDialogContext, userHandles, userNames)
                .setTitle(R.string.select_user)
                .addSelections(Collections.singletonList(UserHandleHidden.myUserId()))
                .showSelectAll(false)
                .setPositiveButton(R.string.ok, (dialog, which, selectedUsers) -> {
                    if (!selectedUsers.isEmpty()) {
                        operationInfo.selectedUsers = ArrayUtils.convertToIntArray(selectedUsers);
                    }
                    mViewModel.prepareForOperation(operationInfo);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @UiThread
    private void startOperation(@NonNull BackupRestoreDialogViewModel.OperationInfo operationInfo) {
        mMode = operationInfo.mode;
        if (mActionBeginInterface != null) {
            mActionBeginInterface.onActionBegin(operationInfo.mode);
        }
        ContextCompat.registerReceiver(mActivity, mBatchOpsBroadCastReceiver,
                new IntentFilter(BatchOpsService.ACTION_BATCH_OPS_COMPLETED), ContextCompat.RECEIVER_NOT_EXPORTED);
        BatchBackupOptions options = operationInfo.toBatchOptions();
        BatchQueueItem queueItem = BatchQueueItem.getBatchOpQueue(operationInfo.op, operationInfo.packageList,
                operationInfo.userIdListMappedToPackageList, options);
        Intent intent = BatchOpsService.getServiceIntent(mActivity, queueItem);
        ContextCompat.startForegroundService(mActivity, intent);
        dismiss();
    }
}
