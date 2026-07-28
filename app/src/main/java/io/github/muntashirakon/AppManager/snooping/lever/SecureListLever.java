// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.snooping.lever;

import android.Manifest;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.pm.PackageInfo;
import android.os.UserHandleHidden;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.muntashirakon.AppManager.runner.Runner;
import io.github.muntashirakon.AppManager.self.SelfPermissions;
import io.github.muntashirakon.AppManager.snooping.SnoopingComponents;
import io.github.muntashirakon.AppManager.utils.ContextUtils;

/**
 * Fork: the two capabilities that are really governed by a colon-separated list
 * in {@code Settings.Secure} — an <b>accessibility service</b> and a
 * <b>notification listener</b>.
 * <p>
 * These are the most invasive things an unprivileged app can hold on Android: an
 * accessibility service sees every window, every text field and every keystroke
 * of every other app, and a notification listener sees the content of every
 * notification, including the one-time codes messaging apps put there. Neither is
 * gated by the app-op the Snooping tab used to show for them —
 * {@code ACCESS_ACCESSIBILITY} and {@code ACCESS_NOTIFICATIONS} are checked in
 * other, narrower places. The real gate is whether the service's component is
 * named in the system's list, which is what this writes.
 * <p>
 * <b>The write cannot go through {@code Settings.Secure.putString}</b>: that runs
 * in <em>our</em> process under <em>our</em> uid, and 応用管理 is an ordinary app
 * with no {@code WRITE_SECURE_SETTINGS} (see
 * {@code ActivityManagerCompat.startActivityViaAssist}, which has to demand the
 * permission of itself before it may do the same thing). It goes through
 * {@link Runner} instead, which executes as the privileged side — exactly what
 * {@code adb shell settings put secure …} does.
 */
abstract class SecureListLever implements SnoopingLever {
    /** {@code Settings.Secure} key holding the colon-separated component list. */
    @NonNull
    private final String mListKey;
    /** The permission the system binds these services with. */
    @NonNull
    private final String mBindPermission;

    SecureListLever(@NonNull String listKey, @NonNull String bindPermission) {
        mListKey = listKey;
        mBindPermission = bindPermission;
    }

    /**
     * A second setting that must be turned off once the list empties, or left
     * alone ({@code null}). Accessibility has one; notification listeners do not.
     */
    @Nullable
    String masterSwitchKey() {
        return null;
    }

    @WorkerThread
    @Override
    public boolean isApplicable(@NonNull PackageInfo packageInfo, @UserIdInt int userId) {
        return !components(packageInfo, userId).isEmpty();
    }

    @Override
    public boolean isModifiable() {
        return SelfPermissions.checkSelfOrRemotePermission(Manifest.permission.WRITE_SECURE_SETTINGS);
    }

    @WorkerThread
    @Override
    public boolean isAllowed(@NonNull PackageInfo packageInfo, @UserIdInt int userId) {
        Set<String> enabled = readList(userId);
        for (ComponentName component : components(packageInfo, userId)) {
            if (contains(enabled, component)) {
                return true;
            }
        }
        return false;
    }

    @WorkerThread
    @Override
    public boolean setAllowed(@NonNull PackageInfo packageInfo, @UserIdInt int userId, boolean allowed) {
        List<ComponentName> components = components(packageInfo, userId);
        if (components.isEmpty()) {
            return false;
        }
        Set<String> enabled = readList(userId);
        boolean changed = false;
        for (ComponentName component : components) {
            if (allowed) {
                changed |= enabled.add(component.flattenToString());
            } else {
                changed |= removeAll(enabled, component);
            }
        }
        if (!changed) {
            // Already in the requested shape — nothing to write, and reporting
            // success is correct: the platform enforces what was asked for.
            return true;
        }
        if (!writeList(userId, enabled)) {
            return false;
        }
        String masterKey = masterSwitchKey();
        if (masterKey != null) {
            // Emptying the list without clearing the master switch leaves the
            // framework believing accessibility is on with nothing to serve it.
            // Turning it back on is the system's job when a service is enabled
            // through Settings, so only ever write the off direction here.
            if (enabled.isEmpty()) {
                writeSecure(userId, masterKey, "0");
            } else if (allowed) {
                writeSecure(userId, masterKey, "1");
            }
        }
        // Re-read rather than trust the write: this is a shell round-trip, and a
        // refusal is silent.
        return isAllowed(packageInfo, userId) == allowed;
    }

    @Nullable
    @Override
    public Boolean defaultAllowed() {
        // A freshly installed app is in nobody's list. This one is unambiguous,
        // which is what makes "you turned this off" worth highlighting.
        return Boolean.FALSE;
    }

    @WorkerThread
    @NonNull
    List<ComponentName> components(@NonNull PackageInfo packageInfo, @UserIdInt int userId) {
        return SnoopingComponents.servicesBoundWith(packageInfo, userId, mBindPermission);
    }

    /**
     * The current list. Read in-process for our own user (cheap and always
     * available) and through the shell for any other, where our {@code Settings}
     * client would silently read the wrong user's value.
     */
    @WorkerThread
    @NonNull
    private Set<String> readList(@UserIdInt int userId) {
        String raw;
        if (userId == UserHandleHidden.myUserId()) {
            raw = Settings.Secure.getString(ContextUtils.getContext().getContentResolver(), mListKey);
        } else {
            raw = readSecureViaShell(userId, mListKey);
        }
        Set<String> components = new LinkedHashSet<>();
        if (raw == null || raw.isEmpty() || "null".equals(raw)) {
            return components;
        }
        for (String entry : raw.split(":")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                components.add(trimmed);
            }
        }
        return components;
    }

    @WorkerThread
    private boolean writeList(@UserIdInt int userId, @NonNull Set<String> components) {
        if (components.isEmpty()) {
            // "settings put … ''" stores a literal empty string on some builds and
            // is rejected on others; deleting the row is what the framework treats
            // as "no listeners" everywhere.
            return run("settings delete --user " + userId + " secure " + mListKey);
        }
        return writeSecure(userId, mListKey, TextUtils.join(":", components));
    }

    @WorkerThread
    private boolean writeSecure(@UserIdInt int userId, @NonNull String key, @NonNull String value) {
        return run("settings put --user " + userId + " secure " + key + " '" + value.replace("'", "'\\''") + "'");
    }

    @WorkerThread
    @Nullable
    private String readSecureViaShell(@UserIdInt int userId, @NonNull String key) {
        Runner.Result result = Runner.runCommand("settings get --user " + userId + " secure " + key);
        if (result == null || !result.isSuccessful()) {
            return null;
        }
        List<String> lines = result.getOutputAsList();
        return lines.isEmpty() ? null : lines.get(0).trim();
    }

    @WorkerThread
    private boolean run(@NonNull String command) {
        Runner.Result result = Runner.runCommand(command);
        return result != null && result.isSuccessful();
    }

    /**
     * Whether the list names this component. Both the flattened forms the
     * framework accepts are matched — {@code pkg/.Cls} appears in these lists as
     * often as {@code pkg/pkg.Cls}, and a mismatch would read as "not enabled"
     * for a service that very much is.
     */
    private static boolean contains(@NonNull Set<String> list, @NonNull ComponentName component) {
        return list.contains(component.flattenToString()) || list.contains(component.flattenToShortString());
    }

    private static boolean removeAll(@NonNull Set<String> list, @NonNull ComponentName component) {
        boolean removed = list.remove(component.flattenToString());
        removed |= list.remove(component.flattenToShortString());
        // Anything else that resolves to the same component (a differently
        // written package/class pair) must go too, or the block would not take.
        List<String> stale = new ArrayList<>();
        for (String entry : list) {
            ComponentName parsed = ComponentName.unflattenFromString(entry);
            if (parsed != null && parsed.getPackageName().equals(component.getPackageName())
                    && parsed.getClassName().equals(component.getClassName())) {
                stale.add(entry);
            }
        }
        removed |= list.removeAll(stale);
        return removed;
    }
}
