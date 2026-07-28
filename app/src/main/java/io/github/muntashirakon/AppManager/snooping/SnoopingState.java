// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.snooping;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Fork: what a Snooping row can be set to.
 * <p>
 * The platform has had a third answer since Android 10 — {@code MODE_FOREGROUND},
 * "only while the app is on screen" — and for the capabilities that support it
 * that is usually the state 白い熊 actually wants: the app keeps working while
 * you are looking at it and goes deaf, blind and lost the moment it is not. A
 * two-position switch could not express it, so the row is a small state machine
 * instead: tapping it cycles through exactly the states its capability supports.
 * <p>
 * The numbers are a <b>wire format</b> — they are what {@link SnoopingPrefs}
 * writes into {@code shiroikuma_snooping.xml} and therefore what travels in a
 * settings export. {@code 0}/{@code 1} are what every build before 4.1.0+18
 * wrote for blocked/allowed, so they must keep their meaning forever; a value
 * this build does not understand decodes to {@link #BLOCKED}, which is the safe
 * direction to be wrong in.
 */
public final class SnoopingState {
    /** The app cannot use this capability at all. */
    public static final int BLOCKED = 0;
    /** The app can use it whenever it likes, including from the background. */
    public static final int ALLOWED = 1;
    /** Only while the app is in the foreground — {@code AppOpsManager.MODE_FOREGROUND}. */
    public static final int FOREGROUND = 2;
    @IntDef({BLOCKED, ALLOWED, FOREGROUND})
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {
    }

    private SnoopingState() {
    }

    /**
     * Whether the app can exercise the capability at all in this state. Note that
     * {@link #FOREGROUND} counts as allowed — it is a narrowing of when, not a
     * denial, and the row's colour language says so separately.
     */
    public static boolean isAllowed(@State int state) {
        return state != BLOCKED;
    }

    @State
    public static int fromAllowed(boolean allowed) {
        return allowed ? ALLOWED : BLOCKED;
    }

    /** Decode a stored value, tolerating anything a newer build might have written. */
    @State
    public static int parse(int raw) {
        return raw == ALLOWED || raw == FOREGROUND ? raw : BLOCKED;
    }
}
