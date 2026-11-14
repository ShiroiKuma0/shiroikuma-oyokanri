// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.scanner.vt;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.InputType;
import android.text.TextUtils;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.inputmethod.EditorInfoCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.settings.FeatureController;
import io.github.muntashirakon.AppManager.settings.Prefs;
import io.github.muntashirakon.AppManager.utils.ForkDialog;
import io.github.muntashirakon.AppManager.utils.UIUtils;
import io.github.muntashirakon.dialog.TextInputDialogBuilder;

/**
 * Fork (白い熊, APK-vetting chain, item 10): the "ask for the key before the scan" dialog, shared by
 * every scan entry (report screen, scanner card, history screen).
 *
 * <p>It offers a field, a "Get a key" link to the VirusTotal sign-up, and Save / Cancel. On Save the
 * key is validated with one cheap authenticated call; a rejected key reopens the dialog with the
 * reason, while quota exhaustion is accepted (and reported as quota, never a bad key). A saved key is
 * stored encrypted, the feature switches it requires are turned on, and any scan parked for a missing
 * key is resumed — so the very file that triggered the prompt continues without being re-tapped.
 */
public final class VtApiKeyDialog {
    private VtApiKeyDialog() {}

    private static final String SIGNUP_URL = "https://www.virustotal.com/gui/join-us";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    public interface OnSaved {
        void onSaved();
    }

    public static void prompt(@NonNull Activity activity, @Nullable OnSaved onSaved) {
        prompt(activity, null, onSaved);
    }

    public static void prompt(@NonNull Activity activity, @Nullable CharSequence reason,
                              @Nullable OnSaved onSaved) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        CharSequence helper = reason != null ? reason
                : activity.getText(R.string.vt_apikey_dialog_helper);
        new TextInputDialogBuilder(activity, R.string.pref_vt_apikey)
                .setTitle(R.string.vt_apikey_dialog_title)
                .setHelperText(helper)
                .setInputText(Prefs.VirusTotal.getApiKey())
                .setInputTypeface(Typeface.MONOSPACE)
                .setInputInputType(InputType.TYPE_CLASS_TEXT)
                .setInputImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfoCompat.IME_FLAG_NO_PERSONALIZED_LEARNING)
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.vt_apikey_get, (dialog, which, inputText, isChecked) -> {
                    openSignup(activity);
                    // Reopen so the entered text and the intent are not lost.
                    prompt(activity, reason, onSaved);
                })
                .setPositiveButton(R.string.save, (dialog, which, inputText, isChecked) -> {
                    String key = inputText != null ? inputText.toString().trim() : "";
                    if (TextUtils.isEmpty(key)) {
                        prompt(activity, activity.getText(R.string.vt_apikey_empty), onSaved);
                        return;
                    }
                    validateAndSave(activity, key, onSaved);
                })
                .show();
    }

    private static void validateAndSave(@NonNull Activity activity, @NonNull String key,
                                        @Nullable OnSaved onSaved) {
        AlertDialog checking = ForkDialog.present(ForkDialog.builder(activity)
                .setTitle(R.string.vt_apikey_checking)
                .setMessage(R.string.loading)
                .setCancelable(false));
        EXECUTOR.execute(() -> {
            VirusTotal.KeyCheck result = new VirusTotal(key).checkApiKey();
            activity.runOnUiThread(() -> {
                checking.dismiss();
                if (activity.isFinishing() || activity.isDestroyed()) return;
                switch (result) {
                    case VALID:
                        save(activity, key);
                        UIUtils.displayShortToast(R.string.vt_apikey_saved);
                        if (onSaved != null) onSaved.onSaved();
                        break;
                    case QUOTA:
                        // The key authenticated; the quota is just exhausted — accept it.
                        save(activity, key);
                        UIUtils.displayLongToast(R.string.vt_apikey_saved_quota);
                        if (onSaved != null) onSaved.onSaved();
                        break;
                    case INVALID:
                        prompt(activity, activity.getText(R.string.vt_apikey_rejected), onSaved);
                        break;
                    default:
                        prompt(activity, activity.getText(R.string.vt_apikey_unreachable), onSaved);
                        break;
                }
            });
        });
    }

    private static void save(@NonNull Activity activity, @NonNull String key) {
        Prefs.VirusTotal.setApiKey(key);
        // Item 10: saving a key enables what the VirusTotal feature check requires, so a stored key
        // never leaves the scan silently unavailable.
        FeatureController fc = FeatureController.getInstance();
        if (!FeatureController.isInternetEnabled()) {
            fc.modifyState(FeatureController.FEAT_INTERNET, true);
        }
        if (!FeatureController.isVirusTotalEnabled()) {
            fc.modifyState(FeatureController.FEAT_VIRUS_TOTAL, true);
        }
        // Resume any scan parked for a missing/invalid key and let the service pick it up.
        EXECUTOR.execute(() -> {
            try (VtScanRepository repository = new VtScanRepository()) {
                if (repository.resumeKeyParkedAttempts() > 0) {
                    VtScanService.start(activity.getApplicationContext());
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private static void openSignup(@NonNull Activity activity) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(SIGNUP_URL));
            if (intent.resolveActivity(activity.getPackageManager()) != null) {
                activity.startActivity(intent);
            }
        } catch (Throwable ignored) {
        }
    }
}
