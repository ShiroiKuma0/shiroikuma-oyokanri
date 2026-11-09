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
 * a different key, and its existing sister-facing {@code shiroikuma.jiyusagyoban.action.RUN_TASK}
 * forwards no variable payload into the task — so this is a <b>new</b> sister-facing entry 白い熊 adds
 * on the 自由作業盤 side: run a named task <b>and</b> inject which app it is about.
 */
public final class AppLaunchContract {
    private AppLaunchContract() {
    }

    /** 白い熊 自由作業盤. */
    public static final String JIYU_PACKAGE = "shiroikuma.jiyusagyoban";

    /**
     * Run a 自由作業盤 task, carrying the app it is about. Explicit ({@code setPackage}), sent as a
     * plain broadcast with {@code FLAG_INCLUDE_STOPPED_PACKAGES}; gated on the 自由作業盤 side by its
     * {@code AutomationCallers} allowlist (uid + signing cert), never by a signature permission we
     * cannot hold. (It was ordered through +071 until an EMUI ordered-queue redelivery to 自由作業盤's
     * single receiver — ~6 s later, after its goAsync() finished — produced a phantom duplicate run;
     * a plain broadcast does not hold the receiver open. 自由作業盤 never checks isOrderedBroadcast.)
     */
    public static final String ACTION_RUN_TASK_WITH_TARGET =
            "shiroikuma.jiyusagyoban.action.RUN_TASK_WITH_TARGET";

    /** The task name to run (String, required). */
    public static final String EXTRA_TASK = "shiroikuma.jiyusagyoban.extra.TASK";
    /** The project the task lives in, to disambiguate a shared name (String, optional). */
    public static final String EXTRA_PROJECT = "shiroikuma.jiyusagyoban.extra.PROJECT";
    /** The target app's package name, injected into the task as the variable {@code pkg}. */
    public static final String EXTRA_TARGET_PACKAGE = "shiroikuma.jiyusagyoban.extra.TARGET_PACKAGE";
    /**
     * The target app's display label, injected as {@code label} — what the task types into 検索,
     * since the startup screen filters by the app's name rather than its package.
     */
    public static final String EXTRA_TARGET_LABEL = "shiroikuma.jiyusagyoban.extra.TARGET_LABEL";
    /** The caller's package, for the {@code AutomationCallers} gate and the run log. */
    public static final String EXTRA_SOURCE = "shiroikuma.jiyusagyoban.extra.SOURCE";
    /**
     * An <b>immutable</b> {@link android.app.PendingIntent} we create, carrying forge-proof caller
     * identity. A broadcast has no sender uid below API 34 and {@code Binder.getCallingUid()} in the
     * receiver returns 自由作業盤's own uid, so {@link #EXTRA_SOURCE} alone could be spoofed; the
     * system stamps a PendingIntent's {@code creatorPackage}/{@code creatorUid}, which 自由作業盤
     * cross-checks against {@code EXTRA_SOURCE} before running its {@code AutomationCallers} gate
     * (name + that uid + pinned cert). 自由作業盤 only reads those two fields and never sends it.
     */
    public static final String EXTRA_IDENTITY = "shiroikuma.jiyusagyoban.extra.IDENTITY";
    /** Where to send the terminal reply (String, optional). */
    public static final String EXTRA_REPLY_PACKAGE = "shiroikuma.jiyusagyoban.extra.REPLY_PACKAGE";
    /** A per-call nonce echoed back on the reply, so a stale or spoofed reply is ignored. */
    public static final String EXTRA_REQUEST_ID = "shiroikuma.jiyusagyoban.extra.REQUEST_ID";

    /** The task 白い熊 creates in 自由作業盤 to open 起動マネージャー and search for {@code label}. */
    public static final String TASK_OPEN_APP_LAUNCH = "起動マネージャーを開く";
    /** The project it lives in. */
    public static final String PROJECT_APP_LAUNCH = "アプリ起動";

    // ── Reply (renrakusaki shape: a FRESH broadcast, never only the ordered result — EMUI severs
    // the ordered-result channel between third-party apps). 自由作業盤 fresh-broadcasts this with
    // setPackage(REPLY_PACKAGE) + FLAG_INCLUDE_STOPPED_PACKAGES. ─────────────────────────────────

    public static final String ACTION_RESULT = "shiroikuma.oyokanri.action.APP_LAUNCH_RESULT";
    /** {@code OK:<detail>} or {@code ERROR:<reason>}. */
    public static final String EXTRA_RESULT = "shiroikuma.oyokanri.extra.RESULT";
}
