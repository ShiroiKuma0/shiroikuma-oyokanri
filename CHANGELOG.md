<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Changelog

All notable fork changes are recorded here. From `4.1.0.2026-06-29.gfc1e7007+090` onward, versions
read `<upstream base>.<upstream commit date>.g<commit>+<fork build>` — the middle field names the
upstream App Manager commit the fork is rebased on. From
`4.1.0+2026-06-29.21-57.gfc1e7007+095` onward a `+` opens each top-level group (upstream's version ·
the pin · our build counter) and the pin carries the commit's **time** as well as its date, so two
syncs landing on one day still sort. Earlier versions used `customBaseVersionName+customBuildNumber`.
Nothing already published is ever retagged.

## 4.1.1+2026-09-05.03-37.g41d79af5+030 — 2026-09-11

Freezing an app suspended it and stopped there. A session of measurement on the phone settled what
each mechanism actually does — and the answers were not the documented ones — so a freeze now applies
**every gate this phone allows, at once**, and the *Hide* method, which had been silently degrading
to *Disable* for as long as this fork has run here, works for the first time. Along the way a latent
defect turned up that would have made a hidden app impossible to thaw from the app. (Built on
upstream App Manager `4.1.1`, commit `41d79af5` of 2026-09-05 03:37 UTC.)

### 🧊 Total freeze — four gates instead of one

- **The default freezing method is now `Total freeze`**: force-stop → suspend → disable-user → hide,
  in that order, each best-effort, failing only if none of them landed. Order is load-bearing — hide
  goes last, because a hidden package is reported as *not installed* and the suspend and
  enabled-state calls would then have nothing to act on.
- **Why a stack rather than a better single choice.** Each gate closes a different hole:
  **suspension** blocks the launch, the launcher path and even an explicit broadcast to a receiver
  that still resolves — and it is the one flag EMUI does not restore at boot; **disabling** is the
  only one that removes the components from *resolution*, the clean tell being a content provider
  that answers "not exported" while suspended and "could not find provider" while disabled;
  **hiding** reports the package as not installed for the user.
- **Suspension now goes in the owner's slot.** The platform records a suspension per *suspending
  package*: through the shell it reads `com.android.shell`, through 白い熊 雫's device-policy
  delegation it reads `android`. Only the second is beyond the reach of `adb shell pm unsuspend`, so
  it is preferred whenever the delegation is live. Unfreezing already lifted both.
- **The force-stop is not redundant beside the suspension.** A `pm suspend` of a running app does
  kill the process by itself, but leaves the package's *stopped* flag clear; only the force-stop sets
  it, and that flag is what withholds implicit broadcasts afterwards.
- `Total freeze` is a distinct method value rather than a combination of the existing flags: they are
  bit flags, but every consumer compares them with `==`, and the value is the wire format of both
  `FreezeRule` and the per-app remembered method. Existing installs move to it by a one-shot
  migration that fires only while the stored value is still exactly the previous default, so a method
  chosen deliberately is never overwritten.

### 🫥 Hide works at last, through the Device Owner

- **The shell holds no `MANAGE_USERS` on this phone**, so `pm hide` is refused outright while
  `SUSPEND_APPS`, `CHANGE_COMPONENT_ENABLED_STATE` and `FORCE_STOP_PACKAGES` are all granted. Hiding
  was therefore unreachable, and the *Hide* freezing method had been quietly lowering itself to
  *Disable* on every start.
- It now goes through 雫's `DELEGATION_PACKAGE_ACCESS` — the same delegated scope that already
  carries suspension, so a phone whose Snooping suspend switch works can hide too, and one without
  the delegation can do neither.

### 🩹 A hidden app you could never have thawed

- **Fixed before it could bite:** the "is this package hidden?" read looked the package up *without*
  asking for uninstalled packages. A hidden package is precisely one the platform reports as not
  installed, so that query failed for exactly the packages it was asked about, fell through to a
  reader this shell cannot use, and answered a flat **"not hidden"** — whereupon unfreezing skipped
  the reveal and the app could never be thawed again from within the app. Harmless until now only
  because nothing here had ever managed to hide anything.
- **And a guard, because the row is the only way back.** After hiding, the app is re-read through the
  exact predicate the main list itself uses; if it would not still get a row, the hide is **reverted**
  and reported as not applied, leaving the other three gates standing. By construction it cannot fire
  — hiding leaves the *installed* flag alone — but that assumption had never once been executed on
  this phone, so it is checked rather than trusted.

### 📋 Measured, and written down

Recorded in `CLAUDE.md` so the next session does not re-derive them: `pm disable` (fully disabled) is
refused for the shell at **package** level too, not only per component, and component state is
refused for both disabled and disabled-user; a **persistent system app keeps running through all four
gates**, because the platform's force-stop deliberately spares persistent processes, so the gates
stop an app being *started* and cannot evict one the OS brought up at boot; and 白い熊 自由作業盤
**mirrors this gate set** from its own device-policy delegation, so a change to the stack has to be
announced to that repo or its thaw stops clearing everything this one applies.

## 4.1.1+2026-09-05.03-37.g41d79af5+028 — 2026-09-09

The batch-operation page ends with the log as its whole report, and the clipboard was the only way
off it — which loses everything past whatever the next copy overwrites. *Save log*, which existed
but only in the overflow nobody opens once a batch has finished, now has a pill beside *Copy*, and
*Share log* is new. Widening the bar to hold four controls turned up a `libcore` layout bug that had
been quietly ignoring `android:gravity="end"` app-wide. (Built on upstream App Manager `4.1.1`,
commit `41d79af5` of 2026-09-05 03:37 UTC.)

### 🧾 The finished log can leave the page

- **The finished bar reads *Copy log · Save log · Share log · Close*.** The page *is* the report
  once the work stops, and until now the only way to keep any of it was the clipboard — where it
  survives exactly until the next thing copied. *Save log* was reachable the whole time, from the
  ⋮ overflow, which is precisely where nobody looks when the log is the last thing on screen.
- **The running bar is unchanged** — *Pause · Leave running · Cancel*. A log still being written is
  not one to keep, and while a batch runs those three are the only controls that bar should offer.
  All three log actions stay in the overflow throughout, so nothing became less reachable.
- **Share log is new, and the log travels as a *file*, never as `EXTRA_TEXT`.** The log is capped at
  20 000 lines and an overnight backup reaches that; a string of it put into an intent is far past
  what a Binder transaction carries — and that limit is not a polite refusal but a
  `TransactionTooLargeException` thrown at whichever side blinks first. The file is handed over as
  an `FmProvider` content URI with a read grant, the mechanism the logcat viewer and the backup
  share already use, so the receiving app opens a descriptor we give it rather than a path it has no
  rights to.
- **A shared copy goes to our own cache; only *Save log* writes beside the backups.** A copy handed
  to another app is a copy, and it has no business landing in the directory the backups live in.
- **One writer.** `writeLog()` produces `shiroikuma-oyokanri_batch-log_<yyyy-MM-dd_HH-mm-ss>.txt`
  for both actions, so a saved log and a shared one can never turn out to be different files.
- **The bar is a `FlowLayout` now, not a `LinearLayout`.** Four pills cannot share one line on the
  folded panel — in Japanese they are four six-character labels — and a `LinearLayout` would have
  squeezed and ellipsized all four rather than dropping one to a second row, which is the one
  failure a bar of controls cannot afford.

### 📐 `android:gravity="end"` finally works in a `FlowLayout`

- **A `libcore` landmine, silently wrong app-wide.**
  `FlowLayout.getHorizontalGravityOffsetForRow` masks the gravity with `HORIZONTAL_GRAVITY_MASK`
  (`0x7`), which strips `Gravity.END`'s `RELATIVE_LAYOUT_DIRECTION` bit and leaves plain `RIGHT`
  (`5`) — then compares the result against `Gravity.END` (`0x00800005`), a case that can never
  match. Every `android:gravity="end"` `FlowLayout` in the app has therefore been laid out from the
  **start** edge, with no error and nothing to notice but a row sitting on the wrong side.
- **Fixed by accepting `Gravity.RIGHT` as well**, which is what both `right` and `end` reduce to
  after that mask. The change is purely additive: `right` previously matched nothing either.
- **Visible consequence elsewhere:** the app backup dialog's *Share · Delete · Restore* row is now
  right-aligned, as its layout always declared it wanted to be.

## 4.1.1+2026-09-05.03-37.g41d79af5+027 — 2026-09-09

A sweep of all 57 sister repos, prompted by finding the same "mismatch" in two of them, inverted the
assumption it was written on: `text` is the family convention and **this app was the one deviating**,
discarding the progress label of essentially every sister app for months. Alongside that, a transfer
that is given up on now stops the app's writes instead of leaving them to vanish, and a stall that
the OS caused says so. (Built on upstream App Manager `4.1.1`, commit `41d79af5` of
2026-09-05 03:37 UTC.)

### 🗣️ Sister apps' progress lines actually arrive

- **The progress label is read from `text`, falling back to `result`.** `result` carries the
  *terminal reply* and nothing else. 自由作業盤's `StateExportReceiver` — the reference
  implementation every sister app mirrors — defines `EXTRA_PROGRESS_TEXT = "text"`, and **26 of the
  31** sister apps that report progress send `"text"` alone. 応用管理's own `StateExportReceiver`
  sends `"text"` too. `AppDataClient` nevertheless read the label from `EXTRA_RESULT`, so this app
  emitted one key and read another — which is why a multi-gigabyte transfer showed climbing counts
  and never a word about what it was doing, and why two sister chats were sent to "fix" apps that
  were correct. The fallback is kept because three apps now send both keys.
- Recorded as a **landmine** in `CLAUDE.md`: a future session must not tidy it back to one key, nor
  conclude from a silent transfer that the app is sending nothing.

### 🛑 An abandoned transfer cancels the writes

- **The callee is force-stopped when a transfer is given up on.** Abandoning it sent a cancel and
  closed our dup of the descriptor, which stops nothing: the app holds its own dup pointing at a
  real file, so the file is merely *unlinked* and every later write succeeds into an inode nobody
  can reach. An app that ignores the cancel therefore writes into nothing for hours — very close to
  a 368-minute "still working" hang seen in the family.
- It happens **before** our descriptor is closed and before the caller deletes the staging file, so
  nothing is mid-write when the file goes. `AppDataClient.Result` gains an `abandoned` flag, so an
  ordinary failure is never treated this way. Force-stop is the instrument this class already uses
  before every transfer and again after every successful import.

### ❄️ A stall says when the OS is the cause

- **`/proc/<pid>/cgroup` is probed alongside the CPU liveness check**, on the same 10-second
  cadence, and the heartbeat says **"The OS has frozen this app"** rather than "Waiting for the
  app". The abandonment message carries the same note.
- This is the distinction that costs whole evenings: off the charger, EMUI puts a sister app in a
  freezer cgroup with `cpuset:/background` while 応用管理 sits in `cpuset:/vip`, and every thread of
  the app goes to D state. Such a process is silent **and** burns no CPU, so it fails both halves of
  the liveness test and is abandoned looking exactly like one that has wedged. A foreground service
  with an ongoing notification does not prevent it — that was measured, with `isForeground=true`
  throughout.
- No new privilege: `/proc/<pid>/cgroup` is world-readable, unlike `smaps_rollup` beside it, which
  is ptrace-gated and refuses the shell. The placement is still not ours to change without root —
  only the diagnosis improves.

## 4.1.1+2026-09-05.03-37.g41d79af5+026 — 2026-09-09

The app list stops truncating the thing it exists to show. Fork version names now pin their upstream
base — this one is 36 characters and 白い熊 雫's is 43 — while the right-hand column was still sized
for `1.6.0+081`. Alongside that: a saved view no longer drags its sort along unless you ask it to,
lenses survive folding the phone, and *Sister apps* becomes one control instead of two.
(Built on upstream App Manager `4.1.1`, commit `41d79af5` of 2026-09-05 03:37 UTC.)

### 📏 The version is readable at any length

- **The version has its line to itself**, with the backup version stacked directly beneath it and
  both right-aligned, so the two line up and can be compared down rather than across. They used to
  share one line, which left a 36–43 character version about ten characters once a nine-character
  backup version had taken its share — the one field that had grown was the one being squeezed.
- **The column is sized from a version, not from a signature.** It was measured from
  `"SHA384withRSA"` — 13 characters — times a constant 1.45, making the whole column about 19
  characters wide. The reference is now a real fork version string, measured with the version
  line's own paint so it follows the configured font.
- **New per-geometry setting: *Version column width*,** on the 白い熊 応用管理 UI page beside the
  column count. Every pixel the version block takes comes out of the app name and package id
  beside it, and the right split genuinely differs folded, unfolded and in a four-column grid —
  so it is remembered per geometry, like the column count. The value line names the geometry it is
  editing (`p403`, `l819`), or a slider would silently mean something else after unfolding.
- **The signature-algorithm line is gone**, which is what pays for the extra line. It read
  `SHA384withRSA` — the signing certificate's algorithm, identical for every app 白い熊 builds. Its
  *view* stays and is only hidden: `MainCardBinder` hands it the third and later lines of a lens's
  right column, so deleting it from the layout would silently truncate every lens. The signature
  **sort** also stays, since sort ids are the wire format of a saved view and are never renumbered.
- **Both version lines are sized as one pair** — the largest size at which *both* fit, so they can
  never disagree. Autosizing each view separately could not promise that: each would pick from its
  own text, and a long installed version above a longer backup one would land on two different
  sizes stacked in the same column.
- **They never grow past nominal.** These two lines are what set the right column's height, so
  anything that makes them taller makes the whole card taller. The way to make them bigger
  deliberately is the VERSION font size, which moves the pair together.
- **Middle ellipsis, never end ellipsis**, once even 9sp will not do: `4.1.1+2026-…f5+026` keeps the
  base version and the build number and drops only the upstream pin between them — the same
  convention the operation log already uses.
- **Both lines share the VERSION font.** Equal sizes were not enough: they were being given two
  different typefaces, and the same `sp` in two faces with different cap and x-heights reads as two
  different sizes. It also made the fit wrong, since both strings are measured with the version
  line's paint. The backup date and time stay on their own font — they are metadata, and nothing
  about them has to line up with a version.

### 📌 A saved view only carries a sort if you say so

- **New toggle when making a pill: *Save the sort with this pill* / *Keep the current sort*.** A
  saved view stores filters and sort together because they usually only mean anything together —
  but not always. Every pill re-imposing the order it had been captured with is what looked like the
  sort resetting itself: a pill saved while the list was on *App label* put it back on *App label*
  for ever. Both kinds can now sit on the shelf.
- **Every pill made before this is untouched.** The wire format gains `save_sort`, written *only
  when false*, so absent means true and an existing pill is byte-identical.
- **Reverse follows the sort** — a pill that does not carry an order does not carry the reverse flag
  either; the two are one decision.
- ***Update to current* keeps the pill's own kind**, so updating what a pill shows never silently
  re-arms its sort.

### 🔍 Lenses survive the phone folding

- **The lens state is persisted** (`shiroikuma_main_lens`) like every other part of the view —
  filter flags, sort, reverse, profile filters, per-geometry columns. It lived only in memory, so
  anything that rebuilt the view model dropped it: folding the Mate XT, rotating, a multi-window
  move, EMUI killing the process. The list came back showing everything, which reads as the lens
  having been cleared rather than never having been saved. A lens id that no longer exists is
  dropped rather than kept.
- **Clearing filters clears the lenses too, and releases their pills.** Both halves of that were one
  omission — the flags, profile filters and search were cleared and the lens state was left alone,
  so what the page showed and what the shelf claimed could not agree.
- **The active *view* pill is released as well.** Clearing every filter, profile filter, search and
  lens *is* un-applying whatever view was showing, so nothing can still be in effect.

### 🤝 One Sister apps control, with the better definition

- **The lens is no longer offered when adding a pill.** It drew nothing — it was `filterOnly` — so
  it was a second Sister-apps filter under another name, in the same dialog as the real one,
  selecting a slightly different set. It stays *registered* so pills already on the shelf keep
  working rather than becoming pills that do nothing.
- **Its membership rule is the one kept**, in the new `AppDataContract.everSisterPackages`: an app
  is a sister app if its manifest declares the contract **or** a backup proves it. That is wider
  than "can I talk to it right now", which required a readable manifest and a contract version this
  build speaks — and so hid an app known only from its backup (the wiped-phone case the contract
  exists for, where the manifest cannot be read at all) and an app whose contract version we do not
  speak (which makes a version mismatch look like the app never having a door).
- **The backup witness is confirmed against the archive, not the flags.** `Backup.flags` records what
  a backup was *asked* for; App-supplied data is ticked by default and `BackupOp` skips it silently
  for an app that declares no contract **without clearing the flag**, so essentially every backup
  claims it. Trusting it matched every app with a backup. The witness now applies only where no
  manifest could be read — for anything installed the manifest answers in both directions — and the
  archive is checked for the file, which keeps the filesystem cost to the handful of
  backed-up-but-absent packages. `SisterAppsLens` had the identical flaw and is corrected the same
  way.

### 💾 Backup and restore

- **The App-data categories pill says whether anything is being left out, before you tap it**:
  `all 7` in the part's green, `5 of 7 · 2 left out` in the theme yellow, `none of 7` in red for a
  ticked part that would export nothing, and `could not ask the app` for one that refuses to list —
  because a pill falling back to its bare name reads as "nothing to report". It asks the app when
  the dialog opens, which can thaw it, but this dialog is one app opened to decide exactly this and
  tapping the pill would pay the same cost a moment later. Nothing is woken for a part that is
  switched off. `AppDataSelection.effective` is the one place that resolves stored choice → app
  defaults, so the pill and the exporter cannot disagree about what "all" means.
- **A restore says which phase it is in.** The in-flight header kept naming our staging copy —
  *Unpacking the archive* — for the whole of the sister app's import, the longest step of the run,
  so a completed byte count under those words read as "100% out of 100%". That import has a stage of
  its own now.

### 🧹 Housekeeping

- `BACKLOG.md`: work parked rather than dropped, starting with a sweep of the sister repos for the
  progress-label mismatch that has now been found in two of them.

## 4.1.1+2026-09-05.03-37.g41d79af5+013 — 2026-09-08

Upstream **4.1.1**, and the backup side stops lying: backing up, restoring and deleting become three
separate actions, a restore stops failing on data it had already restored correctly, and a backup can
be handed to another phone. (Built on upstream App Manager `4.1.1`, commit `41d79af5` of
2026-09-05 03:37 UTC.)

### ⬆️ Rebased onto upstream 4.1.1

Forty-seven upstream commits, including a permission system rewritten around modular controllers, a
reworked installer with concurrent installation, and a substantial ADB / mode-of-operation overhaul.

- **The database version had to move.** Upstream spent schema **v8** on its new `permission_override`
  table; this fork had already spent v8, v9 and v10 on the battery sampler, and those are live on
  installed devices. Upstream's table is therefore appended as **`M_10_11`** and the database is now
  **v11**. Renumbering the battery migrations to make room would have cost every installed device its
  history, or refused to open at all.
- **Two convergent fixes were merged rather than picked.** Upstream fixed the server-JAR race from a
  different angle than the fork did; the fork keeps its atomic `Files.move` and its
  configuration-cache-safe task edge, and additionally adopts upstream's d8 Java-version pinning and
  its new lint-task dependency.
- **The fork's network lever and upstream's new firewall do not collide** — upstream chose
  `FIREWALL_CHAIN_OEM_DENY_3`, the fork has always used `_2`.

### 💾 Backing up, restoring and deleting are three actions

One "Backup/restore" pill opened a sheet with no mode restriction, so a mixed selection produced a
two-tab pager **and** a delete icon — three actions for one intent — headed by a red "these apps are
not installed and cannot be backed up" banner, which when restoring describes the ordinary case
rather than a problem.

- **Separate pills** for Back up, Restore and Delete backup, in both the selection pane and the app
  pane. Existing customised orders pick them up automatically.
- **Every route names one action.** The single-app sheet, App info's action, the "Backup" tag and the
  not-installed-but-has-backups path each open restricted to one mode; `getInstanceWithPref` now
  takes a mode so no caller can open the sheet without saying what it means.
- **The trash icon appears only when deleting was asked for.** Every restore sheet used to carry a
  one-tap way to destroy the backups it was offering to restore.
- **Restore and Delete enable on the right question** — whether anything selected *has* a backup,
  rather than the backup rule they had inherited.
- The backup dialog's ⋮ menu is gone; its entire contents was **Freeze**, which is not a backup
  action. That slot is now **Share**.

### 🔧 A restore failed on data it had already restored correctly

`RestoreOp` ran `chown -R` on **external** app data unguarded. Ownership on FUSE-emulated storage is
synthesised from the path, so `chown` cannot succeed there — which both neighbouring operations
already knew, being wrapped in `!isExternal()`. This one was not, and it is the only one of the three
that throws. An app whose only data was `Android/data/<pkg>` had it extracted correctly and was then
reported as **"Failed to restore ownership info for index 0."**

### ⏱️ App-data transfers are measured rather than guessed at

Sister-app transfers were being killed after two minutes of silence while the apps were working.

- **Liveness no longer depends on the main thread.** The contract client registered its broadcast
  receiver with no scheduler, so the busiest thread in the process decided whether a transfer was
  alive while the watchdog counted down on a worker that was never blocked. Delivery now runs on a
  private `HandlerThread`.
- **Silence is no longer the only death signal.** `/proc/<pid>/stat` is sampled every ten seconds and
  a moving CPU total resets the clock. The pure-silence ceiling rose from two minutes to ten, because
  an app blocked on slow storage accumulates no CPU ticks at all — the two signals cover different
  failures.
- **A heartbeat every five seconds**, saying whether the app is working or merely quiet. A 4 GB
  restore once printed nothing between 13:18:52 and 13:28:20; the work was fine, but a log silent for
  ten minutes cannot be told from a hung one.
- **A timeout now says what it heard** — how many progress messages and replies arrived — so a
  completed-but-unacknowledged transfer can be told from an app that never started.

### 🚦 Large transfers no longer fight each other

One permit for any transfer whose archive exceeds **256 MB**, shared between backup and restore
because they compete for one disk. Five concurrent multi-gigabyte restores took half an hour to
finish three of eight — one 3.2 MB app needed **22m 37s**, queued behind four others — and wanted
twenty-five gigabytes of staging cache at once. Small apps still run in parallel around a large one,
waiting is announced in the log, and the wait polls so Cancel still reaches a queued worker.

### 📋 The log names every failure, and can be read

- **A full failure report closes every run**: each failed app with its reason, its archive path and
  the stage it reached. The closing summary used to print package names alone, leaving twenty
  failures to be hunted through thousands of interleaved lines.
- **Failure lines are legible.** Saturated red on black is the one colour in this fork that genuinely
  cannot be read at log size, so the words are now near-white on a blood-red fill — the pairing the
  Snooping page already uses — while the ✗ keeps the full-strength alarm red. All three colours are
  settable.

### 🔭 Lenses combine, and there is one list of sort orders

- **仲間 (sister apps) is a filter, not a page.** A lens is now either a *display* lens (保存, 盗み見)
  that owns the right-hand column and stays exclusive, or a *filter* lens that only narrows and
  therefore stacks with whatever is on screen — or with nothing, which is the plain list, filtered.
- **仲間 recognises an app by its manifest contract *or* by a backup that carried app data**, so an
  uninstalled sister app appears on the strength of its backup alone. That is the case the whole
  contract exists for: a wiped phone, where no manifest can be read yet.
- **One sort list, everywhere.** The six lens orders — backup date, number of backups, size on disk,
  stale first, allowed capabilities, format — are ordinary sort ids now, so the plain list can be
  sorted by backup date and the backups view by anything the list knows. The lens a chosen order
  depends on is prepared even when it is not lit.

### 📤 Share backup

Hand a backup's **whole directory** to 白い熊 魔法絨毯, which carries it to another device. Reachable
from the app pane, the selection pane, and beside Restore in the backup dialog.

The directory travels rather than its files: a backup is only restorable inside its own folder, and
that folder's name is the timestamp distinguishing two backups of one app. One backup per transfer,
enforced by the picker — the carrier names a received folder by its leaf, and two directories sharing
a leaf are merged into one on arrival.

## 4.1.0+2026-06-29.21-57.gfc1e7007+171 — 2026-09-08

The special pages stop being pages and become **lenses** on the main list, sort gets a control of its
own, and a restore that could never install an app it did not already have is fixed. (Built on
upstream App Manager `4.1.0`, commit `fc1e7007` of 2026-06-29 21:57 UTC.)

### 🔧 A restore could not install an app that was not already installed

Restoring an uninstalled app always failed with `Couldn't perform an installation.` and nothing after
it. Three faults were stacked behind that full stop, and each one hid the next.

- **The reason was being thrown away.** The privileged commit path read the platform's status *and*
  its message, then told every caller only whether it was `STATUS_SUCCESS`. A status carrying a null
  message therefore arrived as a bare failure. `STATUS_PENDING_USER_ACTION` was handled in exactly one
  place — the *unprivileged* broadcast path — and never here. Every status now has a name, a null
  message is filled in on both result paths, and the restore log prints it.
- **`HuaweiUtils` had never detected a Huawei phone.** All three of its checks used
  `java.lang.System.getProperty("ro.build.version.emui")`, which reads the JVM's own property table
  and can never see an Android build property. `isStockHuawei()` was permanently false, so the guard
  it exists for — *"changing package installer in stock Huawei with UID 2000 does not work"* — had
  never once fired on a Huawei device. Measured: `getprop` gives `EmotionUI_14.2.0`,
  `System.getProperty` gives `null`.
- **So the install session was attributed to 白い熊 応用管理 even when the shell created it**, and
  EMUI's own `SilentInstallPolicy` refuses a silent install attributed to a non-privileged installer.
  Measured on the Mate XT with one APK, fresh install: no installer → **Success**;
  `-i shiroikuma.oyokanri` → **Failure [null]**; `-i com.android.shell` → **Success**.

Updates always worked because the app holds `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, and that exemption
applies **only when the package is already installed** — which is precisely why the bug looked like it
did not exist.

### 🔍 Lenses: a "special page" is now a state of the main list

保存一覧, 盗み見一覧 and 仲間 were separate screens, and each paid for that separateness: no search, no
filters, no profile filters, no saved views, no pill shelf, no app pane, no general batch operations.
Each reimplemented selection, a selection bar, sorting and a column picker — while drawing the main
list's own row.

- **A lens changes what some row elements show and which apps are on the page, and nothing else.**
  The filters, the sort, the search, the selection, the batch pane, the app pane, the frames, the
  separators and the per-geometry column count are all the main list's own, unchanged.
- **The plain list is "no lens", and the way out is the way in** — tapping the lit shelf pill again,
  a gesture that already existed for saved views.
- **Existing pills keep working untouched.** The lens ids are the same strings the shelf has always
  stored, so nothing had to be migrated.
- **A lens deliberately cannot take over the row's tap** (that unrolls the pane) **or contribute
  filter flags** (those are a persisted bitmask *and* the wire format of a saved view).
- **Backups, Snooping and Sister apps are lenses**; the shelf now offers them under their own
  heading, apart from the two real screens.
- **Battery and Process stay screens.** Their rows are per-uid and per-pid, not apps.
- The three sources they replaced are **deleted**. `ListScreenActivity` survives serving only the
  tracker screen, and now requires its tracker extra rather than falling through to a page with no
  way in. *Clean up backups* moved to the main overflow, shown while the Backups lens is on.

One consequence is a bug fixed by construction: the Backups screen drew the **Android robot** and the
**package id** for apps that are no longer installed. Both facts were already on disk — `icon.png` is
written into every backup directory unencrypted, and `meta_v5.am.json` carries the label — and neither
was being read. **No backup-format change was needed.** A lens row is an ordinary list item, whose icon
loader has always fallen back to the backup's own icon.

**Cost is paid once.** A keystroke in the search box re-runs the whole pipeline, so the Snooping lens
caches its verdict per package against the app's update time — an update is what changes what an app
can do, and moving that timestamp is how an update announces itself. Pull-to-refresh is what clears it.

### 🔤 Sort leaves the filter sheet

A filter is set once and left alone; a sort is flicked between while you are looking at the same list.
Keeping both in one bottom sheet made the frequent one cost an open, a scroll and a dismiss.

- **Its own icon on the top bar**, right of clear-filters, opening a dropdown rather than a sheet.
- **Drawn by hand**, because a framework popup menu cannot be packed or ruled: rows packed to the
  text, a hairline between them, a wash plus a filled marker on the selection, and the fork's
  text-width underline for the group heading.
- **The active lens's own orders join the same panel** under a heading naming it — one place to sort
  from — while keeping a separate id space, since the list's sort ids are what a saved view stores.
- It hugs its content and otherwise runs to the bottom edge, measured from the anchor rather than
  assumed, because the Mate XT's window height differs folded, unfolded and in multi-window.

### 🐛 Fixes & behaviour

- **App info and Snooping opened the same page.** "App info" passed *"whatever tab is first"*, which
  became Snooping when that was pinned in front. The tab is now named, and **both positions are looked
  up in the tab table rather than written as literals**, so reordering cannot quietly repoint either.
  The backup column's tap and the app pane's default branch had the same bug.
- **Reverse sort could sort with the old value.** It submitted its re-sort *before* assigning, and read
  the field rather than a local — a race that only became visible once Reverse was a one-tap toggle.
- **A single-app restore ran twice, in parallel.** The singleton branch built the package list and then
  fell through into the general loop, which added the same package and user again; the batch executor
  then restored one app twice at once — two installs of the same APK and two extractions into one data
  directory, where one worker's recursive `chown` walked the tree the other was rewriting.
- **The tracker card's red on black could not be read.** The autonomous rung is now a filled pill in
  the Snooping page's own blood-red/near-white pair, matching the summary line above it. A filled pill
  is never faded as a whole — the fill carries the severity, only the ink softens — because fading the
  view fades the fill into the background behind it.
- **An uninstalled app's icon no longer poisons every other screen.** The backup icon has a cache key
  of its own; the shared package-name key had been storing the fallback robot for up to seven days and
  serving it to the process monitor, app usage, Finder and app info.

## 4.1.0+2026-06-29.21-57.gfc1e7007+157 — 2026-09-06

Trackers named and counted on the anti-snooping page, a migration kit that rebuilds this app on a
wiped phone, and the app reopening where you left it. (Built on upstream App Manager `4.1.0`, commit
`fc1e7007` of 2026-06-29 21:57 UTC.)

### 🎯 Trackers, on the page that is about being watched

- **Every tracker inside an app, named**, in a card pinned above the capabilities and framed like the
  device-policy card below it: the count at headline size, a line saying what those trackers can do,
  and a pill per tracker.
- **Colour is a measured severity rung, not a category.** The dataset ships 989 names and signatures
  and no categories, so a taxonomy would have to be invented; what the components *do* say is whether
  a tracker can report while you are not using the app. A service or receiver can be started by the
  system (**red**), a content provider runs whenever the app runs (**theme yellow**), activities need
  a screen of their own (**grey**). Second-degree trackers keep their rung and draw faded.
- **Two scan depths, and the row says which it used.** The manifest pass runs the app's own component
  names through the same index the tracker count already used — cheap enough for a list row, and it
  yields the *names* as well — and states that it is a floor. A **deep scan** mounts the base APK as a
  dex filesystem and walks every class, per app, on demand, cached against the installed version so an
  update re-scans by itself.
- **A pill leads somewhere**: the components that tracker owns in this app, **every other app on the
  phone carrying it** (a screen built on the sibling-screen machinery, so it inherits their rows,
  sort menu and column picker), and the Exodus report.
- **No Block button, and the reason is printed.** Measured again: `pm disable` and `pm disable-user`
  both answer *Shell cannot change component state*, and the Intent Firewall route needs root. Two
  existing features are silently dead here for the same reason — the selection-toolbar *Block
  trackers* discards its failure, and one-click *block trackers* skips every non-test-only app when
  running as shell.
- **盗み見一覧 gained a tracker count and a sort by it**, read from the app database rather than
  scanned again, and its two findings are now **filled pills**: the fork's red at 12sp on black could
  not be read, and the headline stopped saying "%d allowed", which never said allowed *what* — it
  reads **%d ways to snoop**.

### 🧳 A wiped phone becomes this phone

- **A migration kit at the backup root**, refreshed after every backup run: this app's APK, 雫's APK
  (both properly named and versioned), a full settings archive, and a marker naming the versions and
  the four steps. Restoring this app from its own backup is circular — it needs privileges, which need
  雫, which needs the app configured — so the bootstrap rides on the settings export, which needs no
  privileges either to write or to read back.
- **The app finds its own kit**: the configured backup directory first, then the storage root two
  levels down and every removable volume, looking for the marker and nothing else.
- **The offer is gated on the install having nothing of yours in it** — no profiles, no notes, no
  anti-snooping decisions — rather than on "first run", so a phone in use never sees it.
- **A report on the next start** counts what landed, and names the number that matters: how many
  anti-snooping decisions are waiting for apps that are **not installed yet**. It is counted from the
  files on disk, because the process still holds the preference maps the import just replaced.
- **A new APK replaces its predecessor**, matching this kit's own deposit shape for that package, and
  only after the new copy's length is verified against the source.

### ↩️ Reopening the screen you left

- **The launcher returns to the page you were on** — a list screen, or an app's own page — instead of
  the app list, and it survives the process being killed and the task cleared, because the screen is
  recorded rather than hoped for. One recorder covers every screen at once; an allowlist keeps the
  installer, the keystore prompt and a finished operation's page from ever being replayed.
- **The app list is not loaded while it is covered.** Reopening creates the main window and buries it
  at once, so reading and sorting every installed app for a screen nobody is looking at only starved
  the screen that was on top.

### 🤖 Automation, second shape

- **The switch ships on and the token ships off.** `automation_enabled` now defaults to **true** and a
  new **Use an authorization token?** switch defaults to **false**, matching every sister app. A
  pasted secret cannot survive a wipe, and the case this family now serves is restoring a clean phone
  where nothing has been pasted.
- **A token sent to an app that does not require one is ignored, never refused** — tokens outlive the
  setting they were pasted for, and refusing one would turn a switch into half a batch failing.
- **The whole check is one function**, so "disabled" and "bad token" cannot drift apart, and both flag
  writes `commit()` rather than `apply()`, because the gate now fails open.

### 🚪 The data door admits the automation app

- **応用管理 and 自由作業盤** are both authorised at this app's data door: an allowlist of exact package
  names, each cross-checked against the uid the kernel reports and a **pinned signing certificate**.
  Until now the door was self-only, so the automation app could not back this app up through it at all.
- Identity is checked **before** dispatch, so an unknown caller gets the same refusal for a bogus
  method as for a real one, and another app's call passes the automation switch as well.

### 🧾 Smaller things

- **A failure's reason gets its own line** in the operation log. Appended to the summary it was the
  tail of a middle-ellipsized row, which showed the size and the end of a sentence while eliding the
  part that named the failure.
- The selected-screen memory, the deep-scan cache and the migration offer flag are all **device-local**
  and stay out of settings export/import, as the other per-device stores already do.

## 4.1.0+2026-06-29.21-57.gfc1e7007+145 — 2026-09-05

The batch surface rebuilt: a full-page operation log, a pill shelf over three sibling screens, and
backup and restore chosen per app rather than per batch. (Built on upstream App Manager `4.1.0`,
commit `fc1e7007` of 2026-06-29 21:57 UTC.)

### 📋 A page for a running batch, and a log that survives it

The one-line progress dialog is gone. A batch now opens a **full page** over a live log that keeps
every app, stage and file — scrollable back to the beginning, and still there when the operation
ends. Two singletons sit behind it and the split is the point: one answers *what is happening now*,
the other keeps *what has happened*.

- **Every app in flight gets a line.** A backup runs one app per core, so "the current app" never
  existed; the header is as tall as the batch is wide, with each app's stage, its destination and an
  elapsed clock that moves even when nothing else does.
- **Every stage says which of how many it is**, in its own colour — and the number is repeated on
  **every progress line**, not only the heading, because a 6 GB export writes thousands of lines and
  scrolls that heading hours out of reach. The count comes from a plan built before the run, so a
  stage announced repeatedly (the data directories) keeps one number instead of walking the total.
- **The silent passes became stages**: storing the archive, encrypting it and checksumming it, each
  announced with the size it is about to walk. Restore has the mirror three — checking, decrypting,
  unpacking.
- **A sister app whose byte counter goes backwards says so** — that is a second pass over the same
  data, and nothing else in the log marked the boundary.
- **Interleaved threads never lie about ownership**: the log re-announces the owning app whenever
  the emitter changes, so every line belongs to the app named directly above it.
- Colours, text size and the timestamp column are all settable, and the log copies and saves through
  the same renderer it draws with, so a saved log matches the one you read.

### ⏹️ Cancel that actually cancels

The batch checkpoint fired once per **app**, before that app's work — right for a batch, no use at
all for a single app that takes twenty minutes. Cancel had nothing left to reach, and killing the
process was the only way out of a running backup. It now checks at every stage boundary, every few
megabytes of the large copy, and inside the sister-app transfer, whose wait loop polls once a second
and tells the app to stop before walking away. A cancel is also reported as a cancel: both ops
rethrow it ahead of their catch-all instead of dressing it up as "could not back up".

### 🗂️ A pill shelf, and three screens the list could not be

- A configurable, drag-reorderable strip under the toolbar. A pill is a **saved view** — filters,
  sort, profile membership and search captured together — or a **screen**.
- **保存一覧** lists every package with backups, including ones no longer installed: what is inside
  each, how stale it is, how much disk it holds. Long-press multi-selects; the bar backs up, restores
  or deletes the selection in one sweep.
- **盗み見一覧** ranks apps by what they may do right now, through the same resolver the per-app page
  and the enforcer use.
- **仲間** lists the sister apps from their manifests — nothing is woken, and frozen ones still appear.
- All three share the main list's colours, icon sizes, frames and separators: the same row, different
  columns. A **sister-app filter** puts the same question to the main list.

### 👆 A tap unrolls, it does not leave

- Tapping a row opens a pane **inside the list** with the facts the row has no space for and
  configurable action pills — 盗み見 and 応用情報 among them, so nothing the old tap reached is lost.
- The cut-off selection menu became a **batch pane** of wrapping pills, dispatching through the
  existing menu-id handler so no batch logic is duplicated. Both sets of pills reorder by dragging,
  and the pill moves with the finger.

### 💾 Backup and restore, chosen per app

- **Parts have colours.** One colour and one glyph each — APK, internal data, external data,
  app-supplied, OBB/media, cache, extras, rules — used by every surface that chooses between them.
  The palette is fixed rather than settable: it is a legend, and a per-screen colour picker would
  make it worth nothing.
- **Batch backup is a table**, one row per app with its own parts and a foldable app-supplied
  section. One set of flags for five hundred apps is only right when they are all the same kind of
  thing.
- **Batch restore is a table too**, and it had to be: the old chooser built itself from the
  *intersection* of every selected backup, so a part only some archives carried could not be chosen
  at all and the apps that had it silently did not get it back. Each app also picks **which** backup
  to restore from, since the parts are a fact about that archive.
- **Save versus OK** in the app-supplied category picker. Every close used to write the app's stored
  preference, so narrowing one backup narrowed every future one; the batch table wrote it forty
  times over. Save stores and applies, OK applies to this backup only, Use app's defaults forgets
  the choice.
- **Per-app flags, directories and categories travel with the operation** — and reach it: the
  single-app dialog had its own copy of the hand-off that silently dropped all three.
- **Every backup directory is datetime-stamped**, so the folder name answers "when was this" while
  you stand in the directory deciding what is stale. No more `0`-named base backup.
- **The old backup is deleted only after the new one is written** and renamed into place — and a
  failed run no longer leaves an empty directory behind. **Clean up broken backups** removes the
  ones already on disk: empty folders, half-written staging directories, directories with no
  readable metadata. A directory carrying readable metadata is never listed.
- The overwrite warning says what it will actually do and where "Back up multiple" lives; toggling
  that switch in the dialog is a decision about *this* backup and is no longer written back to the
  preference.

### 🤝 応用管理 is a sister app too

It could back up everyone else's data and not its own. It now implements the data contract itself,
exporting its settings, appearance, monitor, toolbar, notes, snooping decisions and profiles through
exactly the archive the 保存復元 broadcast already produces — so it appears under the sister-app
filter and its backups carry an App-supplied part with a category picker. Only its own uid may come
through the door; a stranger gets a refusal in the contract's grammar rather than a `SecurityException`
the client would misread as a missing door. Restoring its own state is **staged** and applied on an
explicit restart, because writing preference files under a running process is how an import silently
undoes itself.

### 🔒 Protection and safety

- **白い熊 雫, 白い熊 応用管理 and 白い熊 自由作業盤 can never be frozen, suspended or uninstalled**,
  checked before the profile lookup so the answer never depends on the profiles being readable. The
  必要 guard fails open by design — right for a list you edit, wrong for the apps that make this one
  work.
- **Suspension had escaped both chokepoints.** The Snooping policy card suspends directly, and
  suspension is the harder freeze; it is guarded now at the bridge and at the compat layer, with
  lifting never blocked.
- **A settings import may tighten a security setting and never loosen one** — screen lock, auto-lock,
  installer verification, tracker blocking, omit-sensitive-info and backup encryption are held when
  an archive would move them the wrong way, including by omitting them. What was held is named in
  the success dialog.

### 🔧 Fixes

- **The sister-app transfer correlated on the wrong job id.** The callee mints it; the client
  invented its own, so a finished 22 MB export was reported as "Could not backup" after a two-minute
  watchdog. The caller adopts the callee's id and parks replies that arrive before the call returns.
- Byte counters read `809,500,672` rather than `809500672`.
- The completion notification is drawn in the fork's colours.
- The toolbar no longer reserves an empty band above the shelf; the search field drove its height.
- A backup no longer stops to ask for a name.
- Restoring an app the database wrongly believed uninstalled offers "Back up" again.

## 4.1.0+2026-06-29.21-57.gfc1e7007+115 — 2026-09-05

App data can now be backed up without root, by asking each app for it — and the backup options
dialog was overhauled around that. (Built on upstream App Manager `4.1.0`, commit `fc1e7007` of
2026-06-29 21:57 UTC.)

### 📦 App-supplied data — the caller half of the sister-app contract

`/data/data` is closed to the shell and the platform's backup transport refuses release builds, so a
non-rooted phone could never capture an app's own data. Measured on the device rather than assumed:
`adb backup` is a dead route there, backup is disabled, and `allowBackup` is not even uniform across
the family. The answer is to **ask the app** instead of taking it.

- **Discovery is a manifest read** — three `<meta-data>` values, so the list is built across every
  installed app without starting any of them, frozen ones included. Read with `getInt`: `aapt2`
  stores a bare numeric `android:value` as an int, so `getString` answers null for every correctly
  built app and the whole mechanism empties silently. Queried with `MATCH_UNINSTALLED_PACKAGES |
  MATCH_DISABLED_COMPONENTS`, or frozen apps vanish from the query outright.
- **The payload is a file descriptor, never a path.** The app writes bytes into it and nothing else,
  and the archive is then folded into the backup through the ordinary path — checksummed, encrypted,
  committed with everything else, and verified before it is handed back at restore. An app writing
  into the backup directory itself would be writing into a temporary path about to be renamed, and
  its file would sit unencrypted and unverified beside files that are neither.
- **A synchronous door, an asynchronous answer.** The handshake is a `ContentProvider` call, which
  identifies the caller through the framework and so needs no shared secret; completion arrives as
  the broadcast this family already proved on EMUI. The watchdog is on **silence**, not duration, so
  a multi-gigabyte export is not killed for legitimately taking twenty minutes.
- **The awkward guarantees live here, not in forty-two apps.** A frozen app is thawed and re-frozen
  exactly as it was; a battery-optimisation exemption is granted around the call so a foreground
  service started from a binder call cannot be refused; and the app is force-stopped the instant it
  reports a successful import, because a live process writes its cached `SharedPreferences` back out
  at orderly shutdown and silently undoes what was just restored. Every change is recorded **before**
  it is made and reconciled at next launch, so a crash mid-export cannot leave frozen apps running.

### 🗂️ Per-app category picking, applied to bulk backups

The **App data categories** button on the App-supplied data row asks that app what it can export and
shows its own categories — nested under their parents, pre-ticked as the app recommends. The choice
is remembered per package and a **bulk backup applies it silently**, which is per-app control inside
a batch without a batch UI existing at all. An app nobody has customised is sent no selection and
exports its own recommended set, which is deliberately not the same as everything.

Both the ticked set **and what the app offered at the time** are stored: without the second, a
category the app adds in a later version is indistinguishable from one deliberately unticked, and
would stay out of every backup for ever. Unticking everything skips that app cleanly rather than
failing the backup.

### ✂️ ADB data withdrawn

The option routed app data through the platform's backup transport, which refuses release builds on
this phone — and when it *did* engage it **superseded the internal and external data flags**, quietly
replacing a working backup with an empty one. It is gone from the dialog and stripped in
`getSanitizedFlags`, since a preference saved by an older build still carries the bit and would have
taken that branch regardless.

### 📋 The backup options say what they actually do

- **External data** names its directory — `/storage/emulated/0/Android/data/` — beside the title, and
  describes what lives there rather than calling it "external data folders".
- **Cache** starts unticked, and its description is corrected: the real exclusions are `cache`,
  `code_cache` and `no_backup`, not the `no_cache` the dialog claimed for years. It now says these
  are subfolders of the folders above, and that `no_backup` is the app asking not to be backed up.
- **Extras** lists what it collects — permissions and their flags, app-op modes, background data
  restrictions, notification-listener access, battery-optimisation exemption, granted URI
  permissions, the freeze method. It no longer collects the **SSAID**, whose file is `system:system`
  and unreadable as the shell, nor **MagiskHide / DenyList** state, which is root-only: every backup
  was paying two package queries to record nothing.
- **Rules** explains itself — blocked components and the permission and app-op decisions managed
  here, and explicitly none of the app's own data.

### 🔧 The backup sheet fills the screen again

The bottom sheet sized itself from a `ViewPager2` that was measured **before its adapter was set**,
so the pager had no pages at all — and ViewPager2 never re-measures for `wrap_content` once one
arrives. It froze at that empty first pass and clipped the options list, which had been surviving on
luck until a description grew to two lines. It now fills the height the sheet may occupy, measured
from settled on-screen positions less the navigation-bar inset, so the tab's own *Back up* button
stays reachable.

### 🎨 Dialog theming

- **Tick marks are tinted explicitly.** The row's tick is `?android:attr/listChoiceIndicatorMultiple`,
  a framework drawable whose tint chain ignores this theme's pinned accent — which is why every row
  rendered Material's baseline lavender while the *Select all* box above it was correctly yellow.
- **The searchable dialog's field is a pill**: black fill, accent border, accent query text and a
  dimmed accent placeholder. The border needed a stroke on the widget's own `MaterialShapeDrawable`,
  since it discards any `android:background` a style sets and paints that shape instead.

## 4.1.0+2026-06-29.21-57.gfc1e7007+105 — 2026-09-03

The app list can now be narrowed to the apps whose backup is out of date — the set a re-backup would
actually change. (Built on upstream App Manager `4.1.0`, commit `fc1e7007` of 2026-06-29 21:57 UTC.)

### 💾 “Backup older than app”

The main list could already answer *has a backup* and *has no backup*, but not the question that
decides whether backing up again is worth the minutes: **is the backup I hold older than the app that
is installed now?** Answering it meant reading the backup column row by row.

The filter sheet gains **Backup older than app** (バックアップが古い), directly under *Without backups*.
Tick it, select all, back up — the list is exactly the apps a re-backup would change.

An app is listed only when all three hold: it is **installed**, it has **at least one** backup, and
**none** of those backups reaches the installed version. The third condition is the point, and it is
deliberately stricter than the “some backup is outdated” test the filter engine already carried: that
one lists an app holding a *current* backup as well as an older named one, which is precisely an app
that needs nothing done to it. One up-to-date backup clears an app, however many stale ones sit
beside it.

Nothing has to be refreshed by hand. Writing or deleting a backup already tells the list its database
changed, so a row leaves the filtered view the moment its new backup lands; an app *update* arrives on
the ordinary package broadcast, which refreshes the version the comparison reads.

## 4.1.0+2026-06-29.21-57.gfc1e7007+104 — 2026-08-24

The batch-progress dialog now says what it is actually doing, and the app list can be narrowed to
the apps you left a note on. (Built on upstream App Manager `4.1.0`, commit `fc1e7007` of
2026-06-29 21:57 UTC.)

### ⏯️ The batch dialog stops being a counter

A 550-app backup moved `52 / 550` once per app, and a single app with a large data directory held it
still for minutes — indistinguishable from a hung operation. The dialog now carries four layers:

- **The counter**, with a percentage beside it.
- **A summary line** — elapsed time, an estimate, how many apps are running, how much has been
  written, how many failed. The estimate comes from the rate the batch has actually achieved and is
  withheld ("estimating…") until two apps have finished: before that the "rate" is one sample of a
  quantity that varies a thousandfold between a stub app and a game, and a wildly wrong number is
  worse than none.
- **One block per app in flight**, with its label, its package id, the **full destination directory**
  it is writing to, the **stage inside that app** (APK files · Data 2/4 · KeyStore · permissions,
  ops and rules · metadata and checksums · finalising) and how long that app has been going.
  Deliberately plural: backup runs one app per CPU core, so "the current app" never existed — the
  monitor now holds a keyed list of in-flight items rather than a single label, which the sequential
  ops (freeze, uninstall, block trackers, …) fill with exactly one entry, so **every** batch
  operation gained the app's name along the way.
- **A note line** — "Waiting for the next app…", and while paused, "Paused — apps already started
  will finish." Pause has always been a checkpoint *between* apps; without saying so, the apps that
  keep completing after the tap read as a broken button.

The clocks are redrawn once a second. Progress events alone cannot do it: they arrive when something
happens, and the interesting case is precisely when nothing has happened for a while.

### 🚶 The in-flight list walks itself

Under its height ceiling only two or three blocks fit, so with eight apps in flight the rest were
reachable only by dragging — which defeats a readout meant to be watched rather than operated. It now
steps one row at a time, holds each for 2.5 seconds and wraps to the top at the end. Rows rather than
pixels: a crawl is hard to read and parks text mid-line, whereas stepping to a row's own top edge
always presents a whole block, and the step finds the *next row* rather than advancing a fixed
distance, because rows genuinely differ in height. Any touch stops the walk for six seconds — the one
that starts a fling included, since a fling settles long after the finger is gone — and it then
resumes from where you left it rather than jumping back to the top.

The ceiling itself is new too: the list is one block on a small device and eight on this one, so
`wrap_content` would push Pause and Cancel off the screen while a fixed height would leave a hole.

### 🔩 How the detail reaches the dialog

`ProgressHandler` can only carry a number and a notification title, which is why there was nothing to
show. A new `BackupProgressListener` carries the rest — the destination from `BackupManager` the
moment the backup directory exists, the stages and byte counts from `BackupOp` and `RestoreOp`. Byte
counts are read off the archive members themselves, so what is reported is what landed on disk
(compressed, encrypted, after exclusions) rather than the size of the source directory, which would
over-report by a wide and varying margin. The destination is the backup's **final** directory, never
the hidden `.{name}` staging directory that the engine hands out mid-backup and renames away at the
end — showing that would name a path the backup can never be found at.

### ⏱️ Durations are localized

The dialog's own duration format is a resource, so a Japanese reader gets `12分04秒` rather than
`12m 04s`. App Manager's `DateUtils.getFormattedDuration` is deliberately not reused for this: it is
built for usage statistics, drops seconds, answers "less than a minute" below one — exactly the range
a progress readout opens in — and varies its unit count with the value, so the field would jitter
every second. Two units at most, the second zero-padded, so the width stands still.

### 📝 "With notes" filter on the app list

A note is written because the app needed remembering — *keep frozen*, *breaks banking*, *needed by
the launcher* — so the set of apps carrying one is a list worth being able to summon, and until now
the only way to find them was to scroll for the pill. The filter sits with the other "With …" rows.

It is a real filter option rather than a main-list special case, so **Finder and filter profiles get
the same condition**, with `contains` and `regex` keys over the note text — which is where it earns
its keep: a saved profile for every app whose note mentions something. The note store is read live
rather than snapshotted, because a filter profile holds its options between runs and a cached key set
would go stale on the next edit.

Because the filter's input is not a property of the apps themselves, it re-applies after a note is
saved (deleting a note must drop the row, not merely un-draw its pill) and on resume — notes are
editable from app details, and the whole store is replaceable by a settings import, neither of which
the list otherwise hears about.

## 4.1.0+2026-06-29.21-57.gfc1e7007+100 — 2026-08-24

Freezing now survives a restart on EMUI, and the snowflake freezes the way you chose. (Built on
upstream App Manager `4.1.0`, commit `fc1e7007` of 2026-06-29 21:57 UTC.)

### ❄️ Advanced suspend is the default freezing method

Stock App Manager defaults to **Disable** — `pm disable-user` — and calls it the recommended method.
On this phone it is the wrong one. EMUI restores the enabled-state flag for Huawei's own system
packages at boot, so a disable-freeze quietly comes undone at the next restart; the per-user
*suspension* flag is left alone and survives. Measured on the Mate XT against `com.huawei.powergenie`,
which is exactly the sort of package you freeze and expect to stay frozen.

The default is now **Advanced suspend**: force-stop, then suspend. The force-stop matters as much as
the suspension — without it the app is left running until it dies of its own accord.

Existing installs are moved across by a **one-time migration**, because a changed default would
otherwise never reach them: App Manager writes every preference default into `preferences.xml` on
first run, so the old value is already stored and the new default is never consulted again. The
migration fires once, and only while the stored value is still exactly the old default — a method you
pick from this build on is never overwritten. Anyone who prefers the old behaviour can simply set
*Disable* back under Settings → Rules → Default freezing method.

### 🎯 The snowflake freezes the way you chose

App info has always offered a **freeze method picker with "remember this option for this app"**, and
the app-details freeze, the freeze shortcuts and batch freeze all honoured what it stored. The two
**snowflakes did not**: the main-list row icon and the battery panel's row icon went straight to the
global default. So remembering "suspend" for one package changed nothing about what its row icon
actually did — a setting that appeared to be ignored, with no way to tell from the screen.

One place now decides the method for everyone — the method remembered for the app, else the global
default — and both snowflakes use it. Plain batch freeze picks it up by the same route. Nothing that
was already correct changed behaviour.

## 4.1.0+2026-06-29.21-57.gfc1e7007+099 — 2026-08-18

The Snooping page now says when each capability was last used, and last refused. (Built on upstream
App Manager `4.1.0`, commit `fc1e7007` of 2026-06-29 21:57 UTC.)

### 🕵️ Used / denied, per capability

Every app-op-backed row gains a third line: *Used 3 hours ago · Denied under a minute ago*. The
numbers are the platform's own per-op bookkeeping, which the row already held — so this costs no
extra privilege and no extra work when the page loads.

**In the background** is called out separately, because an app reaching for the microphone while it is
not on screen is a different fact from one doing it while you watch — and when the most recent use was
on screen but there is an older unseen one, that is named too rather than dropped. It is gated on
Android 9+, below which the platform keeps a single undifferentiated timestamp and a "background"
marker would be invented rather than measured.

A **recorded denial is the only part drawn in the theme yellow**: it is the plainest proof that a
block is doing work, and the one line here you would act on. A capability last touched four months
ago is the argument for turning it off.

### ❓ Three answers, never a blank

A blank line reads as *clean*, and for the cases that matter that would be a lie. So each half of the
line always says which of three things it means:

- **a time** — the system recorded it;
- **never used / never denied** — the system keeps that record for this capability and it is empty. A
  fact, not a gap;
- **not recorded** — there is nothing to read, and the row names which of three reasons applies.

The three reasons are distinct on purpose. A **lever or permission-only row** (a network policy, a
system list, a role) has no app-op behind it and nothing anywhere counts it. An **unreadable app-ops
query** is a gap in what we could see, not in what the app did — previously indistinguishable from an
app that had genuinely never touched anything, which would have had the whole page quietly reporting
"never used".

The third is the one worth understanding: **a denial is only counted when the app-op is what refuses
the call**. That holds for the capabilities needing no permission — clipboard reads, screen capture,
running in the background — which are blocked at the op itself. But a permission-gated capability is
blocked by revoking the permission, and the refusal then happens one layer earlier: the app never
reaches the app-op, so nothing is ever counted however hard it tries. Such a row now reads *"Denials
not recorded (stopped at the permission)"* rather than the page's most misleading sentence. A denial
that *was* recorded always wins over that reasoning — a legacy app revoked compat-style keeps its
grant and is stopped by the op, which is exactly the case that does count.

⋮ → *What the marks mean* gained a **Used / denied line** section covering all of it, including the
caveats that these are last events rather than tallies, and that a shared user ID reports for the
whole group.

## 4.1.0+2026-06-29.21-57.gfc1e7007+098 — 2026-08-16

The privilege alarm speaks Japanese. (Built on upstream App Manager `4.1.0`, commit `fc1e7007` of
2026-06-29 21:57 UTC.)

### 🇯🇵 動作モードが失われたことを日本語で

The alarm added in +097 shipped English-only, which on a Japanese phone made the one bar that has to
be read at a glance the only thing on screen that could not be.

The three strings follow terms already in use rather than inventing their own: **動作モード** is
upstream's own name for the mode of operation, so the bar names it exactly as the settings screen
does, and **タップして…** is how every other tappable hint in the app is phrased. The failure line
takes its shape from the existing app-op failure message, and a full stop separates the two halves of
the alarm instead of the em dash the English uses — the Japanese resources have never used one.

## 4.1.0+2026-06-29.21-57.gfc1e7007+097 — 2026-08-16

Losing the privileged session is no longer silent, and mostly no longer permanent. (Built on upstream
App Manager `4.1.0`, commit `fc1e7007` of 2026-06-29 21:57 UTC.)

### 🔌 A restarted Shizuku server is reclaimed by itself

Every privileged call in the app runs through one binder, and restarting a Shizuku server — updating
白い熊 雫, stopping the server, killing its user-service process — takes that binder with it. The
**entire** reaction to that was a field being set to `null`: from then on the app quietly fell back
to the **no-root shell**, nothing threw, nothing was shown, and privileged operations silently did
nothing while the window looked exactly like a working one. The only cure was restarting the app.

It is now reclaimed automatically, because reclaiming is free when it is possible at all:
authorisation is held by the **server's** own persisted list and survives its restart, and a
returning server *tells us it is back* by pushing a fresh binder at us. Both that notice and the
death notice already existed and were simply never listened for. Now they are, for the life of the
process rather than by a screen — so a server that comes back while the app sits in the background is
picked up without a prompt, without a tap, and without anything to approve. **Starting the server
after opening the app** works the same way, which is the ordinary way round.

The recovery goes through the app's existing mode-initialisation rather than a private rebind, so it
lands on the same flags, the same uid check and the same permission re-grant that a cold start would
— and announces itself with the usual *Working on Shizuku mode*.

### 🚨 A red alarm when it cannot be reclaimed

Auto-recovery can only fix the recoverable half: a server that stays down, a withdrawn
authorisation, or an update that changes what the server will start are real losses — and there was
**no indicator of any kind**, the mode of operation being visible only in settings and in a one-shot
toast at startup. An app manager silently running unprivileged is worse than one that never had
privileges.

So a **red bar** now sits across the top of the app list: *Mode of operation is no longer Shizuku —
tap to reconnect*, with a pulsing warning mark. It is pinned rather than scrolled with the list, it
shows the attempt while it runs and reports one that failed instead of leaving the tap looking inert,
and it is the only path allowed to raise a prompt — everything automatic waits for a server that
already trusts us. It can never appear on an install that has always run unprivileged: it is armed
only once privileges have actually been held.

## 4.1.0+2026-06-29.21-57.gfc1e7007+096 — 2026-08-14

The kernel UID moves to the front of the app ID. (Built on upstream App Manager `4.1.0`, commit
`fc1e7007` of 2026-06-29 21:57 UTC.)

### 🔢 The UID leads the app ID instead of trailing the date

Every main-list row carries the app's kernel UID, but it sat right-aligned and 10sp at the far end of
the **install-date** line — where a bare number reads as something to do with the date. It names the
app, so it now leads the **app ID**: `10123  io.github.muntashirakon.AppManager`.

The number keeps its natural width and the package name takes the slack, so a long ID is clipped at
its own end rather than pushing the UID off the row; it disappears entirely for an uninstalled app
instead of leaving the ID held off its own left edge by an empty gap. The value is unchanged —
the raw UID for a single-user app, `2+123` when the app exists in several users — and so is its
meaning: it turns **orange** when the package shares a user ID with another.

It stays its own view rather than becoming part of the package-name text, so the **UID font
category** and the **UID colour rows** on the 白い熊 応用管理 UI page remain live settings; folding it
into a span would have quietly retired both. The battery screen's header is the same card and
follows it, printing the UID number there too — it used to print the shared-user-ID *name*, which is
long enough to take the whole line for itself in this position.

## 4.1.0+2026-06-29.21-57.gfc1e7007+095 — 2026-08-14

A row's annotations — its note and its tags — now read as one column instead of three unrelated
controls. (Covers builds +094 and +095. Built on upstream App Manager `4.1.0`, commit `fc1e7007` of
2026-06-29 21:57 UTC.)

### 📝 The note is one pill, and it says what it says

The main list's note affordance used to be two different controls in two different places: a glyph
glued to the end of the app name when a note existed, and a `+` floating over the column's top-right
corner when one did not. Neither could show what the note actually **said** — a note was only ever
readable by opening it.

It is now **one pill** in both states, right-aligned at the end of the label line. With a note it
carries the note's **first line**, ellipsized to whatever the row can spare, with the full text as its
tooltip; with none it is the same pill holding only an add glyph — a page with a deliberately
oversized plus, because at row size a Material-proportioned one is indistinguishable from a plain
note. A tap opens the same editor as before, and saving a blank note still deletes it.

### 🏷️ Tags right-aligned, and one builder behind every pill

The profile pills are **right-aligned**, against the note pill's own edge, so a row's annotations
form a column instead of drifting apart. When there are more than fit they **scroll sideways** rather
than wrapping onto a second line and costing every row its height; the `+` sits outside the scroller
and can never be scrolled out of reach.

Both **add** affordances — the empty note pill and the tag `+` — are now uniform: same stadium
outline, same pinned width, same faded ink, stacked into one column at the row's right edge. They are
uniform *by construction*: every pill on the row comes from one builder, in the fork's own pill
language (the same one the Snooping page's status pills use). Opacity carries the hierarchy — an add
control is faded because it is an invitation rather than information; a written note is drawn below
full strength so it reads as a margin note rather than a second title; a tag pill alone is
full-strength, being a fact about the app.

The battery panel's header is the same card, bound through the same builder, so the two cannot drift
apart — and **its note pill now works at all**, having carried a visible `+` with no listener behind
it since the card was added.

### 📐 The app name in full; the note takes the rest

Where the note pill stops is decided at **measure time**, not when the row is bound: the app name
keeps its natural width — in full, and not one pixel more — and the note takes every remaining pixel,
reaching back to exactly where the name ends.

Plain weights cannot say that. Weight on both and the surplus splits between them, leaving a gap
after a short name and truncating the note early (which is what build +094 shipped). Weight on the
note alone and it does reach the name — until a name longer than the whole line leaves it **zero**
pixels wide and the note disappears from the row. So the label line caps the name at *what the line
has minus what the others need* before measuring, floored at half the line so a three- or
four-column grid cannot reserve the name away entirely.

### 🔢 Version names group with `+`, and pin to the minute

The fork's version string now reads `4.1.0+2026-06-29.21-57.gfc1e7007+095`: a `+` opens each
top-level group — upstream's own version, the upstream commit this fork is rebased on, and our build
counter — while the pin's date, time and hash stay dot-joined, since all three describe one commit.
The pin gained the **time** because two upstream syncs landing on the same day tied on the date and
handed the sort order back to a random-looking hash. Both halves that render it, the Gradle build and
the shell helper the pipeline uses, changed in step.

## 4.1.0.2026-06-29.gfc1e7007+093 — 2026-08-10

盗み見 is the page App details opens on, and it can now end the argument about an app outright.
(Covers builds +091 through +093. Built on upstream App Manager `4.1.0`, commit `fc1e7007`.)

### 🕵️ Snooping is the first tab

App details now opens on **Snooping**, with App info second. It is the page this fork exists for, and
it was one swipe away from every route into an app — the main list, the app icon, the battery panel,
`SHOW_APP_INFO` from Settings. All of them now land on it.

The tab's internal property id is deliberately **unchanged**: those ids key the view model's
per-property state, so display order and identity are kept apart — the order lives in one array, the
id stays where it was.

### 🧊 Freeze and Uninstall, on the same card as suspension

The device-policy card carried the *hard* freeze (suspension) and nothing else that acts on the whole
app. It now carries the two **ordinary** verdicts as well, as pills that need no Device Owner:

- **Freeze** — the same freeze as the main list's snowflake, routed through the same chokepoint, so
  the protected `必要` profile refuses it exactly as it refuses the snowflake. The freeze method is
  chosen with the usual dialog (including *remember for this app*), or applied straight away if
  *Skip freeze method dialog* is set. Grey and reading **Freeze** while the app runs; yellow and
  reading **Unfreeze** once it is shut — the same palette and direction as the suspend switch above
  it. Unfreezing lifts every freeze type at once, a suspension included.
- **Uninstall** — the same confirmation App info asks, keep-data checkbox and all, plus *uninstall
  updates* for an updated system app, and the platform's own uninstaller for another user's package
  when we lack the privilege to remove it ourselves. Red in both states: it is the only thing on the
  page that the control which did it cannot undo.

They are **pills, not rows**. As rows with a title, a bold note and a chip each they spent three
quarters of a screen on two actions and pushed the capabilities themselves below the fold; the
account now lives behind an **i** in each pill, read once instead of scrolled past every time. The
two are weighted halves of the card, so they line up with the two columns the list breaks into when
the phone is unfolded.

Frozen state is read **live** from the package manager, with the match flags a hidden package needs
to resolve at all — the cached package info reloads asynchronously after a write, and binding off it
would draw the state you just left. Every write that lands announces itself, so the main list picks
the change up instead of showing a stale row.

### 🐛 The Snooping list can go two-column again

Two separate faults, one symptom — the page stuck at a single column however the phone was unfolded:

- The auto-fit grid decides its column count on its **first layout**, from a width that is still zero
  until the view has actually been laid out, and then never revisits it. That was harmless while
  Snooping was the second tab, since an off-screen page is built after the first layout pass has
  already happened — and it became load-bearing the moment the tab became the one the activity opens
  on. The count now comes from the window configuration, before any view is measured, so it cannot
  depend on when the page happens to be laid out.
- The threshold was also simply too wide to ever split **this** screen: 450dp per column against a
  tri-fold that unfolds to an 840dp window is one column by arithmetic. It is 380dp now, picked
  against the two real geometries — two columns unfolded, one on the folded cover panel with room to
  spare.

## 4.1.0.2026-06-29.gfc1e7007+090 — 2026-08-06

The version now says which upstream App Manager this build actually contains.
(Covers builds +089 and +090. No behaviour changes — this release is about what the version means.)

### 🔖 The version pins the upstream commit the fork sits on

This fork follows upstream's **master branch, commit by commit** — never a release tag. That is
deliberate: MuntashirAkon commits to master for months while the release tag stays frozen, so tags
would be a useless base. The side effect was that upstream's own version literal stood still with it:
every build since June called itself `4.1.0`, and `4.1.0+088` told you nothing about whether upstream
had moved since. The one fact missing from the version was the only one that could answer it.

Versions now carry it:

```
4.1.0.2026-06-29.gfc1e7007+090
└─┬──┘ └───┬────┘ └───┬───┘ └┬─┘
  │        │          │      └── fork build number, as before
  │        │          └───────── upstream commit this is rebased on
  │        └──────────────────── that commit's date (UTC)
  └───────────────────────────── upstream's own version, untouched
```

The commit is the **merge-base of our branch and upstream's** — the commit our patches sit on, not
the tip of upstream's mirror (which would overstate what is actually in the build) and not our own
HEAD (which the build number and the release tag already identify). It therefore moves only when the
fork is genuinely rebased, which is exactly the "has upstream moved?" signal it exists to give.

The date is there so the names **sort**. A bare commit hash is random text: `gfc1e7007` would file
itself arbitrarily among its neighbours and bury the newest APK mid-list. It is the commit's date,
never the build's, so every build on one upstream base shares it — and in UTC, so anything reading
the date back from GitHub's API agrees with what the build wrote.

Everything downstream follows the same string: the APK filename, the release tag, and the version the
installer shows you are now the same text, produced in one place. `versionCode` is unchanged and
still rises with every build, so updates install exactly as before.

## 4.1.0+088 — 2026-08-06

Every list screen remembers a layout per screen shape, the process monitor's top bar stops hiding
its own tools, and the monitor opens on the question it exists to answer.
(Covers builds +085 through +088.)

### 📐 One layout per geometry, remembered separately

A column count that suits the folded cover panel is wrong on the unfolded screen and wrong again
turned sideways — but until now the main list, the process monitor and the battery history each kept
**one** pick and applied it everywhere. Setting the layout you wanted for one shape meant unsetting
the one you wanted for the others.

All three now keep **one pick per geometry**. Fold, unfold, rotate, or drop the app into half the
screen, and each shape shows what you last chose *for that shape*, across restarts. Nothing new to
configure: the picker is the same dialog, it simply writes to the shape you are currently in.

What counts as a shape is the orientation together with the window's shorter side in dp — which is
the fold, measured rather than asked for. It moves when panels unfold and stands still when you
merely rotate, so no fold API, no vendor-specific list of devices, and a phone with three panels or
none gets sensible slots for free. Split-screen falls out of the same rule, since Android has
described the *window* rather than the display since Nougat: a half-screen window is its own shape
and keeps its own layout.

Your existing pick is not lost. It seeds every shape you have not set yet, so nothing changes until
you choose — and from the first choice onwards each shape is on its own.

### ↔️ The process monitor's top bar scrolls sideways too

The main top bar gained this in `+084`; the monitor needed it more. It carries six tools — search,
pause, filter, sort, layout, refresh — plus a title and a live "N processes · M killable · L leaks"
summary, and on a folded panel Android answered by hiding most of them in the overflow menu and
cutting the summary off mid-word.

Now everything stays on the bar at full size and the overflow runs past the edge, reachable by
dragging the bar left and right. The summary line is no longer truncated either. Widen the screen and
it behaves exactly as before.

### 🎚️ The layout icon opens a picker instead of cycling

The monitor's 3×3 grid icon used to cycle 1 → 2 → 3 columns, one tap at a time. It showed neither
what the choices were nor which one you were on, and reaching the one you wanted meant tapping
through the ones you did not.

It now opens the same single-choice dialog the main list uses — **Adaptive** or a fixed **1 / 2 / 3 /
4 columns**, the current one already ticked, Cancel to leave it alone — and nothing changes until you
pick. Adaptive (one column per 450dp, the main list's rule) is the new default for the monitor, and
four columns is newly available.

### ⚡ The monitor opens sorted by CPU

A reaper is opened when something is burning the phone *now*, and that is a CPU question; memory is
the standing state you can look up whenever you like. The list now opens ranked by CPU, with the Sort
toolbar action still flipping back to RAM.

The first frame is still RAM-ordered by necessity — CPU percentage is the difference between two
`/proc` readings, so it does not exist until the second one — and the CPU order lands one refresh
later.

### 🎯 A round gauge for the process monitor

The monitor's top-bar icon was Material's "speed" glyph, whose dial is an arc with a flat bottom. At
the size a top bar actually draws it, that read as a **cloud**. It is now a proper round tachometer —
full ring, seven scale ticks, needle and hub — chosen from nine candidates rendered at real size
before any of them shipped.

## 4.1.0+084 — 2026-08-04

The top bar stops hiding things on a narrow screen.

### ↔️ The main top bar scrolls sideways

Every entry on the main top bar — the process monitor, battery history, app usage, clear-filters,
the filter funnel, the layout picker — is pinned there on purpose. The alternative, letting Android
place them "if there is room", is what put them in the overflow menu on a folded panel, which is
exactly where they are of no use.

The bill for that came due on the search field. It is not a menu entry but the toolbar's own custom
view, sized to whatever the icons leave behind — and on a narrow screen they left nothing. It
collapsed to its bare search-type button, which reads as the search bar having disappeared
altogether.

The bar now **scrolls left and right**. Everything stays on it at full size, and whatever runs past
the screen edge is reached by dragging the bar. Nothing is squeezed, nothing retreats into the
overflow, and no entry has to be dropped to make room for another.

Widen the screen and it behaves exactly as before: when the whole bar fits, the search field
stretches across the space the icons leave, and there is nothing to scroll. So the unfolded display
is unchanged and only the folded one gains the gesture.

## 4.1.0+083 — 2026-08-04

The device-policy card stops speaking in double negatives, its last dialogs go away, a lock can now
be remembered, and a suspended app finally looks suspended on the main list.
(Covers builds +078 through +083; the intermediate ones were never released.)

### 🔁 Switches that name the capability, not the lock

The four device-policy boxes were labelled after the **lock** — *Block uninstall* — and were on when
that lock was in force. That inverted the page's own grammar. An app nothing protects showed three
switches sitting at rest and no red anywhere on the card, while a thoroughly locked-down app lit up
like a warning. The colour language of every capability row below it says the opposite: red means the
app can be got at right now.

Each box now states what the phone is still open to — **Can be uninstalled**, **Can be
force-stopped**, **Accessibility can be enabled** — so the switch is flipped right and red exactly
when that is true, and left and yellow with a thick frame once policy has shut it. The frame keeps
its meaning too: unlocked is what every fresh install gives you, so the frame marks a decision of
yours, never the platform's default.

The **suspend** switch runs the other way on purpose and is now drawn that way: grey and off is the
state every phone ships in, yellow and on means you have shut the app down harder than freezing
would. It was red-when-suspended, which read as a warning about the very thing you had just done to
protect yourself.

### 💬 No more confirmation dialogs on that card

All four dialogs are gone — the three boxes and the suspend switch. Every word they carried is now
the control's own description, where it is read *before* the tap rather than dismissed after it: what
the lock does, what stops working, and how to undo it. The accessibility box still warns that
automation tools, keyboard helpers and screen readers are what accessibility services drive, because
that is the one whose consequence is felt immediately — it just says so where you can act on it.

The "held by 白い熊 雫, which offers no way to read it back" caveat is now **appended** to a box's
description instead of replacing it, so a box no longer explains what its switch does right up until
you use it and then explains something else.

**Clear all locks** keeps a confirmation — it is not a toggle, it releases everything at once, and the
only way back from a mistap is re-applying each lock by hand — but it loses its 危険 heading. It is
the way back from the dangerous things, not one of them.

### 🔗 Remembered device-policy locks

A padlock beside a capability can now be **remembered**, and the mark is a ring around it.

Why it is needed at all: a hard lock cannot drift the way an app-op can — Settings will not lift it
and the app cannot, which is the whole point. But it is stored against the package's *installation*,
so a full uninstall takes it with it; and everything 白い熊 雫 holds vanishes the moment it stops
being Device Owner, for every package at once. Both leave a lock you set silently absent.

- **Yellow ring** — remembered: if the platform loses the lock, it is put back.
- **Red ring on a hollow padlock** — remembered, and gone. The lock is not in force. This is the
  state nothing else on the page could report, since the padlock and the pill both faithfully show
  what the platform is doing right now and therefore hide the disagreement.
- Tapping the padlock to lock also arms the memory; releasing forgets it; **long-press** changes only
  the memory, and only where there is a lock or a memory of one.

The replay runs on package install, on our own update, and on the app-start sweep. It re-reads after
every write and records nothing it did not achieve, so a lock the platform refuses stays remembered
and is tried again rather than quietly dropped. Unlike a remembered capability it does **not** travel
in a settings export — a Device Owner belongs to one phone, and so does everything it holds.

One ordering rule made it work: the ordinary snooping replay and the lock replay now run in **one**
background task rather than two. A fresh install has every permission back at its default, so the
plain replay certainly writes — to the very grant state a policy lock pins — and the shared thread
pool would not have ordered two separate posts.

### 🟣 Suspended apps on the main list

Suspending an app from the Snooping card left it looking **active** on the main list: same upright
label, no film, and an orange "running" frame around an app the system had stopped dead.

The cause is worth recording. The main list is never polled — it is rebuilt from broadcasts — and
suspension emits no `ACTION_PACKAGE_CHANGED`. The platform sends `ACTION_PACKAGES_SUSPENDED`, which
the fork does listen for, and it never arrived. So the row kept the state it was built with; and
because the running frame is drawn only when a row is *not* frozen, a row that never learned it was
frozen also never stopped looking alive. Every policy write that changes what the list shows now
announces it directly, the same way the rest of the app announces its own writes.

Suspended rows then keep the italic and the film that every frozen row has, and add three cues so the
deepest dormant state is never mistaken for an ordinary freeze:

- a **violet padlock** in place of the snowflake — shape carries further than colour at that size;
- the app's name **struck through** — the cue that survives any icon size, column count or palette;
- a **heavier violet film**, distinct from the cool cyan of frozen and the mauve of uninstalled.

Both new colours are configurable like everything else, and both appear in the UI page's legend.

A companion fix: the platform records suspension **per suspending package**, so the ordinary
unsuspend could not lift one applied under 雫's admin — the main-list snowflake would have appeared
to do nothing at all, for ever. Unfreezing now releases a device-policy suspension too.

### 👆 Tap an icon, get its Snooping page

Tapping an app's **icon** on the main list now opens that app's Snooping page. The rest of the icon
column still toggles freeze, so nothing was displaced — the glyph you tap to freeze is the freeze
snowflake, which is where it belonged.

The target tab is threaded through every route into app details — a single reachable user, a package
we wrongly believed uninstalled, a picker across several users — so the tap always lands on the same
tab. The icon also repeats the card's selection-mode guard (during a multi-select it extends the
selection instead of opening an app, since the icon is the easiest thing to hit by accident) and
forwards its long-press to the card, which a clickable child would otherwise swallow.

### 🎯 The padlock's tap area

The padlock glyph is a 24dp square and its column was 40dp, leaving 8dp of slack each side. Aim at
it, overshoot by a finger's width to the right, and the tap landed past the view on the card — whose
click **advances the capability**. Missing a lock did not do nothing; it changed a permission.

The column is now 48dp, and a touch delegate extends the lock's hit area over the gap towards the
switch and across the row's whole height. It stops at the switch's edge deliberately: the switch's
own taps fall through to the card, which is how tapping it advances the state, so reaching further
would have stolen that gesture instead.

## 4.1.0+077 — 2026-08-04

Two more entries on the top bar, a one-column list that actually uses its width, two more screens in
the fork's palette, and the device-policy powers brought out of the menu they were hiding in.
(Covers builds +073 through +077; the intermediate ones were never released.)

### 🧭 App usage and clear-all-filters on the top bar

**App usage** now sits immediately right of the battery icon — the third *where did it go* screen
beside the monitor (what is burning CPU right now) and the battery history (what drained it while you
weren't looking). **Clear all filters** sits immediately left of the filter funnel it undoes, so the
pair reads as set/reset.

Both are `always`, not `ifRoom`. As `ifRoom` they silently fell into the overflow on a narrow or
folded panel — which is where clear-all-filters had been hiding ever since it was added, present in
the menu and invisible on the bar.

### 📐 A one-column list that uses its width

The layout picker gained **1 column**, between *Adaptive* and *2 columns* — and then had to earn it.

Each row's three columns were declared at a fixed **1 : 2** (label/package : version and backup), a
ratio tuned for a two-column grid and wrong the moment a row gets wide. The right column's content
does not grow with the row — a version, user/system, an SDK level, a signature, and the backup values
beside them — so on a one-column list that share bought it hundreds of dp of nothing: a gap between
the version block and the backup block, another between the backup block and the card's edge, and the
package name **ellipsized at a third of the row** to pay for both.

It is no longer a share:

- The version/backup block is **measured from its own signature line** and pinned to that width, so
  the label column absorbs the entire surplus. Measured rather than hardcoded in dp, so the
  configurable fonts carry through — a larger row font widens the block instead of clipping inside
  it. On a one-column list that hands roughly 235dp back to the app name.
- Inside each pair the **backup field became `wrap_content`**, so the backup values sit flush at the
  card's end, the version values flush left, and a long version string gets whatever the backup side
  did not need instead of a fixed half.
- The old 2/3 proportion survives as a **clamp**, so a folded panel or any multi-column grid is left
  exactly as it was. The change can only ever widen the label column, never narrow it.

Separately, the app name now **reserves the width of the note "+" and the debug star** that float over
the top-right of its column. They live in the same `FrameLayout` as the name, pinned `end|top`, while
the name is left-aligned — so a long name used to run underneath them and render with a "+" stamped
through its last characters.

### 🎨 App usage and Finder in the fork's palette

Both screens render under the yellow-on-black theme: the purple *Daily* pill becomes the yellow/black
chip, the chart bars and progress bars turn yellow, the surfaces go black.

Two things a theme structurally cannot reach are done by hand. The row cards take **the main list's
frame** — yellow for a user app, orange for a system one, width and roundness read from the same
preferences the main list and the battery list read, through a new shared helper so the screens cannot
drift apart. Without it a black card on a black screen has no edges at all. And the usage-access
permission dialogs go through the fork's dialog builder, since the yellow border is painted on the
window background after `show()` and can never be inherited from a theme.

### 🔒 The device-policy powers, out of the menu

The four powers behind *More device-policy controls…* are now **toggle boxes on the policy card**,
two per row, shaped like a capability row below it: **Block uninstall**, **Block force-stop**, **Block
accessibility**, **Clear all locks**, each with a one-line explanation. They colour like the rest of
the page — **red while the hard lock is in force, yellow while it is not** — and take the thick frame
only when we put it there, which is the capability rows' *changed from default* rule applied where the
default is always "not locked". The pill and the list dialog behind it are gone; among other things,
that dialog had been hiding the way back behind two taps and a menu nobody would think to open.

**Clear all locks** keeps the box shape and its place in the grid but takes an open padlock instead of
a switch. It is an action, not a state, and a switch that sprang back to off would misdescribe it.

Two of the four cannot be read back. Force-stop blocking and accessibility blocking are
Device-Owner-only in *both* directions — their getters refuse us as surely as their setters do, and
白い熊 雫's status call answers about the device, never about one package. They are recorded on the same
terms the write-only firewall rules already use: **only a write 雫 reports as `ok`**, which it sets
from the real `DevicePolicyManager` call rather than from having received the request, so a refusal
never becomes a tick. The record is device-local, never travels in a settings export, and is cleared
when *clear all locks* succeeds. Both the boxes' own note line and the legend say plainly that the
state shown is ours rather than the platform's.

### 🔧 Mode of operation

The status card was the one surface on that page using Material 3's primary-container pair — a yellow
fill with dark-on-yellow text — so it read as an inverted block on an otherwise yellow-on-black
screen, with two of its three states barely legible on it. It is now repainted from the configurable
fork theme: black fill, yellow stroke, yellow text, and the dimmed "not needed in this mode" state a
faded version of the same yellow rather than a hue of its own.

## 4.1.0+072 — 2026-08-02

One dialog now holds every backup action for one app, instead of a chooser that hid the backups
behind a second screen.

### 💾 Every backup action in one dialog

Long-pressing the main list's backup column used to open a two-line chooser — *Backup*, and
*Restore or delete…* which then opened the backup/restore bottom sheet. So the one thing you came
there to see — **the backups themselves** — was always a screen away, and *Backup* was a bare list
line pretending to be an action.

`AppBackupDialogFragment` is both of those at once:

- The app's **backups as a tick list**, base backup pre-ticked (the same default the bottom sheet
  uses), each row naming its date, flags, version, user, encryption, compression, size and frozen
  state.
- **Restore** (enabled at exactly one ticked) and **Delete** (one or more) as pills under the list,
  plus a **⋮** pill carrying the backup freeze/unfreeze that used to live in the restore dialog's
  overflow — and only the applicable half of it, because a list of two where one is greyed out says
  less than a list of one.
- **Back up** as the dialog's own positive button, beside Cancel. It is an action pill now, not a
  list line.
- An **uninstalled app with a backup** keeps Restore and Delete and loses Back up, which would mean
  nothing for it; an **installed app with no backups** says so and offers only Back up.

It reuses `BackupRestoreDialogViewModel` — the same loader, the same `prepareForOperation`, the same
`BatchOpsService` hand-off — so backup, restore and delete behave identically to the bottom sheet,
which stays in place for batch selections and app details. *Skip backup method dialog* still
short-circuits the options picker, and the overwrite warning now keys off whether a **base** backup
actually exists rather than the sheet's app-count arithmetic.

### 🧭 Four things the dialog has to keep doing

- The positive button is wired in `onStart()` rather than at build time, so a click does **not**
  dismiss: the view model carrying the operation dies with the fragment, and dismissing before the
  service hand-off would drop the operation on the floor.
- Everything is inflated from the **yellow-on-black dialog overlay context**. The Material 3
  multi-choice row declares its tick mark as `app:drawableStartCompat`, which only the AppCompat
  inflater factory honours — rows inflated from the fragment's own context would come out with no
  tick at all.
- Row labels are formatted **off the main thread**: sizing a backup means walking its directory.
- The package is only re-processed on a fresh create; the state LiveData is sticky, so a rotation
  re-renders from what is already loaded instead of re-reading every backup.

The action row is a `FlowLayout`, not a `LinearLayout`: three pills plus the dialog's own two do not
fit one line on a narrow screen, and wrapping beats clipping.

## 4.1.0+071 — 2026-08-01

A session that started as a question — *why isn't "Access Accessibility" on the Snooping page?* —
and ended by removing a filter, adding four capabilities, and fixing three ways the page could
mislead you: a tab that never reloaded, a row that could not be moved, and a decision that had
quietly stopped holding.

### 🕵️ Accessibility access is shown for every app

`ACCESS_ACCESSIBILITY` was hidden for any app that declared no `BIND_ACCESSIBILITY_SERVICE` service.
Measurement on the Mate XT actually **supported** that rule — all ten packages that had ever
exercised the op declared such a service, and six of the seven with the op blocked declared none —
and the rule is still the wrong trade. This is the one capability that subsumes nearly every other
on the page, the op costs nothing to block, and a manifest rule that is right about today's APK
still hides the row from the app that adds the service in its next update. Being wrong here is not
symmetric, so the row is now unconditional.

### ➕ Four new capabilities

- **Install other apps** / **Uninstall other apps** (`REQUEST_INSTALL_PACKAGES`,
  `REQUEST_DELETE_PACKAGES`) — beside *See every installed app*, because changing the set of
  installed apps is the other half of the same reach. Their permissions are `normal`, so the write
  moves **the op alone** rather than attempting to revoke an install-time permission.
- **Place and receive internet (SIP) calls** (`USE_SIP`) — a second, parallel call stack with the
  same microphone and call-metadata reach as the dialler ops, covered by none of them.
- **Read the system log** (`READ_LOGS`) — the second *permission-only* row after background
  location, and it works for the same structural reason: `READ_LOGS` carries the **development**
  protection flag, which is exactly what makes it revocable. The system log carries what every other
  app printed — identifiers, URLs, sometimes contents.

### 🔁 The tabs stopped lying to each other

`AppDetailsActivity.loadTabs()` loads every tab once; paging between them reloads nothing, and a
write notifies only the row that issued it. So blocking a capability on **Snooping** left the **App
ops** tab showing the mode from when the screen opened — and that was not merely stale reading:
`setAppOpMode(item)` picks its direction from the mode the row is holding, so the next toggle there
wrote **the opposite of what its switch appeared to do**.

A state epoch in the view model, bumped by every write that changes what an app may do (snooping
toggles, block-all, app-op writes, permission toggles, revoke-all-dangerous, reset-app-ops); the
Snooping and Permissions fragments record the epoch they rendered and re-read in `onResume` **only
when it moved**, so paging around costs nothing.

### 🧊 A stored `MODE_FOREGROUND` outlives its permission

Measured on `com.elink.fittrackhealth.pro`: `ACCESS_COARSE_LOCATION` revoked and `USER_FIXED`, while
the **uid** op `COARSE_LOCATION` was still at `foreground` — the permission policy service normally
syncs such an op down to `ignore`, and here it plainly had not. Because the row reads the raw stored
mode first (without which a narrowing would be invisible), it claimed **"Only while in use" with its
switch on for an app that cannot use location at all** — and it could not be moved: asking for
*blocked* re-revoked an already-revoked permission, nothing changed, and the write was reported as
failed for ever.

A revoked permission is no access whatever mode the op carries, so the permission now wins — but
only where the app actually **declares** it, or every pre-set row on the page would be mislabelled.

### 🔴 Drift is now visible

A remembered decision that no longer matches what the platform enforces had **no UI at all**: the
switch and the status pill both report the live state, faithfully, and so hide the disagreement.
Now the box around the switch turns **red** instead of theme yellow, and the pill appends
**"you set: Blocked"**. The rule that the pill says nothing about the stored decision holds only
while the two agree; when they differ, the stored decision is the one thing the row cannot otherwise
say. Tapping the row re-applies it.

### 📖 The legend is laid out, not streamed

*What the marks mean* was a single `setMessage` string — every mark, every colour and two screens of
explanation in one grey block, which is unreadable exactly when it is needed. It is built as views
now, in the fork's kxkb language: **bold headings over a text-width yellow rule**, a full-bleed
hairline between sections, **bulleted marks whose lead-in is bold and drawn in the colour it
describes**, hanging bullets so wrapped lines align, and the **status pill first** because it is what
you look at first.

### 🎚️ Air between the switch and its box

The "remembered" box is drawn as the switch's own **background**, so the view's bounds *are* the box
— and at the old fixed 56dp the M3 track (52dp) filled them: the two outlines merged into one, and
the thicker red drift box appeared to bleed over the track. `wrap_content` plus padding fixes it, and
it has to be the padding: `SwitchCompat` ignores horizontal gravity and lays its track against the
end padding edge, so spare width on a fixed-width switch lands on one side only. The padding is
unconditional, so a row with a box and one without hold their switch in exactly the same place.

## 4.1.0+067 — 2026-08-01

Everything this page could do was **soft**. An app-op we wrote or a permission we revoked could be
put back by Settings, by another tool, and sometimes by the app itself. 白い熊 made the sister app
**白い熊 雫** (`shiroikuma.shizuku`) Device Owner, and it now delegates policy powers to this one — so
a decision made on the Snooping page can be made *hard*, and nothing outside this app can undo it.

Alongside that, two long-standing bugs surfaced on a second phone (a Motorola razr 40 ultra, Android
15) that the Mate XT had been hiding for months, and the page finally learned to stop offering
switches it cannot move.

### 🔒 Device-policy locks

- **`DevicePolicyBridge`** — the delegated powers, called as the **public SDK with a `null` admin**:
  permission hard lock (`POLICY_FIXED`), suspension, uninstall block. No hidden API, no binder relay,
  and it keeps working while 雫 is stopped, because `system_server` persists the delegation. Every
  write re-reads the platform and reports whether it actually landed; `setPackagesSuspended`'s return
  array is treated as failure when non-empty. Scopes are asked of the platform on a 5-second TTL and
  never assumed, so a delegation revoked in 雫 stops us within seconds rather than at the next launch.
- **`PolicyApiClient`** — a `ContentResolver.call` client for 雫's policy provider, carrying only what
  no `DELEGATION_*` scope can: accessibility blocking, user restrictions, always-on VPN, camera,
  user-control. A provider call rather than a broadcast because it is **synchronous** (a switch you
  just tapped needs an answer) and because `Binder.getCallingUid()` identifies us on the far side, so
  unlike the 保存復元 contract there is no token to carry. Degrades to "unavailable" when 雫 is absent.
- **A policy card pinned above the capabilities**, stating whether the powers are active and, when
  they are not, **why** — no Device Owner on this phone, versus authorised in 雫 but not for us. It
  carries the **suspend** switch (a stronger freeze than hiding: the app cannot be opened at all) and
  the way into the rest of the controls.
- **A padlock column beside each toggle** — hollow where a lock could land, filled where one does.
  Tap locks, tap again releases, no confirmation in either direction.

### 🧨 `setPermissionGrantState` takes dangerous runtime permissions and nothing else

Having *a* permission is not enough. `GET_USAGE_STATS` is backed by
`android.permission.PACKAGE_USAGE_STATS`, whose protection level on the razr reads
`signature|privileged|development|appop|retailDemo` — so a lock offered on **Read app usage** was
accepted by our UI and refused by the platform after the fact. Lockability is now resolved from the
protection level up front, and the app must actually request the permission, mirroring the rule
`SnoopingResolver#canWritePermission` already applied to the ordinary write path. App-op-only rows —
clipboard, screen capture, background running — can never be locked, and no longer pretend otherwise.

### 🚫 Rows that cannot move are gone

- A lever's `isModifiable()` was a global privilege check with no package argument, so it could not
  say "movable in general, not for this app". It gained an `isModifiable(packageInfo, userId)`
  overload, judged live on every load and never cached, so the same app on another phone keeps its
  working switch.
- **The case that surfaced it, measured 2026-08-01:** the razr ships `com.android.vending` on the doze
  **system-excidle** list. There are three power-save whitelists, and `dumpsys deviceidle whitelist`
  names them in its own output — `user`, `system`, `system-excidle`. The first two have per-package
  removals; **the except-idle list has none on any release** (`cmd deviceidle except-idle-whitelist`
  offers only `+`, `=` and `reset`, and no binder call exists), so that exemption cannot be cleared by
  us, by `adb`, or by root. "Exempt from battery optimisation" is now hidden for such an app instead
  of being offered and refused.
- The lever also got **stronger**: `enableBatteryOptimization` now falls through to
  `removeSystemPowerWhitelistApp` when the user-list removal leaves the app exempt — verified working
  on EMUI, and restoring cleanly — so ROM-whitelisted apps that were previously un-blockable now block.
  The battery panel's doze switch is withheld on a pinned app for the same reason.

### 🎨 Dynamic colour was eating the fork theme

On a **fresh install** the razr rendered white-on-green instead of yellow-on-black — the fork's entire
look, gone. `AppearanceUtils.onActivityPreCreated` pinned `AppTheme_YellowOnBlack` and then, three
lines later, called `DynamicColors.applyToActivityIfAvailable` **unconditionally**; dynamic colour
rewrites `colorPrimary` / `colorSurface` / `colorOnSurface` from the wallpaper and overwrote the
palette we had just set.

It hid for this long because that call is a **no-op wherever the platform does not offer Material
You**: on the Mate XT (EMUI) it never fired, so the theme survived and the bug only appeared on the
first phone that supports dynamic colour. It is also the clobber behind the long-standing
dialog-theming landmine — the yellow-on-black overlay has to be passed explicitly at every call site
precisely because this wiped `materialAlertDialogTheme`.

### 🖼️ Bottom sheets finally get the house border

The +55 dialog sweep could never have reached them, and for a structural reason rather than an
oversight: a bottom sheet is **not** an `AlertDialog`. It has no window background to replace, so
`applyForkDialogBorder` had nothing to act on, and its 16dp inset would have been wrong anyway for a
sheet flush with the screen edge. The frame now goes on the sheet's own container view, applied once
in `CapsuleBottomSheetDialogFragment` — the base every sheet inherits — via a new
`forkBottomSheetBorderDrawable` attr. Only the **top** corners are rounded, since rounding the bottom
curves the stroke away and leaves two notches; and the attr sits on the **activity** theme, because a
sheet resolves attributes from its host and beside the dialog attr it would have resolved to nothing.

Swept up: Backup/restore, Restore…, list options, app-usage details, the audio player, file
properties, debloater options and bloatware details.

### 🧭 The page explains itself

New: **⋮ → What the marks mean.** The card frame, the remember box, the padlock, the status pill and
what locking actually does — none of it was documented anywhere before. It opens with the three
gestures in one line, and carries the warning that used to be a confirmation dialog: a policy-fixed
permission is invisible to the app it lands on, so an app that assumes it can simply re-ask may fail
or loop rather than explain itself.

### 🟡 Smaller things

- **Foreground state takes the configurable theme yellow** rather than a hard-coded amber. The fork
  has one yellow, and it is the theme's.
- **Remember/forget left the lock menu**, where it implied a relationship to device policy that it
  does not have. It is the row's **long-press** now, shown as a **box around the switch**. Remembering
  deliberately stores a state equal to the platform default: the "only departures" rule governs writes
  made through the switch, but here it has been asked for explicitly, and pinning a default is how you
  say *keep it this way even if the default changes*.
- **The row menu is gone.** Once remember/forget moved and the state entries became redundant with
  tapping the card, it held one useful entry.
- **The main list's backup column now taps like the rest of the row** — a simple tap opens app info,
  and every backup action moved to its long-press menu, offering only what the app has. It was the one
  part of a row where the identical tap two centimetres away did something else, and it carried two
  gestures with nothing to distinguish them.
- **The build counter is zero-padded in `versionName` too.** The APK filename padded it but the
  manifest did not, so the install dialog read `4.1.0+62` while the file said `+062`. `versionCode` is
  untouched integer arithmetic, so upgrade ordering is unaffected.

## 4.1.0+55 — 2026-07-31

The yellow dialog border was never a theme. It could not be, and that is why it had been landing on
some dialogs and not others ever since it was introduced — including on the backup **delete
confirmation**, which is where 白い熊 noticed the gap. This build closes it everywhere and records
the reason, so the next dialog added to a fork screen cannot quietly become the odd one out.

### 🖼️ Why a border cannot be inherited

- `MaterialAlertDialogBuilder.create()` rebuilds the dialog's window background as its own
  stroke-less `MaterialShapeDrawable`. Anything a theme puts there is overwritten on the way out of
  `create()`, so the frame can only ever be painted **after** the dialog has been built — that is
  what `UiUtils.applyForkDialogBorder(dialog)` exists to do, driven by the `forkDialogBorderDrawable`
  theme attribute and a no-op wherever that attribute is unset.
- Build +47 wired that call into the six shared libcore builders (`Searchable*`, `TextInput*`,
  `Scrollable`) and into `MainActivity`, and stopped there. Every dialog still raised with a plain
  `new MaterialAlertDialogBuilder(ctx)….show()` therefore kept the yellow-on-black **palette** —
  that part *is* inherited, from the host activity's dialog overlay — while having no **frame**.
- The result was an asymmetry with no visible logic to it: a search dialog and a text-input dialog
  were framed, the confirmation dialog three taps later was not, on the same screen and in the same
  flow.

### 🧷 One entry point, and one that works before `show()`

- `utils/ForkDialog` gains **`bordered(dialog)`**, for the call sites that do not own the `show()`:
  `DialogFragment.onCreateDialog`, which returns the dialog for the fragment to show later, and
  dialogs whose buttons are re-wired after `create()`. Applying the border straight after `create()`
  is sufficient — M3 rebuilds that background inside `create()` only, never again in `show()`.
- Together with the existing `builder(ctx)` (yellow-on-black overlay pinned explicitly, so it does
  not depend on the host theme surviving) and `present(builder)` (create → show → border), that is
  the whole surface a fork dialog needs.

### ✅ 36 dialogs swept, across 25 files

Every dialog reachable from a yellow-on-black screen now goes through `ForkDialog`:

- **Backup / restore** — delete backup (the reported one), delete base backup, the
  overwrite-existing-backup warning, restore-multiple confirmation.
- **App details** — status, battery optimisation (twice), sensors, overlay, freeze, clear data,
  usage access, block/unblock trackers, launch-activity, icon picker, SSAID.
- **Main list** — uninstall-again.
- **Filters** — edit filters, edit filter option.
- **Profiles** — delete (twice), exit confirmation, new profile, remove-from-profile,
  backup/restore.
- **Settings** — import backups, SAF notice, no volumes found, remove all rules, installer app,
  import existing rules, saved APK name format, keystore import (twice), key import.
- **白い熊 応用管理 UI page** — automation token regenerate, font family, font weight, font size,
  colour picker.

### 🔀 Two dialogs kept their host's theme on purpose

- The **ADB** dialogs in `Ops` (manual wireless debugging, pairing) can also appear over the
  installer, which is not a fork screen, so they keep their plain builder and gain only the
  `present()` wrapper. They now behave exactly like the `TextInputDialogBuilder` step standing
  beside them in the same flow: framed on a fork host, untouched anywhere else.
- Left alone deliberately: `SettingsExportImportPanel` and `DirectoryChooserDialog`, which already
  paint their own border, and every non-fork screen — file manager, logcat, scanner, editor,
  debloater, one-click ops, installer, operation history — where the border call is a no-op anyway,
  the theme attribute being unset there.

### 📝 Recorded

- `CLAUDE.md`'s dialog landmine now names `ForkDialog`, states why the libcore builders were framed
  while direct ones were not, and says which of the three helpers to use for which kind of call
  site.

## 4.1.0+54 — 2026-07-30

+49 shipped Shizuku mode; this build makes it work. Three separate defects stood between the app and
a running Shizuku server, and each one hid the next: a fresh install never looked for Shizuku at all,
the binder the server pushed was thrown away on arrival, and the attach that follows asked the server
the wrong question — which is why 応用管理 never appeared in Shizuku's app list, and why there was
nothing there to authorise. All three were measured on-device against genuine Shizuku 13.6.0.

### 🎯 `attachApplication` is wire code 18, not 17

- An AIDL wire code is `IBinder.FIRST_CALL_TRANSACTION + <declared id>`, and `FIRST_CALL_TRANSACTION`
  is **1**. `IShizukuService.aidl` declares `attachApplication … = 17`, so it answers on **18** —
  while **17** belongs to `shouldShowRequestPermissionRationale() = 16`, whose first act is
  `requireClient()`. The client transacted 17, i.e. asked the server *"may I show a rationale"* while
  trying to become a client, and was answered **`IllegalStateException: Not an attached client`** — as
  any modern client would be, not just this app.
- **The failed attach was not the damage; the fallback was.** The pre-v11 attach on code 14 then
  succeeds, so the app attached as a *legacy* client, `isLegacyAttach()` went true, and the mode
  deliberately skipped a permission prompt it believed could never appear. `requestPermission()` was
  therefore never called, the app never appeared in Shizuku's list, and so it could not be authorised
  from there either — a closed loop with no way in.
- The code is now spelled `FIRST_CALL_TRANSACTION + 17` rather than a bare `18`, because the bare
  number is precisely what got it wrong. The constant the AIDL generates is package-private in
  `moe.shizuku.server` and cannot be referenced from the client, so spelling out the arithmetic is
  the next best thing.
- **This supersedes +49's reading of the same symptom.** That build was right that a *thrown* attach
  must not short-circuit the fallback, but it took the exception at face value and concluded the
  server was refusing the transaction. The server was answering a different method, exactly as asked.

### 🧩 The binder was arriving and being thrown away

- Shizuku mode reported **"the Shizuku server is not running"** while the server was demonstrably
  calling our provider on every launch. The real error underneath:
  `ClassCastException: moe.shizuku.api.BinderContainer cannot be cast to rikka.shizuku.BinderContainer`.
- **A server does not put every key into one bundle.** It makes a *separate* `call()` per client API,
  each carrying one key whose value is a container **of the matching type**. The provider read the
  legacy key into a variable declared as the modern container, so the implicit checkcast threw on
  exactly the servers that key exists for — and the `catch` then blamed a missing container class,
  when all three were present and only the types were crossed.
- The value is now read as a `Parcelable` and the binder pulled out of whichever container arrived,
  with a reflective fallback over any `IBinder`-typed field for a fourth flavour nobody has written
  yet. The key that was never read at all — `af.shizuku.plus.api.intent.extra.BINDER` — is read now
  too, so 白い熊 雫's own call is heard and not merely its `rikka` one.

### 🔎 Shizuku is what a fresh install finds

- **Auto-detection hunts for a server instead of glancing at one.** It gated on "a server is pinging
  *and* has already authorised us", and on a fresh install neither half can be true: the binder push
  is asynchronous, so it has usually not landed when the splash worker runs, and nothing has
  authorised a just-installed app. So auto-detection walked straight into ADB — the mode this fork
  added Shizuku to get away from. It now awaits the binder and requests authorisation, on shorter
  timeouts than the explicit mode (3 s for the binder, 60 s for the answer) so an installed-but-
  stopped manager cannot stall the splash. This reverses +49's rule that auto mode must never raise a
  prompt of its own: asking once at first start is the whole point, and a refusal is remembered by
  the server rather than re-asked.
- **Falling back to ADB no longer slams the door.** A stored mode is never auto-detected again, and a
  Shizuku server is started *by hand* — usually after the phone has booted and the app has already
  been opened once — so the first launch is the one most likely to miss it, and that miss used to be
  permanent. The preference now stays on `auto` when a manager is installed but did not serve us, and
  the next launch looks again.
- **A one-shot rewind** hands an install already locked into `adb_tcp` by the old policy back to
  `auto`, exactly once per install. It runs only from the app-start path, never from the settings
  picker, so an ADB mode chosen by hand is never second-guessed; `root` and `no-root` are left alone.
- **A frozen manager is no longer invisible.** `getPackageInfo(pkg, 0)` answers *NameNotFound* for a
  frozen or disabled package — and freezing apps is what this app is for — while a Shizuku server is
  a shell process that outlives its manager being frozen. The lookup now passes
  `MATCH_UNINSTALLED_PACKAGES | MATCH_DISABLED_COMPONENTS`, and still requires `FLAG_INSTALLED`, so a
  package uninstalled with its data kept — a ghost that can never serve us — is not waited on at
  every launch.
- **A live binder wins outright**, with no package lookup at all: if a server is already talking to
  us then a server exists, whatever the manager's install state, or its package id.

### 🔦 Diagnostics that survive EMUI

- The attach diagnostics log at **error** level now, rather than warning/info. EMUI on the Mate XT
  keeps only error-level lines from third-party apps in the main buffer, so the one line that states
  *why* a server refused us was invisible on the only device this fork ships to — and three builds
  were spent inferring what it states outright. A clean v13 attach still logs at info; anything else
  is abnormal and costs the whole mode, so it is an error.

## 4.1.0+49 — 2026-07-30

A new **mode of operation: Shizuku**. ADB-over-TCP was never the privilege — it is only the delivery
van for a single `app_process` command line, after which every privileged call in the app runs
through the same `IAMService` binder. Shizuku performs that launch itself, so the capabilities are
identical (uid 2000, same app-ops behaviour, same firewall access) while `adbd`, the port scan, the
pairing keys and the `INTERNET` dependency all leave the privilege path. Plus the two splash-screen
backgrounds that were grey, and a toast that claimed the wrong mode.

### 🔌 Shizuku mode of operation

- **`MODE_SHIZUKU`**, third in the mode picker after Root, and greyed out when no Shizuku-family app
  is installed — an option that could only ever fail is not an option. It reappears by itself once
  one is installed.
- **Prefers our own fork, 白い熊 雫 (`shiroikuma.shizuku`), over stock Shizuku**
  (`moe.shizuku.privileged.api`). The binder is *pushed* rather than dialled — whichever server is
  running resolves us by provider authority — so the preference governs the places where a choice
  genuinely exists: what the status line names, and which manager is opened when authorisation is
  needed.
- **Two user services, no new privilege machinery.** `ShizukuAMService` and
  `ShizukuFileSystemService` are the entry points Shizuku instantiates; the privileged half of the
  app needed no change at all, because it never depended on how its process was born. Both answer
  Shizuku's `destroy` transaction by exiting, so an unbind cannot leave an orphaned shell-uid process
  behind.
- **Picking Shizuku shuts the ADB path down** — services bound the old way are dropped and the local
  server is closed. Leaving a TCP listener up is precisely what this mode exists to avoid.
- **Auto mode tries Shizuku ahead of ADB**, but strictly non-interactively: it may use an
  authorisation already granted, and never raises a prompt of its own.
- **Three distinct failures, not one.** *Not installed*, *not running* and *not authorised* send you
  to three different places, so the message names the right one instead of a generic "unavailable".
- **The manifest declares both API permission names.** A Shizuku server decides which packages are
  its clients by scanning `requestedPermissions`, and the two servers look for different names; a
  `uses-permission` naming an undefined permission is inert but still appears in that list, so
  declaring both makes one build work against either server.

### 🧨 Two landmines, both measured on-device

- **`Bundle.getParcelable()` unparcels the whole bundle, not just the key you ask for.** The server
  writes three different `BinderContainer` classes into one Bundle, so reading our key needs all
  three classes present or the read throws and the hand-off fails whole. Vendoring only one of them
  made the mode report *"the server is not running"* while the server was demonstrably calling our
  provider. All three ship now — and none of them is referenced anywhere in code, so none may be
  deleted as unused: the reference is a string inside a parcel.
- **A thrown attach is not a failed attach.** The client library chains its v13 and v11
  `attachApplication` attempts with `&&`, which only falls back when the first returns `false` — but
  a server that does not dispatch that transaction answers with an exception instead. Each attempt is
  now guarded separately, and the app records which one succeeded so it can skip a permission prompt
  that such a server can never raise.

### 🖤 The splash is black now, both halves of it

- **The zoom-in splash** used `?attr/colorSurface` for its background. The system draws that window
  from the manifest theme **before the Activity exists**, so the attribute resolved against the
  splash library's own light parent and the black-and-yellow icon zoomed in on **grey**. It is a
  literal black now.
- **The static screen behind it** was grey for a different reason: `activity_authentication` carries
  no background of its own, so it inherited the app-wide theme's window background, which
  `DynamicColors` then tinted. The post-splash theme is now a splash-scoped variant pinning
  `android:windowBackground` to literal black — a drawable attribute, which is exactly what survives
  the dynamic-colour overlay.

### 🔤 The mode toast tells the truth

- uid 2000 is reached by two different routes, so **"Working on ADB mode" after a Shizuku hand-off
  was simply false** — and false in a way that matters here, since no `adbd`, TCP port or pairing key
  is involved. It now reads **"Working on Shizuku mode"**, and the two neighbouring warnings that
  said *"instead of ADB mode"* were corrected the same way.

## 4.1.0+43 — 2026-07-29

Two contract changes from 白い熊 自由作業盤's backup-automation hand-off, in one build. Nothing else
moves: no new categories, no renamed ids, no change to `EXPORT_STATE` or to the token and reply
machinery, and an absent `items` extra still means the default set.

### 🗂️ `LIST_CATEGORIES` states the picker default

- **`Category` carries a `defaultSelected` flag**, added with a constructor overload that defaults it
  to `true`, so no existing entry had to change.
- **Every category here stays `on`.** The rule is for things that are large, derived *and*
  re-creatable — downloaded map tiles, a regenerable thumbnail cache — and this app exports none of
  those. Sending the field is still the point: the app *states* its default rather than the picker
  assuming one, and any category added later inherits a field that already exists.
- **The reply now emits all four positional fields**, `id⇥label⇥parent⇥on|off`. The third is empty —
  this category list is flat — but it is still sent, because the fields are positional and omitting
  it would have `on` read as the parent.
- **The in-app Export/Import sheet seeds its checkboxes from the same flag**, so the sheet and
  自由作業盤's picker cannot disagree about what starts ticked.

### ⏹️ `CANCEL_EXPORT` — a running export can be stopped

- Declared on the **exported receiver**, not on a service: the service that does the work is
  `exported="false"` and a third-party app could never reach it.
- Gated by the same token as the other two actions, and it **answers nothing** — fire-and-forget. The
  running export sends **`ERROR:cancelled`** for its *own* request through the normal reply channel,
  guarded by the existing `AtomicBoolean` so it can never double-fire with a success.
- **Cancellation is cooperative**: a flag the write loop checks between entries, so the run unwinds at
  the next boundary. No thread interrupt, no `System.exit`, nothing killed mid-`write()`.
- **The export writes to `<final-name>.part` and renames only on success.** A cancelled or failed run
  therefore leaves the backup directory exactly as it found it — no short archive that looks
  complete, no stray partial. Previously it wrote straight to the final name.
- **Safe to send at any time.** The flag is cleared as each run starts, so a cancel arriving when
  nothing is running, or after a run has already finished, is a silent no-op — not an error, not a
  reply, not a crash — and cannot stop the next export.

There is no foreground service or wakelock on this path to unwind (it is `goAsync()` plus a
background thread), and the in-app panel has no export stop button to route through the same code.

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
