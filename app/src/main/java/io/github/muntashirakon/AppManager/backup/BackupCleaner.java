// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.backup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.WorkerThread;

import java.util.ArrayList;
import java.util.List;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.settings.Prefs;
import io.github.muntashirakon.io.Path;

/**
 * Fork (白い熊, +137): find and remove the wreckage a failed backup leaves in the backup directory.
 *
 * <p>Three kinds of rubbish accumulate there, and none of them is a backup:
 *
 * <ul>
 *   <li><b>An empty directory per failed run.</b> {@code BackupItems.BackupItem} used to
 *       {@code mkdirs()} the real directory up front and then do all its work inside a hidden
 *       {@code .name} sibling, and its cleanup deleted only the sibling — so every backup that
 *       failed, was cancelled, or was killed left the real folder standing, empty. Fixed at
 *       source in the same build; this finds the ones already on disk.</li>
 *   <li><b>A staging directory.</b> The hidden {@code .name} folder, left whole when the process
 *       died before it could be renamed into place — a half-written backup that no code will
 *       ever look at again.</li>
 *   <li><b>A directory with no metadata.</b> Files were written and the run stopped before the
 *       metadata that makes them readable. Without it nothing can restore from that folder,
 *       whatever it contains.</li>
 * </ul>
 *
 * <p><b>The rule is conservative by construction: a directory carrying readable metadata is never
 * a finding</b>, whether or not the database knows about it, whether or not it is stale, and
 * whether or not it is frozen. Deciding a real backup is rubbish is the one mistake this must not
 * be able to make, so "is it a backup" is answered by the same file the restorer reads.
 */
public final class BackupCleaner {
    public static final String TAG = "BackupCleaner";

    /** The APK-saving directory is not a backup directory and is never walked. */
    private static final String APK_SAVING_DIRECTORY = "apks";

    public static class Finding {
        @NonNull
        public final Path path;
        /** {@code <package>/<directory>}, which is how it reads in a file browser. */
        @NonNull
        public final String label;
        @StringRes
        public final int reasonRes;
        public final long size;

        Finding(@NonNull Path path, @NonNull String label, @StringRes int reasonRes, long size) {
            this.path = path;
            this.label = label;
            this.reasonRes = reasonRes;
            this.size = size;
        }
    }

    private BackupCleaner() {
    }

    /**
     * Walk the backup directory. Never deletes anything; the caller decides.
     */
    @WorkerThread
    @NonNull
    public static List<Finding> scan() {
        List<Finding> findings = new ArrayList<>();
        Path base;
        try {
            base = Prefs.Storage.getAppManagerDirectory();
        } catch (Throwable th) {
            Log.w(TAG, "no backup directory to scan", th);
            return findings;
        }
        Path[] packages = listOrEmpty(base);
        for (Path packageDir : packages) {
            if (!isDirectory(packageDir) || APK_SAVING_DIRECTORY.equals(packageDir.getName())) {
                continue;
            }
            Path[] children = listOrEmpty(packageDir);
            int sound = 0;
            List<Finding> mine = new ArrayList<>();
            for (Path child : children) {
                if (!isDirectory(child)) {
                    // A stray file beside the backups. Left alone: it is not ours to judge.
                    ++sound;
                    continue;
                }
                String label = packageDir.getName() + "/" + child.getName();
                if (child.getName().startsWith(".")) {
                    mine.add(new Finding(child, label, R.string.backup_clean_staging, sizeOf(child)));
                } else if (isEmpty(child)) {
                    mine.add(new Finding(child, label, R.string.backup_clean_empty, 0));
                } else if (!hasMetadata(child)) {
                    mine.add(new Finding(child, label, R.string.backup_clean_no_metadata, sizeOf(child)));
                } else {
                    ++sound;
                }
            }
            findings.addAll(mine);
            if (sound == 0) {
                // Nothing worth keeping would be left in this app's folder, so the folder goes
                // too — otherwise cleaning leaves behind a directory per app, exactly as useless
                // as what was in it. Appended AFTER its children, which is the order delete()
                // relies on.
                findings.add(new Finding(packageDir, packageDir.getName(),
                        R.string.backup_clean_empty_package, 0));
            }
        }
        return findings;
    }

    /**
     * Remove what was found, deepest first, so a package folder is only removed after the
     * directories inside it are gone.
     *
     * @return how many were removed
     */
    @WorkerThread
    public static int delete(@NonNull List<Finding> findings) {
        int removed = 0;
        // The package folders were appended after their children, so plain order already goes
        // inner to outer. Stated rather than relied on silently.
        for (Finding finding : findings) {
            try {
                if (finding.path.delete()) {
                    ++removed;
                } else {
                    Log.w(TAG, "could not delete %s", finding.label);
                }
            } catch (Throwable th) {
                Log.w(TAG, "could not delete %s", th, finding.label);
            }
        }
        return removed;
    }

    /** A backup is a directory the restorer could read: v5 info, or v4-and-earlier metadata. */
    private static boolean hasMetadata(@NonNull Path directory) {
        try {
            if (directory.hasFile(MetadataManager.INFO_V5_FILE)
                    || directory.hasFile(MetadataManager.META_V2_FILE)) {
                return true;
            }
            // An encrypted v5 backup keeps its metadata beside an unencrypted info file, so the
            // check above covers it; this is the belt for a layout that keeps only the metadata.
            for (Path child : listOrEmpty(directory)) {
                String name = child.getName();
                if (name.startsWith(MetadataManager.META_V5_FILE)
                        || name.startsWith(MetadataManager.INFO_V5_FILE)) {
                    return true;
                }
            }
        } catch (Throwable th) {
            // Unreadable is not the same as absent, and a folder we cannot inspect is one we
            // must not offer to delete.
            return true;
        }
        return false;
    }

    private static boolean isEmpty(@NonNull Path directory) {
        return listOrEmpty(directory).length == 0;
    }

    @NonNull
    private static Path[] listOrEmpty(@Nullable Path directory) {
        if (directory == null) {
            return new Path[0];
        }
        try {
            Path[] children = directory.listFiles();
            return children == null ? new Path[0] : children;
        } catch (Throwable th) {
            return new Path[0];
        }
    }

    private static boolean isDirectory(@Nullable Path path) {
        try {
            return path != null && path.isDirectory();
        } catch (Throwable th) {
            return false;
        }
    }

    private static long sizeOf(@Nullable Path path) {
        if (path == null) {
            return 0;
        }
        try {
            if (!path.isDirectory()) {
                return path.length();
            }
            long total = 0;
            for (Path child : listOrEmpty(path)) {
                total += sizeOf(child);
            }
            return total;
        } catch (Throwable th) {
            return 0;
        }
    }
}
