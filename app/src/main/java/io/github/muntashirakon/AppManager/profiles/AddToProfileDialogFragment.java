// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.profiles;

import static io.github.muntashirakon.AppManager.utils.UIUtils.getSecondaryText;
import static io.github.muntashirakon.AppManager.utils.UIUtils.getSmallerText;

import android.app.Dialog;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.profiles.struct.AppsProfile;
import io.github.muntashirakon.AppManager.profiles.struct.BaseProfile;
import io.github.muntashirakon.AppManager.utils.ArrayUtils;
import io.github.muntashirakon.AppManager.utils.ExUtils;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;
import io.github.muntashirakon.dialog.DialogTitleBuilder;
import io.github.muntashirakon.dialog.SearchableMultiChoiceDialogBuilder;
import io.github.muntashirakon.dialog.TextInputDialogBuilder;
import io.github.muntashirakon.io.Path;

public class AddToProfileDialogFragment extends DialogFragment {
    public static final String TAG = AddToProfileDialogFragment.class.getSimpleName();

    private static final String ARG_PKGS = "pkgs";

    public static AddToProfileDialogFragment getInstance(@NonNull String[] packages) {
        AddToProfileDialogFragment fragment = new AddToProfileDialogFragment();
        Bundle args = new Bundle();
        args.putStringArray(ARG_PKGS, packages);
        fragment.setArguments(args);
        return fragment;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        String[] packages = requireArguments().getStringArray(ARG_PKGS);
        // TODO: 16/9/23 Migrate to bottom sheet dialog and use loader before retrieving the profiles
        List<AppsProfile> profiles = ExUtils.requireNonNullElse(() -> ProfileManager.getProfiles(AppsProfile.PROFILE_TYPE_APPS), Collections.emptyList());
        List<CharSequence> profileNames = new ArrayList<>(profiles.size());
        for (AppsProfile profile : profiles) {
            profileNames.add(new SpannableStringBuilder(profile.name).append("\n")
                    .append(getSecondaryText(requireContext(), getSmallerText(
                            profile.toLocalizedString(requireContext())))));
        }
        AtomicReference<AlertDialog> dialogRef = new AtomicReference<>();
        DialogTitleBuilder titleBuilder = new DialogTitleBuilder(requireContext())
                .setTitle(R.string.add_to_profile)
                .setEndIconContentDescription(R.string.new_profile)
                .setEndIcon(R.drawable.ic_add, v -> new TextInputDialogBuilder(requireContext(), R.string.input_profile_name)
                        .setTitle(R.string.new_profile)
                        .setHelperText(R.string.input_profile_name_description)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.go, (dialog, which, profName, isChecked) -> {
                            if (!TextUtils.isEmpty(profName)) {
                                startActivity(AppsProfileActivity.getNewProfileIntent(requireContext(),
                                        profName.toString(), packages));
                                if (dialogRef.get() != null) {
                                    dialogRef.get().dismiss();
                                }
                            }
                        })
                        .show());
        AlertDialog alertDialog = new SearchableMultiChoiceDialogBuilder<>(requireContext(), profiles, profileNames)
                .setTitle(titleBuilder.build())
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.add, (dialog, which, selectedItems) -> ThreadUtils.postOnBackgroundThread(() -> {
                    boolean isSuccess = !selectedItems.isEmpty();
                    boolean isChanged = false;
                    for (AppsProfile selected : selectedItems) {
                        try {
                            // Resolve the profile's actual file by its stored id (not just its
                            // canonical name) and reload it fresh, so the append always lands in
                            // the real profile rather than a stale object or a wrongly-named file.
                            Path profilePath = ProfileManager.resolveExistingProfilePath(selected.profileId);
                            if (profilePath == null) {
                                isSuccess = false;
                                continue;
                            }
                            AppsProfile profile = (AppsProfile) BaseProfile.fromPath(profilePath);
                            profile.appendPackages(packages);
                            try (OutputStream os = profilePath.openOutputStream()) {
                                profile.write(os);
                            }
                            isChanged = true;
                            // Verify the packages actually persisted before claiming success.
                            AppsProfile reloaded = (AppsProfile) BaseProfile.fromPath(profilePath);
                            for (String pkg : packages) {
                                if (!ArrayUtils.contains(reloaded.packages, pkg)) {
                                    isSuccess = false;
                                    break;
                                }
                            }
                        } catch (Throwable e) {
                            isSuccess = false;
                            Log.e(TAG, "Failed to add packages to profile " + selected.profileId, e);
                        }
                    }
                    // Upstream: refresh the profiles UI after a successful write.
                    if (isChanged) {
                        ProfileManager.sendProfilesChangedBroadcast();
                    }
                    // Membership of the protected "必要" profile may have changed.
                    ProtectedAppsProfile.invalidate();
                    boolean finalSuccess = isSuccess;
                    ThreadUtils.postOnMainThread(() -> UIUtils.displayShortToast(
                            finalSuccess ? R.string.done : R.string.failed));
                }))
                .create();
        dialogRef.set(alertDialog);
        return alertDialog;
    }
}
