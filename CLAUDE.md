# CLAUDE.md — 白い熊 応用管理 (`shiroikuma.oyokanri`)

This file is loaded automatically by Claude Code. It captures the fork's facts,
build pipeline, and operating conventions so a fresh session can pick up
exactly where the previous one left off. The full per-commit changelog and
engineering lessons live in **`.claude/skills/appmanager-fork/SKILL.md`** —
consult that for the "why" behind anything in here.

## Project

- Fork of [AppManager](https://github.com/MuntashirAkon/AppManager) (upstream base **4.0.5**, minSdk 21, AGP 8.13.2).
- `applicationId`: `shiroikuma.oyokanri` (installs side-by-side with the official build; renamed from the former `shiroikuma.appmanager`, so it is a fresh install rather than an update).
- Java package (unchanged from upstream): `io.github.muntashirakon.AppManager`.
- Display label: 白い熊 応用管理.
- Remote: `origin` → ShiroiKuma0/shiroikuma-oyokanri (formerly ShiroiKuma0/appmanager).
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

**Always ask how to deliver, after every build.** Delivery of the signed APK is never automatic. After every successful build, summarise what was built and ask **via the `AskUserQuestion` tool** how to deliver it, offering two options in this order: **scp to skhw** (the first / recommended choice → run the `scp` skill) and **adb push to the device** (`adb push /tmp/am-signed.apk /sdcard/tmp/<apk_name>`, needs the phone connected with USB debugging). The tool also lets the user pick "Other" (e.g. neither). Never deliver without asking, and never silently skip the question. Deliver only the chosen way, only once the user answers.

Output APK is named `shiroikuma-oyokanri_${customBaseVersionName}+${customBuildNumber}_arm64-v8a.apk`, derived from `gradle.properties`. Always copy the signed APK to `~/tmp/` before pushing to the device, so a record stays on disk.

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
apk_name="shiroikuma-oyokanri_${VER}+${NUM}_arm64-v8a.apk"
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
| Batch-op completion snap (main list) | `MainViewModel.applyBatchOpResult(op, result, packages, failed)`, called from `MainActivity`'s `ACTION_BATCH_OPS_COMPLETED` receiver, snaps the list to its final state in **one** pass instead of letting the system's throttled per-package change broadcasts repaint it row-by-row over many seconds. For ops with a cheap in-memory state change it mutates the affected rows directly, then re-sorts + re-filters once: **freeze/unfreeze** → `ApplicationItem.setFrozenStateForBatchOp(frozen)` (keyed off `freezeTargetForOp`), via the shared `snapApplicationItems(targets, mutator)` helper; **reinstall** (`OP_INSTALL_EXISTING`) → `ApplicationItem.setInstalledStateForBatchOp()` (flips `isInstalled=true`, so rows drop out of the *Uninstalled apps* filter at once); **uninstall** (`OP_UNINSTALL`, `keepData=false`) → `snapUninstalledApplicationItems(...)`, which per-row mirrors `AppDb.updateApplicationInternal`: an **updated** system app stays installed (left untouched), a **pure** system app or any app **with a backup** flips to `isInstalled=false`, and a user app **with no backup** is removed outright. Any **other** op falls back to a targeted re-read (`updateInfoForPackages`). The optimistic in-memory branches only run when the op `ranToCompletion` (`result != RESULT_CANCELED`); on cancel the package list names every queued app but the unprocessed ones weren't changed, so it falls back to the true-state re-read. The trailing OS broadcasts then merely confirm the same state, so there's no visible row-by-row churn. When adding a new op whose result changes a filterable flag, give it an in-memory snap branch here rather than relying on the re-read/broadcast path. |
| Profile pills (per row) | `MainRecyclerAdapter.mPackageToProfileNames`, rebuilt by `loadProfileMembership()`. Trigger via `reloadProfileMembership()` on pull-to-refresh, on resume, and on the `AddToProfileDialogFragment.RESULT_KEY` fragment-result signal sent after a successful add. |
| Per-app notes (main list + app details) | Free-text note attached to a package. Store: `utils/AppNotesManager.java` — a dedicated SharedPreferences file `shiroikuma_notes.xml` (key = package name, value = note; blank text **deletes** the key). Keyed by package only (multi-user packages share one note). The view/edit dialog is the shared static `AppNotesManager.showNoteDialog(ctx, pkg, label, onSaved)` (wraps `TextInputDialogBuilder`; multi-line, pre-filled, Save persists, blank deletes; optional `onSaved` runnable) — called by both `MainRecyclerAdapter.showNoteDialog` (onSaved = row refresh) and the app-details **Note** horizontal action button (`AppInfoFragment.getHorizontalActions`, an `ActionItem` before Uninstall; onSaved = null, the main list re-binds on resume). Because the store is its **own** `shared_prefs/*.xml`, **Settings export/import covers it automatically** with no change to `SettingsBackupManager`. UI in `MainRecyclerAdapter`: when a note exists, the glyph (`drawable/ic_note_24dp.xml`) is set as a **compound `drawableEnd` on the `label`** — NOT a sibling view — so it hugs the end of the app name and stays visible when the name ellipsizes (a weighted sibling `ImageView` gets shoved to the far edge of the row, on top of the "+" slot; `ConstraintLayout` isn't a dep here, so the compound drawable is the right primitive). Only the glyph's hit region is tappable, via the `label` `OnTouchListener` (compares `event.getX()` against `getCompoundPaddingRight()`/`...Left()` for RTL; returns false elsewhere so the card still gets details/select); `@SuppressLint("ClickableViewAccessibility")` + `v.performClick()`. When there's no note, the label drawable + touch listener are cleared and the top-right "+" shows (`@id/note_add`, reuses `ic_add`, `setOnClickListener`). `label` is `wrap_content` + `ellipsize="end"` + `drawablePadding`. Mutually exclusive; **both** bind branches reset the label drawable/listener AND the "+" visibility/listener (recycling). Tap either → `showNoteDialog(...)` (reuses `TextInputDialogBuilder`, multi-line, pre-filled); on Save it persists and refreshes just that row via `notifyItemChanged(holder.getBindingAdapterPosition())` (re-read at save time, **never** the stale bind position). Tint = `ForkThemeUtils.getTextColor()`. Strings `note` / `note_blank_deletes_helper`. |
| Settings export/import | `app/src/main/java/io/github/muntashirakon/AppManager/settings/SettingsBackupManager.java` bundles `shared_prefs/*.xml` + `files/profiles/` + `files/fonts/`. On import, hard-kill via `Process.killProcess(myPid)` — **not** `Runtime.exit` — or cached `SharedPreferences` will clobber the imported files on orderly shutdown. |
| Profile file resolution | `ProfileManager.resolveExistingProfilePath(profileId)` returns the actual file by matching its stored id (scanning if the canonical name doesn't exist). Use this when writing back to a profile rather than `findProfilePathById`, which assumes the filename stem == id. |
| App-icon cache (main list) | `app/src/main/java/io/github/muntashirakon/AppManager/self/imagecache/ImageLoader.java` caches icons in two layers — in-memory `LruCache` + on-disk `images/<tag>.png` (`ImageFileCache`, 7-day-mtime GC, never a full wipe) — keyed by a **version-unaware tag**, so a reinstall keeps the **old** icon until the GC. The main list keys each row by `ImageLoader.versionedTag(packageName, lastUpdateTime)` → `"pkg@time"` so a reinstall busts both layers. **Set the same versioned tag on both `holder.icon.setTag(...)` and `displayImage(...)`** (`MainRecyclerAdapter` ~line 464) — they must match or the recycled-view guard never binds the bitmap. Feed it a **live** `lastUpdateTime`: `MainViewModel.getNewApplicationItem` sources it from `getPackageInfo` (not the DB-cached `app.lastUpdateTime`, which lags reinstalls — the "some icons refresh, some don't" cause). Other icon surfaces (app-details, usage, running-apps, etc.) still use the bare `packageName` key. |
| Main-list layout picker (top bar) | 3×3 yellow tile-grid icon (`drawable/ic_grid_3x3.xml`, `action_layout_columns` in `activity_main_actions.xml`, last `ifRoom` item so it sits left of the overflow ⋮). Opens a single-choice dialog: **Adaptive (default)** = the original `UIUtils.getGridLayoutAt450Dp` auto-fit grid (one column per 450dp), or fixed **2/3/4 columns** (`GridLayoutManager`). Persisted in `main/MainLayoutPrefs.java` (dedicated SharedPreferences `shiroikuma_main_layout`, key `columns`, 0 = adaptive — covered automatically by settings export/import). `MainActivity.applyListLayout()` swaps the layout manager on `mRecyclerView` at onCreate and on pick. |
| Main-list separator grid (no grey between cells) | Cells are edge-to-edge: `item_main.xml` has **no margins**, the RecyclerView background is **black** (matches the cards, so grey can never show — including under shorter cells in a multi-column row), and the adapter sets **corner radius 0 and stroke width 0** per bind — unselected cells draw **no outline at all** (the old per-state strokes — running yellow/orange, uninstalled, disabled — read as random frames once cells touched; the "Card outline" colour rows were removed from the settings screen with them, though `ColorPrefs.STROKE_*` constants remain). The **checked/selected** card's frame is **fully owned by the adapter from prefs** (NOT the M3 checked theming): colour `ColorPrefs.SELECTED_FRAME` (default yellow), border width + corner roundness in `fonts/SelectionFramePrefs.java` (`shiroikuma_selection_frame`; defaults 4dp / 24dp — 24dp = the original `listItemCornerRadius`), with its own "Selected app frame" settings group (`buildSelectionFrameElement`, `view_selection_frame_element.xml`). The frame prefs are read **at bind time** (not cached in `reloadColors`) and the flag-guarded appearance refresh lives in `MainActivity.refreshForkAppearanceIfChanged()`, called from **both** `onResume` and `onTopResumedActivityChanged` — on the tri-fold, settings + main list can be resumed side by side (multi-window), where switching windows never fires onResume, which made setting changes appear to have no effect. The settings page is titled **白い熊 応用管理 UI** (`pref_fonts`) and is also opened directly by **long-pressing the toolbar overflow (hamburger) button** (`MainActivity.attachOverflowLongPress` — the overflow button is the only `ImageView` child of the toolbar's `ActionMenuView`). Settings-screen hierarchy indents: level-2 element labels at 32dp, level-3 controls at 64dp (each level doubles). **Never derive the radius from `MaterialCardView.getRadius()`** — M3 corner sizes resolve against laid-out bounds and return 0 before layout (that bug shipped briefly as a square selection frame in +72); `getStrokeWidth()` is a plain int and safe to capture (used for the unselected 1dp style stroke). Straight separator lines are drawn by `main/MainSeparatorDecoration.java` (offsets reserve the gap — right of every non-last column, below every row; rows keyed by shared decorated top, line at the tallest cell's bottom). Widths in `fonts/SeparatorPrefs.java` (`shiroikuma_separators`, dp floats, default 0.5, 0 = none, max 8); colours are `ColorPrefs.SEPARATOR_H`/`SEPARATOR_V` (default yellow). Settings UI: "Main list separators" group at the end of **UI colors & fonts** (`FontsPreferences.buildSeparatorElement`, `view_separator_element.xml` — slider in 0.5dp steps + standard colour row). Live refresh via `SeparatorPrefs.consumeChanged()` in `MainActivity.onResume` → `MainSeparatorDecoration.reload` + `invalidateItemDecorations` (also on `ColorPrefs` changes, since the colours live there). |

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

## Commit convention — no Claude attribution

Do **not** add any `Co-Authored-By: Claude …` trailer — nor a "🤖 Generated with Claude Code" / Anthropic-attribution line — to commit messages or PR bodies in this repo. 白い熊 does not want Claude attribution in the history; this **overrides** the harness's default to append such a trailer. End commit messages at the last line of the body. (The existing history was scrubbed of these trailers on 2026-06-08; the global rule lives in `~/.claude/CLAUDE.md`.)
