// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.fonts;

import android.content.Context;
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
import io.github.muntashirakon.AppManager.utils.UIUtils;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatSeekBar;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.processreaper.MonitorPrefs;
import io.github.muntashirakon.AppManager.processreaper.MonitorSeparatorPrefs;

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
                    // (The former "Card outline" colour rows are gone: unselected
                    // cells draw no stroke since the edge-to-edge separator grid.)
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

    // Fork: the five text surfaces of the process detail page (built into a manual
    // group alongside the icon-size / line-padding sliders).
    private static final Cat[] DETAIL_CATS = {
            new Cat(FontPrefs.MONITOR_DETAIL_LABEL, R.string.pref_detail_cat_label, new ColorSpec[]{
                    new ColorSpec(ColorPrefs.MONITOR_DETAIL_LABEL, R.string.pref_detail_color_label)}),
            new Cat(FontPrefs.MONITOR_DETAIL_ID, R.string.pref_detail_cat_id, new ColorSpec[]{
                    new ColorSpec(ColorPrefs.MONITOR_DETAIL_ID, R.string.pref_detail_color_id)}),
            new Cat(FontPrefs.MONITOR_DETAIL_SECTION, R.string.pref_detail_cat_section, new ColorSpec[]{
                    new ColorSpec(ColorPrefs.MONITOR_DETAIL_SECTION, R.string.pref_detail_color_section)}),
            new Cat(FontPrefs.MONITOR_DETAIL_ROW_LABEL, R.string.pref_detail_cat_row_label, new ColorSpec[]{
                    new ColorSpec(ColorPrefs.MONITOR_DETAIL_ROW_LABEL, R.string.pref_detail_color_row_label)}),
            new Cat(FontPrefs.MONITOR_DETAIL_ROW_VALUE, R.string.pref_detail_cat_row_value, new ColorSpec[]{
                    new ColorSpec(ColorPrefs.MONITOR_DETAIL_ROW_VALUE, R.string.pref_detail_color_row_value)}),
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
            // Fork: append the main-list app-icon controls (size + roundness) to
            // the existing "Main app list" group, so there is a single group.
            if (g.titleRes == R.string.pref_font_group_main_list) {
                groupContent.addView(buildMainIconElement(inflater, groupContent));
                groupContent.addView(buildMainRoundnessElement(inflater, groupContent));
            }
            container.addView(groupView);
        }
        // Fork: main-list separators (width slider + colour each) — built
        // manually because the data-driven Cat structure is font/colour-only.
        View sepGroup = inflater.inflate(R.layout.view_font_group, container, false);
        ((AppCompatTextView) sepGroup.findViewById(R.id.group_header)).setText(R.string.pref_sep_group);
        LinearLayoutCompat sepContent = sepGroup.findViewById(R.id.group_content);
        sepContent.addView(buildSeparatorElement(inflater, sepContent, true));
        sepContent.addView(buildSeparatorElement(inflater, sepContent, false));
        container.addView(sepGroup);
        // Fork: the running/active app box (border width + user/system colours).
        View boxGroup = inflater.inflate(R.layout.view_font_group, container, false);
        ((AppCompatTextView) boxGroup.findViewById(R.id.group_header)).setText(R.string.pref_runbox_group);
        LinearLayoutCompat boxContent = boxGroup.findViewById(R.id.group_content);
        boxContent.addView(buildRunningBoxElement(inflater, boxContent));
        container.addView(boxGroup);
        // Fork: process monitor — app-icon size + the four row-state colours.
        View monGroup = inflater.inflate(R.layout.view_font_group, container, false);
        ((AppCompatTextView) monGroup.findViewById(R.id.group_header)).setText(R.string.pref_monitor_group);
        LinearLayoutCompat monContent = monGroup.findViewById(R.id.group_content);
        monContent.addView(buildMonitorIconElement(inflater, monContent));
        monContent.addView(buildMonitorPaddingElement(inflater, monContent));
        monContent.addView(buildMonitorLeakCountElement(inflater, monContent));
        monContent.addView(buildMonitorLeakAgeElement(inflater, monContent));
        View monColors = inflater.inflate(R.layout.view_font_element, monContent, false);
        ((AppCompatTextView) monColors.findViewById(R.id.element_label)).setText(R.string.pref_monitor_row_colors);
        monColors.findViewById(R.id.font_controls).setVisibility(View.GONE);
        LinearLayoutCompat monCr = monColors.findViewById(R.id.color_rows);
        addColorRow(monCr, new ColorSpec(ColorPrefs.MONITOR_KILLABLE, R.string.pref_monitor_killable), () -> {});
        addColorRow(monCr, new ColorSpec(ColorPrefs.MONITOR_LEAK, R.string.pref_monitor_leak), () -> {});
        addColorRow(monCr, new ColorSpec(ColorPrefs.MONITOR_PROTECTED, R.string.pref_monitor_protected), () -> {});
        addColorRow(monCr, new ColorSpec(ColorPrefs.MONITOR_USER_PROTECTED, R.string.pref_monitor_user_protected), () -> {});
        monContent.addView(monColors);
        monContent.addView(buildMonitorSeparatorElement(inflater, monContent, true));
        monContent.addView(buildMonitorSeparatorElement(inflater, monContent, false));
        container.addView(monGroup);
        // Fork: process detail page — icon size, line padding, and the five text
        // surfaces (each: font family/weight/size + colour, via bindElement).
        View detGroup = inflater.inflate(R.layout.view_font_group, container, false);
        ((AppCompatTextView) detGroup.findViewById(R.id.group_header)).setText(R.string.pref_detail_group);
        LinearLayoutCompat detContent = detGroup.findViewById(R.id.group_content);
        detContent.addView(buildDetailIconElement(inflater, detContent));
        detContent.addView(buildDetailPaddingElement(inflater, detContent));
        for (Cat c : DETAIL_CATS) {
            View el = inflater.inflate(R.layout.view_font_element, detContent, false);
            bindElement(el, c);
            detContent.addView(el);
        }
        container.addView(detGroup);
        // Fork: the selected card's frame (colour + border width + roundness).
        View frameGroup = inflater.inflate(R.layout.view_font_group, container, false);
        ((AppCompatTextView) frameGroup.findViewById(R.id.group_header)).setText(R.string.pref_selframe_group);
        LinearLayoutCompat frameContent = frameGroup.findViewById(R.id.group_content);
        frameContent.addView(buildSelectionFrameElement(inflater, frameContent));
        container.addView(frameGroup);
        // Fork: a reference legend at the TOP of the page — sample chips styled
        // in code to mirror the live list, with what each colour/style means.
        View legendGroup = inflater.inflate(R.layout.view_font_group, container, false);
        ((AppCompatTextView) legendGroup.findViewById(R.id.group_header)).setText(R.string.pref_legend_group);
        LinearLayoutCompat legendContent = legendGroup.findViewById(R.id.group_content);
        buildLegend(inflater, legendContent);
        container.addView(legendGroup, 0);
    }

    /** Fork: applies the sample-chip styling for one legend row. */
    private interface LegendStyler {
        void apply(@NonNull AppCompatTextView sample);
    }

    /**
     * Fork: the colour/style legend. Each row's sample chip is styled to look
     * like the real main-list row (coloured label text, a stroked box, a shaded
     * background, or a tinted snowflake), reading the CURRENT configured colours
     * so it stays accurate after recolouring — reopen the page to refresh.
     */
    private void buildLegend(@NonNull LayoutInflater inflater, @NonNull LinearLayoutCompat content) {
        final Context ctx = requireContext();
        final float density = ctx.getResources().getDisplayMetrics().density;
        final CharSequence sample = getString(R.string.pref_legend_sample);

        addLegendHeader(content, R.string.pref_legend_sec_name, density);
        addLegendRow(inflater, content, sample, R.string.pref_legend_user,
                tv -> tv.setTextColor(ColorPrefs.getColor(ctx, ColorPrefs.LABEL_USER)));
        addLegendRow(inflater, content, sample, R.string.pref_legend_system,
                tv -> tv.setTextColor(ColorPrefs.getColor(ctx, ColorPrefs.LABEL_SYSTEM)));
        addLegendRow(inflater, content, sample, R.string.pref_legend_dormant, tv -> {
            tv.setTextColor(ColorPrefs.getColor(ctx, ColorPrefs.LABEL_USER));
            tv.setTypeface(null, Typeface.ITALIC);
        });
        addLegendRow(inflater, content, sample, R.string.pref_legend_uninstalled, tv -> {
            tv.setTextColor(ColorUtils.setAlphaComponent(ColorPrefs.getColor(ctx, ColorPrefs.LABEL_USER), 0xA6));
            tv.setTypeface(null, Typeface.ITALIC);
        });

        addLegendHeader(content, R.string.pref_legend_sec_id, density);
        addLegendRow(inflater, content, "com.app", R.string.pref_legend_id_normal,
                tv -> tv.setTextColor(ColorPrefs.getColor(ctx, ColorPrefs.PACKAGE_NORMAL)));
        addLegendRow(inflater, content, "com.app", R.string.pref_legend_id_trackers,
                tv -> tv.setTextColor(ColorPrefs.getColor(ctx, ColorPrefs.PACKAGE_TRACKERS)));

        addLegendHeader(content, R.string.pref_legend_sec_box, density);
        addLegendRow(inflater, content, sample, R.string.pref_legend_box_user,
                tv -> tv.setBackground(boxChip(ColorPrefs.getColor(ctx, ColorPrefs.STROKE_USER),
                        RunningBoxPrefs.getWidthDp(ctx), density)));
        addLegendRow(inflater, content, sample, R.string.pref_legend_box_system,
                tv -> tv.setBackground(boxChip(ColorPrefs.getColor(ctx, ColorPrefs.STROKE_SYSTEM),
                        RunningBoxPrefs.getWidthDp(ctx), density)));
        addLegendRow(inflater, content, sample, R.string.pref_legend_box_none, tv -> {});
        addLegendRow(inflater, content, sample, R.string.pref_legend_selected,
                tv -> tv.setBackground(boxChip(ColorPrefs.getColor(ctx, ColorPrefs.SELECTED_FRAME),
                        Math.max(2f, SelectionFramePrefs.getWidthDp(ctx)), density)));

        addLegendHeader(content, R.string.pref_legend_sec_shading, density);
        addLegendRow(inflater, content, sample, R.string.pref_legend_film_frozen,
                tv -> tv.setBackground(shadeChip(ColorPrefs.getColor(ctx, ColorPrefs.FILM_FROZEN), density)));
        addLegendRow(inflater, content, sample, R.string.pref_legend_film_uninstalled,
                tv -> tv.setBackground(shadeChip(ColorPrefs.getColor(ctx, ColorPrefs.FILM_UNINSTALLED), density)));

        addLegendHeader(content, R.string.pref_legend_sec_freeze, density);
        addLegendRow(inflater, content, "❄", R.string.pref_legend_freeze_on,
                tv -> tv.setTextColor(ColorPrefs.getColor(ctx, ColorPrefs.FREEZE_FROZEN)));
        addLegendRow(inflater, content, "❄", R.string.pref_legend_freeze_off,
                tv -> tv.setTextColor(ColorPrefs.getColor(ctx, ColorPrefs.FREEZE_THAWED)));
    }

    private void addLegendHeader(@NonNull LinearLayoutCompat content, int textRes, float density) {
        AppCompatTextView h = new AppCompatTextView(requireContext());
        h.setText(textRes);
        h.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_bright_yellow));
        h.setTypeface(h.getTypeface(), Typeface.BOLD);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayoutCompat.LayoutParams lp = new LinearLayoutCompat.LayoutParams(
                LinearLayoutCompat.LayoutParams.WRAP_CONTENT, LinearLayoutCompat.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(14 * density);
        lp.setMarginStart(Math.round(16 * density));
        h.setLayoutParams(lp);
        content.addView(h);
    }

    private void addLegendRow(@NonNull LayoutInflater inflater, @NonNull LinearLayoutCompat parent,
                              @NonNull CharSequence sampleText, int descRes, @NonNull LegendStyler styler) {
        View row = inflater.inflate(R.layout.view_legend_row, parent, false);
        AppCompatTextView sample = row.findViewById(R.id.legend_sample);
        sample.setText(sampleText);
        styler.apply(sample);
        ((AppCompatTextView) row.findViewById(R.id.legend_desc)).setText(descRes);
        parent.addView(row);
    }

    /** A transparent rounded chip with a coloured stroke (mimics a card box). */
    @NonNull
    private GradientDrawable boxChip(int strokeColor, float widthDp, float density) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setCornerRadius(4 * density);
        int w = widthDp <= 0f ? Math.round(density) : Math.max(1, Math.round(widthDp * density));
        g.setStroke(w, strokeColor);
        return g;
    }

    /** A filled rounded chip in the shading colour, hair-lined so dark tints show. */
    @NonNull
    private GradientDrawable shadeChip(int fillColor, float density) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fillColor);
        g.setCornerRadius(4 * density);
        g.setStroke(Math.max(1, Math.round(density)), 0xFF555555);
        return g;
    }

    /** Fork: the process-detail header icon size slider. */
    @NonNull
    private View buildDetailIconElement(@NonNull LayoutInflater inflater, @NonNull LinearLayoutCompat parent) {
        View element = inflater.inflate(R.layout.view_separator_element, parent, false);
        ((AppCompatTextView) element.findViewById(R.id.element_label)).setText(R.string.pref_detail_icon_size);
        ((AppCompatTextView) element.findViewById(R.id.sep_sublabel)).setText(R.string.pref_detail_icon_size_hint);
        final AppCompatTextView valueView = element.findViewById(R.id.sep_width_value);
        final AppCompatSeekBar seek = element.findViewById(R.id.sep_width_seek);
        final int min = MonitorPrefs.MIN_DETAIL_ICON_DP;
        seek.setMax(MonitorPrefs.MAX_DETAIL_ICON_DP - min);
        final Runnable render = () -> {
            int dp = MonitorPrefs.getDetailIconDp(requireContext());
            valueView.setText(String.format(Locale.US, "%d dp", dp));
            seek.setProgress(dp - min);
        };
        render.run();
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                MonitorPrefs.setDetailIconDp(requireContext(), min + progress);
                render.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        return element;
    }

    /** Fork: the process-detail per-row vertical-padding slider. */
    @NonNull
    private View buildDetailPaddingElement(@NonNull LayoutInflater inflater, @NonNull LinearLayoutCompat parent) {
        View element = inflater.inflate(R.layout.view_separator_element, parent, false);
        ((AppCompatTextView) element.findViewById(R.id.element_label)).setText(R.string.pref_detail_row_pad);
        ((AppCompatTextView) element.findViewById(R.id.sep_sublabel)).setText(R.string.pref_detail_row_pad_hint);
        final AppCompatTextView valueView = element.findViewById(R.id.sep_width_value);
        final AppCompatSeekBar seek = element.findViewById(R.id.sep_width_seek);
        seek.setMax(MonitorPrefs.MAX_DETAIL_ROW_PAD_DP);
        final Runnable render = () -> {
            int dp = MonitorPrefs.getDetailRowPadDp(requireContext());
            valueView.setText(String.format(Locale.US, "%d dp", dp));
            seek.setProgress(dp);
        };
        render.run();
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                MonitorPrefs.setDetailRowPadDp(requireContext(), progress);
                render.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        return element;
    }

    /** Fork: the main app list app-icon size slider. */
    @NonNull
    private View buildMainIconElement(@NonNull LayoutInflater inflater, @NonNull LinearLayoutCompat parent) {
        View element = inflater.inflate(R.layout.view_separator_element, parent, false);
        ((AppCompatTextView) element.findViewById(R.id.element_label)).setText(R.string.pref_mainlist_icon_size);
        ((AppCompatTextView) element.findViewById(R.id.sep_sublabel)).setText(R.string.pref_mainlist_icon_size_hint);
        final AppCompatTextView valueView = element.findViewById(R.id.sep_width_value);
        final AppCompatSeekBar seek = element.findViewById(R.id.sep_width_seek);
        final int min = MainIconPrefs.MIN_SIZE_DP;
        seek.setMax(MainIconPrefs.MAX_SIZE_DP - min);
        final Runnable render = () -> {
            int dp = MainIconPrefs.getSizeDp(requireContext());
            valueView.setText(String.format(Locale.US, "%d dp", dp));
            seek.setProgress(dp - min);
        };
        render.run();
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                MainIconPrefs.setSizeDp(requireContext(), min + progress);
                render.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        return element;
    }

    /** Fork: the main app list app-icon roundness slider (% of the icon size). */
    @NonNull
    private View buildMainRoundnessElement(@NonNull LayoutInflater inflater, @NonNull LinearLayoutCompat parent) {
        View element = inflater.inflate(R.layout.view_separator_element, parent, false);
        ((AppCompatTextView) element.findViewById(R.id.element_label)).setText(R.string.pref_mainlist_icon_roundness);
        ((AppCompatTextView) element.findViewById(R.id.sep_sublabel)).setText(R.string.pref_mainlist_icon_roundness_hint);
        final AppCompatTextView valueView = element.findViewById(R.id.sep_width_value);
        final AppCompatSeekBar seek = element.findViewById(R.id.sep_width_seek);
        final int min = MainIconPrefs.MIN_ROUNDNESS_PCT;
        seek.setMax(MainIconPrefs.MAX_ROUNDNESS_PCT - min);
        final Runnable render = () -> {
            int pct = MainIconPrefs.getRoundnessPercent(requireContext());
            valueView.setText(String.format(Locale.US, "%d%%", pct));
            seek.setProgress(pct - min);
        };
        render.run();
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                MainIconPrefs.setRoundnessPercent(requireContext(), min + progress);
                render.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        return element;
    }

    /** Fork: the process-monitor app-icon size slider (reuses the separator element). */
    @NonNull
    private View buildMonitorIconElement(@NonNull LayoutInflater inflater, @NonNull LinearLayoutCompat parent) {
        View element = inflater.inflate(R.layout.view_separator_element, parent, false);
        ((AppCompatTextView) element.findViewById(R.id.element_label)).setText(R.string.pref_monitor_icon_size);
        ((AppCompatTextView) element.findViewById(R.id.sep_sublabel)).setText(R.string.pref_monitor_icon_size_hint);
        final AppCompatTextView valueView = element.findViewById(R.id.sep_width_value);
        final AppCompatSeekBar seek = element.findViewById(R.id.sep_width_seek);
        final int min = MonitorPrefs.MIN_ICON_DP;
        seek.setMax(MonitorPrefs.MAX_ICON_DP - min);
        final Runnable render = () -> {
            int dp = MonitorPrefs.getIconSizeDp(requireContext());
            valueView.setText(String.format(Locale.US, "%d dp", dp));
            seek.setProgress(dp - min);
        };
        render.run();
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                MonitorPrefs.setIconSizeDp(requireContext(), min + progress);
                render.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        return element;
    }

    /** Fork: the process-monitor row vertical-padding slider. */
    @NonNull
    private View buildMonitorPaddingElement(@NonNull LayoutInflater inflater, @NonNull LinearLayoutCompat parent) {
        View element = inflater.inflate(R.layout.view_separator_element, parent, false);
        ((AppCompatTextView) element.findViewById(R.id.element_label)).setText(R.string.pref_monitor_row_pad);
        ((AppCompatTextView) element.findViewById(R.id.sep_sublabel)).setText(R.string.pref_monitor_row_pad_hint);
        final AppCompatTextView valueView = element.findViewById(R.id.sep_width_value);
        final AppCompatSeekBar seek = element.findViewById(R.id.sep_width_seek);
        seek.setMax(MonitorPrefs.MAX_ROW_PAD_DP);
        final Runnable render = () -> {
            int dp = MonitorPrefs.getRowPaddingDp(requireContext());
            valueView.setText(String.format(Locale.US, "%d dp", dp));
            seek.setProgress(dp);
        };
        render.run();
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                MonitorPrefs.setRowPaddingDp(requireContext(), progress);
                render.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        return element;
    }

    /** Fork: the leak-grouping count threshold slider (min identical processes). */
    @NonNull
    private View buildMonitorLeakCountElement(@NonNull LayoutInflater inflater, @NonNull LinearLayoutCompat parent) {
        View element = inflater.inflate(R.layout.view_separator_element, parent, false);
        ((AppCompatTextView) element.findViewById(R.id.element_label)).setText(R.string.pref_monitor_leak_count);
        ((AppCompatTextView) element.findViewById(R.id.sep_sublabel)).setText(R.string.pref_monitor_leak_count_hint);
        final AppCompatTextView valueView = element.findViewById(R.id.sep_width_value);
        final AppCompatSeekBar seek = element.findViewById(R.id.sep_width_seek);
        seek.setMax(MonitorPrefs.MAX_LEAK_COUNT - MonitorPrefs.MIN_LEAK_COUNT);
        final Runnable render = () -> {
            int n = MonitorPrefs.getLeakThreshold(requireContext());
            valueView.setText(String.format(Locale.US, "%d", n));
            seek.setProgress(n - MonitorPrefs.MIN_LEAK_COUNT);
        };
        render.run();
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                MonitorPrefs.setLeakThreshold(requireContext(), MonitorPrefs.MIN_LEAK_COUNT + progress);
                render.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        return element;
    }

    /** Fork: the leak min-age slider (sustained-age filter; 0 = off). */
    @NonNull
    private View buildMonitorLeakAgeElement(@NonNull LayoutInflater inflater, @NonNull LinearLayoutCompat parent) {
        View element = inflater.inflate(R.layout.view_separator_element, parent, false);
        ((AppCompatTextView) element.findViewById(R.id.element_label)).setText(R.string.pref_monitor_leak_age);
        ((AppCompatTextView) element.findViewById(R.id.sep_sublabel)).setText(R.string.pref_monitor_leak_age_hint);
        final AppCompatTextView valueView = element.findViewById(R.id.sep_width_value);
        final AppCompatSeekBar seek = element.findViewById(R.id.sep_width_seek);
        seek.setMax(MonitorPrefs.MAX_LEAK_AGE_SEC / MonitorPrefs.LEAK_AGE_STEP_SEC);
        final Runnable render = () -> {
            int s = MonitorPrefs.getLeakMinAgeSec(requireContext());
            valueView.setText(s <= 0 ? getString(R.string.pref_monitor_leak_age_off)
                    : String.format(Locale.US, "%d s", s));
            seek.setProgress(s / MonitorPrefs.LEAK_AGE_STEP_SEC);
        };
        render.run();
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                MonitorPrefs.setLeakMinAgeSec(requireContext(), progress * MonitorPrefs.LEAK_AGE_STEP_SEC);
                render.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        return element;
    }

    /**
     * Fork: the running/active app box — border-width slider (0.5dp steps, 0 =
     * no box), corner-roundness slider (1dp steps, 0 = square), and the
     * user/system stroke colours. Reuses the selection-frame element (two
     * sliders + colour rows).
     */
    @NonNull
    private View buildRunningBoxElement(@NonNull LayoutInflater inflater, @NonNull LinearLayoutCompat parent) {
        View element = inflater.inflate(R.layout.view_selection_frame_element, parent, false);
        ((AppCompatTextView) element.findViewById(R.id.element_label)).setText(R.string.pref_runbox_cat);
        final AppCompatTextView widthValue = element.findViewById(R.id.frame_width_value);
        final AppCompatSeekBar widthSeek = element.findViewById(R.id.frame_width_seek);
        final AppCompatTextView radiusValue = element.findViewById(R.id.frame_radius_value);
        final AppCompatSeekBar radiusSeek = element.findViewById(R.id.frame_radius_seek);
        widthSeek.setMax(Math.round(RunningBoxPrefs.MAX_WIDTH_DP * 2));  // half-dp steps
        radiusSeek.setMax(RunningBoxPrefs.MAX_RADIUS_DP);
        final Runnable render = () -> {
            float widthDp = RunningBoxPrefs.getWidthDp(requireContext());
            int radiusDp = RunningBoxPrefs.getRadiusDp(requireContext());
            widthValue.setText(String.format(Locale.US, "%.1f dp", widthDp));
            widthSeek.setProgress(Math.round(widthDp * 2));
            radiusValue.setText(String.format(Locale.US, "%d dp", radiusDp));
            radiusSeek.setProgress(radiusDp);
        };
        render.run();
        widthSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                RunningBoxPrefs.setWidthDp(requireContext(), progress / 2f);
                render.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        radiusSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                RunningBoxPrefs.setRadiusDp(requireContext(), progress);
                render.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        LinearLayoutCompat colorRows = element.findViewById(R.id.color_rows);
        addColorRow(colorRows, new ColorSpec(ColorPrefs.STROKE_USER, R.string.pref_color_stroke_user), () -> {});
        addColorRow(colorRows, new ColorSpec(ColorPrefs.STROKE_SYSTEM, R.string.pref_color_stroke_system), () -> {});
        return element;
    }

    /** Fork: one process-monitor separator (horizontal or vertical): width slider + colour. */
    @NonNull
    private View buildMonitorSeparatorElement(@NonNull LayoutInflater inflater, @NonNull LinearLayoutCompat parent,
                                              boolean horizontal) {
        View element = inflater.inflate(R.layout.view_separator_element, parent, false);
        ((AppCompatTextView) element.findViewById(R.id.element_label)).setText(
                horizontal ? R.string.pref_sep_cat_horizontal : R.string.pref_sep_cat_vertical);
        final AppCompatTextView widthValue = element.findViewById(R.id.sep_width_value);
        final AppCompatSeekBar widthSeek = element.findViewById(R.id.sep_width_seek);
        widthSeek.setMax(Math.round(MonitorSeparatorPrefs.MAX_WIDTH_DP * 2));
        final Runnable render = () -> {
            float dp = MonitorSeparatorPrefs.getWidthDp(requireContext(), horizontal);
            widthValue.setText(String.format(Locale.US, "%.1f dp", dp));
            widthSeek.setProgress(Math.round(dp * 2));
        };
        render.run();
        widthSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                MonitorSeparatorPrefs.setWidthDp(requireContext(), horizontal, progress / 2f);
                render.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        LinearLayoutCompat colorRows = element.findViewById(R.id.color_rows);
        addColorRow(colorRows, new ColorSpec(
                horizontal ? ColorPrefs.MONITOR_SEPARATOR_H : ColorPrefs.MONITOR_SEPARATOR_V,
                R.string.pref_color_separator), () -> {});
        return element;
    }

    /**
     * The selected (checked) card's frame: border-width slider (0.5dp steps,
     * 0 = no border), corner-roundness slider (1dp steps, 0 = square), and a
     * colour row reusing the standard picker.
     */
    @NonNull
    private View buildSelectionFrameElement(@NonNull LayoutInflater inflater, @NonNull LinearLayoutCompat parent) {
        View element = inflater.inflate(R.layout.view_selection_frame_element, parent, false);
        ((AppCompatTextView) element.findViewById(R.id.element_label)).setText(R.string.pref_selframe_cat);
        final AppCompatTextView widthValue = element.findViewById(R.id.frame_width_value);
        final AppCompatSeekBar widthSeek = element.findViewById(R.id.frame_width_seek);
        final AppCompatTextView radiusValue = element.findViewById(R.id.frame_radius_value);
        final AppCompatSeekBar radiusSeek = element.findViewById(R.id.frame_radius_seek);
        widthSeek.setMax(Math.round(SelectionFramePrefs.MAX_WIDTH_DP * 2));  // half-dp steps
        radiusSeek.setMax(SelectionFramePrefs.MAX_RADIUS_DP);
        final Runnable render = () -> {
            float widthDp = SelectionFramePrefs.getWidthDp(requireContext());
            int radiusDp = SelectionFramePrefs.getRadiusDp(requireContext());
            widthValue.setText(String.format(Locale.US, "%.1f dp", widthDp));
            widthSeek.setProgress(Math.round(widthDp * 2));
            radiusValue.setText(String.format(Locale.US, "%d dp", radiusDp));
            radiusSeek.setProgress(radiusDp);
        };
        render.run();
        widthSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                SelectionFramePrefs.setWidthDp(requireContext(), progress / 2f);
                render.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        radiusSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                SelectionFramePrefs.setRadiusDp(requireContext(), progress);
                render.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        LinearLayoutCompat colorRows = element.findViewById(R.id.color_rows);
        addColorRow(colorRows, new ColorSpec(ColorPrefs.SELECTED_FRAME, R.string.pref_color_separator), () -> {});
        return element;
    }

    /**
     * One separator element: heading, width value + slider (0–{@link SeparatorPrefs#MAX_WIDTH_DP}dp
     * in 0.5dp steps, 0 = none), and a colour row reusing the standard picker.
     */
    @NonNull
    private View buildSeparatorElement(@NonNull LayoutInflater inflater, @NonNull LinearLayoutCompat parent,
                                       boolean horizontal) {
        View element = inflater.inflate(R.layout.view_separator_element, parent, false);
        ((AppCompatTextView) element.findViewById(R.id.element_label)).setText(
                horizontal ? R.string.pref_sep_cat_horizontal : R.string.pref_sep_cat_vertical);
        final AppCompatTextView widthValue = element.findViewById(R.id.sep_width_value);
        final AppCompatSeekBar widthSeek = element.findViewById(R.id.sep_width_seek);
        widthSeek.setMax(Math.round(SeparatorPrefs.MAX_WIDTH_DP * 2));  // half-dp steps
        final Runnable render = () -> {
            float dp = SeparatorPrefs.getWidthDp(requireContext(), horizontal);
            widthValue.setText(String.format(Locale.US, "%.1f dp", dp));
            widthSeek.setProgress(Math.round(dp * 2));
        };
        render.run();
        widthSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                SeparatorPrefs.setWidthDp(requireContext(), horizontal, progress / 2f);
                render.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        LinearLayoutCompat colorRows = element.findViewById(R.id.color_rows);
        addColorRow(colorRows, new ColorSpec(
                horizontal ? ColorPrefs.SEPARATOR_H : ColorPrefs.SEPARATOR_V,
                R.string.pref_color_separator), () -> {});
        return element;
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
    /** Wire one A/R/G/B slider: drag updates argb[index] + the hex/preview (the hex stays canonical). */
    private void wireColorChannel(@NonNull AppCompatSeekBar seek, @NonNull AppCompatTextView val,
                                  @NonNull int[] argb, int index, @NonNull EditText hexInput,
                                  @NonNull View preview, @NonNull boolean[] updating) {
        seek.setMax(255);
        seek.setProgress(argb[index]);
        val.setText(String.valueOf(argb[index]));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                val.setText(String.valueOf(progress));
                if (!fromUser) return;
                argb[index] = progress;
                int col = Color.argb(argb[0], argb[1], argb[2], argb[3]);
                updating[0] = true;
                hexInput.setText(hex(col));
                updating[0] = false;
                preview.setBackground(swatchDrawable(col));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    private void openColorPicker(@NonNull ColorSpec spec, @NonNull Runnable onChanged) {
        View body = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_color_picker, null);
        final View preview = body.findViewById(R.id.cp_preview);
        final EditText hexInput = body.findViewById(R.id.cp_hex);
        final LinearLayoutCompat presets = body.findViewById(R.id.cp_presets);
        final AppCompatSeekBar aSeek = body.findViewById(R.id.cp_alpha);
        final AppCompatSeekBar rSeek = body.findViewById(R.id.cp_red);
        final AppCompatSeekBar gSeek = body.findViewById(R.id.cp_green);
        final AppCompatSeekBar bSeek = body.findViewById(R.id.cp_blue);
        final AppCompatTextView aVal = body.findViewById(R.id.cp_alpha_val);
        final AppCompatTextView rVal = body.findViewById(R.id.cp_red_val);
        final AppCompatTextView gVal = body.findViewById(R.id.cp_green_val);
        final AppCompatTextView bVal = body.findViewById(R.id.cp_blue_val);
        final int[] argb = new int[4];
        final boolean[] updating = {false};
        int current = ColorPrefs.getColor(requireContext(), spec.key);
        hexInput.setText(hex(current));
        preview.setBackground(swatchDrawable(current));
        argb[0] = Color.alpha(current);
        argb[1] = Color.red(current);
        argb[2] = Color.green(current);
        argb[3] = Color.blue(current);
        wireColorChannel(aSeek, aVal, argb, 0, hexInput, preview, updating);
        wireColorChannel(rSeek, rVal, argb, 1, hexInput, preview, updating);
        wireColorChannel(gSeek, gVal, argb, 2, hexInput, preview, updating);
        wireColorChannel(bSeek, bVal, argb, 3, hexInput, preview, updating);
        hexInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                if (updating[0]) return;
                Integer col = parseColor(e.toString());
                if (col == null) return;
                preview.setBackground(swatchDrawable(col));
                argb[0] = Color.alpha(col);
                argb[1] = Color.red(col);
                argb[2] = Color.green(col);
                argb[3] = Color.blue(col);
                updating[0] = true;
                aSeek.setProgress(argb[0]);
                rSeek.setProgress(argb[1]);
                gSeek.setProgress(argb[2]);
                bSeek.setProgress(argb[3]);
                aVal.setText(String.valueOf(argb[0]));
                rVal.setText(String.valueOf(argb[1]));
                gVal.setText(String.valueOf(argb[2]));
                bVal.setText(String.valueOf(argb[3]));
                updating[0] = false;
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
                        UIUtils.displayShortToast(R.string.pref_color_invalid);
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
