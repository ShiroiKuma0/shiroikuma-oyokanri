// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.trackers;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.muntashirakon.AppManager.rules.RuleType;

/**
 * Fork (白い熊, +146): one tracker found inside one app.
 *
 * <p><b>Why it carries a severity rung rather than a category.</b> The screenshot this feature
 * grew from labels each tracker "Analytics", "Crash reporting" — Exodus metadata that this app
 * does not ship: {@code trackers.xml} is 989 signatures and 989 names, and nothing else. A
 * category column would therefore have to be invented and hand-maintained, and most rows would
 * read "Unclassified".
 *
 * <p>What we <em>do</em> know is written in the components the tracker owns, and it answers a
 * better question than a taxonomy would — <b>can this thing report on me when I am not using the
 * app?</b> A receiver or a service can be started by the system with no help from you (boot,
 * connectivity, an alarm); a content provider is built before {@code Application.onCreate}, so it
 * runs whenever the app runs and not otherwise; an activity needs you to open a screen it owns.
 * Three rungs, each measured from the manifest, each different pill to pill within one app.
 *
 * <p>The colours are the Snooping page's own, deliberately: red is "it can do this right now",
 * and a tracker that wakes itself is exactly that.
 */
public class TrackerHit {
    /** Activities only — it needs you to open a screen it owns. */
    public static final int RUNG_PASSIVE = 0;
    /** A provider (or code with no components at all): it runs whenever the app runs. */
    public static final int RUNG_WITH_APP = 1;
    /** A service or a receiver: the system can start it with no help from you. */
    public static final int RUNG_AUTONOMOUS = 2;

    /** The Snooping page's "it can do this right now" red. */
    @ColorInt
    public static final int COLOR_AUTONOMOUS = 0xFFFF0028;
    /** Neutral grey, the page's "nothing to look at here". */
    @ColorInt
    public static final int COLOR_PASSIVE = 0xFF9E9E9E;

    @NonNull
    public final String name;
    /**
     * The dataset marks a second-degree tracker by prefixing its name with "²" — a library that
     * bundles somebody else's tracker rather than being one. Kept as a flag, so the pill can be
     * faded rather than renamed.
     */
    public final boolean secondDegree;
    /** Component class name → what kind of component it is. Empty for a code-only hit. */
    @NonNull
    public final Map<String, RuleType> components = new LinkedHashMap<>();
    /** Classes the deep scan matched. 0 when the deep scan has not run. */
    public int classes;

    public TrackerHit(@NonNull String name, boolean secondDegree) {
        this.name = name;
        this.secondDegree = secondDegree;
    }

    /** Found in the code but owning no component: it cannot self-start, but it ships with the app. */
    public boolean isCodeOnly() {
        return components.isEmpty();
    }

    public int rung() {
        boolean provider = false;
        for (RuleType type : components.values()) {
            if (type == RuleType.SERVICE || type == RuleType.RECEIVER) {
                return RUNG_AUTONOMOUS;
            }
            if (type == RuleType.PROVIDER) {
                provider = true;
            }
        }
        if (provider || components.isEmpty()) {
            return RUNG_WITH_APP;
        }
        return RUNG_PASSIVE;
    }

    public int countOf(@NonNull RuleType type) {
        int n = 0;
        for (RuleType t : components.values()) {
            if (t == type) ++n;
        }
        return n;
    }
}
