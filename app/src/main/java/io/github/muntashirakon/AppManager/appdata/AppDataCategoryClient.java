// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.appdata;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.github.muntashirakon.AppManager.logs.Log;

/**
 * Fork: asks an app what it can export, over the §1 broadcast channel.
 * <p>
 * This is a different channel from the data door on purpose — not ours. The provider exposes only
 * {@code describe}/{@code export}/{@code import}/{@code cancel}, so the category listing has to be
 * a broadcast round trip. That is the whole reason the picker is fetched when you open it and never
 * eagerly for a list of apps: one round trip per app, and for a frozen app a thaw as well.
 */
public class AppDataCategoryClient {
    public static final String TAG = AppDataCategoryClient.class.getSimpleName();

    /** The listing is documented as instant; this only has to cover starting a cold process. */
    private static final long TIMEOUT_MS = 30_000L;

    private final Context mContext;

    public AppDataCategoryClient(@NonNull Context context) {
        mContext = context.getApplicationContext();
    }

    /**
     * Returns the app's exportable categories, or {@code null} if it refused, did not answer, or
     * answered something unparseable. The caller must already have thawed the app —
     * {@link AppDataTransfer#listCategories} is the entry point that does that.
     */
    @WorkerThread
    @Nullable
    public List<AppDataCategory> list(@NonNull String packageName) {
        String replyId = UUID.randomUUID().toString();
        LinkedBlockingQueue<String> terminal = new LinkedBlockingQueue<>(1);

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !replyId.equals(intent.getStringExtra(AppDataContract.EXTRA_REPLY_ID))) {
                    return;
                }
                String result = intent.getStringExtra(AppDataContract.EXTRA_RESULT);
                terminal.offer(result != null ? result : AppDataContract.ERROR_PREFIX + "empty reply");
            }
        };
        // The reply is a broadcast from another app, so this must be exported.
        ContextCompat.registerReceiver(mContext, receiver, new IntentFilter(AppDataContract.ACTION_REPLY),
                ContextCompat.RECEIVER_EXPORTED);
        try {
            Intent request = new Intent(packageName + AppDataContract.ACTION_LIST_CATEGORIES_SUFFIX);
            request.setPackage(packageName);
            // Without this a stopped package never receives it — and a freshly installed or
            // force-stopped app is exactly the case that matters.
            request.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            request.putExtra(AppDataContract.EXTRA_REPLY_ACTION, AppDataContract.ACTION_REPLY);
            request.putExtra(AppDataContract.EXTRA_REPLY_PACKAGE, mContext.getPackageName());
            request.putExtra(AppDataContract.EXTRA_REPLY_ID, replyId);
            mContext.sendBroadcast(request);

            String result = terminal.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (result == null) {
                Log.w(TAG, "%s did not answer LIST_CATEGORIES", packageName);
                return null;
            }
            List<AppDataCategory> categories = AppDataCategory.parseReply(result);
            if (categories == null) {
                Log.w(TAG, "%s refused or answered unparseably: %s", packageName, result);
            }
            return categories;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable th) {
            Log.w(TAG, "LIST_CATEGORIES failed for %s", th, packageName);
            return null;
        } finally {
            try {
                mContext.unregisterReceiver(receiver);
            } catch (Throwable ignore) {
            }
        }
    }
}
