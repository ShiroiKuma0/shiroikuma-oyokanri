# VirusTotal scan-and-report (`scanner/vt/`) — the APK-vetting chain (白い熊, +080, 2026-11-14)

> Moved verbatim out of `CLAUDE.md` (2026-11-15) to keep that file under the size limit. `CLAUDE.md` holds the index; add new knowledge about this area **here**, not there.


This app is the **scan and report home** of a three-app chain: tap an APK in 白い熊 書類管理
(`shiroikuma.shoruikanri`) → full VirusTotal scan here → report with a verdict → **Install** pill
hands the file to 白い熊 汎用設置 (`shiroikuma.universalinstaller`). Built from
`~/.claude/plans/apk-vetting-oyokanri.md` (master `nifty-herding-valley.md`). The other two apps are
built by their own chats; **the shared contract names are identical across all three and must never
be renamed on one side** — they live in `scanner/vt/VtContract.java`.

- **The contract.** Scan request → this app's `VtReportActivity` (new, **exported**, always-enabled —
  a separate component from `VtHistoryActivity`, which `FeatureController` toggles, so a scan request
  works whatever the feature switches say): action `shiroikuma.oyokanri.action.VT_SCAN`, a
  `content://` data URI (MIME + `FLAG_GRANT_READ_URI_PERMISSION`), extra `shiroikuma.extra.REAL_PATH`
  (absolute path when known — preferred over the URI, all three apps hold all-files access). Install
  hand-off → `shiroikuma.universalinstaller` / `…presentation.install.DialogInstallActivity`,
  `ACTION_VIEW`, our own `FmProvider` content URI over the real path, extras `REAL_PATH`,
  `shiroikuma.extra.VT_REVIEWED` (true) and `shiroikuma.extra.VT_SHA256` (lowercase hex). The extra
  names carry the family's `shiroikuma.extra.*` namespace, **not** this app's applicationId, because
  they travel between apps.
- **LANDMINE — the Install hand-off is identity-sensitive (installer chat, 2026-11-14).** On the
  phone (API 31) the installer trusts only the system-recorded caller: `Activity.getReferrer()` below
  API 34. So `VtReportActivity.installFile(...)` MUST be a plain `startActivity` from the Activity
  (not a chooser, PendingIntent or trampoline) and MUST NOT set `EXTRA_REFERRER`/`EXTRA_REFERRER_NAME`
  (those override the system value and the reviewed hand-off is then ignored). It resolves the
  installer by explicit component and **falls back to this app's own `PackageInstallerActivity`** when
  it does not resolve.
- **The store is a real store now, not a scratch cache.** `VtScanRepository.MAX_FILES` is 5 000 (was
  100). Files are keyed by SHA-256; the raw report JSON is kept per file (`vt_file.latest_report_json`)
  and per attempt. The whole existing pipeline (client `VirusTotal`, foreground `VtScanService`, Room
  `vt_file`/`vt_file_source`/`vt_scan_attempt`, `VtHistoryActivity`) is upstream 4.1.1 code the fork
  extended; the report destination is now `VtReportActivity` (it **replaced `VirusTotalDialog`** from
  the scanner card, the history rows and the scan notification, merging the duplicated
  `ScannerFragment.publishVirusTotalReport` + `VtHistoryActivity.displayReport`).
- **The verdict is `VtVerdict.java` — all thresholds in one place, computed from the stored JSON** so
  a file can be re-judged without a rescan (白い熊 tunes it on real files). One vote per engine family
  (Avast+AVG, BitDefender+licensees, McAfee+Skyhigh, K7+K7GW); each label is noise (riskware/patcher/
  PUA/adware/generic) or severe (banker/spy/stealer/RAT/ransom/SMS/dropper/named family); reads
  `popular_threat_classification` + `sandbox_verdicts`. Levels NOT_ANALYSED/INCOMPLETE/CLEAN(_
  PROVISIONAL)/RISKWARE/SUSPICIOUS/MALICIOUS. **A crack's riskware-only labels are RISKWARE, never
  Malicious**; a report with zero engine results is NOT_ANALYSED, never Clean. Never rely on
  `VtAvEngineStats.getTotal()`/`getDetected()` (they omit timeouts and count malicious only).
- **Reliable big uploads (`VirusTotal.uploadAnyFile`).** `setFixedLengthStreamingMode(total)` so a
  300–650 MB body is streamed, not buffered; a counting copy feeds `UploadProgressListener` (the live
  % is published per attempt via `VtScanService.getUploadPercent`, which the report screen reads — the
  DB only carries the coarse status); connect/read timeouts on every connection; plain
  `filename="…"` (never RFC 5987 `filename*`, which VirusTotal rejects) and **no**
  `Content-Transfer-Encoding: chunked` part header. Upload is from the original path when readable
  (`resolveSource`/`observeUri` only stage a `content://` whose grant may lapse), and the file is
  hashed once.
- **Attempts park rather than fail (`VtScanService`).** Statuses `pending_key` (no/invalid key — item
  10) and the `next_attempt_at` back-off (quota — item 2) are in the active set, so the service's
  dispatcher skips them until runnable and re-dispatches via `mScheduler`. A **minute** quota
  (≤ 5 min) keeps the service alive; a **daily** quota (parsed from the message → next UTC midnight)
  parks and lets the service stop, resuming on the next start. A 401 parks `pending_key` (a
  notification/the report screen reopens the key dialog; `resumeKeyParkedAttempts` + a key save resume
  it). Quota is never reported as a bad key. Over 650 MB → `TooLarge` fail (local analysis only, no
  upload — item 9).
- **The key (`VtApiKeyStore` + `VtApiKeyDialog`).** Stored **encrypted** with an Android Keystore
  AES/GCM key (`enc:v1:iv:ct`); `Prefs.VirusTotal.getApiKey()` decrypts and migrates a legacy plain
  value in place; Settings shows it **masked to the last four**. `VtApiKeyDialog.prompt(...)` is the
  shared "ask before the scan" dialog (field, Get-a-key link, validate with one cheap authenticated
  call `VirusTotal.checkApiKey()`); a save **enables the feature switches the check requires**
  (`FEAT_INTERNET`, `FEAT_VIRUS_TOTAL`) and resumes the parked scan, so the same file continues with
  nothing re-tapped.
- **Bundles (`VtBundle`).** A `.apks`/`.xapk`/`.apkm` is unpacked locally; each code-bearing APK
  (base, ABI split, feature splits — language/density splits skipped) is observed by its own hash
  (`vt_file.bundle_of` = the container's SHA-256) and scanned; the container (`vt_file.is_bundle`) is
  never uploaded and the report shows the parts with the worst part's verdict on top. OBB code
  (`.dex`/`.so`/`.apk`/`.jar`) is listed locally only.
- **The report is a framed DIALOG, and it must name every outcome (白い熊, +081).** `VtReportActivity`
  runs under `AppTheme.YellowOnBlack.Floating` (translucent + dimmed; set in the manifest AND in
  `AppearanceUtils`, which overrides the manifest theme) and draws one almost page-big panel with a
  rounded frame in the fork's ink. **LANDMINE — `ColorCodes.getListItemDefaultIndicatorColor` is a
  SURFACE colour**, near-black on this page: used as the "muted" ink it drew the *Report unavailable*
  card, every muted line and the Cancel pill invisibly, which is why a failed scan showed a blank
  gap and nothing else. The quiet ink is `ColorPrefs.SETTINGS_DESCRIPTION`. A scan that ended with
  no report shows the **last attempt's own error** (`VtScanRepository.getLatestAttempt`) with Retry;
  a parked attempt shows *why* (`setNextAttemptAt(id, at, reason)` stores the reason in
  `error_message` under `CODE_WAITING`, cleared by `setAttemptStatus`). The view model polls every
  second, so `render` rebuilds only when `signatureOf(state)` moves and otherwise touches just the
  live widgets (`updateLive`) — or the `VtGaugeView` ring restarts its sweep every second.
  **Two service faults fixed with it:** a lookup that found the file *known but not yet analysed*
  (HTTP 200, `last_analysis_date` 0 — the state after an upload whose polling timed out) fell
  through to `error(response)` with no error, i.e. "VirusTotal request failed."; it now waits for
  the report. And the analysis poll gave up after 10 min, which a large APK routinely exceeds —
  `MAX_ANALYSIS_POLLS` is 60 (30 min). *Open on VirusTotal* is a footnote of the details card and
  exists only once a report does.
- **The install happens HERE; the hand-off to the universal installer is retired (白い熊, +083).**
  The report dialog's foot holds every decision (`setActions`: the state's actions in one row, main
  action last, a full-width Cancel beneath — no ✕, no second pop-up for the upload question), an
  app card on top shows icon · label · package · installed → new version and which of new install /
  update / same version / downgrade it is (`VtReportViewModel.AppFacts`), and the install button
  (labelled Install/Update/Reinstall/Downgrade) calls `VtReportActivity.installFile(…, confirmed)` →
  `PackageInstallerActivity.getConfirmedInstance`, which skips the what's-new and confirmation
  steps but still asks about splits, an uninstall-first downgrade and a signature mismatch. **The
  "confirmed" mark is an in-process static (`sConfirmedUri`), never an intent extra** — the installer
  activity is exported, and an extra would let any app start an install with the confirmation off.
  Installing here is also what makes the per-app install source (+055) apply. The Package installer
  feature is switched back on once by `FeatureController.migrateInstallerBackOn` (run before
  `reassertDisabledComponents`, guarded in the device-local `shiroikuma_migration` prefs). The
  `INSTALL_CONFIRMED` extra added in +082 and honoured by universal installer 1.18.0+004 is no
  longer sent by anything here; the contract names stay reserved.
- **DB — `M_12_13` (version 13).** Adds `vt_scan_attempt.next_attempt_at`, `vt_file.auto_refresh_at`
  (the one-shot post-upload refresh ~4 days on — item 7), `vt_file.is_bundle`, `vt_file.bundle_of`.
  Follows the fork rule: **never renumber a live migration**, and each `@ColumnInfo(defaultValue)` must
  match the `ALTER … DEFAULT` exactly or Room refuses to open the DB at start (see the battery
  `M_8_9` note). Settings (upload-automatically default **on** = prompt-before-upload default off;
  Wi-Fi-only; rescan-age days) are on the 白い熊 応用管理 UI page.

- **+111 — a confirmed install sometimes ended in a notification instead of the dialog (白い熊, 2026-11-17).**
  `PackageInstallerActivity.launchInstallerService` treated `mService == null` as “install in the background”:
  it dropped the listener and closed the window, so `PackageInstallerService.finishInstallation` found no
  listener and posted “… installed”. The connection from `bindService` in `onCreate` is asynchronous; with the
  +083 confirmed path nothing stands between opening and installing, so on a busy phone the install could be
  submitted first. Now that case sets `mAwaitingService`, shows the progress and lets `onServiceConnected`
  attach the listener — **without asking `hasOperation()`**, because the operation may not have reached the
  service's `onStartCommand` yet and would be reported as aborted. Diagnosed from the code, not reproduced.
- **+113 — the “Set up VirusTotal” state has the install button too (白い熊, 2026-11-17).** With no API key the
  dialog offered only Set up / Cancel, so a phone without a key (the rooted Samsung) could not install from the
  vetting dialog at all. `STATUS_PENDING_KEY` now passes `install` to `setActions` like the consent state does.
- **+114 — the install button is always the last (right, main) action (白い熊, 2026-11-17).** In the no-key,
  upload-question and not-scanned/failed/cancelled states it was first and the scan action was the main one;
  under a report it was already last. `setActions` makes the last action the thick one, so install is now the
  main action in every state that has it.
- **+115 — “Waiting for upload consent” notifications stayed after the install (白い熊, 2026-11-17).**
  `ScanProgress` is a **sub**-handler of `NotificationProgressHandler`; its `onDetach(null)` removes
  nothing — only `onResult(…)` cancels the progress entry. A scan that ended without a result
  (`parked(…)`: consent, quota, Wi-Fi) therefore left its ongoing entry behind for good. `detach()`
  now calls `onResult(null)` first. A consent park goes through `needsConsent()`: nothing while the
  report dialog or the history is visible, else one auto-cancel notice that opens the dialog. And the
  install button in the consent state calls `rejectUpload` first — installing without uploading is
  the answer “no”; before, the attempt stayed `pending_consent` for ever.
- **+116 — a plain APK open goes through the report dialog too (白い熊, 2026-11-17).** The dialog
  depended on the sender knowing `ACTION_VT_SCAN`: an old 書類管理 (1.7.4+24, on a second phone) sent
  `ACTION_VIEW` straight to `PackageInstallerActivity` and got the bare installer.
  `PackageInstallerActivity.handOverToVetting` now forwards an external `VIEW`/`INSTALL_PACKAGE` to
  `VtReportActivity` as a scan request and finishes. **Only with a real readable path** (the
  `REAL_PATH` extra, a `file://` URI, or `readlink /proc/self/fd/N` of the opened content URI) —
  without one the dialog has no install button, so the installer is shown as before. **Never
  redirected:** the confirmed mark (`sConfirmedUri`) and every in-app launch (those carry no action),
  which is what keeps the dialog's own install from looping. A later intent to the already running
  singleInstance installer (`onNewIntent`, the queue) is not redirected either.
- **+117 — a cancelled scan kept a notification and looked alive (白い熊, 2026-11-18).** Three causes.
  (1) `ScanProgress.cancelled()` posted a **result** entry — file name, “Cancel scan”, with a Cancel
  scan button, not auto-cancel — whenever no VirusTotal screen was visible, which is exactly the case
  after cancel → install; it now posts nothing. (2) The progress entry was withdrawn only when the
  scan thread returned, and a thread inside an `HttpURLConnection` call does not notice an interrupt:
  `VtScanService.cancelAttempt` now withdraws it at once through `mProgressByAttempt`, and
  `update/complete/failed` are no-ops after that. (3) A report arriving after the cancel overwrote
  `STATUS_CANCELLED` with completed and announced a result: `completeAttempt`/`failAttempt` leave a
  cancelled attempt alone and `execute` re-reads the status before completing. **+118:** the service's own
  foreground entry (“VirusTotal — running”) goes with the cancel as well — `stopForeground(REMOVE)` when
  every running scan is in `mCancelledIds`; the service lives on without an entry until the thread
  returns, and `mForeground` makes the next `ACTION_RUN` or dispatched scan call `startForeground` again.
- **+119 — the cancelled state (白い熊, 2026-11-19).** No cross: `addStateHero` has an overload with a
  ring colour and an icon; “Scan cancelled” draws a blue ring (`CANCELLED_RING`, `#2979FF` — the update
  pill's `#0D47A1` ink is too dark on black) around the app's icon from `state.app.icon`, and falls
  back to the cross only when the file has no readable icon. Frame and headline keep the dim colour.
