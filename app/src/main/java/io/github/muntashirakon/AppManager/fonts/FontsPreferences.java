// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.fonts;

import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.SeekBar;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatSeekBar;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import io.github.muntashirakon.AppManager.R;

/**
 * Settings → Appearance → Fonts. Lets each text surface pick family, weight
 * and size, with a Default (all text) group that the others inherit from.
 * Controls are inline per the mockup: tap the Font / weight rows for a
 * chooser, drag the size slider (or tap the value to type a larger size),
 * and a preview line updates live.
 *
 * Data-driven: {@link #GROUPS} declares the section structure. Adding a
 * surface later = one entry here + one {@link FontUtil#apply} call at that
 * surface's bind site.
 */
public class FontsPreferences extends Fragment {

    public static final String TAG = FontsPreferences.class.getSimpleName();

    private static final class Cat {
        final String key;
        final int labelRes;
        Cat(String key, int labelRes) {
            this.key = key;
            this.labelRes = labelRes;
        }
    }

    private static final class Group {
        final int titleRes;
        final Cat[] cats;
        Group(int titleRes, Cat[] cats) {
            this.titleRes = titleRes;
            this.cats = cats;
        }
    }

    // Increment 1: Default + the first two main-list surfaces. Later
    // increments append cats here (version, install date, SDK, signature,
    // backup info) and add the matching FontUtil.apply call.
    private static final Group[] GROUPS = {
            new Group(R.string.pref_font_group_default, new Cat[]{
                    new Cat(FontPrefs.DEFAULT, R.string.pref_font_cat_default),
            }),
            new Group(R.string.pref_font_group_main_list, new Cat[]{
                    new Cat(FontPrefs.LABEL, R.string.pref_font_cat_label),
                    new Cat(FontPrefs.PACKAGE, R.string.pref_font_cat_package),
                    new Cat(FontPrefs.VERSION, R.string.pref_font_cat_version),
                    new Cat(FontPrefs.INSTALL_DATE, R.string.pref_font_cat_install_date),
                    new Cat(FontPrefs.SDK, R.string.pref_font_cat_sdk),
                    new Cat(FontPrefs.SIGNATURE, R.string.pref_font_cat_signature),
                    new Cat(FontPrefs.BACKUP_INFO, R.string.pref_font_cat_backup_info),
            }),
    };

    @Nullable
    private String mPendingImportCat;
    private ActivityResultLauncher<String[]> mOpenFont;
    @Nullable
    private Runnable mRefreshPending;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mOpenFont = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null || mPendingImportCat == null) return;
            String path = resolveFontPath(uri);
            if (path != null) {
                FontPrefs.addImportedFont(requireContext(), path);
                FontPrefs.setFamily(requireContext(), mPendingImportCat, FontUtil.FILE_PREFIX + path);
                FontUtil.clearCache();
            }
            mPendingImportCat = null;
            if (mRefreshPending != null) mRefreshPending.run();
        });
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_fonts_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        LinearLayoutCompat container = view.findViewById(R.id.fonts_container);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (Group g : GROUPS) {
            View groupView = inflater.inflate(R.layout.view_font_group, container, false);
            ((AppCompatTextView) groupView.findViewById(R.id.group_header)).setText(g.titleRes);
            LinearLayoutCompat groupContent = groupView.findViewById(R.id.group_content);
            for (Cat c : g.cats) {
                View element = inflater.inflate(R.layout.view_font_element, groupContent, false);
                bindElement(element, c);
                groupContent.addView(element);
            }
            container.addView(groupView);
        }
    }

    private void bindElement(@NonNull View element, @NonNull Cat cat) {
        final AppCompatTextView label = element.findViewById(R.id.element_label);
        final View rowFont = element.findViewById(R.id.row_font);
        final AppCompatTextView fontValue = element.findViewById(R.id.font_value);
        final View rowWeight = element.findViewById(R.id.row_weight);
        final AppCompatTextView weightValue = element.findViewById(R.id.weight_value);
        final AppCompatTextView sizeValue = element.findViewById(R.id.size_value);
        final AppCompatSeekBar sizeSeek = element.findViewById(R.id.size_seek);
        final AppCompatTextView preview = element.findViewById(R.id.preview);
        label.setText(cat.labelRes);

        sizeSeek.setMax(FontUtil.SIZE_SLIDER_MAX);

        final Runnable render = () -> {
            String fam = FontPrefs.getFamily(requireContext(), cat.key);
            int weight = FontPrefs.getWeight(requireContext(), cat.key);
            int size = FontPrefs.getSize(requireContext(), cat.key);
            fontValue.setText(FontUtil.familyLabel(requireContext(), fam));
            weightValue.setText(FontUtil.weightLabel(weight));
            sizeValue.setText(size > 0 ? size + " sp" : getString(R.string.pref_font_size_inherit));
            int sliderPos = size > 0 ? size : 0;
            if (sliderPos > FontUtil.SIZE_SLIDER_MAX) sliderPos = FontUtil.SIZE_SLIDER_MAX;
            sizeSeek.setProgress(sliderPos);
            // Live preview: resolve the EFFECTIVE styling (so DEFAULT shows
            // through on inheriting categories).
            String efam = FontPrefs.effectiveFamily(requireContext(), cat.key);
            int eweight = FontPrefs.effectiveWeight(requireContext(), cat.key);
            int esize = FontPrefs.effectiveSize(requireContext(), cat.key);
            Typeface tf = FontUtil.resolveTypeface(efam, eweight, Typeface.DEFAULT);
            preview.setTypeface(tf != null ? tf : Typeface.DEFAULT);
            preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, esize > 0 ? esize : 16);
        };
        render.run();

        rowFont.setOnClickListener(v -> {
            List<FontUtil.Option> opts = FontUtil.families(requireContext());
            CharSequence[] items = new CharSequence[opts.size() + 1];
            for (int i = 0; i < opts.size(); ++i) items[i] = opts.get(i).label;
            items[opts.size()] = getString(R.string.pref_font_add_custom);
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.pref_font_family)
                    .setItems(items, (d, which) -> {
                        if (which == opts.size()) {
                            // "Add custom font…" — remember which category to
                            // assign the picked file to, then launch SAF.
                            mPendingImportCat = cat.key;
                            mRefreshPending = render;
                            mOpenFont.launch(new String[]{"font/ttf", "font/otf",
                                    "application/x-font-ttf", "application/x-font-otf",
                                    "application/octet-stream", "*/*"});
                        } else {
                            FontPrefs.setFamily(requireContext(), cat.key, opts.get(which).value);
                            render.run();
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });

        rowWeight.setOnClickListener(v -> new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pref_font_weight)
                .setItems(FontUtil.WEIGHT_LABELS, (d, which) -> {
                    FontPrefs.setWeight(requireContext(), cat.key, FontUtil.WEIGHT_VALUES[which]);
                    render.run();
                })
                .setNegativeButton(R.string.cancel, null)
                .show());

        sizeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                int sz = Math.max(FontUtil.SIZE_SLIDER_MIN, progress);
                FontPrefs.setSize(requireContext(), cat.key, sz);
                render.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        // Tap the size value to type an exact size (allows values beyond the
        // slider max, up to SIZE_HARD_CAP). Long-press the value to reset
        // size back to inherit.
        sizeValue.setOnClickListener(v -> {
            final EditText input = new EditText(requireContext());
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            int cur = FontPrefs.getSize(requireContext(), cat.key);
            if (cur > 0) input.setText(String.valueOf(cur));
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.pref_font_size)
                    .setView(input)
                    .setPositiveButton(R.string.ok, (d, w) -> {
                        try {
                            int sz = Integer.parseInt(input.getText().toString().trim());
                            sz = Math.max(0, Math.min(FontUtil.SIZE_HARD_CAP, sz));
                            FontPrefs.setSize(requireContext(), cat.key, sz);
                            render.run();
                        } catch (NumberFormatException ignored) {
                        }
                    })
                    .setNeutralButton(R.string.pref_font_size_inherit, (d, w) -> {
                        FontPrefs.setSize(requireContext(), cat.key, 0);
                        render.run();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
    }

    /**
     * Resolve a SAF document Uri to an absolute file path so the font can be
     * referenced in place. Handles ExternalStorageProvider primary:/volume:
     * paths; otherwise copies the file into filesDir/fonts and returns that.
     */
    @Nullable
    private String resolveFontPath(@NonNull Uri uri) {
        String docId = null;
        try {
            docId = android.provider.DocumentsContract.getDocumentId(uri);
        } catch (Exception ignored) {
        }
        if (docId != null && docId.contains(":")) {
            String[] split = docId.split(":", 2);
            String type = split[0];
            String rel = split[1];
            if ("primary".equalsIgnoreCase(type)) {
                File f = new File(Environment.getExternalStorageDirectory(), rel);
                if (f.exists()) return f.getAbsolutePath();
            } else if (!"raw".equalsIgnoreCase(type)) {
                File candidate = new File("/storage/" + type + "/" + rel);
                if (candidate.exists()) return candidate.getAbsolutePath();
            } else {
                File candidate = new File(rel);
                if (candidate.exists()) return candidate.getAbsolutePath();
            }
        }
        // Fallback: copy into internal storage.
        return copyToInternal(uri);
    }

    @Nullable
    private String copyToInternal(@NonNull Uri uri) {
        try {
            File dir = new File(requireContext().getFilesDir(), "fonts");
            if (!dir.exists() && !dir.mkdirs()) return null;
            String name = "font_" + System.currentTimeMillis();
            File out = new File(dir, name);
            try (InputStream in = requireContext().getContentResolver().openInputStream(uri);
                 OutputStream os = new FileOutputStream(out)) {
                if (in == null) return null;
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }
}
