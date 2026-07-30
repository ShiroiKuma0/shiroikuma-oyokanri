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
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;

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
     * Every extra key a Shizuku-family server is known to write, in preference order.
     * <p>
     * LANDMINE (measured on-device 2026-07-30, cost build +51): a server does <b>not</b> put all of
     * these in one bundle — it makes a <i>separate</i> {@code call()} per client API, each carrying
     * one key holding a container <i>of the matching type</i>
     * ({@code ShizukuService.sendBinderToUserApp} in shiroikuma-shizuku builds three bundles:
     * {@code plus}, {@code rikka}, {@code moe}). So a client must accept whichever call it gets:
     * reading only the modern key means a stock server that sends the legacy call is never heard,
     * and the mode reports "the Shizuku server is not running" while the server is demonstrably
     * calling this provider once a second.
     */
    private static final String[] BINDER_KEYS = {
            "rikka.shizuku.intent.extra.BINDER",              // current API — every maintained server
            "af.shizuku.plus.api.intent.extra.BINDER",        // 白い熊 雫's own
            "moe.shizuku.privileged.api.intent.extra.BINDER", // legacy, still what stock sends
    };

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
        IBinder binder;
        try {
            binder = extractBinder(extras);
        } catch (Throwable th) {
            // See the LANDMINE note above: this is what a missing container class looks like, and
            // it takes the whole bundle down rather than just one key. Name the cause explicitly —
            // the symptom otherwise surfaces as "the Shizuku server is not running", which is a lie.
            Log.e(TAG, "Could not unparcel the binder bundle; a BinderContainer class is missing", th);
            return false;
        }
        if (binder == null) {
            Log.w(TAG, "sendBinder called without a binder");
            return false;
        }
        Log.d(TAG, "binder received");
        //noinspection DataFlowIssue — onCreate has run, so the context is attached
        Shizuku.onBinderReceived(binder, getContext().getPackageName());
        return true;
    }

    /**
     * The binder out of whichever key this particular server used.
     * <p>
     * Read as {@link Parcelable}, never as a typed container: the value under the legacy key is a
     * {@code moe.shizuku.api.BinderContainer} and the one under the Plus key an
     * {@code af.shizuku.api.BinderContainer}, so a typed read compiles to a checkcast that throws
     * {@code ClassCastException} on the very servers those keys exist for. That is the bug this
     * method replaces — it fired on stock Shizuku 13.6.0, which sends the legacy call.
     */
    @Nullable
    private static IBinder extractBinder(@NonNull Bundle extras) {
        for (String key : BINDER_KEYS) {
            IBinder binder = binderOf(extras.getParcelable(key));
            if (binder != null) {
                return binder;
            }
        }
        return null;
    }

    /**
     * The {@code binder} field of any of the three container classes — or of a fourth nobody has
     * written yet, by reflection. All of them are the same one-field shape, and the field is the
     * whole payload, so a server that adds another flavour of the same wrapper still works here.
     */
    @Nullable
    private static IBinder binderOf(@Nullable Parcelable container) {
        if (container == null) {
            return null;
        }
        if (container instanceof BinderContainer) {
            return ((BinderContainer) container).binder;
        }
        if (container instanceof moe.shizuku.api.BinderContainer) {
            return ((moe.shizuku.api.BinderContainer) container).binder;
        }
        if (container instanceof af.shizuku.api.BinderContainer) {
            return ((af.shizuku.api.BinderContainer) container).binder;
        }
        for (Field field : container.getClass().getFields()) {
            if (IBinder.class.isAssignableFrom(field.getType())) {
                try {
                    return (IBinder) field.get(container);
                } catch (Throwable th) {
                    Log.w(TAG, "Could not read " + field.getName() + " from " + container.getClass(), th);
                }
            }
        }
        Log.w(TAG, "Unrecognised binder container: " + container.getClass().getName());
        return null;
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
