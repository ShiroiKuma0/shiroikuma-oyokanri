// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.snooping.lever;

import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.PackageInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.WorkerThread;

import io.github.muntashirakon.AppManager.snooping.SnoopingState;

/**
 * Fork: a snooping capability whose switch is <b>not</b> an app-op and not a
 * runtime permission.
 * <p>
 * The Snooping tab began as an app-ops page, and the ops turned out to be the
 * easy half. The capabilities that matter most on a modern phone are gated
 * somewhere else entirely — a system list in {@code Settings.Secure} (an
 * accessibility service, a notification listener), a per-uid network policy, a
 * role holder, the doze whitelist. Each of those is reachable from ADB/Shizuku,
 * each is per-app, and none of them fits {@code setMode(op, uid, pkg, mode)}. A
 * lever is the third kind of row: it answers the same four questions the op path
 * answers, in whatever way its particular corner of the system requires.
 * <p>
 * Implementations must be <b>honest about failure</b>. {@link #setAllowed} returns
 * whether the platform actually did it — the caller records the decision only if
 * it did, following the same rule as the op path: never store a decision a write
 * did not achieve.
 */
public interface SnoopingLever {
    /**
     * Whether this capability means anything for this app — an app with no
     * accessibility service can never have one enabled, so the row would be a
     * switch with nothing behind it. Judged from the manifest, never from a
     * stored state, so an update that adds the component brings the row back by
     * itself.
     */
    @WorkerThread
    boolean isApplicable(@NonNull PackageInfo packageInfo, @UserIdInt int userId);

    /** Whether we hold the privileges this particular lever needs. */
    boolean isModifiable();

    /** Whether the app has the capability right now. */
    @WorkerThread
    boolean isAllowed(@NonNull PackageInfo packageInfo, @UserIdInt int userId);

    /**
     * Apply the state.
     *
     * @return {@code true} only when the platform actually took it — a refused or
     *         silently-discarded write must report {@code false} so the caller
     *         does not record a decision that never landed.
     */
    @WorkerThread
    boolean setAllowed(@NonNull PackageInfo packageInfo, @UserIdInt int userId, boolean allowed);

    /**
     * The state a fresh install lands in, or {@code null} when it cannot be said.
     * Used exactly like the op path's {@code opToDefaultMode}: a decision equal to
     * the default is not stored, and a live state equal to it is not highlighted.
     */
    @Nullable
    Boolean defaultAllowed();

    /**
     * The small print under the row — what the lever actually is, so the switch
     * never has to be taken on trust. May be {@code null} for no chip.
     */
    @Nullable
    CharSequence detail(@NonNull Context context);

    // ── Levers with more than two positions ──────────────────────────────────
    // Most have exactly two and need none of the below. A lever that can express
    // a middle ground overrides these four together: the states it offers, how to
    // read and write them, and what to call them — a generic "Only while in use"
    // would be a lie on a row whose middle state means something else.

    /** The states this lever can express, most permissive first. */
    @NonNull
    default int[] supportedStates() {
        return new int[]{SnoopingState.ALLOWED, SnoopingState.BLOCKED};
    }

    @WorkerThread
    @SnoopingState.State
    default int getState(@NonNull PackageInfo packageInfo, @UserIdInt int userId) {
        return SnoopingState.fromAllowed(isAllowed(packageInfo, userId));
    }

    /** @return {@code true} only when the platform actually took it. */
    @WorkerThread
    default boolean setState(@NonNull PackageInfo packageInfo, @UserIdInt int userId,
                             @SnoopingState.State int state) {
        return setAllowed(packageInfo, userId, SnoopingState.isAllowed(state));
    }

    /** A label for this state specific to this lever, or {@code 0} for the generic one. */
    @StringRes
    default int stateLabelRes(@SnoopingState.State int state) {
        return 0;
    }
}
