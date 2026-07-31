// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.profiles;

import static io.github.muntashirakon.AppManager.utils.UIUtils.getSecondaryText;
import static io.github.muntashirakon.AppManager.utils.UIUtils.getSmallerText;

import android.app.Dialog;
import android.os.Bundle;
import android.text.SpannableStringBuilder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.profiles.struct.AppsProfile;
import io.github.muntashirakon.AppManager.profiles.struct.BaseProfile;
import io.github.muntashirakon.AppManager.utils.ArrayUtils;
import io.github.muntashirakon.AppManager.utils.ExUtils;
import io.github.muntashirakon.AppManager.utils.ForkDialog;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;
import io.github.muntashirakon.dialog.SearchableMultiChoiceDialogBuilder;
import io.github.muntashirakon.io.Path;

/**
 * Fork: batch counterpart to {@link AddToProfileDialogFragment}. Given a set of
 * selected packages, lets the user pick one or more apps profiles and removes
 * every selected package from each chosen profile at once. Only profiles that
 * actually contain at least one of the selected packages are offered, since
 * removing from any other profile would be a no-op.
 */
public class RemoveFromProfileDialogFragment extends DialogFragment {
    public static final String TAG = RemoveFromProfileDialogFragment.class.getSimpleName();

    /** Fragment-result key broadcast after at least one package is removed, so
     *  callers (e.g. the main list) can refresh any cached profile state. */
    public static final String RESULT_KEY = "remove_from_profile_result";

    private static final String ARG_PKGS = "pkgs";

    public static RemoveFromProfileDialogFragment getInstance(@NonNull String[] packages) {
        RemoveFromProfileDialogFragment fragment = new RemoveFromProfileDialogFragment();
        Bundle args = new Bundle();
        args.putStringArray(ARG_PKGS, packages);
        fragment.setArguments(args);
        return fragment;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        String[] packages = requireArguments().getStringArray(ARG_PKGS);
        List<AppsProfile> allProfiles = ExUtils.requireNonNullElse(() -> ProfileManager.getProfiles(AppsProfile.PROFILE_TYPE_APPS), Collections.emptyList());
        // Only offer profiles that contain at least one of the selected packages.
        List<AppsProfile> profiles = new ArrayList<>();
        for (AppsProfile profile : allProfiles) {
            for (String pkg : packages) {
                if (ArrayUtils.contains(profile.packages, pkg)) {
                    profiles.add(profile);
                    break;
                }
            }
        }
        if (profiles.isEmpty()) {
            return ForkDialog.bordered(ForkDialog.builder(requireContext())
                    .setTitle(R.string.remove_from_profile)
                    .setMessage(R.string.no_profile_contains_selection)
                    .setNegativeButton(R.string.close, null)
                    .create());
        }
        List<CharSequence> profileNames = new ArrayList<>(profiles.size());
        for (AppsProfile profile : profiles) {
            profileNames.add(new SpannableStringBuilder(profile.name).append("\n")
                    .append(getSecondaryText(requireContext(), getSmallerText(
                            profile.toLocalizedString(requireContext())))));
        }
        AlertDialog alertDialog = new SearchableMultiChoiceDialogBuilder<>(requireContext(), profiles, profileNames)
                .setTitle(R.string.remove_from_profile)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.item_remove, (dialog, which, selectedItems) -> ThreadUtils.postOnBackgroundThread(() -> {
                    boolean isSuccess = !selectedItems.isEmpty();
                    for (AppsProfile selected : selectedItems) {
                        try {
                            // Resolve the profile's actual file by its stored id (not just its
                            // canonical name) and reload it fresh, so the removal always lands in
                            // the real profile rather than a stale object or a wrongly-named file.
                            Path profilePath = ProfileManager.resolveExistingProfilePath(selected.profileId);
                            if (profilePath == null) {
                                isSuccess = false;
                                continue;
                            }
                            AppsProfile profile = (AppsProfile) BaseProfile.fromPath(profilePath);
                            List<String> remaining = new ArrayList<>(Arrays.asList(profile.packages));
                            remaining.removeAll(Arrays.asList(packages));
                            profile.packages = remaining.toArray(new String[0]);
                            try (OutputStream os = profilePath.openOutputStream()) {
                                profile.write(os);
                            }
                            // Verify the packages actually got removed before claiming success.
                            AppsProfile reloaded = (AppsProfile) BaseProfile.fromPath(profilePath);
                            for (String pkg : packages) {
                                if (ArrayUtils.contains(reloaded.packages, pkg)) {
                                    isSuccess = false;
                                    break;
                                }
                            }
                        } catch (Throwable e) {
                            isSuccess = false;
                            Log.e(TAG, "Failed to remove packages from profile " + selected.profileId, e);
                        }
                    }
                    // Membership of the protected "必要" profile may have changed.
                    ProtectedAppsProfile.invalidate();
                    boolean finalSuccess = isSuccess;
                    ThreadUtils.postOnMainThread(() -> {
                        UIUtils.displayShortToast(finalSuccess ? R.string.done : R.string.failed);
                        if (finalSuccess && isAdded()) {
                            getParentFragmentManager().setFragmentResult(RESULT_KEY, new Bundle());
                        }
                    });
                }))
                .create();
        return alertDialog;
    }
}
