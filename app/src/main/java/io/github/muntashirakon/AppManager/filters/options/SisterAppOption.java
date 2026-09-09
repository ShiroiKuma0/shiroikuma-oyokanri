// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.filters.options;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.muntashirakon.AppManager.appdata.AppDataContract;
import io.github.muntashirakon.AppManager.filters.IFilterableAppInfo;
import io.github.muntashirakon.AppManager.utils.ContextUtils;

/**
 * Fork (白い熊, +124): filter on whether an app implements the sister-app data contract.
 *
 * <p>The 仲間 screen answers "how do the family apps stand"; this answers the other half of the
 * same question — "show me them, here, among everything else". They are not the same thing: on
 * the main list a sister app can be frozen, backed up, added to a profile and batch-operated on
 * with everything else, and none of that is what a status screen is for. A screen was never a
 * substitute for a filter.
 *
 * <p>Support is read through {@link AppDataContract#everSisterPackages} — so a <b>frozen</b> app
 * still matches, which is the whole reason discovery was built that way, nothing is woken to
 * answer the question, and an app that is not installed at all still matches when a backup proves
 * it is one of the family.
 *
 * <p><b>This is now the only Sister-apps control.</b> The 仲間 lens drew nothing (it was
 * {@code filterOnly}) and so was a second filter under another name, with a slightly different
 * membership — which is worse than a duplicate. Its rule is the one kept, here; the lens is no
 * longer offered when adding a pill, and remains registered only so pills already on the shelf
 * keep working.
 */
public class SisterAppOption extends FilterOption {
    private final Map<String, Integer> mKeysWithType = new LinkedHashMap<String, Integer>() {{
        put(KEY_ALL, TYPE_NONE);
        put("is_sister", TYPE_NONE);
        put("not_sister", TYPE_NONE);
    }};

    public SisterAppOption() {
        super("sister_app");
    }

    @NonNull
    @Override
    public Map<String, Integer> getKeysWithType() {
        return mKeysWithType;
    }

    @NonNull
    @Override
    public TestResult test(@NonNull IFilterableAppInfo info, @NonNull TestResult result) {
        // Fork (白い熊, +175): everSisterPackages, NOT supportedPackages.
        //
        // This used to ask "can I talk to it right now", which requires a readable manifest and a
        // contract version this build speaks — and so hid an app known only from its backup (the
        // wiped-phone case this contract exists for) and an app whose contract we do not speak
        // (which makes a version mismatch look like the app never having a door). Both are sister
        // apps and both are exactly what you are looking for when you tick this.
        //
        // Gathered in one package-manager pass plus one pass over the backup table, cached for a
        // few seconds — a filter runs this over several hundred apps, and asking per app would
        // turn a list refresh into a visible stall.
        boolean sister = AppDataContract.everSisterPackages(ContextUtils.getContext())
                .contains(info.getPackageName());
        switch (key) {
            case KEY_ALL:
                return result.setMatched(true);
            case "is_sister":
                return result.setMatched(sister);
            case "not_sister":
                return result.setMatched(!sister);
            default:
                throw new UnsupportedOperationException("Invalid key: " + key);
        }
    }

    @NonNull
    @Override
    public CharSequence toLocalizedString(@NonNull Context context) {
        switch (key) {
            case KEY_ALL:
                return "Sister apps and everything else";
            case "is_sister":
                return "Only the sister apps — declared, or proven by a backup";
            case "not_sister":
                return "Only the apps that are not sister apps";
            default:
                throw new UnsupportedOperationException("Invalid key " + key);
        }
    }
}
