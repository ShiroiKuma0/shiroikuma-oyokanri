// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.db.dao;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

import io.github.muntashirakon.AppManager.db.entity.VtFile;

@Dao
public interface VtFileDao {
    @Nullable
    @Query("SELECT * FROM vt_file WHERE sha256 = :sha256 LIMIT 1")
    VtFile getBySha256(@NonNull String sha256);

    @Nullable
    @Query("SELECT * FROM vt_file WHERE id = :id LIMIT 1")
    VtFile get(long id);

    @Query("SELECT * FROM vt_file ORDER BY last_accessed_at DESC, updated_at DESC")
    List<VtFile> getAll();

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(@NonNull VtFile file);

    @Update
    void update(@NonNull VtFile file);

    @Query("UPDATE vt_file SET last_accessed_at = :accessedAt, updated_at = :updatedAt WHERE id = :id")
    int touch(long id, long accessedAt, long updatedAt);

    @Query("SELECT id FROM vt_file WHERE NOT EXISTS (SELECT 1 FROM vt_scan_attempt " +
            "WHERE vt_scan_attempt.file_id = vt_file.id AND status IN " +
            "('pending_consent', 'pending_key', 'queued', 'looking_up', 'uploading', 'analysing')) " +
            "AND bundle_of IS NULL " +
            "ORDER BY last_accessed_at ASC, updated_at ASC LIMIT :limit")
    List<Long> getLeastRecentlyAccessedIds(int limit);

    // Fork (白い熊, APK-vetting chain, item 7): files whose one-shot post-upload auto-refresh is due.
    @Query("SELECT * FROM vt_file WHERE auto_refresh_at > 0 AND auto_refresh_at <= :now")
    List<VtFile> getDueForAutoRefresh(long now);

    // Fork (白い熊, APK-vetting chain, bundles): the extracted parts of a bundle container.
    @Query("SELECT * FROM vt_file WHERE bundle_of = :bundleSha256 ORDER BY display_name ASC")
    List<VtFile> getBundleParts(@NonNull String bundleSha256);

    @Query("UPDATE vt_file SET auto_refresh_at = :at, updated_at = :updatedAt WHERE id = :id")
    void setAutoRefreshAt(long id, long at, long updatedAt);

    @Delete
    void delete(@NonNull VtFile file);

    @Query("DELETE FROM vt_file WHERE id = :id")
    void delete(long id);

    @Query("DELETE FROM vt_file")
    void deleteAll();
}
