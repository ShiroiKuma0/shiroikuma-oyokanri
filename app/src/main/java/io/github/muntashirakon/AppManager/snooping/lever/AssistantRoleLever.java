// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.snooping.lever;

import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.UserHandleHidden;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.List;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.runner.Runner;
import io.github.muntashirakon.AppManager.self.SelfPermissions;
import io.github.muntashirakon.AppManager.snooping.SnoopingComponents;
import io.github.muntashirakon.AppManager.utils.ContextUtils;

/**
 * Fork: is this app the phone's assistant?
 * <p>
 * The assistant is handed the screen. {@code ASSIST_STRUCTURE} gives it the view
 * hierarchy of whatever is in front of the user — every label and every text
 * field — and {@code ASSIST_SCREENSHOT} gives it the pixels. Those two ops are on
 * this page in their own right, but they only describe <em>what the assistant is
 * given</em>; the switch that decides <em>whether this app is the assistant at
 * all</em> is the role holder, and that is this row.
 * <p>
 * Two mechanisms, because the platform has two: the {@code RoleManager} holder
 * for {@code android.app.role.ASSISTANT} (Android 10+) and the older
 * {@code Settings.Secure} pair {@code assistant} / {@code voice_interaction_service}
 * that still decides on some builds and OEM skins. Both are read, and blocking
 * clears both — an app left in one of them keeps the job.
 */
public class AssistantRoleLever implements SnoopingLever {
    private static final String ROLE_ASSISTANT = "android.app.role.ASSISTANT";
    private static final String PERM_MANAGE_ROLE_HOLDERS = "android.permission.MANAGE_ROLE_HOLDERS";
    private static final String SETTING_ASSISTANT = "assistant";
    private static final String SETTING_VOICE_INTERACTION = "voice_interaction_service";

    @WorkerThread
    @Override
    public boolean isApplicable(@NonNull PackageInfo packageInfo, @UserIdInt int userId) {
        // Only an app that could hold the role: it declares a voice-interaction
        // service, or an activity that answers ACTION_ASSIST.
        return !SnoopingComponents.servicesBoundWith(packageInfo, userId, SnoopingComponents.BIND_VOICE_INTERACTION)
                .isEmpty()
                || !SnoopingComponents.assistActivities(packageInfo).isEmpty();
    }

    @Override
    public boolean isModifiable() {
        return SelfPermissions.checkSelfOrRemotePermission(PERM_MANAGE_ROLE_HOLDERS)
                || SelfPermissions.checkSelfOrRemotePermission(android.Manifest.permission.WRITE_SECURE_SETTINGS);
    }

    @WorkerThread
    @Override
    public boolean isAllowed(@NonNull PackageInfo packageInfo, @UserIdInt int userId) {
        return holdsRole(packageInfo.packageName, userId) || namedInSettings(packageInfo.packageName, userId);
    }

    @WorkerThread
    @Override
    public boolean setAllowed(@NonNull PackageInfo packageInfo, @UserIdInt int userId, boolean allowed) {
        if (allowed) {
            // Putting it back is a best effort: the role manager legitimately
            // refuses candidates it does not consider eligible, and we report
            // whatever it decided rather than pretending.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Runner.runCommand("cmd role add-role-holder --user " + userId + " " + ROLE_ASSISTANT
                        + " " + packageInfo.packageName);
            }
            return isAllowed(packageInfo, userId);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Runner.runCommand("cmd role remove-role-holder --user " + userId + " " + ROLE_ASSISTANT
                    + " " + packageInfo.packageName);
        }
        if (namedInSettings(packageInfo.packageName, userId)) {
            Runner.runCommand("settings delete --user " + userId + " secure " + SETTING_ASSISTANT);
            Runner.runCommand("settings delete --user " + userId + " secure " + SETTING_VOICE_INTERACTION);
        }
        return !isAllowed(packageInfo, userId);
    }

    @Nullable
    @Override
    public Boolean defaultAllowed() {
        return Boolean.FALSE;
    }

    @Nullable
    @Override
    public CharSequence detail(@NonNull Context context) {
        return context.getString(R.string.snooping_detail_role, ROLE_ASSISTANT);
    }

    @WorkerThread
    private static boolean holdsRole(@NonNull String packageName, @UserIdInt int userId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false;
        }
        Runner.Result result = Runner.runCommand("cmd role get-role-holders --user " + userId + " " + ROLE_ASSISTANT);
        if (result == null || !result.isSuccessful()) {
            return false;
        }
        for (String line : result.getOutputAsList()) {
            if (line.contains(packageName)) {
                return true;
            }
        }
        return false;
    }

    @WorkerThread
    private static boolean namedInSettings(@NonNull String packageName, @UserIdInt int userId) {
        for (String key : new String[]{SETTING_ASSISTANT, SETTING_VOICE_INTERACTION}) {
            String value = readSecure(key, userId);
            if (value == null || value.isEmpty() || "null".equals(value)) {
                continue;
            }
            ComponentName component = ComponentName.unflattenFromString(value);
            if (component != null ? packageName.equals(component.getPackageName()) : value.startsWith(packageName)) {
                return true;
            }
        }
        return false;
    }

    @WorkerThread
    @Nullable
    private static String readSecure(@NonNull String key, @UserIdInt int userId) {
        if (userId == UserHandleHidden.myUserId()) {
            return Settings.Secure.getString(ContextUtils.getContext().getContentResolver(), key);
        }
        Runner.Result result = Runner.runCommand("settings get --user " + userId + " secure " + key);
        if (result == null || !result.isSuccessful()) {
            return null;
        }
        List<String> lines = result.getOutputAsList();
        return lines.isEmpty() ? null : lines.get(0).trim();
    }
}
