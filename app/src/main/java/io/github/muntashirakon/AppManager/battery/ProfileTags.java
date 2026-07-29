// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.battery;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.profiles.struct.AppsProfile;
import io.github.muntashirakon.AppManager.profiles.struct.BaseProfile;
import io.github.muntashirakon.AppManager.profiles.ProfileManager;

/**
 * Fork: which apps-profiles a package belongs to — the pills the main list
 * shows under each row (保存復元, 凍結, 必要 …). Same source as
 * {@code MainRecyclerAdapter.loadProfileMembership}, just for one package.
 */
public final class ProfileTags {
    private static final String TAG = ProfileTags.class.getSimpleName();

    private ProfileTags() {}

    @NonNull
    public static List<String> forPackage(@NonNull String packageName) {
        List<String> names = new ArrayList<>();
        try {
            for (BaseProfile profile : ProfileManager.getProfiles()) {
                if (!(profile instanceof AppsProfile)) continue;
                for (String pkg : ((AppsProfile) profile).packages) {
                    if (packageName.equals(pkg)) {
                        names.add(profile.name);
                        break;
                    }
                }
            }
        } catch (Throwable th) {
            Log.e(TAG, "Failed to read profile membership", th);
            return Collections.emptyList();
        }
        return names;
    }
}
