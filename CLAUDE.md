# CLAUDE.md — 白い熊 App Manager (`shiroikuma.appmanager`)

This file is loaded automatically by Claude Code. It captures the fork's facts,
build pipeline, and operating conventions so a fresh session can pick up
exactly where the previous one left off. The full per-commit changelog and
engineering lessons live in **`.claude/skills/appmanager-fork/SKILL.md`** —
consult that for the "why" behind anything in here.

## Project

- Fork of [AppManager](https://github.com/MuntashirAkon/AppManager) (upstream base **4.0.5**, minSdk 21, AGP 8.13.2).
- `applicationId`: `shiroikuma.appmanager` (installs side-by-side with the official build).
- Java package (unchanged from upstream): `io.github.muntashirakon.AppManager`.
- Display label: 白い熊 App Manager.
- Remote: `origin` → ShiroiKuma0/appmanager.
- Branch: **`custom`** — all fork work lives here; pushes go to `origin/custom`.

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

**Always ask before `adb push`.** The final `adb push` to the device is the one step that is never automatic: after every successful build, explicitly ask the user whether to push the signed APK to the device. Never push without asking, and never silently skip it — end the build report with the question (it needs the phone connected with USB debugging). Push only once the user confirms.

Output APK is named `shiroikuma-appmanager_${customBaseVersionName}+${customBuildNumber}_arm64-v8a.apk`, derived from `gradle.properties`. Always copy the signed APK to `~/tmp/` before pushing to the device, so a record stays on disk.

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

zipalign -p -f 4 "$UNSIGNED_APK" /tmp/am-aligned.apk
apksigner sign --ks ~/.android-keystores/appmanager-custom.jks \
    --ks-key-alias appmanager \
    --ks-pass pass:appmanager123 \
    --key-pass pass:appmanager123 \
    --out /tmp/am-signed.apk /tmp/am-aligned.apk
apksigner verify --verbose /tmp/am-signed.apk 2>&1 | grep -v 'not protected by signature'

VER=$(grep '^customBaseVersionName=' gradle.properties | cut -d= -f2)
NUM=$(grep '^customBuildNumber='     gradle.properties | cut -d= -f2)
apk_name="shiroikuma-appmanager_${VER}+${NUM}_arm64-v8a.apk"
cp /tmp/am-signed.apk ~/tmp/"$apk_name"

# After the user has connected the phone with USB debugging:
adb push /tmp/am-signed.apk "/sdcard/tmp/$apk_name"
```

Filter notes:
- `ノート:` / `Note:` are mandatory `javac` summary notes for deprecation/unchecked usage; `-Xlint:none` and `-nowarn` do not suppress them.
- `[CXX5304]` is a benign SDK-XML version-skew message from the NDK pipeline. Brackets must be escaped (`\[CXX5304\]`) for `grep -E`.

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

## Key files & chokepoints

| Concern | File |
|---|---|
| Freeze chokepoint | `app/src/main/java/io/github/muntashirakon/AppManager/utils/FreezeUtils.java` |
| Uninstall chokepoint | `app/src/main/java/io/github/muntashirakon/AppManager/apk/installer/PackageInstallerCompat.java` |
| Customisable selection toolbar registry | `app/src/main/java/io/github/muntashirakon/AppManager/main/MainToolbarPrefs.java` — add new actions to `ALL_KEYS` + `idForKey`/`titleForKey`/`iconForKey`. An item in the menu XML alone is **invisible**, because the toolbar is rebuilt programmatically. |
| Toast theming (black/yellow) | `app/src/main/java/io/github/muntashirakon/AppManager/utils/UIUtils.java` routes every `displayShortToast` / `displayLongToast` / `displayLongToastPl` through a custom view (`layout/toast_shiroikuma.xml`). Colours/border now come from the **configurable fork theme** at runtime via `ForkThemeUtils` (the static `drawable/bg_toast_shiroikuma.xml` is no longer the source of truth). Custom toast views only render in the foreground on Android 12+. |
| Configurable fork theme | `app/src/main/java/io/github/muntashirakon/AppManager/utils/ForkThemeUtils.java` is the single source of truth for text/background/border colour + border width (prefs `PREF_THEME_*` in `AppPref`, accessors in `Prefs.Appearance`, UI in `preferences_appearance.xml` + `AppearancePreferences`). Styles **both** the themed toasts and the batch-progress dialog. Defaults reproduce the original hard-coded bright-yellow-on-black look. |
| Batch-op progress dialog + pause/cancel | In-app pop-up mirroring the batch-ops notification, with **Pause/Continue** and **Cancel**. `batchops/BatchOpsProgressMonitor.java` is a process-wide singleton bus: the service calls `begin`/`finish`, the worker loop calls `publishProgress` and the per-item checkpoint, the dialog observes its `LiveData` and drives `pause`/`resume`/`cancel`. The single chokepoint is `BatchOpsManager.updateProgress(...)` (first call in every op loop) — it blocks while paused and throws `OperationCancelledException` on cancel, caught in `performOp(info, handler)`. `BatchOpsService.onHandleIntent` reads the cancelled flag and reports `RESULT_CANCELED`. `main/BatchProgressDialog.java` builds/themes the dialog; `MainActivity` shows it on `ACTION_BATCH_OPS_STARTED` (and on resume if still active), dismisses on `ACTION_BATCH_OPS_COMPLETED`. Gated by the `batch_progress_dialog` pref. |
| Profile pills (per row) | `MainRecyclerAdapter.mPackageToProfileNames`, rebuilt by `loadProfileMembership()`. Trigger via `reloadProfileMembership()` on pull-to-refresh, on resume, and on the `AddToProfileDialogFragment.RESULT_KEY` fragment-result signal sent after a successful add. |
| Settings export/import | `app/src/main/java/io/github/muntashirakon/AppManager/settings/SettingsBackupManager.java` bundles `shared_prefs/*.xml` + `files/profiles/` + `files/fonts/`. On import, hard-kill via `Process.killProcess(myPid)` — **not** `Runtime.exit` — or cached `SharedPreferences` will clobber the imported files on orderly shutdown. |
| Profile file resolution | `ProfileManager.resolveExistingProfilePath(profileId)` returns the actual file by matching its stored id (scanning if the canonical name doesn't exist). Use this when writing back to a profile rather than `findProfilePathById`, which assumes the filename stem == id. |
| App-icon cache (main list) | `app/src/main/java/io/github/muntashirakon/AppManager/self/imagecache/ImageLoader.java` caches icons in two layers — in-memory `LruCache` + on-disk `images/<tag>.png` (`ImageFileCache`, 7-day-mtime GC, never a full wipe) — keyed by a **version-unaware tag**, so a reinstall keeps the **old** icon until the GC. The main list keys each row by `ImageLoader.versionedTag(packageName, lastUpdateTime)` → `"pkg@time"` so a reinstall busts both layers. **Set the same versioned tag on both `holder.icon.setTag(...)` and `displayImage(...)`** (`MainRecyclerAdapter` ~line 464) — they must match or the recycled-view guard never binds the bitmap. Feed it a **live** `lastUpdateTime`: `MainViewModel.getNewApplicationItem` sources it from `getPackageInfo` (not the DB-cached `app.lastUpdateTime`, which lags reinstalls — the "some icons refresh, some don't" cause). Other icon surfaces (app-details, usage, running-apps, etc.) still use the bare `packageName` key. |

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
- When a new piece of knowledge surfaces (a new chokepoint, a non-obvious file naming convention, a regression cause), update either this CLAUDE.md or the `appmanager-fork` skill so it persists into the next session.

## Commit / branch process

- `custom` is a growing stack of small, self-describing commits on top of upstream `master`. **Keep appending commits**; when a new upstream version is released, **rebase `custom` onto it**. There is no goal to squash the history down to a fixed number of commits — do not autosquash/collapse the stack.
