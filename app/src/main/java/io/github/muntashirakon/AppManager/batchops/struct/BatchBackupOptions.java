// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.batchops.struct;

import android.annotation.UserIdInt;
import android.os.Parcel;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

import io.github.muntashirakon.AppManager.backup.BackupFlags;
import io.github.muntashirakon.AppManager.backup.BackupUtils;
import io.github.muntashirakon.AppManager.backup.struct.BackupOpOptions;
import io.github.muntashirakon.AppManager.backup.struct.DeleteOpOptions;
import io.github.muntashirakon.AppManager.backup.struct.RestoreOpOptions;
import io.github.muntashirakon.AppManager.db.entity.Backup;
import io.github.muntashirakon.AppManager.history.JsonDeserializer;
import io.github.muntashirakon.AppManager.utils.ContextUtils;
import io.github.muntashirakon.AppManager.utils.DateUtils;
import io.github.muntashirakon.AppManager.utils.JSONUtils;

public class BatchBackupOptions implements IBatchOpOptions {
    public static final String TAG = BatchBackupOptions.class.getSimpleName();

    @BackupFlags.BackupFlag
    private final int mFlags;
    @Nullable
    private final String[] mBackupNames;
    @Nullable
    private final String[] mRelativeDirs;
    /**
     * Fork (白い熊, +121): per-package overrides of {@link #mFlags}.
     *
     * <p>A batch used to mean one set of flags for five hundred apps, which is only right when
     * every app is the same kind of thing — and they are not. The table dialog lets each app carry
     * its own selection, and this is where those selections travel; {@link #mFlags} stays as the
     * value for anything not named here, so an ordinary batch is unchanged and an older queued
     * operation still deserialises.
     */
    @Nullable
    private final Map<String, Integer> mPerPackageFlags;
    /**
     * Fork (白い熊, +131): per-package backup directories, for a delete that spans several apps.
     *
     * <p>{@link #mRelativeDirs} is one list for the whole batch, which is only meaningful when
     * the batch is one app — every other caller falls back to "the base backup", one per app.
     * The backups screen selects whole apps and deletes <b>everything they own</b>, so each app
     * carries its own list here.
     */
    @Nullable
    private final Map<String, String[]> mPerPackageRelativeDirs;

    /**
     * Fork (白い熊, +133): the App-supplied categories chosen <b>for this run</b>, per app.
     *
     * <p>Ticking a category in a picker used to write the app's stored preference, so narrowing
     * one backup narrowed every future one — and in a batch table, where the ticks are a working
     * surface you sweep across forty apps, that was silent and wholesale. These travel with the
     * operation instead and change nothing on disk; an app absent from the map keeps using
     * whatever it has stored, and an <b>empty</b> array is a real answer meaning "none of it".
     */
    @Nullable
    private final Map<String, String[]> mPerPackageAppData;

    public BatchBackupOptions(@BackupFlags.BackupFlag int flags,
                              @Nullable String[] backupNames,
                              @Nullable String[] relativeDirs) {
        this(flags, backupNames, relativeDirs, null);
    }

    public BatchBackupOptions(@BackupFlags.BackupFlag int flags,
                              @Nullable String[] backupNames,
                              @Nullable String[] relativeDirs,
                              @Nullable Map<String, Integer> perPackageFlags) {
        this(flags, backupNames, relativeDirs, perPackageFlags, null);
    }

    public BatchBackupOptions(@BackupFlags.BackupFlag int flags,
                              @Nullable String[] backupNames,
                              @Nullable String[] relativeDirs,
                              @Nullable Map<String, Integer> perPackageFlags,
                              @Nullable Map<String, String[]> perPackageRelativeDirs) {
        this(flags, backupNames, relativeDirs, perPackageFlags, perPackageRelativeDirs, null);
    }

    public BatchBackupOptions(@BackupFlags.BackupFlag int flags,
                              @Nullable String[] backupNames,
                              @Nullable String[] relativeDirs,
                              @Nullable Map<String, Integer> perPackageFlags,
                              @Nullable Map<String, String[]> perPackageRelativeDirs,
                              @Nullable Map<String, String[]> perPackageAppData) {
        mFlags = flags;
        mBackupNames = backupNames;
        mRelativeDirs = relativeDirs;
        mPerPackageFlags = perPackageFlags;
        mPerPackageRelativeDirs = perPackageRelativeDirs;
        mPerPackageAppData = perPackageAppData;
    }

    public BackupOpOptions getBackupOpOptions(@NonNull String packageName, @UserIdInt int userId) {
        String backupName;
        int flags = mFlags;
        if (mPerPackageFlags != null) {
            Integer own = mPerPackageFlags.get(packageName);
            if (own != null) {
                flags = own;
            }
        }
        boolean customBackup = (flags & BackupFlags.BACKUP_MULTIPLE) != 0;
        if (mBackupNames != null && mBackupNames.length > 0) {
            backupName = mBackupNames[0];
        } else {
            // Fork (白い熊, +126): a sortable stamp, not the locale's medium date — see
            // BackupUtils#timestampBackupName. The old name sorted by month NAME in the
            // directory, which is where you stand when deciding what to delete.
            backupName = customBackup ? BackupUtils.timestampBackupName() : null;
        }
        String[] appData = mPerPackageAppData == null ? null : mPerPackageAppData.get(packageName);
        return new BackupOpOptions(packageName, userId, flags, backupName, !customBackup, appData);
    }

    public RestoreOpOptions getRestoreOpOptions(@NonNull String packageName, @UserIdInt int userId) {
        // For restore operation, backup names (v4) and relative dirs are only set for single
        // package backups. In all other cases, it only uses base backups.
        // Fork (白い熊, +132): unless the restore table said otherwise. It chooses WHICH backup
        // and WHICH parts per app, because both are facts about that app's archive rather than
        // about the batch — see RestorePartsTable.
        int flags = mFlags;
        if (mPerPackageFlags != null) {
            Integer own = mPerPackageFlags.get(packageName);
            if (own != null) {
                flags = own;
            }
        }
        String[] ownDirs = mPerPackageRelativeDirs == null ? null : mPerPackageRelativeDirs.get(packageName);
        if (ownDirs != null && ownDirs.length > 0) {
            return new RestoreOpOptions(packageName, userId, ownDirs[0], flags);
        }
        String relativeDir;
        if (mRelativeDirs != null && mRelativeDirs.length > 0) {
            relativeDir = mRelativeDirs[0];
        } else {
            if (mBackupNames == null || mBackupNames.length == 0) {
                // Base backup
                relativeDir = null;
            } else {
                // Generate relative directories
                Backup backup = BackupUtils.retrieveLatestBackupFromDb(userId, mBackupNames[0], packageName);
                if (backup == null) {
                    throw new IllegalArgumentException("Backup with name " + mBackupNames[0] + " doesn't exist.");
                }
                relativeDir = backup.relativeDir;
            }
        }
        return new RestoreOpOptions(packageName, userId, relativeDir, flags);
    }

    public DeleteOpOptions getDeleteOpOptions(@NonNull String packageName, @UserIdInt int userId) {
        // For delete operation, backup names (v4) and relative dirs are only set for single
        // package backups. In all other cases, it only uses base backups.
        String[] relativeDirs;
        String[] own = mPerPackageRelativeDirs == null ? null : mPerPackageRelativeDirs.get(packageName);
        if (own != null) {
            relativeDirs = own;
        } else if (mRelativeDirs != null) {
            relativeDirs = mRelativeDirs;
        } else {
            if (mBackupNames == null || mBackupNames.length == 0) {
                // Base backup
                relativeDirs = null;
            } else {
                // Generate relative directories
                relativeDirs = new String[mBackupNames.length];
                for (int i = 0; i < relativeDirs.length; ++i) {
                    Backup backup = BackupUtils.retrieveLatestBackupFromDb(userId, mBackupNames[i], packageName);
                    if (backup == null) {
                        throw new IllegalArgumentException("Backup with name " + mBackupNames[i] + " doesn't exist.");
                    }
                    relativeDirs[i] = backup.relativeDir;
                }
            }
        }
        return new DeleteOpOptions(packageName, userId, relativeDirs);
    }

    protected BatchBackupOptions(@NonNull Parcel in) {
        mFlags = in.readInt();
        mBackupNames = in.createStringArray();
        mRelativeDirs = in.createStringArray();
        mPerPackageFlags = readFlagMap(in.createStringArray(), in.createIntArray());
        mPerPackageRelativeDirs = readDirMap(in.createStringArray(), in.createStringArrayList());
        mPerPackageAppData = readDirMap(in.createStringArray(), in.createStringArrayList());
    }

    @Nullable
    private static Map<String, String[]> readDirMap(@Nullable String[] names,
                                                    @Nullable java.util.List<String> joined) {
        if (names == null || joined == null || names.length != joined.size()) {
            return null;
        }
        Map<String, String[]> map = new LinkedHashMap<>(names.length);
        for (int i = 0; i < names.length; ++i) {
            String value = joined.get(i);
            map.put(names[i], value.isEmpty() ? new String[0] : value.split("\\n"));
        }
        return map;
    }

    public static final Creator<BatchBackupOptions> CREATOR = new Creator<BatchBackupOptions>() {
        @Override
        @NonNull
        public BatchBackupOptions createFromParcel(@NonNull Parcel in) {
            return new BatchBackupOptions(in);
        }

        @Override
        @NonNull
        public BatchBackupOptions[] newArray(int size) {
            return new BatchBackupOptions[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mFlags);
        dest.writeStringArray(mBackupNames);
        dest.writeStringArray(mRelativeDirs);
        // Two parallel arrays rather than a Map: a Bundle/Serializable round trip through a
        // Parcel is the sort of thing that works until the day it does not.
        String[] names = mPerPackageFlags == null ? null
                : mPerPackageFlags.keySet().toArray(new String[0]);
        int[] values = null;
        if (names != null) {
            values = new int[names.length];
            for (int i = 0; i < names.length; ++i) {
                Integer v = mPerPackageFlags.get(names[i]);
                values[i] = v == null ? mFlags : v;
            }
        }
        dest.writeStringArray(names);
        dest.writeIntArray(values);
        String[] dirNames = mPerPackageRelativeDirs == null ? null
                : mPerPackageRelativeDirs.keySet().toArray(new String[0]);
        java.util.ArrayList<String> dirValues = null;
        if (dirNames != null) {
            dirValues = new java.util.ArrayList<>(dirNames.length);
            for (String name : dirNames) {
                String[] dirs = mPerPackageRelativeDirs.get(name);
                // Joined with a newline: a relative dir is a path segment list and never
                // contains one, and a String[][] has no Parcel primitive of its own.
                dirValues.add(dirs == null ? "" : TextUtils.join("\n", dirs));
            }
        }
        dest.writeStringArray(dirNames);
        dest.writeStringList(dirValues);
        writeDirMap(dest, mPerPackageAppData);
    }

    /** The same parallel-array shape the relative dirs use; see writeToParcel. */
    private static void writeDirMap(@NonNull Parcel dest, @Nullable Map<String, String[]> map) {
        String[] names = map == null ? null : map.keySet().toArray(new String[0]);
        java.util.ArrayList<String> values = null;
        if (names != null) {
            values = new java.util.ArrayList<>(names.length);
            for (String name : names) {
                String[] own = map.get(name);
                values.add(own == null ? "" : TextUtils.join("\n", own));
            }
        }
        dest.writeStringArray(names);
        dest.writeStringList(values);
    }

    @Nullable
    private static Map<String, Integer> readFlagMap(@Nullable String[] names, @Nullable int[] values) {
        if (names == null || values == null || names.length != values.length) {
            return null;
        }
        Map<String, Integer> map = new LinkedHashMap<>(names.length);
        for (int i = 0; i < names.length; ++i) {
            map.put(names[i], values[i]);
        }
        return map;
    }

    public BatchBackupOptions(@NonNull JSONObject jsonObject) throws JSONException {
        assert jsonObject.getString("tag").equals(TAG);
        mFlags = jsonObject.getInt("flags");
        mBackupNames = JSONUtils.getArray(String.class, jsonObject.optJSONArray("backup_names"));
        mRelativeDirs = JSONUtils.getArray(String.class, jsonObject.optJSONArray("relative_dirs"));
        Map<String, Integer> perPackage = null;
        JSONObject own = jsonObject.optJSONObject("per_package_flags");
        if (own != null) {
            perPackage = new LinkedHashMap<>();
            for (java.util.Iterator<String> it = own.keys(); it.hasNext(); ) {
                String key = it.next();
                perPackage.put(key, own.optInt(key, mFlags));
            }
        }
        mPerPackageFlags = perPackage;
        Map<String, String[]> perPackageDirs = null;
        JSONObject dirs = jsonObject.optJSONObject("per_package_relative_dirs");
        if (dirs != null) {
            perPackageDirs = new LinkedHashMap<>();
            for (java.util.Iterator<String> it = dirs.keys(); it.hasNext(); ) {
                String key = it.next();
                org.json.JSONArray array = dirs.optJSONArray(key);
                String[] value = new String[array == null ? 0 : array.length()];
                for (int i = 0; i < value.length; ++i) {
                    value[i] = array.optString(i, "");
                }
                perPackageDirs.put(key, value);
            }
        }
        mPerPackageRelativeDirs = perPackageDirs;
        mPerPackageAppData = readJsonDirMap(jsonObject.optJSONObject("per_package_app_data"));
    }

    @Nullable
    private static Map<String, String[]> readJsonDirMap(@Nullable JSONObject object) {
        if (object == null) {
            return null;
        }
        Map<String, String[]> map = new LinkedHashMap<>();
        for (java.util.Iterator<String> it = object.keys(); it.hasNext(); ) {
            String key = it.next();
            org.json.JSONArray array = object.optJSONArray(key);
            String[] value = new String[array == null ? 0 : array.length()];
            for (int i = 0; i < value.length; ++i) {
                value[i] = array.optString(i, "");
            }
            map.put(key, value);
        }
        return map;
    }

    public static final JsonDeserializer.Creator<BatchBackupOptions> DESERIALIZER
            = BatchBackupOptions::new;

    @NonNull
    @Override
    public JSONObject serializeToJson() throws JSONException {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("tag", TAG);
        jsonObject.put("flags", mFlags);
        jsonObject.put("backup_names", JSONUtils.getJSONArray(mBackupNames));
        jsonObject.put("relative_dirs", JSONUtils.getJSONArray(mRelativeDirs));
        if (mPerPackageFlags != null && !mPerPackageFlags.isEmpty()) {
            JSONObject own = new JSONObject();
            for (Map.Entry<String, Integer> entry : mPerPackageFlags.entrySet()) {
                own.put(entry.getKey(), entry.getValue());
            }
            jsonObject.put("per_package_flags", own);
        }
        if (mPerPackageRelativeDirs != null && !mPerPackageRelativeDirs.isEmpty()) {
            JSONObject dirs = new JSONObject();
            for (Map.Entry<String, String[]> entry : mPerPackageRelativeDirs.entrySet()) {
                dirs.put(entry.getKey(), new org.json.JSONArray(java.util.Arrays.asList(entry.getValue())));
            }
            jsonObject.put("per_package_relative_dirs", dirs);
        }
        if (mPerPackageAppData != null && !mPerPackageAppData.isEmpty()) {
            JSONObject appData = new JSONObject();
            for (Map.Entry<String, String[]> entry : mPerPackageAppData.entrySet()) {
                appData.put(entry.getKey(), new org.json.JSONArray(java.util.Arrays.asList(entry.getValue())));
            }
            jsonObject.put("per_package_app_data", appData);
        }
        return jsonObject;
    }
}
