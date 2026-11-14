// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.scanner.vt;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.KeyStore;
import java.security.spec.AlgorithmParameterSpec;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Fork (白い熊, APK-vetting chain): encrypts the VirusTotal API key at rest with an Android Keystore
 * key, so the plain key never sits in {@code shared_prefs/} (where it used to be, shown in the clear
 * as the settings summary too).
 *
 * <p>The stored string is {@code enc:v1:<base64 iv>:<base64 ciphertext+tag>}. A legacy plain value
 * (anything without the {@link #PREFIX}) is returned as-is by {@link #decrypt} so the caller can
 * re-save it encrypted on first use — the one-time migration.
 *
 * <p>The Keystore key never leaves secure hardware where it exists, so the backup export (which
 * carries {@code shared_prefs/}) only ever moves the ciphertext, and it is useless on another phone.
 * On API 21–22, where {@code AES/GCM} in the AndroidKeyStore is unavailable, this degrades to a
 * passthrough (the value is stored plain) rather than failing the whole feature; the target device
 * is API 31, so encryption is the real path.
 */
public final class VtApiKeyStore {
    private VtApiKeyStore() {}

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "shiroikuma_vt_api_key";
    private static final String TRANSFORM = KeyProperties.KEY_ALGORITHM_AES + "/"
            + KeyProperties.BLOCK_MODE_GCM + "/" + KeyProperties.ENCRYPTION_PADDING_NONE;
    private static final String PREFIX = "enc:v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    public static boolean isEncrypted(@Nullable String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    private static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    /**
     * Encrypts {@code plain} into the {@link #PREFIX} wire form, or returns {@code plain} unchanged
     * when encryption is not available (API < 23) or fails — a usable key that is stored plain is
     * better than a scan that cannot run.
     */
    @Nullable
    public static String encrypt(@Nullable String plain) {
        if (plain == null || plain.isEmpty() || !supported()) {
            return plain;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] iv = cipher.getIV();
            byte[] ct = cipher.doFinal(plain.getBytes("UTF-8"));
            return PREFIX + base64(iv) + ":" + base64(ct);
        } catch (Throwable e) {
            e.printStackTrace();
            return plain;
        }
    }

    /**
     * Decrypts an encrypted value, or returns a legacy plain value unchanged. Returns {@code null}
     * only when an encrypted value cannot be decrypted (a lost/rotated Keystore key), which the
     * caller treats as "no key stored".
     */
    @Nullable
    public static String decrypt(@Nullable String stored) {
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        if (!isEncrypted(stored)) {
            // Legacy plain value — hand it back so the caller can re-save it encrypted.
            return stored;
        }
        if (!supported()) {
            return null;
        }
        try {
            String[] parts = stored.substring(PREFIX.length()).split(":", 2);
            if (parts.length != 2) return null;
            byte[] iv = unbase64(parts[0]);
            byte[] ct = unbase64(parts[1]);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            AlgorithmParameterSpec spec = new GCMParameterSpec(TAG_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec);
            return new String(cipher.doFinal(ct), "UTF-8");
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }

    @NonNull
    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build();
        generator.init(spec);
        return generator.generateKey();
    }

    @NonNull
    private static String base64(@NonNull byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    @NonNull
    private static byte[] unbase64(@NonNull String s) {
        return Base64.decode(s, Base64.NO_WRAP);
    }
}
