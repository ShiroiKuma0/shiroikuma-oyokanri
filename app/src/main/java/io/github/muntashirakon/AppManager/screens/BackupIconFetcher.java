// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.screens;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import java.io.InputStream;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.backup.BackupItems;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.self.imagecache.ImageLoader;
import io.github.muntashirakon.io.Path;

/**
 * Fork (白い熊, 2026-09-07): the icon of an app that is <em>no longer installed</em>, read out of its
 * own backup.
 *
 * <p>保存一覧 drew the generic Android robot for every uninstalled app — on a phone with 460 backups,
 * most of the page. The fix needs no change to the backup format, because the icon is already
 * there: {@link io.github.muntashirakon.AppManager.backup.BackupOp} writes a 164×164
 * {@code icon.png} into every backup directory <em>before</em> any flag branch, so even a data-only
 * backup has one, and {@link BackupItems.BackupItem#getIconFile()} is commented "Icon is never
 * encrypted" — it is readable with no key, no decompression and no staging copy.
 *
 * <p><b>The tag must not be the bare package name.</b> That tag is shared with the process monitor,
 * app usage, Finder and app info, and {@link ImageLoader} persists what it resolves to
 * {@code <cache>/images/<tag>.png} for seven days. Keying the backup icon on
 * {@code <pkg>@bk<backupTime>} keeps the two apart and, incidentally, re-reads by itself when a
 * newer backup is taken.
 */
public class BackupIconFetcher implements ImageLoader.ImageFetcherInterface {
    /**
     * The tag this fetcher answers to. Distinct from {@link ImageLoader#versionedTag} — that one
     * keys an <i>installed</i> package by its update time.
     */
    @NonNull
    public static String tagFor(@NonNull String packageName, long backupTime) {
        return packageName + "@bk" + backupTime;
    }

    @NonNull
    private final String mRelativeDir;

    public BackupIconFetcher(@NonNull String relativeDir) {
        mRelativeDir = relativeDir;
    }

    @WorkerThread
    @NonNull
    @Override
    public ImageLoader.ImageFetcherResult fetchImage(@NonNull String tag) {
        Bitmap bitmap = null;
        try {
            Path iconFile = BackupItems.findBackupItem(mRelativeDir).getIconFile();
            if (iconFile.exists()) {
                try (InputStream is = iconFile.openInputStream()) {
                    bitmap = BitmapFactory.decodeStream(is);
                }
            }
        } catch (Throwable th) {
            // A missing or unreadable icon is not an error worth a dialog: the row still draws,
            // it just falls back to the robot below.
            Log.w("BackupIconFetcher", "Could not read the backup icon for %s", th, mRelativeDir);
        }
        // Fork: the fallback carries a tag of its OWN, so the robot is cached once for the whole app
        // rather than once per package — and never under a package's own tag, which is what used to
        // poison every other icon surface.
        return new ImageLoader.ImageFetcherResult(tag, bitmap,
                new ImageLoader.DefaultImageDrawableRes("fork_default_android_icon",
                        R.drawable.ic_android));
    }
}
