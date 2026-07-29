<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Changelog

All notable fork changes are recorded here. Versions use the fork's
`customBaseVersionName+customBuildNumber` scheme (the base mirrors the upstream App Manager release
this fork is built on).

## 4.1.0+42 — 2026-07-29

The fork learns to answer a question the platform refuses to: **which app drained the battery while
you weren't looking**. Android keeps ten days of daily discharge *rates* and zero days of per-app
attribution — every counter that could name a culprit is wiped at the next full charge — so this
build samples those counters itself, stores the differences, and turns the result into something you
can act on rather than just read.

### 🔋 Battery history (電池) — the new screen

- **A new top-level screen**, entered from a battery icon on the main toolbar beside the process
  monitor. Ranked per-app list over a selectable window (15 min → 14 days), with search, sort
  (impact / packets / bytes / wakelock / CPU / mAh where real), an adaptive or fixed 2–4 column grid,
  and a screen-off-only view for what happens in your pocket.
- **Self-granting privileges.** `android.permission.BATTERY_STATS` is
  `signature|privileged|development` — the same protection level as `DUMP`, which the fork already
  grants itself. `SelfPermissions.init()` now grants it too, the platform persists the grant, and the
  counters are then dumped **in-process** from the `batterystats` binder. Sampling therefore
  **survives a reboot with no shell alive at all**; the privileged-shell path remains as the fallback
  for the window before the grant lands.
- **A sampler that costs nothing.** A `setPersisted` periodic `JobScheduler` job, 15 minutes by
  default: no wakelock, no foreground service, no exact alarm. Battery counters are cumulative, so a
  Doze-deferred reading loses nothing — it only widens a bucket.
- **Storage is deltas, not absolutes** (`battery_sample`, Room v8 → v10). Reset detection compares
  the dump's own "Start clock time" plus time-on-battery; on a reset the absolutes are written as one
  bucket rather than dropped, because the hours around a charge are the ones worth seeing. A dump
  that fails to parse writes nothing — an all-zero bucket would read back as a quiet hour that never
  happened. Configurable retention (1–60 days) with automatic pruning.
- **mAh only where the power model is real.** The gate is the dump's own reported profile capacity
  (≥ 100 mAh — no phone has a smaller battery); the Mate XT reports **5.00 mAh** and a computed drain
  of zero, so on that device no mAh is shown anywhere and the ranking is a composite of measured
  counters, labelled *impact*, never power. The flag is stored **per bucket**, so a history spanning
  several phones can never make old zero-mAh rows look like "used no power". Where the profile is
  real, mAh is shown and offered as a sort key.
- **Real battery percentages for the drainers.** The header's columns show `share × the drop the
  device actually recorded` — percentage points of battery — instead of a share of whatever was
  measured, which reads as alarming even when the phone lost three points all day. Only decreases
  between two consecutive non-charging readings count, so a charge mid-window cannot cancel the
  discharge either side of it.
- **Open-ended metrics.** Beyond the fixed columns each bucket carries a JSON map of everything else
  the platform offered — per-component mAh, job/sync time and counts, process-state times, Wi-Fi
  running/lock/scan, Bluetooth scan, audio, video, camera, flashlight, vibrator, radio wakeups — and
  unknown keys still render, so a counter a future Android release starts printing shows up instead
  of being silently dropped.

### 🎯 Acting on it, not just reading it

- **A per-app control panel** replaces the read-only dialog: recommendations, a zoomable history
  chart, every control, and the full measurement list.
- **Recommendations map the dominant counter to the lever that addresses it** — sustained packets to
  the network lever, held wakelocks to `WAKE_LOCK`, background CPU to `RUN_ANY_IN_BACKGROUND`,
  foreground-service time to `START_FOREGROUND`, sensor time to location, a Doze exemption on a busy
  app to dropping it. **Freeze is offered last and only after something already scored high** — a map
  app burning GPS needs a location lever, not removal. All thresholds are *rates*, so an app measured
  over four minutes and one over four days are judged alike. Recommendations that map to a switch
  carry the switch inline.
- **Controls**: freeze (through the same chokepoint, so the `必要` profile still refuses), the
  three-state network lever, Doze exemption, `RUN_ANY_IN_BACKGROUND`, `RUN_IN_BACKGROUND`,
  `START_FOREGROUND`, `WAKE_LOCK`, force-stop. Every op write is **re-read afterwards** and reports
  whether the platform actually complied — `AppOpsService` accepts writes it silently discards.
- **Alerts** (off by default) run inside the sampler, since the drain that matters happens in a
  pocket. Judged over a trailing hour with a coverage gate and a six-hour per-uid cooldown, with a
  per-app ignore list, opening straight into that app's panel.
- **Before/after comparison** against the equally long preceding window, in rates rather than totals
  so a shorter earlier window cannot invent an improvement.

### 📈 Charts

- Bars are placed **and sized by real time**, not by index: Doze makes buckets wildly uneven, and an
  index axis would erase exactly the idle gaps under investigation. Pinch to zoom (focus-preserving),
  drag to pan, double-tap to fit, tap to read a bucket.
- A **device battery trace** over the retained history, opening on the last 24 h and draggable
  further back, with **charging stretches in red** — a discharge chart that silently merges charging
  shows a line going *up*, and a reader who sees that stops trusting all of it.
- Labelled axes on both: battery percent on the device chart, and **awake-equivalent time** on the
  per-app chart (wakelocks + radio-active + CPU + sensors, plus 2 ms per packet), with a legend
  saying so and stating that it is not mAh.
- **Top drainers** panel beside it, horizontally scrollable, colour columns with the window's real
  percentage points.

### 🎨 Presentation

- Battery rows are **the main list's own card** — same layout, same freeze snowflake and force-stop
  ✕ in the same places, the note "+", the profile tags, the frozen film and italics, the
  type-coloured border — with only the right-hand column swapped for drain. The main-list item
  derivation was lifted to a shared static so the card cannot drift from the list it copies.
- Frozen apps resolve correctly here: a package frozen by *hiding* is invisible to a plain lookup, so
  the very apps you froze for draining used to lose their name and icon.
- The whole page scrolls, header included; the header spans all columns in grid layouts.

### 🔧 Fixes and behaviour

- **`item_main.xml` declared `layout_width="match_parent"` together with a `layout_weight`** on both
  weighted columns. In a horizontal `LinearLayout` that makes each child's base width the whole row,
  so the leftover space is negative and the weights distribute *that* — the heavier column shrank
  most. It is why the right column clipped while the middle kept a wide gap. Both are now `0dp`.
- **The list layout picker (3×3 grid) moved onto the main toolbar for good** — it was `ifRoom`, so on
  a narrow or folded panel it silently fell into the overflow.
- **Clear-all-filters gets a filter-with-cross icon** instead of an unrelated glyph, so it reads as
  the inverse of the filter button beside it.
- **App details can be opened on a specific tab**, so "Restrict location / sensors" lands on 盗み見
  rather than the front page.
- The network lever is cycled through the states it *actually* supports; it was being asked for a
  middle rung that does not exist when the firewall path is unavailable, and correctly refused.
- Controls are **never hidden** because privileges happen to be down — a failed change names
  ADB/Shizuku instead, since that is the one cause you can act on.
- Counts are compacted (`15412 packets` → `15.0k packets`) and the metric slot is never left blank.

## 4.1.0+23 — 2026-07-28

The anti-snooping page stops being an app-ops page. The capabilities that matter most on a modern
phone are not app-ops at all, so a row now drives whichever mechanism actually governs it — and the
most consequential one, whether an app can reach the network, gets a real firewall behind it. Several
things were also measured against the phone and then **removed**, because they could never have
worked; those findings are listed too.

### 🌐 Sending data out — the new first group

- **"Use the internet" is now a row, and it is the first one on the page.** Nothing an app collects
  can hurt you until it can leave the phone, so the ordering of the whole tab was changed to put it
  there: network, accessibility & notifications, watching the screen, location, microphone & camera,
  messages & calls, personal data, files & media, nearby, background. Groups are ordered by
  consequence rather than by subsystem.
- `INTERNET` cannot be revoked by anyone — not by this app, not by `adb`, not by root. It is not
  enforced by a permission check at all: an app that declares it is put into the `inet` group (gid
  3003) when its uid is created at install, and a live uid cannot be taken out of a group. So the row
  is a **firewall**, with three positions:
  - **Allowed** — nothing set.
  - **No background mobile data** — the per-uid network policy. The strongest rung the ROM actually
    declares is discovered rather than assumed (LineageOS and some OEM builds can reject whole
    transports; stock Android offers only metered-background), and **the row names the rung it got**,
    so the switch never claims more than it does.
  - **Blocked** — a per-uid firewall DENY on **every interface, foreground included**, using the
    per-uid firewall chains Android 13 exposes. This is the same mechanism a root firewall uses.
- The chain is chosen deliberately: the chains AOSP drives itself are rewritten whenever an app
  changes standby bucket, which would silently undo the rule. The rule is written to a chain the
  platform reserves for exactly this and never touches.
- The rule cannot be read back before Android 14, so what we wrote is recorded locally — **including
  the uid**, because a reinstall changes it and a stale rule must not outlive the app onto whichever
  uid the system recycles next. Unblocking clears the rule for the old uid as well as the current one.
  That record is device-local and never travels in a settings export; the decision itself still does.

### 🔊 Four more capabilities that were never app-ops

A row can now be driven by a **lever** — a system list, a network policy, a role holder, the doze
whitelist — instead of an app-op or a permission:

- **"Accessibility service enabled"** and **"Reads all notifications"** drive the `Settings.Secure`
  component lists that actually gate them. These are the two most invasive things an unprivileged app
  can hold — one sees every window, every text field and every keystroke of every other app; the other
  sees the content of every notification, including the one-time codes banks put there — and neither
  is stopped by the app-op the page used to show for it. Those ops remain as separate rows, since they
  are separately settable, but the list is the real gate.
- **"Is the device assistant"** clears the assistant role. `ASSIST_STRUCTURE` and `ASSIST_SCREENSHOT`
  describe what the assistant is handed; this decides whether the app is the assistant at all. Both
  mechanisms are read and cleared — the role holder and the older secure-settings pair — because an
  app left in either one keeps the job.
- **"Exempt from battery optimisation"** takes an app off the doze whitelist. Not a capability of its
  own; it is what turns every other capability into a continuous one.

### 🧭 The catalogue now asks the phone what it missed

- **⋮ → "Ops not in the catalogue"** walks every app-op the device has, drops the ones that can never
  hold a mode of their own and the ones already listed, and reports the rest with this app's current
  mode. A hand-written list of platform constants rots quietly; this turns "what did we forget" into
  something you read rather than remember.
- It found fifty on Android 13. Thirteen are now on the page: **recording incoming call audio**,
  **capturing played-back audio**, binding as an accessibility service, activating a platform VPN,
  managing IPsec tunnels, seeing every installed app, reaching across work/personal profiles,
  bypassing storage isolation, reading financial SMS, SIM authentication with the device identifier,
  placing calls, the usage-stats loader, and turning Wi-Fi on and off (which is how an app forces a
  scan, and a scan list is a location fix by another name).
- Also added: reading SMS on the SIM, ambient audio triggers, bypassing scoped storage,
  user-selected photo reads, user-initiated jobs, and the two Android 14 sandboxed camera/microphone
  ops, which simply stay hidden until the phone has them.

### 🩹 Honesty fixes

- **A switch that could not move is gone.** "Location in the background" is the one capability with no
  app-op behind it, so the permission itself is the lever — and for an app predating runtime
  permissions the platform's revoke path can only act *through* an app-op, does nothing when there is
  none, and then re-grants what you asked it to revoke. Such a row is now dropped, using the platform's
  own rule, so a modern app keeps its working switch and an app that raises its target SDK gets the
  row back with no maintenance.
- **A refused write is never recorded as a decision** on permission-only and lever rows either. The
  grant is re-read from the platform after the write — and where the permission has an app-op, the op
  has the last word, because a legacy app's permission is revoked "compat" style: the grant stays and
  the op is what actually stops it.
- **The state a row reports is the state the platform stores.** `checkOperation` resolves a
  foreground mode against the app's process state at that instant, so it is the right answer to "can
  it do this right now" and the wrong one to "what did we set".
- **A refused narrowing no longer deletes a row.** Asking for *less* access says nothing about whether
  a capability can be turned on; treating it as "will not turn on" was removing rows that worked.
- **Every dialog on the tab now carries the fork's yellow border**, through one shared presenter.

### 🧪 Measured, then removed

Two features shipped during this cycle and were taken out again once the phone was asked directly.
Both removals are the page's own rule applied to itself: nothing that cannot act.

- **Disabling the component behind a capability.** Since Android O the platform refuses component
  state changes from a shell-privileged caller for anything that is not a test-only app —
  `pm disable` answers *"Shell cannot change component state"*. The row action, and the fourth state
  built on it, are gone. (Even where it works it is not a lock: an app can re-enable its own
  components.)
- **"Only while in use" on app-op rows.** A permission-backed op's mode is not independently settable
  at all: the platform re-derives it from the permission grant and re-asserts it, so the write is
  accepted and thrown away — a foreground write to `READ_EXTERNAL_STORAGE` came back as `ignore`.
  This is also *why blocking works*, since blocking revokes the permission and the platform then
  agrees. The platform's real "only while in use" is a permission distinction — an app granted
  foreground location but not background location — which already has its own row on this page.
  Camera and microphone have no such pair at all. The network row keeps its middle position, because
  that one is a genuinely different mechanism and it demonstrably works.

## 4.1.0+16 — 2026-07-28

A hardening release for the anti-snooping page. It now shows **only what can actually snoop**, its
writes land where the block really lives, and it never records a decision the phone did not honour.
The launcher icon also stops burying the screen you were on.

### 盗み見 — only what can snoop, or be made to

- **Capabilities the app could never use are gone from the page.** Each one now carries a
  prerequisite read from the app's own manifest, and a row is dropped when that prerequisite is
  missing beyond doubt: media reads without a `READ_MEDIA_*`/storage permission, hotword and in-call
  microphone without `RECORD_AUDIO`, in-call camera without `CAMERA`, Write SMS without a single SMS
  permission (so the app can never hold the default-SMS role), device identifiers for any app that
  is not privileged (signature-only since Android 10), and accessibility, VPN and the two assistant
  rows without a `<service>` bound with the matching `BIND_*` permission — the assistant also accepts
  an `ACTION_ASSIST` activity. **A zero-permission app drops from sixteen rows to four**: screen
  capture, clipboard, and the two background ops, which no manifest gates and which therefore can
  never be hidden this way.
- Nothing is learned from a failed attempt: the check is recomputed **on every load**, so an update
  that adds the missing permission or service brings the row back by itself. It fails open on
  anything ambiguous, and *Show all capabilities* still lists everything it filtered.
- **A capability that refuses to turn on is permanently safe, so it leaves the page.** Android cannot
  be asked this in advance — it accepts a write and silently discards it — so it is learnt the only
  way it can be: a "turn it on" write that leaves the enforced mode unchanged marks that capability
  and the row disappears, with a toast saying why. The mark is device-local (it never travels in a
  settings export, since it says nothing about another phone), it only ever hides a row that is
  currently **blocked** — a stale mark can never conceal actual snooping — and it is cleared when the
  app is installed or updated, and when 応用管理 itself is updated.

### Writes that actually land

- **Every write now goes to both the uid and the package slot.** `AppOpsService` *deletes* an entry
  whose mode equals the op's default instead of storing it, so un-blocking a capability whose default
  is *allow* cleared a uid entry that had never existed while a package-level `ignore` — where a block
  from `adb`, another tool, or an earlier build of this app actually lives — kept winning. Nothing
  threw, the switch stayed off, and the decision was saved anyway. Both levels are written now, which
  is correct in every combination: a value equal to the default removes the entry at that level, any
  other value is stored there, so the two always agree afterwards.
- A **repair pass** forces the package entry when the enforced mode still hasn't moved, leaving the
  permission path's `MODE_FOREGROUND` nuance alone unless it demonstrably failed.
- **A decision is recorded only after the phone confirms it.** The enforced mode is re-read after
  every write, and neither a single toggle nor *Block everything* stores anything the platform did not
  honour — a refused toggle now reports failure instead of leaving a row that claims one thing while
  the switch says another.
- **Only departures from the default are stored.** A toggle back to the untouched state clears the
  record rather than writing one, so set-then-unset leaves nothing to export or re-apply, and
  *Block everything* clears such entries explicitly instead of merging past them.

### Reading a row at a glance

- **One accent colour drives the whole row.** Red when the capability is allowed — the app can do it
  right now; yellow when it is blocked against a permissive default — you closed it; grey when it is
  blocked and blocked is simply the default. The colour lands on the switch's dot and outline as well
  as the card, and a **thick frame marks a state you chose**, which is the only tell for the case that
  has none: a capability that is on by default and is off only because you turned it off.
- The **Allowed pill is filled blood red** with near-white text; both descriptive lines now sit on
  tinted chips, because the shared success/failure colours (`#1b8654` / `#ff0028`) were close to
  illegible at body-small size on a pure-black page.
- The status line no longer mentions the stored decision. It would only restate the switch, and the
  store now holds nothing that isn't a real departure from the default.
- **"needs no permission" now keys off the capability, not the row's internal tier** — blocking a row
  used to make the label vanish, so the page silently changed what it claimed about itself. The app-op
  line dropped its duplicate parenthetical and simply names the op.

### Fixes

- **The launcher icon stops burying the screen you were on.** The splash activity is a trampoline: it
  starts the main window and finishes, so tapping the icon while the app was already open could run a
  fresh splash on top of the existing task and stack a fresh main window over the app-details page you
  had left — measured on-device, every such main window carried `launchedFromPackage=shiroikuma.oyokanri`,
  i.e. the app had started it itself. It now detects that case before the theme, the splash screen and
  the layout are touched, and simply finishes: no splash, no animation, straight back to where you
  were. A genuinely cold start is unchanged.

## 4.1.0+7 — 2026-07-27

A fork-feature release: a per-app **anti-snooping page** that gathers every privacy-invasive
capability the phone will actually let us switch off, remembers each decision against the package
name, and re-applies it wherever that package turns up next.

### 盗み見 — the Snooping tab

- A new **second tab** on every app-details page, immediately after *App info*. It collects the
  privacy-relevant capabilities that are otherwise scattered across the **App Ops** and
  **Permissions** tabs — among hundreds of rows that mostly cannot be moved — into eight groups:
  **location**, **microphone & camera**, **messages & calls**, **personal data**, **files & media**,
  **watching the screen**, **nearby & network**, and **background activity**.
- Switch sense matches the rest of the app: **on = the app is allowed**, off = blocked. The state
  line is red when a capability is allowed and green when it is blocked, so a locked-down app reads
  as a page of green at a glance.
- Every flip is **recorded**, not just applied. Each row shows its live state, whether a decision is
  saved for it, and why it is listed; long-press explains what the saved decision means and offers to
  forget it.
- Overflow actions: **Block everything** (with confirmation), **Show all capabilities**,
  **Re-apply on install**, **Forget saved settings**, and a refresh.
- Group headings span the full width when the list goes multi-column, so the tab reads correctly
  unfolded on the tri-fold.

### No decorative switches

The tab's one promise is that nothing on it is for show. That is enforced structurally rather than by
a hand-maintained list:

- Capabilities name their app-op by **AOSP name**, never by numeric code — codes are renumbered
  between Android releases, names are not. The catalogue resolves names against the running platform
  and silently drops anything that does not exist there.
- A row survives only if we hold the privileges to change it. **Without ADB or Shizuku the list is
  empty** and a warning says why, rather than showing switches that would do nothing.
- Ops that Android redirects to a **different controlling op** are dropped, because they have no
  storage slot of their own and can never move — for this app, for `adb`, or for root. This removed
  **GPS** and **Continuous location tracking**, both in fact governed by the *Precise* and
  *Approximate location* rows, which remain. The rule is expressed as the platform's own
  (`opToSwitch(op) != op`) rather than as a list of names, so a capability that Android merges on one
  version and splits on another needs no maintenance.
- Rows report the mode the system **actually enforces**, not the stored per-op entry — the latter
  stays absent until something writes it, so a row built on it can claim *Allowed* for a capability
  already being denied.

### Three tiers, decided on the device

- **Requested** — the app declares the permission, or its app-op already carries a non-default mode.
- **Reachable without asking** — no manifest permission gates the op, so the app can use it without
  declaring anything: **screen capture**, **clipboard reads**, **assistant screen reads and
  screenshots**, **VPN**, **accessibility**, **background activity**. Shown by default, because "not
  requested" does not mean "cannot use".
- **Not requested** — permission-gated and never asked for. Hidden behind **Show all capabilities**,
  where it acts as a **pre-set**: because decisions are re-applied on install and update, blocking the
  microphone today on an app that has no microphone permission means the block lands the moment a
  future update starts asking for one.

Which tier a capability falls into is computed on the device, from what the platform reports — not
hardcoded per app or per capability.

### Saved decisions travel

- Decisions live in their own preferences file keyed by **package name**, so they survive uninstalling
  the app entirely, and an archive imported onto a phone that has never seen the app is kept verbatim
  until that package finally appears.
- A capability with **no** saved decision is a real third state: nothing is written for it, so an
  import never disturbs a switch you never touched. Unknown ids are preserved through a
  read-modify-write, so a file written by a newer build survives a round-trip through an older one.
- The store joins settings **Export/Import** as a seventh category, **Anti-snooping settings** (wire
  id `snooping`) — which also means the 保存復元 automation contract can request it by name.

### Re-applying

- A **manifest-registered receiver** re-applies stored decisions when a package is installed or
  updated. Updates count too: an app update can quietly reset an op that was blocked.
- A **sweep at startup**, at the point where privileges are known to be settled, covers importing an
  archive onto a phone that already has the apps, and any install that happened while ADB/Shizuku was
  unavailable. Nothing is ever dropped — only deferred to the next launch.
- Both paths share the **same resolver** the tab itself uses, so the replay can never disagree with
  what the page offered.

### Fixes

- **Call microphone / call camera would not switch off.** The shared app-op helper returns early when
  the current mode already equals the target, comparing the *enforced* mode while the row displays the
  *stored* one; where the two disagreed the write was silently swallowed and the switch snapped back.
  Ungated and pre-set rows now write the mode unconditionally. Rows carrying a real runtime permission
  still go through the full grant/revoke path, which moves the permission, the op and the permission
  flags together.
- A **not-requested** row moves the app-op **alone**. Asking the platform to grant or revoke a
  permission the app never declared throws, which would have failed the toggle and lost the very
  pre-set it was recording.

## 4.1.0+5 — 2026-07-25

A fork-feature release: the settings export becomes **remote-triggerable**, so an external automation
app can back this app up headlessly — with a token gate, real-count progress, and no UI at all.

### 保存復元 automation contract — headless, token-gated state export

- A new **exported broadcast receiver** answers two actions, both gated by the automation token:
  - `shiroikuma.oyokanri.action.LIST_CATEGORIES` — replies instantly with `OK:` plus one
    `id<TAB>label` line per exportable category, so the caller can render a picker. The six ids are
    `general`, `appearance`, `monitor`, `toolbar`, `notes`, `profiles`; they are **stable wire ids**
    and will not be renamed.
  - `shiroikuma.oyokanri.action.EXPORT_STATE` — runs the **very same export** the Export/Import panel
    runs, headlessly, into **exactly one** zip, and replies
    `OK:<absolute path>|<bytes>|<human size>|<n> categories`.
- **Directory precedence**: the request's `path` extra (created if missing) → the app's configured
  export directory → `ERROR:no-directory`. Both are plain absolute paths and the app holds
  All-Files-Access, so the archive is written directly with `java.io.File`.
- **Category subsets**: an `items` extra takes a comma-separated list of category ids; absent or empty
  means everything. An unknown id fails the request (`ERROR:unknown category in items: …`) and writes
  nothing, rather than silently exporting a partial set.
- **Progress is real counts, never a percentage** — while exporting, the app broadcasts
  `区分 i/N — <category>` along with structured `current` / `total` / `unit` extras, throttled to at
  most one every 500 ms plus a mandatory final one carrying the finished archive size.
- Distinct, debuggable errors: **`ERROR:automation disabled`** and **`ERROR:bad token`** are separate
  results, and exactly one terminal reply is ever sent per request.
- The export logic is **not duplicated**: it was refactored into a headless core
  (`writeExport(categories, OutputStream, progress)`) that the Export/Import panel and the receiver
  both call.

### Automation token

- New **Automation export** master switch — **off by default**; nothing above is reachable until it is
  turned on — plus a **24-byte `SecureRandom` token**, generated lazily so the row always shows a
  value and compared **constant-time** on every request.
- Both rows live **inside the existing Export / Import section** of the 白い熊 応用管理 UI page, directly
  below the Export/Import row. The token row shows the value abbreviated (`80922d8c…4c49a87c`),
  **copies the full token on tap**, and carries a **Regenerate** action that warns pasted copies go
  stale.
- The token lives in its **own preferences file, excluded from both export and import** — so it can
  never travel inside a backup archive, nor be planted on this device by one.

### Backup file naming

- Every archive this app writes — from the automation path **and** from the Export/Import page — is now
  named **`shiroikuma-oyokanri_<yyyy-MM-dd_HH-mm-ss>.zip`**: no version, no infix, no suffix. All of
  白い熊's apps keep their backups in one directory, so the names must sort and read uniformly.
- The previous `AppManager-settings_…` prefix is still recognised, so older archives remain listed and
  still count as the "last export".

## 4.1.0+3 — 2026-07-25

A fork-feature release: the settings Export/Import is rebuilt as a **category-based panel at the top
of the 白い熊 応用管理 UI page**, the UI page itself is restyled to the kxkb section idiom, and popup
menus gain the yellow frame.

### Settings Export/Import — category-based, on the UI page

- **Moved** from Settings → Backup/restore (the "Settings export / import" category there is gone) to
  a new **Export / Import section at the very top of the 白い熊 応用管理 UI page**: a heading plus one
  tappable row whose summary is **re-queried on every page open** — the export directory and
  "Last export: <timestamp>" of the newest export archive (warn-red when no directory is set or the
  directory has no exports yet).
- The row opens a bordered black/yellow **Export/Import panel**: a tappable **export-directory box**
  (built-in filesystem browser, no SAF), the last-export line, a **Select all** master checkbox over
  **six categories** — General settings · UI colours, fonts & layout · Process monitor & reaper ·
  Toolbar & filters · App notes · Profiles — and a pill button row with **Cancel alone on the left**
  and **Import / Export on the right** (black pills, yellow outline and ripple).
- One checklist drives both directions: **Export** zips only the ticked categories; **Import** applies
  only the ticked categories a chosen archive contains. Every zip entry is classified by prefs-file
  name; unrecognized files fall into *General settings*, so nothing is ever silently dropped. The
  archive layout is unchanged, so **exports made before categories existed still import**.
- **Success dialogs with a yellow border**: "✓ Export finished" (OK) and "✓ Import finished"
  (Later / Restart now). Acknowledging (OK, or Later on import) **auto-closes the whole chain** —
  the info dialog, the Export/Import panel beneath it, and the UI settings page itself. **Restart
  now** hard-restarts the process (SIGKILL, so cached SharedPreferences can't clobber the imported
  files). Failures ("Export failed…", "No categories selected.") are toasts and **leave the panel
  open**.
- The minimal directory browser was extracted into a shared, **yellow-bordered** chooser dialog, also
  used by the Backup-directory preference.

### UI page — kxkb restyle

- The whole 白い熊 応用管理 UI page now follows the kxkb section idiom: section headings 20 sp bold with
  a **text-width** 2.5 dp yellow underline, sub-headings 17 sp with a 1.5 dp text-width underline,
  and sections separated by **full-bleed 1 px yellow hairlines** (none above the first section).
- Indent ladder standardized to **36 dp headings / 54 dp sub-headings / 72 dp controls & rows**; the
  legend headers get the same underlined sub-heading treatment; the page container's horizontal
  padding was removed so the hairlines run edge-to-edge.

### Theming

- **Popup menus** (the toolbar overflow menu and every other popup) now draw with a **2 dp yellow
  border** on their black rounded background, so they read as panels against the equally-black screen.

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
