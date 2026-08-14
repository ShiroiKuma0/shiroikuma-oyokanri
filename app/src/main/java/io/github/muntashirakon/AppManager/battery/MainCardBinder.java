// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.battery;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;

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
import io.github.muntashirakon.AppManager.main.RowPills;
import io.github.muntashirakon.AppManager.self.imagecache.ImageLoader;
import io.github.muntashirakon.AppManager.utils.AppNotesManager;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;

/**
 * Fork: renders {@code item_main.xml} — the main list's card — outside the main
 * list.
 *
 * <p>The battery panel's header is that card, so the two cannot look like two
 * different apps. Deliberately a <b>subset</b> of {@code MainRecyclerAdapter}'s
 * bind: selection state, search highlighting and the tag "+" belong to the list
 * and mean nothing on a single-app page. Everything visible — icon, freeze
 * snowflake, force-stop ✕, label, package, install date, uid, version, type,
 * SDK, signature, the note pill and the profile tags — is bound here.
 */
public final class MainCardBinder {
    private MainCardBinder() {}

    public static void bind(@NonNull Context context, @NonNull View card,
                            @Nullable ApplicationItem item,
                            @Nullable ApplicationInfo applicationInfo,
                            @Nullable String packageName, int uid, boolean frozen,
                            @NonNull List<String> profileTags,
                            @Nullable BatteryUsageViewModel.Row batteryRow,
                            @Nullable String windowLabel, boolean allCounters,
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
        // The battery column carries longer strings than version/SDK ever do,
        // so it takes a larger share here. Applied at bind time, so the main
        // list keeps the proportions it was tuned with.
        View centerColumn = card.findViewById(R.id.main_center_column);
        View rightColumn = card.findViewById(R.id.main_right_column);
        if (centerColumn != null && rightColumn != null) {
            LinearLayoutCompat.LayoutParams centerLp =
                    (LinearLayoutCompat.LayoutParams) centerColumn.getLayoutParams();
            LinearLayoutCompat.LayoutParams rightLp =
                    (LinearLayoutCompat.LayoutParams) rightColumn.getLayoutParams();
            centerLp.width = 0;
            rightLp.width = 0;
            centerLp.weight = batteryRow != null ? 1.35f : 1f;
            rightLp.weight = batteryRow != null ? 1f : 2f;
            centerColumn.setLayoutParams(centerLp);
            rightColumn.setLayoutParams(rightLp);
        }

        View barTrack = card.findViewById(R.id.battery_bar_track);
        View barFill = card.findViewById(R.id.battery_bar_fill);
        if (batteryRow != null) {
            BatterySampleDao.BatteryAggregate agg = batteryRow.agg;
            String headline = agg.powerModelUsable && agg.powerMah > 0
                    ? context.getString(R.string.battery_mah, agg.powerMah)
                    : String.format(Locale.getDefault(), "%d%%", Math.round(batteryRow.share * 100));
            int accent = batteryRow.share >= 0.25f ? 0xFFFF0028 : ForkThemeUtils.getTextColor();
            // The share never needs more than two digits, so the window it is
            // measured over rides in the space in front of it — grey, so it
            // reads as a qualifier rather than blending into the number.
            if (windowLabel != null) {
                SpannableStringBuilder sb = new SpannableStringBuilder();
                sb.append(windowLabel).append("  ");
                // Grey, lighter and smaller — it is a qualifier, and at the
                // headline's weight it pushed two-digit percentages out of view.
                sb.setSpan(new ForegroundColorSpan(0xFF9A9A9A), 0, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new RelativeSizeSpan(0.72f), 0, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(Typeface.NORMAL), 0, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                int from = sb.length();
                sb.append(headline);
                sb.setSpan(new ForegroundColorSpan(accent), from, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                version.setText(sb);
            } else {
                version.setText(headline);
                version.setTextColor(accent);
            }
            version.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
            version.setTypeface(null, Typeface.BOLD);
            version.setGravity(android.view.Gravity.END);
            // The share bar, as before — item_main keeps it GONE for the list.
            if (barTrack != null && barFill != null) {
                barTrack.setVisibility(View.VISIBLE);
                barFill.setBackgroundColor(accent);
                barTrack.post(() -> {
                    ViewGroup.LayoutParams lp = barFill.getLayoutParams();
                    lp.width = Math.max(batteryRow.share > 0 ? 2 : 0,
                            Math.round(barTrack.getWidth() * batteryRow.share));
                    barFill.setLayoutParams(lp);
                });
            }
            // The app's own page lists every counter; a list cell takes three,
            // which is what the corrected column widths left room for.
            String[] lines = BatteryUsageAdapter.describeLines(context, agg, allCounters ? 8 : 3);
            TextView sha = card.findViewById(R.id.sha);
            isSystem.setText(lines.length > 0 ? lines[0] : "");
            isSystem.setGravity(android.view.Gravity.END);
            isSystem.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
            isSystem.setEllipsize(null);
            isSystem.setSingleLine(false);
            isSystem.setMaxLines(allCounters ? 8 : 1);
            if (sha != null) {
                StringBuilder rest = new StringBuilder();
                for (int i = 1; i < lines.length; i++) {
                    if (rest.length() > 0) rest.append('\n');
                    rest.append(lines[i]);
                }
                sha.setText(rest.toString());
                sha.setGravity(android.view.Gravity.END);
                sha.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
                sha.setEllipsize(null);
                sha.setSingleLine(false);
                sha.setMaxLines(allCounters ? 8 : 2);
            }
        } else {
            if (barTrack != null) barTrack.setVisibility(View.GONE);
            version.setText(item != null && item.versionName != null ? item.versionName : "");
            version.setTextColor(ForkThemeUtils.getTextColor());
            isSystem.setText(context.getString(system ? R.string.system : R.string.user));
            setIfPresent(card, R.id.sha, "");
        }
        // The right column's third row (size + backup time) has nothing to say
        // on a battery card. Blanking its text still left the row occupying a
        // line — an empty gap opposite the date — so it is GONE, not "".
        goneIfPresent(card, R.id.backup_version);
        goneIfPresent(card, R.id.backup_date);
        goneIfPresent(card, R.id.backup_time);
        goneIfPresent(card, R.id.size);
        View backupIndicator = card.findViewById(R.id.backup_indicator);
        if (backupIndicator != null) backupIndicator.setVisibility(View.GONE);
        // The note pill and profile pills are part of the card 白い熊 asked for,
        // so they stay; only the list's selection machinery is left out.
        //
        // Fork (白い熊, +094): the note is ONE pill in both states, bound exactly
        // as the list binds it — so this card shows the note's first line rather
        // than a bare affordance, and tapping it opens the same editor. Before
        // this the card carried a visible "+" with no listener behind it.
        View favorite = card.findViewById(R.id.favorite_icon);
        if (favorite != null) favorite.setVisibility(View.GONE);

        TextView notePill = card.findViewById(R.id.note_pill);
        if (notePill != null) {
            // A uid with no package behind it has nothing to key a note on.
            notePill.setVisibility(packageName == null ? View.GONE : View.VISIBLE);
            if (packageName != null) {
                final String notePkg = packageName;
                final int noteInk = ForkThemeUtils.getTextColor();
                final CharSequence noteLabel = item != null && item.label != null
                        ? item.label : packageName;
                RowPills.bindNote(notePill, notePkg, noteInk);
                notePill.setOnClickListener(v -> AppNotesManager.showNoteDialog(
                        context, notePkg, noteLabel,
                        () -> RowPills.bindNote(notePill, notePkg, noteInk)));
            }
        }

        // Profile tags — the 保存復元 / 凍結 / 必要 pills from the main list, built
        // by the list's own builder so the two surfaces cannot drift apart.
        // Read-only here: this card is a header, not a list row.
        LinearLayoutCompat pills = card.findViewById(R.id.profile_pills);
        if (pills != null) {
            pills.removeAllViews();
            for (String name : profileTags) {
                TextView pill = RowPills.tagPill(context, name, yellow);
                pill.setClickable(false);
                pill.setFocusable(false);
                pills.addView(pill);
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

    private static void goneIfPresent(@NonNull View card, int id) {
        View v = card.findViewById(id);
        if (v != null) v.setVisibility(View.GONE);
    }
}
