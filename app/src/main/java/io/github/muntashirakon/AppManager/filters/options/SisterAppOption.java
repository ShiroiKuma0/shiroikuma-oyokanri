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
 * <p>Support is a manifest fact, read through {@link AppDataContract} — so a <b>frozen</b> app
 * still matches, which is the whole reason discovery was built that way, and nothing is woken to
 * answer the question.
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
        // The supported set is gathered in one package-manager pass and cached for a few seconds
        // — a filter runs this over several hundred apps, and asking the package manager once per
        // app would turn a list refresh into a visible stall.
        boolean sister = AppDataContract.supportedPackages(ContextUtils.getContext())
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
                return "Only the apps that implement the sister-app data contract";
            case "not_sister":
                return "Only the apps that do not implement the sister-app data contract";
            default:
                throw new UnsupportedOperationException("Invalid key " + key);
        }
    }
}
