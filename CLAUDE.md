# CLAUDE.md — 白い熊 応用管理 (`shiroikuma.oyokanri`)

This file is loaded automatically by Claude Code. It captures the fork's facts,
build pipeline, and operating conventions so a fresh session can pick up
exactly where the previous one left off. The full per-commit changelog and
engineering lessons live in **`.claude/skills/appmanager-fork/SKILL.md`** —
consult that for the "why" behind anything in here.

## Project

- Fork of [AppManager](https://github.com/MuntashirAkon/AppManager) (upstream base **4.1.1**, minSdk 21, AGP 8.13.2).
- `applicationId`: `shiroikuma.oyokanri` (installs side-by-side with the official build; renamed from the former `shiroikuma.appmanager`, so it is a fresh install rather than an update).
- Java package (unchanged from upstream): `io.github.muntashirakon.AppManager`.
- Display label: 白い熊 応用管理.
- Remote: `origin` → ShiroiKuma0/shiroikuma-oyokanri (formerly ShiroiKuma0/appmanager); `upstream` → MuntashirAkon/AppManager.
- Branch: **`custom`** — all fork work lives here; pushes go to `origin/custom`. `master` mirrors upstream.
- **Upstream tracking: `git`** (白い熊, 2026-08-06) — `custom` is rebased onto upstream **master commits**, not onto release tags, so the fork versionName pins the upstream base: `<upstream>+<base commit date>.<HH-MM>.g<sha8>+<BUILD_NUMBER, 3 digits>`, e.g. `4.1.0+2026-06-29.21-57.gfc1e7007+089` (the `HH-MM` and the `+` grouping arrived 2026-08-12; this repo was already rendering the timestamp in UTC). See the global **`git-versioning`** skill for the rationale and the exact format, and the versioning rules under *Build & deploy pipeline* below for what this repo must NOT do with the counter.

## Target device & environment

- Huawei Mate XT (tri-fold, Android 13, non-rooted, all-files access).
- ABI shipped: `arm64-v8a` only.
- Dev machine: Tuxedo OS Prague (TZ **Europe/Prague**).
- JDK: `/usr/lib/jvm/java-21-openjdk-amd64` (Japanese locale, so `javac` prints `ノート:` instead of `Note:`).
- Android SDK: `~/android-sdk`. Each build writes `local.properties` with `sdk.dir=$ANDROID_HOME`.
- Keystore: `~/.android-keystores/appmanager-custom.jks`
  - alias: `appmanager`
  - keystore + key password: `appmanager123`

## Build & deploy pipeline

**Always build after changes.** Whenever you finish a set of working-tree edits (Java/XML/resources), run the full pipeline below to completion — bump, assemble, sign, verify, copy the signed APK to `~/tmp/` — without waiting for the user to say "Build". Treat a task as unfinished until it has produced a fresh signed APK on disk. If the build fails, stop and surface the error rather than reporting the change as done.

**Deliver automatically after every build — never ask.** Standing instruction from 白い熊 (2026-08-06), replacing the former "always ask via `AskUserQuestion`" rule: follow the global **`/after-build`** skill and its delivery chain, and do not put a question between the build and the delivery. After a successful build, walk the chain yourself — `adb devices` (**unsandboxed**, per `/adb-check`) → `adb connect 192.168.1.87:5555` → `adb connect skhw:15555` → `scp` to `skhw:~/tmp/` — and deliver **exactly once**, to the first target that answers. Those are all the **same machine**, so never deliver by two paths. Never ask "how should I deliver?" and never ask "is the phone connected?" — the chain answers that itself. Any wireless adb session opened for the batch gets `adb disconnect`ed at the end of it (check `ps` for a live `scrcpy` first). Then say plainly what landed and where.

Output APK is named `shiroikuma-oyokanri_$(tools/fork-version.sh)_arm64-v8a.apk`, e.g. `shiroikuma-oyokanri_4.1.0.2026-06-29.gfc1e7007+089_arm64-v8a.apk`. Always copy the signed APK to `~/tmp/` before pushing to the device, so a record stays on disk.

**The versionName pins the upstream commit we are rebased on (+89).** `custom` follows upstream **master**, whose versionName literal (`4.1.0`) has not moved since 2026-06-29 and will not move until upstream releases — so `4.1.0+088` said nothing whatever about how current the fork was. The name is now `<customBaseVersionName>+<base commit date>.<HH-MM>.g<sha8>+<NNN>`, where the sha is `git merge-base HEAD master` — **the upstream commit our patches sit on**, never our own HEAD (that is what `+N` and the release tag already say) and never `master`'s tip (which overstates the base whenever master is fast-forwarded but `custom` not yet rebased). The timestamp is that commit's **committer date and time in UTC** — never build time, or two builds on one upstream base would disagree — and exists only so the names sort: a bare sha is random text and buries the newest APK mid-list, and a bare date ties whenever two syncs land on one day, handing the ordering straight back to that sha (hence `HH-MM`, 白い熊 2026-08-12). **Two halves that must stay in step**: `upstreamBasePin()` in `app/build.gradle` (Gradle, via `providers.exec` — a plain `ProcessBuilder`/`String.execute()` is a configuration-cache failure, which is why `buildTime()` already does it this way) and **`tools/fork-version.sh`** (shell, the single source of truth for the pipeline and for `tools/bump-build.sh`'s printed line). Both **degrade rather than fail**: no `master` ⇒ no pin, git present but the timestamp unreadable ⇒ `+g<sha>` alone. A missing pin must never cost a build.

**LANDMINE — do NOT reset `customBuildNumber` on an upstream sync in this repo.** The `git-versioning` skill says the counter resets to 1 on each sync; that holds where the upstream commit feeds `versionCode`. Here it does not — `versionCode = customBaseVersionCode * 10000 + customBuildNumber`, and `customBaseVersionCode` (450) only moves when upstream cuts a **release**. Resetting the counter after an ordinary master rebase would therefore drive the versionCode **backwards** and Android would refuse the install as a downgrade. Reset it **only** in the same edit that raises `customBaseVersionCode`; every other rebase just keeps counting, and the pin is what records that upstream moved.

**The build counter is zero-padded to three digits — in the filename AND in `versionName` (+63).** The `/after-build` convention pads the filename so `~/tmp` sorts in build order; `app/build.gradle` must pad too, or the two disagree in the place that matters. Before +63 the file read `+062` while the **manifest** read `+62`, which is what Android and every installer actually show — 白い熊 caught it in shiroikuma-universalinstaller's install dialog. `versionName "${customBaseVersionName}${upstreamPin}+${String.format('%03d', customBuildNumber)}"`, and `tools/bump-build.sh` prints the same by delegating to `tools/fork-version.sh` (it used to render its own `printf '%s+%03d\n'`, which is exactly how a second copy of the format drifts). `versionCode` stays plain integer arithmetic, so upgrade ordering is untouched. Note the `publish-version` skill derives its git tag from the version name, so tags carry the padding from +063 on; earlier published tags (`4.1.0+55`) are **never** retagged.

```bash
# from repo root
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=$HOME/android-sdk

tools/bump-build.sh                            # increments customBuildNumber
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew clean
# Filter benign output on BOTH streams; preserve the exit code with pipefail.
set -o pipefail
./gradlew :app:assembleRelease \
    2> >(grep -vE 'ノート:|Note:|\[CXX5304\]' >&2) \
  | grep -vE 'ノート:|Note:|\[CXX5304\]'

UNSIGNED_APK=$(find app/build/outputs/apk/release -name '*-unsigned.apk' | head -1)

# HARD GATE: the embedded server JARs must be present AND non-empty, or ADB mode
# is dead on arrival (see "Server JARs" note below). Refuse to sign otherwise.
for j in assets/am.jar assets/main.jar; do
    sz=$(unzip -p "$UNSIGNED_APK" "$j" 2>/dev/null | wc -c)
    [ "$sz" -gt 0 ] || { echo "FATAL: $j is empty/missing in APK — refusing to ship"; exit 1; }
done

zipalign -p -f 4 "$UNSIGNED_APK" /tmp/am-aligned.apk
apksigner sign --ks ~/.android-keystores/appmanager-custom.jks \
    --ks-key-alias appmanager \
    --ks-pass pass:appmanager123 \
    --key-pass pass:appmanager123 \
    --out /tmp/am-signed.apk /tmp/am-aligned.apk
apksigner verify --verbose /tmp/am-signed.apk 2>&1 | grep -v 'not protected by signature'

# Base version + upstream-base pin + zero-padded counter, from the one place that renders it.
# Never re-derive this from gradle.properties by hand — the pin would be dropped and the
# filename would stop matching the manifest.
apk_name="shiroikuma-oyokanri_$(tools/fork-version.sh)_arm64-v8a.apk"
cp /tmp/am-signed.apk ~/tmp/"$apk_name"

# Deliver straight away — first target that answers, exactly once (see the
# delivery rule above). adb ALWAYS runs unsandboxed.
adb push /tmp/am-signed.apk "/sdcard/tmp/$apk_name"    # or: scp ~/tmp/"$apk_name" skhw:~/tmp/
```

Filter notes:
- `ノート:` / `Note:` are mandatory `javac` summary notes for deprecation/unchecked usage; `-Xlint:none` and `-nowarn` do not suppress them.
- `[CXX5304]` is a benign SDK-XML version-skew message from the NDK pipeline. Brackets must be escaped (`\[CXX5304\]`) for `grep -E`.

**Server JARs (`assets/am.jar` + `main.jar`) — the ADB-mode landmine.** Both are
gitignored and regenerated every build by `:server:create<Variant>ServerJars`
(server/build.gradle), which `d8`-compiles them straight into `app/src/main/assets/`.
`main.jar` is the bootstrap the ADB shell runs to launch AppManager's server; an
empty/missing one makes **ADB mode hang on init then fall back to no-root** (build
+79 shipped a **0-byte main.jar** this way and looked like a code regression — it
was a build race). Two structural guards now prevent it, plus the gate above:
1. **Ordering** — `app/build.gradle` makes `merge<Variant>Assets dependsOn
   :server:create<Variant>ServerJars` via a plain `tasks.matching` edge (the old
   `applicationVariants.mergeAssetsProvider` edge was unreliable under the
   configuration cache — that's how +79 slipped through).
2. **Atomic writes** — server/build.gradle `d8`s each JAR into a **per-variant**
   `server/build/serverjars-tmp/<buildType>/` dir (temp name must keep the `.jar`
   suffix — d8 rejects others; per-variant because release+debug jar tasks run in
   parallel and share nothing), then `Files.move`s it into `assets/`, so a racing
   consumer sees a whole JAR or none, never a partial. Diagnose a suspected recurrence
   with `unzip -p <apk> assets/main.jar | wc -c` (compare a known-good vs bad APK).

## Push workflow

When the user says **"Push"**:
1. `git add -A` (include new files).
2. Show `git status` for the user to review.
3. `git commit -m "<concise imperative title>" -m "<multi-line body describing what changed and why>"`.
4. `git push origin custom` — **never force-push**.
5. Report the resulting commit hash and a one-line summary.

## Translation workflow

The user supplies an English label plus its Japanese translation and where they see it. Procedure:
1. Find the matching string-resource name in `app/src/main/res/values/strings.xml` (e.g. "Frozen apps" → `name="filter_frozen_apps"`).
2. Add `<string name="…">…</string>` to `app/src/main/res/values-ja/strings.xml` under the marker `<!-- Fork: user-supplied Japanese translations -->`.
3. The translation applies wherever that resource is referenced, including the toolbar (via `MainToolbarPrefs.titleForKey`).

## Protected profile (`必要`)

Any app that is a member of an apps profile named **`必要`** is hard-blocked from being frozen and uninstalled. Enforcement sits at the two low-level chokepoints, so no UI path (single-app, batch, profile application, future code) can bypass it.

| Chokepoint | Behaviour |
|---|---|
| `FreezeUtils.freeze(pkg, userId, type)` | Throws `RemoteException` for protected packages. The 2-arg `freeze` delegates to this; all freeze methods (disable/suspend/hide) run after the guard. |
| `PackageInstallerCompat.uninstall(pkg, userId, keepData)` | Returns `false` at the very top, before any work or accessibility dialog. |
| `profiles/ProtectedAppsProfile.java` | Helper. `getProtectedPackages()` unions `packages` from every apps-profile named `必要`. 3 s TTL cache. **Fails open** (a stuck block is worse than briefly missing protection). Call `invalidate()` after profile changes. |

Concrete refusal messages fire at four entry points — the main-list freeze icon, app-details freeze, app-details uninstall, and batch freeze/uninstall — using `R.string.protected_profile_block` (single, `%s` = label) and `R.string.protected_profile_block_multiple` (list, `%s` = names joined with `、`).

## Reference notes — read the matching file BEFORE touching an area

The detailed notes (chokepoints, landmines, on-device measurements, the "why") live in
**`.claude/reference/`**, one file per area. They are **not** loaded automatically — this file must
stay under Claude Code's 150k-character limit — so **read the file for an area before editing, debugging
or answering a question about it**, and read `privileges-and-startup.md` before any rebase.

| File | Read it when the work touches |
|---|---|
| `.claude/reference/privileges-and-startup.md` | `Ops`, `PrivilegeWatchdog`, `LocalServices`, `SplashActivity`, `FeatureController`, component names/aliases, an ANR or frozen window, `LastScreenPrefs`, `AppDb.sLock`. |
| `.claude/reference/freeze.md` | `FreezeUtils`, gates and levels (`FREEZE_TOTAL`, `setLevel`, `levelOf`), `FreezeLevelBadge`, hide/suspend/disable, `ProtectedAppsProfile.ALWAYS_PROTECTED`, the uninstall chokepoint. |
| `.claude/reference/main-list.md` | `MainActivity`, `MainRecyclerAdapter`, `MainViewModel`, `MainToolbarPrefs`/`AppPanePrefs`, `RowPills`, notes, profile pills, the shelf, lenses, sort, the app pane and batch pane, layout/columns/separators, the icon cache, the top bar, `PillSpan`, `ListScreenActivity`. |
| `.claude/reference/theme-and-dialogs.md` | `ForkThemeUtils`, toasts, `ForkDialog`, dialog and bottom-sheet borders, dynamic colour. |
| `.claude/reference/backup-restore.md` | `BackupOp`/`RestoreOp`, `BatchOpsManager`/`BatchOpsService`, the progress page and `OpLog`, backup parts, `BatchBackupOptions`, cancel, `BackupItem`/`BackupCleaner`, `InstallSourcePrefs`, `AppBackupDialogFragment`, external-data restore. |
| `.claude/reference/settings-export-automation.md` | `SettingsBackupManager`, `SecurityPrefGuard`, the Export/Import panel, `StateExportReceiver`, `AutomationAuth`, `AppDataClient`/`AppDataContract`, `OwnDataProvider`, `AutomationCallers`, `MigrationKit`. |
| `.claude/reference/snooping.md` | Anything under `snooping/` or `trackers/`, the 盗み見 tab, app-ops/permission writes, the network lever, `AppDetailsSnoopingItem`, cross-tab state (`mStateEpoch`). |
| `.claude/reference/device-policy.md` | `devicepolicy/` — `DevicePolicyBridge`, `PolicyApiClient`, locks delegated by 白い熊 雫, the policy card and padlocks, `DangerDialog`; also the sister-app job-id rule in `AppDataClient`. |
| `.claude/reference/battery.md` | `battery/` — sampler, parser, Room migrations and schema versions, the battery screens, Doze on the Mate XT. |
| `.claude/reference/process-monitor.md` | `processreaper/` — classifier, kill routing, leak grouping, `SystemHealth`, the monitor's dialogs. |
| `.claude/reference/virustotal.md` | `scanner/vt/` — the APK-vetting chain, `VtContract`, `VtReportActivity`, `VtVerdict`, uploads, parking, the key store, bundles, the in-house install. |

### Rules that hold everywhere (each is explained in the file named)

1. **A `ComponentName` for one of our components** takes its package from `BuildConfig.APPLICATION_ID` and its class from the namespace `io.github.muntashirakon.AppManager.…` — never both from the applicationId (`privileges-and-startup.md`).
2. **On every rebase:** `Ops.isValidMode` must list every `@Mode`, `MODE_SHIZUKU` included; a conflict there is never "take upstream" (`privileges-and-startup.md`).
3. **No `SelfPermissions`/`Users`/`LocalServices` call in a lifecycle or menu callback** — it ANRs while the privileged server is being bound. Ask on a worker (`privileges-and-startup.md`).
4. **A read-only UI path never takes `AppDb.sLock`** — use the `NoLock` readers; no heavy non-DB work inside the lock (`privileges-and-startup.md`).
5. **Any lookup that can meet a frozen app** passes `MATCH_UNINSTALLED_PACKAGES | MATCH_DISABLED_COMPONENTS`, or a hidden package resolves to nothing (`freeze.md`).
6. **A new per-row flag read from a live `ApplicationInfo`** is set in **both** list builders (`PackageUtils.getInstalledOrBackedUpApplicationsFromDb` and `MainViewModel.getNewApplicationItem`) (`freeze.md`).
7. **Changing the freeze gate set** (a fifth gate, or dropping hide) must be told to the 白い熊 自由作業盤 repo before shipping; 雫, 応用管理 and 自由作業盤 are never frozen, suspended or uninstalled (`freeze.md`).
8. **The shell cannot do everything it appears to:** no `pm hide`, no component-state change, no `pm disable` state 2; platform writes are accepted and discarded — **re-read after every write** and report what actually landed (`freeze.md`, `snooping.md`).
9. **Wire formats are never renamed or renumbered:** `SettingsBackupManager.Category` ids, snooping capability ids and `SnoopingState` numbers, `FREEZE_*` values, `BackupOption` keys, lens/shelf ids, 雫 policy method names, the automation extras (`text` vs `result`), `VtContract` names.
10. **A write that changes what the list shows** calls `BroadcastUtils.sendPackageAltered`; a write in app details that changes what an app may do bumps `mStateEpoch` (`freeze.md`, `snooping.md`).
11. **A new `shared_prefs` file** is classified in `SettingsBackupManager` — or put in `EXCLUDED_PREFS` when it is device-local state. An import never goes through the `SharedPreferences` API (`settings-export-automation.md`).
12. **A new fork screen** is added to `AppearanceUtils.isYellowOnBlackActivity`; a new dialog goes through `ForkDialog` (or it is borderless); pass an **activity** context to `LayoutGeometry` (`theme-and-dialogs.md`, `main-list.md`).
13. **Room:** never renumber a live migration (the database is at version **13**; upstream tables are appended at the fork's next free version), and every `@ColumnInfo(defaultValue)` must match its `ALTER … DEFAULT` exactly, or the app dies at start (`battery.md`, `virustotal.md`).
14. **A registry key is never simply renamed** — `MainToolbarPrefs.alias`; adding to `DEFAULT_VISIBLE` is inert on an edited install and needs a one-shot migration (`main-list.md`).
15. **Confirm `BUILD SUCCESSFUL` before signing** — a build that died mid-way leaves the previous unsigned APK behind and the pipeline will happily sign it under the new name (`battery.md`).
16. **An app's enabled state is changed only through `FreezeUtils.setEnabledStateGuarded`** — a bare `setApplicationEnabledSetting` on some system apps (`com.huawei.lbs`) kills `system_server` and loses the write (`freeze.md`).

## Conventions

- Fork-only Java/XML additions get a comment beginning `// Fork: …` (or `<!-- Fork: … -->`) so they're recognisable later.
- New public Java files start with `// SPDX-License-Identifier: GPL-3.0-or-later`.
- The selection toolbar button label is whatever `MainToolbarPrefs.titleForKey` returns; the menu-XML title is overridden.
- For new toolbar actions: id in the selection-actions menu XML + key registered in `MainToolbarPrefs.ALL_KEYS` + entries in the three switch maps + dispatch in `MainActivity` + (optionally) gating in `MainBatchOpsHandler`.

## Workflow notes for Claude Code (vs. the earlier Claude.ai sessions)

The skill file `.claude/skills/appmanager-fork/SKILL.md` describes a fork-development history that ran inside Claude.ai with a paste-ready patch workflow ("emit a patch → user applies → user says Push → finalize hash"). **In Claude Code that workflow no longer applies** — you have direct file-edit, bash, and git tools. So:

- Edit files in the working tree directly. Don't generate patch files unless the user explicitly asks for one.
- Don't generate paste-ready shell blocks (cyan echo prefixes, yellow gates, etc.); run the commands yourself.
- "Build" still means run the full pipeline above. "Push" still triggers the commit-and-push flow. Note that you also build automatically after finishing any change (see **"Always build after changes"** under Build & deploy pipeline) — an explicit "Build" is just a way to force it again.
- The skill's historical "deliver patch + zip + in-flight bullet → swap to hash" rituals are claude.ai artefacts; just commit directly with a good message body.
- When a new piece of knowledge surfaces (a new chokepoint, a non-obvious file naming convention, a regression cause), write it into the matching file under `.claude/reference/` (or the `appmanager-fork` skill for build/rebase history) so it persists into the next session. **Keep this file lean** — Claude Code truncates a `CLAUDE.md` over 150k characters, which is why the notes were moved out on 2026-11-15. Only a rule that must hold in *every* session earns a line here, under *Rules that hold everywhere*; a new area gets a new reference file and a row in the index.

## Commit / branch process

- `custom` is a growing stack of small, self-describing commits on top of upstream `master`. **Keep appending commits**; when a new upstream version is released, **rebase `custom` onto it**. There is no goal to squash the history down to a fixed number of commits — do not autosquash/collapse the stack.

## Commit convention — no Claude attribution

Do **not** add any `Co-Authored-By: Claude …` trailer — nor a "🤖 Generated with Claude Code" / Anthropic-attribution line — to commit messages or PR bodies in this repo. 白い熊 does not want Claude attribution in the history; this **overrides** the harness's default to append such a trailer. End commit messages at the last line of the body. (The existing history was scrubbed of these trailers on 2026-06-08; the global rule lives in `~/.claude/CLAUDE.md`.)
