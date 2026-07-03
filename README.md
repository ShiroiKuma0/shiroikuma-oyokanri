<!-- SPDX-License-Identifier: GPL-3.0-or-later OR CC-BY-SA-4.0 -->

<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="白い熊 応用管理 app icon" />

# 白い熊 応用管理

**Android app manager & control panel — themed and tooled to taste.**

A fork of [AppManager](https://github.com/MuntashirAkon/AppManager) with **major additions**: a
configurable **yellow-on-black UI** with a deep customization page, a hard-blocking **protected
profile**, a from-scratch **process monitor / reaper**, a **pausable batch-op dialog**, one-tap
**main-list quick actions**, readable **per-app backups**, and the **AM Debug** toolset unlocked in a
normal release build.

**📥 Latest release: [`4.1.0+2`](https://github.com/ShiroiKuma0/shiroikuma-oyokanri/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/shiroikuma-oyokanri/releases)

</div>

---

## Installs side-by-side with official App Manager

This fork ships as **`shiroikuma.oyokanri`** (label **白い熊 応用管理**), so it installs **right next to**
the official `io.github.muntashirakon.AppManager` from F-Droid — two apps, no conflict. It's signed
with its own key, so it never installs *over* official App Manager (Android refuses mismatched keys);
grab the APK from the [releases page](https://github.com/ShiroiKuma0/shiroikuma-oyokanri/releases) and
install it as its own app. (The package was renamed from the earlier `shiroikuma.appmanager`, so it's
a fresh install rather than an update.)

Built for **arm64-v8a**. App Manager itself is unchanged underneath — same component browser, same
root/ADB/Shizuku backends, same backup engine — this fork adds a thick layer of personalization plus a
few control-panel tools on top.

## 🎨 Configurable yellow-on-black UI

The fork's signature look: pure-black backgrounds with a bright `#FFFF00` foreground, applied across
the main list, app-details, profiles, settings, overflow menu, the installer banner, dialogs (with
yellow borders and yellow-outlined buttons), and the launcher / file-manager / TV-banner icons. Toasts
are restyled as a black box with yellow text and border, and even the splash screen renders the app
name, status and version in yellow.

None of it is hard-coded. A dedicated **白い熊 応用管理 UI** page (long-press the toolbar overflow, or open
it from Settings) lets you tune, per element:

- **Fonts** — family, weight and size for the app label, package id, version, app-type, dates, UID,
  SDK, signature and the app-details header, with **`.ttf` / `.otf` import** from storage.
- **Colours** — per-element text/fill/border colours for labels, indicators, chips, the running box,
  the selected-card frame, separators and the process-monitor rows.
- **Main-list layout** — adaptive or a fixed **2 / 3 / 4-column** grid, an **edge-to-edge separator
  grid** (configurable widths and colours), the **running-app box** (border width/roundness, yellow
  for user apps / orange for system), the **selected-card frame**, and the **app-icon size and
  roundness** (square → circle).

Changes apply live the moment you leave the screen, and a reference **legend** explains what every
colour and style means. A four-state main list — installed, frozen (snowflake + cool film), stopped,
uninstalled (dimmed + mauve film) — reads at a glance, with type-coloured italic labels.

## 🛡️ Protected profile (`必要`)

Put any app into an apps-profile named **`必要`** ("necessary") and it becomes **hard-blocked from
being frozen or uninstalled**. Enforcement sits at the two lowest-level chokepoints (freeze and
uninstall), so **no** UI path — single-app, batch, profile-apply, or anything added later — can bypass
it. Attempts are refused with a clear message that names the protected apps. It's the safety net that
lets you batch-freeze aggressively without ever clobbering something you depend on.

## 📊 Process monitor / reaper

A from-scratch replacement for the legacy "Running apps" screen, built for actually reaping memory:

- **PSS-ranked** memory (not RSS — PSS reflects what killing a process actually frees) and **live
  instantaneous CPU%** sampled from `/proc` ticks.
- A **smart kill router** — app packages get force-stopped, orphaned shell processes get a signal —
  with a built-in denylist (Shizuku, the privilege chain, IMEs, the launcher…) plus a **user-editable
  protected set** (tap to Protect/Allow, long-press to override even the built-ins).
- **Leak detection & grouping** — clusters of identical orphaned shells collapse into one "comm ×N"
  row with a configurable threshold and optional minimum age; multi-process apps collapse by package.
- **Actively-in-use protection** so you can't one-tap kill the foreground app or something playing
  media, a **per-process detail page** (memory / CPU / scheduling / lifecycle / security / app
  metadata), a **faceted filter** (killability × type) and a **toolbar search**.

## ⏯️ Batch-op progress dialog

Long batch operations get an in-app pop-up that mirrors the notification, with **Pause / Continue**
and **Cancel** — so you can hold a freeze/backup mid-run or stop it cleanly. On completion the main
list **snaps to its final state in one pass** instead of repainting row-by-row over several seconds.

## ❌ Main-list quick actions

The app list does more without a trip into details:

- A one-tap **force-stop ✕** on every running app (next to the freeze snowflake under the icon).
- A freeze/unfreeze toggle on the whole icon column, with an at-a-glance snowflake indicator.
- Free-text **per-app notes** (on the list and in app-details; included in settings export/import).
- Per-row **profile pills** — tap to filter, long-press to remove, "+" to add to a profile.
- A **copy-all-displayed-IDs** toolbar action, a **multi-profile include/exclude filter** with a
  tri-state picker, and a fully **customisable bottom selection toolbar** (reorder/hide actions;
  long-press any button to open the editor).

## 💾 Backups & settings portability

- **Readable per-app backup folders** instead of the opaque `backups/<uuid>` layout, plus a **Backup
  directory** option that points the engine at a fast filesystem path (bypassing slow SAF volumes).
- Inline backup details in the main list (version / date / time), with **tap = new backup** and
  **long-press = restore/delete**, and a tap-to-back-up affordance for apps that have none.
- **Settings export/import** that bundles App Manager's settings together with the fork's profiles,
  colours and fonts, so a new install comes up looking and behaving exactly like the old one.

## 🔧 AM Debug features in a release build

The fork exposes App Manager's debug-only toolset in a normal signed release: **Finder** (find app
components/permissions across all apps), the **Crazy Logger**, the scanner's **missing-classes** view,
**historical operations**, and the **Unfrozen** filter — and it **neuters the upstream build-expiry
checker**, so the release never "expires".

## 🇯🇵 Japanese label & de-brand

The app is labelled **白い熊 応用管理** with Japanese strings for the freeze/unfreeze/stopped surfaces, and
it's fully de-branded to this fork — package, label, launcher icon, and the new-issue/report links all
point here rather than at upstream.

---

## Built on App Manager

This is a downstream personalization of [App Manager](https://github.com/MuntashirAkon/AppManager) by
Muntashir Al-Islam. All credit for App Manager — the component browser, the backup engine, the
root/ADB/Shizuku backends and the rest — goes to its author and contributors. Like upstream, this fork
is licensed under the **GNU General Public License v3.0** (see [`COPYING`](COPYING)).
