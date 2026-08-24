// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.backup;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Fork: the narrow channel through which a single-app backup or restore says
 * what it is doing while it does it.
 *
 * <p>{@link io.github.muntashirakon.AppManager.progress.ProgressHandler} can
 * only carry a number and a notification title, which is why a 550-app batch
 * backup had nothing to show but a counter that moved once per app — and an app
 * with a large data directory can hold that counter still for minutes. This
 * interface carries the rest: the directory the backup is landing in, the stage
 * within the app, and the bytes produced as they are produced.
 *
 * <p>It is deliberately not the {@code ProgressHandler}: that is a per-app
 * sub-progress owned by the notification, whereas these events are addressed to
 * the batch as a whole. Implementations are called from the backup worker
 * thread and must not block it.
 */
@AnyThread
public interface BackupProgressListener {
    /**
     * The backup directory has been created and is where this app's files will
     * be written. Reported once, before any work.
     *
     * @param destination Full destination path, or a URI string when the backup
     *                    volume is not a plain filesystem path.
     */
    void onDestination(@NonNull String destination);

    /**
     * The op has moved to a new stage.
     *
     * @param stage  Short human-readable stage, e.g. "Data 2/4".
     * @param detail Optional supporting text, e.g. the directory being read.
     */
    void onStage(@NonNull CharSequence stage, @Nullable CharSequence detail);

    /**
     * Bytes written since the last call. Accumulated by the caller; never a
     * running total, so a listener can simply add.
     */
    void onBytesWritten(long bytes);
}
