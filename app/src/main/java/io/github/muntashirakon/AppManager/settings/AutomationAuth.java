// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Fork: the gate on {@link StateExportReceiver} — the <b>v2 shape</b> of the sister-app
 * contract (白い熊, +151), matching 自由作業盤's own {@code AutomationAuth} so every
 * 白い熊 app behaves the same.
 * <p>
 * 白い熊's automation app (白い熊 自由作業盤, {@code shiroikuma.jiyusagyoban}) backs up
 * every sister app in one run by broadcasting at each of them. v1 shipped every app closed:
 * the master switch was off and a caller had to present a 48-character secret pasted out of
 * this app's settings.
 * <p>
 * <b>Why the switch now ships on and the token ships off.</b> A pasted secret cannot survive
 * a wipe, and the case this family now exists to serve is 応用管理 restoring apps and their
 * data onto a <em>clean</em> phone, where nothing has been configured and nobody has pasted
 * anything. A gate that only works once the phone is already set up is no gate for setting
 * the phone up. The switch stays, because it is the only way to close this app off and a
 * feature that can be turned on but never off is one 白い熊 cannot retreat from.
 * <p>
 * <b>A token sent to an app that does not require one is IGNORED, never refused.</b> Tokens
 * live in task arguments that outlive the setting they were pasted for, and another app in
 * the same batch may still want one. Refusing it would turn "白い熊 turned a switch off"
 * into "half the batch mysteriously fails".
 * <p>
 * <b>The whole check lives in {@link #refuse}</b>, in one place — two checks written out at
 * each entry point is how "disabled" and "bad token" drift apart across forty-odd apps.
 * <p>
 * These live in their own SharedPreferences file, {@link #PREF_FILE}, which
 * {@link SettingsBackupManager} excludes from both export and import — a secret must never
 * travel inside a backup archive nor be overwritten by one. With the v2 defaults that
 * exclusion costs nothing on a migration: a fresh install comes up answering the batch.
 */
public final class AutomationAuth {
    /** Device-local, never exported (see {@code SettingsBackupManager.EXCLUDED_PREFS}). */
    public static final String PREF_FILE = "shiroikuma_automation";

    private static final String KEY_ENABLED = "automation_enabled";
    private static final String KEY_REQUIRE_TOKEN = "automation_require_token";
    private static final String KEY_TOKEN = "automation_token";
    private static final int TOKEN_BYTES = 24;

    private AutomationAuth() {
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    /**
     * Master switch. Default <b>true</b>: this app answers the batch out of the box, which is
     * what makes a clean phone recoverable without anything having been paired first.
     */
    public static boolean isEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, true);
    }

    /**
     * LANDMINE — {@code commit()}, never {@code apply()}. The gate now fails <b>open</b>: the
     * default is ON, so a write that never reaches disk reverts to "automation allowed", not
     * to "closed". This app force-stops other apps with SIGKILL, sometimes seconds after a
     * switch is flipped, and an asynchronous write is exactly what such a kill loses. Same
     * reasoning as {@link #getToken}, which has always committed; the two flag setters were
     * the gap (自由作業盤's contract §2, confirmed cross-session, +152).
     */
    public static void setEnabled(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).commit();
    }

    /** Default <b>false</b>: the token is an extra a caller may be asked for, not the gate. */
    public static boolean isTokenRequired(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_REQUIRE_TOKEN, false);
    }

    /** Committed, not applied — see {@link #setEnabled}. */
    public static void setTokenRequired(@NonNull Context context, boolean required) {
        prefs(context).edit().putBoolean(KEY_REQUIRE_TOKEN, required).commit();
    }

    /**
     * The whole gate, in one answer: {@code null} means proceed, anything else is the exact
     * {@code ERROR:} line to reply with. "automation disabled" and "bad token" stay distinct
     * because they debug differently.
     * <p>
     * The candidate is only looked at when {@link #isTokenRequired} is on; when it is off, a
     * token that arrived anyway is dropped on the floor rather than refused.
     */
    @Nullable
    public static String refuse(@NonNull Context context, @Nullable String candidate) {
        if (!isEnabled(context)) {
            return "ERROR:automation disabled";
        }
        if (isTokenRequired(context) && !tokenMatches(context, candidate)) {
            return "ERROR:bad token";
        }
        return null;
    }

    /**
     * The stored token, generated lazily on first read so the settings row
     * always shows a value (even before the switch is ever flipped).
     */
    @NonNull
    public static String getToken(@NonNull Context context) {
        SharedPreferences sp = prefs(context);
        String token = sp.getString(KEY_TOKEN, "");
        if (token == null || token.isEmpty()) {
            token = generate();
            // commit(), not apply(): a broadcast receiver may read this in a
            // short-lived process, and the value must survive it.
            sp.edit().putString(KEY_TOKEN, token).commit();
        }
        return token;
    }

    /** Throw the old token away and mint a new one. Pasted copies go stale. */
    @NonNull
    public static String regenerate(@NonNull Context context) {
        String token = generate();
        prefs(context).edit().putString(KEY_TOKEN, token).commit();
        return token;
    }

    /** Constant-time comparison of a presented token against the stored one. */
    public static boolean tokenMatches(@NonNull Context context, String candidate) {
        if (candidate == null) return false;
        return MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8),
                getToken(context).getBytes(StandardCharsets.UTF_8));
    }

    /** {@code 80922d8c…4c49a87c} — what the settings row displays. */
    @NonNull
    public static String abbreviate(@NonNull String token) {
        if (token.length() <= 20) return token;
        return token.substring(0, 8) + "…" + token.substring(token.length() - 8);
    }

    @NonNull
    private static String generate() {
        byte[] raw = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder(TOKEN_BYTES * 2);
        for (byte b : raw) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
