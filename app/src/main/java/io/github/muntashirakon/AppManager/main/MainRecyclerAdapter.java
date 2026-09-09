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
import android.graphics.Paint;
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
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
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
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.card.MaterialCardView;

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
import io.github.muntashirakon.AppManager.backup.dialog.AppBackupDialogFragment;
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
import io.github.muntashirakon.AppManager.main.lens.MainLens;
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
    // Fork, +81: violet marks the third dormant state, suspended.
    private final int mColorViolet;
    // Fork: dark "dormant" films painted as the card background (cool = frozen,
    // mauve = uninstalled, violet = suspended). Defaults; overridable via
    // ColorPrefs at runtime.
    private final int mColorFilmFrozen;
    private final int mColorFilmUninstalled;
    private final int mColorFilmSuspended;
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
    private int mcFreezeFrozen, mcFreezeThawed, mcFreezeSuspended;
    private int mcChip, mcAddPill;
    // Fork: re-added running/active box strokes (yellow user / orange system)
    // and the dormant-row films (cool = frozen, mauve = uninstalled).
    private int mcStrokeUser, mcStrokeSystem;
    private int mcFilmFrozen, mcFilmUninstalled, mcFilmSuspended;

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
        mColorViolet = ContextCompat.getColor(activity, R.color.theme_violet);
        mColorFilmFrozen = ContextCompat.getColor(activity, R.color.theme_film_frozen);
        mColorFilmUninstalled = ContextCompat.getColor(activity, R.color.theme_film_uninstalled);
        mColorFilmSuspended = ContextCompat.getColor(activity, R.color.theme_film_suspended);
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
            // Fork: with the "With notes" filter on, whether this row belongs in
            // the list at all is what just changed — deleting its note must drop
            // it, not merely un-draw its pill.
            if (mActivity.viewModel != null
                    && mActivity.viewModel.hasFilterFlag(MainListOptions.FILTER_APPS_WITH_NOTES)) {
                mActivity.viewModel.reapplyFilters();
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
        mcFreezeSuspended = ColorPrefs.getColor(mActivity, ColorPrefs.FREEZE_SUSPENDED, mColorViolet);
        mcChip = ColorPrefs.getColor(mActivity, ColorPrefs.CHIP, mColorYellow);
        mcAddPill = ColorPrefs.getColor(mActivity, ColorPrefs.ADDPILL, mColorYellow);
        mcStrokeUser = ColorPrefs.getColor(mActivity, ColorPrefs.STROKE_USER, mColorYellow);
        mcStrokeSystem = ColorPrefs.getColor(mActivity, ColorPrefs.STROKE_SYSTEM, mColorOrange);
        mcFilmFrozen = ColorPrefs.getColor(mActivity, ColorPrefs.FILM_FROZEN, mColorFilmFrozen);
        mcFilmUninstalled = ColorPrefs.getColor(mActivity, ColorPrefs.FILM_UNINSTALLED, mColorFilmUninstalled);
        mcFilmSuspended = ColorPrefs.getColor(mActivity, ColorPrefs.FILM_SUSPENDED, mColorFilmSuspended);
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

    /** The widest single item in the version/backup block, and its reference width. */
    /**
     * Fork (白い熊, +173): the block is sized from a VERSION, not from the signature.
     * <p>
     * This literal is a real fork version name at its full shape —
     * {@code <base>+<upstream base date>.<HH-MM>.g<sha8>+<NNN>} — because that is the widest thing
     * the column now carries and the thing 白い熊 asked to be able to read in full. The old
     * reference was {@code "SHA384withRSA"}: 13 characters for a field that had grown to 43.
     * <p>
     * It is deliberately a fixed string rather than the row's own version: a per-row width would
     * make the column ragged down the list, which is worse than either extreme. What varies is the
     * multiplier, and that is {@code MainLayoutPrefs.getRightColumnPct} — per geometry, 白い熊's
     * to set.
     */
    private static final String RIGHT_COLUMN_REFERENCE = "4.1.1+2026-09-05.03-37.g41d79af5+016";

    /**
     * Fork (白い熊, +094): the row's note affordance — ONE pill, right-aligned on
     * the label line, in both of its states.
     * <p>
     * Before this it was two: a glyph glued to the end of the app name when a
     * note existed, and a "+" floating over the column's top-right corner when
     * one did not. Two controls for one thing, in two different places, and
     * neither could show what the note actually <em>said</em> — the note was
     * only ever readable by opening it. The pill shows its first line instead,
     * and reads as the same control whether or not it has anything to show.
     * <p>
     * All the pill's own styling lives in {@link RowPills}, shared with the tag
     * pills below it so the two add affordances cannot drift apart, and how wide
     * it may grow is settled at measure time by {@link LabelLineLayout} — the app
     * name in full, the note reaching back to exactly where the name ends. None
     * of the row's geometry is decided here, which is the point: a bind-time
     * guess cannot know the line's width, and +094 guessed a fixed half.
     * <p>
     * Both branches of every switch are set, always: the row is recycled.
     */
    private void bindNotePill(@NonNull ViewHolder holder, @NonNull ApplicationItem item) {
        final String pkg = item.packageName;
        final CharSequence label = item.label;
        RowPills.bindNote(holder.notePill, pkg, ForkThemeUtils.getTextColor());
        // The same two guards the app icon carries (+82), and for the same
        // reasons — only more so, since with a note this pill is half the label
        // line rather than a 20dp glyph. A tap during multi-select must extend
        // the selection instead of opening an editor, and a clickable child
        // swallows the long-press that drives range selection unless it hands it
        // back to the card.
        holder.notePill.setOnClickListener(v -> {
            int currentPos = holder.getBindingAdapterPosition();
            if (currentPos == RecyclerView.NO_POSITION) return;
            if (isInSelectionMode()) {
                toggleSelection(currentPos);
                AccessibilityUtils.requestAccessibilityFocus(holder.itemView);
                return;
            }
            showNoteDialog(holder, pkg, label);
        });
        holder.notePill.setOnLongClickListener(v -> holder.itemView.performLongClick());
    }

    /**
     * Fork (白い熊, +76/+77): give the version/backup column the width its text
     * needs, and hand every remaining pixel to the label/package column.
     * <p>
     * The XML declared the two at 1:2, tuned for a two-column grid. On a
     * one-column list that share bought the right column hundreds of dp of
     * nothing — a gap before the backup values, another after them, and the
     * package name ellipsized at a third of the row to pay for it. It is no
     * longer a share: the block is measured from its own signature line (so the
     * configurable fonts carry through) and pinned to that width, which puts the
     * backup values flush at the card's end with the version values immediately
     * left of them. See {@link MainLayoutPrefs#rightColumnWidthPx}.
     * <p>
     * Applied per bind rather than once, because the picker can change the
     * column count and a fold can change the window under a live list, and both
     * rebind rows without re-inflating them.
     */
    /**
     * Fork (白い熊, +173/+178): size the installed version and the backup version <b>together</b>.
     *
     * <p>Two rules, and one function because they cannot be satisfied separately.
     *
     * <p><b>They must match.</b> 白い熊: "the backup version font size must be the same as the
     * app's version font size." Autosizing each view on its own cannot promise that — each would
     * pick from its own text, so 白い熊 GNU Jami (a long installed version above a longer backup
     * one) would land on two different sizes stacked in the same column. So one size is chosen
     * for the pair: the largest at which <em>both</em> fit.
     *
     * <p><b>They grow where there is room.</b> "For items that have space — like ArcaneChat — make
     * them both bigger than the current installed version font size." The configured VERSION size
     * is therefore the nominal size rather than the ceiling: a short pair grows to
     * {@link #VERSION_GROW}× it, and a long pair shrinks toward {@link #VERSION_MIN_SP} before the
     * middle ellipsis in the layout takes over. The font setting still drives everything — it
     * moves the whole band up and down.
     *
     * <p><b>The measurement is done here, not by the framework.</b> {@code TextView} autosize is
     * per-view by construction and also fights {@code setTextSize}, which is what the +173 version
     * of this had to work around; measuring the two strings against the column width is both
     * simpler and the only way to get a shared answer.
     *
     * @param backupText the backup version, or null when the column carries no backup version (no
     *                   backup at all, or the "Back up" hint, which keeps its own size)
     */
    /**
     * Fork (白い熊, +179): put both version lines back to their nominal size.
     *
     * <p>Called before anything measures or draws them, and it must not be skipped: the sizes are
     * chosen per row, {@code FontUtil.apply} only writes a size when the category has one
     * configured, and a {@code RecyclerView} holder is reused across hundreds of rows. Without
     * this the "nominal" size read at the next bind is the last row's answer, which then gets
     * re-fitted from there — the backup line drifting bigger than the installed line, and the rows
     * growing taller, both came from that.
     *
     * <p>The layout default is restored first and the configured font applied over it, in that
     * order, so the result is the same whether or not a size is configured.
     */
    private static void resetVersionSizes(@NonNull ViewHolder holder) {
        if (holder.version != null) {
            if (holder.baseVersionPx > 0) {
                holder.version.setTextSize(TypedValue.COMPLEX_UNIT_PX, holder.baseVersionPx);
            }
            FontUtil.apply(holder.version, FontPrefs.VERSION);
        }
        if (holder.backupVersion != null) {
            if (holder.baseBackupPx > 0) {
                holder.backupVersion.setTextSize(TypedValue.COMPLEX_UNIT_PX, holder.baseBackupPx);
            }
            FontUtil.apply(holder.backupVersion, FontPrefs.BACKUP_INFO);
        }
    }

    private static void fitVersionPair(@NonNull ViewHolder holder, @Nullable CharSequence backupText) {
        TextView version = holder.version;
        if (version == null) {
            return;
        }
        // The nominal size, re-read every bind: FontUtil.apply has already put the configured
        // value on the view, and a recycled row arrives carrying whatever the last row chose.
        float density = version.getResources().getDisplayMetrics().scaledDensity;
        float baseSp = version.getTextSize() / density;
        int max = Math.max(VERSION_MIN_SP, Math.round(baseSp * VERSION_GROW));
        int available = version.getWidth() - version.getPaddingLeft() - version.getPaddingRight();
        if (available <= 0) {
            // First bind, before layout. The column width is known independently of it, and it is
            // what the text actually has to fit into.
            available = holder.rightColumn != null ? holder.rightColumn.getLayoutParams().width : 0;
        }
        int chosen = max;
        if (available > 0) {
            TextPaint probe = new TextPaint(version.getPaint());
            CharSequence installed = version.getText();
            for (int sp = max; sp > VERSION_MIN_SP; --sp) {
                probe.setTextSize(sp * density);
                if (fits(probe, installed, available) && fits(probe, backupText, available)) {
                    chosen = sp;
                    break;
                }
                chosen = VERSION_MIN_SP;
            }
        }
        version.setTextSize(TypedValue.COMPLEX_UNIT_SP, chosen);
        if (backupText != null && holder.backupVersion != null) {
            holder.backupVersion.setTextSize(TypedValue.COMPLEX_UNIT_SP, chosen);
        }
    }

    private static boolean fits(@NonNull TextPaint paint, @Nullable CharSequence text, int width) {
        return text == null || text.length() == 0 || paint.measureText(text, 0, text.length()) <= width;
    }

    /** How small the pair may shrink before the middle ellipsis takes over. */
    private static final int VERSION_MIN_SP = 9;
    /**
     * Fork (白い熊, +179): the pair never grows past the version line's nominal size.
     * <p>
     * +178 let a short pair grow to 1.45x, which 白い熊 rejected on sight: these two lines are what
     * set the right column's height, so anything that makes them taller makes the whole card
     * taller — "we make the app's box grow vertically here. We don't want that." Growth and a
     * fixed row height cannot both be had, and the fixed height wins.
     * <p>
     * The backup line still ENDS UP bigger than it was, because it is now equalised to the version
     * line's size rather than pinned at 11sp. The way to make both bigger deliberately is the
     * VERSION font size on the 白い熊 応用管理 UI page, which moves the pair together and lets 白い熊
     * accept the extra row height knowingly.
     */
    private static final float VERSION_GROW = 1f;

    private static void applyColumnProportions(@NonNull Context context, @NonNull ViewHolder holder) {
        applyColumnProportions(context, holder, false);
    }

    /**
     * Fork (白い熊, +162): under a lens the right column carries that lens's own lines rather than
     * version/type/SDK/signature, so it is sized the way every other surface that does this sizes it
     * — the wide proportions {@code MainCardBinder} uses — instead of against a signature algorithm
     * that is no longer being drawn.
     */
    private static void applyColumnProportions(@NonNull Context context, @NonNull ViewHolder holder,
                                               boolean wideRight) {
        if (holder.centerColumn == null || holder.rightColumn == null || holder.version == null) return;
        if (wideRight) {
            LinearLayoutCompat.LayoutParams centerLp =
                    (LinearLayoutCompat.LayoutParams) holder.centerColumn.getLayoutParams();
            LinearLayoutCompat.LayoutParams rightLp =
                    (LinearLayoutCompat.LayoutParams) holder.rightColumn.getLayoutParams();
            if (centerLp.width == 0 && centerLp.weight == 1.35f
                    && rightLp.width == 0 && rightLp.weight == 1f) {
                return;
            }
            centerLp.width = 0;
            centerLp.weight = 1.35f;
            rightLp.width = 0;
            rightLp.weight = 1f;
            holder.centerColumn.setLayoutParams(centerLp);
            holder.rightColumn.setLayoutParams(rightLp);
            return;
        }
        // The version view's own paint, so a larger configured font widens the block instead of
        // clipping inside it. It must be the VERSION's paint now that the version is what the
        // reference describes — the signature line is no longer drawn in the plain list.
        float referencePx = holder.version.getPaint().measureText(RIGHT_COLUMN_REFERENCE);
        int rightWidth = MainLayoutPrefs.rightColumnWidthPx(context, referencePx);
        LinearLayoutCompat.LayoutParams centerLp =
                (LinearLayoutCompat.LayoutParams) holder.centerColumn.getLayoutParams();
        LinearLayoutCompat.LayoutParams rightLp =
                (LinearLayoutCompat.LayoutParams) holder.rightColumn.getLayoutParams();
        // requestLayout on every bind would be wasted work on a scrolling list.
        if (rightLp.width == rightWidth && rightLp.weight == 0f
                && centerLp.width == 0 && centerLp.weight == 1f) {
            return;
        }
        // The label column takes all the slack; the block beside it takes none.
        centerLp.width = 0;
        centerLp.weight = 1f;
        rightLp.width = rightWidth;
        rightLp.weight = 0f;
        holder.centerColumn.setLayoutParams(centerLp);
        holder.rightColumn.setLayoutParams(rightLp);
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final ApplicationItem item = getItem(position);
        MaterialCardView cardView = holder.itemView;
        Context context = cardView.getContext();
        // Fork (白い熊, +162): the active lens, resolved once per bind. Everything about the row is
        // unchanged by it -- icon, film, running box, frames, label line, pane, selection -- except
        // the right-hand column, which is the whole point.
        MainLens lens = mActivity.viewModel != null ? mActivity.viewModel.getLens() : null;
        // Fork (白い熊, +178): put the version line back to its configured size BEFORE the column
        // is measured from it. applyColumnProportions measures the reference string with this
        // view's own paint, and fitVersionPair leaves the paint at whatever size the previous row
        // needed -- so without this reset the column width would follow the last row bound, and
        // the columns would go ragged down the list.
        resetVersionSizes(holder);
        applyColumnProportions(context, holder, lens != null && lens.wideRight());
        bindAppPane(holder, item);
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
            // Fork (白い熊, +118): a tap unrolls the pane instead of leaving for App details.
            // Both of the pages the old tap could reach are pills inside it.
            toggleExpanded(item, currentPos);
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
        } else if (item.isSuspendedApp) {
            // Fork, +81: suspended is checked BEFORE frozen — it is a kind of
            // frozen (isFrozen is true as well), and it is the deeper state, so
            // it has to win the film.
            filmColor = mcFilmSuspended;
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
        // Set UID. Fork (白い熊, +096): it now leads the app-ID line instead of
        // trailing the install date — see item_main.xml. GONE rather than an
        // empty string when there is nothing to show, or the empty view would
        // still hold its end margin and hold the package name off its own left
        // edge for every uninstalled row.
        if (item.isInstalled && !TextUtils.isEmpty(item.uidOrAppIds)) {
            holder.userId.setVisibility(View.VISIBLE);
            holder.userId.setText(item.uidOrAppIds);
            // Set UID text color to orange if the package is shared
            holder.userId.setTextColor(item.sharedUserId != null ? mcUidShared : mcUidNormal);
        } else {
            holder.userId.setText("");
            holder.userId.setVisibility(View.GONE);
        }
        FontUtil.apply(holder.userId, FontPrefs.UID);
        // Fork (白い熊, +173): the signature-algorithm line is NOT drawn in the plain list.
        //
        // It said "SHA384withRSA" — the signing certificate's algorithm, identical for every app
        // 白い熊 builds — and it cost a line of every row plus, until this build, the width of the
        // whole column, which was measured from it. 白い熊 traded it for the version's own line.
        //
        // The VIEW stays and is only hidden: MainCardBinder.renderCustomRightLines hands it the
        // third and later lines of a lens's custom right column, so deleting it from the layout
        // would silently truncate every lens. The signature SORT also stays — sort ids are the
        // wire format of a saved view and are never renumbered — see the note in the release
        // summary about it no longer having a visible column.
        holder.sha.setVisibility(View.GONE);
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
        // Fork, +81: a SUSPENDED row swaps the snowflake for a filled padlock in
        // violet. Shape carries further than colour at this size, and the two
        // together mean the deepest dormant state on the list is never mistaken
        // for an ordinary freeze — which matters, because the app is not merely
        // asleep: the system puts a stub in its place and nothing can open it.
        // Three-way, and every branch sets both properties (recycled views).
        if (item.isSuspendedApp) {
            holder.freezeIndicator.setImageResource(R.drawable.ic_lock);
            holder.freezeIndicator.setImageTintList(ColorStateList.valueOf(mcFreezeSuspended));
        } else {
            holder.freezeIndicator.setImageResource(item.isFrozen
                    ? R.drawable.ic_snowflake_24dp
                    : R.drawable.ic_snowflake_outline_24dp);
            holder.freezeIndicator.setImageTintList(ColorStateList.valueOf(
                    item.isFrozen ? mcFreezeFrozen : mcFreezeThawed));
        }
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
        // Fork, +82: the ICON itself opens the app's Snooping page. The rest of
        // the column — the snowflake row and the space around it — keeps the
        // freeze toggle, so nothing is lost: the glyph you tap to freeze is the
        // freeze glyph, which is where it belonged anyway.
        //
        // The selection-mode guard is repeated here deliberately, exactly as the
        // backup column repeats it. Without it, a tap on the icon during a
        // multi-select opens an app instead of extending the selection — and the
        // icon is the easiest thing on the row to hit by accident while
        // selecting. Long-press is forwarded to the card so range-selection by
        // long-pressing the icon keeps working, which a clickable child would
        // otherwise swallow.
        holder.icon.setOnClickListener(v -> {
            int currentPos = holder.getBindingAdapterPosition();
            if (currentPos == RecyclerView.NO_POSITION) return;
            if (isInSelectionMode()) {
                toggleSelection(currentPos);
                AccessibilityUtils.requestAccessibilityFocus(holder.itemView);
                return;
            }
            // Fork (白い熊, +118): the icon opens the same pane as the rest of the row. It
            // used to jump straight to 盗み見 (+82); one rule for the whole row beats a
            // shortcut nobody can predict, and 盗み見 is a pill inside the pane.
            toggleExpanded(item, currentPos);
        });
        holder.icon.setOnLongClickListener(v -> holder.itemView.performLongClick());
        // Fork: italic marks BOTH frozen and uninstalled (dormant) rows.
        boolean dormantItalic = item.isFrozen || !item.isInstalled;
        holder.label.setTypeface(null, dormantItalic ? Typeface.ITALIC : Typeface.NORMAL);
        // Fork, +81: and a strikethrough marks the suspended ones. It is the
        // cue that survives everything — any icon size, any column count, any
        // colour the user picks — and it says exactly the right thing: this app
        // is struck out, not merely dimmed. Set in both branches; paint flags
        // live on the view, so a recycled label would keep the line for ever.
        int paintFlags = holder.label.getPaintFlags();
        holder.label.setPaintFlags(item.isSuspendedApp
                ? paintFlags | Paint.STRIKE_THRU_TEXT_FLAG
                : paintFlags & ~Paint.STRIKE_THRU_TEXT_FLAG);
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
        bindNotePill(holder, item);
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
        // and backup info text used to live). Each pill is yellow text inside a
        // yellow hairline-stroked transparent stadium — same visual language as
        // the search bar and the installer master-toggle banner, and, since +094,
        // built by the same RowPills builder as the note pill on the label line,
        // so the row's two add affordances are uniform by construction rather
        // than by two blocks of styling agreeing with each other.
        //
        // They are RIGHT-aligned, against the note pill's edge (see the layout).
        //
        // Interaction model on this row (per fork spec):
        //   - tap a profile pill        -> filter the main list to apps in that profile
        //   - long-press a profile pill -> remove this app from that profile (saves on disk)
        //   - tap the "+" pill          -> open the add-to-profile dialog for this app
        //   - long-press the "+" pill   -> clear an active profile filter, if any
        // The "+" pill is declared in item_main.xml, sitting OUTSIDE the
        // horizontal scroller that holds the memberships so it can never be
        // scrolled out of reach, and so it stays pinned to the row's right edge
        // however many pills precede it. It guarantees a tappable surface for
        // the add affordance even when an app has no profile memberships. The
        // strip's own empty-space click/long-click handlers below are kept as a
        // defensive backup — they fire only when the user lands between or
        // beyond pills, since each pill consumes its own touch area.
        holder.profilePills.removeAllViews();
        final String pkgForRow = item.packageName;
        // Profile-membership pills.
        List<String> profileNames = mPackageToProfileNames.get(item.packageName);
        if (profileNames != null) {
            for (String name : profileNames) {
                TextView chip = RowPills.tagPill(context, name, mcChip);
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
        // The XML-declared "+" pill — always shown, and pinned to the same width
        // as the empty note pill above it so the two add affordances line up as
        // one column. Style applied here because the pill is the same shape
        // across all rows.
        RowPills.bindAddTag(holder.addPill, mcAddPill);
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
        if (lens != null) {
            bindLensRightColumn(context, holder, item, lens);
            super.onBindViewHolder(holder, position);
            return;
        }
        // Set version (along with HW accelerated, debug and test only flags)
        // Fork (白い熊, +173): the version line, which is now the widest thing in the row.
        // Autosize BEFORE the text so a long name shrinks rather than losing its middle; see
        // fitLongValue.
        holder.version.setText(item.versionTag);
        // Fork (白い熊, +176): right-aligned, so the installed version sits directly above the
        // backup version rather than starting at the opposite edge of the same column. Set here
        // rather than in the layout because this view is shared: the battery card binds it as a
        // left-aligned stat line, and the lens path sets its own gravity in
        // MainCardBinder.renderCustomRightLines. Every bind states what it wants, or a recycled
        // row inherits whichever surface used it last.
        holder.version.setGravity(Gravity.END);
        // Set version color to dark cyan if the app is inactive
        holder.version.setTextColor(item.isAppInactive ? mcVersionInactive : mcVersionNormal);
        // Size is settled once for the PAIR, after the backup version is known -- see
        // fitVersionPair, called at the end of the backup block below.
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
            // Recycling: the no-backup branch dims this line for its hint.
            holder.backupDate.setAlpha(1f);
            holder.backupTime.setVisibility(View.VISIBLE);
            holder.backupTime.setText(timeFmt.format(when));
            holder.backupTime.setTextColor(mcBackup);
            // Fork (白い熊, +180): the backup VERSION follows the VERSION font, not BACKUP_INFO.
            //
            // 白い熊: "they must be identical." Equal text SIZES were not enough and could never
            // be: the two lines were being given two different typefaces, and the same sp in two
            // faces with different cap and x-heights reads as two different sizes -- which is
            // exactly what it looked like. It also made the fit wrong, because fitVersionPair
            // measures both strings with the version line's paint.
            //
            // Only this line moves. The date and time below it stay BACKUP_INFO: they are backup
            // metadata, they sit opposite the app's own type and SDK lines, and nothing about them
            // has to line up with a version. The backup version is still told apart by its COLOUR,
            // which is what distinguished it in the first place.
            FontUtil.apply(holder.backupVersion, FontPrefs.VERSION);
            FontUtil.apply(holder.backupDate, FontPrefs.BACKUP_INFO);
            FontUtil.apply(holder.backupTime, FontPrefs.BACKUP_INFO);
            bindBackupColumnGestures(holder, item);
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
            FontUtil.apply(holder.backupDate, FontPrefs.BACKUP_INFO);
            // Keep the date/time cells visible-but-empty so the tap target
            // spans the whole right-hand backup column (all three rows),
            // not just the one-line "Back up" text - much easier to hit.
            // Empty text renders nothing but the cells still occupy their
            // row height and receive clicks.
            holder.backupDate.setVisibility(View.VISIBLE);
            // Fork: the second line says HOW, now that a simple tap opens app
            // info like the rest of the row. Without it the column reads
            // "Back up" and then does something else when you tap it.
            holder.backupDate.setText(R.string.backup_longpress_hint);
            holder.backupDate.setTextColor(mcBackup);
            holder.backupDate.setAlpha(0.6f);
            holder.backupTime.setVisibility(View.VISIBLE);
            holder.backupTime.setText("");
            bindBackupColumnGestures(holder, item);
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
        // Fork (白い熊, +178): both version lines are sized together, here, because this is the
        // first point at which BOTH texts are known. The backup version is passed only when it
        // really is a version -- the "Back up" hint that borrows the same view is a different
        // string and keeps its own size.
        fitVersionPair(holder, item.backup != null ? item.backup.versionName : null);
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

    /**
     * Fork: gestures for the right-hand backup column (白い熊, 2026-08-01).
     * <p>
     * A simple tap used to start a backup, which made the column the one part of
     * the row that did not behave like the row — the same tap two centimetres to
     * the left opened the app. It now opens app info like everywhere else, and
     * every backup action lives on the long-press dialog instead.
     * <p>
     * The tap repeats the card's selection-mode guard on purpose: without it,
     * tapping this column during a multi-select would open an app instead of
     * extending the selection.
     */
    /**
     * Fork (白い熊, +162): the right-hand column under a lens.
     *
     * <p>Rendered through {@code MainCardBinder.renderCustomRightLines} -- the SAME renderer the
     * sibling screens and the battery header use -- so a change to how these lines look cannot
     * apply to one surface and not the other.
     *
     * <p>The three backup cells are hidden and their listeners nulled, exactly as the
     * not-installed-and-not-backed-up branch does: a recycled holder would otherwise keep a stale
     * {@link ApplicationItem} alive through a captured listener, and under the Backups lens the
     * column beneath is already saying everything those cells would.
     */
    private void bindLensRightColumn(@NonNull Context context, @NonNull ViewHolder holder,
                                     @NonNull ApplicationItem item, @NonNull MainLens lens) {
        // Fork (白い熊, +179): the lens's first line goes into the version view, which a plain row
        // may have shrunk to fit a version string; resetVersionSizes has already put it back.
        holder.backupIndicator.setVisibility(View.GONE);
        holder.backupVersion.setVisibility(View.GONE);
        holder.backupVersion.setAlpha(1f);
        holder.backupDate.setVisibility(View.GONE);
        holder.backupDate.setAlpha(1f);
        holder.backupTime.setVisibility(View.GONE);
        holder.backupVersion.setOnClickListener(null);
        holder.backupDate.setOnClickListener(null);
        holder.backupTime.setOnClickListener(null);
        holder.backupVersion.setOnLongClickListener(null);
        holder.backupDate.setOnLongClickListener(null);
        holder.backupTime.setOnLongClickListener(null);
        List<CharSequence> lines;
        int accent;
        try {
            lines = lens.rightLines(context, item);
            accent = lens.accent(item);
        } catch (Throwable th) {
            // A lens that throws must cost one row's detail, never the list.
            lines = java.util.Collections.emptyList();
            accent = 0;
        }
        io.github.muntashirakon.AppManager.battery.MainCardBinder
                .renderCustomRightLines(holder.itemView, lines, accent);
    }

    private void bindBackupColumnGestures(@NonNull ViewHolder holder, @NonNull ApplicationItem item) {
        View.OnClickListener openInfo = v -> {
            int currentPos = holder.getBindingAdapterPosition();
            if (currentPos == RecyclerView.NO_POSITION) return;
            if (isInSelectionMode()) {
                toggleSelection(currentPos);
                AccessibilityUtils.requestAccessibilityFocus(holder.itemView);
                return;
            }
            // Fork (白い熊, +168): App info explicitly. This tap has always meant "the app's
            // information page"; passing -1 made it mean "whatever tab is first", which became
            // 盗み見 when that was pinned in front.
            handleClick(item, AppDetailsActivity.TAB_APP_INFO);
        };
        View.OnLongClickListener backupMenu = v -> {
            showBackupDialog(item);
            return true;
        };
        holder.backupVersion.setOnClickListener(openInfo);
        holder.backupDate.setOnClickListener(openInfo);
        holder.backupTime.setOnClickListener(openInfo);
        holder.backupVersion.setOnLongClickListener(backupMenu);
        holder.backupDate.setOnLongClickListener(backupMenu);
        holder.backupTime.setOnLongClickListener(backupMenu);
    }

    /**
     * Every backup action for this app, in one dialog (白い熊, 2026-08-02).
     * <p>
     * This used to be a two-line chooser — "Backup", and "Restore or delete…"
     * which then opened the backup/restore bottom sheet — so the one thing you
     * came here to see, the backups themselves, was always a screen away and
     * "Backup" was a list line pretending to be an action.
     * {@link AppBackupDialogFragment} shows the backups, acts on the ticked ones
     * and carries "Back up" as its own action pill. It decides for itself what
     * to offer, so nothing is gated here beyond the case where the app is
     * neither installed nor backed up and there is nothing to open.
     */
    // ── The unrolled pane (白い熊, +118) ─────────────────────────────────────

    /** The row that is currently unrolled, as package:user, or null. */
    @Nullable
    private String mExpandedKey;

    @NonNull
    private static String keyOf(@NonNull ApplicationItem item) {
        return item.packageName + ':' + (item.userIds.length > 0 ? item.userIds[0] : 0);
    }

    public boolean isPaneOpen() {
        return mExpandedKey != null;
    }

    /**
     * Close whatever is open. Returns whether anything was.
     * <p>
     * The Back button uses this: an open pane is a state you should be able to leave without
     * leaving the screen.
     */
    public boolean collapsePane() {
        if (mExpandedKey == null) {
            return false;
        }
        mExpandedKey = null;
        notifyDataSetChanged();
        return true;
    }

    /**
     * Unroll this row, or roll it up if it is already open.
     * <p>
     * One at a time: two open panes would put the row you tapped second below a screenful of the
     * first one's actions. Both the old and the new row are redrawn, and the span cache is
     * invalidated because an open row takes the full width of a grid (see
     * {@code MainActivity#applyListLayout}).
     */
    private void toggleExpanded(@NonNull ApplicationItem item, int position) {
        String key = keyOf(item);
        String previous = mExpandedKey;
        mExpandedKey = key.equals(previous) ? null : key;
        // notifyDataSetChanged rather than two item changes: the span size of BOTH rows has just
        // changed, and RecyclerView will not re-run the lookup for a row it thinks is unchanged.
        notifyDataSetChanged();
        if (mActivity != null) {
            mActivity.onPaneToggled(position, mExpandedKey != null);
        }
    }

    /** Whether the row at {@code position} is the open one. Used by the span-size lookup. */
    public boolean isExpandedAt(int position) {
        if (mExpandedKey == null || position < 0 || position >= getItemCount()) {
            return false;
        }
        try {
            return isExpanded(getItem(position));
        } catch (Throwable th) {
            return false;
        }
    }

    /** Whether {@code item} is the open row. Read on every bind — the holder recycles. */
    public boolean isExpanded(@NonNull ApplicationItem item) {
        return mExpandedKey != null && mExpandedKey.equals(keyOf(item));
    }

    private void bindAppPane(@NonNull ViewHolder holder, @NonNull ApplicationItem item) {
        if (holder.appPane == null) {
            return;
        }
        if (!isExpanded(item)) {
            holder.appPane.setVisibility(View.GONE);
            holder.appPane.removeAllViews();
            return;
        }
        holder.appPane.setVisibility(View.VISIBLE);
        AppPaneBinder.bind(holder.appPane, item, new AppPaneBinder.Host() {
            @Override
            public void onPaneAction(@NonNull String key, @NonNull ApplicationItem paneItem) {
                MainRecyclerAdapter.this.onPaneAction(key, paneItem);
            }

            @Override
            public void onPaneReordered() {
                notifyDataSetChanged();
            }
        }, 0);
    }

    /**
     * What a pane pill does. Everything routes to the action that already exists — the pane adds
     * a way to reach them, never a second implementation of them.
     */
    private void onPaneAction(@NonNull String key, @NonNull ApplicationItem item) {
        int userId = item.userIds.length > 0 ? item.userIds[0] : UserHandleHidden.myUserId();
        switch (key) {
            case "open": {
                Intent launch = mActivity.getPackageManager().getLaunchIntentForPackage(item.packageName);
                if (launch != null) {
                    mActivity.startActivity(launch);
                } else {
                    displayShortToast(R.string.app_not_installed);
                }
                break;
            }
            case "app_info":
                // Fork (白い熊, +168): name the tab. Left as the default this pill opened 盗み見 —
                // the same page as the pill beside it.
                handleClick(item, AppDetailsActivity.TAB_APP_INFO);
                break;
            case "snooping":
                handleClick(item, AppDetailsActivity.TAB_SNOOPING);
                break;
            case "freeze":
                toggleFreeze(item);
                break;
            case "force_stop":
                forceStopApp(item);
                break;
            case "backup":
                showBackupDialog(item);
                break;
            case "restore":
                openBackupSheet(item, BackupRestoreDialogFragment.MODE_RESTORE);
                break;
            case "delete_backup":
                openBackupSheet(item, BackupRestoreDialogFragment.MODE_DELETE);
                break;
            case "share_backup":
                // Fork (白い熊): pick one of this app's backups, hand its directory to 魔法絨毯.
                ShareBackupHandler.share(mActivity, Collections.singletonList(
                        new UserPackagePair(item.packageName, UserHandleHidden.myUserId())));
                break;
            case "note":
                AppNotesManager.showNoteDialog(mActivity, item.packageName, item.label,
                        () -> notifyItemChanged(indexOf(item)));
                break;
            case "battery":
                mActivity.startActivity(new Intent(mActivity,
                        io.github.muntashirakon.AppManager.battery.BatteryUsageActivity.class));
                break;
            case "app_settings":
                mActivity.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + item.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            default:
                // Uninstall, clear data/cache, save APK, profiles, manifest and scanner all have
                // a single-app home in App details; sending the row there beats a second copy of
                // each flow living in the list. App info is that home — none of them is a snooping
                // question, so none should land on 盗み見 just because it sits first.
                handleClick(item, AppDetailsActivity.TAB_APP_INFO);
                break;
        }
    }

    private void showBackupDialog(@NonNull ApplicationItem item) {
        if (!item.isInstalled && item.backup == null) {
            return;
        }
        AppBackupDialogFragment fragment = AppBackupDialogFragment.getInstance(item.packageName,
                UserHandleHidden.myUserId(), item.label);
        fragment.setOnActionBeginListener(mode -> mActivity.showProgressIndicator(true));
        fragment.setOnActionCompleteListener((mode, failedPackages) -> mActivity.showProgressIndicator(false));
        fragment.show(mActivity.getSupportFragmentManager(), AppBackupDialogFragment.TAG);
    }

    private void handleClick(@NonNull ApplicationItem item) {
        handleClick(item, -1);
    }

    /**
     * Open this row, landing on {@code tabIndex} of App details.
     * <p>
     * Fork, +82: the tab is threaded through the whole method rather than
     * short-circuited at the caller, because every one of the branches below can
     * end in App details — a single reachable user, a package we wrongly believed
     * uninstalled, a picker over several users — and a tap that lands on 盗み見
     * for one of them and on App info for another would be the kind of
     * inconsistency nobody can hold in their head. {@code -1} means "wherever
     * App details opens by default", which is what every pre-existing caller
     * wants.
     */
    private void handleClick(@NonNull ApplicationItem item, int tabIndex) {
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
                mActivity.startActivity(detailsIntent(item.packageName, UserHandleHidden.myUserId(), tabIndex));
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
                mActivity.startActivity(detailsIntent(item.packageName, item.userIds[0], tabIndex));
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
                    mActivity.startActivity(detailsIntent(item.packageName, item.userIds[which], tabIndex));
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** App details for this package, on a given tab when one was asked for. */
    @NonNull
    private Intent detailsIntent(@NonNull String packageName, int userId, int tabIndex) {
        return tabIndex >= 0
                ? AppDetailsActivity.getIntent(mActivity, packageName, userId, tabIndex)
                : AppDetailsActivity.getIntent(mActivity, packageName, userId);
    }

    private void showBackupRestoreDialogOrAppNotInstalled(@NonNull ApplicationItem item) {
        if (item.backup == null) {
            // No backups
            displayShortToast(R.string.app_not_installed);
            return;
        }
        // Fork (白い熊): the app is NOT installed and has backups, so the only thing that can be
        // meant here is a restore. This used to open the unrestricted sheet, which for a mixed
        // state produced the backup tab, its "not installed and cannot be backed up" banner and a
        // delete icon — for an app that by definition cannot be backed up.
        openBackupSheet(item, BackupRestoreDialogFragment.MODE_RESTORE);
    }

    /** Fork (白い熊): the sheet for ONE app, restricted to one action. */
    private void openBackupSheet(@NonNull ApplicationItem item,
                                 @BackupRestoreDialogFragment.ActionMode int actionMode) {
        BackupRestoreDialogFragment fragment = BackupRestoreDialogFragment.getInstance(
                Collections.singletonList(new UserPackagePair(
                        item.packageName, UserHandleHidden.myUserId())), actionMode);
        fragment.setOnActionBeginListener(mode -> mActivity.showProgressIndicator(true));
        fragment.setOnActionCompleteListener((mode, failedPackages) -> mActivity.showProgressIndicator(false));
        fragment.show(mActivity.getSupportFragmentManager(), BackupRestoreDialogFragment.TAG);
    }

    /**
     * Toggle the freeze state of {@code item} via the freeze-indicator click.
     * Uses {@link FreezeUtils#resolveFreezeMethod(String)} when freezing — the
     * method remembered for this app, else the global default — and
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
                            FreezeUtils.resolveFreezeMethod(item.packageName));
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
        // Fork (白い熊, +124): one owner for this rule. The battery header and the sibling
        // screens draw the same card, and before this they showed the layout's default 60dp
        // whatever the setting said.
        io.github.muntashirakon.AppManager.battery.MainCardBinder.applyIconSize(mActivity, holder.itemView);
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
        /**
         * Fork (白い熊, +179): the two version lines' LAYOUT default sizes, captured once.
         * <p>
         * They are re-sized at bind time, and {@code FontUtil.apply} only calls
         * {@code setTextSize} when the category has a configured size — with the size left on
         * "inherit" it changes the typeface and nothing else. So a bind that reads the size back
         * off the view is reading whatever the PREVIOUS row was given, and multiplying it again:
         * that is what made the backup line balloon past the installed one and the rows grow
         * taller as the list was scrolled.
         */
        float baseVersionPx;
        float baseBackupPx;
        MaterialCardView itemView;
        View centerColumn;  // Fork: label/package. Widened on a wide row.
        View rightColumn;   // Fork: version/backup. Held to a constant width.
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
        LinearLayoutCompat profilePills;
        TextView addPill;
        // Fork (+094): one pill for the note in both states. How wide it may
        // grow is LabelLineLayout's business, not the holder's.
        TextView notePill;
        // Fork (+118): the pane a tap unrolls. Part of the row, not an inserted item.
        LinearLayoutCompat appPane;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            this.itemView = (MaterialCardView) itemView;
            appPane = itemView.findViewById(R.id.app_pane);
            centerColumn = itemView.findViewById(R.id.main_center_column);
            rightColumn = itemView.findViewById(R.id.main_right_column);
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
            baseVersionPx = version != null ? version.getTextSize() : 0f;
            baseBackupPx = backupVersion != null ? backupVersion.getTextSize() : 0f;
            backupDate = itemView.findViewById(R.id.backup_date);
            backupTime = itemView.findViewById(R.id.backup_time);
            profilePills = itemView.findViewById(R.id.profile_pills);
            addPill = itemView.findViewById(R.id.profile_add_pill);
            notePill = itemView.findViewById(R.id.note_pill);
        }
    }
}