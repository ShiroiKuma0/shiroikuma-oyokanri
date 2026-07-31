// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main;

import static io.github.muntashirakon.AppManager.compat.PackageManagerCompat.MATCH_UNINSTALLED_PACKAGES;
import static io.github.muntashirakon.AppManager.utils.UIUtils.displayLongToast;
import static io.github.muntashirakon.AppManager.utils.UIUtils.displayShortToast;
import static io.github.muntashirakon.util.AdapterUtils.PAYLOAD_HIGHLIGHT_CHANGED;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;

import androidx.core.graphics.ColorUtils;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.RemoteException;
import android.os.UserHandleHidden;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.SectionIndexer;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.apk.installer.PackageInstallerActivity;
import io.github.muntashirakon.AppManager.fonts.ColorPrefs;
import io.github.muntashirakon.AppManager.fonts.FontPrefs;
import io.github.muntashirakon.AppManager.fonts.FontUtil;
import io.github.muntashirakon.AppManager.fonts.MainIconPrefs;
import io.github.muntashirakon.AppManager.fonts.RunningBoxPrefs;
import io.github.muntashirakon.AppManager.fonts.SelectionFramePrefs;
import io.github.muntashirakon.AppManager.apk.installer.PackageInstallerCompat;
import io.github.muntashirakon.AppManager.backup.dialog.BackupRestoreDialogFragment;
import io.github.muntashirakon.AppManager.compat.ApplicationInfoCompat;
import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.details.AppDetailsActivity;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.profiles.AddToProfileDialogFragment;
import io.github.muntashirakon.AppManager.profiles.ProfileManager;
import io.github.muntashirakon.AppManager.profiles.ProtectedAppsProfile;
import io.github.muntashirakon.AppManager.profiles.struct.AppsProfile;
import io.github.muntashirakon.AppManager.profiles.struct.BaseProfile;
import io.github.muntashirakon.AppManager.self.SelfPermissions;
import io.github.muntashirakon.AppManager.self.imagecache.ImageLoader;
import io.github.muntashirakon.AppManager.settings.FeatureController;
import io.github.muntashirakon.AppManager.settings.Prefs;
import io.github.muntashirakon.AppManager.types.UserPackagePair;
import io.github.muntashirakon.AppManager.users.UserInfo;
import io.github.muntashirakon.AppManager.users.Users;
import io.github.muntashirakon.AppManager.utils.AppNotesManager;
import io.github.muntashirakon.AppManager.utils.ArrayUtils;
import io.github.muntashirakon.AppManager.utils.BroadcastUtils;
import io.github.muntashirakon.AppManager.utils.DateUtils;
import io.github.muntashirakon.AppManager.utils.ForkDialog;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;
import io.github.muntashirakon.AppManager.utils.FreezeUtils;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;
import io.github.muntashirakon.AppManager.utils.appearance.ColorCodes;
import io.github.muntashirakon.dialog.SearchableItemsDialogBuilder;
import io.github.muntashirakon.io.Path;
import io.github.muntashirakon.io.Paths;
import io.github.muntashirakon.util.AccessibilityUtils;
import io.github.muntashirakon.util.AdapterUtils;
import io.github.muntashirakon.widget.MultiSelectionView;

public class MainRecyclerAdapter extends MultiSelectionView.Adapter<ApplicationItem, MainRecyclerAdapter.ViewHolder>
        implements SectionIndexer {
    private static final String TAG = MainRecyclerAdapter.class.getSimpleName();
    private static final String sSections = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private final MainActivity mActivity;
    private String mSearchQuery;

    private final int mColorGreen;
    private final int mColorOrange;
    private final int mColorPrimary;
    private final int mColorSecondary;
    private final int mQueryStringHighlight;
    // Custom-theme palette for the main list. mColorOrange is reused for non-frozen
    // system-app labels (same orange as the existing "SDK 35"/cleartext-traffic
    // highlight), so no new field is needed for that. mColorIceBlue is the unified
    // "frozen" indicator colour for both the snowflake icon and the label text;
    // it replaces the previous grey/greyish-orange split for frozen apps because
    // the outline-vs-filled drawable swap alone was too subtle to read.
    private final int mColorYellow;
    private final int mColorIceBlue;
    // Fork: dark "dormant" films painted as the card background (cool = frozen,
    // mauve = uninstalled). Defaults; overridable via ColorPrefs at runtime.
    private final int mColorFilmFrozen;
    private final int mColorFilmUninstalled;
    // Fork: uninstalled labels are dimmed to ~65% alpha so they read as inactive.
    private static final int UNINSTALLED_LABEL_DIM_ALPHA = 0xA6;
    private final int mLabelFrozenUser;
    private final int mLabelFrozenSystem;

    private static final DiffUtil.ItemCallback<ApplicationItem> DIFF_CALLBACK = new DiffUtil.ItemCallback<ApplicationItem>() {
        @Override
        public boolean areItemsTheSame(@NonNull ApplicationItem oldItem, @NonNull ApplicationItem newItem) {
            return Objects.equals(oldItem.packageName, newItem.packageName);
        }

        @Override
        public boolean areContentsTheSame(@NonNull ApplicationItem oldItem, @NonNull ApplicationItem newItem) {
            return oldItem.getItemVersion() == newItem.getItemVersion();
        }
    };

    // Fork: normal cells are square (edge-to-edge separator grid) with NO
    // stroke at all — the separator lines are the only chrome between apps
    // (the old per-state outlines — running yellow/orange, uninstalled,
    // disabled — stacked against the separators and read as random frames).
    // The selected card's frame is fully owned by the bind from prefs —
    // colour (ColorPrefs.SELECTED_FRAME), border width and corner roundness
    // (SelectionFramePrefs) — thick rounded yellow by default. The prefs are
    // read AT BIND TIME (cheap in-memory map lookups, and only selected cards
    // pay them) so the frame is always fresh — on the tri-fold, settings and
    // the main list can be resumed side by side (multi-window), where the
    // onResume consume-flag refresh never fires. (Never derive the radius
    // from getRadius(): M3 shape resolution needs laid-out bounds and returns
    // 0 before layout — the cause of the square-selection regression in +72.)

    // Resolved per-element text colours (fork). Defaults are the palette above;
    // each may be overridden via ColorPrefs. Reloaded in the constructor and
    // whenever a colour setting changes (reloadColors, called from
    // MainActivity.onResume). For SIGNATURE the original had no explicit colour,
    // so it is only applied when the user has set one (mcSignatureSet).
    private int mcLabelUser, mcLabelSystem, mcLabelFrozen;
    private int mcPackageNormal, mcPackageTrackers;
    private int mcVersionNormal, mcVersionInactive;
    private int mcApptypeNormal, mcApptypePersistent;
    private int mcDateNormal, mcDateReadable;
    private int mcUidNormal, mcUidShared;
    private int mcSdkNormal, mcSdkCleartext;
    private int mcBackup;
    private int mcSignature;
    private boolean mcSignatureSet;
    // Non-text indicators (Stage 2): freeze snowflake, chips, + pill.
    private int mcFreezeFrozen, mcFreezeThawed;
    private int mcChip, mcAddPill;
    // Fork: re-added running/active box strokes (yellow user / orange system)
    // and the dormant-row films (cool = frozen, mauve = uninstalled).
    private int mcStrokeUser, mcStrokeSystem;
    private int mcFilmFrozen, mcFilmUninstalled;

    // package name -> profile names containing it. Loaded asynchronously on
    // adapter creation; until the load finishes the map is empty and bind
    // just renders zero pills for every row, which is the same as an app
    // that is in no profile. Replaced wholesale on each load to keep
    // reads lock-free.
    @NonNull
    private volatile Map<String, List<String>> mPackageToProfileNames = Collections.emptyMap();

    MainRecyclerAdapter(@NonNull MainActivity activity) {
        super(DIFF_CALLBACK);
        mActivity = activity;
        mColorGreen = ContextCompat.getColor(activity, io.github.muntashirakon.ui.R.color.stopped);
        mColorOrange = ContextCompat.getColor(activity, R.color.theme_bright_orange);
        mColorPrimary = ContextCompat.getColor(activity, io.github.muntashirakon.ui.R.color.textColorPrimary);
        mColorSecondary = ContextCompat.getColor(activity, io.github.muntashirakon.ui.R.color.textColorSecondary);
        mQueryStringHighlight = ColorCodes.getQueryStringHighlightColor(activity);
        mColorYellow = ContextCompat.getColor(activity, R.color.theme_bright_yellow);
        mColorIceBlue = ContextCompat.getColor(activity, R.color.theme_ice_blue);
        mColorFilmFrozen = ContextCompat.getColor(activity, R.color.theme_film_frozen);
        mColorFilmUninstalled = ContextCompat.getColor(activity, R.color.theme_film_uninstalled);
        mLabelFrozenUser = ContextCompat.getColor(activity, R.color.theme_label_frozen_user);
        mLabelFrozenSystem = ContextCompat.getColor(activity, R.color.theme_label_frozen_system);
        reloadColors();
        ThreadUtils.postOnBackgroundThread(this::loadProfileMembership);
    }

    /**
     * Rebuild the package→profile-names map from disk and re-render. Safe to
     * call after profile membership changes (e.g. adding an app to a profile)
     * or on a list refresh.
     */
    public void reloadProfileMembership() {
        ThreadUtils.postOnBackgroundThread(this::loadProfileMembership);
    }

    /**
     * Reads every profile JSON from disk, builds a package-name to
     * profile-names map, then swaps it in and re-renders the list. Called
     * once on adapter construction; the list is small (handful of profiles)
     * so we do not bother with incremental updates.
     */
    @WorkerThread
    private void loadProfileMembership() {
        Map<String, List<String>> map = new HashMap<>();
        try {
            for (BaseProfile profile : ProfileManager.getProfiles()) {
                if (profile instanceof AppsProfile) {
                    for (String pkg : ((AppsProfile) profile).packages) {
                        map.computeIfAbsent(pkg, k -> new ArrayList<>()).add(profile.name);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load profile membership", e);
        }
        mPackageToProfileNames = map;
        ThreadUtils.postOnMainThread(this::notifyDataSetChanged);
    }

    /**
     * Removes a single package from a named AppsProfile, persists the
     * modified profile JSON to disk, then reloads the in-memory
     * package-to-profiles map so the corresponding row's pills update.
     * Used by the long-press handler on a profile pill (see the bind block).
     * Runs entirely on a background thread; surfaces success/failure as a
     * short toast on the main thread.
     */
    private void removePackageFromProfile(@NonNull String packageName, @NonNull String profileName) {
        ThreadUtils.postOnBackgroundThread(() -> {
            boolean success = false;
            try {
                String profileId = ProfileManager.getProfileIdCompat(profileName);
                Path profilePath = ProfileManager.findProfilePathById(profileId);
                if (profilePath != null) {
                    BaseProfile baseProfile = BaseProfile.fromPath(profilePath);
                    if (baseProfile instanceof AppsProfile) {
                        AppsProfile profile = (AppsProfile) baseProfile;
                        List<String> remaining = new ArrayList<>(Arrays.asList(profile.packages));
                        if (remaining.remove(packageName)) {
                            profile.packages = remaining.toArray(new String[0]);
                            try (OutputStream os = profilePath.openOutputStream()) {
                                profile.write(os);
                                success = true;
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                Log.e(TAG, "Failed to remove " + packageName + " from profile " + profileName, e);
            }
            final boolean ok = success;
            if (ok) {
                // Already on a background thread; loadProfileMembership posts
                // its notifyDataSetChanged back to the main thread itself.
                loadProfileMembership();
            }
            ThreadUtils.postOnMainThread(() ->
                    displayShortToast(ok ? R.string.done : R.string.failed));
        });
    }

    /**
     * Fork: show the per-app note dialog (shared builder lives in
     * {@link AppNotesManager#showNoteDialog}). On save, the edited row is
     * refreshed via notifyItemChanged using the holder's <em>current</em>
     * binding position (re-read at save time, never the stale bind position) so
     * it stays correct under RecyclerView recycling.
     */
    private void showNoteDialog(@NonNull ViewHolder holder, @NonNull String packageName,
                                @NonNull CharSequence appLabel) {
        AppNotesManager.showNoteDialog(mActivity, packageName, appLabel, () -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                notifyItemChanged(pos);
            }
        });
    }

    @UiThread
    void setDefaultList(List<ApplicationItem> list) {
        if (mActivity.viewModel == null) return;
        String oldSearchQuery = mSearchQuery;
        mSearchQuery = mActivity.viewModel.getSearchQuery();
        submitListWithScrollState(
                list != null ? new ArrayList<>(list) : null,
                AdapterUtils.isStartingSearch(oldSearchQuery, mSearchQuery),
                AdapterUtils.isClearingSearch(oldSearchQuery, mSearchQuery)
        );
        if (!Objects.equals(oldSearchQuery, mSearchQuery)) {
            notifyItemRangeChanged(0, getItemCount(), PAYLOAD_HIGHLIGHT_CHANGED);
        }
        notifySelectionChange();
    }

    /**
     * (Re)load the per-element colour cache from {@link ColorPrefs}, defaulting
     * to the fork palette. Call after a colour setting changes so the next bind
     * paints the new colours (see {@link MainActivity#onResume}).
     */
    public void reloadColors() {
        mcLabelUser = ColorPrefs.getColor(mActivity, ColorPrefs.LABEL_USER, mColorYellow);
        mcLabelSystem = ColorPrefs.getColor(mActivity, ColorPrefs.LABEL_SYSTEM, mColorOrange);
        mcLabelFrozen = ColorPrefs.getColor(mActivity, ColorPrefs.LABEL_FROZEN, mColorIceBlue);
        mcPackageNormal = ColorPrefs.getColor(mActivity, ColorPrefs.PACKAGE_NORMAL, mColorYellow);
        mcPackageTrackers = ColorPrefs.getColor(mActivity, ColorPrefs.PACKAGE_TRACKERS, mColorOrange);
        mcVersionNormal = ColorPrefs.getColor(mActivity, ColorPrefs.VERSION_NORMAL, mColorSecondary);
        mcVersionInactive = ColorPrefs.getColor(mActivity, ColorPrefs.VERSION_INACTIVE, mColorGreen);
        mcApptypeNormal = ColorPrefs.getColor(mActivity, ColorPrefs.APPTYPE_NORMAL, mColorSecondary);
        mcApptypePersistent = ColorPrefs.getColor(mActivity, ColorPrefs.APPTYPE_PERSISTENT, Color.MAGENTA);
        mcDateNormal = ColorPrefs.getColor(mActivity, ColorPrefs.DATE_NORMAL, mColorSecondary);
        mcDateReadable = ColorPrefs.getColor(mActivity, ColorPrefs.DATE_READABLE, mColorOrange);
        mcUidNormal = ColorPrefs.getColor(mActivity, ColorPrefs.UID_NORMAL, mColorSecondary);
        mcUidShared = ColorPrefs.getColor(mActivity, ColorPrefs.UID_SHARED, mColorOrange);
        mcSdkNormal = ColorPrefs.getColor(mActivity, ColorPrefs.SDK_NORMAL, mColorSecondary);
        mcSdkCleartext = ColorPrefs.getColor(mActivity, ColorPrefs.SDK_CLEARTEXT, mColorOrange);
        mcBackup = ColorPrefs.getColor(mActivity, ColorPrefs.BACKUP, mColorYellow);
        mcSignatureSet = ColorPrefs.isSet(mActivity, ColorPrefs.SIGNATURE);
        mcSignature = ColorPrefs.getColor(mActivity, ColorPrefs.SIGNATURE, mColorSecondary);
        mcFreezeFrozen = ColorPrefs.getColor(mActivity, ColorPrefs.FREEZE_FROZEN, mColorIceBlue);
        mcFreezeThawed = ColorPrefs.getColor(mActivity, ColorPrefs.FREEZE_THAWED, mColorYellow);
        mcChip = ColorPrefs.getColor(mActivity, ColorPrefs.CHIP, mColorYellow);
        mcAddPill = ColorPrefs.getColor(mActivity, ColorPrefs.ADDPILL, mColorYellow);
        mcStrokeUser = ColorPrefs.getColor(mActivity, ColorPrefs.STROKE_USER, mColorYellow);
        mcStrokeSystem = ColorPrefs.getColor(mActivity, ColorPrefs.STROKE_SYSTEM, mColorOrange);
        mcFilmFrozen = ColorPrefs.getColor(mActivity, ColorPrefs.FILM_FROZEN, mColorFilmFrozen);
        mcFilmUninstalled = ColorPrefs.getColor(mActivity, ColorPrefs.FILM_UNINSTALLED, mColorFilmUninstalled);
        // (The selected card's frame is deliberately NOT cached here — it is
        // read at bind time so it stays fresh in multi-window, where the
        // onResume flag consumption never runs.)
    }

    /**
     * Snapshot of the package names currently shown in the list, in display
     * order (i.e. after search + all active filters). Used by the toolbar
     * "copy displayed app IDs" action. Reads the ListAdapter's current
     * (filtered) list via getItem/getItemCount.
     */
    @NonNull
    public List<String> getDisplayedPackageNames() {
        int count = getItemCount();
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ApplicationItem item = getItem(i);
            if (item != null) out.add(item.packageName);
        }
        return out;
    }

    @Override
    public void cancelSelection() {
        super.cancelSelection();
        if (mActivity.viewModel != null) {
            mActivity.viewModel.cancelSelection();
        }
    }

    @Override
    public int getSelectedItemCount() {
        if (mActivity.viewModel == null) return 0;
        return mActivity.viewModel.getSelectedPackages().size();
    }

    @Override
    protected int getTotalItemCount() {
        if (mActivity.viewModel == null) return 0;
        return mActivity.viewModel.getApplicationItemCount();
    }

    @Override
    protected boolean isSelected(int position) {
        return getItem(position).isSelected;
    }

    @Override
    protected boolean select(int position) {
        mActivity.viewModel.select(getItem(position));
        return true;
    }

    @Override
    protected boolean deselect(int position) {
        mActivity.viewModel.deselect(getItem(position));
        return true;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        final View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_main, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty()) {
            for (Object payload : payloads) {
                if (Objects.equals(payload, PAYLOAD_HIGHLIGHT_CHANGED)) {
                    updateTextHighlights(holder, getItem(position));
                }
            }
        }
        // Handle other stuff
        super.onBindViewHolder(holder, position, payloads);
    }

    private void updateTextHighlights(@NonNull ViewHolder holder, @NonNull ApplicationItem item) {
        String query = mSearchQuery;
        holder.label.setText(UIUtils.getHighlightedText(item.label, query, mQueryStringHighlight));
        holder.packageName.setText(UIUtils.getHighlightedText(item.packageName, query, mQueryStringHighlight));
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final ApplicationItem item = getItem(position);
        MaterialCardView cardView = holder.itemView;
        Context context = cardView.getContext();
        // Add click listeners
        cardView.setOnClickListener(v -> {
            int currentPos = holder.getBindingAdapterPosition();
            if (currentPos == RecyclerView.NO_POSITION) return;
            // If selection mode is on, select/deselect the current item instead of the default behaviour
            if (isInSelectionMode()) {
                toggleSelection(currentPos);
                AccessibilityUtils.requestAccessibilityFocus(holder.itemView);
                return;
            }
            handleClick(item);
        });
        cardView.setOnLongClickListener(v -> {
            int currentPos = holder.getBindingAdapterPosition();
            if (currentPos == RecyclerView.NO_POSITION) return false;
            // Long click listener: Select/deselect an app.
            // 1) Turn selection mode on if this is the first item in the selection list
            // 2) Select between last selection position and this position (inclusive) if selection mode is on
            ApplicationItem lastSelectedItem = mActivity.viewModel.getLastSelectedPackage();
            int lastSelectedItemPosition = lastSelectedItem == null ? -1 : indexOf(lastSelectedItem);
            if (lastSelectedItemPosition >= 0) {
                // Select from last selection to this selection
                selectRange(lastSelectedItemPosition, currentPos);
            } else {
                toggleSelection(currentPos);
                AccessibilityUtils.requestAccessibilityFocus(holder.itemView);
            }
            return true;
        });
        // Fork: dormant-row film behind ALL content. The card background is
        // normally black; uninstalled rows get a faint mauve tint and frozen
        // rows a faint cool tint, so dormant apps read at a glance even before
        // you parse the label. Painted as the card background (not a foreground
        // overlay) so the label text on top is never washed. Uninstalled takes
        // priority over frozen. Set unconditionally — the ViewHolder recycles.
        int filmColor;
        if (!item.isInstalled) {
            filmColor = mcFilmUninstalled;
        } else if (item.isFrozen) {
            filmColor = mcFilmFrozen;
        } else {
            filmColor = Color.BLACK;
        }
        cardView.setCardBackgroundColor(filmColor);
        // Fork: square cells for the edge-to-edge separator grid; the selected
        // card gets the configurable frame (thick rounded yellow by default).
        float density = context.getResources().getDisplayMetrics().density;
        if (isSelected(position)) {
            float frameWidthDp = SelectionFramePrefs.getWidthDp(context);
            cardView.setRadius(SelectionFramePrefs.getRadiusDp(context) * density);
            cardView.setStrokeWidth(frameWidthDp <= 0f ? 0 : Math.max(1, Math.round(frameWidthDp * density)));
            cardView.setStrokeColor(ColorPrefs.getColor(context, ColorPrefs.SELECTED_FRAME, mColorYellow));
        } else {
            // Fork: active apps (installed, not frozen, not force-stopped) get a
            // box matching their label colour — yellow (user) / orange (system).
            // Frozen/uninstalled rows use their films; force-stopped rows get no
            // box. Width AND corner roundness are read at bind time (like the
            // selection frame) from RunningBoxPrefs; non-box cells stay square
            // (radius 0) to keep the edge-to-edge separator grid straight.
            if (item.isInstalled && !item.isFrozen && !item.isStopped) {
                float boxDp = RunningBoxPrefs.getWidthDp(context);
                cardView.setRadius(RunningBoxPrefs.getRadiusDp(context) * density);
                cardView.setStrokeWidth(boxDp <= 0f ? 0 : Math.max(1, Math.round(boxDp * density)));
                cardView.setStrokeColor(item.isUser ? mcStrokeUser : mcStrokeSystem);
            } else {
                cardView.setRadius(0f);
                cardView.setStrokeWidth(0);
            }
        }
        // Fork: force-stop ✕ in the row under the icon, to the right of the
        // freeze snowflake. Shown only for running apps (installed, not frozen,
        // not stopped — the same condition as the running box) and never for
        // our own package; a single tap force-stops the app with no
        // confirmation. Rendered as a plain yellow cross, sized to match the
        // snowflake (see item_main.xml).
        boolean running = item.isInstalled && !item.isFrozen && !item.isStopped
                && !BuildConfig.APPLICATION_ID.equals(item.packageName);
        if (running) {
            holder.killBadge.setColorFilter(mColorYellow);
            holder.killBadge.setVisibility(View.VISIBLE);
            holder.killBadge.setOnClickListener(v -> forceStopApp(item));
        } else {
            holder.killBadge.setVisibility(View.GONE);
            holder.killBadge.setOnClickListener(null);
        }
        // Display yellow star if the app is in debug mode
        holder.debugIcon.setVisibility(item.debuggable ? View.VISIBLE : View.INVISIBLE);
        // Set date and (if available,) days between first installation and last update
        String lastUpdateDate = DateUtils.formatDate(context, item.lastUpdateTime);
        if (item.firstInstallTime == item.lastUpdateTime) {
            holder.date.setText(lastUpdateDate);
        } else {
            long days = item.diffInstallUpdateInDays;
            SpannableString ssDate = new SpannableString(context.getResources()
                    .getQuantityString(R.plurals.main_list_date_days, (int) days, lastUpdateDate, days));
            ssDate.setSpan(new RelativeSizeSpan(.8f), lastUpdateDate.length(),
                    ssDate.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            holder.date.setText(ssDate);
        }
        // Set date color to orange if app can read logs (and accepted)
        holder.date.setTextColor(item.canReadLogs ? mcDateReadable : mcDateNormal);
        FontUtil.apply(holder.date, FontPrefs.INSTALL_DATE);
        if (item.isInstalled) {
            // Set UID
            if (item.uidOrAppIds != null) {
                holder.userId.setText(item.uidOrAppIds);
            }
            // Set UID text color to orange if the package is shared
            holder.userId.setTextColor(item.sharedUserId != null ? mcUidShared : mcUidNormal);
        } else holder.userId.setText("");
        FontUtil.apply(holder.userId, FontPrefs.UID);
        if (item.sha != null) {
            // Set signature type (right column)
            holder.sha.setVisibility(View.VISIBLE);
            holder.sha.setText(item.sha.second);
        } else {
            holder.sha.setVisibility(View.GONE);
        }
        FontUtil.apply(holder.sha, FontPrefs.SIGNATURE);
        // Signature had no explicit colour originally; only override if set.
        if (mcSignatureSet) holder.sha.setTextColor(mcSignature);
        // Fork: configurable main-list icon size. Size the icon and widen the
        // icon column to match, and scale the snowflake + ✕ glyphs under it
        // proportionally (so the pair always fits with a gap). Read at bind
        // time; a change triggers a re-bind via MainIconPrefs.consumeChanged().
        applyMainIconSize(holder);
        // Load app icon
        // Fork: version-aware cache key — fold lastUpdateTime in so a reinstall (which bumps
        // lastUpdateTime) busts the stale in-memory + on-disk icon cache instead of showing
        // the old icon. The tag set here must match the one passed to displayImage(), since
        // ImageLoader's recycled-view guard compares them before binding the bitmap.
        String iconTag = ImageLoader.versionedTag(item.packageName, item.lastUpdateTime);
        holder.icon.setTag(iconTag);
        ImageLoader.getInstance().displayImage(iconTag, item, holder.icon);
        // Frozen apps: dim the icon, show a snowflake under it, italicize the label.
        // item.isFrozen covers PM-disabled, suspended, and hidden mechanisms. It is
        // populated from a live PackageManager query at list-load (see PackageUtils
        // .getInstalledOrBackedUpApplicationsFromDb) so DB-cache staleness doesn't
        // produce false negatives. ViewHolder is recycled, so every state below MUST
        // be set in both branches.
        holder.icon.setAlpha(item.isFrozen ? 0.5f : 1.0f);
        // Freeze indicator: always visible. Swap drawable between outline (not
        // frozen) and filled (frozen) AND swap the tint between yellow (not
        // frozen) and ice blue (frozen) so the state reads at a glance. The
        // drawable swap alone was too subtle on its own — Material Symbols
        // ac_unit looks similar in filled and outlined form — so the colour
        // change carries most of the signal.
        holder.freezeIndicator.setImageResource(item.isFrozen
                ? R.drawable.ic_snowflake_24dp
                : R.drawable.ic_snowflake_outline_24dp);
        holder.freezeIndicator.setImageTintList(ColorStateList.valueOf(
                item.isFrozen ? mcFreezeFrozen : mcFreezeThawed));
        // Make the whole left icon column a tap target to toggle freeze, but
        // ONLY for eligible apps: anything that is not AppManager itself.
        // Our own package keeps the column non-clickable so taps fall
        // through to the parent card's click handler (which opens app
        // details or toggles selection). System apps ARE allowed - the
        // earlier conservative rule that excluded them was relaxed at the
        // user's request. Long-clicks on the column always bubble up to the
        // card's long-click handler, so selection-via-long-press on the
        // icon area continues to work in both branches. (ViewHolder
        // recycling demands both branches set both properties.)
        if (!BuildConfig.APPLICATION_ID.equals(item.packageName)) {
            holder.iconColumn.setClickable(true);
            holder.iconColumn.setOnClickListener(v -> toggleFreeze(item));
        } else {
            holder.iconColumn.setOnClickListener(null);
            holder.iconColumn.setClickable(false);
        }
        // Fork: italic marks BOTH frozen and uninstalled (dormant) rows.
        boolean dormantItalic = item.isFrozen || !item.isInstalled;
        holder.label.setTypeface(null, dormantItalic ? Typeface.ITALIC : Typeface.NORMAL);
        // Set app label
        if (!TextUtils.isEmpty(mSearchQuery) && item.label.toLowerCase(Locale.ROOT).contains(mSearchQuery)) {
            // Highlight searched query
            holder.label.setText(UIUtils.getHighlightedText(item.label, mSearchQuery, mQueryStringHighlight));
        } else holder.label.setText(item.label);
        // Set app label color (custom theme). The label hue now encodes app
        // *type* — user = yellow, system = orange — NOT freeze state. Freeze is
        // shown by the snowflake + cool film, so frozen user vs system apps stay
        // distinguishable (the old ice-blue override collapsed them into one
        // colour). mcLabelFrozen / LABEL_FROZEN are retained so settings
        // export keeps the key, but they're no longer applied to the label.
        // Uninstalled rows keep their type colour but DIMMED to ~65% — an
        // "inactive" cue stacking with the italic + the mauve film.
        int labelColor = item.isUser ? mcLabelUser : mcLabelSystem;
        if (!item.isInstalled) {
            labelColor = ColorUtils.setAlphaComponent(labelColor, UNINSTALLED_LABEL_DIM_ALPHA);
        }
        holder.label.setTextColor(labelColor);
        // Custom per-element font (shiroikuma fork). Applied after the
        // default typeface so a chosen family/weight/size wins; the frozen
        // italic is then re-derived on top of whatever typeface is now set
        // (when the category inherits, FontUtil.apply is a no-op and this
        // just re-applies the same italic/normal as before).
        FontUtil.apply(holder.label, FontPrefs.LABEL);
        holder.label.setTypeface(holder.label.getTypeface(), dormantItalic ? Typeface.ITALIC : Typeface.NORMAL);
        // Fork: per-app note affordance. Mutually exclusive per row —
        //   has a note -> note glyph as a compound drawableEnd hugging the app
        //                 name (tap the glyph to view/edit); top-right "+" hidden
        //   no note    -> top-right "+" to create a note; no glyph
        // The glyph is a compound drawable rather than a sibling view so it hugs
        // the label text (a weighted sibling gets pushed to the far edge of the
        // row, next to the "+" slot) and stays visible when the name ellipsizes.
        // Only the glyph's hit region opens the dialog; every other touch on the
        // label falls through to the card (open details / select). ViewHolder is
        // recycled, so BOTH branches set the label drawable + touch listener AND
        // the "+" visibility + click listener. Tint tracks the fork theme colour.
        final String notePkg = item.packageName;
        final CharSequence noteLabel = item.label;
        int noteTint = ForkThemeUtils.getTextColor();
        if (AppNotesManager.hasNote(context, notePkg)) {
            int sz = Math.round(18 * context.getResources().getDisplayMetrics().density);
            Drawable noteGlyph = ContextCompat.getDrawable(context, R.drawable.ic_note_24dp);
            if (noteGlyph != null) {
                noteGlyph = noteGlyph.mutate();
                noteGlyph.setBounds(0, 0, sz, sz);
                noteGlyph.setTintList(ColorStateList.valueOf(noteTint));
            }
            // Gap between the app name and the glyph: 0.7× the glyph width.
            holder.label.setCompoundDrawablePadding(Math.round(sz * 0.7f));
            holder.label.setCompoundDrawablesRelative(null, null, noteGlyph, null);
            holder.label.setOnTouchListener((v, event) -> {
                Drawable end = holder.label.getCompoundDrawablesRelative()[2];
                if (end == null) return false;
                boolean inBadge = holder.label.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                        ? event.getX() <= holder.label.getCompoundPaddingLeft()
                        : event.getX() >= holder.label.getWidth() - holder.label.getCompoundPaddingRight();
                if (!inBadge) return false;
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    v.performClick();
                    showNoteDialog(holder, notePkg, noteLabel);
                }
                return true;
            });
            holder.noteAdd.setVisibility(View.GONE);
            holder.noteAdd.setOnClickListener(null);
        } else {
            holder.label.setCompoundDrawablesRelative(null, null, null, null);
            holder.label.setOnTouchListener(null);
            holder.noteAdd.setImageTintList(ColorStateList.valueOf(noteTint));
            holder.noteAdd.setVisibility(View.VISIBLE);
            holder.noteAdd.setOnClickListener(v -> showNoteDialog(holder, notePkg, noteLabel));
        }
        // Set package name
        if (!TextUtils.isEmpty(mSearchQuery) && item.packageName.toLowerCase(Locale.ROOT).contains(mSearchQuery)) {
            // Highlight searched query
            holder.packageName.setText(UIUtils.getHighlightedText(item.packageName, mSearchQuery, mQueryStringHighlight));
        } else holder.packageName.setText(item.packageName);
        // Set package name (app ID) colour: bright orange if the app has
        // known tracker components (same orange as system-app labels), else
        // yellow.
        if (item.trackerCount > 0) {
            holder.packageName.setTextColor(mcPackageTrackers);
        } else holder.packageName.setTextColor(mcPackageNormal);
        FontUtil.apply(holder.packageName, FontPrefs.PACKAGE);
        // Populate profile-membership pills (these sit where the cert issuer
        // and backup info text used to live). Each pill is a Chip styled as
        // yellow text inside a yellow hairline-stroked transparent oval —
        // same visual language as the search bar and the installer
        // master-toggle banner.
        //
        // Interaction model on this row (per fork spec):
        //   - tap a profile pill        -> filter the main list to apps in that profile
        //   - long-press a profile pill -> remove this app from that profile (saves on disk)
        //   - tap the "+" pill          -> open the add-to-profile dialog for this app
        //   - long-press the "+" pill   -> clear an active profile filter, if any
        // The "+" pill is a separate Chip declared in item_main.xml, sitting
        // outside the ChipGroup so it can be right-justified by the wrapping
        // LinearLayout (ChipGroup has weight=1, "+" sits at the right edge).
        // It guarantees a tappable surface for the add affordance even when
        // an app has no profile memberships. The ChipGroup's own empty-space
        // click/long-click handlers below are kept as a defensive backup —
        // they fire only when the user lands between or beyond pills, since
        // each Chip consumes its own touch area.
        holder.profilePills.removeAllViews();
        ColorStateList chipStrokeList = ColorStateList.valueOf(mcChip);
        ColorStateList addPillStrokeList = ColorStateList.valueOf(mcAddPill);
        ColorStateList transparentList = ColorStateList.valueOf(Color.TRANSPARENT);
        final String pkgForRow = item.packageName;
        // Profile-membership pills.
        List<String> profileNames = mPackageToProfileNames.get(item.packageName);
        if (profileNames != null) {
            for (String name : profileNames) {
                Chip chip = new Chip(context);
                chip.setText(name);
                chip.setTextColor(mcChip);
                chip.setChipBackgroundColor(transparentList);
                chip.setChipStrokeColor(chipStrokeList);
                chip.setChipStrokeWidth(2f);
                chip.setChipIconVisible(false);
                chip.setCloseIconVisible(false);
                chip.setCheckable(false);
                chip.setClickable(true);
                chip.setFocusable(true);
                final String profileName = name;
                chip.setOnClickListener(v -> {
                    if (mActivity.viewModel == null) return;
                    // Fork: clicking a pill narrows *within* the current view rather
                    // than replacing the whole filter — add this profile to the
                    // include set so the result is the intersection of every active
                    // profile filter ("filter within this selection for this
                    // profile"). With no filter active this behaves exactly like
                    // filtering for just this profile.
                    Set<String> include = new LinkedHashSet<>(mActivity.viewModel.getProfileFiltersInclude());
                    Set<String> exclude = new LinkedHashSet<>(mActivity.viewModel.getProfileFiltersExclude());
                    // A pill only ever appears on apps that belong to the profile, so
                    // the profile can't sensibly remain in the exclude set; drop it
                    // there before including it.
                    exclude.remove(profileName);
                    include.add(profileName);
                    mActivity.viewModel.setProfileFilters(include, exclude);
                });
                chip.setOnLongClickListener(v -> {
                    removePackageFromProfile(pkgForRow, profileName);
                    return true;
                });
                holder.profilePills.addView(chip);
            }
        }
        // The XML-declared "+" pill — always shown, right-justified by the
        // parent LinearLayout's weight distribution. Style applied here
        // because the chip is the same shape across all rows.
        holder.addPill.setText("+");
        holder.addPill.setTextColor(mcAddPill);
        holder.addPill.setChipBackgroundColor(transparentList);
        holder.addPill.setChipStrokeColor(addPillStrokeList);
        holder.addPill.setChipStrokeWidth(2f);
        holder.addPill.setChipIconVisible(false);
        holder.addPill.setCloseIconVisible(false);
        holder.addPill.setCheckable(false);
        holder.addPill.setClickable(true);
        holder.addPill.setFocusable(true);
        holder.addPill.setOnClickListener(v -> {
            AddToProfileDialogFragment dialog = AddToProfileDialogFragment.getInstance(
                    new String[]{pkgForRow});
            dialog.show(mActivity.getSupportFragmentManager(), AddToProfileDialogFragment.TAG);
        });
        holder.addPill.setOnLongClickListener(v -> {
            if (mActivity.viewModel == null) return false;
            if (mActivity.viewModel.getFilterProfileName() != null) {
                mActivity.viewModel.setFilterProfileName(null);
                return true;
            }
            return false;
        });
        // Empty-space handlers on the pill row itself. Defensive backup
        // for taps that land between or beyond profile pills.
        holder.profilePills.setClickable(true);
        holder.profilePills.setLongClickable(true);
        holder.profilePills.setOnClickListener(v -> {
            AddToProfileDialogFragment dialog = AddToProfileDialogFragment.getInstance(
                    new String[]{pkgForRow});
            dialog.show(mActivity.getSupportFragmentManager(), AddToProfileDialogFragment.TAG);
        });
        holder.profilePills.setOnLongClickListener(v -> {
            if (mActivity.viewModel == null) return false;
            if (mActivity.viewModel.getFilterProfileName() != null) {
                mActivity.viewModel.setFilterProfileName(null);
                return true;
            }
            return false;
        });
        // Set version (along with HW accelerated, debug and test only flags)
        holder.version.setText(item.versionTag);
        // Set version color to dark cyan if the app is inactive
        holder.version.setTextColor(item.isAppInactive ? mcVersionInactive : mcVersionNormal);
        FontUtil.apply(holder.version, FontPrefs.VERSION);
        // Set app type: system or user app (along with large heap, suspended, multi-arch,
        // has code, vm safe mode)
        if (item.isInstalled) {
            String isSystemApp = context.getString(item.isSystem ? R.string.system : R.string.user) + item.appTypePostfix;
            holder.isSystemApp.setText(isSystemApp);
        } else {
            holder.isSystemApp.setText("-");
        }
        // Set app type text color to magenta if the app is persistent
        holder.isSystemApp.setTextColor(item.isPersistent ? mcApptypePersistent : mcApptypeNormal);
        FontUtil.apply(holder.isSystemApp, FontPrefs.APP_TYPE);
        // Set SDK
        if (item.sdkString != null) {
            holder.size.setText(item.sdkString);
        } else {
            holder.size.setText("-");
        }
        // Set SDK color to orange if the app is using cleartext (e.g. HTTP) traffic
        holder.size.setTextColor(item.usesCleartextTraffic ? mcSdkCleartext : mcSdkNormal);
        FontUtil.apply(holder.size, FontPrefs.SDK);
        // Backup indicator on the LEFT (under the icon) is suppressed in
        // this fork - we surface backup presence and details on the right
        // column instead (version + date + time, in yellow, three lines
        // each paired horizontally with the existing right-column row).
        holder.backupIndicator.setVisibility(View.GONE);
        if (item.backup != null) {
            // Three-line backup summary on the right, in yellow. Date and
            // time use Locale.ROOT so the format is fixed yyyy-MM-dd /
            // 24-hour HH:mm:ss regardless of the device locale - per spec.
            java.text.SimpleDateFormat dateFmt =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT);
            java.text.SimpleDateFormat timeFmt =
                    new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT);
            java.util.Date when = new java.util.Date(item.backup.backupTime);
            holder.backupVersion.setVisibility(View.VISIBLE);
            holder.backupVersion.setAlpha(1f);
            holder.backupVersion.setText(item.backup.versionName);
            holder.backupVersion.setTextColor(mcBackup);
            holder.backupDate.setVisibility(View.VISIBLE);
            holder.backupDate.setText(dateFmt.format(when));
            holder.backupDate.setTextColor(mcBackup);
            holder.backupTime.setVisibility(View.VISIBLE);
            holder.backupTime.setText(timeFmt.format(when));
            holder.backupTime.setTextColor(mcBackup);
            FontUtil.apply(holder.backupVersion, FontPrefs.BACKUP_INFO);
            FontUtil.apply(holder.backupDate, FontPrefs.BACKUP_INFO);
            FontUtil.apply(holder.backupTime, FontPrefs.BACKUP_INFO);
            // Tapping any of the three backup lines opens the
            // backup/restore dialog for this single app, from which the
            // user can start a fresh backup, restore, or delete the
            // existing one. Listener captures `item` by reference, which
            // is fine because we re-bind per onBindViewHolder call.
            View.OnClickListener backupTap =
                    v -> showBackupRestoreDialogOrAppNotInstalled(item);
            holder.backupVersion.setOnClickListener(backupTap);
            holder.backupDate.setOnClickListener(backupTap);
            holder.backupTime.setOnClickListener(backupTap);
            // Long-press on any of the three backup lines opens the same
            // dialog restricted to RESTORE + DELETE modes, so it lands
            // directly on the existing-backup management view (the
            // "Restore..." dialog with Delete / Restore buttons) instead
            // of the new-backup mode the short tap defaults to.
            View.OnLongClickListener backupLongTap = v -> {
                if (item.backup == null) return false;
                BackupRestoreDialogFragment frag = BackupRestoreDialogFragment.getInstance(
                        Collections.singletonList(new UserPackagePair(
                                item.packageName, UserHandleHidden.myUserId())),
                        BackupRestoreDialogFragment.MODE_RESTORE
                                | BackupRestoreDialogFragment.MODE_DELETE);
                frag.setOnActionBeginListener(mode -> mActivity.showProgressIndicator(true));
                frag.setOnActionCompleteListener(
                        (mode, failedPackages) -> mActivity.showProgressIndicator(false));
                frag.show(mActivity.getSupportFragmentManager(),
                        BackupRestoreDialogFragment.TAG);
                return true;
            };
            holder.backupVersion.setOnLongClickListener(backupLongTap);
            holder.backupDate.setOnLongClickListener(backupLongTap);
            holder.backupTime.setOnLongClickListener(backupLongTap);
        } else if (item.isInstalled) {
            // Fork: no backup yet, but the app is installed — surface a
            // tappable "Back up" affordance in the same right-column area.
            // Tapping opens the backup dialog in MODE_BACKUP, which, when
            // "Skip backup method dialog" is enabled, starts the backup
            // immediately with the default options.
            holder.backupVersion.setVisibility(View.VISIBLE);
            holder.backupVersion.setAlpha(0.6f);
            holder.backupVersion.setText(R.string.backup_tap_hint);
            holder.backupVersion.setTextColor(mcBackup);
            FontUtil.apply(holder.backupVersion, FontPrefs.BACKUP_INFO);
            // Keep the date/time cells visible-but-empty so the tap target
            // spans the whole right-hand backup column (all three rows),
            // not just the one-line "Back up" text - much easier to hit.
            // Empty text renders nothing but the cells still occupy their
            // row height and receive clicks.
            holder.backupDate.setVisibility(View.VISIBLE);
            holder.backupDate.setText("");
            holder.backupTime.setVisibility(View.VISIBLE);
            holder.backupTime.setText("");
            View.OnClickListener startBackupTap = v -> openBackupModeDialog(item);
            holder.backupVersion.setOnClickListener(startBackupTap);
            holder.backupDate.setOnClickListener(startBackupTap);
            holder.backupTime.setOnClickListener(startBackupTap);
            holder.backupVersion.setOnLongClickListener(null);
            holder.backupDate.setOnLongClickListener(null);
            holder.backupTime.setOnLongClickListener(null);
        } else {
            // No backup and not installed: nothing to show or tap.
            holder.backupVersion.setVisibility(View.GONE);
            holder.backupVersion.setAlpha(1f);
            holder.backupDate.setVisibility(View.GONE);
            holder.backupTime.setVisibility(View.GONE);
            // Clear listeners so a recycled ViewHolder doesn't keep a
            // reference to a stale ApplicationItem captured by a previous
            // bind. (The GONE views can't be tapped anyway, but the
            // captured reference would still keep the old item alive.)
            holder.backupVersion.setOnClickListener(null);
            holder.backupDate.setOnClickListener(null);
            holder.backupTime.setOnClickListener(null);
            holder.backupVersion.setOnLongClickListener(null);
            holder.backupDate.setOnLongClickListener(null);
            holder.backupTime.setOnLongClickListener(null);
        }
        super.onBindViewHolder(holder, position);
    }

    @Override
    public long getItemId(int position) {
        return getItem(position).hashCode();
    }

    @Override
    public int getPositionForSection(int section) {
        List<ApplicationItem> currentList = getCurrentList();
        for (int i = 0; i < currentList.size(); i++) {
            String item = currentList.get(i).label;
            if (!item.isEmpty()) {
                if (item.charAt(0) == sSections.charAt(section)) {
                    return i;
                }
            }
        }
        return 0;
    }

    @Override
    public int getSectionForPosition(int position) {
        return 0;
    }

    @Override
    public Object[] getSections() {
        String[] sectionsArr = new String[sSections.length()];
        for (int i = 0; i < sSections.length(); i++) {
            sectionsArr[i] = String.valueOf(sSections.charAt(i));
        }
        return sectionsArr;
    }

    private int indexOf(@Nullable ApplicationItem item) {
        if (item == null) {
            return -1;
        }
        List<ApplicationItem> list = getCurrentList();
        for (int i = 0; i < list.size(); ++i) {
            if (Objects.equals(list.get(i), item)) {
                return i;
            }
        }
        return -1;
    }

    private void handleClick(@NonNull ApplicationItem item) {
        if (!item.isInstalled || item.userIds.length == 0) {
            // The app should not be installed. But make sure this is really true. (For current user only)
            ApplicationInfo info;
            try {
                info = PackageManagerCompat.getApplicationInfo(item.packageName, MATCH_UNINSTALLED_PACKAGES
                                | PackageManagerCompat.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES,
                        UserHandleHidden.myUserId());
            } catch (RemoteException | PackageManager.NameNotFoundException e) {
                showBackupRestoreDialogOrAppNotInstalled(item);
                return;
            }
            // 1. Check if the app was really uninstalled.
            if (ApplicationInfoCompat.isInstalled(info)) {
                // The app is already installed, and we were wrong to assume that it was installed.
                // Update data before opening it.
                item.isInstalled = true;
                item.isOnlyDataInstalled = false;
                item.userIds = new int[]{UserHandleHidden.myUserId()};
                Intent intent = AppDetailsActivity.getIntent(mActivity, item.packageName, UserHandleHidden.myUserId());
                mActivity.startActivity(intent);
                return;
            }
            // 2. If the app can be installed, offer it to install again.
            if (FeatureController.isInstallerEnabled()) {
                if (ApplicationInfoCompat.isSystemApp(info) && SelfPermissions.canInstallExistingPackages()) {
                    // Install existing app instead of installing as an update
                    mActivity.startActivity(PackageInstallerActivity.getLaunchableInstance(mActivity, item.packageName));
                    return;
                }
                // Otherwise, try with APK files
                // FIXME: 1/4/23 Include splits
                if (Paths.exists(info.publicSourceDir)) {
                    mActivity.startActivity(PackageInstallerActivity.getLaunchableInstance(mActivity,
                            Uri.fromFile(new File(info.publicSourceDir))));
                    return;
                }
            }
            // 3. The app might be uninstalled without clearing data
            if (ApplicationInfoCompat.isSystemApp(info)) {
                // The app is a system app, there's no point in asking to uninstall it again
                showBackupRestoreDialogOrAppNotInstalled(item);
                return;
            }
            // Fork: yellow-on-black + bordered like every other fork dialog.
            ForkDialog.present(ForkDialog.builder(mActivity)
                    .setTitle(mActivity.getString(R.string.uninstall_app, item.label))
                    .setMessage(R.string.uninstall_app_again_message)
                    .setNegativeButton(R.string.no, null)
                    .setPositiveButton(R.string.yes, (dialog, which) -> ThreadUtils.postOnBackgroundThread(() -> {
                        PackageInstallerCompat installer = PackageInstallerCompat.getNewInstance();
                        installer.setAppLabel(item.label);
                        boolean uninstalled = installer.uninstall(item.packageName, UserHandleHidden.myUserId(), false);
                        ThreadUtils.postOnMainThread(() -> {
                            if (uninstalled) {
                                displayLongToast(R.string.uninstalled_successfully, item.label);
                            } else {
                                displayLongToast(R.string.failed_to_uninstall, item.label);
                            }
                        });
                    })));
            return;
        }
        // The app is installed
        if (item.userIds.length == 1) {
            int[] userHandles = Users.getUsersIds();
            if (ArrayUtils.contains(userHandles, item.userIds[0])) {
                Intent intent = AppDetailsActivity.getIntent(mActivity, item.packageName, item.userIds[0]);
                mActivity.startActivity(intent);
                return;
            }
            // Outside our jurisdiction
            showBackupRestoreDialogOrAppNotInstalled(item);
            return;
        }
        // More than a user, ask the user to select one
        CharSequence[] userNames = new String[item.userIds.length];
        List<UserInfo> users = Users.getUsers();
        for (UserInfo info : users) {
            for (int i = 0; i < item.userIds.length; ++i) {
                if (info.id == item.userIds[i]) {
                    userNames[i] = info.toLocalizedString(mActivity);
                }
            }
        }
        new SearchableItemsDialogBuilder<>(mActivity, userNames)
                .setTitle(R.string.select_user)
                .setOnItemClickListener((dialog, which, item1) -> {
                    Intent intent = AppDetailsActivity.getIntent(mActivity, item.packageName, item.userIds[which]);
                    mActivity.startActivity(intent);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * Fork: open the backup/restore dialog in MODE_BACKUP for a single
     * installed app that has no backup yet. When "Skip backup method dialog"
     * is enabled, the dialog's backup-only path starts the backup immediately
     * with the default options instead of showing the picker.
     */
    private void openBackupModeDialog(@NonNull ApplicationItem item) {
        BackupRestoreDialogFragment fragment = BackupRestoreDialogFragment.getInstance(
                Collections.singletonList(new UserPackagePair(
                        item.packageName, UserHandleHidden.myUserId())),
                BackupRestoreDialogFragment.MODE_BACKUP);
        fragment.setOnActionBeginListener(mode -> mActivity.showProgressIndicator(true));
        fragment.setOnActionCompleteListener((mode, failedPackages) -> mActivity.showProgressIndicator(false));
        fragment.show(mActivity.getSupportFragmentManager(), BackupRestoreDialogFragment.TAG);
    }

    private void showBackupRestoreDialogOrAppNotInstalled(@NonNull ApplicationItem item) {
        if (item.backup == null) {
            // No backups
            displayShortToast(R.string.app_not_installed);
            return;
        }
        // Has backups
        BackupRestoreDialogFragment fragment = BackupRestoreDialogFragment.getInstance(
                Collections.singletonList(new UserPackagePair(
                        item.packageName, UserHandleHidden.myUserId())));
        fragment.setOnActionBeginListener(mode -> mActivity.showProgressIndicator(true));
        fragment.setOnActionCompleteListener((mode, failedPackages) -> mActivity.showProgressIndicator(false));
        fragment.show(mActivity.getSupportFragmentManager(), BackupRestoreDialogFragment.TAG);
    }

    /**
     * Toggle the freeze state of {@code item} via the freeze-indicator click.
     * Uses {@link Prefs.Blocking#getDefaultFreezingMethod()} when freezing, and
     * the same internal unfreeze path the rest of the app uses when thawing.
     * The PM call runs on a background thread and is followed by a
     * {@code sendPackageAltered} broadcast so the main list re-binds the item
     * with the new state automatically.
     * <p>
     * Caller is responsible for the eligibility gate — this method does not
     * re-check whether the app is a system app or AppManager itself.
     */
    private void toggleFreeze(@NonNull ApplicationItem item) {
        final Context ctx = mActivity.getApplicationContext();
        final int userId = (item.userIds != null && item.userIds.length > 0)
                ? item.userIds[0]
                : UserHandleHidden.myUserId();
        final boolean wasFrozen = item.isFrozen;
        ThreadUtils.postOnBackgroundThread(() -> {
            try {
                if (wasFrozen) {
                    FreezeUtils.unfreeze(item.packageName, userId);
                } else {
                    if (ProtectedAppsProfile.isProtected(item.packageName)) {
                        ThreadUtils.postOnMainThread(() -> displayLongToast(
                                R.string.protected_profile_block, item.label));
                        return;
                    }
                    FreezeUtils.freeze(item.packageName, userId,
                            Prefs.Blocking.getDefaultFreezingMethod());
                }
                BroadcastUtils.sendPackageAltered(ctx, new String[]{item.packageName});
            } catch (Throwable th) {
                Log.e(TAG, "Freeze toggle failed for " + item.packageName, th);
                ThreadUtils.postOnMainThread(() -> displayLongToast(
                        wasFrozen ? R.string.failed_to_unfreeze : R.string.failed_to_freeze,
                        item.label));
            }
        });
    }

    // Fork: apply the configurable main-list icon size + roundness. Sizes the
    // icon square, widens the icon column to match, scales the snowflake +
    // force-stop ✕ glyphs to ~0.43× the icon (so at the 60dp default they stay
    // 26dp, and the two glyphs always fit under the icon with a gap), and clips
    // the icon corners to a % of its size (0 = square, 50 = circle). Idempotent —
    // only writes LayoutParams when a dimension actually changed.
    private void applyMainIconSize(@NonNull ViewHolder holder) {
        float density = mActivity.getResources().getDisplayMetrics().density;
        int sizeDp = MainIconPrefs.getSizeDp(mActivity);
        int iconPx = Math.round(sizeDp * density);
        int glyphPx = Math.round(sizeDp * density * 0.43f);
        setViewWidth(holder.iconColumn, iconPx);
        setViewSize(holder.icon, iconPx, iconPx);
        setViewSize(holder.freezeIndicator, glyphPx, glyphPx);
        setViewSize(holder.killBadge, glyphPx, glyphPx);
        int roundPct = MainIconPrefs.getRoundnessPercent(mActivity);
        if (roundPct > 0) {
            holder.icon.setOutlineProvider(new RoundOutline(iconPx * roundPct / 100f));
            holder.icon.setClipToOutline(true);
        } else {
            holder.icon.setOutlineProvider(null);
            holder.icon.setClipToOutline(false);
        }
    }

    /** Fork: rounds the app-icon corners to a fixed radius (px) for the roundness pref. */
    private static final class RoundOutline extends ViewOutlineProvider {
        private final float mRadius;

        RoundOutline(float radiusPx) {
            mRadius = radiusPx;
        }

        @Override
        public void getOutline(@NonNull View view, @NonNull Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), mRadius);
        }
    }

    private static void setViewWidth(@NonNull View v, int w) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp.width != w) {
            lp.width = w;
            v.setLayoutParams(lp);
        }
    }

    private static void setViewSize(@NonNull View v, int w, int h) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            v.setLayoutParams(lp);
        }
    }

    // Fork: one-tap force-stop from the running app's icon ✕ badge. Mirrors the
    // app-details / process-monitor force-stop — a direct privileged call on a
    // background thread, then a package-altered broadcast so the row re-reads
    // its (now stopped) state and the running box + badge drop. Toast on
    // failure (e.g. no FORCE_STOP_PACKAGES privilege).
    private void forceStopApp(@NonNull ApplicationItem item) {
        final Context ctx = mActivity.getApplicationContext();
        final int userId = (item.userIds != null && item.userIds.length > 0)
                ? item.userIds[0]
                : UserHandleHidden.myUserId();
        ThreadUtils.postOnBackgroundThread(() -> {
            try {
                PackageManagerCompat.forceStopPackage(item.packageName, userId);
                BroadcastUtils.sendPackageAltered(ctx, new String[]{item.packageName});
            } catch (Throwable th) {
                Log.e(TAG, "Force-stop failed for " + item.packageName, th);
                ThreadUtils.postOnMainThread(() -> displayLongToast(
                        R.string.failed_to_stop, item.label));
            }
        });
    }

    public static class ViewHolder extends MultiSelectionView.ViewHolder {
        MaterialCardView itemView;
        View iconColumn;
        AppCompatImageView icon;
        AppCompatImageView killBadge;   // Fork: force-stop ✕ on the icon corner.
        AppCompatImageView debugIcon;
        AppCompatImageView freezeIndicator;
        TextView label;
        TextView packageName;
        TextView version;
        TextView isSystemApp;
        TextView date;
        TextView size;
        TextView userId;
        TextView sha;
        TextView backupIndicator;
        TextView backupVersion;
        TextView backupDate;
        TextView backupTime;
        ChipGroup profilePills;
        Chip addPill;
        AppCompatImageView noteAdd;   // Fork: top-right "+" (no-note). The has-note
                                      // badge is a compound drawable on `label`.

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            this.itemView = (MaterialCardView) itemView;
            iconColumn = itemView.findViewById(R.id.icon_column);
            icon = itemView.findViewById(R.id.icon);
            killBadge = itemView.findViewById(R.id.kill_badge);
            debugIcon = itemView.findViewById(R.id.favorite_icon);
            freezeIndicator = itemView.findViewById(R.id.freeze_indicator);
            label = itemView.findViewById(R.id.label);
            packageName = itemView.findViewById(R.id.packageName);
            version = itemView.findViewById(R.id.version);
            isSystemApp = itemView.findViewById(R.id.isSystem);
            date = itemView.findViewById(R.id.date);
            size = itemView.findViewById(R.id.size);
            userId = itemView.findViewById(R.id.shareid);
            sha = itemView.findViewById(R.id.sha);
            backupIndicator = itemView.findViewById(R.id.backup_indicator);
            backupVersion = itemView.findViewById(R.id.backup_version);
            backupDate = itemView.findViewById(R.id.backup_date);
            backupTime = itemView.findViewById(R.id.backup_time);
            profilePills = itemView.findViewById(R.id.profile_pills);
            addPill = itemView.findViewById(R.id.profile_add_pill);
            noteAdd = itemView.findViewById(R.id.note_add);
        }
    }
}