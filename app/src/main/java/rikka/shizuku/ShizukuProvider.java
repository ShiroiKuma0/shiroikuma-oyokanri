// SPDX-License-Identifier: Apache-2.0
// Copyright 2021 Rikka
// Copyright 2026 白い熊

package rikka.shizuku;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Fork: a trimmed, client-only rewrite of Rikka's {@code ShizukuProvider}, vendored for
 * 白い熊 応用管理 from the sister repo shiroikuma-shizuku.
 * <p>
 * The upstream copy in that repo is the <i>manager's</i> variant: it reads the Plus-private extra
 * key, pulls in Sui bootstrapping, and offers cross-process binder sharing. None of that applies
 * here — 応用管理 talks to whichever server is installed and has exactly one process that cares
 * (the {@code :audio_player} service never touches privileges), so this copy keeps only the one
 * job a client has: receive the binder the server pushes in and hand it to {@link Shizuku}.
 * <p>
 * Registered in the manifest as
 * <pre>
 * &lt;provider
 *     android:name="rikka.shizuku.ShizukuProvider"
 *     android:authorities="${applicationId}.shizuku"
 *     android:exported="true"
 *     android:multiprocess="false"
 *     android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" /&gt;
 * </pre>
 * The authority suffix {@code .shizuku} is the wire contract — the server resolves clients by
 * {@code <packageName> + ".shizuku"} — and the guarding permission has to be one held by shell but
 * not by ordinary apps, which is what makes the provider effectively private while still reachable
 * from the server.
 */
public class ShizukuProvider extends ContentProvider {
    private static final String TAG = "ShizukuProvider";

    public static final String METHOD_SEND_BINDER = "sendBinder";

    /**
     * The extra every server writes for a third-party client. 白い熊 雫 additionally writes its own
     * Plus-private key and the legacy {@code moe.shizuku.privileged.api} one, but this is the key
     * present in all of them, so it is the only one worth reading.
     */
    private static final String EXTRA_BINDER = "rikka.shizuku.intent.extra.BINDER";
    private static final String EXTRA_BINDER_LEGACY = "moe.shizuku.privileged.api.intent.extra.BINDER";

    // LANDMINE (measured on-device 2026-07-30, cost build +44): Bundle.getParcelable() unparcels the
    // WHOLE bundle, every value, not just the key asked for. 白い熊 雫's sendBinderToUserApp puts
    // THREE containers in — rikka.shizuku.BinderContainer, moe.shizuku.api.BinderContainer and (for
    // the manager itself) af.shizuku.api.BinderContainer — so reading OUR key still requires all of
    // those classes to be resolvable, or the read throws BadParcelableException and the hand-off
    // fails whole. Vendoring only rikka.shizuku.BinderContainer is what made the mode report
    // "server is not running" while the server was demonstrably calling this provider. That is why
    // the upstream provider module ships three near-identical container classes, and why this app
    // does too: app/src/main/java/{rikka/shizuku,moe/shizuku/api,af/shizuku/api}/BinderContainer.java.
    // Do not delete one because "nothing references it" — the reference is a string in a parcel.
    // (The same trap is visible in other apps on this phone: MiXplorer and Inure both fail this
    // exact way in logcat, having shrunk one of the containers out of their builds.)

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        if (extras == null) {
            return null;
        }
        extras.setClassLoader(BinderContainer.class.getClassLoader());
        if (METHOD_SEND_BINDER.equals(method)) {
            // A non-null reply is how the server decides the hand-off worked. Returning null on
            // failure is deliberate: the server logs it and retries on the next process event,
            // which is the only way a transient miss (provider published a moment too late) ever
            // recovers. Claiming success without a binder would strand the mode until a restart.
            return handleSendBinder(extras) ? new Bundle() : null;
        }
        return new Bundle();
    }

    private boolean handleSendBinder(@NonNull Bundle extras) {
        if (Shizuku.pingBinder()) {
            Log.d(TAG, "sendBinder called when a living binder is already held");
            return true;
        }
        BinderContainer container;
        try {
            container = extras.getParcelable(EXTRA_BINDER);
            if (container == null) {
                container = extras.getParcelable(EXTRA_BINDER_LEGACY);
            }
        } catch (Throwable th) {
            // See the LANDMINE note above: this is what a missing container class looks like, and
            // it takes the whole bundle down rather than just one key. Name the cause explicitly —
            // the symptom otherwise surfaces as "the Shizuku server is not running", which is a lie.
            Log.e(TAG, "Could not unparcel the binder bundle; a BinderContainer class is missing", th);
            return false;
        }
        if (container == null || container.binder == null) {
            Log.w(TAG, "sendBinder called without a binder");
            return false;
        }
        Log.d(TAG, "binder received");
        //noinspection DataFlowIssue — onCreate has run, so the context is attached
        Shizuku.onBinderReceived(container.binder, getContext().getPackageName());
        return true;
    }

    // No other provider surface: this exists purely as a binder mailbox.

    @Nullable
    @Override
    public final Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                              @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public final String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public final Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public final int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public final int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                            @Nullable String[] selectionArgs) {
        return 0;
    }

    /**
     * Kept so callers can name the binder container class explicitly when they need its class
     * loader; the container itself is package-visible API in every Shizuku client.
     */
    @Nullable
    public static IBinder peekBinder() {
        return Shizuku.getBinder();
    }
}
