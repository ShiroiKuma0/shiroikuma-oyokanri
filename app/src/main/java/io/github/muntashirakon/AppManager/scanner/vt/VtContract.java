// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.scanner.vt;

/**
 * Fork (白い熊, APK-vetting chain): the wire contract shared by the three apps of the APK-vetting
 * chain — the file manager ({@code shiroikuma.shoruikanri}), this scan-and-report home
 * ({@code shiroikuma.oyokanri}) and the installer ({@code shiroikuma.universalinstaller}).
 *
 * <p><b>These names are identical in all three apps' sub-plans and must never be renamed on one
 * side.</b> The extra names are deliberately NOT prefixed with any one app's applicationId: they
 * travel between apps, so they carry the family's {@code shiroikuma.extra.*} namespace instead.
 */
public final class VtContract {
    private VtContract() {}

    // ── Scan request — received by this app ──────────────────────────────────────────────
    // Sent by the file manager (APK tap, context menu) and by the installer ("Full scan" pill).
    // Delivered to VtReportActivity, exported, with a content:// data URI (its MIME type,
    // FLAG_GRANT_READ_URI_PERMISSION) and, when the sender knows it, EXTRA_REAL_PATH.
    public static final String ACTION_VT_SCAN = "shiroikuma.oyokanri.action.VT_SCAN";

    // ── Install hand-off — RETIRED in +083 ───────────────────────────────────────────────
    // This app installs reviewed files with its own installer now (VtReportActivity.installFile),
    // so nothing here sends the hand-off or the three install extras any more. The names stay
    // because the universal installer still understands them and they must never be reused.
    // Received by the installer's DialogInstallActivity via ACTION_VIEW with a content:// URI this
    // app is entitled to grant (its own FmProvider over the real path). Falls back to this app's own
    // PackageInstallerActivity when the installer component does not resolve.
    public static final String INSTALLER_PACKAGE = "shiroikuma.universalinstaller";
    public static final String INSTALLER_COMPONENT =
            "app.pwhs.universalinstaller.presentation.install.DialogInstallActivity";

    // ── Shared extras (literal, cross-app) ───────────────────────────────────────────────
    /** Absolute filesystem path of the file, when the sender knows it. Preferred over the URI
     *  whenever it is readable (all three apps hold all-files access). */
    public static final String EXTRA_REAL_PATH = "shiroikuma.extra.REAL_PATH";
    /** The user saw the scan report for this file (sent to the installer). */
    public static final String EXTRA_VT_REVIEWED = "shiroikuma.extra.VT_REVIEWED";
    /** SHA-256 of the reviewed file (sent to the installer). The installer honours VT_REVIEWED only
     *  when the caller is {@code shiroikuma.oyokanri} and this hash matches its own of the file. */
    public static final String EXTRA_VT_SHA256 = "shiroikuma.extra.VT_SHA256";
    /** Fork (白い熊, +082): the user was shown installed-version → new-version for this exact file
     *  in this app's report dialog and pressed its install button, so the installer skips its own
     *  confirmation screen (under the same caller + hash conditions as VT_REVIEWED). It still asks
     *  when there is a decision this app did not put to the user (signature mismatch, splits…). */
    public static final String EXTRA_INSTALL_CONFIRMED = "shiroikuma.extra.INSTALL_CONFIRMED";
}
