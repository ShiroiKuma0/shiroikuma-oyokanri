// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.processreaper;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserHandleHidden;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.muntashirakon.AppManager.BaseActivity;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.details.AppDetailsActivity;
import io.github.muntashirakon.AppManager.fonts.ColorPrefs;
import io.github.muntashirakon.AppManager.fonts.FontPrefs;
import io.github.muntashirakon.AppManager.fonts.FontUtil;
import io.github.muntashirakon.AppManager.runner.Runner;
import io.github.muntashirakon.AppManager.self.imagecache.ImageLoader;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;

/**
 * Fork: dedicated process detail / inspector screen, opened by tapping a row in
 * the process monitor (the tap no longer kills). Gathers the full per-process
 * property set from {@code /proc/<pid>/} (one privileged read) plus app metadata
 * from the PackageManager, grouped into sections. Toolbar actions: App details
 * (into AppManager's own screen), Kill, Refresh.
 */
public class ProcessDetailActivity extends BaseActivity {
    private static final String EXTRA_PID = "pid";
    private static final String EXTRA_PIDS = "pids";
    private static final String EXTRA_PKG = "pkg";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_KILLABLE = "killable";
    private static final String EXTRA_METHOD = "method";
    private static final String EXTRA_UID = "uid";
    private static final String EXTRA_REASON = "reason";
    private static final String EXTRA_USER = "user";
    private static final String EXTRA_CPU = "cpu";
    private static final String EXTRA_PSS = "pss";
    private static final String EXTRA_IN_USE = "in_use";

    private static final double CLK_TCK = 100.0;

    @NonNull
    public static Intent getIntent(@NonNull Context ctx, int pid, @NonNull int[] pids, @Nullable String pkg,
                                   @NonNull String title, boolean killable, int method, int uid,
                                   @NonNull String reason, @Nullable String user, @NonNull String cpu,
                                   long pssBytes, boolean inUse) {
        Intent i = new Intent(ctx, ProcessDetailActivity.class);
        i.putExtra(EXTRA_PID, pid);
        i.putExtra(EXTRA_PIDS, pids);
        i.putExtra(EXTRA_PKG, pkg);
        i.putExtra(EXTRA_TITLE, title);
        i.putExtra(EXTRA_KILLABLE, killable);
        i.putExtra(EXTRA_METHOD, method);
        i.putExtra(EXTRA_UID, uid);
        i.putExtra(EXTRA_REASON, reason);
        i.putExtra(EXTRA_USER, user);
        i.putExtra(EXTRA_CPU, cpu);
        i.putExtra(EXTRA_PSS, pssBytes);
        i.putExtra(EXTRA_IN_USE, inUse);
        return i;
    }

    private int mPid;
    private int[] mPids;
    @Nullable
    private String mPkg;
    private boolean mKillable;
    private int mMethod;
    private int mUid;
    private LinearLayout mContent;

    @Override
    protected void onAuthenticated(@Nullable Bundle savedInstanceState) {
        setContentView(R.layout.activity_process_detail);
        setSupportActionBar(findViewById(R.id.toolbar));
        ActionBar ab = getSupportActionBar();
        Intent in = getIntent();
        mPid = in.getIntExtra(EXTRA_PID, -1);
        mPids = in.getIntArrayExtra(EXTRA_PIDS);
        if (mPids == null) mPids = new int[]{mPid};
        mPkg = in.getStringExtra(EXTRA_PKG);
        mKillable = in.getBooleanExtra(EXTRA_KILLABLE, false);
        mMethod = in.getIntExtra(EXTRA_METHOD, ProcessClassifier.METHOD_NONE);
        mUid = in.getIntExtra(EXTRA_UID, -1);
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
            ab.setTitle(in.getStringExtra(EXTRA_TITLE));
            ab.setSubtitle("PID " + mPid);
            ((MaterialToolbar) findViewById(R.id.toolbar))
                    .setSubtitleTextColor(ContextCompat.getColor(this, R.color.theme_bright_yellow));
        }
        mContent = findViewById(R.id.detail_content);
        reload();
    }

    private void reload() {
        ThreadUtils.postOnBackgroundThread(() -> {
            final List<String[]> rows = gather();
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) populate(rows);
            });
        });
    }

    // ---- Gather ----------------------------------------------------------

    @NonNull
    private List<String[]> gather() {
        Map<String, String> status = new LinkedHashMap<>();
        Map<String, String> vals = new HashMap<>();
        String stat = "";
        try {
            // status + stat are multi-line blocks (newline-terminated). The
            // single-value fields are captured via $(...) — which strips trailing
            // newlines/nulls — onto one "@V@KEY=value" line each, so a value with no
            // trailing newline (cmdline, SELinux) can't run into the next marker.
            Runner.Result r = Runner.runCommand("p=" + mPid + "; "
                    + "echo @ST@; cat /proc/$p/status 2>/dev/null; "
                    + "echo @SS@; cat /proc/$p/stat 2>/dev/null; "
                    + "echo \"@V@OA=$(cat /proc/$p/oom_score_adj 2>/dev/null)\"; "
                    + "echo \"@V@OO=$(cat /proc/$p/oom_score 2>/dev/null)\"; "
                    + "echo \"@V@SE=$(cat /proc/$p/attr/current 2>/dev/null | tr -d '\\0')\"; "
                    + "echo \"@V@CG=$(head -1 /proc/$p/cgroup 2>/dev/null)\"; "
                    + "echo \"@V@CL=$(cat /proc/$p/cmdline 2>/dev/null | tr '\\0' ' ')\"; "
                    + "echo \"@V@FD=$(ls /proc/$p/fd 2>/dev/null | wc -l)\"");
            if (r != null && r.isSuccessful()) {
                String sec = "";
                StringBuilder statBuf = new StringBuilder();
                for (String line : r.getOutputAsList()) {
                    if ("@ST@".equals(line)) {
                        sec = "ST";
                    } else if ("@SS@".equals(line)) {
                        sec = "SS";
                    } else if (line.startsWith("@V@")) {
                        int eq = line.indexOf('=', 3);
                        if (eq > 3) vals.put(line.substring(3, eq), line.substring(eq + 1).trim());
                    } else if ("ST".equals(sec)) {
                        int c = line.indexOf(':');
                        if (c > 0) status.put(line.substring(0, c).trim(), line.substring(c + 1).trim());
                    } else if ("SS".equals(sec)) {
                        statBuf.append(line);
                    }
                }
                stat = statBuf.toString();
            }
        } catch (Throwable ignore) {
        }
        return buildRows(status, stat, orDash(vals.get("OA")), orDash(vals.get("OO")),
                orDash(vals.get("SE")), orDash(vals.get("CG")), orDash(vals.get("CL")), orDash(vals.get("FD")));
    }

    // ---- Build display rows ---------------------------------------------

    @NonNull
    private List<String[]> buildRows(@NonNull Map<String, String> st, @NonNull String stat,
                                     @NonNull String oomAdj, @NonNull String oomScore, @NonNull String selinux,
                                     @NonNull String cgroup, @NonNull String cmdline, @NonNull String fd) {
        Intent in = getIntent();
        String[] f = parseStat(stat);
        List<String[]> rows = new ArrayList<>();

        header(rows, "Process");
        row(rows, "Name", orDash(st.get("Name")));
        row(rows, "Package", orDash(mPkg));
        row(rows, "PID", String.valueOf(mPid));
        row(rows, "PPID", firstTok(st.get("PPid")));
        String user = in.getStringExtra(EXTRA_USER);
        row(rows, "UID / user", mUid + (TextUtils.isEmpty(user) ? "" : " / " + user));
        row(rows, "GID", firstTok(st.get("Gid")));
        row(rows, "State", stateLabel(st.get("State")));
        row(rows, "Threads", orDash(st.get("Threads")));
        row(rows, "cmdline", cmdline);

        header(rows, "Memory");
        row(rows, "PSS", Formatter.formatShortFileSize(this, in.getLongExtra(EXTRA_PSS, 0)));
        row(rows, "RSS", humanKb(st.get("VmRSS")));
        row(rows, "Peak RSS", humanKb(st.get("VmHWM")));
        row(rows, "Virtual", humanKb(st.get("VmSize")));
        row(rows, "Peak virtual", humanKb(st.get("VmPeak")));
        row(rows, "Data / Stack", humanKb(st.get("VmData")) + " / " + humanKb(st.get("VmStk")));
        row(rows, "Swap", humanKb(st.get("VmSwap")));
        row(rows, "Open FDs", fd);

        header(rows, "CPU & scheduling");
        row(rows, "CPU now", orDash(in.getStringExtra(EXTRA_CPU)));
        row(rows, "CPU time", cpuTime(f));
        row(rows, "Priority / nice", field(f, 15) + " / " + field(f, 16));
        row(rows, "Scheduling", schedPolicy(field(f, 38)));
        row(rows, "Last CPU", field(f, 36));
        row(rows, "Ctxt switches", orDash(st.get("voluntary_ctxt_switches")) + " vol / "
                + orDash(st.get("nonvoluntary_ctxt_switches")) + " invol");

        header(rows, "Lifecycle & security");
        row(rows, "oom_score_adj", oomAdj);
        row(rows, "oom_score", oomScore);
        row(rows, "In use", in.getBooleanExtra(EXTRA_IN_USE, false) ? "yes (foreground/service)" : "no");
        row(rows, "Age", ageLabel(field(f, 19)));
        row(rows, "Traced by", tracer(st.get("TracerPid")));
        row(rows, "Seccomp", seccomp(st.get("Seccomp")));
        row(rows, "SELinux", selinux);
        row(rows, "cgroup", cgroup);

        if (mPkg != null) {
            appSection(rows, mPkg);
        }

        header(rows, "Classification");
        row(rows, "Status", mKillable
                ? "Killable · " + in.getStringExtra(EXTRA_REASON)
                + (mMethod == ProcessClassifier.METHOD_SIGKILL ? " (SIGKILL)" : " (force-stop)")
                : "Protected · " + in.getStringExtra(EXTRA_REASON));
        if (mPids.length > 1) row(rows, "Grouped pids", mPids.length + " processes");
        return rows;
    }

    private void appSection(@NonNull List<String[]> rows, @NonNull String pkg) {
        try {
            PackageManager pm = getPackageManager();
            PackageInfo pi = pm.getPackageInfo(pkg, 0);
            ApplicationInfo ai = pi.applicationInfo;
            header(rows, "App");
            if (ai != null) row(rows, "Label", String.valueOf(ai.loadLabel(pm)));
            long vc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? pi.getLongVersionCode() : pi.versionCode;
            row(rows, "Version", orDash(pi.versionName) + " (" + vc + ")");
            if (ai != null) {
                row(rows, "Target SDK", String.valueOf(ai.targetSdkVersion));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    row(rows, "Min SDK", String.valueOf(ai.minSdkVersion));
                }
                row(rows, "System app", yesNo((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0));
                row(rows, "Debuggable", yesNo((ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0));
                row(rows, "APK", orDash(ai.sourceDir));
                row(rows, "Data dir", orDash(ai.dataDir));
            }
            DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
            row(rows, "Installed", df.format(new Date(pi.firstInstallTime)));
            row(rows, "Updated", df.format(new Date(pi.lastUpdateTime)));
        } catch (Throwable ignore) {
        }
    }

    // ---- Populate UI -----------------------------------------------------

    private void populate(@NonNull List<String[]> rows) {
        mContent.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        int gap = Math.round(MonitorPrefs.getDetailRowPadDp(this) * density);
        int sectionTop = Math.round(16 * density);
        int sectionColor = ColorPrefs.getColor(this, ColorPrefs.MONITOR_DETAIL_SECTION);
        int labelColor = ColorPrefs.getColor(this, ColorPrefs.MONITOR_DETAIL_ROW_LABEL);
        int valueColor = ColorPrefs.getColor(this, ColorPrefs.MONITOR_DETAIL_ROW_VALUE);
        addIconHeader(density);
        for (String[] kv : rows) {
            if (kv[0] == null) {
                TextView h = new TextView(this);
                h.setText(kv[1]);
                h.setTextColor(sectionColor);
                h.setTypeface(Typeface.DEFAULT_BOLD);
                h.setTextSize(15f);
                h.setAllCaps(true);
                h.setPadding(0, sectionTop, 0, gap);
                FontUtil.apply(h, FontPrefs.MONITOR_DETAIL_SECTION);
                mContent.addView(h);
                continue;
            }
            LinearLayout rl = new LinearLayout(this);
            rl.setOrientation(LinearLayout.HORIZONTAL);
            rl.setPadding(0, gap, 0, gap);
            TextView label = new TextView(this);
            label.setText(kv[0]);
            label.setTextColor(labelColor);
            label.setTextSize(13f);
            label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3.2f));
            FontUtil.apply(label, FontPrefs.MONITOR_DETAIL_ROW_LABEL);
            TextView value = new TextView(this);
            value.setText(kv[1]);
            value.setTextColor(valueColor);
            value.setTextSize(13f);
            value.setTextIsSelectable(true);
            value.setGravity(Gravity.END);
            value.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 6.8f));
            FontUtil.apply(value, FontPrefs.MONITOR_DETAIL_ROW_VALUE);
            rl.addView(label);
            rl.addView(value);
            mContent.addView(rl);
        }
    }

    /**
     * Header: app icon on the left, with the label (larger) and package id (smaller)
     * stacked to its right. Tap the whole header to open the app's full details.
     */
    private void addIconHeader(float density) {
        int size = Math.round(MonitorPrefs.getDetailIconDp(this) * density);
        int pad = Math.round(8 * density);
        int gap = Math.round(12 * density);
        int labelColor = ColorPrefs.getColor(this, ColorPrefs.MONITOR_DETAIL_LABEL);
        int idColor = ColorPrefs.getColor(this, ColorPrefs.MONITOR_DETAIL_ID);
        // Any real app (killable OR protected) has a package; only shell/leak rows
        // arrive with mPkg == null.
        boolean realApp = mPkg != null;

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, pad, 0, pad);

        ImageView icon = new ImageView(this);
        icon.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        if (realApp) {
            try {
                ApplicationInfo ai = getPackageManager().getPackageInfo(mPkg, 0).applicationInfo;
                if (ai != null) {
                    // ImageLoader guards against recycled views by comparing the
                    // ImageView's tag with the load key — they MUST match or the
                    // bitmap never binds (see CLAUDE.md ImageLoader note).
                    icon.setTag(mPkg);
                    ImageLoader.getInstance().displayImage(mPkg, ai, icon);
                } else {
                    icon.setImageResource(R.drawable.ic_android);
                }
            } catch (Throwable t) {
                icon.setImageResource(R.drawable.ic_android);
            }
        } else {
            icon.setImageResource(R.drawable.ic_android);
        }
        header.addView(icon);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.setMarginStart(gap);
        col.setLayoutParams(colLp);

        TextView label = new TextView(this);
        label.setText(getIntent().getStringExtra(EXTRA_TITLE));
        label.setTextColor(labelColor);
        label.setTextSize(20f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setMaxLines(2);
        FontUtil.apply(label, FontPrefs.MONITOR_DETAIL_LABEL);
        col.addView(label);

        if (mPkg != null) {
            TextView pkg = new TextView(this);
            pkg.setText(mPkg);
            pkg.setTextColor(idColor);
            pkg.setTextSize(13f);
            pkg.setTextIsSelectable(true);
            FontUtil.apply(pkg, FontPrefs.MONITOR_DETAIL_ID);
            col.addView(pkg);
        }
        header.addView(col);

        if (realApp) {
            header.setClickable(true);
            header.setFocusable(true);
            header.setContentDescription(getString(R.string.monitor_app_details));
            header.setOnClickListener(v ->
                    startActivity(AppDetailsActivity.getIntent(this, mPkg, UserHandleHidden.getUserId(mUid))));
            TypedValue tv = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            header.setBackgroundResource(tv.resourceId);
        }
        mContent.addView(header);
    }

    // ---- Menu / actions --------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.process_detail_actions, menu);
        int yellow = ContextCompat.getColor(this, R.color.theme_bright_yellow);
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            Drawable icon = item.getIcon();
            if (icon != null) {
                icon = icon.mutate();
                icon.setColorFilter(yellow, PorterDuff.Mode.SRC_IN);
                item.setIcon(icon);
            }
        }
        MenuItem appInfo = menu.findItem(R.id.action_detail_app_info);
        if (appInfo != null) appInfo.setVisible(mPkg != null);
        MenuItem kill = menu.findItem(R.id.action_detail_kill);
        if (kill != null) kill.setVisible(mKillable);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null && toolbar.getOverflowIcon() != null) {
            Drawable overflow = toolbar.getOverflowIcon().mutate();
            overflow.setColorFilter(yellow, PorterDuff.Mode.SRC_IN);
            toolbar.setOverflowIcon(overflow);
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.action_detail_refresh) {
            reload();
            return true;
        } else if (id == R.id.action_detail_app_info && mPkg != null) {
            startActivity(AppDetailsActivity.getIntent(this, mPkg, UserHandleHidden.getUserId(mUid)));
            return true;
        } else if (id == R.id.action_detail_kill) {
            doKill();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void doKill() {
        final String title = String.valueOf(getIntent().getStringExtra(EXTRA_TITLE));
        ThreadUtils.postOnBackgroundThread(() -> {
            boolean ok = false;
            try {
                if (mMethod == ProcessClassifier.METHOD_FORCE_STOP && mPkg != null) {
                    PackageManagerCompat.forceStopPackage(mPkg, UserHandleHidden.getUserId(mUid));
                    ok = true;
                } else if (mMethod == ProcessClassifier.METHOD_SIGKILL) {
                    StringBuilder pl = new StringBuilder();
                    for (int p : mPids) pl.append(p).append(' ');
                    Runner.Result r = Runner.runCommand("kill -9 " + pl.toString().trim() + " 2>&1");
                    ok = true;
                    if (r != null) {
                        for (String l : r.getOutputAsList()) {
                            String low = l.toLowerCase(Locale.ROOT);
                            if (low.contains("not permitted") || low.contains("permission denied")) ok = false;
                        }
                    }
                }
            } catch (Throwable ignore) {
            }
            final boolean killed = ok;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                UIUtils.displayShortToast(getString(killed ? R.string.monitor_killed : R.string.monitor_kill_failed, title));
                if (killed) finish();
            });
        });
    }

    // ---- Formatting helpers ---------------------------------------------

    @NonNull
    private static String[] parseStat(@NonNull String stat) {
        int close = stat.lastIndexOf(')');
        if (close < 0) return new String[0];
        return stat.substring(close + 1).trim().split("\\s+");
    }

    @NonNull
    private static String field(@NonNull String[] f, int idx) {
        return idx >= 0 && idx < f.length ? f[idx] : "—";
    }

    @NonNull
    private String cpuTime(@NonNull String[] f) {
        try {
            long ticks = Long.parseLong(field(f, 11)) + Long.parseLong(field(f, 12));
            double sec = ticks / CLK_TCK;
            return String.format(Locale.US, "%.1f s", sec);
        } catch (Exception e) {
            return "—";
        }
    }

    @NonNull
    private String ageLabel(@NonNull String starttimeTicks) {
        try {
            long st = Long.parseLong(starttimeTicks);
            long sec = (long) (SystemClock.elapsedRealtime() / 1000.0 - st / CLK_TCK);
            if (sec <= 0) return "—";
            if (sec < 60) return sec + "s";
            if (sec < 3600) return (sec / 60) + "m " + (sec % 60) + "s";
            return (sec / 3600) + "h " + ((sec % 3600) / 60) + "m";
        } catch (Exception e) {
            return "—";
        }
    }

    @NonNull
    private String humanKb(@Nullable String statusVal) {
        if (TextUtils.isEmpty(statusVal)) return "—";
        try {
            String num = statusVal.replaceAll("[^0-9]", "");
            if (num.isEmpty()) return "—";
            return Formatter.formatShortFileSize(this, Long.parseLong(num) * 1024L);
        } catch (Exception e) {
            return statusVal;
        }
    }

    @NonNull
    private static String stateLabel(@Nullable String state) {
        if (TextUtils.isEmpty(state)) return "—";
        String c = state.trim().substring(0, 1).toUpperCase(Locale.ROOT);
        switch (c) {
            case "R": return "R (running)";
            case "S": return "S (sleeping)";
            case "D": return "D (uninterruptible)";
            case "Z": return "Z (zombie)";
            case "T": return "T (stopped)";
            default: return state.trim();
        }
    }

    @NonNull
    private static String schedPolicy(@NonNull String p) {
        switch (p) {
            case "0": return "SCHED_OTHER";
            case "1": return "SCHED_FIFO";
            case "2": return "SCHED_RR";
            case "3": return "SCHED_BATCH";
            case "5": return "SCHED_IDLE";
            default: return p;
        }
    }

    @NonNull
    private static String seccomp(@Nullable String s) {
        if (s == null) return "—";
        switch (s.trim()) {
            case "0": return "disabled";
            case "1": return "strict";
            case "2": return "filter";
            default: return s.trim();
        }
    }

    @NonNull
    private static String tracer(@Nullable String t) {
        if (TextUtils.isEmpty(t)) return "—";
        return "0".equals(t.trim()) ? "no" : t.trim();
    }

    @NonNull
    private static String firstTok(@Nullable String s) {
        if (TextUtils.isEmpty(s)) return "—";
        String[] p = s.trim().split("\\s+");
        return p.length > 0 ? p[0] : "—";
    }

    @NonNull
    private static String orDash(@Nullable String s) {
        return TextUtils.isEmpty(s) ? "—" : s;
    }

    @NonNull
    private static String yesNo(boolean b) {
        return b ? "yes" : "no";
    }

    private static void header(@NonNull List<String[]> rows, @NonNull String title) {
        rows.add(new String[]{null, title});
    }

    private static void row(@NonNull List<String[]> rows, @NonNull String label, @NonNull String value) {
        rows.add(new String[]{label, value});
    }
}
