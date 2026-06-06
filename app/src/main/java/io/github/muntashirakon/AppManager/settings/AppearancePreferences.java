// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.settings;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.transition.MaterialSharedAxis;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.utils.appearance.AppearanceUtils;
import io.github.muntashirakon.AppManager.utils.appearance.TypefaceUtil;
import io.github.muntashirakon.dialog.SearchableFlagsDialogBuilder;
import io.github.muntashirakon.dialog.SearchableSingleChoiceDialogBuilder;

public class AppearancePreferences extends PreferenceFragment {
    private static final List<Integer> THEME_CONST = Arrays.asList(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY,
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES);
    private static final List<Integer> LAYOUT_ORIENTATION_CONST = Arrays.asList(
            View.LAYOUT_DIRECTION_LOCALE,
            View.LAYOUT_DIRECTION_LTR,
            View.LAYOUT_DIRECTION_RTL);
    // Fork: colour presets for the configurable dialog/toast theme (ARGB) and
    // their display-name resources, kept in parallel.
    private static final List<Integer> THEME_COLOR_VALUES = Arrays.asList(
            0xFFFFFF00, 0xFFFF7A1A, 0xFF00E676, 0xFF00E5FF,
            0xFF2196F3, 0xFFFF5252, 0xFFFFFFFF, 0xFF000000, 0xFF888888);
    private static final int[] THEME_COLOR_NAME_RES = {
            R.string.color_yellow, R.string.color_orange, R.string.color_green, R.string.color_cyan,
            R.string.color_blue, R.string.color_red, R.string.color_white, R.string.color_black, R.string.color_grey};
    private static final List<Integer> THEME_BORDER_WIDTHS = Arrays.asList(0, 1, 2, 3, 4, 6);

    /** Fork: a typed read/write pair for one theme colour preference. */
    private interface ColorBinding {
        int get();

        void set(int color);
    }

    private int mCurrentTheme;
    private int mCurrentLayoutDirection;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences_appearance, rootKey);
        getPreferenceManager().setPreferenceDataStore(new SettingsDataStore());
        // App theme
        final String[] themes = getResources().getStringArray(R.array.themes);
        mCurrentTheme = Prefs.Appearance.getNightMode();
        Preference appTheme = Objects.requireNonNull(findPreference("app_theme"));
        appTheme.setSummary(themes[THEME_CONST.indexOf(mCurrentTheme)]);
        appTheme.setOnPreferenceClickListener(preference -> {
            new SearchableSingleChoiceDialogBuilder<>(requireActivity(), THEME_CONST, themes)
                    .setTitle(R.string.select_theme)
                    .setSelection(mCurrentTheme)
                    .setPositiveButton(R.string.apply, (dialog, which, selectedTheme) -> {
                        if (selectedTheme != null && selectedTheme != mCurrentTheme) {
                            mCurrentTheme = selectedTheme;
                            Prefs.Appearance.setNightMode(mCurrentTheme);
                            AppCompatDelegate.setDefaultNightMode(mCurrentTheme);
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return true;
        });
        // Black theme/custom theme
        SwitchPreferenceCompat fullBlackTheme = Objects.requireNonNull(findPreference("app_theme_pure_black"));
        fullBlackTheme.setChecked(Prefs.Appearance.isPureBlackTheme());
        fullBlackTheme.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean enabled = (boolean) newValue;
            Prefs.Appearance.setPureBlackTheme(enabled);
            AppearanceUtils.applyConfigurationChangesToActivities();
            return true;
        });
        // Black theme/custom theme
        SwitchPreferenceCompat useSystemFontPref = Objects.requireNonNull(findPreference("use_system_font"));
        useSystemFontPref.setChecked(Prefs.Appearance.useSystemFont());
        useSystemFontPref.setOnPreferenceChangeListener((preference, newValue) -> {
            if (((boolean) newValue)) {
                // Enable system font
                TypefaceUtil.replaceFontsWithSystem(requireContext());
            } else {
                // Disable system font
                TypefaceUtil.restoreFonts();
            }
            AppearanceUtils.applyConfigurationChangesToActivities();
            return true;
        });
        // Layout orientation
        final String[] layoutOrientations = getResources().getStringArray(R.array.layout_orientations);
        mCurrentLayoutDirection = Prefs.Appearance.getLayoutDirection();
        Preference layoutOrientation = Objects.requireNonNull(findPreference("layout_orientation"));
        layoutOrientation.setSummary(layoutOrientations[LAYOUT_ORIENTATION_CONST.indexOf(mCurrentLayoutDirection)]);
        layoutOrientation.setOnPreferenceClickListener(preference -> {
            new SearchableSingleChoiceDialogBuilder<>(requireActivity(), LAYOUT_ORIENTATION_CONST, layoutOrientations)
                    .setTitle(R.string.pref_layout_direction)
                    .setSelection(mCurrentLayoutDirection)
                    .setPositiveButton(R.string.apply, (dialog, which, selectedLayoutOrientation) -> {
                        mCurrentLayoutDirection = Objects.requireNonNull(selectedLayoutOrientation);
                        Prefs.Appearance.setLayoutDirection(mCurrentLayoutDirection);
                        AppearanceUtils.applyConfigurationChangesToActivities();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return true;
        });
        // Enable/disable features
        FeatureController fc = FeatureController.getInstance();
        ((Preference) Objects.requireNonNull(findPreference("enabled_features")))
                .setOnPreferenceClickListener(preference -> {
                    new SearchableFlagsDialogBuilder<>(requireActivity(), FeatureController.featureFlags, FeatureController.getFormattedFlagNames(requireActivity()), fc.getFlags())
                            .setTitle(R.string.enable_disable_features)
                            .setOnMultiChoiceClickListener((dialog, which, item, isChecked) ->
                                    fc.modifyState(FeatureController.featureFlags.get(which), isChecked))
                            .setNegativeButton(R.string.close, null)
                            .show();
                    return true;
                });
        // Fork: in-app batch-progress dialog toggle
        SwitchPreferenceCompat batchDialog = Objects.requireNonNull(findPreference("batch_progress_dialog"));
        batchDialog.setChecked(Prefs.Appearance.showBatchProgressDialog());
        batchDialog.setOnPreferenceChangeListener((preference, newValue) -> {
            Prefs.Appearance.setShowBatchProgressDialog((boolean) newValue);
            return true;
        });
        // Fork: configurable theme (dialog + toasts) — colours and border width
        bindColorPreference("theme_text_color", R.string.pref_theme_text_color, new ColorBinding() {
            @Override
            public int get() {
                return Prefs.Appearance.getThemeTextColor();
            }

            @Override
            public void set(int color) {
                Prefs.Appearance.setThemeTextColor(color);
            }
        });
        bindColorPreference("theme_background_color", R.string.pref_theme_background_color, new ColorBinding() {
            @Override
            public int get() {
                return Prefs.Appearance.getThemeBackgroundColor();
            }

            @Override
            public void set(int color) {
                Prefs.Appearance.setThemeBackgroundColor(color);
            }
        });
        bindColorPreference("theme_border_color", R.string.pref_theme_border_color, new ColorBinding() {
            @Override
            public int get() {
                return Prefs.Appearance.getThemeBorderColor();
            }

            @Override
            public void set(int color) {
                Prefs.Appearance.setThemeBorderColor(color);
            }
        });
        bindBorderWidthPreference();
    }

    // Fork: wire one colour preference to a {@link ColorBinding}, with a preset
    // picker and a live summary showing the chosen colour's name.
    private void bindColorPreference(@NonNull String key, @StringRes int titleRes, @NonNull ColorBinding binding) {
        Preference pref = findPreference(key);
        if (pref == null) {
            return;
        }
        pref.setSummary(colorName(binding.get()));
        pref.setOnPreferenceClickListener(p -> {
            new SearchableSingleChoiceDialogBuilder<>(requireActivity(), THEME_COLOR_VALUES, colorNames())
                    .setTitle(titleRes)
                    .setSelection(binding.get())
                    .setPositiveButton(R.string.apply, (dialog, which, selected) -> {
                        if (selected != null) {
                            binding.set(selected);
                            p.setSummary(colorName(selected));
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return true;
        });
    }

    private void bindBorderWidthPreference() {
        Preference pref = findPreference("theme_border_width");
        if (pref == null) {
            return;
        }
        pref.setSummary(getString(R.string.theme_border_width_dp, Prefs.Appearance.getThemeBorderWidthDp()));
        pref.setOnPreferenceClickListener(p -> {
            String[] labels = new String[THEME_BORDER_WIDTHS.size()];
            for (int i = 0; i < labels.length; ++i) {
                labels[i] = getString(R.string.theme_border_width_dp, THEME_BORDER_WIDTHS.get(i));
            }
            new SearchableSingleChoiceDialogBuilder<>(requireActivity(), THEME_BORDER_WIDTHS, labels)
                    .setTitle(R.string.pref_theme_border_width)
                    .setSelection(Prefs.Appearance.getThemeBorderWidthDp())
                    .setPositiveButton(R.string.apply, (dialog, which, selected) -> {
                        if (selected != null) {
                            Prefs.Appearance.setThemeBorderWidthDp(selected);
                            p.setSummary(getString(R.string.theme_border_width_dp, selected));
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return true;
        });
    }

    @NonNull
    private String[] colorNames() {
        String[] names = new String[THEME_COLOR_NAME_RES.length];
        for (int i = 0; i < names.length; ++i) {
            names[i] = getString(THEME_COLOR_NAME_RES[i]);
        }
        return names;
    }

    @NonNull
    private String colorName(int color) {
        int idx = THEME_COLOR_VALUES.indexOf(color);
        if (idx >= 0) {
            return getString(THEME_COLOR_NAME_RES[idx]);
        }
        return String.format("#%08X", color);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public int getTitle() {
        return R.string.pref_cat_appearance;
    }
}
