# Third-party backup/restore hand-off

**You have been pointed here because 白い熊 wants a closed-source third-party app — no
source, just an APK — to become something 白い熊 応用管理 (`shiroikuma.oyokanri`) can back
up and restore properly, so that the app's state survives a move to a clean phone.**

This document is written for a session that has never seen the conversation that produced
it. Read it end to end before touching anything: several of the steps only make sense
together, and two of them (§4 and §6) are where a port that looks finished silently
destroys data.

The goal is **safe transfer to a clean device**. Not a file copy — a restore that a person
can trust, where the app comes up on the new phone with the progress, profile and settings
it had on the old one, and where a restore that cannot do that says so instead of
half-doing it.

## What you are implementing

You are adding 白い熊's **automation contract v2** "data door" to an app whose source you do
not have: an exported `ContentProvider` that 応用管理 calls to ask the app to export its own
state into a file descriptor, and later to import it back.

The point of the door is that the app writes its own data. 応用管理 can already copy an
app's private directories wholesale, and for many apps that is enough. It is not enough
when the state is a format only the app understands, when it lives outside the private
directories, or when a blind copy would carry gigabytes of re-creatable content along with
the 50 KB that actually matters. The door lets the app answer "here is my state" and "put
this state back", and it is the only mechanism that gives a clean phone a correct result.

## Read these two first

**The wire protocol is specified in `~/git/shiroikuma-jiyusagyoban`** — 白い熊 自由作業盤 is the
reference implementation every app in this family mirrors, and its hand-off document is the
normative spec for the contract: methods, extras, reply shape, job ids, category listing,
progress. **Do not re-derive the protocol from this document and do not duplicate it.** What
you will find below is only what a *third-party, no-source* port needs on top of that spec.

**The complete worked example is `~/git/shiroikuma-trials-frontier`** — a modded Trials
Frontier (`com.ubisoft.redlynx.trialsfrontier.ggp`), confirmed working by 白い熊. When a
question below says "see the worked example", these are the files:

| File | What it shows |
| --- | --- |
| `provider/src/shiroikuma/tfrestore/AutomationProvider.java` | `call()` with `describe`/`export`/`import`/`cancel` |
| `provider/src/shiroikuma/tfrestore/AutomationCallers.java` | the caller gate, Java port of 自由作業盤's Kotlin one |
| `provider/src/shiroikuma/tfrestore/AutomationDataService.java` | the `dataSync` foreground service |
| `provider/src/shiroikuma/tfrestore/PlayerSave.java` | an app-specific codec with integrity checking |
| `provider/src/shiroikuma/tfrestore/PrepareActivity.java` | the launcher trampoline (§5) |
| `patches/apk.py` | every manifest edit, asserted before applying |
| `build.sh` | originals → patched, signed APK in one command |
| `CLAUDE.md` | every finding, including the offline/native-patch technique |

## Phase 0 — ask 白い熊 what to back up (do this before you write the door)

**This is a question you must ask, not answer.** You cannot know which of an app's files are
worth carrying, and guessing wrong is either a useless backup or a dangerous one.

Inventory first, then ask. The inventory:

- `files/` — usually where the state is.
- `shared_prefs/` — settings, and often the pointers that say where everything else lives.
- `databases/` — SQLite. Include `-wal` and `-shm`, **or** checkpoint the database before
  reading it. A `.db` copied without its WAL is a database missing its most recent writes.
- `no_backup/` — usually exclude. It is where an app puts what it does not want carried,
  and that is frequently a first-run marker whose absence is what triggers a rebuild.
- `app_*` directories — app-specific; look before deciding.
- `Android/data/<pkg>` — 応用管理 backs external data up itself, as its own part. You do
  not need the door for it, and usually should not duplicate it.

Then split what you found into two piles and present them:

- **State** — progress, profile, settings, accounts, purchases, anything a person would be
  upset to lose and that nothing else can reconstruct.
- **Re-creatable content** — caches, downloaded assets, ad and analytics SDK caches, and
  anything the APK itself or the network can rebuild.

Give 白い熊 the split and ask which categories to include. Two rules while you do:

- **Category ids are the wire format.** They appear in 自由作業盤's saved selections and in
  応用管理's `items` extra. Pick them once; they can never be renamed.
- **If the app holds secrets — login tokens, session cookies, saved passwords — ask
  explicitly whether they should travel.** Do not decide this silently in either direction.
  An archive is a file on shared storage.

**Recommendation to put to 白い熊: leave the OBB and other bulk content out when the APK can
restore it.** Trials Frontier's APK carries its own 67 MB OBB plus ~200 MB of track data and
rebuilds both on first run, so backing them up spent 67 MB per archive to reproduce a file
that reconstructs itself for free. One caveat when you offer this: in 応用管理 the OBB and
`Android/media` are a **single** backup part, so unticking it drops both — check whether the
app keeps real state under `Android/media` before recommending it.

## Phase 1 — set up the repo before you change anything

Create `~/git/shiroikuma-<app>` and put the **original, unmodified APK(s) in it**.

- **Never modify an original and never delete one.** Every later build is derived from
  these; if they are gone, nothing is reproducible.
- **`build.sh` must reproduce the patched APK from the originals in one command** —
  `apktool d` → scripted patches → your own dex → `apktool b` → `zipalign` → `apksigner`.
  A port that only exists as a decompiled tree someone edited by hand is not maintainable
  and will not survive the app's next version.
- **Patch scripts assert before they patch.** Check the original bytes, the original smali
  instruction, the original manifest attribute — then change it. A patch that silently
  matches nothing is how a build ships with a feature missing and no error.
- **`CLAUDE.md` records every finding** as you make it: what the format is, which class does
  what, which check had to be defeated, what you measured on the phone. The next session
  starts from that file.
- **Builds go to `~/tmp/<slug>_<version>+NNN.apk`, counter zero-padded to three digits,
  never reusing a number and never overwriting an older build.** 白い熊's rule across every
  repo: an old build is a record of what shipped and the only way back to it.
- **Check that a `--no-bump` rebuild reproduces the shipped APK** before you believe the
  build is reproducible.

## Phase 2 — signing

The patched APK must be re-signed, and that has consequences to state **before the first
install**, not after:

- **Store updates stop working.** The app is no longer the store's app.
- **A key change forces a one-time uninstall**, which destroys the app's data. Tell 白い熊
  this before the first install, so the choice of when to lose the existing state is theirs.
- **After that, always sign with one key**, so every later build installs over the previous
  one and keeps the data. Trials Frontier uses `~/.android/debug.keystore`.

**Look for in-app signature, licence and integrity checks before assuming the app will run
re-signed.** Trials Frontier already carried an MT-Manager signature killer, which is why
it started at all. Another app may need one, or may refuse to run until the check is
patched out. Find this out early — it decides whether the port is possible.

One consequence on 応用管理's side, which matters for Phase 10: when 応用管理 restores an APK it
compares the **installed** app's signing certificate against the one recorded in the
archive, and on a mismatch it refuses the restore outright — unless "skip signature check"
is on, in which case it **uninstalls the app first** and loses the data. So the one-key rule
is not merely a convenience: a second key turns every future restore into either a refusal
or a wipe.

**A defect in that path was found and fixed on 2026-09-27 (応用管理 build +049) — if you are
reading this against an older 応用管理, know what it did.** The signature-mismatch branch in
`RestoreOp.restoreApkFiles` tested the uninstall's return value with the wrong polarity:
`PackageInstallerCompat.uninstall` returns `true` on success, and the call site threw
`An uninstallation was necessary but couldn't perform it` when it got `true`. So a mismatch
restore with skip-signature-check enabled **uninstalled the app with its data and then
abandoned the restore**, leaving nothing to go back to, while an uninstall that had genuinely
failed was the case allowed to proceed. Fixed at +049. Either way the rule for you is
unchanged: never let the key change, and never treat skip-signature-check as an escape hatch
that will rebuild the app for you.

## Phase 3 — add code without touching the app's code

Write the door in Java, compile it with `javac --release 8` then `d8`, and add the result as
an **extra `classesN.dex`** zipped into the `apktool` output. This is the whole trick: you
add classes without rewriting a line of the app's smali, so the app's own code stays byte
-identical and the next version of the app costs you only the manifest patch.

Classes (see the worked example for each):

- **`AutomationProvider`** — exported, authority `<pkg>.automation`, `call()` dispatching
  `describe` / `export` / `import` / `cancel`.
- **`AutomationCallers`** — the gate. A Java port of 自由作業盤's `AutomationCallers.kt`:
  exact package name **and** the uid the kernel reports **and** a pinned signing
  certificate, all three. **Re-derive every pin with `apksigner verify --print-certs`
  against the installed caller APK. Never copy a hash out of a message or a document,
  including this one.** The pin matters most in exactly the case this contract exists for —
  a clean phone, where an allowlisted package may not be installed yet and its name is a
  name anyone can take.
- **`AutomationDataService`** — a `dataSync` foreground service. **`startForeground` first,
  then** take the file descriptor out of the hand-over map.
- **A small codec class** for any app-specific format, with an integrity check (see §8).

Manifest additions:

- **The three metadata, as plain integers**: `android:value="2"`, `"1"`, `"1"` for
  `shiroikuma.automation.contract`, `.format` and `.min_format`. 応用管理 reads them with
  `Bundle.getInt`, so a string value reads back as `0` and the door looks absent. **The
  contract value must be exactly `2`** — 応用管理 accepts `contract == 2`, not `>= 2`.
- **The provider**, exported, and **the service** with `foregroundServiceType="dataSync"`.
- **The `FOREGROUND_SERVICE` permission.**
- **`<queries>` for `shiroikuma.oyokanri` and `shiroikuma.jiyusagyoban`.** Mandatory at
  `targetSdk >= 30`. Without it the certificate lookup in your own gate fails and the reply
  broadcast's `setPackage` fails **silently** — the transfer then dies of a timeout with the
  work already done on disk, which reads as a broken door.

**Verify all of it in the binary manifest, not in the source you wrote**: `aapt dump
xmltree` on the built APK, checking that each metadata is type `0x10` (integer), that the
provider and service carry the exported flags you intended, and that there is exactly one
launcher activity (§5 moves it — make sure it did not leave two).

## Phase 4 — targetSdk, and what to do when it is 31 or higher

**Never raise the app's `targetSdk` as part of this work.** It changes the app's own
behaviour in ways you cannot test.

A background-started `dataSync` foreground service is straightforwardly legal at
**`targetSdk <= 30`**: the API-31 background-FGS-start restriction applies only to apps
targeting S and above. Trials Frontier targets 30, which is why its service pattern is
simple. If the app you are porting targets 30 or below, use the worked example as-is.

**At `targetSdk >= 31` you have two routes.** Prefer the second unless the payload is large.

1. **The battery-allowlist route.** Before every transfer, 応用管理's `AppDataTransfer.
   preflight` thaws the app, unsuspends it, and **puts it on the battery allowlist**
   (`DeviceIdleManagerCompat.disableBatteryOptimization`), undoing all three afterwards. An
   app that is ignoring battery optimisations is exempt from the background-FGS-start
   restriction, so the service start should be legal for the duration of the transfer.
   **This is reasoning from the mechanism, not something measured** — if you take this
   route, verify it on the phone before believing it, and watch for
   `ForegroundServiceStartNotAllowedException`.
2. **Do the work inside `call()` and do not start a service at all.** While the binder
   transaction is outstanding the calling process's priority is lent to yours, so the
   process will not be killed under you. 応用管理 supports this explicitly: it holds a
   terminal reply that arrives **before `call()` returns** and claims it once the job id is
   known, precisely so that a fast transfer is not penalised. This is the reliable route
   for a small payload — which, if Phase 0 went well, is what you have.

Whichever you choose, §5 still applies.

## Phase 5 — the provider's `onCreate` must stay light

**On a clean phone, 応用管理's `import` call is what first starts the app's process.** Android
gives a starting process's providers roughly 10 seconds to publish; miss that and the
process is killed. 応用管理 then reports `no answer from <pkg> (frozen, or no door)`, which
is indistinguishable from the app having no door at all — the most misleading failure this
contract can produce.

So `AutomationProvider.onCreate` must do nothing but return. Any heavy first-run work goes
into a **launcher trampoline activity**: move the `MAIN`/`LAUNCHER` intent-filter from the
app's own launcher to yours, show a "preparing" screen, do the work on a background thread,
then start the app's original launcher and finish. Trials Frontier unpacks ~270 MB of
bundled content this way (`PrepareActivity.java`).

## Phase 6 — the disjointness rule

**This is the one that silently destroys a restore, and it is created by Phase 5.**

Moving first-run work out of the provider means that on a restored phone it runs **after**
the import: 応用管理 installs the APK, calls `import`, force-stops the app — and then the
person opens the app and the first-run work runs for the first time. If anything it writes
overlaps what the import wrote, the restore is undone at first launch, and it will look as
though the door never worked.

So: **what first-run work writes must be disjoint from what export/import carries.**

Trials Frontier satisfies this — its unpack writes 39 `files/Trials*.dat` plus four named
files and the OBB, its own marker lives in `no_backup/`, and the export excludes exactly
those name patterns. But note *how* it satisfies it: **two separately maintained lists that
happen to agree.** That is the durable rule to record in your `CLAUDE.md`, where whoever
edits the bundled content will see it:

> Anything added to the first-run payload must either match an existing export exclusion
> pattern or gain one **in the same edit**.

## Phase 7 — import safety

An import overwrites a person's only copy of their state. Behave accordingly.

- **Validate the whole archive before touching disk.** Allowed path prefixes only; reject
  any entry containing `..`; require a header with a format version; and check whatever
  app-specific integrity exists. Trials Frontier decodes `player.bin` and verifies its
  checksum before anything is written.
- **Stage every file as a temp file, `fsync` it, then rename them all**, and **write the
  most important file last**. If anything fails, reply `ERROR:` having changed nothing. A
  half-written state file is worse than a failed restore, because a failed restore can be
  retried.
- **Use `commit()`, never `apply()`.** 応用管理 force-stops the app the instant the reply
  lands — a `SIGKILL`, which an `apply()` in flight does not survive. A restore that reports
  success and restored nothing is the one failure worse than an honest error.
- **Refuse to *export* a state file that fails its own integrity check.** An archive of a
  corrupt save is worse than no archive: it looks like a backup.

## Phase 8 — replies

The normative details are in 自由作業盤's spec. The parts that this family has learnt the
hard way, and that a port gets wrong:

- **Exactly one terminal reply per request**, guarded by an `AtomicBoolean`.
- **A fresh `sendBroadcast`** with `setPackage(reply_package)` **and**
  `FLAG_INCLUDE_STOPPED_PACKAGES`. Without the flag a backgrounded caller never hears you.
- **Never a `ResultReceiver`, `PendingIntent` or `Messenger`.** EMUI may drop a broadcast
  carrying a live Binder outright. If you also set an ordered-broadcast result, it must
  never be the only reply — EMUI severs that channel between third-party apps.
- **The callee mints the job id.** Answer `call()` with `OK:<your own job id>` and broadcast
  completion carrying **that** id. An app that returns one id and broadcasts another fails
  in the most confusing possible way: the reply arrives, fails correlation, and the transfer
  dies of silence with the work already complete on disk.
- **A progress label goes in the `text` extra, never `result`.** `result` carries the
  terminal reply and nothing else. Real counts only — never a percentage.
- **`location` on the terminal reply** (its own extra, beside `result`, never inside it)
  names the absolute path you wrote to or read from. Set it on failure as well as success.
  応用管理 reads it and prints `Written to: <path>` in the restore log. It exists because a
  sister app reported success three times over while writing to a directory that was not
  its data at all, and nothing in the log named a path. Optional, and worth it whenever the
  app's location is in any way configurable.

## Phase 9 — offline and network blocking (only if the app needs it)

Skip this unless the app is dead, server-gone, or must run offline — Trials Frontier is.

Removing the `INTERNET` permission is a valid blanket block. Watch for gameplay gates that
ask "is the network up?" and fail closed; those may need targeted native patches. **The
technique — disassembly tools, xref hunting, language-key scanning — is documented in
`~/git/shiroikuma-trials-frontier/CLAUDE.md`.** Do not reinvent it.

## Phase 10 — the test checklist to walk 白い熊 through

Do not declare the port finished on any of these alone.

1. The new build **installs over the previous build** with progress kept.
2. 応用管理 **lists the app** and offers app data for it.
3. A backup's log shows **`OK`** and, if you implemented it, **`Written to`**.
4. A restore **onto a clean install** — or after an uninstall — gives back the same state.
5. **The values survive the first launch after a restore** (Phase 6). Note a couple of
   specific numbers before opening the app, and check them after.
6. A **corrupted archive** gives `ERROR:` and changes nothing.

## What 応用管理 requires of you — and what it does not

Authoritative, from this repository's own code:

- **Nothing needs registering with 応用管理, and no chat needs to tell it about the new app.**
  There is no allowlist, no package-name prefix rule and no signing-key requirement on the
  calling side: `AppDataClient` and `AppDataTransfer` read the three manifest metadata and
  nothing else. Declare them correctly and the app is offered.
- **A non-`shiroikuma.*` package is listed like any other.** The 仲間 lens reads
  `AppDataContract.fromMetaData` off the package manager with no name filter, and reads it
  without waking the app — so a frozen app still appears.
- **All three metadata must be present and non-negative**, and `contract` must be exactly
  `2`. Missing any one of them is the documented **opt-out**, not an error: the app is
  simply not offered.
- **The traffic is one-way.** Your app never calls 応用管理. 応用管理's own provider has a
  pinned caller allowlist that a third-party app is not on and does not need to be.
- **Restore order**, from `RestoreOp.runRestore`: verify master key → **APK** → **data**
  (internal, external, `Android/obb`, `Android/media`, each gated on its own part) →
  KeyStore → permissions, app ops and rules → **your `import`** → blocking rules. So the
  APK and the OBB are already in place when your door is called.
- **The app is never launched before `import`.** It is force-stopped immediately before the
  call — the call is what starts the process — and force-stopped again after `OK`. The
  header's `requires_launch_first` is parsed and **deliberately ignored**, so never rely on
  having been launched.
- **Timeouts.** 応用管理 puts none around `call()` itself; the binding constraint is the
  platform's ~10 s provider-publish window (Phase 5). Once the call has returned you have
  **10 minutes** of silence before the watchdog gives up, extended further by a CPU-liveness
  probe, so a genuinely busy process is not killed.
- **A re-signed third-party APK is just an APK to 応用管理** — but see the end of Phase 2:
  the archive records the signing certificate, and a later key change turns every restore
  into a refusal or a wipe. As of build +047 the restore log also names version changes, so
  a downgrade back to an archived build is visible rather than silent.

## When you are done

Report to 白い熊: where the repo is, which build number landed in `~/tmp`, what you asked
about in Phase 0 and what was decided, and which of the Phase 10 checks you have actually
run versus which still need the phone. Record every finding in the app repo's own
`CLAUDE.md` as you go, not at the end.
