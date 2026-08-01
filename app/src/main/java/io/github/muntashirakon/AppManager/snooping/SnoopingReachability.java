// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.snooping;

import android.annotation.UserIdInt;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.muntashirakon.AppManager.utils.ContextUtils;

/**
 * Fork: can this app <em>ever</em> exercise a capability, judged from its own
 * manifest?
 * <p>
 * An app-op switch answers "is it permitted"; this answers the question that
 * actually decides whether a row belongs on the Snooping tab — 白い熊's rule:
 * <b>the page shows what is snooping, or what can be made to snoop</b>. An op
 * sitting at its default {@code allow} means nothing when the app has no way to
 * reach the capability behind it: an app with no {@code RECORD_AUDIO} cannot
 * record during a call however permissive {@code PHONE_CALL_MICROPHONE} is, and
 * an app with no VPN service cannot run a VPN however permissive
 * {@code ACTIVATE_VPN} is.
 * <p>
 * Unlike {@link SnoopingImmovable}, this needs no failed attempt and nothing
 * persisted: it is recomputed on every load from the app's own manifest, so an
 * update that adds the missing permission or service brings the row back by
 * itself.
 * <p>
 * <b>Conservative by construction.</b> A wrong answer here hides a real
 * capability, so a row is dropped only where the prerequisite is unambiguous —
 * a runtime permission that is simply not declared, a bound service that does
 * not exist, or an op no unprivileged app can reach. Anything unknown, any
 * capability with no manifest gate at all ({@code PROJECT_MEDIA},
 * {@code READ_CLIPBOARD}, the background ops), and any case where the platform
 * refused to answer (see {@link #services()}) is treated as reachable. Note the
 * distinction that matters: <b>"the app declares none" is an answer, not a
 * missing one</b>. ⋮ → <i>Show all</i> lists the
 * dropped rows, where blocking them still records a pre-set for a future version
 * that does declare what it needs.
 */
public final class SnoopingReachability {
    private static final String PERM_RECORD_AUDIO = "android.permission.RECORD_AUDIO";
    private static final String PERM_CAMERA = "android.permission.CAMERA";
    private static final String PERM_READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE";
    private static final String PERM_MANAGE_EXTERNAL_STORAGE = "android.permission.MANAGE_EXTERNAL_STORAGE";
    private static final String PERM_READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES";
    private static final String PERM_READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO";
    private static final String PERM_READ_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO";
    private static final String PERM_READ_PRIVILEGED_PHONE_STATE = "android.permission.READ_PRIVILEGED_PHONE_STATE";

    private static final String PERM_WRITE_EXTERNAL_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE";

    private static final String BIND_NOTIFICATION_LISTENER = SnoopingComponents.BIND_NOTIFICATION_LISTENER;
    private static final String BIND_VPN = SnoopingComponents.BIND_VPN;
    private static final String BIND_VOICE_INTERACTION = SnoopingComponents.BIND_VOICE_INTERACTION;

    /** Any of these makes an app a plausible default-SMS candidate. */
    private static final String[] SMS_PERMISSIONS = {
            "android.permission.READ_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.SEND_SMS",
            "android.permission.WRITE_SMS",
            "android.permission.BROADCAST_SMS",
    };

    @NonNull
    private final PackageInfo mPackageInfo;
    @UserIdInt
    private final int mUserId;
    @NonNull
    private final Set<String> mRequestedPermissions;
    /** Lazy: an ACTION_ASSIST activity is the second way to become the assistant. */
    @Nullable
    private Boolean mAssistCandidate;
    /** Lazy, and deliberately tri-state — see {@link #services()}. */
    @Nullable
    private ServiceInfo[] mServices;
    private boolean mServicesResolved;
    private boolean mServicesUnknown;

    private SnoopingReachability(@NonNull PackageInfo packageInfo, @UserIdInt int userId) {
        mPackageInfo = packageInfo;
        mUserId = userId;
        mRequestedPermissions = packageInfo.requestedPermissions != null
                ? new HashSet<>(Arrays.asList(packageInfo.requestedPermissions))
                : new HashSet<>();
    }

    @NonNull
    public static SnoopingReachability forPackage(@NonNull PackageInfo packageInfo, @UserIdInt int userId) {
        return new SnoopingReachability(packageInfo, userId);
    }

    /**
     * @return {@code false} only when the app's manifest makes this capability
     *         unreachable beyond doubt.
     */
    @WorkerThread
    public boolean canEverUse(@NonNull SnoopingCatalog.Resolved capability) {
        switch (capability.entry.id) {
            // ── Reading media needs one of the media permissions, or all-files
            //    access, or (below Android 13) legacy read access.
            case "media_images_read":
            case "media_visual_user_selected":
                return hasAny(PERM_READ_MEDIA_IMAGES, PERM_READ_EXTERNAL_STORAGE, PERM_MANAGE_EXTERNAL_STORAGE);
            case "media_video_read":
                return hasAny(PERM_READ_MEDIA_VIDEO, PERM_READ_EXTERNAL_STORAGE, PERM_MANAGE_EXTERNAL_STORAGE);
            case "media_audio_read":
                return hasAny(PERM_READ_MEDIA_AUDIO, PERM_READ_EXTERNAL_STORAGE, PERM_MANAGE_EXTERNAL_STORAGE);

            // ── Bypassing scoped storage buys nothing without storage access.
            case "storage_legacy":
                return hasAny(PERM_READ_EXTERNAL_STORAGE, PERM_WRITE_EXTERNAL_STORAGE, PERM_MANAGE_EXTERNAL_STORAGE);

            // ── No microphone permission, no recording — hotword, ambient
            //    trigger, sandboxed or in-call alike.
            case "microphone_hotword":
            case "microphone_ambient_trigger":
            case "microphone_sandboxed":
            case "microphone_call":
                return hasAny(PERM_RECORD_AUDIO);
            case "camera_sandboxed":
            case "camera_call":
                return hasAny(PERM_CAMERA);

            // ── Writing to the SMS provider is the default-SMS app's privilege;
            //    an app declaring no SMS permission at all cannot hold that role.
            case "sms_write":
            case "sms_icc_read":
                return hasAny(SMS_PERMISSIONS);

            // ── Device identifiers are signature/privileged-only since Android 10.
            case "device_identifiers":
                return isSystemApp() || hasAny(PERM_READ_PRIVILEGED_PHONE_STATE);

            // ── These are only ever exercised through a component the app must
            //    declare, and the system binds it by that permission.
            //
            //    NOT "accessibility" (白い熊, 2026-08-01). ACCESS_ACCESSIBILITY
            //    used to be gated on a BIND_ACCESSIBILITY_SERVICE service here,
            //    and measurement on the Mate XT supported the rule — all ten
            //    packages that had ever exercised the op declared such a service.
            //    It is off anyway: this is the one capability that subsumes every
            //    other on the page, the op costs nothing to block, and a rule that
            //    is right about today's manifest still hides the row from the app
            //    that adds the service in its next update. Being wrong here is not
            //    symmetric, so the row is now shown for every app.
            case "notifications_read":
                return hasServiceBoundWith(BIND_NOTIFICATION_LISTENER);
            case "vpn":
            case "vpn_establish":
            case "vpn_establish_manager":
                return hasServiceBoundWith(BIND_VPN);
            case "assist_screenshot":
            case "assist_structure":
                return hasServiceBoundWith(BIND_VOICE_INTERACTION) || isAssistCandidate();

            default:
                // No unambiguous manifest prerequisite — screen capture (consent
                // dialog), clipboard, background running, and everything the tier
                // system already governs.
                return true;
        }
    }

    private boolean hasAny(@NonNull String... permissions) {
        for (String permission : permissions) {
            if (mRequestedPermissions.contains(permission)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSystemApp() {
        ApplicationInfo applicationInfo = mPackageInfo.applicationInfo;
        return applicationInfo != null
                && (applicationInfo.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
    }

    /**
     * The app's services, or {@code null} with {@link #mServicesUnknown} set when
     * the platform would not tell us.
     * <p>
     * <b>Landmine (cost a build, +11):</b> {@code PackageInfo.services} is
     * {@code null} in <em>two</em> completely different situations — the caller
     * did not pass {@code GET_SERVICES}, and the app declares no services at all.
     * The first is "no data" and must fail open; the second is the strongest
     * evidence of unreachability there is. Guessing wrong the safe-looking way
     * kept every service-gated row on the page for exactly the apps that most
     * deserved to lose them. The ambiguity is not resolvable from a
     * {@code PackageInfo}, so a null is re-queried here with {@code GET_SERVICES}
     * explicitly; only a failed query counts as unknown.
     */
    @Nullable
    private ServiceInfo[] services() {
        if (mServicesResolved) {
            return mServices;
        }
        mServicesResolved = true;
        SnoopingComponents.Services services = SnoopingComponents.services(mPackageInfo, mUserId);
        mServices = services.services;
        mServicesUnknown = services.unknown;
        return mServices;
    }

    /**
     * Whether the app declares a service the system binds with this permission.
     * Disabled components count — they can be switched back on.
     */
    private boolean hasServiceBoundWith(@NonNull String bindPermission) {
        ServiceInfo[] services = services();
        if (mServicesUnknown) {
            // No answer from the platform: never hide a row on a guess.
            return true;
        }
        if (services == null) {
            // Asked and answered: the app declares no services whatsoever, so
            // nothing can ever be bound with this permission.
            return false;
        }
        for (ServiceInfo service : services) {
            if (service != null && bindPermission.equals(service.permission)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The other route to the assistant ops: an activity answering
     * {@link Intent#ACTION_ASSIST}, which Settings can promote to the assist app.
     * Intent filters are not carried in {@link PackageInfo}, so this asks the
     * package manager. Fails open on any refusal.
     */
    private boolean isAssistCandidate() {
        if (mAssistCandidate != null) {
            return mAssistCandidate;
        }
        boolean candidate = true;
        try {
            Context context = ContextUtils.getContext();
            List<ResolveInfo> handlers = context.getPackageManager()
                    .queryIntentActivities(new Intent(Intent.ACTION_ASSIST), 0);
            candidate = false;
            for (ResolveInfo info : handlers) {
                if (info.activityInfo != null && mPackageInfo.packageName.equals(info.activityInfo.packageName)) {
                    candidate = true;
                    break;
                }
            }
        } catch (Throwable ignore) {
            // No package-manager answer: assume it could be the assistant.
        }
        mAssistCandidate = candidate;
        return candidate;
    }
}
