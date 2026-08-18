<!-- SPDX-License-Identifier: GPL-3.0-or-later OR CC-BY-SA-4.0 -->

<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="白い熊 応用管理 app icon" />

# 白い熊 応用管理

**Android app manager & control panel — themed and tooled to taste.**

A fork of [AppManager](https://github.com/MuntashirAkon/AppManager) with **major additions**: a
**Shizuku mode of operation** that needs no listening `adbd`, a per-app
**battery history** that survives the charge cycle Android wipes it on, a per-app
**anti-snooping page** that can cut an app off the network entirely, **device-policy locks** that
Settings cannot undo, a configurable
**yellow-on-black UI** with a deep customization page, a hard-blocking **protected profile**, a
from-scratch **process monitor / reaper**, a **pausable batch-op dialog**, one-tap **main-list quick
actions**, readable **per-app backups**, a **remote-triggerable settings export**, and the **AM
Debug** toolset unlocked in a normal release build.

**📥 Latest release: [`4.1.0+2026-06-29.21-57.gfc1e7007+099`](https://github.com/ShiroiKuma0/shiroikuma-oyokanri/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/shiroikuma-oyokanri/releases)

<sub>Version reads as **upstream `4.1.0`**, rebased onto upstream commit **`fc1e7007` of 2026-06-29 21:57 UTC**, fork build **099**.</sub>

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

## 🕵️ Anti-snooping page (盗み見)

Every app-details page opens on the **Snooping** tab — first in the strip, because it is what this
fork is opened for — gathering the privacy-invasive capabilities that are otherwise scattered across
the App Ops and Permissions tabs — among hundreds of rows that mostly cannot be moved — into ten
groups ordered by **consequence, not by subsystem**:
sending data out, accessibility & notifications, watching the screen, location, microphone & camera,
messages & calls, personal data, files & media, nearby, running in the background.

**Network first, because nothing an app collects can hurt you until it can leave the phone.**
`INTERNET` is not a permission anyone can revoke — it is baked into the app's uid at install — so the
row is a firewall instead: allowed, *no background mobile data* (the per-uid network policy, with the
row naming the exact policy it got so it never claims more than it does), or **blocked on every
interface, foreground included**, using the same per-uid firewall chains a root firewall uses.

**App-ops are only half of it.** The capabilities that matter most on a modern phone are gated
somewhere else entirely, so a row drives whichever mechanism actually governs it: an app-op, a runtime
permission, or a **lever** — the `Settings.Secure` list that really decides whether an accessibility
service or a notification listener is running, the assistant role holder, the doze whitelist. The
accessibility and notification-listener rows switch off the two most invasive things an unprivileged
app can hold: one sees every window and keystroke, the other the content of every notification.

**The page shows only what is snooping, or what can be made to.** A row survives four questions: can
this phone move it, can the write land on *this* app, could the app ever use it, and is it still worth
showing? Ops that don't exist here are dropped; ops the platform redirects to a *different*
controlling op are dropped (they have no slot of their own and can never change — not for this app,
not for `adb`, not for root); permission-only rows are dropped where the platform would silently
re-grant what you revoked; and capabilities the app's own manifest rules out are dropped too — no
`RECORD_AUDIO`, no in-call microphone; no service bound with `BIND_VPN_SERVICE`, no VPN. A
zero-permission app goes from sixteen rows to **four**. Nothing is learned from a failed attempt here:
it is read from the manifest on every load, so an update that adds the missing permission or service
brings the row straight back.

**Rows report the system, not our bookkeeping.** They show the mode Android *actually enforces*, so a
switch never claims *Allowed* for something already being denied; every write goes to **both** the uid
and the package slot, because a block can live in either and clearing only one silently leaves it
standing; and a decision is recorded **only after the phone is asked whether it took**. If a
capability refuses to turn on at all, it is permanently safe — the row says so and leaves the page.

**Colour tells the story at a glance.** Red means the app can do this *right now*. A thick frame marks
a state you chose — red where you opened something the platform keeps shut, yellow where you closed
something it leaves open. Grey means off, and off is simply what a fresh install gives you.

**Every row says when it was last used — and when it was last refused.** *Used 3 hours ago · Denied
under a minute ago*, with **in the background** called out, because an app reaching for the microphone
while it is not on screen is a different fact from one doing it while you watch. A recorded denial is
the plainest proof a block is doing work, so it is the one thing on the row drawn in the theme colour;
a capability last touched four months ago is the argument for turning it off. And because a blank line
would read as *clean*, there are no blanks: each half says a time, or **never** — the system keeps that
record and it is empty — or **not recorded**, naming which of three reasons applies. The subtle one is
worth knowing: a denial is only counted when the app-op itself refuses, so where blocking works by
revoking a permission the refusal happens one layer earlier and can never be counted, however often the
app tries. The row says exactly that instead of claiming the app never tried.

It also lists what an app can reach **without asking for anything** — screen capture, clipboard reads,
assistant screen reads, VPN, accessibility, background activity — because "never requested" is not the
same as "cannot use". Behind *Show all capabilities* sit everything filtered out, usable as
**pre-sets**: block the microphone today on an app that has none, and the block lands the day an
update starts asking for one. And because a hand-written catalogue of platform constants rots quietly,
⋮ → *Ops not in the catalogue* asks the phone itself what it has that the page does not yet cover.

**Decisions are remembered, not just applied** — and only the ones that mean something. A setting that
matches the platform default is not stored at all, so set-then-unset leaves nothing behind. What is
stored is keyed by package name, so it outlives uninstalling the app, rides along in a settings export,
and is re-applied automatically when that package appears on another phone — on install, on update, and
on every startup once privileges are up. Set an app up once; it stays that way, everywhere.

**And when a decision stops holding, the page says so.** A remembered setting can be undone from
outside — by Settings, by the app asking again, by a write the platform accepted and later dropped —
and a switch that simply reports the live state hides exactly that. So a drifted row draws its box in
**red** and the pill spells out *what you asked for* beside what the system is doing; one tap puts it
back. ⋮ → *What the marks mean* is the legend for all of it, and it reads like a reference card
rather than a wall of text.

## 🔒 Device-policy locks — decisions Settings cannot undo

Everything above is **soft**: an app-op written or a permission revoked can be put back by Settings,
by another tool, and sometimes by the app itself. Make the sister app
[白い熊 雫](https://github.com/ShiroiKuma0/shiroikuma-shizuku) Device Owner and authorise this one, and
a decision here becomes **hard** instead.

A **padlock beside each switch** — hollow where a lock can land, filled where one does. Tap to lock,
tap again to release. A locked permission is fixed by device policy: the app cannot request it, its
switch in Settings is greyed out, and the lock **outlives this app** — uninstalling 白い熊 応用管理 does
not release it. A card above the capabilities says whether the powers are live and, when they are not,
*why*: no Device Owner on this phone, versus authorised in 雫 but not for us.

Every switch on that card names the **capability**, never the lock — *Can be uninstalled*, *Can be
force-stopped*, *Accessibility can be enabled* — so it reads exactly like the capability rows below
it: flipped right and red while the phone is still open to this, flipped left and yellow with a thick
frame once policy has shut it. None of them asks for confirmation; what a dialog would have said is
the box's own description, where it can be read before the tap instead of dismissed after it. Beside
them sit **suspension** — a harder freeze than hiding, where the app cannot be opened at all and the
system shows a stub in its place — and **clear all locks**, the way back that works even if this app
is gone.

The card also carries the two **ordinary** verdicts on the whole app, as pills that need no Device
Owner at all: **Freeze** — the same freeze as the main list's snowflake, grey while the app runs,
yellow and reading *Unfreeze* once it is shut — and **Uninstall**, red because it is the one thing on
the page that the control which did it cannot undo. Both refuse an app in the protected `必要`
profile, and an **i** in each pill explains it in full rather than spending three lines of the card
on prose you read once.

A lock can also be **remembered**: a ring around the padlock means it is put back if the platform
ever loses it. A hard lock cannot drift the way an app-op can — Settings will not lift it and the app
cannot — but it dies with a full uninstall, and everything the Device Owner holds dies with it. When
that happens the ring turns **red on a hollow padlock**: remembered, and gone. Nothing else on the
page could have told you.

Only what the platform will actually enforce is offered. Device policy's per-app lever is
`setPermissionGrantState`, which takes **dangerous runtime permissions and nothing else**, so
app-op-only rows (clipboard, screen capture, background running) show no padlock rather than a switch
that would be refused. That is the page's standing rule, applied here too.

**⋮ → What the marks mean** explains the whole vocabulary: card frame, remember box, padlock, status
pill, and what locking actually does to an app.

## 🎨 Configurable yellow-on-black UI

The fork's signature look: pure-black backgrounds with a bright `#FFFF00` foreground, applied across
the main list, app-details, profiles, settings, app usage, Finder, overflow menu, the installer banner,
**every** dialog (yellow border, yellow-outlined buttons), and the launcher / file-manager / TV-banner
icons. Toasts
are restyled as a black box with yellow text and border, and even the splash screen renders the app
name, status and version in yellow.

None of it is hard-coded. A dedicated **白い熊 応用管理 UI** page (long-press the toolbar overflow, or open
it from Settings) lets you tune, per element:

- **Fonts** — family, weight and size for the app label, package id, version, app-type, dates, UID,
  SDK, signature and the app-details header, with **`.ttf` / `.otf` import** from storage.
- **Colours** — per-element text/fill/border colours for labels, indicators, chips, the running box,
  the selected-card frame, separators and the process-monitor rows.
- **Main-list layout** — adaptive or a fixed **1 / 2 / 3 / 4-column** grid (one column gives the app
  name the whole row's surplus, with version and backup sized to their text at the card's edge), an **edge-to-edge separator
  grid** (configurable widths and colours), the **running-app box** (border width/roundness, yellow
  for user apps / orange for system), the **selected-card frame**, and the **app-icon size and
  roundness** (square → circle).

**One layout per screen shape.** The main list, the process monitor and the battery history each
remember their column layout **separately for every geometry** — folded, unfolded, portrait,
landscape, half a split screen — so the shape you are in shows what you last chose for it, across
restarts. A fold is recognised by measuring the window's shorter side rather than by asking a vendor
API, so a phone with three panels or none gets sensible slots for free.

The **top bar scrolls sideways** instead of hiding what will not fit — on the main list and on the
process monitor alike. Search field, process monitor, battery history, app usage, clear-filters, the
filter funnel and the layout picker all stay on the bar at full size on a folded panel; whatever runs
past the screen edge is reached by dragging the bar. Widen the screen and it goes back to filling the
width, with the search field stretching across the space the icons leave.

Changes apply live the moment you leave the screen, and a reference **legend** explains what every
colour and style means. A four-state main list — installed, frozen (snowflake + cool film), stopped,
uninstalled (dimmed + mauve film) — reads at a glance, with type-coloured italic labels.

## 🛡️ Protected profile (`必要`)

Put any app into an apps-profile named **`必要`** ("necessary") and it becomes **hard-blocked from
being frozen or uninstalled**. Enforcement sits at the two lowest-level chokepoints (freeze and
uninstall), so **no** UI path — single-app, batch, profile-apply, or anything added later — can bypass
it. Attempts are refused with a clear message that names the protected apps. It's the safety net that
lets you batch-freeze aggressively without ever clobbering something you depend on.

## 🔋 Battery history (電池)

Android keeps **ten days of daily discharge *rates* and zero days of per-app attribution** — every
counter that could name a culprit is wiped at the next full charge. So nothing on the phone can
answer "which app drained the battery last night". This screen can.

- **It samples in the background and keeps the history itself.** `BATTERY_STATS` is
  `signature|privileged|development` — the same level as `DUMP` — so the app grants it to *itself*
  the way it already grants `DUMP`. The platform persists that grant, so the counters are read
  **in-process** and sampling **survives reboots with no shell alive at all**. Readings are stored as
  *differences*, so the charge-cycle reset ends one bucket and starts the next instead of erasing
  everything.
- **It costs nothing to run.** A persisted periodic job every 15 minutes: no wakelock, no foreground
  service, no exact alarm. The counters are cumulative, so a Doze-deferred reading loses nothing — a
  monitor that woke the phone to measure why the phone was awake would be its own worst finding.
- **It refuses to invent numbers.** Where the device's `power_profile.xml` is stubbed — this phone
  reports a 5.00 mAh battery — no mAh is shown at all; the ranking comes from measured counters and
  is called *impact*, never power. Where the profile is real, mAh appears and can be sorted on. The
  drainer columns show **real battery percentage points**, not a share of whatever happened to be
  measured.
- **It tells you which lever to pull.** Each app's dominant counter maps to the control that
  addresses it — packets to the network lever, held wakelocks to `WAKE_LOCK`, background CPU to
  `RUN_ANY_IN_BACKGROUND`, sensor time to location — with freeze offered last, and only once
  something already scored high. A map app burning GPS needs a location lever, not removal.
- **Charts you can actually read**: a device battery/charging trace (charging stretches in red) and a
  per-app timeline, both placing bars by *real time* rather than index, so Doze gaps stay visibly
  empty. Pinch to zoom, drag to pan, tap to read, labelled axes.
- Optional **alerts** when an app crosses a threshold while you're not looking, a **before/after**
  comparison against the previous equal window, and a **screen-off-only** view for what happens in
  your pocket.

## 📊 Process monitor / reaper

A from-scratch replacement for the legacy "Running apps" screen, built for actually reaping memory:

- **PSS-ranked** memory (not RSS — PSS reflects what killing a process actually frees) and **live
  instantaneous CPU%** sampled from `/proc` ticks. It **opens sorted by CPU** — a reaper is opened
  when something is burning the phone right now — with one tap to rank by memory instead.
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
- A tap on the **app icon** opens that app's Snooping page; the rest of the icon column stays a
  freeze/unfreeze toggle, with an at-a-glance snowflake indicator.
- **Suspended apps read apart from merely frozen ones** at a glance: a violet padlock in place of the
  snowflake, the app's name struck through, and a heavier film over the row.
- Free-text **per-app notes**, shown on the row as a pill carrying the note's **first line** — you
  read the note without opening it. The app name keeps its full width and the note takes whatever is
  left, so it says as much as the row can fit. Also in app-details, and included in settings
  export/import.
- Per-row **profile pills**, right-aligned against the note's own edge — tap to filter, long-press to
  remove, "+" to add to a profile. More pills than fit scroll sideways instead of costing the row a
  second line.
- The **kernel UID leads the app ID** rather than trailing the install date, so the number reads as
  part of the identity it belongs to — and turns orange the moment a package shares a user ID with
  another.
- A **copy-all-displayed-IDs** toolbar action, a **multi-profile include/exclude filter** with a
  tri-state picker, and a fully **customisable bottom selection toolbar** (reorder/hide actions;
  long-press any button to open the editor).

## 💾 Backups & settings portability

- **Readable per-app backup folders** instead of the opaque `backups/<uuid>` layout, plus a **Backup
  directory** option that points the engine at a fast filesystem path (bypassing slow SAF volumes).
- Inline backup details in the main list (version / date / time). A **tap opens app info** like the
  rest of the row; a **long-press opens one dialog holding every backup action** — the app's backups
  as a tick list, with Restore, Delete, freeze/unfreeze and *Back up* all on it, so nothing drills
  down into a second screen.
- **Category-based settings Export/Import** at the top of the UI page: pick a directory once, see the
  latest export at a glance, then export or restore any mix of seven categories (general settings,
  colours & fonts, monitor, toolbar & filters, notes, anti-snooping settings, profiles) — so a new install comes up looking
  and behaving exactly like the old one.

## 🤖 Remote-triggerable export

The same export runs **headlessly** on request, so an automation app can back this app up without any
UI: a token-gated broadcast lists the exportable categories, then writes **exactly one** archive to a
requested directory and reports back its real path and byte size. Progress arrives as **real counts,
never a percentage**. The gate is an **Automation export** switch — **off by default** — plus a
24-byte token that is compared constant-time and deliberately kept **out of every backup archive**.

The category listing states **which items start ticked**, so the caller's picker takes its default
from this app rather than guessing. And a running export can be **cancelled from outside**: the
archive is written under a `.part` name and renamed only on success, so a cancelled run leaves the
backup directory exactly as it found it — no short archive that looks complete, no stray partial —
and the original request is answered `ERROR:cancelled` rather than quietly finishing.

## 🔌 Shizuku mode of operation

Upstream offers root and ADB-over-TCP. This fork adds a third: **take the privileges from a running
Shizuku server instead.** The capabilities are identical — uid 2000 either way, same app-ops
behaviour, same per-uid firewall access — but nothing has to listen on a TCP port for them. No
`adbd` in TCP mode, no port scan, no pairing keys, and the `INTERNET` permission leaves the privilege
path entirely.

It prefers 白い熊's own Shizuku fork, **白い熊 雫** (`shiroikuma.shizuku`), and falls back to stock
Shizuku when that isn't installed. Auto-detection **hunts for it ahead of ADB** — it waits for the
server's binder and asks for authorisation, so a *fresh install* lands in Shizuku mode by itself
instead of falling into ADB, which is the thing this mode exists to avoid. A refusal is remembered by
the server, not re-asked. If no server serves us this time the preference stays on auto rather than
locking itself into ADB, so starting the server later is enough — no trip into settings. Choosing the
mode explicitly shuts the ADB path down, because leaving a TCP listener up would defeat the point.

When it can't connect it says which of the three things is actually wrong — *not installed*, *not
running*, *not authorised* — since those send you to three different places.

**It also survives the server going away.** Updating or restarting a Shizuku server takes the
privileged session with it, and an app manager that quietly drops to no-root is worse than one that
never had privileges: every operation still looks like it worked. So the fork listens for the server
leaving *and* coming back, and rebinds by itself — authorisation is held by the server and survives
the restart, so there is nothing to approve. When it can't be recovered — a server that stays down, a
revoked authorisation — a **red bar** appears across the top of the app list saying the mode of
operation is no longer Shizuku, and tapping it retries.

---

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
root and ADB backends and the rest — goes to its author and contributors (the Shizuku
backend is this fork’s own addition; upstream deliberately does not ship one). Like upstream, this fork
is licensed under the **GNU General Public License v3.0** (see [`COPYING`](COPYING)).
