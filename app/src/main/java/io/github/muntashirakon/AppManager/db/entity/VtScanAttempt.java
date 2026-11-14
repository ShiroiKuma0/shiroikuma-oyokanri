// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.db.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringDef;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "vt_scan_attempt", foreignKeys = @ForeignKey(
        entity = VtFile.class,
        parentColumns = "id",
        childColumns = "file_id",
        onDelete = ForeignKey.CASCADE
), indices = {
        @Index(name = "index_vt_scan_attempt_file_id", value = {"file_id"}),
        @Index(name = "index_vt_scan_attempt_status", value = {"status"}),
        @Index(name = "index_vt_scan_attempt_updated_at", value = {"updated_at"})
})
public class VtScanAttempt {
    @StringDef({
            TYPE_LOOKUP,
            TYPE_UPLOAD,
            TYPE_UPDATE,
            TYPE_RESCAN
    })
    public @interface VtScanType {
    }

    public static final String TYPE_LOOKUP = "lookup";
    public static final String TYPE_UPLOAD = "upload";
    public static final String TYPE_UPDATE = "update";
    public static final String TYPE_RESCAN = "rescan";

    @StringDef({
            STATUS_PENDING_CONSENT,
            STATUS_PENDING_KEY,
            STATUS_QUEUED,
            STATUS_LOOKING_UP,
            STATUS_UPLOADING,
            STATUS_ANALYSING,
            STATUS_COMPLETED,
            STATUS_FAILED,
            STATUS_CANCELLED
    })
    public @interface VtScanStatus {
    }
    public static final String STATUS_PENDING_CONSENT = "pending_consent";
    // Fork (白い熊, APK-vetting chain, item 10): parked because no VirusTotal API key is stored (or
    // the stored key stopped working). Resumes when a key is saved. It is an ACTIVE status, so the
    // attempt stays in the active set and the history screen lists it as pending.
    public static final String STATUS_PENDING_KEY = "pending_key";
    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_LOOKING_UP = "looking_up";
    public static final String STATUS_UPLOADING = "uploading";
    public static final String STATUS_ANALYSING = "analysing";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";

    // Fork (白い熊, +081): on an ACTIVE attempt, error_code = this and error_message = the reason
    // it is parked (quota, waiting for Wi-Fi). Cleared as soon as the attempt moves on.
    public static final String CODE_WAITING = "Waiting";

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "file_id")
    public long fileId;

    @NonNull
    @VtScanType
    @ColumnInfo(name = "type")
    public String type = TYPE_LOOKUP;

    @NonNull
    @VtScanStatus
    @ColumnInfo(name = "status")
    public String status = STATUS_QUEUED;

    @Nullable
    @ColumnInfo(name = "analysis_id")
    public String analysisId;

    @Nullable
    @ColumnInfo(name = "permalink")
    public String permalink;

    @Nullable
    @ColumnInfo(name = "report_json")
    public String reportJson;

    @Nullable
    @ColumnInfo(name = "detected")
    public Integer detected;

    @Nullable
    @ColumnInfo(name = "total")
    public Integer total;

    @Nullable
    @ColumnInfo(name = "error_code")
    public String errorCode;

    @Nullable
    @ColumnInfo(name = "error_message")
    public String errorMessage;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "started_at")
    public long startedAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;

    @ColumnInfo(name = "completed_at")
    public long completedAt;

    @ColumnInfo(name = "read_at")
    public long readAt;

    // Fork (白い熊, APK-vetting chain, item 2): a parked attempt must not run before this epoch-ms
    // (quota back-off, or a Wi-Fi-only upload deferred off Wi-Fi). 0 = runnable now.
    @ColumnInfo(name = "next_attempt_at", defaultValue = "0")
    public long nextAttemptAt;
}
