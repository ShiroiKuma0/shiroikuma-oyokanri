// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.battery;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.db.dao.BatterySampleDao;
import io.github.muntashirakon.AppManager.fonts.ColorPrefs;
import io.github.muntashirakon.AppManager.fonts.RunningBoxPrefs;
import io.github.muntashirakon.AppManager.main.ApplicationItem;
import io.github.muntashirakon.AppManager.self.imagecache.ImageLoader;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;

/**
 * Fork: renders {@code item_main.xml} — the main list's card — outside the main
 * list.
 *
 * <p>The battery panel's header is that card, so the two cannot look like two
 * different apps. Deliberately a <b>subset</b> of {@code MainRecyclerAdapter}'s
 * bind: selection state, search highlighting and the note "+" belong to the
 * list and mean nothing on a single-app page. Everything visible — icon, freeze
 * snowflake, force-stop ✕, label, package, install date, uid, version, type,
 * SDK, signature and the profile tags — is bound here.
 */
public final class MainCardBinder {
    private MainCardBinder() {}

    public static void bind(@NonNull Context context, @NonNull View card,
                            @Nullable ApplicationItem item,
                            @Nullable ApplicationInfo applicationInfo,
                            @Nullable String packageName, int uid, boolean frozen,
                            @NonNull List<String> profileTags,
                            @Nullable BatteryUsageViewModel.Row batteryRow,
                            @NonNull View.OnClickListener onCard,
                            @NonNull View.OnClickListener onFreeze,
                            @NonNull View.OnClickListener onKill) {
        float density = context.getResources().getDisplayMetrics().density;
        MaterialCardView cardView = (MaterialCardView) card;

        int yellow = ContextCompat.getColor(context, R.color.theme_bright_yellow);
        int orange = ContextCompat.getColor(context, R.color.theme_bright_orange);
        int iceBlue = ContextCompat.getColor(context, R.color.theme_ice_blue);
        boolean system = item != null ? item.isSystem
                : applicationInfo != null && (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

        cardView.setCardBackgroundColor(frozen
                ? ColorPrefs.getColor(context, ColorPrefs.FILM_FROZEN,
                ContextCompat.getColor(context, R.color.theme_film_frozen))
                : Color.BLACK);
        if (!frozen) {
            float boxDp = RunningBoxPrefs.getWidthDp(context);
            cardView.setRadius(RunningBoxPrefs.getRadiusDp(context) * density);
            cardView.setStrokeWidth(boxDp <= 0f ? 0 : Math.max(1, Math.round(boxDp * density)));
            cardView.setStrokeColor(system
                    ? ColorPrefs.getColor(context, ColorPrefs.STROKE_SYSTEM, orange)
                    : ColorPrefs.getColor(context, ColorPrefs.STROKE_USER, yellow));
        } else {
            cardView.setRadius(0f);
            cardView.setStrokeWidth(0);
        }

        ImageView icon = card.findViewById(R.id.icon);
        if (applicationInfo != null && packageName != null) {
            icon.setTag(packageName);
            ImageLoader.getInstance().displayImage(packageName, applicationInfo, icon);
        } else {
            icon.setImageResource(R.drawable.ic_android);
        }
        icon.setAlpha(frozen ? 0.5f : 1f);

        ImageView freeze = card.findViewById(R.id.freeze_indicator);
        freeze.setImageResource(frozen
                ? R.drawable.ic_snowflake_24dp : R.drawable.ic_snowflake_outline_24dp);
        freeze.setImageTintList(ColorStateList.valueOf(frozen
                ? ColorPrefs.getColor(context, ColorPrefs.FREEZE_FROZEN, iceBlue)
                : ColorPrefs.getColor(context, ColorPrefs.FREEZE_THAWED, yellow)));
        View iconColumn = card.findViewById(R.id.icon_column);
        iconColumn.setOnClickListener(onFreeze);

        ImageView kill = card.findViewById(R.id.kill_badge);
        boolean killable = packageName != null && !frozen
                && !BuildConfig.APPLICATION_ID.equals(packageName);
        kill.setVisibility(killable ? View.VISIBLE : View.GONE);
        kill.setColorFilter(yellow);
        kill.setOnClickListener(killable ? onKill : null);

        TextView label = card.findViewById(R.id.label);
        label.setText(item != null && item.label != null ? item.label
                : (packageName != null ? packageName : "uid " + uid));
        label.setTypeface(null, frozen ? Typeface.ITALIC : Typeface.NORMAL);
        label.setTextColor(system ? orange : ForkThemeUtils.getTextColor());

        TextView pkg = card.findViewById(R.id.packageName);
        pkg.setText(packageName != null ? packageName : "—");
        pkg.setTypeface(null, frozen ? Typeface.ITALIC : Typeface.NORMAL);

        TextView date = card.findViewById(R.id.date);
        if (item != null && item.firstInstallTime > 0) {
            date.setText(new SimpleDateFormat("M/d/yy", Locale.getDefault())
                    .format(new Date(item.firstInstallTime)));
        } else date.setText("");

        TextView shareId = card.findViewById(R.id.shareid);
        shareId.setText(item != null && item.sharedUserId != null ? item.sharedUserId
                : String.valueOf(uid));

        // The right column: the main list puts version / type / SDK / signature
        // here; on the battery screens that space carries the drain instead.
        // Same card, same height, same everything else — only this column差.
        TextView version = card.findViewById(R.id.version);
        TextView isSystem = card.findViewById(R.id.isSystem);
        if (batteryRow != null) {
            BatterySampleDao.BatteryAggregate agg = batteryRow.agg;
            String headline = agg.powerModelUsable && agg.powerMah > 0
                    ? context.getString(R.string.battery_mah, agg.powerMah)
                    : String.format(Locale.getDefault(), "%d%%", Math.round(batteryRow.share * 100));
            version.setText(headline);
            version.setTextColor(batteryRow.share >= 0.25f ? 0xFFFF0028 : ForkThemeUtils.getTextColor());
            String[] lines = BatteryUsageAdapter.describeLines(context, agg);
            isSystem.setText(lines.length > 0 ? lines[0] : "");
            setIfPresent(card, R.id.sha, lines.length > 1 ? lines[1] : "");
        } else {
            version.setText(item != null && item.versionName != null ? item.versionName : "");
            version.setTextColor(ForkThemeUtils.getTextColor());
            isSystem.setText(context.getString(system ? R.string.system : R.string.user));
            setIfPresent(card, R.id.sha, "");
        }
        setIfPresent(card, R.id.backup_version, "");
        setIfPresent(card, R.id.backup_date, "");
        setIfPresent(card, R.id.backup_time, "");
        setIfPresent(card, R.id.size, "");
        View backupIndicator = card.findViewById(R.id.backup_indicator);
        if (backupIndicator != null) backupIndicator.setVisibility(View.GONE);
        // The note "+" and profile pills are part of the card 白い熊 asked for,
        // so they stay; only the list's selection machinery is left out.
        View noteAdd = card.findViewById(R.id.note_add);
        if (noteAdd != null) noteAdd.setVisibility(View.VISIBLE);
        View favorite = card.findViewById(R.id.favorite_icon);
        if (favorite != null) favorite.setVisibility(View.GONE);

        // Profile tags — the 保存復元 / 凍結 / 必要 pills from the main list.
        ChipGroup pills = card.findViewById(R.id.profile_pills);
        if (pills != null) {
            pills.removeAllViews();
            for (String name : profileTags) {
                Chip chip = new Chip(context);
                chip.setText(name);
                chip.setTextColor(yellow);
                chip.setChipBackgroundColor(ColorStateList.valueOf(Color.TRANSPARENT));
                chip.setChipStrokeColor(ColorStateList.valueOf(yellow));
                chip.setChipStrokeWidth(2f);
                chip.setCheckable(false);
                chip.setClickable(false);
                chip.setChipIconVisible(false);
                chip.setCloseIconVisible(false);
                pills.addView(chip);
            }
        }
        View addPill = card.findViewById(R.id.profile_add_pill);
        if (addPill != null) addPill.setVisibility(View.GONE);

        card.setOnClickListener(onCard);
    }

    private static void setIfPresent(@NonNull View card, int id, @NonNull String text) {
        View v = card.findViewById(id);
        if (v instanceof TextView) ((TextView) v).setText(text);
    }
}
