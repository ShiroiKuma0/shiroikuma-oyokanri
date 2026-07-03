<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Changelog

All notable fork changes are recorded here. Versions use the fork's
`customBaseVersionName+customBuildNumber` scheme (the base mirrors the upstream App Manager release
this fork is built on).

## 4.1.0+2 — 2026-07-03

A **rolling upstream sync** on top of 4.1.0+1: the fork's 97-commit stack was rebased onto the latest
upstream App Manager master (`fc1e70074`, three commits past the 4.1.0+1 base). **No fork feature
changed** — every customization is carried forward unchanged — but the build absorbs three upstream
commits and re-implements the fork's server-JAR handling on top of upstream's reworked build pipeline.

### Upstream improvements you get

- **Android 17 support** — fixes retrieving the installed-application list on Android 17 (new
  `IPackageManagerV37` / `PackageInfoList` hidden-API shims and a `PackageManagerCompat` branch).
- **Reworked `am.jar` / `main.jar` creation** — upstream rebuilt the server-JAR build task with proper
  Gradle input/output wiring, provider-based class directories, per-variant output, and a cleaner `d8`
  invocation (`--min-api` / `--lib`, deterministic sorted inputs).
- **Native build** — consistent C compiler and linker flags across ABIs.

### Fork re-implementation notes

- The fork's two server-JAR guards were **re-layered onto upstream's new task**: the
  configuration-cache-safe capture of SDK / build-tool / class-directory references (upstream reads
  them at execution time, which the fork's enabled configuration cache forbids), and the **atomic
  temp-then-move** of each JAR into `assets/` that stops a racing `mergeAssets` from packaging a 0-byte
  JAR (the ADB-mode landmine). The fork's single `tasks.matching` merge-ordering edge is kept as the
  source of truth; upstream's parallel `applicationVariants` edge was dropped as redundant.
- Build counter advanced to **`+2`** (versionCode `4500002`) so it installs over `4.1.0+1` as an
  upgrade.

## 4.1.0+1 — 2026-06-29

Rebased the entire fork onto upstream **App Manager 4.1.0** (the previous release was built on a
4.0.5-development master snapshot). **Every fork feature listed under 4.0.5+124 below is carried
forward unchanged** — this release is the fork rebuilt on the newer upstream, so it also picks up all
of upstream's improvements since. The fork's 94-commit stack was re-anchored onto upstream's
refactors — a `ListAdapter` / `DiffUtil` rewrite of the main list, a Material 3 rework of the settings
screens, and a rewrite of the profile-filter pipeline — with no loss of functionality.

### Upstream improvements you get with 4.1.0

- **Main list** migrated to `ListAdapter` / `DiffUtil` for smoother, diff-based updates; fixes for
  checking inactive apps under non-default users, filtering by a filter-based profile with a custom
  expression, the on-screen-keyboard logic, RecyclerView position restore after filtering, and the
  highlighting of filtered items; realtime search/filter is now debounced.
- **Material 3 preference screens** across Settings (App info, Profile configurations, and the rest).
- **Privileged server**: `run_server` is now a native executable and `main.jar` is copied via it; ADB
  connections use **HMAC-based mutual challenge-response authentication**, and agent mode inserts the
  password just once.
- **Scanner**: the Pithus scanner and its pinned certificates were removed.
- **Log viewer** scrolling/filtering fixes; **Finder / Debloater** navigation fixes; Debloater now
  lists uninstalled system apps; the **code editor** no longer crops symbols at large system font sizes.
- Hidden API updated from **Android 16**; assorted NPE / crash fixes (path parsing, `AppDb#findUsage`,
  APKS compiling, external cache creation in root mode); many translation updates.
- Upstream version bumped to **4.1.0** (versionCode 450) — the fork's `versionCode` is now
  `4500000 + buildNumber` (this build: `4500001`).

### Fork tooling

- New **`/upstream-new-version`** skill — one-command upstream sync: fetch-first detection against
  `upstream/master`, a pre-rebase summary to approve, a rebase that carefully re-implements every fork
  feature (stopping for approval on anything lossy or uncertain), the `+1` build-counter reset on a
  base bump, then build and deliver.
- Builds now **auto-deliver via `/after-build`** (adb-push to the phone if connected, else scp to the
  remote) instead of prompting how to deliver.

## 4.0.5+124 — 2026-06-21

First public release of **白い熊 応用管理**, 白い熊's downstream-renamed fork of
[App Manager](https://github.com/MuntashirAkon/AppManager), built on upstream **4.0.5**. Package
`shiroikuma.oyokanri`, arm64-v8a, installable side-by-side with the official
`io.github.muntashirakon.AppManager`. Everything below is what this fork adds on top of stock App
Manager.

### Theme & UI customization

- **Yellow-on-black theme** applied across the main list, app-details, profiles, settings, overflow
  menu, the installer master/per-row toggles, and the search bar (pure-black interior, no border).
- A configurable **白い熊 応用管理 UI** page (long-press the toolbar overflow, or via Settings; Back from it
  returns straight to where it was opened from):
  - **Per-element fonts** — family + weight + size for the app label, package id, version, app-type,
    install date, UID, target SDK, signature, backup info, and the app-details header; **`.ttf` /
    `.otf` import** via SAF. Live refresh on leaving the screen.
  - **Per-element colours** — text/fill/border colours for list labels, indicators, chips, the add-pill,
    the running box, the selected-card frame, separators, and the process-monitor rows, with an
    HandyRSS-style settings layout (underlined headers, indented controls).
  - **App-icon size and roundness** sliders (square → circle), merged into a single "Main app list"
    settings group.
- **Main-list layout picker** in the top bar — adaptive (one column per 450 dp) or a fixed 2 / 3 / 4
  column grid.
- **Edge-to-edge separator grid** with configurable horizontal/vertical widths and colours; a
  configurable **selected-card frame** (colour + border width + corner roundness); a **running-app box**
  (yellow for user apps, orange for system) with configurable width and roundness.
- **Four-state main list** — dormant films (mauve for uninstalled, cool for frozen), type-coloured
  italic labels, a running-row box, and a reference **legend** explaining every colour/style.
- Larger main-list app icons (dedicated 60 dp dimen) with the freeze indicator centred beneath.
- **Themed dialogs** — yellow window borders and yellow-outlined buttons (OK / Cancel / Neutral), a
  2 dp yellow border and yellow selected-radio on alert dialogs; M3 tonal-elevation overlay disabled to
  kill the olive tint.
- **Themed toasts** — black box, yellow text and border — including the export/import and other stray
  flashes; batch-op completion shown as a themed toast when foreground.
- **Splash screen** renders the app name, status and version in yellow; first-run changelog snackbar
  disabled.
- Restyled launcher, file-manager and TV-banner icons in the black/yellow palette.

### Protected profile (`必要`)

- Apps in an apps-profile named **`必要`** are **hard-blocked from being frozen or uninstalled** at the
  two low-level chokepoints, so no UI path (single, batch, or profile-apply) can bypass it; refusals
  name the protected apps.

### Process monitor / reaper

- A from-scratch **process monitor** screen replacing the legacy "Running apps" screen.
- **PSS-ranked** memory and **live instantaneous CPU%**; an **in-use / foreground protection** so the
  on-screen app and media/nav processes can't be one-tap killed.
- A **smart kill router** (force-stop for app packages, signal for orphaned shells), an **editable
  protected set** plus overridable built-in denylist, and a fix so the monitor never kills its own
  command shell.
- **Leak detection & grouping** with a configurable threshold and optional minimum age.
- A per-process **detail screen**, **app-process grouping** (collapse multi-process apps by package),
  a **faceted filter** (killability × type) and a **toolbar search**.

### Batch operations

- An in-app **batch-progress dialog** with **Pause / Continue** and **Cancel**, mirroring the
  notification, showing the current app under the counter.
- Main list **snaps to its final state in one pass** after batch reinstall and uninstall.
- Themed **batch-confirm dialogs** for uninstall, reinstall, freeze and unfreeze.
- Added batch **reinstall (system apps)**, batch **unfreeze**, and batch **remove-from-profile**
  selection-toolbar actions; the batch Freeze action honours "Skip freeze method dialog".
- A floating **selection reminder** so an active selection can't scroll out of sight; the multi-selection
  is cleared after every action (fixes a carry-over that applied ops to the wrong apps).

### Main list & profiles

- One-tap **force-stop ✕** on running app rows (beside the freeze snowflake under the icon).
- **Per-app notes** on the main list and in app-details (covered by settings export/import).
- **Freeze indicator** with auto-refresh; whole icon column is a freeze-toggle target (system apps
  included); ice-blue for the frozen state.
- **Profile-membership pills** per row (tap to filter, long-press to remove, "+" to add), refreshed
  after add and on resume; a **multi-profile include/exclude filter** with a tri-state picker.
- A **customisable bottom selection toolbar** — reorder / hide actions from Settings, long-press any
  toolbar button to open the editor.
- Inline backup details in the right column (version / date / time; tap = new backup, long-press =
  restore/delete).
- A **copy-all-displayed-IDs** toolbar action; a filter-active indicator and a **clear-all-filters**
  action; version-aware main-list icon cache so reinstalls refresh icons.

### Backups & settings portability

- **Readable per-app backup folders** instead of the `backups/<uuid>` layout; a **Backup directory**
  option that overrides the SAF backup volume with a fast filesystem path; the startup backup-volume
  check removed.
- A **Skip backup method dialog** toggle and a tap-to-back-up affordance for apps without a backup.
- **Settings export/import** bundling App Manager settings + the fork's profiles, colours and fonts
  (import applies reliably to toolbar/colours/fonts).

### Freeze

- A **Skip freeze method dialog** preference (Settings → Rules); fixed the switch resetting to OFF on
  reopen; relabelled the Freeze button.

### Debug features

- App Manager's debug-only toolset (**Finder**, **Crazy Logger**, scanner **missing-classes**,
  **historical operations**, the **Unfrozen** filter) unlocked in a normal signed release, with the
  upstream **build-expiry checker neutered**.

### Localization & de-branding

- App labelled **白い熊 応用管理** (package `shiroikuma.oyokanri`); **Japanese** strings for the
  freeze/unfreeze/stopped filter and toast surfaces; the new-issue/report form de-branded to this fork.

### Build & infrastructure

- Custom build numbering (`tools/bump-build.sh`); Gradle **configuration cache** enabled; build-log
  warning noise suppressed so real errors stay visible.
- Fixed an intermittent **empty server JAR** in release builds (which silently broke ADB mode) via
  explicit `mergeAssets` ordering and atomic JAR writes.
- Agent config (`CLAUDE.md` + `.claude/skills/`), including a `publish-version` skill.
