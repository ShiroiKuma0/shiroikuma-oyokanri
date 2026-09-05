// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.backup.struct;

import android.annotation.UserIdInt;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.ParcelCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

import io.github.muntashirakon.AppManager.backup.BackupFlags;
import io.github.muntashirakon.AppManager.history.IJsonSerializer;
import io.github.muntashirakon.AppManager.utils.JSONUtils;

public class BackupOpOptions implements Parcelable, IJsonSerializer {
    @NonNull
    public final String packageName;
    @UserIdInt
    public final int userId;
    public final BackupFlags flags;
    @Nullable
    public final String backupName;
    public final boolean override;
    /**
     * Fork (白い熊, +133): the App-supplied categories to export <b>this once</b>, or
     * {@code null} to use whatever is stored for the app. An <b>empty</b> array is a real
     * answer — "export none of it" — and must never be confused with null.
     */
    @Nullable
    public final String[] appDataCategories;

    public BackupOpOptions(@NonNull String packageName, int userId, int flags, @Nullable String backupName, boolean override) {
        this(packageName, userId, flags, backupName, override, null);
    }

    public BackupOpOptions(@NonNull String packageName, int userId, int flags, @Nullable String backupName,
                           boolean override, @Nullable String[] appDataCategories) {
        this.packageName = packageName;
        this.userId = userId;
        this.flags = new BackupFlags(flags);
        this.backupName = backupName;
        this.override = override;
        this.appDataCategories = appDataCategories;
    }

    protected BackupOpOptions(@NonNull Parcel in) {
        packageName = Objects.requireNonNull(in.readString());
        userId = in.readInt();
        flags = new BackupFlags(in.readInt());
        backupName = in.readString();
        override = ParcelCompat.readBoolean(in);
        appDataCategories = in.createStringArray();
    }

    public static final Creator<BackupOpOptions> CREATOR = new Creator<BackupOpOptions>() {
        @Override
        @NonNull
        public BackupOpOptions createFromParcel(@NonNull Parcel in) {
            return new BackupOpOptions(in);
        }

        @Override
        @NonNull
        public BackupOpOptions[] newArray(int size) {
            return new BackupOpOptions[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(packageName);
        dest.writeInt(userId);
        dest.writeInt(this.flags.getFlags());
        dest.writeString(backupName);
        ParcelCompat.writeBoolean(dest, override);
        dest.writeStringArray(appDataCategories);
    }

    public BackupOpOptions(@NonNull JSONObject jsonObject) throws JSONException {
        packageName = jsonObject.getString("package_name");
        userId = jsonObject.getInt("user_id");
        flags = new BackupFlags(jsonObject.getInt("flags"));
        backupName = JSONUtils.optString(jsonObject, "backup_name");
        override = jsonObject.getBoolean("override");
        appDataCategories = JSONUtils.getArray(String.class, jsonObject.optJSONArray("app_data_categories"));
    }

    @NonNull
    @Override
    public JSONObject serializeToJson() throws JSONException {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("package_name", packageName);
        jsonObject.put("user_id", userId);
        jsonObject.put("flags", flags.getFlags());
        jsonObject.put("backup_name", backupName);
        jsonObject.put("override", override);
        if (appDataCategories != null) {
            jsonObject.put("app_data_categories", JSONUtils.getJSONArray(appDataCategories));
        }
        return jsonObject;
    }
}
