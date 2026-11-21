// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.scanner.vt;

import static io.github.muntashirakon.AppManager.utils.UIUtils.getColoredText;
import static io.github.muntashirakon.AppManager.utils.UIUtils.getPrimaryText;
import static io.github.muntashirakon.AppManager.utils.UIUtils.getSmallerText;

import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.github.muntashirakon.AppManager.BaseActivity;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.apk.installer.PackageInstallerActivity;
import io.github.muntashirakon.AppManager.db.entity.VtFile;
import io.github.muntashirakon.AppManager.db.entity.VtScanAttempt;
import io.github.muntashirakon.AppManager.fm.FmProvider;
import io.github.muntashirakon.AppManager.intercept.IntentCompat;
import io.github.muntashirakon.AppManager.main.RowPills;
import io.github.muntashirakon.AppManager.settings.FeatureController;
import io.github.muntashirakon.AppManager.settings.Prefs;
import io.github.muntashirakon.AppManager.utils.DateUtils;
import io.github.muntashirakon.AppManager.utils.ForkThemeUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;
import io.github.muntashirakon.AppManager.utils.appearance.ColorCodes;
import io.github.muntashirakon.io.Path;
import io.github.muntashirakon.io.Paths;

/**
 * Fork (白い熊, APK-vetting chain, item 5): the scan-and-report home. It is the destination from the
 * scanner card, the history rows and the scan notification, and the entry the file manager and the
 * installer target with {@link VtContract#ACTION_VT_SCAN}. It shows a verdict, the detections, the
 * dates, the file facts, the local analysis, and an Install pill that hands the file to the universal
 * installer per the shared contract.
 */
public class VtReportActivity extends BaseActivity {
    public static final String EXTRA_FILE_ID = BuildConfigId() + ".extra.VT_FILE_ID";
    public static final String EXTRA_ATTEMPT_ID = BuildConfigId() + ".extra.VT_REPORT_ATTEMPT_ID";
    public static final String EXTRA_SHA256 = BuildConfigId() + ".extra.VT_REPORT_SHA256";

    private static String BuildConfigId() {
        return io.github.muntashirakon.AppManager.BuildConfig.APPLICATION_ID;
    }

    private static volatile boolean sVisible;

    public static boolean isVisible() {
        return sVisible;
    }

    @NonNull
    public static Intent intentForFile(@NonNull Context context, long fileId) {
        return new Intent(context, VtReportActivity.class).putExtra(EXTRA_FILE_ID, fileId);
    }

    @NonNull
    public static Intent intentForAttempt(@NonNull Context context, long attemptId, long fileId) {
        return new Intent(context, VtReportActivity.class)
                .putExtra(EXTRA_ATTEMPT_ID, attemptId).putExtra(EXTRA_FILE_ID, fileId);
    }

    @NonNull
    public static Intent intentForSha(@NonNull Context context, @NonNull String sha256) {
        return new Intent(context, VtReportActivity.class).putExtra(EXTRA_SHA256, sha256);
    }

    private LinearLayout mContent;
    private LinearLayout mActions;
    private TextView mTitle;
    private VtReportViewModel mViewModel;
    private final Set<Long> mKeyDialogShown = new HashSet<>();
    // Fork (白い熊, +081): the view model polls once a second. The sections are rebuilt only when
    // what they show actually changed (this signature); in between, only the live widgets of a
    // running scan are touched — otherwise the ring would restart its sweep every second and a
    // long report would jump back to the top while it is being read.
    @Nullable
    private String mSignature;
    @Nullable
    private VtReportViewModel.State mLastState;
    private boolean mShowAllEngines;
    // Fork (白い熊, +084): the full report is a second page of this same dialog. SUMMARY = the
    // verdict page; ALL or a category index (0 flagged · 1 suspicious · 2 clean · 3 no answer)
    // = the full report filtered to it.
    private static final int PAGE_SUMMARY = -2;
    private static final int FILTER_ALL = -1;
    private int mPage = PAGE_SUMMARY;
    private androidx.activity.OnBackPressedCallback mBackToSummary;
    @Nullable
    private VtGaugeView mLiveGauge;
    @Nullable
    private TextView mLiveTitle;
    @Nullable
    private TextView mLiveDetail;
    @Nullable
    private TextView mLiveElapsed;

    @Override
    protected void onAuthenticated(@Nullable Bundle savedInstanceState) {
        setContentView(R.layout.activity_vt_report);
        // The framed panel: black, rounded, in the fork's ink — painted here because the ink is
        // the configurable theme's, which no theme attribute can supply.
        View panel = findViewById(R.id.vt_report_panel);
        GradientDrawable frame = new GradientDrawable();
        frame.setColor(ForkThemeUtils.getBackgroundColor());
        frame.setCornerRadius(dp(22));
        frame.setStroke(Math.max(2, dp(3)), accent());
        panel.setBackground(frame);
        panel.setClipToOutline(true);
        int inset = dp(3);
        panel.setPadding(inset, inset, inset, inset);

        androidx.appcompat.widget.AppCompatImageView logo = findViewById(R.id.vt_report_logo);
        logo.setColorFilter(accent());
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(Math.max(1, dp(1.5f)), accent());
        logo.setBackground(ring);
        mTitle = findViewById(R.id.vt_report_title);
        mTitle.setTextColor(accent());
        ((TextView) findViewById(R.id.vt_report_subtitle)).setTextColor(dim());
        findViewById(R.id.vt_report_hairline).setBackgroundColor(RowPills.withAlpha(accent(), 0.45f));
        findViewById(R.id.vt_report_hairline_bottom).setBackgroundColor(RowPills.withAlpha(accent(), 0.45f));
        mActions = findViewById(R.id.vt_report_actions);

        mContent = findViewById(R.id.vt_report_content);
        mBackToSummary = new androidx.activity.OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                showPage(PAGE_SUMMARY);
            }
        };
        getOnBackPressedDispatcher().addCallback(this, mBackToSummary);
        mViewModel = new ViewModelProvider(this).get(VtReportViewModel.class);
        mViewModel.getStateLiveData().observe(this, this::render);
        mViewModel.getErrorLiveData().observe(this, msg -> {
            if (msg != null) UIUtils.displayLongToast(msg);
        });
        mViewModel.start(getIntent());
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        mKeyDialogShown.clear();
        mSignature = null;
        if (mViewModel != null) mViewModel.start(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        sVisible = true;
        // The installed version may have changed while we were away (an install just finished).
        if (mViewModel != null) mViewModel.refreshAppFacts();
    }

    @Override
    protected void onPause() {
        sVisible = false;
        super.onPause();
    }

    // ── Rendering ────────────────────────────────────────────────────────────────────────────────

    private static boolean isRunning(@Nullable VtScanAttempt active) {
        return active != null && !VtScanAttempt.STATUS_PENDING_CONSENT.equals(active.status)
                && !VtScanAttempt.STATUS_PENDING_KEY.equals(active.status);
    }

    @NonNull
    private String signatureOf(@NonNull VtReportViewModel.State s) {
        StringBuilder sb = new StringBuilder();
        VtFile f = s.file;
        sb.append(f.id).append('|').append(f.latestReportJson == null ? 0 : f.latestReportJson.length())
                .append('|').append(f.latestAnalysisAt).append('|')
                .append(f.localAnalysisJson == null ? 0 : f.localAnalysisJson.length()).append('|')
                .append(f.displayName).append('|').append(s.realPathString).append('|')
                .append(s.packageName).append('|').append(mShowAllEngines).append('|').append(mPage).append('|')
                .append(s.app == null ? "-" : s.app.kind + ":" + s.app.installedVersionName).append('|');
        appendAttempt(sb, s.active);
        appendAttempt(sb, s.last);
        if (s.bundle) {
            sb.append("B").append(s.bundleScanning).append(s.bundleWorst);
            if (s.bundleParts != null) {
                for (VtReportViewModel.BundlePartView p : s.bundleParts) {
                    sb.append(p.file.id).append(':').append(p.active == null ? "-" : p.active.status)
                            .append(':').append(p.verdict == null ? "-" : p.verdict.level).append(',');
                }
            }
        }
        return sb.toString();
    }

    private static void appendAttempt(@NonNull StringBuilder sb, @Nullable VtScanAttempt a) {
        if (a == null) {
            sb.append("-|");
            return;
        }
        sb.append(a.id).append(':').append(a.status).append(':').append(a.nextAttemptAt)
                .append(':').append(a.analysisId != null).append(':').append(a.errorMessage).append('|');
    }

    private void render(@Nullable VtReportViewModel.State state) {
        if (state == null || mContent == null) return;
        mLastState = state;
        VtFile file = state.file;
        mTitle.setText(file.displayName != null ? file.displayName : file.sha256);
        String signature = signatureOf(state);
        if (signature.equals(mSignature)) {
            updateLive(state);
            return;
        }
        mSignature = signature;
        mLiveGauge = null;
        mLiveTitle = null;
        mLiveDetail = null;
        mLiveElapsed = null;
        mContent.removeAllViews();

        mActions.removeAllViews();
        VtScanAttempt active = state.active;
        boolean running = isRunning(active);
        // What the file IS comes first, in every state: whether this is an update, the version
        // already installed, or a downgrade is worth knowing before anything is uploaded.
        if (mPage == PAGE_SUMMARY || state.report == null) addAppCard(state);
        TextView install = state.realPathString == null ? null
                : action(installLabel(state), v -> installHandoff(state));

        if (active != null && VtScanAttempt.STATUS_PENDING_KEY.equals(active.status)) {
            addStateHero(warn(), "?", getString(R.string.vt_set_up), getString(R.string.vt_needs_key));
            // Fork (白い熊, +113): no key is no reason to refuse the install — a phone where
            // VirusTotal was never set up could not install anything from here.
            setActions(getString(R.string.cancel), v -> finish(), action(getString(R.string.vt_set_up), v -> {
                mKeyDialogShown.remove(active.id);
                maybeShowKeyDialog(active.id);
            }), install);
            maybeShowKeyDialog(active.id);
            addFileHeader(state);
            return;
        }
        if (active != null && VtScanAttempt.STATUS_PENDING_CONSENT.equals(active.status)) {
            // The upload question is asked HERE, once — no second dialog on top of this one.
            addStateHero(accent(), "?", getString(R.string.scan_in_vt),
                    getString(R.string.vt_confirm_uploading_file));
            setActions(getString(R.string.cancel), v -> {
                mViewModel.rejectUpload(active.id);
                finish();
            }, action(getString(R.string.vt_confirm_upload_and_scan),
                    v -> mViewModel.approveUpload(active.id)),
                    // Fork (白い熊, +115): installing without uploading answers the question with
                    // "no" — else the attempt waited for consent for ever, with its notification.
                    state.realPathString == null ? null : action(installLabel(state), v -> {
                        mViewModel.rejectUpload(active.id);
                        installHandoff(state);
                    }));
            addFileHeader(state);
            return;
        }

        if (state.bundle) {
            renderBundle(state);
            return;
        }

        if (state.report != null && mPage != PAGE_SUMMARY) {
            renderFullReport(state);
            return;
        }
        if (mPage != PAGE_SUMMARY) {
            mPage = PAGE_SUMMARY;
            mBackToSummary.setEnabled(false);
        }
        if (state.report != null) {
            View hero = addVerdictHero(state.verdict == null ? VtVerdict.Level.NOT_ANALYSED : state.verdict.level,
                    state.verdict, state.report);
            hero.setOnClickListener(v -> showPage(FILTER_ALL));
            if (running) {
                // A rescan/update of a file that already has a report: the progress sits under it.
                addScanHero(state, true);
                setActions(getString(R.string.close), v -> finish(),
                        action(getString(R.string.vt_cancel_scan), v -> mViewModel.cancelAttempt(active.id)),
                        install);
            } else {
                addLastFailureNote(state);
                setActions(getString(R.string.close), v -> finish(),
                        action(getString(R.string.vt_rescan), v -> mViewModel.rescan()), install);
            }
            addStaleHint(state.verdict, file);
            addStatTiles(state.report);
            addDetections(state.report);
            addVtDetails(state);
            addFileHeader(state);
            addLocalAnalysis(file);
        } else if (running) {
            addScanHero(state, false);
            // Closing never stops a scan (it is a foreground service), so the two ways out are
            // named for what they do.
            setActions(getString(R.string.vt_leave_running), v -> finish(),
                    action(getString(R.string.vt_cancel_scan), v -> mViewModel.cancelAttempt(active.id)));
            addFileHeader(state);
            addLocalAnalysis(file);
        } else {
            // No report and nothing running: say exactly why, and offer the way forward.
            VtScanAttempt last = state.last;
            boolean failed = last != null && VtScanAttempt.STATUS_FAILED.equals(last.status);
            boolean cancelled = last != null && VtScanAttempt.STATUS_CANCELLED.equals(last.status);
            if (failed && VtScanService.CODE_SWITCHED_OFF.equals(last.errorCode)) {
                // Fork (白い熊, +123): a switch on the UI screen kept this file off VirusTotal. That
                // is the setting doing its work — a quiet state with its reason, never the red "!".
                addStateHero(dim(), "?", getString(R.string.vt_state_not_checked),
                        last.errorMessage != null ? last.errorMessage : getString(R.string.vt_switched_off));
            } else if (failed) {
                String reason = last.errorMessage != null && !last.errorMessage.trim().isEmpty()
                        ? last.errorMessage : getString(R.string.vt_state_failed_no_reason);
                if (last.errorCode != null && !reason.contains(last.errorCode)) {
                    reason = reason + "\n(" + last.errorCode + ")";
                }
                addStateHero(danger(), "!", getString(R.string.vt_state_failed), reason);
            } else if (cancelled) {
                // Fork (白い熊, +119): a cancel is no fault — a blue ring around the app's own icon,
                // not a cross. The cross stays only for a file whose icon cannot be read.
                addStateHero(dim(), CANCELLED_RING, state.app == null ? null : state.app.icon, "×",
                        getString(R.string.vt_state_cancelled), getString(R.string.vt_state_cancelled_hint));
            } else {
                addStateHero(dim(), "?", getString(R.string.vt_state_not_scanned),
                        getString(R.string.vt_state_not_scanned_hint));
            }
            setActions(getString(R.string.cancel), v -> finish(),
                    action(getString(failed || cancelled ? R.string.vt_retry : R.string.vt_scan_now),
                            v -> mViewModel.rescan()), install);
            addFileHeader(state);
            addLocalAnalysis(file);
        }
    }

    // ── The foot of the dialog: every decision is made here ──────────────────────────────────────

    /**
     * Fork (白い熊, +082): the actions of the current state in one row of equal buttons — the LAST
     * one is the main action (thick frame) — and a full-width way out beneath them. The install
     * button is ALWAYS the last one, in every state (白い熊, +114): it sat on the left in the
     * no-key, upload-question and not-scanned states and on the right under a report. This replaced
     * the ✕ in the header, the pills scattered through the content and the pop-up that asked the
     * upload question a second time on top of this dialog.
     */
    private void setActions(@NonNull CharSequence dismissLabel, @NonNull View.OnClickListener dismiss,
                            @NonNull TextView... actions) {
        mActions.removeAllViews();
        List<TextView> present = new ArrayList<>();
        for (TextView a : actions) if (a != null) present.add(a);
        if (!present.isEmpty()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < present.size(); ++i) {
                TextView a = present.get(i);
                boolean main = i == present.size() - 1;
                styleAction(a, main);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
                if (i > 0) lp.leftMargin = dp(8);
                row.addView(a, lp);
            }
            mActions.addView(row);
        }
        TextView out = action(dismissLabel, dismiss);
        styleAction(out, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        if (!present.isEmpty()) lp.topMargin = dp(8);
        mActions.addView(out, lp);
    }

    @NonNull
    private TextView action(@NonNull CharSequence label, @NonNull View.OnClickListener listener) {
        TextView tv = new AppCompatTextView(this);
        tv.setText(label);
        tv.setOnClickListener(listener);
        return tv;
    }

    private void styleAction(@NonNull TextView tv, boolean main) {
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        // Fork (+124): the dialog's actions wear the Buttons roles.
        final int frameInk = io.github.muntashirakon.AppManager.utils.Pills.frameFor(this, accent());
        tv.setTextColor(io.github.muntashirakon.AppManager.utils.Pills.textFor(this, accent()));
        tv.setTextSize(15);
        tv.setTypeface(main ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tv.setPadding(dp(10), 0, dp(10), 0);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.TRANSPARENT);
        shape.setCornerRadius(dp(100));
        shape.setStroke(Math.max(1, dp(main ? 3f : 1.25f)), frameInk);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(100));
        tv.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(RowPills.withAlpha(frameInk, 0.25f)), shape, mask));
    }

    @NonNull
    private String installLabel(@NonNull VtReportViewModel.State state) {
        if (state.app == null) return getString(R.string.vt_install);
        switch (state.app.kind) {
            case VtReportViewModel.AppFacts.KIND_UPDATE: return getString(R.string.update);
            case VtReportViewModel.AppFacts.KIND_SAME: return getString(R.string.reinstall);
            case VtReportViewModel.AppFacts.KIND_DOWNGRADE: return getString(R.string.downgrade);
            default: return getString(R.string.vt_install);
        }
    }

    /** The app inside the file: icon, name, package, installed version → this version, and which
     *  of new install / update / same version / downgrade this would be. */
    private void addAppCard(@NonNull VtReportViewModel.State state) {
        VtReportViewModel.AppFacts app = state.app;
        if (app == null) return;
        LinearLayout box = card(accent());
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        if (app.icon != null) {
            androidx.appcompat.widget.AppCompatImageView icon = new androidx.appcompat.widget.AppCompatImageView(this);
            icon.setImageDrawable(app.icon);
            box.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));
        }
        TextView label = text(app.label != null ? app.label : app.packageName, accent(), 22, true);
        label.setGravity(Gravity.CENTER);
        box.addView(label, topMargin(6));
        TextView pkg = text(app.packageName, dim(), 13, false);
        pkg.setGravity(Gravity.CENTER);
        pkg.setTextIsSelectable(true);
        box.addView(pkg);
        boolean same = app.kind == VtReportViewModel.AppFacts.KIND_SAME;
        boolean down = app.kind == VtReportViewModel.AppFacts.KIND_DOWNGRADE;
        if (app.installed) {
            TextView from = text(String.valueOf(app.installedVersionName), dim(), 14, false);
            from.setGravity(Gravity.CENTER);
            box.addView(from, topMargin(6));
            TextView arrow = text(same ? "=" : "↓", dim(), 13, false);
            arrow.setGravity(Gravity.CENTER);
            box.addView(arrow);
        }
        TextView to = text(String.valueOf(app.versionName), accent(), 16, true);
        to.setGravity(Gravity.CENTER);
        box.addView(to, app.installed ? topMargin(0) : topMargin(6));
        // Fork (白い熊, +085): a FILLED pill, a size up — red text on black was barely legible, and
        // this is the line that says "you are about to go backwards". Downgrade = red fill, white
        // ink; same version = green fill; update or new install = the theme yellow; black ink on both.
        // 白い熊, +090: an update is the yellow pill with BLUE ink, which tells it from the yellow
        // "new install" (black ink) without giving up the theme's fill.
        boolean update = app.installed && !same && !down;
        int kindFill = down ? danger() : same ? safe() : accent();
        int kindRes = !app.installed ? R.string.vt_app_kind_new : same ? R.string.vt_app_kind_same
                : down ? R.string.vt_app_kind_downgrade : R.string.vt_app_kind_update;
        TextView kind = text(getString(kindRes), down ? Color.WHITE : update ? 0xFF0D47A1 : Color.BLACK, 17, true);
        kind.setGravity(Gravity.CENTER);
        kind.setPadding(dp(16), dp(6), dp(16), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(kindFill);
        bg.setCornerRadius(dp(100));
        kind.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        box.addView(kind, lp);
        TextView size = text(Formatter.formatShortFileSize(this, state.file.sizeBytes), dim(), 12, false);
        size.setGravity(Gravity.CENTER);
        box.addView(size, topMargin(4));
        mContent.addView(box);
    }

    // ── A running scan ───────────────────────────────────────────────────────────────────────────

    /** What a running attempt is doing right now, in words the dialog can show. */
    private static final class Stage {
        String title = "";
        String detail = "";
        int percent = -1;
        int step = -1;
        boolean waiting;
    }

    @NonNull
    private Stage stageOf(@NonNull VtReportViewModel.State state) {
        Stage st = new Stage();
        VtScanAttempt a = state.active;
        if (a == null) return st;
        boolean rescan = VtScanAttempt.TYPE_RESCAN.equals(a.type);
        long now = System.currentTimeMillis();
        if (a.nextAttemptAt > now) {
            st.waiting = true;
            st.title = a.errorMessage != null ? a.errorMessage : getString(R.string.vt_stage_waiting);
            st.detail = getString(R.string.vt_next_try, DateUtils.formatDateTime(this, a.nextAttemptAt));
        } else if (VtScanAttempt.STATUS_UPLOADING.equals(a.status)) {
            String size = Formatter.formatShortFileSize(this, state.file.sizeBytes);
            if (state.uploadPercent >= 0) {
                st.percent = state.uploadPercent;
                st.title = getString(R.string.vt_stage_upload_pct, state.uploadPercent);
                st.detail = getString(R.string.vt_upload_bytes, Formatter.formatShortFileSize(this,
                        state.file.sizeBytes * state.uploadPercent / 100L), size);
            } else {
                st.title = getString(R.string.vt_stage_upload);
                st.detail = getString(R.string.vt_upload_starting, size);
            }
        } else if (VtScanAttempt.STATUS_ANALYSING.equals(a.status)) {
            st.title = getString(a.analysisId == null && rescan
                    ? R.string.vt_stage_request : R.string.vt_stage_analyse);
            st.detail = getString(R.string.vt_detail_analyse);
        } else if (VtScanAttempt.STATUS_LOOKING_UP.equals(a.status)) {
            if (a.analysisId != null) {
                st.title = getString(R.string.vt_stage_report);
                st.detail = getString(R.string.vt_detail_report);
            } else {
                st.title = getString(R.string.vt_stage_lookup);
                st.detail = getString(R.string.vt_detail_lookup);
            }
        } else {
            st.title = getString(R.string.vt_stage_queued);
            st.detail = getString(R.string.vt_detail_queued);
        }
        // Which step of the ladder is lit.
        if (VtScanAttempt.STATUS_UPLOADING.equals(a.status)) st.step = 1;
        else if (VtScanAttempt.STATUS_ANALYSING.equals(a.status)) st.step = rescan && a.analysisId == null ? 0 : 2;
        else if (VtScanAttempt.STATUS_LOOKING_UP.equals(a.status)) st.step = a.analysisId != null ? 3 : 0;
        if (rescan && st.step > 0) st.step -= 1; // the rescan ladder has no Upload rung
        return st;
    }

    private void addScanHero(@NonNull VtReportViewModel.State state, boolean compact) {
        VtScanAttempt active = state.active;
        if (active == null) return;
        Stage st = stageOf(state);
        LinearLayout box = framedBox(accent(), compact ? 1.25f : 3f);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        VtGaugeView gauge = new VtGaugeView(this);
        gauge.setInk(accent(), dim());
        int size = dp(compact ? 64 : 108);
        row.addView(gauge, new LinearLayout.LayoutParams(size, size));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.leftMargin = dp(14);
        TextView title = text("", accent(), compact ? 15 : 19, true);
        TextView detail = text("", accent(), 13, false);
        TextView elapsed = text("", dim(), 12, false);
        col.addView(title);
        col.addView(detail, topMargin(3));
        col.addView(elapsed, topMargin(3));
        row.addView(col, cp);
        box.addView(row);

        mLiveGauge = gauge;
        mLiveTitle = title;
        mLiveDetail = detail;
        mLiveElapsed = elapsed;
        updateLive(state);

        if (!st.waiting) box.addView(stepLadder(active, st.step), topMargin(12));
        mContent.addView(box);
    }

    /** Touch only the widgets of a running scan — called every poll while nothing structural moved. */
    private void updateLive(@NonNull VtReportViewModel.State state) {
        VtScanAttempt active = state.active;
        if (mLiveGauge == null || active == null) return;
        Stage st = stageOf(state);
        mLiveGauge.showScanning(st.percent, st.percent >= 0 ? st.percent + "%" : (st.waiting ? "…" : ""), "");
        if (mLiveTitle != null) mLiveTitle.setText(st.title);
        if (mLiveDetail != null) mLiveDetail.setText(st.detail);
        if (mLiveElapsed != null) {
            long since = active.startedAt > 0 ? active.startedAt : active.createdAt;
            long sec = Math.max(0, (System.currentTimeMillis() - since) / 1000L);
            mLiveElapsed.setText(getString(R.string.vt_elapsed,
                    String.format(java.util.Locale.ROOT, "%d:%02d", sec / 60, sec % 60)));
        }
    }

    /** Look up → Upload → Analyse → Report, with the rung the scan is standing on lit. */
    @NonNull
    private View stepLadder(@NonNull VtScanAttempt attempt, int current) {
        int[] labels = VtScanAttempt.TYPE_RESCAN.equals(attempt.type)
                ? new int[]{R.string.vt_step_request, R.string.vt_step_analyse, R.string.vt_step_report}
                : new int[]{R.string.vt_step_lookup, R.string.vt_step_upload, R.string.vt_step_analyse,
                R.string.vt_step_report};
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < labels.length; ++i) {
            if (i > 0) {
                View line = new View(this);
                line.setBackgroundColor(i <= current ? accent() : RowPills.withAlpha(dim(), 0.5f));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Math.max(1, dp(1.5f)), 1f);
                lp.leftMargin = dp(4);
                lp.rightMargin = dp(4);
                row.addView(line, lp);
            }
            boolean done = i < current;
            boolean now = i == current;
            TextView tv = text((done ? "✓ " : now ? "● " : "○ ") + getString(labels[i]),
                    done || now ? accent() : dim(), 12, now);
            if (now) {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.TRANSPARENT);
                bg.setCornerRadius(dp(100));
                bg.setStroke(Math.max(1, dp(1.25f)), accent());
                tv.setBackground(bg);
                tv.setPadding(dp(8), dp(3), dp(8), dp(3));
            }
            row.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return row;
    }

    // ── Heroes ───────────────────────────────────────────────────────────────────────────────────

    /** Failed · cancelled · not scanned · needs a key: one ring, a headline and the reason. */
    // Fork (白い熊, +119): the ring of a cancelled scan. Brighter than the update pill's ink
    // (#0D47A1), which is made for a yellow ground and all but disappears on black.
    private static final int CANCELLED_RING = 0xFF2979FF;

    private void addStateHero(@ColorInt int color, @NonNull String glyph, @NonNull CharSequence headline,
                              @NonNull CharSequence message) {
        addStateHero(color, color, null, glyph, headline, message);
    }

    /** @param icon drawn inside the ring in place of {@code glyph} when not null */
    private void addStateHero(@ColorInt int color, @ColorInt int ringColor,
                              @Nullable android.graphics.drawable.Drawable icon, @NonNull String glyph,
                              @NonNull CharSequence headline, @NonNull CharSequence message) {
        LinearLayout box = framedBox(color, 3f);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        VtGaugeView gauge = new VtGaugeView(this);
        gauge.setInk(accent(), dim());
        gauge.showState(ringColor, icon == null ? glyph : "", "");
        if (icon == null) {
            row.addView(gauge, new LinearLayout.LayoutParams(dp(88), dp(88)));
        } else {
            android.widget.FrameLayout holder = new android.widget.FrameLayout(this);
            holder.addView(gauge, new android.widget.FrameLayout.LayoutParams(dp(88), dp(88)));
            androidx.appcompat.widget.AppCompatImageView image = new androidx.appcompat.widget.AppCompatImageView(this);
            // A fresh drawable: the app card above shows the same icon, and one Drawable
            // cannot be laid out in two views.
            android.graphics.drawable.Drawable.ConstantState cs = icon.getConstantState();
            image.setImageDrawable(cs == null ? icon : cs.newDrawable(getResources()).mutate());
            holder.addView(image, new android.widget.FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
            row.addView(holder, new LinearLayout.LayoutParams(dp(88), dp(88)));
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.leftMargin = dp(14);
        col.addView(text(headline, color, 21, true));
        TextView msg = text(message, accent(), 13, false);
        msg.setTextIsSelectable(true);
        col.addView(msg, topMargin(4));
        row.addView(col, cp);
        box.addView(row);
        mContent.addView(box);
    }

    @NonNull
    private View addVerdictHero(@NonNull VtVerdict.Level level, @Nullable VtVerdict verdict,
                                @Nullable VtFileReport report) {
        int color = verdictColor(level);
        LinearLayout box = framedBox(color, 3f);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        VtGaugeView gauge = new VtGaugeView(this);
        gauge.setInk(accent(), dim());
        if (report != null) {
            int[] c = countEngines(report);
            gauge.showResult(new int[]{c[0], c[1], c[2], c[3]},
                    new int[]{flaggedColor(report), warn(), safe(), RowPills.withAlpha(dim(), 0.55f)},
                    (c[0] + c[1]) + "/" + (c[0] + c[1] + c[2] + c[3]), color,
                    getString(R.string.vt_gauge_flagged));
        } else {
            gauge.showState(color, level == VtVerdict.Level.CLEAN ? "✓" : "!", "");
        }
        row.addView(gauge, new LinearLayout.LayoutParams(dp(108), dp(108)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.leftMargin = dp(14);
        col.addView(text(verdictLabel(level), color, 23, true));
        if (verdict != null && verdict.suggestedLabel != null && !verdict.suggestedLabel.isEmpty()) {
            TextView label = RowPills.filledPill(this, verdict.suggestedLabel, color,
                    level == VtVerdict.Level.MALICIOUS ? Color.WHITE : Color.BLACK);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            col.addView(label, lp);
        }
        if (verdict != null && !verdict.reasons.isEmpty()) {
            col.addView(text("• " + TextUtils.join("\n• ", verdict.reasons), accent(), 13, false), topMargin(6));
        }
        row.addView(col, cp);
        if (report != null) {
            TextView more = text("›", color, 26, true);
            row.addView(more, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        box.addView(row);
        mContent.addView(box);
        return box;
    }

    /** flagged (VirusTotal's "malicious") · suspicious · clean (undetected + harmless) · no answer. */
    @NonNull
    private static int[] countEngines(@NonNull VtFileReport report) {
        int[] c = new int[4];
        for (VtAvEngineResult r : report.results) {
            if (r.category == VtAvEngineResult.CAT_MALICIOUS) c[0]++;
            else if (r.category == VtAvEngineResult.CAT_SUSPICIOUS) c[1]++;
            else if (r.category == VtAvEngineResult.CAT_UNDETECTED
                    || r.category == VtAvEngineResult.CAT_HARMLESS) c[2]++;
            else c[3]++;
        }
        return c;
    }

    private void addLastFailureNote(@NonNull VtReportViewModel.State state) {
        VtScanAttempt last = state.last;
        if (last != null && VtScanAttempt.STATUS_FAILED.equals(last.status)) {
            TextView tv = addMuted(getString(R.string.vt_last_update_failed, last.errorMessage != null
                    ? last.errorMessage : getString(R.string.vt_state_failed_no_reason)));
            tv.setTextColor(warn());
            mContent.addView(tv);
        }
    }

    private void addStatTiles(@NonNull VtFileReport report) {
        int[] c = countEngines(report);
        int[] labels = {R.string.vt_stat_flagged, R.string.vt_stat_suspicious, R.string.vt_stat_clean,
                R.string.vt_stat_no_answer};
        int[] colors = {flaggedColor(report), warn(), safe(), dim()};
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 4; ++i) {
            int color = c[i] == 0 && i < 2 ? dim() : colors[i];
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setPadding(dp(4), dp(8), dp(4), dp(8));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(RowPills.withAlpha(color, 0.10f));
            bg.setCornerRadius(dp(12));
            bg.setStroke(Math.max(1, dp(1.25f)), RowPills.withAlpha(color, 0.75f));
            tile.setBackground(bg);
            TextView number = text(String.valueOf(c[i]), color, 22, true);
            number.setGravity(Gravity.CENTER);
            TextView label = text(getString(labels[i]) + " ›", color, 11, false);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            tile.addView(number);
            tile.addView(label);
            // Each tile opens the full report on its own group of engines.
            final int filter = i;
            tile.setOnClickListener(v -> showPage(filter));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(8);
            row.addView(tile, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(6);
        mContent.addView(row, lp);
    }

    // ── The full report (a second page of this dialog) ───────────────────────────────────────────

    private void showPage(int page) {
        mPage = page;
        mBackToSummary.setEnabled(page != PAGE_SUMMARY);
        mSignature = null;
        if (mLastState != null) render(mLastState);
        ((androidx.core.widget.NestedScrollView) findViewById(R.id.vt_report_scroller)).scrollTo(0, 0);
    }

    private int categoryOf(@NonNull VtAvEngineResult r) {
        if (r.category == VtAvEngineResult.CAT_MALICIOUS) return 0;
        if (r.category == VtAvEngineResult.CAT_SUSPICIOUS) return 1;
        if (r.category == VtAvEngineResult.CAT_UNDETECTED || r.category == VtAvEngineResult.CAT_HARMLESS) return 2;
        return 3;
    }

    @ColorInt
    private int categoryColor(int category) {
        switch (category) {
            case 0: return danger();
            case 1: return warn();
            case 2: return safe();
            default: return dim();
        }
    }

    /**
     * Fork: the colour of the "Flagged" bucket is the worst class INSIDE it, by our own reading of
     * each label ({@link VtVerdict#classify}) — red only when at least one engine names something
     * other than riskware/patcher/PUA noise. VirusTotal files every detection under "malicious"
     * whatever the label says, so colouring by that raw category painted a red "Malicious 1"
     * beside a verdict card that read "Riskware only" for the very same engine.
     */
    @ColorInt
    private int flaggedColor(@NonNull VtFileReport report) {
        for (VtAvEngineResult r : report.results) {
            if (r.category == VtAvEngineResult.CAT_MALICIOUS
                    && VtVerdict.classify(r.result) != VtVerdict.LabelClass.NOISE) {
                return danger();
            }
        }
        return warn();
    }

    /**
     * Everything VirusTotal returned, in colour: a filter strip over the engines (all · flagged ·
     * suspicious · clean · no answer), one row per engine with its verdict, label, version and
     * signature date, and — on "All" — the threat classification, sandbox verdicts, permissions
     * and signing certificate VirusTotal extracted from the file.
     */
    private void renderFullReport(@NonNull VtReportViewModel.State state) {
        VtFileReport report = state.report;
        if (report == null) return;
        setActions(getString(R.string.close), v -> finish(),
                action("‹ " + getString(R.string.vt_back_to_summary), v -> showPage(PAGE_SUMMARY)));
        int[] counts = countEngines(report);
        int total = counts[0] + counts[1] + counts[2] + counts[3];

        // Filter strip.
        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        int[] labels = {R.string.vt_stat_flagged, R.string.vt_stat_suspicious, R.string.vt_stat_clean,
                R.string.vt_stat_no_answer};
        strip.addView(filterChip(getString(R.string.vt_filter_all) + "  " + total, accent(), FILTER_ALL));
        for (int i = 0; i < 4; ++i) {
            strip.addView(filterChip(getString(labels[i]) + "  " + counts[i],
                    i == 0 ? flaggedColor(report) : categoryColor(i), i));
        }
        scroller.addView(strip);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(8);
        sp.bottomMargin = dp(4);
        mContent.addView(scroller, sp);

        if (mPage == FILTER_ALL) addFullExtras(state);

        // Engines.
        List<VtAvEngineResult> results = new ArrayList<>(report.results);
        Collections.sort(results, DETECTION_ORDER);
        mContent.addView(sectionTitle(getString(R.string.vt_full_engines)));
        int shown = 0;
        for (VtAvEngineResult r : results) {
            int category = categoryOf(r);
            if (mPage != FILTER_ALL && mPage != category) continue;
            mContent.addView(engineRow(r, category));
            shown++;
        }
        if (shown == 0) addMutedLine(getString(R.string.vt_full_none_in_category));
    }

    @NonNull
    private TextView filterChip(@NonNull CharSequence label, @ColorInt int color, int filter) {
        boolean selected = mPage == filter;
        TextView chip = text(label, selected ? io.github.muntashirakon.AppManager.utils.ForkPalette.get(io.github.muntashirakon.AppManager.utils.ForkPalette.GROUND) : color, 13, selected);
        chip.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(100));
        bg.setColor(selected ? color : Color.TRANSPARENT);
        bg.setStroke(Math.max(1, dp(1.25f)), color);
        chip.setBackground(bg);
        chip.setOnClickListener(v -> showPage(filter));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        chip.setLayoutParams(lp);
        return chip;
    }

    @NonNull
    private TextView sectionTitle(@NonNull CharSequence title) {
        TextView tv = text(title, accent(), 16, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(4);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** One engine: a bar in its verdict's colour, its name, what it said, and how fresh it is. */
    @NonNull
    private View engineRow(@NonNull VtAvEngineResult r, int category) {
        int color = categoryColor(category);
        if (category <= 1 && VtVerdict.classify(r.result) == VtVerdict.LabelClass.NOISE) color = warn();
        String word;
        switch (r.category) {
            // Fork: our class of the label, never VirusTotal's raw category name.
            case VtAvEngineResult.CAT_MALICIOUS:
                word = getString(VtVerdict.classify(r.result) == VtVerdict.LabelClass.NOISE
                        ? R.string.vt_engine_riskware : R.string.vt_engine_malware);
                break;
            case VtAvEngineResult.CAT_SUSPICIOUS: word = getString(R.string.vt_stat_suspicious); break;
            case VtAvEngineResult.CAT_UNDETECTED:
            case VtAvEngineResult.CAT_HARMLESS: word = getString(R.string.vt_engine_clean); break;
            case VtAvEngineResult.CAT_TIMEOUT: word = getString(R.string.vt_engine_timeout); break;
            case VtAvEngineResult.CAT_UNSUPPORTED: word = getString(R.string.vt_engine_unsupported); break;
            default: word = getString(R.string.vt_engine_failure);
        }
        List<String> meta = new ArrayList<>();
        if (r.engineVersion != null && !r.engineVersion.isEmpty()) meta.add(r.engineVersion);
        if (r.engineUpdate != null && r.engineUpdate.length() == 8) {
            meta.add(getString(R.string.vt_engine_updated, r.engineUpdate.substring(0, 4) + "-"
                    + r.engineUpdate.substring(4, 6) + "-" + r.engineUpdate.substring(6)));
        }
        return coloredRow(color, r.engineName, word, r.result, meta.isEmpty() ? null : TextUtils.join(" · ", meta));
    }

    /** The full report's row shape: colour bar · bold title with a verdict pill · detail · meta. */
    @NonNull
    private View coloredRow(@ColorInt int color, @NonNull CharSequence title, @Nullable CharSequence pill,
                            @Nullable CharSequence detail, @Nullable CharSequence meta) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(RowPills.withAlpha(color, 0.08f));
        bg.setCornerRadius(dp(10));
        row.setBackground(bg);
        row.setClipToOutline(true);
        View bar = new View(this);
        bar.setBackgroundColor(color);
        row.addView(bar, new LinearLayout.LayoutParams(dp(5), ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), dp(7), dp(10), dp(7));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(title, accent(), 14, true);
        head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (pill != null) {
            TextView tag = text(pill, color, 11, true);
            tag.setPadding(dp(8), dp(2), dp(8), dp(2));
            GradientDrawable tb = new GradientDrawable();
            tb.setCornerRadius(dp(100));
            tb.setColor(RowPills.withAlpha(color, 0.16f));
            tb.setStroke(Math.max(1, dp(1f)), color);
            tag.setBackground(tb);
            head.addView(tag);
        }
        col.addView(head);
        if (detail != null && detail.length() > 0) {
            TextView d = text(detail, color, 13, false);
            d.setTextIsSelectable(true);
            col.addView(d, topMargin(2));
        }
        if (meta != null && meta.length() > 0) col.addView(text(meta, dim(), 11, false), topMargin(2));
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(5);
        row.setLayoutParams(lp);
        return row;
    }

    /**
     * Fork: whether everything a sandbox named is riskware-class. A sandbox that names nothing at
     * all is NOT noise — a bare "malicious" with no label is the sandbox's whole statement, where
     * for an engine a missing label is merely a missing detail.
     */
    private static boolean sandboxIsNoise(@NonNull JSONObject verdict) {
        boolean any = false;
        for (String key : new String[]{"malware_classification", "malware_names"}) {
            JSONArray arr = verdict.optJSONArray(key);
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); ++i) {
                String label = arr.optString(i, "");
                if (label.isEmpty()) continue;
                any = true;
                String l = label.toLowerCase(java.util.Locale.ROOT);
                boolean grey = l.contains("greyware") || l.contains("grayware");
                if (!grey && VtVerdict.classify(label) != VtVerdict.LabelClass.NOISE) return false;
            }
        }
        return any;
    }

    /** What VirusTotal says beyond the engines — shown on the "All" filter. */
    private void addFullExtras(@NonNull VtReportViewModel.State state) {
        JSONObject attrs = state.attrs;
        if (attrs == null) return;
        // Threat classification: the categories and family names the engines agree on.
        JSONObject ptc = attrs.optJSONObject("popular_threat_classification");
        if (ptc != null) {
            boolean any = false;
            for (String key : new String[]{"popular_threat_category", "popular_threat_name"}) {
                JSONArray arr = ptc.optJSONArray(key);
                if (arr == null) continue;
                for (int i = 0; i < arr.length(); ++i) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    if (!any) {
                        mContent.addView(sectionTitle(getString(R.string.vt_full_threats)));
                        any = true;
                    }
                    String value = o.optString("value", "?");
                    int color = VtVerdict.classify(value) == VtVerdict.LabelClass.NOISE ? warn() : danger();
                    mContent.addView(coloredRow(color, value, "×" + o.optInt("count"), null, null));
                }
            }
        }
        JSONObject sandboxes = attrs.optJSONObject("sandbox_verdicts");
        if (sandboxes != null && sandboxes.length() > 0) {
            mContent.addView(sectionTitle(getString(R.string.vt_full_sandboxes)));
            for (java.util.Iterator<String> it = sandboxes.keys(); it.hasNext(); ) {
                String name = it.next();
                JSONObject v = sandboxes.optJSONObject(name);
                if (v == null) continue;
                String category = v.optString("category", "?");
                // Fork: as for the engines — our class of what the sandbox named, never
                // VirusTotal's raw category word. A "malicious" run whose every classification
                // and name is riskware-class reads Riskware, in amber.
                int color;
                String word;
                if ("malicious".equals(category)) {
                    boolean noise = sandboxIsNoise(v);
                    color = noise ? warn() : danger();
                    word = getString(noise ? R.string.vt_engine_riskware : R.string.vt_engine_malware);
                } else if ("suspicious".equals(category)) {
                    color = warn();
                    word = getString(R.string.vt_stat_suspicious);
                } else if ("harmless".equals(category) || "undetected".equals(category)) {
                    color = safe();
                    word = getString(R.string.vt_engine_clean);
                } else {
                    color = dim();
                    word = category;
                }
                mContent.addView(coloredRow(color, name, word,
                        joinArray(v.optJSONArray("malware_classification"), 8), null));
            }
        }
        JSONObject droid = attrs.optJSONObject("androguard");
        if (droid != null) {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            for (String[] kv : new String[][]{{"Package", "Package"}, {"AndroidVersionName", "Version"},
                    {"MinSdkVersion", "Min SDK"}, {"TargetSdkVersion", "Target SDK"},
                    {"main_activity", "Main activity"}}) {
                String value = droid.optString(kv[0], "");
                if (!value.isEmpty()) line(sb, kv[1] + ":", value);
            }
            if (sb.length() > 0) {
                mContent.addView(sectionTitle(getString(R.string.vt_full_app)));
                LinearLayout box = card(accent());
                TextView tv = body(sb);
                tv.setTextIsSelectable(true);
                box.addView(tv);
                mContent.addView(box);
            }
            JSONObject cert = droid.optJSONObject("certificate");
            if (cert != null) {
                SpannableStringBuilder cb = new SpannableStringBuilder();
                JSONObject subject = cert.optJSONObject("Subject");
                if (subject != null) {
                    String dn = subject.optString("DN", subject.optString("CN", ""));
                    if (!dn.isEmpty()) line(cb, "Subject:", dn);
                }
                for (String[] kv : new String[][]{{"validfrom", "Valid from"}, {"validto", "Valid to"},
                        {"thumbprint", "SHA-1"}}) {
                    String value = cert.optString(kv[0], "");
                    if (!value.isEmpty()) line(cb, kv[1] + ":", value);
                }
                if (cb.length() > 0) {
                    mContent.addView(sectionTitle(getString(R.string.vt_full_certificate)));
                    LinearLayout box = card(accent());
                    TextView tv = body(cb);
                    tv.setTextIsSelectable(true);
                    box.addView(tv);
                    mContent.addView(box);
                }
            }
            // Permissions, the dangerous ones first and in red.
            JSONObject perms = droid.optJSONObject("permission_details");
            if (perms != null && perms.length() > 0) {
                List<String> names = new ArrayList<>();
                for (java.util.Iterator<String> it = perms.keys(); it.hasNext(); ) names.add(it.next());
                Collections.sort(names, (a, b) -> {
                    int ra = permRank(perms.optJSONObject(a));
                    int rb = permRank(perms.optJSONObject(b));
                    return ra != rb ? Integer.compare(ra, rb) : a.compareTo(b);
                });
                mContent.addView(sectionTitle(getString(R.string.vt_full_permissions) + "  " + names.size()));
                for (String name : names) {
                    JSONObject d = perms.optJSONObject(name);
                    String type = d == null ? "" : d.optString("permission_type", "");
                    int rank = permRank(d);
                    int color = rank == 0 ? danger() : rank == 1 ? warn() : dim();
                    String shortName = name.startsWith("android.permission.")
                            ? name.substring("android.permission.".length()) : name;
                    mContent.addView(coloredRow(color, shortName, type.isEmpty() ? null : type,
                            d == null ? null : d.optString("short_description", ""), null));
                }
            }
        }
    }

    private static int permRank(@Nullable JSONObject details) {
        String type = details == null ? "" : details.optString("permission_type", "");
        if (type.contains("dangerous")) return 0;
        if (type.contains("signature") || type.contains("privileged")) return 1;
        return 2;
    }

    // Item 3: the bundle container — worst part's verdict on top, the parts listed beneath.
    private void renderBundle(@NonNull VtReportViewModel.State state) {
        VtVerdict.Level worst = state.bundleWorst;
        if (worst != null) {
            addVerdictHero(worst, null, null);
        } else if (state.bundleScanning) {
            addStateHero(accent(), "…", getString(R.string.vt_bundle_unpacking), getString(R.string.vt_updating));
        } else {
            addStateHero(dim(), "?", getString(R.string.vt_state_not_scanned),
                    getString(R.string.vt_state_not_scanned_hint));
        }
        if (worst != null && state.bundleScanning) addMutedLine(getString(R.string.vt_updating));
        setActions(getString(R.string.close), v -> finish(),
                action(getString(R.string.vt_rescan), v -> mViewModel.rescanBundle()),
                state.realPathString == null ? null
                        : action(getString(R.string.vt_install), v -> installHandoff(state)));
        LinearLayout box = card(accent());
        box.addView(heading(getString(R.string.vt_bundle_parts)));
        List<VtReportViewModel.BundlePartView> parts = state.bundleParts;
        if (parts == null || parts.isEmpty()) {
            box.addView(body(getString(R.string.vt_bundle_part_count, 0)));
        } else {
            for (VtReportViewModel.BundlePartView part : parts) {
                box.addView(bundlePartRow(part));
            }
        }
        mContent.addView(box);
        addFileHeader(state);
    }

    @NonNull
    private TextView bundlePartRow(@NonNull VtReportViewModel.BundlePartView part) {
        VtVerdict.Level level = part.verdict != null ? part.verdict.level : null;
        CharSequence label = part.active != null ? getString(R.string.vt_checking)
                : level != null ? verdictLabel(level) : getString(R.string.vt_report_unavailable);
        int color = level != null ? verdictColor(level) : dim();
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(getPrimaryText(this, part.file.displayName != null ? part.file.displayName : part.file.sha256));
        sb.append("\n").append(getColoredText(getSmallerText(label), color));
        TextView tv = body(sb);
        tv.setPadding(0, dp(6), 0, dp(6));
        tv.setOnClickListener(v -> startActivity(intentForFile(this, part.file.id)));
        return tv;
    }

    // Item 7: offer a rescan when the stored analysis is older than the configured age.
    private void addStaleHint(@Nullable VtVerdict verdict, @NonNull VtFile file) {
        long lastAnalysed = verdict != null && verdict.lastAnalysisDate > 0
                ? verdict.lastAnalysisDate : file.latestAnalysisAt;
        if (lastAnalysed <= 0) return;
        long days = (System.currentTimeMillis() - lastAnalysed) / (24 * 60 * 60_000L);
        if (days >= Prefs.VirusTotal.getRescanAgeDays()) {
            addMutedLine(getString(R.string.vt_report_stale, (int) days));
        }
    }

    /** Everything VirusTotal says about the file beyond the engine verdicts. */
    private void addVtDetails(@NonNull VtReportViewModel.State state) {
        VtFile file = state.file;
        VtVerdict verdict = state.verdict;
        JSONObject attrs = state.attrs;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (verdict != null && verdict.suggestedLabel != null && !verdict.suggestedLabel.isEmpty()) {
            line(sb, getString(R.string.vt_detail_threat_label), verdict.suggestedLabel);
        }
        if (attrs != null) {
            String type = attrs.optString("type_description", "");
            if (!type.isEmpty()) line(sb, getString(R.string.vt_detail_type), type);
        }
        long firstSeen = verdict != null ? verdict.firstSubmissionDate : 0;
        long lastAnalysed = verdict != null && verdict.lastAnalysisDate > 0
                ? verdict.lastAnalysisDate : file.latestAnalysisAt;
        if (firstSeen > 0) {
            line(sb, getString(R.string.vt_first_seen),
                    DateUtils.formatDateTime(this, firstSeen) + "  " + ageString(firstSeen));
        }
        if (lastAnalysed > 0) {
            line(sb, getString(R.string.vt_last_analysed),
                    DateUtils.formatDateTime(this, lastAnalysed) + "  " + ageString(lastAnalysed));
        }
        if (attrs != null) {
            int submitted = attrs.optInt("times_submitted", 0);
            if (submitted > 0) line(sb, getString(R.string.vt_detail_submissions), String.valueOf(submitted));
            JSONObject votes = attrs.optJSONObject("total_votes");
            if (votes != null && (votes.optInt("harmless") > 0 || votes.optInt("malicious") > 0)) {
                line(sb, getString(R.string.vt_detail_votes), getString(R.string.vt_detail_votes_value,
                        votes.optInt("harmless"), votes.optInt("malicious")));
            }
            if (attrs.has("reputation") && attrs.optInt("reputation") != 0) {
                line(sb, getString(R.string.vt_detail_reputation), String.valueOf(attrs.optInt("reputation")));
            }
            String names = joinArray(attrs.optJSONArray("names"), 4);
            if (names != null) line(sb, getString(R.string.vt_detail_names), names);
            String tags = joinArray(attrs.optJSONArray("tags"), 24);
            if (tags != null) line(sb, getString(R.string.vt_detail_tags), tags);
            JSONObject sandboxes = attrs.optJSONObject("sandbox_verdicts");
            if (sandboxes != null && sandboxes.length() > 0) {
                List<String> parts = new ArrayList<>();
                for (java.util.Iterator<String> it = sandboxes.keys(); it.hasNext(); ) {
                    String name = it.next();
                    JSONObject v = sandboxes.optJSONObject(name);
                    if (v != null) parts.add(name + ": " + v.optString("category", "?"));
                }
                if (!parts.isEmpty()) line(sb, getString(R.string.vt_detail_sandboxes), TextUtils.join(", ", parts));
            }
        }
        LinearLayout box = card(accent());
        box.addView(heading(getString(R.string.vt_details_title)));
        if (sb.length() > 0) {
            TextView tv = body(sb);
            tv.setTextIsSelectable(true);
            box.addView(tv);
        }
        String permalink = file.permalink != null ? file.permalink : VirusTotal.getPermalink(file.sha256);
        TextView open = regularPill(getString(R.string.vt_open_on_vt) + " ›", v -> openUrl(permalink));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        box.addView(open, lp);
        mContent.addView(box);
    }

    private void line(@NonNull SpannableStringBuilder sb, @NonNull String label, @NonNull CharSequence value) {
        if (sb.length() > 0) sb.append("\n");
        sb.append(getPrimaryText(this, label + " ")).append(value);
    }

    @Nullable
    private static String joinArray(@Nullable JSONArray array, int max) {
        if (array == null || array.length() == 0) return null;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.length() && i < max; ++i) {
            String s = array.optString(i, "");
            if (!s.isEmpty()) out.add(s);
        }
        if (out.isEmpty()) return null;
        String joined = TextUtils.join(", ", out);
        return array.length() > max ? joined + " …" : joined;
    }

    private void addFileHeader(@NonNull VtReportViewModel.State state) {
        VtFile file = state.file;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(getPrimaryText(this, getString(R.string.file_name) + ": "))
                .append(file.displayName != null ? file.displayName : file.sha256).append("\n");
        sb.append(getPrimaryText(this, getString(R.string.size) + ": "))
                .append(Formatter.formatShortFileSize(this, file.sizeBytes));
        appendHash(sb, "SHA-256", file.sha256);
        appendHash(sb, "SHA-1", file.sha1);
        appendHash(sb, "MD5", file.md5);
        LinearLayout box = card(accent());
        box.addView(heading(getString(R.string.file_information)));
        TextView tv = body(sb);
        tv.setTextIsSelectable(true);
        box.addView(tv);
        mContent.addView(box);
    }

    private void appendHash(@NonNull SpannableStringBuilder sb, @NonNull String name, @Nullable String value) {
        if (value == null) return;
        sb.append("\n").append(getPrimaryText(this, name + ": "))
                .append(getSmallerText(UIUtils.getMonospacedText(value)));
    }

    /** Every engine's answer: the flagged ones always, the clean and silent ones behind one pill. */
    private void addDetections(@NonNull VtFileReport report) {
        List<VtAvEngineResult> results = new ArrayList<>(report.results);
        Collections.sort(results, DETECTION_ORDER);
        List<VtAvEngineResult> detections = new ArrayList<>();
        List<VtAvEngineResult> others = new ArrayList<>();
        int undetected = 0;
        int inconclusive = 0;
        for (VtAvEngineResult r : results) {
            if (r.category == VtAvEngineResult.CAT_MALICIOUS || r.category == VtAvEngineResult.CAT_SUSPICIOUS) {
                detections.add(r);
            } else {
                others.add(r);
                if (r.category == VtAvEngineResult.CAT_UNDETECTED || r.category == VtAvEngineResult.CAT_HARMLESS) {
                    undetected++;
                } else {
                    inconclusive++;
                }
            }
        }
        LinearLayout box = card(accent());
        box.addView(heading(getString(R.string.vt_engine_results)));
        if (detections.isEmpty()) {
            TextView none = body(getString(R.string.vt_no_detections));
            none.setTextColor(safe());
            box.addView(none);
        } else {
            box.addView(text(getString(R.string.vt_engines_flagged_heading, detections.size()), dim(), 12, false));
            SpannableStringBuilder sb = new SpannableStringBuilder();
            for (VtAvEngineResult r : detections) {
                VtVerdict.LabelClass lc = VtVerdict.classify(r.result);
                int color = lc == VtVerdict.LabelClass.NOISE ? warn() : danger();
                if (sb.length() > 0) sb.append("\n");
                sb.append(getColoredText(getPrimaryText(this, "● " + r.engineName), color));
                if (r.result != null) {
                    sb.append(getSmallerText("  " + r.result));
                }
            }
            TextView tv = body(sb);
            tv.setTextIsSelectable(true);
            tv.setLineSpacing(dp(2), 1f);
            box.addView(tv);
        }
        box.addView(addMuted(getString(R.string.vt_engines_summary, undetected, inconclusive)));
        if (!others.isEmpty()) {
            if (mShowAllEngines) {
                SpannableStringBuilder sb = new SpannableStringBuilder();
                for (VtAvEngineResult r : others) {
                    boolean clean = r.category == VtAvEngineResult.CAT_UNDETECTED
                            || r.category == VtAvEngineResult.CAT_HARMLESS;
                    String word = clean ? getString(R.string.vt_engine_clean)
                            : r.category == VtAvEngineResult.CAT_TIMEOUT ? getString(R.string.vt_engine_timeout)
                            : r.category == VtAvEngineResult.CAT_UNSUPPORTED
                            ? getString(R.string.vt_engine_unsupported) : getString(R.string.vt_engine_failure);
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(getColoredText((clean ? "✓ " : "– ") + r.engineName, clean ? safe() : dim()));
                    sb.append(getColoredText(getSmallerText("  " + word), dim()));
                }
                TextView tv = body(sb);
                tv.setLineSpacing(dp(2), 1f);
                box.addView(tv, topMargin(6));
            }
            TextView toggle = regularPill(mShowAllEngines ? getString(R.string.vt_engines_hide_all)
                    : getString(R.string.vt_engines_show_all, results.size()) + " ›", v -> showPage(FILTER_ALL));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            box.addView(toggle, lp);
        }
        mContent.addView(box);
    }

    private void addLocalAnalysis(@NonNull VtFile file) {
        if (file.localAnalysisJson == null) return;
        try {
            JSONObject local = new JSONObject(file.localAnalysisJson);
            JSONArray trackers = local.optJSONArray("trackers");
            JSONArray libraries = local.optJSONArray("libraries");
            SpannableStringBuilder sb = new SpannableStringBuilder();
            sb.append(getPrimaryText(this, getString(R.string.trackers) + ": "))
                    .append(String.valueOf(trackers == null ? 0 : trackers.length())).append("\n");
            sb.append(getPrimaryText(this, getString(R.string.libraries_title) + ": "))
                    .append(String.valueOf(libraries == null ? 0 : libraries.length()));
            if (trackers != null && trackers.length() > 0) {
                List<String> names = new ArrayList<>();
                for (int i = 0; i < trackers.length(); ++i) {
                    JSONObject t = trackers.optJSONObject(i);
                    if (t != null) names.add(t.optString("label"));
                }
                sb.append("\n").append(getSmallerText(TextUtils.join(", ", names)));
            }
            LinearLayout box = card(accent());
            box.addView(heading(getString(R.string.vt_local_analysis)));
            box.addView(body(sb));
            mContent.addView(box);
        } catch (Throwable ignored) {
        }
    }

    // ── Install hand-off (白い熊, APK-vetting chain, item 6; installer-chat constraints) ──────────

    private void installHandoff(@NonNull VtReportViewModel.State state) {
        installFile(this, state.file, state.realPath, state.realPathString, true);
        // Fork (白い熊, +082/+083): the decision was made here and our installer carries on without
        // asking again, so this dialog has nothing left to say — leave the screen to the install.
        if (state.realPath != null) finish();
    }

    /**
     * Fork (白い熊, +083): install the reviewed file HERE, with this app's own installer. The
     * hand-off to the separate universal installer (APK-vetting chain, item 6) is retired: it put a
     * second app and a second confirmation between the decision and the install, and it bypassed
     * the per-app install source (+055), which only applies when this app owns the session.
     *
     * @param confirmed the user saw installed → new version and pressed the install button in the
     *                  report dialog, so the installer does not ask again. False from the history
     *                  list, whose row shows neither.
     */
    public static void installFile(@NonNull android.app.Activity activity, @NonNull VtFile file,
                                   @Nullable Path realPath, @Nullable String realPathString,
                                   boolean confirmed) {
        if (realPath == null) {
            UIUtils.displayShortToast(R.string.vt_install_unavailable);
            return;
        }
        Uri content = FmProvider.getContentUri(realPath);
        if (!FeatureController.isInstallerEnabled()) {
            // Switched off by hand since the one-shot migration: an install asked for here is an
            // explicit request for it, so switch it back on rather than refuse.
            FeatureController.getInstance().modifyState(FeatureController.FEAT_INSTALLER, true);
        }
        Context context = activity.getApplicationContext();
        activity.startActivity(confirmed
                ? PackageInstallerActivity.getConfirmedInstance(context, content)
                : PackageInstallerActivity.getLaunchableInstance(context, content));
    }

    // ── Dialogs ──────────────────────────────────────────────────────────────────────────────────

    private void maybeShowKeyDialog(long attemptId) {
        if (!mKeyDialogShown.add(attemptId)) return;
        VtApiKeyDialog.prompt(this, () -> {
            if (mViewModel != null) mViewModel.resume();
        });
    }

    private void openUrl(@Nullable String url) {
        if (url == null) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            if (intent.resolveActivity(getPackageManager()) != null) startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    // ── Small view helpers ─────────────────────────────────────────────────────────────────────

    private static final Comparator<VtAvEngineResult> DETECTION_ORDER = (a, b) -> {
        int ra = detectionRank(a);
        int rb = detectionRank(b);
        if (ra != rb) return Integer.compare(ra, rb);
        return a.engineName.compareToIgnoreCase(b.engineName);
    };

    private static int detectionRank(@NonNull VtAvEngineResult r) {
        if (r.category == VtAvEngineResult.CAT_MALICIOUS || r.category == VtAvEngineResult.CAT_SUSPICIOUS) {
            VtVerdict.LabelClass lc = VtVerdict.classify(r.result);
            switch (lc) {
                case SEVERE: return 0;
                case DROPPER_SMS: return 1;
                case OTHER: return 2;
                default: return 3; // NOISE
            }
        }
        if (r.category == VtAvEngineResult.CAT_UNDETECTED || r.category == VtAvEngineResult.CAT_HARMLESS) {
            return 4;
        }
        return 5;
    }

    @NonNull
    private LinearLayout card(@ColorInt int strokeColor) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(dp(14));
        bg.setStroke(Math.max(1, dp(1.25f)), RowPills.withAlpha(strokeColor, 0.6f));
        box.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(6);
        box.setLayoutParams(lp);
        return box;
    }

    /** The hero box: a faint wash of its own colour inside a full-strength frame. */
    @NonNull
    private LinearLayout framedBox(@ColorInt int color, float strokeDp) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{RowPills.withAlpha(color, 0.16f), RowPills.withAlpha(color, 0.03f)});
        bg.setCornerRadius(dp(18));
        bg.setStroke(Math.max(1, dp(strokeDp)), color);
        box.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(6);
        box.setLayoutParams(lp);
        return box;
    }

    @NonNull
    private TextView text(@NonNull CharSequence text, @ColorInt int color, float sp, boolean bold) {
        TextView tv = new AppCompatTextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(sp);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    @NonNull
    private LinearLayout.LayoutParams topMargin(float dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(dp);
        return lp;
    }

    @NonNull
    private TextView heading(@NonNull CharSequence text) {
        TextView tv = text(text, accent(), 15, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        tv.setLayoutParams(lp);
        return tv;
    }

    @NonNull
    private TextView body(@NonNull CharSequence text) {
        return text(text, accent(), 14, false);
    }

    @NonNull
    private TextView addMuted(@NonNull CharSequence text) {
        TextView tv = text(text, dim(), 13, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void addMutedLine(@NonNull CharSequence text) {
        mContent.addView(addMuted(text));
    }

    @NonNull
    private TextView mainPill(@NonNull CharSequence text, @NonNull View.OnClickListener listener) {
        TextView pill = RowPills.actionPill(this, text, 0, accent(), false);
        // Main action: a 3 dp frame (the pill rule), still unfilled.
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(dp(100));
        bg.setStroke(Math.max(2, dp(3)), accent());
        pill.setBackground(bg);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setOnClickListener(listener);
        return pill;
    }

    @NonNull
    private TextView regularPill(@NonNull CharSequence text, @NonNull View.OnClickListener listener) {
        TextView pill = RowPills.actionPill(this, text, 0, accent(), false);
        pill.setTypeface(Typeface.DEFAULT);
        pill.setOnClickListener(listener);
        return pill;
    }

    @ColorInt
    private int accent() {
        return ForkThemeUtils.getTextColor();
    }

    /**
     * Fork (白い熊, +081): the quiet ink. It used to be {@code getListItemDefaultIndicatorColor}, which
     * is a SURFACE colour — near-black on this black page — so every "muted" line, the Cancel pill
     * and the whole "Report unavailable" card were drawn invisibly. The fork's quiet ink is the
     * settable dim gold.
     */
    @ColorInt
    private int dim() {
        return io.github.muntashirakon.AppManager.fonts.ColorPrefs.getColor(this,
                io.github.muntashirakon.AppManager.fonts.ColorPrefs.SETTINGS_DESCRIPTION);
    }

    @ColorInt
    private int danger() {
        return ColorCodes.getVirusTotalExtremelyUnsafeIndicatorColor(this);
    }

    @ColorInt
    private int warn() {
        return ColorCodes.getVirusTotalUnsafeIndicatorColor(this);
    }

    @ColorInt
    private int safe() {
        return ColorCodes.getVirusTotalSafeIndicatorColor(this);
    }

    @ColorInt
    private int verdictColor(@NonNull VtVerdict.Level level) {
        switch (level) {
            case MALICIOUS:
                return danger();
            case SUSPICIOUS:
                return warn();
            case CLEAN:
            case CLEAN_PROVISIONAL:
                return safe();
            case RISKWARE:
                return accent();
            default:
                return dim();
        }
    }

    @NonNull
    private String verdictLabel(@NonNull VtVerdict.Level level) {
        switch (level) {
            case MALICIOUS: return getString(R.string.vt_verdict_malicious);
            case SUSPICIOUS: return getString(R.string.vt_verdict_suspicious);
            case RISKWARE: return getString(R.string.vt_verdict_riskware);
            case CLEAN: return getString(R.string.vt_verdict_clean);
            case CLEAN_PROVISIONAL: return getString(R.string.vt_verdict_clean_provisional);
            case INCOMPLETE: return getString(R.string.vt_verdict_incomplete);
            default: return getString(R.string.vt_verdict_not_analysed);
        }
    }

    @NonNull
    private String ageString(long time) {
        long diff = System.currentTimeMillis() - time;
        if (diff < 0) return "";
        long days = diff / (24 * 60 * 60_000L);
        if (days >= 1) return getResources().getQuantityString(R.plurals.vt_days_ago, (int) days, (int) days);
        long hours = diff / (60 * 60_000L);
        if (hours >= 1) return getResources().getQuantityString(R.plurals.vt_hours_ago, (int) hours, (int) hours);
        return getString(R.string.vt_just_now);
    }

    private int dp(float value) {
        return Math.round(ForkThemeUtils.dpToPx(this, value));
    }

    // ── View model ───────────────────────────────────────────────────────────────────────────────

    public static class VtReportViewModel extends AndroidViewModel {
        public static class State {
            public VtFile file;
            @Nullable
            public VtScanAttempt active;
            /** Fork (白い熊, +081): the newest attempt of any status — names a failure. */
            @Nullable
            public VtScanAttempt last;
            /** Fork (白い熊, +081): data.attributes of the stored report, for the details card. */
            @Nullable
            public JSONObject attrs;
            @Nullable
            public VtFileReport report;
            @Nullable
            public VtVerdict verdict;
            public int uploadPercent = -1;
            @Nullable
            public Path realPath;
            @Nullable
            public String realPathString;
            @Nullable
            public String packageName;
            @Nullable
            public String versionName;
            @Nullable
            public AppFacts app;
            // Item 3: bundle container view.
            public boolean bundle;
            public boolean bundleScanning;
            @Nullable
            public VtVerdict.Level bundleWorst;
            @Nullable
            public List<BundlePartView> bundleParts;
        }

        /** Fork (白い熊, +082): what the installer's own dialog shows — read here so the upload
         *  and install decisions are made knowing whether this is an update at all. */
        public static class AppFacts {
            public static final int KIND_NEW = 0;
            public static final int KIND_UPDATE = 1;
            public static final int KIND_SAME = 2;
            public static final int KIND_DOWNGRADE = 3;
            public String packageName;
            @Nullable
            public CharSequence label;
            @Nullable
            public android.graphics.drawable.Drawable icon;
            @Nullable
            public String versionName;
            public long versionCode;
            public boolean installed;
            @Nullable
            public String installedVersionName;
            public long installedVersionCode;
            public int kind;
        }

        public static class BundlePartView {
            public VtFile file;
            @Nullable
            public VtScanAttempt active;
            @Nullable
            public VtVerdict verdict;
        }

        private final VtScanRepository mRepository = new VtScanRepository();
        private final ScheduledExecutorService mScheduler = Executors.newSingleThreadScheduledExecutor();
        private final MutableLiveData<State> mStateLiveData = new MutableLiveData<>();
        private final io.github.muntashirakon.lifecycle.SingleLiveEvent<String> mErrorLiveData =
                new io.github.muntashirakon.lifecycle.SingleLiveEvent<>();
        private volatile long mFileId;
        private volatile boolean mPolling;
        @Nullable
        private volatile Path mRealPath;
        @Nullable
        private volatile String mRealPathString;
        @Nullable
        private volatile String mPackageName;
        @Nullable
        private volatile String mVersionName;
        @Nullable
        private volatile AppFacts mApp;

        public VtReportViewModel(@NonNull Application application) {
            super(application);
        }

        LiveData<State> getStateLiveData() {
            return mStateLiveData;
        }

        LiveData<String> getErrorLiveData() {
            return mErrorLiveData;
        }

        void start(@NonNull Intent intent) {
            long fileId = intent.getLongExtra(EXTRA_FILE_ID, 0);
            long attemptId = intent.getLongExtra(EXTRA_ATTEMPT_ID, 0);
            String sha = intent.getStringExtra(EXTRA_SHA256);
            String action = intent.getAction();
            Uri dataUri = IntentCompat.getDataUri(intent);
            String realPath = intent.getStringExtra(VtContract.EXTRA_REAL_PATH);
            String type = intent.getType();
            mScheduler.execute(() -> resolve(fileId, attemptId, sha, action, dataUri, realPath, type));
        }

        @WorkerThread
        private void resolve(long fileId, long attemptId, @Nullable String sha, @Nullable String action,
                             @Nullable Uri dataUri, @Nullable String realPath, @Nullable String type) {
            try {
                long resolvedId = fileId;
                if (attemptId > 0) {
                    VtScanAttempt attempt = mRepository.getAttempt(attemptId);
                    if (attempt != null) {
                        resolvedId = attempt.fileId;
                        mRepository.markAttemptRead(attemptId);
                    }
                }
                if (resolvedId == 0 && sha != null) {
                    VtFile file = mRepository.getFileBySha256(sha);
                    if (file != null) resolvedId = file.id;
                }
                if (resolvedId == 0) {
                    // External scan request (VT_SCAN) — observe the file and begin a scan.
                    resolvedId = beginScan(dataUri, realPath, type);
                }
                if (resolvedId == 0) {
                    mErrorLiveData.postValue(getApplication().getString(R.string.vt_report_unavailable));
                    return;
                }
                mFileId = resolvedId;
                mRepository.markLatestRead(resolvedId);
                resolveSourceFacts();
                ensurePolling();
                loadOnce();
            } catch (Throwable e) {
                e.printStackTrace();
                mErrorLiveData.postValue(e.getMessage());
            }
        }

        @WorkerThread
        private long beginScan(@Nullable Uri dataUri, @Nullable String realPath, @Nullable String type)
                throws Exception {
            Path path = null;
            String sourceUri = null;
            String displayName = null;
            if (realPath != null) {
                Path candidate = Paths.get(new java.io.File(realPath));
                if (candidate.isFile()) {
                    path = candidate;
                    sourceUri = candidate.getUri().toString();
                    displayName = candidate.getName();
                }
            }
            // Item 3: a .apks/.xapk/.apkm bundle is unpacked and its code-bearing parts scanned.
            if (path != null && VtBundle.isBundle(displayName, type)) {
                return VtBundle.scan(getApplication(), mRepository, path, displayName, type);
            }
            VtScanRepository.ObservedFile observed;
            if (path != null) {
                observed = mRepository.observePath(path, sourceUri, displayName, type,
                        VtScanRepository.SOURCE_SCAN_REQUEST);
            } else if (dataUri != null) {
                observed = mRepository.observeUri(dataUri, type, VtScanRepository.SOURCE_SCAN_REQUEST);
            } else {
                return 0;
            }
            VtScanAttempt attempt = mRepository.createInitialAttempt(observed, Prefs.VirusTotal.promptBeforeUpload());
            if (attempt != null && VtScanAttempt.STATUS_QUEUED.equals(attempt.status)) {
                VtScanService.start(getApplication());
            }
            return observed.file.id;
        }

        @WorkerThread
        private void resolveSourceFacts() {
            try {
                Path path = mRepository.resolveSource(mFileId);
                mRealPath = path;
                mRealPathString = absolutePathOf(path);
                PackageInfo info = getApplication().getPackageManager()
                        .getPackageArchiveInfo(mRealPathString != null ? mRealPathString
                                : path.getUri().getPath(), 0);
                if (info != null) {
                    mPackageName = info.packageName;
                    mVersionName = info.versionName;
                    mApp = readAppFacts(info, mRealPathString != null ? mRealPathString
                            : path.getUri().getPath());
                }
            } catch (Throwable ignored) {
                // No readable source (e.g. a content grant that has lapsed) — Install is hidden.
            }
        }

        @WorkerThread
        @NonNull
        private AppFacts readAppFacts(@NonNull PackageInfo info, @Nullable String apkPath) {
            PackageManager pm = getApplication().getPackageManager();
            AppFacts facts = new AppFacts();
            facts.packageName = info.packageName;
            facts.versionName = info.versionName;
            facts.versionCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info);
            try {
                if (info.applicationInfo != null && apkPath != null) {
                    // An archive's ApplicationInfo has no source dir, so its label and icon
                    // resolve to nothing until it is pointed at the file.
                    info.applicationInfo.sourceDir = apkPath;
                    info.applicationInfo.publicSourceDir = apkPath;
                    facts.label = info.applicationInfo.loadLabel(pm);
                    facts.icon = info.applicationInfo.loadIcon(pm);
                }
            } catch (Throwable ignored) {
            }
            try {
                // The match flags are what let a frozen (hidden/disabled) app be seen at all.
                PackageInfo installed = pm.getPackageInfo(info.packageName,
                        PackageManager.MATCH_UNINSTALLED_PACKAGES | PackageManager.MATCH_DISABLED_COMPONENTS);
                if (installed.applicationInfo == null || (installed.applicationInfo.flags
                        & android.content.pm.ApplicationInfo.FLAG_INSTALLED) != 0) {
                    facts.installed = true;
                    facts.installedVersionName = installed.versionName;
                    facts.installedVersionCode =
                            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(installed);
                }
            } catch (Throwable ignored) {
                // Not installed.
            }
            if (!facts.installed) facts.kind = AppFacts.KIND_NEW;
            else if (facts.versionCode > facts.installedVersionCode) facts.kind = AppFacts.KIND_UPDATE;
            else if (facts.versionCode < facts.installedVersionCode) facts.kind = AppFacts.KIND_DOWNGRADE;
            else facts.kind = AppFacts.KIND_SAME;
            return facts;
        }

        @Nullable
        private static String absolutePathOf(@NonNull Path path) {
            Uri uri = path.getUri();
            if ("file".equals(uri.getScheme())) return uri.getPath();
            return null;
        }

        void refreshAppFacts() {
            mScheduler.execute(() -> {
                if (mFileId == 0) return;
                resolveSourceFacts();
            });
        }

        private void ensurePolling() {
            if (mPolling) return;
            mPolling = true;
            mScheduler.scheduleWithFixedDelay(this::loadOnceSafe, 1, 1, TimeUnit.SECONDS);
        }

        private void loadOnceSafe() {
            try {
                loadOnce();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }

        @WorkerThread
        private void loadOnce() {
            if (mFileId == 0) return;
            VtFile file = mRepository.getFile(mFileId);
            if (file == null) {
                mErrorLiveData.postValue(getApplication().getString(R.string.vt_report_unavailable));
                return;
            }
            State state = new State();
            state.file = file;
            state.active = mRepository.getActiveAttempt(mFileId);
            state.last = mRepository.getLatestAttempt(mFileId);
            state.realPath = mRealPath;
            state.realPathString = mRealPathString;
            state.packageName = mPackageName;
            state.versionName = mVersionName;
            state.app = mApp;
            if (file.latestReportJson != null) {
                state.report = parseReport(file.latestReportJson);
                state.verdict = VtVerdict.fromJson(file.latestReportJson);
                try {
                    state.attrs = new JSONObject(file.latestReportJson).getJSONObject("data")
                            .getJSONObject("attributes");
                } catch (Throwable ignored) {
                }
            }
            if (state.active != null && VtScanAttempt.STATUS_UPLOADING.equals(state.active.status)) {
                state.uploadPercent = VtScanService.getUploadPercent(state.active.id);
            }
            if (file.isBundle) {
                populateBundle(state, file);
            }
            mStateLiveData.postValue(state);
        }

        @WorkerThread
        private void populateBundle(@NonNull State state, @NonNull VtFile container) {
            state.bundle = true;
            List<BundlePartView> views = new ArrayList<>();
            VtVerdict.Level worst = null;
            boolean anyActive = false;
            for (VtFile part : mRepository.getBundleParts(container.sha256)) {
                BundlePartView bv = new BundlePartView();
                bv.file = part;
                bv.active = mRepository.getActiveAttempt(part.id);
                if (part.latestReportJson != null) bv.verdict = VtVerdict.fromJson(part.latestReportJson);
                if (bv.active != null) anyActive = true;
                if (bv.verdict != null) worst = worstOf(worst, bv.verdict.level);
                views.add(bv);
            }
            Collections.sort(views, (a, b) -> Integer.compare(levelOrdinal(b.verdict), levelOrdinal(a.verdict)));
            state.bundleParts = views;
            state.bundleWorst = worst;
            state.bundleScanning = anyActive;
        }

        @Nullable
        private static VtVerdict.Level worstOf(@Nullable VtVerdict.Level a, @Nullable VtVerdict.Level b) {
            if (a == null) return b;
            if (b == null) return a;
            return a.ordinal() >= b.ordinal() ? a : b;
        }

        private static int levelOrdinal(@Nullable VtVerdict verdict) {
            return verdict == null ? -1 : verdict.level.ordinal();
        }

        void rescanBundle() {
            mScheduler.execute(() -> {
                if (mFileId == 0) return;
                VtFile container = mRepository.getFile(mFileId);
                if (container == null) return;
                boolean started = false;
                for (VtFile part : mRepository.getBundleParts(container.sha256)) {
                    VtScanAttempt attempt = part.latestReportJson != null
                            ? mRepository.createRescanAttempt(part.id)
                            : mRepository.retryAttempt(part.id, VtScanAttempt.TYPE_LOOKUP);
                    if (VtScanAttempt.STATUS_QUEUED.equals(attempt.status)) started = true;
                }
                if (started) VtScanService.start(getApplication());
                loadOnce();
            });
        }

        @Nullable
        private static VtFileReport parseReport(@NonNull String json) {
            try {
                return new VtFileReport(new JSONObject(json));
            } catch (Throwable e) {
                return null;
            }
        }

        void rescan() {
            mScheduler.execute(() -> {
                if (mFileId == 0) return;
                VtFile file = mRepository.getFile(mFileId);
                if (file == null) return;
                VtScanAttempt attempt = file.latestReportJson != null
                        ? mRepository.createRescanAttempt(mFileId)
                        : mRepository.retryAttempt(mFileId, VtScanAttempt.TYPE_LOOKUP);
                if (VtScanAttempt.STATUS_QUEUED.equals(attempt.status)) {
                    VtScanService.start(getApplication());
                }
                loadOnce();
            });
        }

        void resume() {
            mScheduler.execute(() -> {
                mRepository.resumeKeyParkedAttempts();
                VtScanService.start(getApplication());
                loadOnce();
            });
        }

        void approveUpload(long attemptId) {
            mScheduler.execute(() -> {
                mRepository.approveUpload(attemptId);
                VtScanService.start(getApplication());
                loadOnce();
            });
        }

        void rejectUpload(long attemptId) {
            mScheduler.execute(() -> {
                mRepository.rejectUpload(attemptId);
                loadOnce();
            });
        }

        void cancelAttempt(long attemptId) {
            mScheduler.execute(() -> {
                mRepository.cancelAttempt(attemptId);
                getApplication().startService(new Intent(getApplication(), VtScanService.class)
                        .setAction(VtScanService.ACTION_CANCEL)
                        .putExtra(VtScanService.EXTRA_ATTEMPT_ID, attemptId));
                loadOnce();
            });
        }

        @Override
        protected void onCleared() {
            mScheduler.shutdownNow();
            mRepository.close();
            super.onCleared();
        }
    }
}
