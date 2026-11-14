// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.db.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "vt_file", indices = {
        @Index(name = "index_vt_file_sha256", value = {"sha256"}, unique = true)
})
public class VtFile {
    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    @ColumnInfo(name = "sha256")
    public String sha256 = "";

    @Nullable
    @ColumnInfo(name = "md5")
    public String md5;

    @Nullable
    @ColumnInfo(name = "sha1")
    public String sha1;

    @Nullable
    @ColumnInfo(name = "display_name")
    public String displayName;

    @Nullable
    @ColumnInfo(name = "mime_type")
    public String mimeType;

    @ColumnInfo(name = "size_bytes")
    public long sizeBytes;

    @Nullable
    @ColumnInfo(name = "latest_report_json")
    public String latestReportJson;

    @Nullable
    @ColumnInfo(name = "latest_detected")
    public Integer latestDetected;

    @Nullable
    @ColumnInfo(name = "latest_total")
    public Integer latestTotal;

    @Nullable
    @ColumnInfo(name = "latest_analysis_id")
    public String latestAnalysisId;

    @Nullable
    @ColumnInfo(name = "permalink")
    public String permalink;

    @ColumnInfo(name = "latest_analysis_at")
    public long latestAnalysisAt;

    @ColumnInfo(name = "latest_read_at")
    public long latestReadAt;

    @Nullable
    @ColumnInfo(name = "local_analysis_json")
    public String localAnalysisJson;

    // Fork (白い熊, APK-vetting chain): when > 0, the epoch-ms at which the one-shot post-upload
    // auto-refresh becomes due (set ~3–7 days after a fresh first-seen upload; cleared to 0 once
    // the refresh fires). See VtScanRepository.enqueueDueAutoRefreshes.
    @ColumnInfo(name = "auto_refresh_at", defaultValue = "0")
    public long autoRefreshAt;

    // Fork (白い熊, APK-vetting chain, bundles): this row is a bundle container (.apks/.xapk/.apkm)
    // whose verdict is synthesised from its parts; it is never itself uploaded.
    @ColumnInfo(name = "is_bundle", defaultValue = "0")
    public boolean isBundle;

    // Fork (白い熊, APK-vetting chain, bundles): for a part extracted from a bundle, the SHA-256 of
    // the containing bundle; null for a standalone file and for a bundle container itself.
    @Nullable
    @ColumnInfo(name = "bundle_of")
    public String bundleOf;

    @ColumnInfo(name = "last_accessed_at")
    public long lastAccessedAt;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;
}
