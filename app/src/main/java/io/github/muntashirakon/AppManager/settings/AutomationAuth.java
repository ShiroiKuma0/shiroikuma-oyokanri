// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Fork: the automation token that gates {@link StateExportReceiver}.
 * <p>
 * 白い熊's automation app (白い熊 自由作業盤, {@code shiroikuma.jiyusagyoban})
 * backs up every sister app in one run by firing a token-gated broadcast at
 * each of them. This is the shared gate: a master switch that is <b>off by
 * default</b> plus a 24-byte random token that must be presented on every
 * request.
 * <p>
 * The token lives in its own SharedPreferences file, {@link #PREF_FILE}, which
 * {@link SettingsBackupManager} explicitly excludes from both export and
 * import — a secret must never travel inside a backup archive (nor be
 * overwritten by one).
 */
public final class AutomationAuth {
    /** Device-local, never exported (see {@code SettingsBackupManager.EXCLUDED_PREFS}). */
    public static final String PREF_FILE = "shiroikuma_automation";

    private static final String KEY_ENABLED = "automation_enabled";
    private static final String KEY_TOKEN = "automation_token";
    private static final int TOKEN_BYTES = 24;

    private AutomationAuth() {
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    /** Master switch. Default <b>false</b>: nothing is reachable until 白い熊 turns it on. */
    public static boolean isEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
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
