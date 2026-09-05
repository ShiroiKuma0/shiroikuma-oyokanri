// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.settings;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.backup.CryptoUtils;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.utils.AppPref;

/**
 * Fork (白い熊, +117): an import may <b>tighten</b> a security setting and never loosen one.
 *
 * <p><b>Why this exists.</b> {@link SettingsBackupManager} replays whole {@code shared_prefs}
 * files, so every value in an archive lands verbatim — including the ones that decide whether the
 * app is locked, whether installs are verified, and whether backups are encrypted. An archive is
 * not necessarily something 白い熊 made five minutes ago: it can be months old, from another
 * phone, or handed over by someone else. Restoring a year-old backup should not quietly switch
 * off the screen lock that has been on since, and nothing in a wholesale file copy could notice.
 *
 * <p><b>Where the rule belongs, and why here.</b> Only the app that owns a preference knows
 * whether it is security-relevant, so the check cannot live in whatever handed us the archive —
 * and a caller-side check is walkable in any case, since an archive can arrive from anywhere.
 * The caller's contribution is provenance; the judgment is the owner's. This is 応用管理 applying
 * that rule to itself. (The same rule was written into §2a of the sister-app contract on
 * 2026-09-05.)
 *
 * <p><b>The asymmetry is the whole design.</b> A setting is held back only when the <em>current</em>
 * value is the safe one and the archive would move it away — including by <b>omitting</b> it,
 * since a missing entry silently reverts to the default and the default is not always the safe
 * value. An import that makes something safer is always allowed through, so this can never fight
 * a deliberate tightening.
 *
 * <p><b>LANDMINE — this must edit the file, never the SharedPreferences API.</b> Import replaces
 * prefs files on disk while the process still holds them cached; that is why the panel offers
 * "Restart now" and kills the process rather than exiting cleanly. Any {@code edit().apply()}
 * here would write the whole cached map back over the file just imported and undo the import.
 * So the correction is made to the XML text on its way to disk.
 */
public final class SecurityPrefGuard {
    public static final String TAG = SecurityPrefGuard.class.getSimpleName();

    /** The file this guard applies to: everything guarded lives in AppPref's own store. */
    private static final String GUARDED_FILE = "preferences.xml";

    /** One boolean setting, and the value of it that is the safe one. */
    private static final class BoolGuard {
        final AppPref.PrefKey key;
        final boolean safeValue;
        @StringRes
        final int labelRes;

        BoolGuard(AppPref.PrefKey key, boolean safeValue, @StringRes int labelRes) {
            this.key = key;
            this.safeValue = safeValue;
            this.labelRes = labelRes;
        }
    }

    /**
     * The guarded set. Deliberately short: every entry here is a setting whose weakening is a
     * real loss of protection rather than a change of taste, and a long list of "sort of
     * important" settings would make an import unpredictable instead of safe.
     */
    private static final BoolGuard[] BOOL_GUARDS = {
            // The app lock itself, and the auto-lock that makes it mean anything.
            new BoolGuard(AppPref.PrefKey.PREF_ENABLE_SCREEN_LOCK_BOOL, true, R.string.screen_lock),
            new BoolGuard(AppPref.PrefKey.PREF_ENABLE_AUTO_LOCK_BOOL, true, R.string.pref_enable_auto_lock),
            // Installer: verification off, or tracker blocking off, is strictly a weakening.
            new BoolGuard(AppPref.PrefKey.PREF_INSTALLER_DISABLE_VERIFICATION_BOOL, false, R.string.pref_disable_apk_verification),
            new BoolGuard(AppPref.PrefKey.PREF_INSTALLER_BLOCK_TRACKERS_BOOL, true, R.string.block_trackers),
            // Logs: sensitive info is omitted, or it is not.
            new BoolGuard(AppPref.PrefKey.PREF_LOG_VIEWER_OMIT_SENSITIVE_INFO_BOOL, true, R.string.omit_sensitive_info),
    };

    /** Settings held back by the most recent import, for the panel to report. */
    private static final List<String> sHeld = Collections.synchronizedList(new ArrayList<>());

    private SecurityPrefGuard() {
    }

    /** Called at the start of an import; the previous run's findings are not this run's. */
    public static void reset() {
        sHeld.clear();
    }

    /** Names of the settings this import refused to loosen. Empty for an ordinary import. */
    @NonNull
    public static List<String> getHeldSettings() {
        synchronized (sHeld) {
            return new ArrayList<>(sHeld);
        }
    }

    /** Whether this entry is the one file the guard inspects. */
    public static boolean guards(@NonNull String prefsFileName) {
        return GUARDED_FILE.equals(prefsFileName);
    }

    /**
     * Correct an incoming {@code preferences.xml} so that it cannot loosen anything guarded.
     *
     * <p>Never throws and never returns null: a guard that breaks an import would be a worse
     * failure than the one it prevents, so anything unexpected passes the bytes through
     * unchanged.
     */
    @NonNull
    public static byte[] apply(@NonNull Context context, @NonNull byte[] incoming) {
        try {
            String xml = new String(incoming, StandardCharsets.UTF_8);
            if (!xml.contains("</map>")) {
                // Not a preferences file we recognise. Leave it exactly as it came.
                return incoming;
            }
            String out = xml;
            for (BoolGuard guard : BOOL_GUARDS) {
                String key = AppPref.PrefKey.keyOf(guard.key);
                boolean current = AppPref.getBoolean(guard.key);
                if (current != guard.safeValue) {
                    // Already not the safe value here, so an import cannot LOOSEN it; whatever
                    // the archive says stands, including a tightening.
                    continue;
                }
                Boolean arriving = readBoolean(xml, key);
                if (arriving != null && arriving == guard.safeValue) {
                    continue;
                }
                out = writeBoolean(out, key, guard.safeValue);
                held(context, guard.labelRes);
            }
            out = guardEncryption(context, xml, out);
            if (out.equals(xml)) {
                return incoming;
            }
            return out.getBytes(StandardCharsets.UTF_8);
        } catch (Throwable th) {
            Log.w(TAG, "could not inspect the imported preferences; importing them unchanged", th);
            return incoming;
        }
    }

    /**
     * Backup encryption is a string rather than a flag, so "safe" is not one value: it is
     * <em>any</em> mode other than none. An archive may switch between AES, RSA, ECC and PGP —
     * that is a choice — but it may not turn encryption off on a phone where it is on.
     */
    @NonNull
    private static String guardEncryption(@NonNull Context context, @NonNull String xml, @NonNull String out) {
        String key = AppPref.PrefKey.keyOf(AppPref.PrefKey.PREF_ENCRYPTION_STR);
        String current = AppPref.getString(AppPref.PrefKey.PREF_ENCRYPTION_STR);
        if (TextUtils.isEmpty(current) || CryptoUtils.MODE_NO_ENCRYPTION.equals(current)) {
            return out;
        }
        String arriving = readString(xml, key);
        // Absent counts as unsafe: the default is no encryption, so omitting the entry turns it
        // off exactly as effectively as setting it to "none".
        if (arriving != null && !CryptoUtils.MODE_NO_ENCRYPTION.equals(arriving)) {
            return out;
        }
        held(context, R.string.encryption);
        return writeString(out, key, current);
    }

    private static void held(@NonNull Context context, @StringRes int labelRes) {
        String label = context.getString(labelRes);
        Log.w(TAG, "kept the current value of \"%s\": the archive would have weakened it", label);
        sHeld.add(label);
    }

    // ── Reading and writing the SharedPreferences XML ────────────────────────
    //
    // Targeted edits on the one entry involved, rather than a parse-and-rebuild: rewriting the
    // whole file would have to reproduce every type, order and escape exactly, and a bug there
    // would corrupt settings that have nothing to do with this guard. If the pattern is not
    // found the entry is inserted instead, which is the case that matters most — an OMITTED
    // guarded key is the quiet way to weaken something.

    @Nullable
    private static Boolean readBoolean(@NonNull String xml, @NonNull String key) {
        Matcher m = Pattern.compile("<boolean\\s+name=\"" + Pattern.quote(key)
                + "\"\\s+value=\"(true|false)\"\\s*/>").matcher(xml);
        return m.find() ? Boolean.valueOf("true".equals(m.group(1))) : null;
    }

    @NonNull
    private static String writeBoolean(@NonNull String xml, @NonNull String key, boolean value) {
        String replacement = "<boolean name=\"" + key + "\" value=\"" + value + "\" />";
        Pattern p = Pattern.compile("<boolean\\s+name=\"" + Pattern.quote(key)
                + "\"\\s+value=\"(?:true|false)\"\\s*/>");
        Matcher m = p.matcher(xml);
        if (m.find()) {
            return m.replaceFirst(Matcher.quoteReplacement(replacement));
        }
        return insert(xml, replacement);
    }

    @Nullable
    private static String readString(@NonNull String xml, @NonNull String key) {
        Matcher m = Pattern.compile("<string\\s+name=\"" + Pattern.quote(key) + "\">(.*?)</string>",
                Pattern.DOTALL).matcher(xml);
        if (m.find()) {
            return m.group(1);
        }
        // A self-closing entry is an empty string, which is not the same as absent.
        Matcher empty = Pattern.compile("<string\\s+name=\"" + Pattern.quote(key) + "\"\\s*/>").matcher(xml);
        return empty.find() ? "" : null;
    }

    @NonNull
    private static String writeString(@NonNull String xml, @NonNull String key, @NonNull String value) {
        String replacement = "<string name=\"" + key + "\">" + value + "</string>";
        Matcher m = Pattern.compile("<string\\s+name=\"" + Pattern.quote(key) + "\">.*?</string>",
                Pattern.DOTALL).matcher(xml);
        if (m.find()) {
            return m.replaceFirst(Matcher.quoteReplacement(replacement));
        }
        Matcher empty = Pattern.compile("<string\\s+name=\"" + Pattern.quote(key) + "\"\\s*/>").matcher(xml);
        if (empty.find()) {
            return empty.replaceFirst(Matcher.quoteReplacement(replacement));
        }
        return insert(xml, replacement);
    }

    /** Put an entry back into the map, immediately before it closes. */
    @NonNull
    private static String insert(@NonNull String xml, @NonNull String element) {
        int at = xml.lastIndexOf("</map>");
        if (at < 0) {
            return xml;
        }
        return xml.substring(0, at) + "    " + element + "\n" + xml.substring(at);
    }
}
