// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.snooping;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import io.github.muntashirakon.AppManager.details.struct.AppDetailsAppOpItem;
import io.github.muntashirakon.AppManager.details.struct.AppDetailsSnoopingItem;

/**
 * Fork: when a capability was last <b>used</b> and last <b>denied</b>, for one
 * row of the Snooping tab (白い熊, +99).
 * <p>
 * The numbers come from {@code AppOpsService}'s own per-op bookkeeping, which the
 * upstream <i>App ops</i> tab already renders — {@code lastAccessTime} and
 * {@code lastRejectTime}. Nothing new is asked of the platform: the row already
 * holds the {@link AppDetailsAppOpItem} these live on.
 * <p>
 * <b>The point of this class is the difference between "nothing happened" and
 * "we cannot tell" (白い熊, +99).</b> A blank line reads as <i>clean</i>, and for
 * most of the interesting cases a blank would be a lie — so every answer here is
 * a tri-state, and the row always says which one it is:
 * <ul>
 *   <li>{@link #KNOWN} — a timestamp. The strongest thing this page can show:
 *       "denied 3 minutes ago" is proof the switch is doing work, and "last used
 *       four months ago" is the argument for turning one off.</li>
 *   <li>{@link #NEVER} — the platform keeps this record for this op and it is
 *       empty. A fact, not an absence.</li>
 *   <li>{@link #UNKNOWN} — we have no source. Either app-ops would not answer at
 *       all, or the capability is not an op (a lever, a permission-only row) and
 *       no such record exists anywhere, or — the subtle one — the record exists
 *       but structurally cannot fill: see {@link #REASON_PERMISSION_GATE}.</li>
 * </ul>
 * <p>
 * <b>LANDMINE — a denial is only recorded when the app-op is the gate that
 * fires.</b> {@code lastRejectTime} moves when the app's call reaches
 * {@code noteOp} and comes back {@code MODE_IGNORED}. That is the ungated ops
 * (clipboard, screen capture, background run), where we block by writing the mode
 * and the app keeps calling. But a <em>permission-backed</em> capability is
 * blocked by revoking the permission (see the +22 landmine: a direct op write is
 * re-derived and thrown away by {@code PermissionPolicyService}), and the
 * framework's permission check then fails <em>before</em> {@code noteOp} is ever
 * reached. Nothing is recorded, for ever. Reporting that as "never denied" for an
 * app being denied all day would look like a broken feature rather than a
 * platform limit, so this case is {@link #UNKNOWN}, with its own reason.
 * <p>
 * The same asymmetry does not apply to <em>use</em>: an access is recorded when
 * the call succeeds, and a call that never gets past the permission check never
 * happened as far as the capability is concerned. So "never used" stays a fact in
 * every op case.
 */
public final class SnoopingActivityTimes {
    @IntDef({UNKNOWN, NEVER, KNOWN})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Availability {
    }

    /** We have no source for this — not the same as nothing having happened. */
    public static final int UNKNOWN = 0;
    /** The platform holds this record and it is empty. */
    public static final int NEVER = 1;
    /** We have a timestamp. */
    public static final int KNOWN = 2;

    @IntDef({REASON_NONE, REASON_NOT_AN_OP, REASON_NO_APP_OPS, REASON_PERMISSION_GATE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Reason {
    }

    public static final int REASON_NONE = 0;
    /** A lever or permission-only row: no app-op exists, so nothing records it. */
    public static final int REASON_NOT_AN_OP = 1;
    /** App-ops would not answer for this package at all (no privileges, or it threw). */
    public static final int REASON_NO_APP_OPS = 2;
    /** Denials land at the permission check, which keeps no record — see the class note. */
    public static final int REASON_PERMISSION_GATE = 3;

    @Availability
    public final int access;
    /** Wall-clock ms of the last recorded use; meaningful only when {@link #access} is {@link #KNOWN}. */
    public final long lastAccess;
    /** …and of the last use while the app was in the background, or 0. */
    public final long lastAccessBackground;
    @Availability
    public final int reject;
    public final long lastReject;
    public final long lastRejectBackground;
    @Reason
    public final int reason;
    /**
     * Whether the platform can separate foreground from background at all. False
     * below Android P, where {@code OpEntry} carries one undifferentiated time and
     * the compat layer answers every question with it — a "(background)" marker
     * built on that would be invented, not measured.
     */
    public final boolean backgroundKnown;

    private SnoopingActivityTimes(@Availability int access, long lastAccess, long lastAccessBackground,
                                  @Availability int reject, long lastReject, long lastRejectBackground,
                                  @Reason int reason, boolean backgroundKnown) {
        this.access = access;
        this.lastAccess = lastAccess;
        this.lastAccessBackground = lastAccessBackground;
        this.reject = reject;
        this.lastReject = lastReject;
        this.lastRejectBackground = lastRejectBackground;
        this.reason = reason;
        this.backgroundKnown = backgroundKnown;
    }

    /** Neither question can be answered, and here is why. */
    @NonNull
    private static SnoopingActivityTimes blind(@Reason int reason) {
        return new SnoopingActivityTimes(UNKNOWN, 0, 0, UNKNOWN, 0, 0, reason, false);
    }

    /** Whether there is any record to speak of — false for lever and permission-only rows. */
    public boolean isSupported() {
        return reason != REASON_NOT_AN_OP;
    }

    /** Whether the most recent use we know of happened while the app was in the background. */
    public boolean lastAccessWasBackground() {
        return backgroundKnown && lastAccessBackground > 0 && lastAccessBackground >= lastAccess;
    }

    /** A background use older than the most recent one — worth naming separately. */
    public boolean hasEarlierBackgroundAccess() {
        return backgroundKnown && lastAccessBackground > 0 && lastAccessBackground < lastAccess;
    }

    public boolean lastRejectWasBackground() {
        return backgroundKnown && lastRejectBackground > 0 && lastRejectBackground >= lastReject;
    }

    /**
     * Read the two times off a row. Cheap — the {@link AppDetailsAppOpItem} already
     * holds the {@code OpEntry}, so this is field access, not a binder call, and it
     * is safe to call at bind time. Deliberately <b>not</b> snapshotted on the item:
     * a write calls {@code AppDetailsAppOpItem#invalidate}, which replaces the entry,
     * and a cached copy would keep showing the times from before the write.
     */
    @NonNull
    public static SnoopingActivityTimes forItem(@NonNull AppDetailsSnoopingItem item) {
        return forOp(item.opItem, item.opStateReadable);
    }

    @NonNull
    private static SnoopingActivityTimes forOp(@Nullable AppDetailsAppOpItem opItem, boolean opStateReadable) {
        if (opItem == null) {
            // A lever (a system list, a network policy, a role, the doze whitelist)
            // or a permission-only capability. The platform keeps no usage record
            // for any of them — there is no op to hang one on.
            return blind(REASON_NOT_AN_OP);
        }
        if (!opStateReadable) {
            // getOpsForPackage threw for this package, so EVERY op row is blind.
            // Without this flag an empty map would be indistinguishable from a
            // package that has genuinely never touched anything, and the whole
            // page would quietly claim "never used".
            return blind(REASON_NO_APP_OPS);
        }
        boolean backgroundKnown = opItem.hasBackgroundTimeSplit();
        // A permission the app declares but does not hold: the permission check
        // refuses first and app-ops never sees the call, so no denial can ever be
        // recorded here. Also covers a not-requested pre-set row, which cannot
        // call at all. See the class note.
        boolean deniedBeforeTheOp = opItem.permission != null && !opItem.permission.isGranted();
        if (!opItem.hasOpEntry()) {
            // AppOpsService creates the Op object on the first note() or setMode()
            // and prunes it only when it holds neither a mode nor any access data,
            // so its absence is evidence rather than a gap: nothing has ever been
            // recorded for this op on this package.
            return new SnoopingActivityTimes(NEVER, 0, 0,
                    deniedBeforeTheOp ? UNKNOWN : NEVER, 0, 0,
                    deniedBeforeTheOp ? REASON_PERMISSION_GATE : REASON_NONE, backgroundKnown);
        }
        long lastAccess = sane(opItem.getTime());
        long lastReject = sane(opItem.getRejectTime());
        long lastAccessBackground = backgroundKnown ? sane(opItem.getLastAccessBackgroundTime()) : 0;
        long lastRejectBackground = backgroundKnown ? sane(opItem.getLastRejectBackgroundTime()) : 0;
        int rejectAvailability;
        int reason;
        if (lastReject > 0) {
            // Evidence beats theory: a recorded denial is a recorded denial, even
            // for a row whose permission is revoked now (it was granted then, or
            // the app is legacy and was revoked compat-style — the grant stays and
            // the op is what refuses, which is exactly the case that DOES record).
            rejectAvailability = KNOWN;
            reason = REASON_NONE;
        } else if (deniedBeforeTheOp) {
            rejectAvailability = UNKNOWN;
            reason = REASON_PERMISSION_GATE;
        } else {
            rejectAvailability = NEVER;
            reason = REASON_NONE;
        }
        return new SnoopingActivityTimes(lastAccess > 0 ? KNOWN : NEVER, lastAccess, lastAccessBackground,
                rejectAvailability, lastReject, lastRejectBackground, reason, backgroundKnown);
    }

    /**
     * The platform reports 0 for "never" and -1 for "not tracked"; a time in the
     * future means the clock moved under us. Anything that is not a usable past
     * timestamp becomes 0, i.e. "never".
     */
    private static long sane(long time) {
        if (time <= 0) {
            return 0;
        }
        return time > System.currentTimeMillis() ? 0 : time;
    }
}
