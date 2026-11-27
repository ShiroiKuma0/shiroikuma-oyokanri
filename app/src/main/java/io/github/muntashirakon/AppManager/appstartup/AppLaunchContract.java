// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.appstartup;

/**
 * Fork (白い熊, 2026-11-07): the wire contract 白い熊 応用管理 fires at 白い熊 自由作業盤
 * ({@code shiroikuma.jiyusagyoban}) to open EMUI's App-launch screen (起動マネージャー) at a given app.
 *
 * <p>Why the delegation exists at all: on this EMUI build 応用管理 cannot open that screen itself.
 * Its activities ({@code StartupNormalAppListActivity} / {@code StartupAppControlActivity}) are
 * exported but require the signature-level {@code com.huawei.permission.external_app_settings.USE_COMPONENT},
 * and this phone's shell uid does <b>not</b> hold {@code START_ANY_ACTIVITY} — so {@code am start},
 * Shizuku, a normal launch and {@code startActivityViaAssist} are all refused (measured 2026-11-07).
 * 自由作業盤's AccessibilityService is the only thing on the phone that can drive the screen — and
 * that screen has a 検索 (search) box, so a task can jump to any app by typing its name rather than
 * scrolling.
 *
 * <p>自由作業盤 already trusts 応用管理 as a sister in its {@code AutomationCallers} allowlist
 * (package name + kernel-reported uid + pinned signing certificate), so no shared token is needed.
 * Its clean {@code com.opentasker.action.RUN_TASK} trigger is signature-gated and we are signed with
 * a different key — so this is a sister-facing entry of its own on the 自由作業盤 side: run a
 * named task <b>and</b> inject which app it is about.
 */
public final class AppLaunchContract {
    private AppLaunchContract() {
    }

    /** 白い熊 自由作業盤. */
    public static final String JIYU_PACKAGE = "shiroikuma.jiyusagyoban";

    // ── The provider call (白い熊, 2026-11-24, security audit V-06) — the only path. ─────────────
    //
    // 自由作業盤's data door (authority {@code shiroikuma.jiyusagyoban.automation}, the one
    // AppDataClient already calls for describe/export/import) answers {@code run_task} from its
    // build +023 on. A provider call is where the system names the caller: 自由作業盤 checks package,
    // binder uid and pinned certificate there, so nothing we hand out can stand in for us. The
    // broadcast it replaced (RUN_TASK_WITH_TARGET) proved identity with a PendingIntent — a bearer
    // object that every notification listener and launcher receives from us and could present in
    // our place. 自由作業盤 closed that door in its +024; it was removed here in +130.
    //
    // These names are a wire format: never renamed.

    /** Run a named task about one app. Synchronous — call it off the main thread. */
    public static final String METHOD_RUN_TASK = "run_task";
    /** The task name to run (String, required). */
    public static final String CALL_TASK = "task";
    /** The project the task lives in, to tell same-named tasks apart (String, optional). */
    public static final String CALL_PROJECT = "project";
    /** The target app's package name; {@code %pkg} in the task. */
    public static final String CALL_TARGET_PACKAGE = "target_package";
    /** The target app's display label; {@code %label} in the task. */
    public static final String CALL_TARGET_LABEL = "target_label";
    /**
     * The answer: {@code OK:ran '<task>' in <n>ms}, {@code OK:running '<task>'} (still going after
     * 6 s; the run carries on) or {@code ERROR:<why>}.
     */
    public static final String CALL_RESULT = "result";
    /** What a 自由作業盤 older than +023 answers: it is too old, and 白い熊 is told so. */
    public static final String CALL_UNKNOWN_METHOD = "ERROR:unknown method";

    /** The task 白い熊 creates in 自由作業盤 to open 起動マネージャー and search for {@code label}. */
    public static final String TASK_OPEN_APP_LAUNCH = "起動マネージャーを開く";
    /** The project it lives in. */
    public static final String PROJECT_APP_LAUNCH = "アプリ起動";
}
