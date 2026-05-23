// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.fonts;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatSeekBar;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.content.ContextCompat;
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

    private static final class ColorSpec {
        final String key;
        final int labelRes;
        ColorSpec(String key, int labelRes) {
            this.key = key;
            this.labelRes = labelRes;
        }
    }

    private static final ColorSpec[] NO_COLORS = new ColorSpec[0];

    private static final class Cat {
        final String key;
        final int labelRes;
        final ColorSpec[] colors;
        Cat(String key, int labelRes, ColorSpec[] colors) {
            this.key = key;
            this.labelRes = labelRes;
            this.colors = colors;
        }
        Cat(String key, int labelRes) {
            this(key, labelRes, NO_COLORS);
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
                    new Cat(FontPrefs.LABEL, R.string.pref_font_cat_label, new ColorSpec[]{
                            new ColorSpec(ColorPrefs.LABEL_USER, R.string.pref_color_label_user),
                            new ColorSpec(ColorPrefs.LABEL_SYSTEM, R.string.pref_color_label_system),
                            new ColorSpec(ColorPrefs.LABEL_FROZEN, R.string.pref_color_label_frozen),
                    }),
                    new Cat(FontPrefs.PACKAGE, R.string.pref_font_cat_package, new ColorSpec[]{
                            new ColorSpec(ColorPrefs.PACKAGE_NORMAL, R.string.pref_color_package_normal),
                            new ColorSpec(ColorPrefs.PACKAGE_TRACKERS, R.string.pref_color_package_trackers),
                    }),
                    new Cat(FontPrefs.VERSION, R.string.pref_font_cat_version, new ColorSpec[]{
                            new ColorSpec(ColorPrefs.VERSION_NORMAL, R.string.pref_color_version_normal),
                            new ColorSpec(ColorPrefs.VERSION_INACTIVE, R.string.pref_color_version_inactive),
                    }),
                    new Cat(FontPrefs.APP_TYPE, R.string.pref_font_cat_app_type, new ColorSpec[]{
                            new ColorSpec(ColorPrefs.APPTYPE_NORMAL, R.string.pref_color_apptype_normal),
                            new ColorSpec(ColorPrefs.APPTYPE_PERSISTENT, R.string.pref_color_apptype_persistent),
                    }),
                    new Cat(FontPrefs.INSTALL_DATE, R.string.pref_font_cat_install_date, new ColorSpec[]{
                            new ColorSpec(ColorPrefs.DATE_NORMAL, R.string.pref_color_date_normal),
                            new ColorSpec(ColorPrefs.DATE_READABLE, R.string.pref_color_date_readable),
                    }),
                    new Cat(FontPrefs.UID, R.string.pref_font_cat_uid, new ColorSpec[]{
                            new ColorSpec(ColorPrefs.UID_NORMAL, R.string.pref_color_uid_normal),
                            new ColorSpec(ColorPrefs.UID_SHARED, R.string.pref_color_uid_shared),
                    }),
                    new Cat(FontPrefs.SDK, R.string.pref_font_cat_sdk, new ColorSpec[]{
                            new ColorSpec(ColorPrefs.SDK_NORMAL, R.string.pref_color_sdk_normal),
                            new ColorSpec(ColorPrefs.SDK_CLEARTEXT, R.string.pref_color_sdk_cleartext),
                    }),
                    new Cat(FontPrefs.SIGNATURE, R.string.pref_font_cat_signature, new ColorSpec[]{
                            new ColorSpec(ColorPrefs.SIGNATURE, R.string.pref_color_signature),
                    }),
                    new Cat(FontPrefs.BACKUP_INFO, R.string.pref_font_cat_backup_info, new ColorSpec[]{
                            new ColorSpec(ColorPrefs.BACKUP, R.string.pref_color_backup),
                    }),
            }),
            new Group(R.string.pref_color_group_indicators, new Cat[]{
                    new Cat(null, R.string.pref_color_cat_stroke, new ColorSpec[]{
                            new ColorSpec(ColorPrefs.STROKE_USER, R.string.pref_color_stroke_user),
                            new ColorSpec(ColorPrefs.STROKE_SYSTEM, R.string.pref_color_stroke_system),
                    }),
                    new Cat(null, R.string.pref_color_cat_freeze, new ColorSpec[]{
                            new ColorSpec(ColorPrefs.FREEZE_FROZEN, R.string.pref_color_freeze_frozen),
                            new ColorSpec(ColorPrefs.FREEZE_THAWED, R.string.pref_color_freeze_thawed),
                    }),
                    new Cat(null, R.string.pref_color_cat_chips, new ColorSpec[]{
                            new ColorSpec(ColorPrefs.CHIP, R.string.pref_color_chip),
                    }),
                    new Cat(null, R.string.pref_color_cat_addpill, new ColorSpec[]{
                            new ColorSpec(ColorPrefs.ADDPILL, R.string.pref_color_addpill),
                    }),
            }),
            new Group(R.string.pref_font_group_app_details, new Cat[]{
                    new Cat(FontPrefs.DETAIL_LABEL, R.string.pref_font_cat_label, new ColorSpec[]{
                            new ColorSpec(ColorPrefs.DETAIL_LABEL, R.string.pref_color_detail_label),
                    }),
                    new Cat(FontPrefs.DETAIL_PACKAGE, R.string.pref_font_cat_package, new ColorSpec[]{
                            new ColorSpec(ColorPrefs.DETAIL_PACKAGE, R.string.pref_color_detail_package),
                    }),
                    new Cat(FontPrefs.DETAIL_VERSION, R.string.pref_font_cat_version, new ColorSpec[]{
                            new ColorSpec(ColorPrefs.DETAIL_VERSION, R.string.pref_color_detail_version),
                    }),
            }),
    };

    // Sentinel family value for the trailing "Add custom font…" picker row.
    private static final String ADD_MARKER = "\u0000add_custom";

    @Nullable
    private String mPendingImportCat;    private ActivityResultLauncher<String[]> mOpenFont;
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
        label.setText(cat.labelRes);

        if (cat.key == null) {
            // Colour-only element (non-text indicator): hide the font controls,
            // render just the colour rows.
            element.findViewById(R.id.font_controls).setVisibility(View.GONE);
            LinearLayoutCompat cr = element.findViewById(R.id.color_rows);
            for (ColorSpec spec : cat.colors) {
                addColorRow(cr, spec, () -> {});
            }
            return;
        }

        final View rowFont = element.findViewById(R.id.row_font);
        final AppCompatTextView fontValue = element.findViewById(R.id.font_value);
        final View rowWeight = element.findViewById(R.id.row_weight);
        final AppCompatTextView weightValue = element.findViewById(R.id.weight_value);
        final AppCompatTextView sizeValue = element.findViewById(R.id.size_value);
        final AppCompatSeekBar sizeSeek = element.findViewById(R.id.size_seek);
        final AppCompatTextView preview = element.findViewById(R.id.preview);

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
            final List<FontUtil.Option> opts = new java.util.ArrayList<>(FontUtil.families(requireContext()));
            opts.add(new FontUtil.Option(getString(R.string.pref_font_add_custom), ADD_MARKER));
            // Preview each option in its own typeface, at the category's
            // effective weight so it reflects how the surface will look.
            int weight = FontPrefs.effectiveWeight(requireContext(), cat.key);
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.pref_font_family)
                    .setAdapter(fontPickerAdapter(opts, weight), (d, which) -> {
                        FontUtil.Option chosen = opts.get(which);
                        if (ADD_MARKER.equals(chosen.value)) {
                            // "Add custom font…" — remember which category to
                            // assign the picked file to, then launch SAF.
                            mPendingImportCat = cat.key;
                            mRefreshPending = render;
                            mOpenFont.launch(new String[]{"font/ttf", "font/otf",
                                    "application/x-font-ttf", "application/x-font-otf",
                                    "application/octet-stream", "*/*"});
                        } else {
                            FontPrefs.setFamily(requireContext(), cat.key, chosen.value);
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

        // --- Per-element colours (fork) ---
        // The font preview reflects the element's primary (first) colour role.
        final LinearLayoutCompat colorRows = element.findViewById(R.id.color_rows);
        final Runnable previewColor = () -> {
            if (cat.colors.length > 0) {
                preview.setTextColor(ColorPrefs.getColor(requireContext(), cat.colors[0].key));
            }
        };
        previewColor.run();
        for (ColorSpec spec : cat.colors) {
            addColorRow(colorRows, spec, previewColor);
        }
    }

    /** Add one tappable colour role row (swatch + label + value) to a container. */
    private void addColorRow(@NonNull LinearLayoutCompat container, @NonNull ColorSpec spec,
                             @NonNull Runnable previewRefresh) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.view_color_row, container, false);
        AppCompatTextView label = row.findViewById(R.id.cr_label);
        AppCompatTextView value = row.findViewById(R.id.cr_value);
        View swatch = row.findViewById(R.id.cr_swatch);
        label.setText(spec.labelRes);
        final Runnable refresh = () -> {
            int color = ColorPrefs.getColor(requireContext(), spec.key);
            swatch.setBackground(swatchDrawable(color));
            value.setText(ColorPrefs.isSet(requireContext(), spec.key)
                    ? hex(color) : getString(R.string.pref_color_default));
        };
        refresh.run();
        row.setOnClickListener(v -> openColorPicker(spec, () -> {
            refresh.run();
            previewRefresh.run();
        }));
        container.addView(row);
    }

    /** Colour picker dialog: hex input + live preview + preset palette swatches. */
    private void openColorPicker(@NonNull ColorSpec spec, @NonNull Runnable onChanged) {
        View body = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_color_picker, null);
        final View preview = body.findViewById(R.id.cp_preview);
        final EditText hexInput = body.findViewById(R.id.cp_hex);
        final LinearLayoutCompat presets = body.findViewById(R.id.cp_presets);
        int current = ColorPrefs.getColor(requireContext(), spec.key);
        hexInput.setText(hex(current));
        preview.setBackground(swatchDrawable(current));
        hexInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                Integer col = parseColor(e.toString());
                if (col != null) preview.setBackground(swatchDrawable(col));
            }
        });
        int[] palette = {
                ContextCompat.getColor(requireContext(), R.color.theme_bright_orange),
                ContextCompat.getColor(requireContext(), R.color.theme_bright_yellow),
                ContextCompat.getColor(requireContext(), R.color.theme_ice_blue),
                ContextCompat.getColor(requireContext(), io.github.muntashirakon.ui.R.color.stopped),
                Color.MAGENTA,
                ContextCompat.getColor(requireContext(), io.github.muntashirakon.ui.R.color.textColorSecondary),
                Color.WHITE,
                Color.BLACK,
        };
        int sz = (int) dp(32);
        int m = (int) dp(3);
        for (int p : palette) {
            View sw = new View(requireContext());
            LinearLayoutCompat.LayoutParams lp = new LinearLayoutCompat.LayoutParams(sz, sz);
            lp.setMargins(m, 0, m, 0);
            sw.setLayoutParams(lp);
            sw.setBackground(swatchDrawable(p));
            sw.setOnClickListener(v -> {
                hexInput.setText(hex(p));
                preview.setBackground(swatchDrawable(p));
            });
            presets.addView(sw);
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(spec.labelRes)
                .setView(body)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    Integer col = parseColor(hexInput.getText().toString());
                    if (col != null) {
                        ColorPrefs.setColor(requireContext(), spec.key, col);
                        onChanged.run();
                    } else {
                        Toast.makeText(requireContext(), R.string.pref_color_invalid, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton(R.string.pref_color_reset, (d, w) -> {
                    ColorPrefs.reset(requireContext(), spec.key);
                    onChanged.run();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @NonNull
    private GradientDrawable swatchDrawable(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(4));
        d.setColor(color);
        d.setStroke((int) dp(1), 0xFF666666);
        return d;
    }

    @NonNull
    private static String hex(int c) {
        if (Color.alpha(c) == 255) return String.format("#%06X", 0xFFFFFF & c);
        return String.format("#%08X", c);
    }

    @Nullable
    private static Integer parseColor(@NonNull String s) {
        s = s.trim();
        if (s.isEmpty()) return null;
        if (!s.startsWith("#")) s = "#" + s;
        try {
            return Color.parseColor(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /**
     * Adapter for the Font family chooser: each row shows a small caption
     * (the filename, for imported fonts) above a preview line rendered in
     * that font at {@code weight}, so the user sees the actual typeface.
     */
    @NonNull
    private ArrayAdapter<FontUtil.Option> fontPickerAdapter(@NonNull List<FontUtil.Option> opts, int weight) {
        final LayoutInflater inf = LayoutInflater.from(requireContext());
        return new ArrayAdapter<FontUtil.Option>(requireContext(), 0, opts) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View v = convertView != null ? convertView
                        : inf.inflate(R.layout.item_font_picker, parent, false);
                FontUtil.Option o = opts.get(position);
                TextView caption = v.findViewById(R.id.fp_caption);
                TextView previewLine = v.findViewById(R.id.fp_preview);
                if (ADD_MARKER.equals(o.value)) {
                    caption.setVisibility(View.GONE);
                    previewLine.setText(o.label);
                    previewLine.setTypeface(Typeface.DEFAULT);
                } else {
                    boolean isFile = o.value.startsWith(FontUtil.FILE_PREFIX);
                    caption.setVisibility(isFile ? View.VISIBLE : View.GONE);
                    if (isFile) caption.setText(o.label);
                    previewLine.setText(isFile ? stripExtension(o.label) : o.label);
                    Typeface tf = FontUtil.resolveTypeface(o.value, weight, null);
                    previewLine.setTypeface(tf != null ? tf : Typeface.DEFAULT);
                }
                return v;
            }
        };
    }

    @NonNull
    private static String stripExtension(@NonNull String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
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
