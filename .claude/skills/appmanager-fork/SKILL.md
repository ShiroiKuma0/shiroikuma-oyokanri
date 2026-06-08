---
name: appmanager-fork
description: Build the user's patched fork of AppManager for Android (package shiroikuma.appmanager, installable side-by-side with the official AppManager from F-Droid). The fork exposes AM Debug features (Finder, Crazy Logger, scanner missing-classes, historical operations, Unfrozen filter) in a release-grade build and neuters the upstream build-expiry checker. Use this skill any time the user mentions AppManager, MuntashirAkon/AppManager, ShiroiKuma0/appmanager, io.github.muntashirakon.AppManager, shiroikuma.appmanager, App Manager Custom, AM Debug, AMInsecureDebugBuilds, the AppManager fork, the Finder activity, asks to pull a new AppManager upstream master, asks to rebase the custom branch onto a newer master, asks to rebuild AppManager, or references their AppManager build pipeline. Default to assuming this skill applies when in doubt during a session about AppManager. Companion to and follows the same shell formatting as `shell-block-formatting`, `simplex-chat-build`, and `shiroikumanojisho-build`.
---

# AppManager — patched fork build skill

The user maintains a downstream-patched build of [AppManager](https://github.com/MuntashirAkon/AppManager) on Android. They build it on a Tuxedo OS workstation from their fork (`ShiroiKuma0/appmanager`) and sideload the APK alongside the official AppManager. The fork has three purposes: (1) install side-by-side with the official build via a different `applicationId`; (2) light up the AM Debug-only features that MuntashirAkon has gated behind `BuildConfig.DEBUG` in master but no longer publishes prebuilt AM Debug APKs of (the `MuntashirAkon/AMInsecureDebugBuilds` distribution repo was discontinued); (3) remove the built-in build-expiry check so the build doesn't self-disable after N months.

## Project identity

| Item | Value |
|------|-------|
| Upstream repo | `MuntashirAkon/AppManager` |
| User's fork | `ShiroiKuma0/appmanager` (lowercase, intentional) |
| Local working tree | `~/git/shiroikuma-appmanager` |
| Local remote names | `upstream` → `MuntashirAkon/AppManager` (SSH), `origin` → `ShiroiKuma0/appmanager` (SSH) |
| Persistent branch | `custom` (force-pushable on each rebase) |
| Base | **rolling on `upstream/master`** — NOT pinned to a release tag (see "Why master, not a tag" below) |
| Custom Android applicationId | `shiroikuma.appmanager` |
| Custom app display name (release) | `白い熊 App Manager` |
| Java/Kotlin namespace (unchanged) | `io.github.muntashirakon.AppManager` |
| Custom signing keystore | `~/.android-keystores/appmanager-custom.jks` (alias `appmanager`, passphrase `appmanager123`) |
| Output APK directory | `~/tmp/` |
| Output APK name | `shiroikuma-appmanager_<versionName>_arm64-v8a.apk` where versionName is `<customBaseVersionName>+<customBuildNumber>`, e.g. `shiroikuma-appmanager_4.0.5+21_arm64-v8a.apk`. No datetime, no git sha. |
| On-device deploy path | `/sdcard/tmp/` |
| Build host | Tuxedo OS |
| Target ABI | `arm64-v8a` only (skip `armeabi-v7a`, `x86`, `x86_64`; `universalApk false`) |
| Build JDK | OpenJDK 21 at `/usr/lib/jvm/java-21-openjdk-amd64` |
| Android SDK | `/home/shiroikuma/android-sdk`, platform-36 + build-tools-36.1.0 required |
| NDK | AGP auto-picks an installed NDK or auto-downloads (~1 GB) if none present |
| AGP / Gradle | 8.13.2 / 9.0.0 |
| Gradle configuration cache | **enabled** (in customization commit 2; see "The Gradle configuration cache commit" below) |
| Git submodules | `scripts/android-libraries` and `scripts/android-debloat-list` — must clone with `--recurse-submodules`, re-init after each checkout that crosses submodule pointer changes |

Apply the same shell-formatting conventions as `shell-block-formatting`: every command in chat blocks starts with a cyan `>>>` echo prefix, errors get red-colored via the `r() { "$@" 2> >(sed $'s/.*/\033[1;31m&\033[0m/' >&2); }` helper (note the **`$'...'`** bash ANSI-C quoting — plain `'...'` makes GNU sed parse `\033` as `\0` whole-match backreference plus literal `33`, corrupting output), and every long-running step (Gradle build, signing) sits behind a `read -p` pause gate.

## Mandatory pre-patch protocol — pull, reset, then generate

**Every time before generating a patch for this project, the sandbox MUST be synced to `origin/custom`.** The user pushes to `origin/custom` as the authoritative state. Working from a stale sandbox produces patches that fail to apply because of even minor drift (blank lines stripped by an editor, prior local applies that diverged, etc.). The patch generation base is always `origin/custom` as it actually exists on GitHub right now — never a local commit, never a tag, never a previous sandbox state assumed to still match.

Steps every time, in the sandbox:

```bash
cd /home/claude/AppManager
# Ensure origin is configured (one-time):
git remote add origin https://github.com/ShiroiKuma0/appmanager.git 2>/dev/null || true
# Fetch with full refspec so origin/<branch> remote-tracking refs land properly:
git fetch origin '+refs/heads/*:refs/remotes/origin/*'
# Hard-reset sandbox to whatever the user has on origin/custom RIGHT NOW:
git reset --hard origin/custom
git clean -fd
```

If the user has WIP locally that was not pushed (uncommitted patch applications, hand-edits), those are NOT visible to Claude and MUST NOT be assumed. Generate the patch against `origin/custom`, and have the user `git reset --hard origin/custom` before applying — that throws away their WIP. The combined patch should be a single comprehensive patch covering all changes from `origin/custom` to the desired end state, so one apply gets them where they need to be.

If the user pushes a new commit (or amends `custom`), repeat the pull+reset before the next patch. Skipping this step is what produces "patch does not apply" errors after the user reports a successful push.

## Why master, not a tag

Standard fork practice is to base on a tagged release for stability. **For AppManager that reflex is wrong.** Two reasons:

1. **MuntashirAkon stopped publishing AM Debug builds.** The features the user wants from AM Debug (Finder, Crazy Logger, etc.) are still in master — gated by `BuildConfig.DEBUG` — but never make it to a "stable" release flavor anymore. Master is the only place they live.

2. **Useful features land on master between releases and may not appear in the next tag for a long time.** Example: the "Unfrozen apps" filter was added in commit `a58a2f260` (2025-09-20) two months *after* tag `v4.0.5` (2025-07-27). As of skill-creation time (~mid-May 2026, 10 months after v4.0.5), there is still no release containing that commit. If we'd based on `v4.0.5`, we'd be missing Unfrozen and every other post-v4.0.5 improvement.

Cherry-picking individual master commits onto a release tag isn't a workable workaround either: commit `f186c4f52` ("Replace most of the filter implementation with Finder-based filters") was a structural refactor between `v4.0.5` and `a58a2f260`, so the Unfrozen commit (`a58a2f260`) doesn't cherry-pick cleanly onto `v4.0.5`. The cherry-pick conflicts on `MainListOptions.java`. The clean strategy is to sit on master and let the rebase do the work.

This means `custom` is anchored to a specific master commit at any moment (the commit it was rebased onto). When the user asks to pull upstream, fetch `upstream/master` and rebase `custom`.

## The customization commits

`custom` carries **seven commits** on top of `upstream/master`, in this order:

1. `Customize for shiroikuma side-by-side install` — applicationId, app_name, ABI splits, BuildExpiryChecker, four `BuildConfig.DEBUG` feature-gate flips. Six files. Documented below as **commit 1**.
2. `Enable Gradle configuration cache (server, docs, app buildTime)` — `org.gradle.configuration-cache=true` plus the four-site refactor that makes the build CC-compatible. Four files. Documented below as **commit 2**.
3. `Fix cleanupDocs CC regression: use File.deleteDir() GDK extension` — corrective hotfix on top of commit 2, replacing the broken call to upstream's script-level `def deleteDir(File)` helper with Groovy's GDK `File.deleteDir()`. One file. Documented below as **commit 3**.
4. `Add custom build numbering and profile-filter negation` — versioning system (gradle.properties additions, app/build.gradle defaultConfig override, `tools/bump-build.sh` script) and the profile-filter-negate UI + viewmodel logic. Ten files. Documented below as **commit 4**.
5. `Fix mergeAssets ordering: explicit dependsOn on :server:create*ServerJars` — second corrective hotfix on commit 2. Adds an explicit `mergeAssetsProvider.dependsOn ":server:create<Variant>ServerJars"` in app/build.gradle so the server-runner JARs (`am.jar`, `main.jar`) are reliably packaged into the APK under CC + parallel scheduling. One file. Documented below as **commit 5**.
6. `Add main list freeze indicator with auto-refresh after freeze/unfreeze` — snowflake badge + italic label + dimmed icon for frozen apps (any of: PM-disabled, suspended, hidden) in the main package list, plus live-PM enrichment so the indicator survives DB cache staleness and delayed broadcasts, plus an explicit `BroadcastUtils.sendPackageAltered` call after freeze/unfreeze so the list refreshes automatically. Six files modified + one new vector drawable. Documented below as **commit 6**.
7. `Add 'Skip freeze method dialog' preference under Settings -> Rules` — new `SwitchPreferenceCompat` plumbed end-to-end (AppPref enum, Prefs.Blocking accessors, XML preference, strings, AppInfoFragment branch). When enabled, the App-info Freeze action skips the method-picker dialog and freezes directly with the resolved method. Bulk freeze dialogs are intentionally unaffected. Five files. Documented below as **commit 7**.

(That seven-commit list is a historical snapshot; `custom` has grown well beyond it since — see `git log master..custom`.) Each commit's edits are small and surgical, designed for trivial conflict resolution on rebase. The process is to **keep appending** small, logically-distinct commits on top of upstream and **rebase `custom` onto each new upstream version** — there is no goal to collapse the stack into a fixed number of commits. When adding NEW customizations: append another small commit if it's logically distinct, or amend into the most natural existing commit only if the change is corrective and still unpushed.

## Commit 1 — `Customize for shiroikuma side-by-side install`

Six files. Each edit is small and surgical, designed for trivial conflict resolution on rebase.

### 1. `app/build.gradle` — applicationId, app_name, ABI splits

```diff
-        applicationId 'io.github.muntashirakon.AppManager'
+        applicationId 'shiroikuma.appmanager'
```

```diff
         release {
             minifyEnabled false
             proguardFiles getDefaultProguardFile('proguard-android.txt'), 'proguard-rules.pro'
-            resValue "string", "app_name", "App Manager"
+            resValue "string", "app_name", "App Manager Custom"
         }
```

```diff
     splits {
         abi {
             reset()
-            include 'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'
-            universalApk true
+            include 'arm64-v8a'
+            universalApk false
         }
     }
```

`namespace 'io.github.muntashirakon.AppManager'` is left **unchanged**. This is critical: Java class references (`io.github.muntashirakon.AppManager.settings.XYZ` in preference fragment XML and layout `tools:context=`), JNI symbol names (`Java_io_github_muntashirakon_*` in `app/src/main/cpp/`), `Class.forName("io.github.muntashirakon.AppManager.BuildConfig")` reflection in `LocalFileOverlay.java`, and the `BuildConfig` class location all follow the namespace, not applicationId. Keeping namespace constant means these all keep working without modification.

The `FileProvider` authority is `${applicationId}.file` (line ~1399 of `AndroidManifest.xml`) — auto-propagates with applicationId. No manifest patching needed.

### 2. `BuildExpiryChecker.java` — never expire

```diff
     @Nullable
     public static Boolean buildExpired() {
-        int buildType = getBuildType();
-        long timeSpan = getCurrentTime() - getBuildTime();
-        long realTimeSpan = TIME_SPAN_MILLIS[buildType];
-        if (timeSpan <= realTimeSpan) {
-            // Build hasn't yet expired
-            return false;
-        }
-        // Build has expired
-        long warningPeriod = WARNING_PERIOD_MILLIS[buildType];
-        if (timeSpan <= realTimeSpan + warningPeriod) {
-            // Build has expired but in warning period
-            return null;
-        }
-        // Build has completely expired and should stop working
-        return true;
+        // Customized: never expire
+        return false;
     }
```

Upstream's `BuildExpiryChecker` expires builds by build-type: DEBUG after 2 months, ALPHA/BETA/RC after 6, STABLE after 18. When expired, the user gets a blocking "Update or Uninstall" dialog (STABLE additionally offers "Continue"; DEBUG does not). We neuter the whole check to return `false` always, regardless of build type or build time. The `TIME_SPAN_MILLIS`, `WARNING_PERIOD_MILLIS`, and `getBuildType()`/`getCurrentTime()`/`getBuildTime()` helpers are left untouched (intentionally — minimal diff for clean rebase; the helpers are dead code in our build but harmless).

### 3-6. Four `BuildConfig.DEBUG` feature gates flipped to `true`

```diff
# app/src/main/java/io/github/muntashirakon/AppManager/main/MainActivity.java
-        finderMenu.setVisible(BuildConfig.DEBUG);
+        finderMenu.setVisible(true);
```

```diff
# app/src/main/java/io/github/muntashirakon/AppManager/logcat/LiveLogViewerFragment.java
-        crazyLoggerMenuItem.setEnabled(BuildConfig.DEBUG);
-        crazyLoggerMenuItem.setVisible(BuildConfig.DEBUG);
+        crazyLoggerMenuItem.setEnabled(true);
+        crazyLoggerMenuItem.setVisible(true);
```

```diff
# app/src/main/java/io/github/muntashirakon/AppManager/scanner/ScannerViewModel.java
         mLibraryClassesLiveData.postValue(libraryInfoList);

-        if (BuildConfig.DEBUG) {
-            mMissingClassesLiveData.postValue(missingLibs);
-        }
+        mMissingClassesLiveData.postValue(missingLibs);
     }
```

```diff
# app/src/main/java/io/github/muntashirakon/AppManager/ssaid/SettingsStateV26.java
-        mHistoricalOperations = BuildConfig.DEBUG ? new ArrayList<>(HISTORICAL_OPERATION_COUNT) : null;
+        mHistoricalOperations = new ArrayList<>(HISTORICAL_OPERATION_COUNT);
```

### `BuildConfig.DEBUG` sites NOT flipped, and why

Several other `BuildConfig.DEBUG`-gated sites are intentionally left alone. Don't reflexively flip them on rebase:

- `app/.../logs/Log.java`, `logs/Logger.java`, `logcat/LogViewerRecyclerAdapter.java`, `ipc/RemoteShellImpl.java`, `utils/BinderShellExecutor.java`, `servermanager/AssetsUtils.java`, `AppManager.java` (`Shell.enableVerboseLogging`) — pure verbose-logging gates. Flipping them would spam logcat, slow execution, and risk info disclosure in logs. Not user-visible features.
- `dex/DexUtils.java`, `dex/DexClasses.java` — `mOptions.debugInfo` / `setDebugInfo(BuildConfig.DEBUG)`. Adds debug info to generated DEX output. Bloats APK. No user-visible feature.
- `servermanager/ServerConfig.java` (`boolean force = BuildConfig.DEBUG`) — forces re-copy of the privileged-helper JAR every `init()`. Dev iteration aid, not a feature.
- `io/LocalFileOverlay.java` (`appId = "io.github.muntashirakon.AppManager" + (BuildConfig.DEBUG ? ".debug" : "")`) — defensive fallback for reflection failure on `BuildConfig`. The reflection succeeds in our build (namespace unchanged → BuildConfig class found at expected path), so this is dead code. Don't touch it; modifying it would conflict on rebases for no benefit.
- `self/life/BuildExpiryChecker.java:116` — this is the *other* `BuildConfig.DEBUG` site in BuildExpiryChecker, used inside `getBuildType()` which we made dead by collapsing `buildExpired()`. Leave it.

## Commit 2 — `Enable Gradle configuration cache (server, docs, app buildTime)`

Four files. Enables `org.gradle.configuration-cache=true` and refactors the three sites that were CC-incompatible. With this commit, no-op rebuilds drop from minutes to ~1 second ("Configuration cache entry reused."). Upstream-clean — this could be sent as a PR to MuntashirAkon; `InjectedExecOps` was already in use, suggesting partial CC migration that just was not finished.

### 1. `gradle.properties` — enable CC

```diff
-# org.gradle.unsafe.configuration-cache=true
+org.gradle.configuration-cache=true
```

The `unsafe.` prefix was the Gradle 6.6 spelling when CC was experimental. Modern (Gradle 7.5+) property name drops `unsafe.`.

### 2. `server/build.gradle` — capture project/android values at config time

Inside `android.libraryVariants.configureEach { variant -> ... }`, BEFORE `tasks.register(...)`:

```groovy
// Capture project/android references at configuration time (configuration-cache requirement
// — Task.project / android.* access at execution time is forbidden).
String capturedRootDir = project.rootDir.absolutePath
File capturedRootDirFile = project.rootDir
String capturedSdkDirPath = android.sdkDirectory.path
String capturedBuildToolsVersion = android.buildToolsVersion
int capturedTargetSdk = target_sdk
```

Then inside `doLast`: replace `project.rootDir.absolutePath` → `capturedRootDir`, `"${android.sdkDirectory.path}/platforms/android-${target_sdk}/..."` → `"${capturedSdkDirPath}/platforms/android-${capturedTargetSdk}/..."`. Inside `injected.execOps.exec { ... }`: `workingDir = project.rootDir` → `workingDir = capturedRootDirFile`; `executable = file("${android.sdkDirectory.path}/build-tools/${android.buildToolsVersion}/d8")` → `executable = new File("${capturedSdkDirPath}/build-tools/${capturedBuildToolsVersion}/d8")`.

Same pattern for `cleanupServerJars`: capture `rootProject.projectDir.absolutePath` outside `doLast`, replace `file("...")` with `new File("...")` inside.

### 3. `docs/build.gradle` — same capture pattern, PLUS `cleanupDocs` deleteDir fix

`buildDocs`: capture `rootProject.projectDir.absolutePath` and `project.rootDir` at the top of the `tasks.register` block. Use captured locals inside `doLast` and the `injected.execOps.exec` configuration. `cleanupDocs`: capture `rootProject.projectDir.absolutePath`, use `new File(...)` instead of `file(...)`.

**Plus a non-obvious CC bug we discovered the hard way:** upstream's `cleanupDocs` task's `doLast` block calls a *script-level* helper `def deleteDir(File dir) { ... }` defined elsewhere in `docs/build.gradle`. That works fine without CC because Groovy resolves script-level methods via the script binding at execution time. **With CC enabled, the `doLast` closure is serialized and the script binding is lost at replay time**, producing `Could not find method deleteDir() for arguments [...] on task ':docs:cleanupDocs' of type org.gradle.api.DefaultTask` on the first build attempt with CC active.

Fix: replace the call `deleteDir(file)` with Groovy's built-in `file.deleteDir()` — that's the `java.io.File.deleteDir()` GDK extension method, which is part of Groovy's runtime metaclass system and IS accessible from inside a CC-restored closure (unlike script-level `def` methods). Then delete the now-unused `def deleteDir(File dir) { ... }` helper from the script.

```diff
 tasks.register('cleanupDocs') {
     String capturedRootProjectDir = rootProject.projectDir.absolutePath
     doLast {
         File file = new File("${capturedRootProjectDir}/docs/src/main/res")
         if (file.exists()) {
-            deleteDir(file)
+            file.deleteDir()
         }
     }
 }

-def deleteDir(File dir) {
-    if (dir != null && dir.isDirectory()) {
-        String[] children = dir.list()
-        if (children == null) return false
-        for (String child : children) {
-            boolean success = deleteDir(new File(dir, child))
-            if (!success) return false
-        }
-        return dir.delete()
-    } else if (dir != null && dir.isFile()) {
-        return dir.delete()
-    } else return false
-}
```

**General lesson for all `doLast` closures under CC:** script-level `def fn(...)` methods are NOT accessible inside CC-restored closures. If a `doLast` calls a custom helper, either inline the helper logic into the closure, or replace the call with a GDK extension method on the object (the latter survives CC because Groovy's metaclass system serializes correctly). On future master rebases, if MuntashirAkon introduces new `doLast` blocks that call script-level helpers, expect them to break under CC and apply the same fix.

### 4. `app/build.gradle` — `buildTime()` uses `providers.exec`

```diff
 def buildTime() {
-    var commitTime = "git show --no-patch --format=%ct000".execute([], project.rootDir).text.trim()
-    if (isDigitsOnly(commitTime)) {
-        return Long.parseLong(commitTime)
+    // Configuration-cache-compatible external process: providers.exec is tracked as a CC fingerprint input,
+    // unlike Groovy's String.execute() which is forbidden at configuration time.
+    try {
+        var execOutput = providers.exec {
+            commandLine 'git', 'show', '--no-patch', '--format=%ct000'
+            workingDir = rootProject.projectDir
+        }
+        var commitTime = execOutput.standardOutput.asText.get().trim()
+        if (isDigitsOnly(commitTime)) {
+            return Long.parseLong(commitTime)
+        }
+    } catch (Exception e) {
+        // fall through to system time
     }
     println("Using system time as the build time.")
     return System.currentTimeMillis()
 }
```

Groovy's `String.execute()` at configuration time is the classic "external process started during configuration" CC violation — output isn't tracked, so CC can't invalidate when the git commit changes. `providers.exec` (Gradle 7.5+) IS tracked: command line + working dir + output are all CC fingerprint inputs.

Note: `BUILD_TIME_MILLIS` is only consumed by `BuildExpiryChecker.getBuildTime()`, which we already neutered in commit 1 — so the value is functionally dead in our build. We fix it anyway for upstream-cleanliness.

### Validation

After applying commit 2 and rebuilding from scratch:

1. First build prints `Configuration cache entry stored.` at the end. `.gradle/configuration-cache/` now contains a UUID-named subdirectory.
2. Second `./gradlew :app:assembleRelease` (no source changes) prints `Reusing configuration cache.` at the top and `BUILD SUCCESSFUL in ~1s`.

If a future upstream change introduces a new CC violation (some new task that touches `project` at execution time), the symptom is `<N> problems were found storing the configuration cache.` followed by file:line references. Fix shape is always the same — capture at config time, use captured local at execution time, replace `file(...)` with `new File(...)`.

## Commit 3 — `Fix cleanupDocs CC regression: use File.deleteDir() GDK extension`

One file, corrective hotfix on top of commit 2. Already described in detail in the "Commit 2 — section 3. docs/build.gradle" subsection above (the "Plus a non-obvious CC bug" paragraph).

## Commit 4 — `Add custom build numbering and profile-filter negation`

Ten files. Two logically distinct features bundled into one commit because they shipped together.

### Versioning system

Adds a custom build-number suffix that appears in both `versionName` (`4.0.5+N`) and `versionCode` (4450000+N) so each rebuild is identifiable and registered by Android's update-detection as an upgrade over the previous custom build.

**`gradle.properties` additions:**

```properties
customBuildNumber=1
customBaseVersionName=4.0.5
customBaseVersionCode=445
```

`customBuildNumber` advances on every dev build via `tools/bump-build.sh`. `customBaseVersion*` mirror the current upstream `defaultConfig` values — update them by hand when adopting a new upstream base (e.g. when MuntashirAkon ships 4.0.6 / versionCode 446).

**`app/build.gradle` changes** — at script level, BEFORE `android {}`:

```groovy
def customBuildNumber = (project.findProperty('customBuildNumber') ?: '0') as Integer
def customBaseVersionName = project.findProperty('customBaseVersionName') ?: '4.0.5'
def customBaseVersionCode = (project.findProperty('customBaseVersionCode') ?: '0') as Integer
```

Inside `defaultConfig {}`:

```groovy
versionCode customBaseVersionCode * 10000 + customBuildNumber
versionName "${customBaseVersionName}+${customBuildNumber}"
```

**Why the `def`s are at script level, NOT inside `android {}`:** Groovy DSL scoping for nested closures inside the `android` extension is unreliable — variables declared with `def` inside the `android` closure body can fail to be visible to `defaultConfig {}`'s nested closure under some Groovy versions / CC configurations. Script-level declarations are unambiguously visible to every nested DSL block. *We tripped over this directly: first attempt had the defs inside `android {}` and the build silently used pre-CC-cached compilation of an old AndroidManifest with versionCode=445 / versionName=4.0.5. Surface symptom: APK with correct filename but wrong internal version.*

**Why `* 10000`:** leaves room for 9999 customization builds between upstream base bumps, while keeping our versionCode strictly above upstream's so the custom build is always recognized as newer than the equivalent official release.

**`tools/bump-build.sh` (new file, executable bit set):** reads `customBuildNumber` from `gradle.properties`, increments by 1, writes back, prints the new `<base>+<N>` to stdout. Used by the build pipeline as `new_ver=$(tools/bump-build.sh)`. Counter is monotonic — gaps from aborted builds are harmless. The committed value reflects the value at last push; future builds resume from there.

### Profile-filter negation

Adds a "Negate" `MaterialCheckBox` alongside the existing "Profile name" spinner in the main list filter dialog. When checked, the apps list shows apps NOT in the selected profile (instead of apps IN the selected profile).

Files:

- `app/src/main/res/layout/dialog_list_options.xml` — wraps the spinner in a horizontal `LinearLayoutCompat` with the checkbox to its right (`layout_weight=1` on the spinner, `wrap_content` on the checkbox).
- `app/src/main/res/values/strings.xml` — new `filter_profile_negate` string "Negate" next to the existing `input_profile_name`.
- `app/src/main/java/io/github/muntashirakon/AppManager/misc/ListOptions.java` — declares `protected MaterialCheckBox profileNegateCheckbox;`, looks it up in `onViewCreated`, toggles visibility with the spinner.
- `app/src/main/java/io/github/muntashirakon/AppManager/main/MainListOptions.java` — wires `setOnCheckedChangeListener` to call `viewModel.setFilterProfileNegate(isChecked)`; restores its state after the profile-list async load completes.
- `app/src/main/java/io/github/muntashirakon/AppManager/main/MainViewModel.java` — adds `mFilterProfileNegate` field with setter, getter, persistence; modifies `filterItemsByFlags()` to compute a `HashSet<String> excludePackages` set when negate is on (from `AppsProfile.packages` via `Collections.addAll(set, packages)` for the array, or by running the `AppsFilterProfile`'s embedded `FilterItem.getFilteredList(candidates)` to collect matched package names), then skips matching items in the result loop. *Watch the array-vs-collection type when adding `addAll`: `AppsProfile.packages` is `String[]`, not `List<String>`, so use `Collections.addAll(target, array)` — first attempt used `target.addAll(array)` which doesn't compile.*
- `app/src/main/java/io/github/muntashirakon/AppManager/utils/AppPref.java` — new `PREF_MAIN_WINDOW_FILTER_PROFILE_NEGATE_BOOL` enum value with `false` default (in the false-cluster of the case fall-through).
- `app/src/main/java/io/github/muntashirakon/AppManager/settings/Prefs.java` — new `Prefs.MainPage.getFilteredProfileNegate()` and `setFilteredProfileNegate(boolean)` methods alongside the existing profile-name pair.

## Commit 5 — `Fix mergeAssets ordering: explicit dependsOn on :server:create*ServerJars`

One file, second corrective hotfix on top of commit 2 (after commit 3).

**The bug.** Upstream's `server/build.gradle` wires the server-runner JAR-creation task (which writes `am.jar` and `main.jar` into `app/src/main/assets/` at build time) only as a finalizer of the server module's Java-compile task:

```groovy
javaCompileProvider.get().finalizedBy(jarTask)
```

That's fine under standard Gradle scheduling, but under CC + parallel scheduling (enabled by commit 2), `:app:mergeReleaseAssets` can run before that finalizer completes. The asset directory is snapshotted while the JARs are still being written (or haven't been written at all on incremental builds where the compile is up-to-date), and the resulting APK is missing them.

**Runtime symptom.** First time the app's `ServerConfig.init()` runs:

```
E Ops : java.io.FileNotFoundException: am.jar
E Ops :   at io.github.muntashirakon.AppManager.servermanager.ServerConfig.init(ServerConfig.java:61)
```

Line 61 is `AssetsUtils.copyFile(context, Constants.JAR_NAME, SERVER_RUNNER_JAR[0], force)`, which calls `context.getAssets().openFd("am.jar")` — and that throws immediately when the asset isn't packaged. The exception is caught by `MainPreferencesViewModel.loadCustomCommands()`, which `postValue(null)`s both `mCustomCommand0` and `mCustomCommand1` LiveData fields. The UI surface is the **Settings → Mode of operation → Custom command** page: the two code boxes that should show `sh /storage/emulated/0/Android/data/shiroikuma.appmanager/cache/run_server.sh <port> <token>` (and the equivalent under `/data/user_de/0/.../cache/`) stay blank.

**Diagnostic that confirms it.** Compare `app/src/main/assets/` (which DOES contain `am.jar` and `main.jar` after build) to the APK's `assets/` (which does NOT). The JARs are written *eventually*, just not in time for `mergeAssets` to pick them up.

**The fix** — added at the end of the `android {}` block in `app/build.gradle`:

```groovy
android.applicationVariants.configureEach { variant ->
    String variantName = variant.buildType.name.capitalize()
    variant.mergeAssetsProvider.configure {
        dependsOn ":server:create${variantName}ServerJars"
    }
}
```

This adds an explicit `dependsOn` from each application variant's `mergeAssets` task to the corresponding server JAR-creation task (`:server:createReleaseServerJars`, `:server:createDebugServerJars`, etc.). Gradle's task scheduler then guarantees the JARs are written before being snapshotted, AND treats the JAR-creation task as a required upstream of asset merging — so it runs reliably on every build, not just when its finalizer-anchor compile task happens to run.

**Verification step** — added to the build pipeline:

```bash
unzip -l "$UNSIGNED_APK" | grep -E 'am\.jar|main\.jar'
```

After a successful build this should list both `assets/am.jar` and `assets/main.jar`. If it doesn't, the fix isn't in place or the wiring is broken for the variant being built.

**General lesson.** Under CC + parallel scheduling, `finalizedBy` is too loose for ordering between sibling modules. Producer tasks need explicit `dependsOn` from the actual consumer task in the downstream module. Upstream gets away with `finalizedBy` because they don't enable CC; we enabled it in commit 2, which is exactly when this latent bug became actual.

## Commit 6 — `Add main list freeze indicator with auto-refresh after freeze/unfreeze`

Seven files. The visual layer marks every frozen app in the main package list, and the auto-refresh layer makes sure it stays current.

### What "frozen" means in this commit

All three mechanisms AppManager's freeze action can use: PM-disabled (`pm disable`), suspended (`pm suspend`), and hidden (`pm hide` → `PRIVATE_FLAG_HIDDEN`). The list-view `item.isFrozen` field is the union of all three — same definition that `FreezeUtils.isFrozen(ApplicationInfo)` returns.

### Visual indicators

Three reinforcing channels, color-blind safe, recyclable-state-aware:

- **Snowflake badge** under the app icon. New 16dp `ImageView` `@id/freeze_indicator` in the icon column of `app/src/main/res/layout/item_main.xml`, between the existing icon and the backup_indicator. Source vector `app/src/main/res/drawable/ic_snowflake_24dp.xml` is the Material Symbols `ac_unit` glyph, tinted `?attr/colorPrimary`. Visibility `gone` by default; adapter switches to `VISIBLE` for frozen items.
- **Italic label.** Adapter calls `holder.label.setTypeface(null, item.isFrozen ? Typeface.ITALIC : Typeface.NORMAL)`. `null` as first arg keeps the default typeface family.
- **Dimmed icon.** Adapter calls `holder.icon.setAlpha(item.isFrozen ? 0.5f : 1.0f)`. Matches the visual convention every Android launcher uses for hidden/suspended apps.

Critical recycling rule: because ViewHolders are reused, EVERY one of those three properties must be set in BOTH the frozen and not-frozen branches. The pattern is `setX(isFrozen ? frozenValue : defaultValue)` — never `if (isFrozen) setX(...)` without an else.

### Live PM enrichment (the data source)

The original cached path (`!app.isEnabled` from the DB) was wrong because the cache lags reality in multiple ways: AppManager wasn't running when an external freeze happened; the freeze mechanism didn't fire `PACKAGE_CHANGED` (true for suspend / hide on some OEMs); the broadcast handler entered through `ACTION_DB_PACKAGE_ALTERED` which doesn't refresh from PM; or PM hadn't yet propagated when `updateApplications()` queried it.

Fix is applied at BOTH places where `ApplicationItem` instances are populated:

- **`PackageUtils.getInstalledOrBackedUpApplicationsFromDb`** (the swipe-to-refresh + initial-load path): one bulk `pm.getInstalledApplications(MATCH_DISABLED_COMPONENTS | MATCH_UNINSTALLED_PACKAGES)` call at the top of the method, results stored in a `Map<String, ApplicationInfo>`, then per-row override of `item.isDisabled` and `item.isFrozen` from the live data via `FreezeUtils.isFrozen(liveAi)`.
- **`MainViewModel.getNewApplicationItem`** (the per-package broadcast-handler refresh path): per-package live `pm.getApplicationInfo(packageName, ...)` query in the same hot loop. Slightly more binder calls than the bulk path, but the broadcast handler runs for one or a few packages at a time, so cost is fine.

Both paths fall back to the cached `app.isEnabled` if the live query throws (package not installed for the current user, permission denied, etc.). The fallback is intentional — better stale than missing — but in practice the fallback rarely fires for visible-list apps.

### Auto-refresh after freeze/unfreeze

`AppInfoFragment.doFreeze()` and `doUnfreeze()` now call `BroadcastUtils.sendPackageAltered(requireContext(), new String[]{mPackageName})` after the freeze action returns. That fires AppManager's custom `ACTION_PACKAGE_ALTERED` broadcast, which `MainViewModel.updateInfoForPackages` listens for and which causes a fresh `getNewApplicationItem` call for the affected package. Since `getNewApplicationItem` now uses live PM enrichment (see above), the resulting `item.isFrozen` is correct.

Why not rely on Android's own `Intent.ACTION_PACKAGE_CHANGED`: it's unreliable for the suspend and hide freeze methods, and on some OEMs doesn't fire at all even for disable. The explicit in-process broadcast bypasses that entire variability.

### File list

- `app/src/main/res/layout/item_main.xml` — adds `xmlns:app=...` to the card root and the new freeze-indicator `ImageView`.
- `app/src/main/res/drawable/ic_snowflake_24dp.xml` — new vector drawable.
- `app/src/main/java/io/github/muntashirakon/AppManager/main/ApplicationItem.java` — new `public boolean isFrozen` field with javadoc.
- `app/src/main/java/io/github/muntashirakon/AppManager/main/MainRecyclerAdapter.java` — `Typeface` import, `freezeIndicator` ViewHolder field + `findViewById`, three-channel bind logic.
- `app/src/main/java/io/github/muntashirakon/AppManager/main/MainViewModel.java` — `FreezeUtils` import, live PM enrichment in `getNewApplicationItem`.
- `app/src/main/java/io/github/muntashirakon/AppManager/utils/PackageUtils.java` — bulk live PM map at the top of `getInstalledOrBackedUpApplicationsFromDb`, per-row override.
- `app/src/main/java/io/github/muntashirakon/AppManager/details/info/AppInfoFragment.java` — `BroadcastUtils` import, `sendPackageAltered` call in both `doFreeze` and `doUnfreeze`.

## Commit 7 — `Add 'Skip freeze method dialog' preference under Settings -> Rules`

Five files. Adds a `SwitchPreferenceCompat` to **Settings → Rules** right below the existing "Default freezing method" preference. When checked, tapping Freeze on an App-info screen skips the method-picker dialog and applies the freeze directly using the resolved method (per-app stored method if remembered, otherwise the global default). Bulk freeze dialogs (`MainActivity`, `DebloaterActivity`) are intentionally **not** affected because those dialogs also serve as the Unfreeze entry point for multi-selection — skipping them would lose Unfreeze with no replacement.

### Plumbing

The end-to-end plumbing of a new boolean preference in AppManager touches four layers:

1. **`AppPref.java` — register the enum value.** Naming convention is `PREF_<NAME>_<TYPE>` where `<TYPE>` is one of `STR / INT / LONG / FLOAT / BOOL`. Our new key: `PREF_SKIP_FREEZE_METHOD_DIALOG_BOOL`. Add it next to `PREF_FREEZE_TYPE_INT` (alphabetically/topically). Then add the same enum value to the appropriate default-value switch case — for our boolean with default false, that's the false-cluster around the existing `PREF_MAIN_WINDOW_FILTER_PROFILE_NEGATE_BOOL` line.

2. **`Prefs.java` — add typed accessors** inside the relevant nested class (`Prefs.Blocking` for our freeze-related pref):

   ```java
   public static boolean getSkipFreezeMethodDialog() {
       return AppPref.getBoolean(AppPref.PrefKey.PREF_SKIP_FREEZE_METHOD_DIALOG_BOOL);
   }
   public static void setSkipFreezeMethodDialog(boolean skip) {
       AppPref.set(AppPref.PrefKey.PREF_SKIP_FREEZE_METHOD_DIALOG_BOOL, skip);
   }
   ```

3. **`preferences_<screen>.xml` — wire the UI.** For ours: `app/src/main/res/xml/preferences_rules.xml`. A standard `SwitchPreferenceCompat` with `app:key="skip_freeze_method_dialog"` is enough — no manual `RulesPreferences.java` binding required.

4. **`strings.xml` — title + description strings.** For ours: `pref_skip_freeze_method_dialog` and `pref_skip_freeze_method_dialog_description`. The summary explicitly documents the bulk-dialog carve-out so users aren't surprised.

### Why no manual binding in RulesPreferences

`SwitchPreferenceCompat` works automatically with the existing `SettingsDataStore` (set in `RulesPreferences.onCreatePreferences` via `getPreferenceManager().setPreferenceDataStore(new SettingsDataStore())`). The XML `app:key` value flows through `SettingsDataStore.putBoolean(String key, ...)` → `AppPref.setPref(String key, ...)`, which resolves the string key back to the matching `PrefKey` enum value using a name-mangling convention:

```
enum name        PREF_SKIP_FREEZE_METHOD_DIALOG_BOOL
strip PREF_      SKIP_FREEZE_METHOD_DIALOG_BOOL
strip _<TYPE>    SKIP_FREEZE_METHOD_DIALOG
lowercase        skip_freeze_method_dialog                <- XML key
```

The conversion is done once at class-load time in `AppPref.PrefKey`'s static initializer (`keyStr.substring(PREF_SKIP, typeSeparator).toLowerCase(...)` where `PREF_SKIP = 5` is the length of the `PREF_` prefix). So the XML key must EXACTLY match this derived form — `skip_freeze_method_dialog`, not `skipFreezeMethodDialog`, not `pref_skip_freeze_method_dialog`. Don't add a manual binding in `RulesPreferences.onCreatePreferences` unless the preference needs custom click-handling beyond simple persist-the-boolean (which `Preference` typed entries like the freeze-method picker DO need — those use `setOnPreferenceClickListener`).

### Behavioral hook

The actual skip happens in `AppInfoFragment.java`, in the `mMainModel.getFreezeTypeLiveData().observe(...)` lambda that previously called `showFreezeDialog` unconditionally. The branch is:

```java
if (Prefs.Blocking.getSkipFreezeMethodDialog()) {
    ThreadUtils.postOnBackgroundThread(() -> doFreeze(freezeTypeN, freezeType != null));
} else {
    showFreezeDialog(freezeTypeN, freezeType != null);
}
```

The second arg to `doFreeze` (`freezeType != null`) preserves the per-app "remember" semantics: if the app already has a stored freeze method, we keep it remembered; if it didn't, we don't introduce a new remember. The global default is reached via `Optional.orElse(Prefs.Blocking.getDefaultFreezingMethod())` a few lines above.

### File list

- `app/src/main/java/io/github/muntashirakon/AppManager/utils/AppPref.java` — `PREF_SKIP_FREEZE_METHOD_DIALOG_BOOL` enum entry, false-default case.
- `app/src/main/java/io/github/muntashirakon/AppManager/settings/Prefs.java` — `getSkipFreezeMethodDialog` / `setSkipFreezeMethodDialog` accessors.
- `app/src/main/res/xml/preferences_rules.xml` — `<SwitchPreferenceCompat>` entry.
- `app/src/main/res/values/strings.xml` — title + description strings.
- `app/src/main/java/io/github/muntashirakon/AppManager/details/info/AppInfoFragment.java` — skip branch in the freeze-type observer.

## Later commits (added after Commit 7 — summary)

Commits 1–7 above are documented in full. Subsequent `custom` commits from later sessions are summarized here; the precise diffs are recoverable from `git log`/`git show`, and the durable architecture for the larger ones is captured below.

- `096b3ba7e` — **Multi-profile filter**: include/exclude *sets* of profiles (replaces the single-profile negate from Commit 4). `MainViewModel` stores `LinkedHashSet` include/exclude in a private SharedPreferences (`am_main_page_profile_filter`); legacy single-profile migration kept. Picker button text shows `∈ A,B ∉ C`.
- `2bb9b651b` — **Backup details inline** in the list row's right column (version/date/time in yellow; tap = new backup, long-press = restore/delete), dropping the left-side Backup label.
- `a460bfe56` — **Customisable bottom selection toolbar**: reorder/hide actions from Settings → "Main selection toolbar" (`MainToolbarPrefs`/`MainToolbarPreferences`, comma-delimited order in SharedPreferences `am_main_toolbar`); long-press any toolbar button opens the editor.
- `90ce9b798` — **Refresh profile membership** in the list after add-to-profile completes (`AddToProfileDialogFragment.OnProfileChangedListener` → `MainRecyclerAdapter.reloadProfileMembership()`; wired at the batch op + both per-row "+" pill sites).
- `df37328f1` — **Rename release label to `白い熊 App Manager`** (supersedes Commit 1's `App Manager Custom`) and **fix the Skip-freeze switch resetting to OFF on reopen** (explicit `setChecked(Prefs.Blocking.getSkipFreezeMethodDialog())` in `RulesPreferences`, mirroring `global_blocking_enabled`; root cause = `setPreferenceDataStore` attached *after* `addPreferencesFromResource`, so the inflate-time read hit the default SharedPreferences not AppPref — this is the binding caveat from Commit 7's "Why no manual binding" note, which only bites when the data store is attached late).
- `9edfdcb14` — **Profile filter picker tri-state rows**: custom `+`/`-` pill TextViews (not Material Chips), whole-row tap cycles neutral → include → exclude → neutral, painted with `GradientDrawable`/`LayerDrawable` (yellow fill / thick border / double-bordered pill). Yellow = `R.color.theme_bright_yellow`.
- `cc7dfa54c` — **Configurable per-element fonts (increment 1)** — see the Fonts section below.
- `49a343e7e` — **Brighter, fully-opaque orange** `theme_bright_orange` `#FF7A1A` (the libcore `tracker` colour is 50%-alpha at night, the dim/illegible cause); `mColorOrange` repointed to it so every fork orange use (system label, system card stroke, readable-logs date, shared-user-ID, cleartext-SDK highlight) becomes it; app-ID coloured bright orange when `trackerCount > 0` else **yellow** (was grey).
- `6251cc1c6` — **Build-log hygiene** (see that section below).
- `5538bf3d7` — **Configurable fonts increment 2**: the remaining main-list surfaces wired — VERSION/INSTALL_DATE/SDK/SIGNATURE/BACKUP_INFO.
- `8616d18ff` — **List refresh on resume after a font change** (see the Fonts section's "live update" note).
- `da254a20a` — **Fonts settings UI reshaped to the HandyRSS treatment** (underlined headers, indented controls, left-aligned size value, font-preview family picker; see the Fonts section).
- `dbce05a12` — **Bigger main-list icons + centered freeze indicator.** New dedicated dimen `main_list_icon_size` = `60dp` (40dp × 1.5) used only by `item_main.xml` for `icon_column` width and the `icon` width/height — the shared `icon_size` (40dp) is left alone so the other 7 layouts using it (app usage, running apps, debloater, several dialogs) are unaffected. The icon column was restructured: the `icon` sits at the top (`center_horizontal`, no weight) and the `freeze_indicator` (snowflake, 32dp) now lives in a `weight=1` `FrameLayout` with `layout_gravity="center"`, so it's centered horizontally and vertically in the whitespace under the icon. The left `backup_indicator` is unconditionally `GONE` in the adapter (`onBindViewHolder` ~line 573), so it never competes for that space. Note `dimens.xml` uses CRLF line endings — insert with care (a universal-newline read+write will flip the whole file to LF and bloat the diff).
- `0b460e615` — **Fonts: finish the main-list row + extend to App details.** Adds `UID` + `APP_TYPE` categories wired to `holder.userId` / `holder.isSystemApp` (completing the list row), and a new "App details" group with `DETAIL_LABEL`/`DETAIL_PACKAGE`/`DETAIL_VERSION` wired in `AppInfoFragment` (header `mLabelView`/`mPackageNameView`/`mVersionView`), with the unconditional `onResume` re-apply. See the Fonts section's "add a surface" + "two refresh idioms" notes.
- `55c629290` — **Copy displayed app IDs (top toolbar action).** New menu item `action_export_displayed_ids` (icon `ic_content_copy`, `showAsAction="ifRoom"`) inserted in `activity_main_actions.xml` immediately after `action_list_options`, so it sits between the list-options icon and the overflow `⋮` in the bar. Handled in `MainActivity.onOptionsItemSelected` → `copyDisplayedAppIds()`: pulls the displayed package names from the adapter's new `getDisplayedPackageNames()` (a synchronized snapshot of `mAdapterList`, i.e. post-search + post-filter, in display order), joins them one-per-line with `TextUtils.join` (not `String.join`, which is API 26 and minSdk is 21), writes via `ClipboardUtils.copyToClipboard` (no toast of its own; transparently spills to a FileProvider Uri for very large text), and flashes a count toast from the `copied_n_app_ids` plurals (`%1$d app ID(s) copied to clipboard`). Empty list → `no_apps_displayed_to_copy` toast. **Note: writing the primary clip from a foreground activity needs no IME** — the Android 10+ restriction is only on background clipboard *reads*, so the originally-feared dialog fallback was unnecessary. Icon choice: used the copy glyph `ic_content_copy` rather than `ic_file_export` (which in this app means file export, e.g. the selection-mode "export app list") to signal clipboard-copy semantics.
- `314013e12` — **Per-element colours, Stage 1 of 2 (main-list text).** The user chose **per-element/per-state independent colours** (option B): each element-state is its own settable colour defaulting to today's palette value, *not* a shared semantic palette. New `fonts/ColorPrefs.java` mirrors `FontPrefs` (dedicated `shiroikuma_colors` SharedPreferences; keys `color_<role>`; `isSet`/`getColor(ctx,key[,def])`/`setColor`/`reset`/`consumeChanged`). The crux is `ColorPrefs.defaultColor(ctx,key)` — a centralised switch returning the *original* hardcoded palette colour per role, so the adapter (which paints) and the settings UI (which shows the "Default" swatch) never disagree, and an **unset key reproduces the original appearance exactly** (0 is a valid colour, so "unset" is tracked by key presence, never a sentinel). Stage-1 roles (17): `LABEL_{FROZEN,SYSTEM,USER}`, `PACKAGE_{TRACKERS,NORMAL}`, `VERSION_{INACTIVE,NORMAL}`, `APPTYPE_{PERSISTENT,NORMAL}`, `DATE_{READABLE,NORMAL}`, `UID_{SHARED,NORMAL}`, `SDK_{CLEARTEXT,NORMAL}`, `SIGNATURE`, `BACKUP`. **Adapter** (`MainRecyclerAdapter`): added resolved `mc*` colour fields + `public void reloadColors()` (reads each role from `ColorPrefs` with the palette default; called in the constructor and from `MainActivity.onResume`); every main-list **text** `setTextColor` site now uses an `mc*` field instead of the literal palette field. SIGNATURE had **no** explicit colour originally, so it is applied **only if the user has set one** (`mcSignatureSet`) — leaving the theme default untouched otherwise. The non-text palette uses (card stroke `~403`, freeze snowflake `~461`, chips `~546`, add-pill `~572`) are deliberately **left for Stage 2**. **Settings UI** (`FontsPreferences`): `Cat` extended with a `ColorSpec[]` (a 2-arg `Cat` ctor keeps `DEFAULT` + the `DETAIL_*` group colour-less for now); each main-list element renders one tappable colour row per state (`view_color_row.xml`: swatch + label + value, value shows the hex or "Default"); the font **preview now reflects** the element's *primary* (first) colour role. New colour-picker dialog (`dialog_color_picker.xml` + `openColorPicker`): hex input (`#RRGGBB`/`#AARRGGBB`, parsed via `Color.parseColor`, live preview, invalid → toast) + a preset row (orange/yellow/ice-blue/green/magenta/secondary/white/black) + a **Reset to default** neutral button. Swatches are `GradientDrawable` rounded rects built in code (no drawable XML). **Live refresh**: `ColorPrefs.consumeChanged()` is consumed alongside `FontPrefs.consumeChanged()` in `MainActivity.onResume`; a colour change triggers `mAdapter.reloadColors()` + `notifyDataSetChanged()` (a font change still also `clearCache()`s). **Rename**: the Settings entry `pref_fonts` "Fonts" → **"UI colors & fonts"** (American spelling per the user; `&amp;` in XML) and its description now mentions colour. Stage 2 (next patch): the non-text indicators as their own colour group + the App-details header colours (`DETAIL_*`).
- `aa9603a64` — **Per-element colours, Stage 2 of 2 (non-text indicators + App-details header).** On top of `314013e12`. Adds 9 roles to `ColorPrefs`: indicators `STROKE_USER`(def yellow)/`STROKE_SYSTEM`(def orange), `FREEZE_FROZEN`(def ice-blue)/`FREEZE_THAWED`(def yellow), `CHIP`(def yellow), `ADDPILL`(def yellow); and App-details `DETAIL_LABEL`/`DETAIL_PACKAGE`/`DETAIL_VERSION` (def secondary, but **applied only if the user has set one**, like SIGNATURE — the headers had no explicit colour). **Adapter**: new resolved fields `mcStroke{User,System}`, `mcFreeze{Frozen,Thawed}`, `mcChip`, `mcAddPill` loaded in `reloadColors()`; the card-outline stroke (`item.isUser ? mcStrokeUser : mcStrokeSystem`), the freeze snowflake tint (`item.isFrozen ? mcFreezeFrozen : mcFreezeThawed`), the profile chips (text + stroke → `mcChip`), and the "+" add-pill (text + stroke → `mcAddPill`) all read the resolved fields. The shared `yellowList` ColorStateList was split into independent `chipStrokeList`/`addPillStrokeList` so chip and pill are independent. The ColorCodes-derived strokes (uninstalled/disabled) and the transparent stroke are left theme-driven on purpose — only the user/system palette strokes are exposed. **Settings UI** (`FontsPreferences`): a `Cat` may now have a **null font key** = a colour-only element; `bindElement` early-returns for those, hiding the font controls and rendering only colour rows. To support hiding, `view_font_element.xml` now wraps all font controls (family/weight/size/preview/slider) in a `@+id/font_controls` `LinearLayoutCompat`, with `color_rows` as its sibling. A new **"List indicators"** group holds four colour-only elements: Card outline (user/system), Freeze indicator (frozen/not-frozen), Profile chips, Add-to-profile (+) pill. The existing **App details** group's three header cats gained one colour role each. **AppInfoFragment**: `onResume` now also calls `applyDetailColor(view, key)` for the three header views (sets the colour only if `ColorPrefs.isSet`; the fragment is recreated per open, so an unset colour reverts to theme default on next open). No `MainActivity` change needed — `reloadColors()` already covers the new roles and the existing on-resume colour-change refresh repaints them.
- `d38d27490` — **Filter active-state icon + clear-all-filters toolbar action.** On top of `aa9603a64`. The `action_list_options` icon (the top-right "filter" affordance, `ic_list_status`) now shows a filter-active state: inactive = normal yellow tint; active = orange tint **plus a small dot badge** in the top-right corner (the user picked option B). Built in `MainActivity.updateFilterIcon(menu)` via a `LayerDrawable` of the mutated base icon + a `GradientDrawable` oval badge, positioned with `setLayerInset` (NOT `setLayerGravity`, which is API 23; minSdk is 21); called from `onPrepareOptionsMenu`, and the `getApplicationItems` observer now calls `invalidateOptionsMenu()` so the state re-evaluates after every refilter. A new **clear-all-filters** action (`action_clear_filters`, new drawable `ic_filter_remove` = a funnel-with-x, mdi `filter-remove` path) sits in the menu **between** `action_list_options` and `action_export_displayed_ids` (both `ifRoom`); the existing onCreate icon-tint loop tints it yellow like the others. `MainViewModel` gained `isFilterActive()` (true if `mFilterFlags != 0` OR either profile-filter set is non-empty OR `mSearchQuery` is non-empty) and `clearAllFilters()` (resets flag filters + both profile sets, persisting the empty profile state, + clears the search query, then one `filterItemsByFlags`; sort order left untouched). The `action_clear_filters` handler calls `clearAllFilters()`, syncs the SearchView UI text to empty (`setQuery("", false)`), `invalidateOptionsMenu()`, and toasts `filters_cleared`. Note: tapping a profile pill already filtered to just that profile (`setFilterProfileName` from the earlier chip work) — this patch is what makes that **visible** (the dot) and **reversible** (the clear button).
- `c2849617b` — **Backup directory: a fast filesystem override for the SAF backup volume.** On top of `d38d27490`. Adds a "Backup directory" preference **above** "Backup volume" in Settings → Backup/Restore. When set, it is a plain filesystem path (no SAF) used for **both backup and restore**, fully overriding the volume; clearing it falls back to the volume. The whole feature hinges on one chokepoint: `Prefs.Storage.getAppManagerDirectory()` is the single base-directory resolver that `BackupItems.getBaseDirectory()` calls for every backup and restore, so the override lives there — if `getBackupDirectory()` is non-empty, return `Paths.get(dir)` (mkdirs) directly as the base (AppManager then creates its `backups`/`apks`/`.nomedia` structure inside it), otherwise the original SAF-volume logic runs. Storage is a **dedicated SharedPreferences** (`am_backup_directory`, key `path`, "" = unset) read via `ContextUtils.getContext()` — deliberately NOT a new `AppPref.PrefKey` enum entry (that enum is the brittle parallel-array one and rebase-sensitive; the fork already sidesteps it for FontPrefs/ColorPrefs). New `Prefs.Storage` methods: `getBackupDirectory()`, `hasBackupDirectory()`, `setBackupDirectory(String)` (pass "" to clear). The picker is the user's chosen design (built-in filesystem browser, not SAF, not a typed path): `BackupRestorePreferences.showBackupDirectoryChooser()` builds a minimal browser in a `MaterialAlertDialogBuilder` over a `ListView` + `ArrayAdapter<String>`, navigating AppManager's `Path` API (`listFiles()` filtered to `isDirectory()`, `getParent()` for the ".." row, `getFilePath()` for the absolute path); starts at the current backup dir or `Environment.getExternalStorageDirectory()`; positive button "Use this directory" saves `current.getFilePath()`, neutral "Use backup volume" clears it, and the preference summary shows the path or `backup_directory_not_set`. FmActivity was evaluated as a reusable picker but it is a full file manager with no folder-pick-return mode, so a focused chooser was built instead.
- `7943bb1e3` — **Readable per-app backup layout (drop the `backups/<uuid>` scheme).** On top of `c2849617b`. The user wanted backups to land in human-readable per-app folders **directly inside** the chosen backup directory — `<base>/<packageName>/<userId[_name]>` — not the upstream v5 `<base>/backups/<uuid>` layout (no `backups/` intermediate, no cryptic UUID leaves). Changed in `BackupItems`: new helper `createPerPackageBackupPath(userId, backupName, packageName)` creates `<base>/<packageName>/<getV4BackupName(userId,backupName)>` with a `_N` collision suffix (a fresh dir each time, preserving "multiple backups" semantics); both v5 creators (`findOrCreateBackupItem`, `createBackupItemGracefully`) now call it instead of building `findOrCreateDirectory(BACKUP_DIRECTORY).findOrCreateDirectory(uuid)`. `BackupItem.getRelativeDir()` for v5 was generalized from `getV5RelativeDir(name)` (which hardcodes `backups/<name>`) to the generic `{parent}/{name}` form (`BackupUtils.getV4RelativeDir(name, parent)`) — this is **backward-compatible**: existing v5 backups at `<base>/backups/<uuid>` still yield `backups/<uuid>`, while new ones yield `<packageName>/<name>`, and both resolve through `findBackupItem(relativeDir)` / the DB. **Why it's safe:** `getV5RelativeDir` had exactly one caller (this one), `BACKUP_DIRECTORY` is only referenced by the now-bypassed write paths + `getV5RelativeDir`, nothing parses the leaf as a UUID, and `findAllBackupItems` already discovers any `<base>/<group>/<child>` two-level layout (so restore listing finds per-app backups). v5 **metadata file format is unchanged** (still `info_v5.am.json` etc.); only the directory layout changed. `apks/` and `.nomedia` still live at the base.
- `3d1fe3635` — **"Skip backup method dialog" setting + tappable Back-up affordance for no-backup apps.** On top of `7943bb1e3`. Two coupled requests. **(1)** A new `SwitchPreferenceCompat` "Skip backup method dialog" in Settings → Backup/Restore (right under "Backup volume"), modelled on the Rules "Skip freeze method dialog". When on, backing up an app that has **no existing backup** skips the "Backup options" picker and starts the backup immediately with the default flags. Storage is a dedicated SharedPreferences (`am_backup_options`, key `skip_method_dialog`) via `Prefs.Storage.getSkipBackupMethodDialog()`/`setSkipBackupMethodDialog()` — not the AppPref enum. The XML pref is `app:persistent="false"` and wired manually in `BackupRestorePreferences` (`setChecked` from Prefs + `setOnPreferenceChangeListener` → Prefs), bypassing the `SettingsDataStore`. The skip is implemented in the backup dialog itself: `BackupFragment.getInstance` gained a `soleBackupAction` arg (new `ARG_SOLE_BACKUP_ACTION`); when true **and** the toggle is on, `onViewCreated` calls `handleBackup(BackupFlags.fromPref())` and returns instead of building the flags picker — reusing all existing logic (multiple-backup name prompt, overwrite warning, `prepareForOperation`). `BackupRestoreDialogFragment.getBackupFragment(boolean)` passes `true` from the **backup-only** loaders (`loadSingleBackupFragment`, `loadMultipleBackupFragment`) and `false` from the backup+restore tab pagers, so the auto-start can never fire from the backup tab when restore is also offered. Because the dialog only enters a backup-only state when no backup exists, the overwrite warning never triggers there → immediate start. **(2)** In `MainRecyclerAdapter`, rows for **installed** apps with no backup now show a tappable "Back up" affordance (string `backup_tap_hint`, dimmed `alpha 0.6`, `mcBackup` colour) in the same right-column area where the three-line backup summary appears for backed-up apps; tapping calls new helper `openBackupModeDialog(item)` → `BackupRestoreDialogFragment.getInstance(single, MODE_BACKUP)`, which lands in the backup-only path and therefore honours the skip toggle (immediate backup) or shows the picker. The has-backup branch now resets `alpha` to 1f (recycled holders); the not-installed branch stays blank. The shared `showBackupRestoreDialogOrAppNotInstalled` helper was **not** repurposed (its no-backup→"app not installed" toast is correct for the genuinely-not-installed tap flows). **NOT DONE — request to colour the backup-done notification black/yellow:** that toast is a **system notification** (`NotificationProgressHandler` → `NotificationManagerCompat.notify`); Android/the ROM render notification text and background, so the app cannot force black/yellow. Full control would need a custom `RemoteViews` layout on the **shared** completion-notification builder (affects every AppManager notification, ROMs often re-wrap custom notifications) — **the user subsequently dropped this request from the backlog; not pursued.**
- `b7dd977ca` — **Remove the startup backup-volume check + make the whole no-backup backup column tappable.** On top of `3d1fe3635`.
- `fbad0707b` — **Settings export / import (App Manager's own settings, not app backups).** On top of `b7dd977ca`.
- `899ebd02e` — **Honor "Skip freeze method dialog" for the batch Freeze toolbar action.** On top of `fbad0707b`.
- `918cdd2f8` — **Batch reinstall (install-existing) for uninstalled system apps + dedicated batch unfreeze, both on the customizable selection toolbar.** On top of `899ebd02e`.
- `9f5187225` — **Rename the toolbar Freeze button + fix settings import completeness (toolbar/colors/fonts) + bundle custom font files.** On top of `918cdd2f8`.
- `808b94ceb` — **Themed toasts (black box / yellow text / yellow border) + first user-supplied Japanese translations.** On top of `9f5187225`. (1) The app's transient toast messages ("flashes" — the success/failure notices from app-info actions etc.) were the OS default white-box/black-text. All of `UIUtils.displayShortToast`/`displayLongToast`/`displayLongToastPl` (9 overloads) now route through a private `showThemedToast(CharSequence, duration)` that inflates a custom view (`R.layout.toast_shiroikuma` = a `TextView` with `@drawable/bg_toast_shiroikuma`) — black box, `@color/theme_bright_yellow` (#FFFF00) text, 2dp yellow border, 14dp corners. Literal colors (not `?attr`) because toasts inflate with the **application context**, which carries no theme (so `?attr/colorPrimary` wouldn't resolve). **Android-12+ caveat:** custom toast views are honored only while the app is in the **foreground** (all these fire from foreground user actions, so fine); background toasts silently fall back to the system text toast. `@SuppressWarnings("deprecation")` on the helper for `Toast.setView`. (2) Started a user-supplied Japanese (`values-ja`) translation set: `freeze` → 凍結, `unfreeze` → 融解 (these back the bottom-toolbar Freeze/Unfreeze buttons via `MainToolbarPrefs.titleForKey`, and apply consistently anywhere else those strings are used). Upstream `values-ja` had no `freeze`/`unfreeze` entries, so these are additions, not overrides. **Pattern for future translation batches: add `<string name="…">…</string>` to `app/src/main/res/values-ja/strings.xml` (under the "Fork: user-supplied Japanese translations" marker); the user supplies key→Japanese and where they see it.**
- `5f385328b` — **Profile-filter dialog: tap = In/Neutral, long-press = Not in.** On top of `808b94ceb`.
- `7990f17d1` — **Japanese translations: filter labels.** On top of `5f385328b`.
- `085ac84c7` — **Protected apps profile "必要": hard-block freeze + uninstall (with concrete warnings at every entry point).** On top of `7990f17d1`.
- `bd4ab5461` — **Fix "Add to profile" silently not persisting (reported "完了" but the app wasn't added).** On top of `085ac84c7`.
- `192614b37` — **Batch freeze/uninstall protected-app warning now names each app.** On top of `bd4ab5461`.
- `36148153d` — **Refresh the per-row profile pills after adding an app to a profile (and on pull-to-refresh/resume).** On top of `192614b37`. The main-list profile pills come from `MainRecyclerAdapter.mPackageToProfileNames`, built once by `loadProfileMembership()` (worker thread) at adapter construction — so after adding an app via the "+" dialog the pill didn't appear, and pull-to-refresh (which reloads `ApplicationItem`s but not this map) didn't fix it either; only recreating the activity (exit/re-enter) did. Fix: new public `MainRecyclerAdapter.reloadProfileMembership()` (re-runs `loadProfileMembership` on a background thread, then `notifyDataSetChanged`), called from `MainActivity.onRefresh()` (pull-to-refresh) and `onResume()`. For the immediate case (the "+" dialog is a `DialogFragment` and doesn't pause the activity, so `onResume` wouldn't fire), `AddToProfileDialogFragment` now broadcasts a Fragment Result (`RESULT_KEY = "add_to_profile_result"`, empty `Bundle`) on successful add via `getParentFragmentManager().setFragmentResult(...)`, and `MainActivity` registers a `setFragmentResultListener` (after adapter creation) that calls `reloadProfileMembership()` — so the pill appears right after "完了". `MainActivity.warnIfSelectionHasProtectedApps()` previously toasted a generic count ("%d protected app(s)… won't be changed"). It now snapshots the selection (`Map<String,ApplicationItem>` via `getSelectedPackages()`, copied into a `LinkedHashMap` so the background thread reads a stable view), collects the **labels** of the protected packages, and toasts the concrete message: one app → reuses `protected_profile_block` (`%s` = the label), multiple → `protected_profile_block_multiple` repurposed from a `%d` count to a `%s` list ("These apps are protected by the 「必要」 profile and can't be frozen or uninstalled: name1、name2"; JA: 次のアプリは…：%s), names joined with 「、」. (The BatchOpsService result notification still independently shows its generic "N app not frozen" — that's a separate code path; this gives the concrete naming the user asked for.) The "+" pill in the main list → `AddToProfileDialogFragment` wrote each selected profile back to `ProfileManager.findProfilePathById(profile.profileId)` = `<profilesDir>/<profileId>.am.json`, which is only correct while a profile's on-disk filename stem equals its stored `profileId`. If that ever diverges, `openOutputStream` (truncate mode) writes/creates a *wrongly-named* file while the real profile is untouched — and since the loop's `isSuccess` started `true` and nothing threw, it still toasted `R.string.done` ("完了"). Three robustness fixes (all in upstream-origin files, but the bug surfaces with the user's `必要` profile): (1) new `ProfileManager.resolveExistingProfilePath(profileId)` returns the canonical file if it exists, else **scans** the profiles dir for the file whose deserialized `profileId` matches — so the write always lands in the real profile regardless of filename/id divergence; (2) the add now reloads the profile fresh from that resolved path, appends, writes, then **re-reads and verifies** every requested package is actually present, reporting `R.string.done`/`R.string.failed` honestly (no more silent false success; empty selection also reports failure rather than success); (3) calls `ProtectedAppsProfile.invalidate()` after a successful add since 必要 membership may have changed (its 3s cache would otherwise lag). LESSON: a profile's filename stem is *assumed* to equal its stored `profileId` but that invariant isn't guaranteed; write profiles back via `resolveExistingProfilePath`, not a name-built path, and verify writes that report success. New `profiles/ProtectedAppsProfile.java`: any app that is a member of an apps profile named `必要` (constant `PROTECTED_PROFILE_NAME`) is refused freezing and uninstalling. `getProtectedPackages()` reads `ProfileManager.getProfiles(AppsProfile.PROFILE_TYPE_APPS)` (generic inference to `List<AppsProfile>`, same pattern as `AddToProfileDialogFragment`) and unions `profile.packages` for every profile whose `name` matches; result cached for 3s (so a batch op that queries per-package doesn't re-parse the profiles each time) and **fails open** (if profiles can't be read, nothing is treated as protected — a stuck block refusing every freeze/uninstall would be worse). Enforcement is at the two low-level chokepoints so no UI path (single-app, batch, profile application, future code) can bypass it: (1) `FreezeUtils.freeze(pkg, userId, type)` — the 2-arg overload delegates to this 3-arg core, and all freeze methods (disable/suspend/hide) run after the guard — throws `RemoteException` if protected (callers already handle it: `AppInfoFragment.doFreeze` catches `Throwable`→`failed_to_freeze` toast, batch handlers catch `Throwable`→failedPackages); (2) `PackageInstallerCompat.uninstall(pkg, userId, keepData)` returns `false` at the very top if protected (before any work/accessibility dialog, so even the unprivileged accessibility-driven uninstall path in `opUninstall` is blocked — it only auto-confirms a dialog that now never appears). Messaging: concrete user-facing warnings in addition to the hard backstop — new strings `protected_profile_block` (`%s` app label) and `protected_profile_block_multiple` (`%d` count) in `values/` + `values-ja/` (JA: 「必要」プロファイルで保護されているため…). Single-app freeze (`AppInfoFragment.doFreeze`, top) and single-app uninstall (the uninstall dialog's positive button, covering both the size==1 and multi-user branches, keyed on `mPackageName`) short-circuit with the concrete toast before attempting. The main-list per-row freeze icon (`MainRecyclerAdapter.toggleFreeze`, freeze direction only — unfreeze stays allowed) likewise pre-checks and shows the concrete toast instead of the generic `failed_to_freeze` its catch block would otherwise show. Batch freeze + uninstall (`MainActivity` `action_freeze_unfreeze`/`action_uninstall`) call a new `warnIfSelectionHasProtectedApps()` that counts protected packages in the selection off the main thread (profiles are read from disk) and toasts the `_multiple` string; the op still dispatches and the chokepoints refuse the protected ones, so the rest of the batch proceeds. LESSON: freeze funnels through `FreezeUtils.freeze` (3-arg core) and uninstall through `PackageInstallerCompat.uninstall` — these are the two chokepoints for app-protection guards. Added to `values-ja/strings.xml` (under the "Fork: user-supplied Japanese translations" marker): `filter_frozen_apps` → 凍結済みアプリ, `filter_unfrozen_apps` → 融解済みアプリ, `filter_force_stopped_apps` → 終了済みアプリ (the "Frozen apps"/"Unfrozen apps"/"Stopped apps" filter labels). Strings-only. The Filter-by-profile picker (`MainListOptions.openProfileFilterPicker`, rows from `dialog_profile_filter_picker_row`, tri-state `st[0]` 0=neutral/1=include/2=exclude backed by the `include`/`exclude` working sets) previously cycled the **whole-row tap** through neutral→include→exclude→neutral. Changed per user request: row tap now `st[0] = (st[0] == NEUTRAL) ? INCLUDE : NEUTRAL` (neutral↔include, and exclude→neutral on tap), and a new `row.setOnLongClickListener` sets `st[0] = EXCLUDE` (returns `true` to swallow the gesture so the tap handler doesn't also fire). The explicit `+`/`-` pills are unchanged (still set their own polarity directly; long-pressing a pill hits the pill's own click, not the row long-press, since the pill consumes the touch). Single file.
- `a26808ec5` — **Version-aware main-list icon cache (fix stale icons after reinstall).** Reinstalling an APK with the same package name but a **new icon** kept showing the **old** icon in the main list (the app's own icon included), until a manual cache-clear or the 7-day GC. Root cause: `self/imagecache/ImageLoader` has a two-layer icon cache — an in-memory `LruCache<String,Bitmap>` (300) **plus** an on-disk PNG per tag at `FileUtils.getCachePath()/images/<tag>.png` (`ImageFileCache`, entries valid only if file mtime is within 7 days; `clear()`/`close()` only GC entries **older** than 7 days, so it's never a full wipe) — and both layers are keyed by a tag with **no version awareness**. The main list keyed each row by the **bare `packageName`**, so a reinstall hit the stale cached entry. **Two-part fix.** (1) **Versioned key**: new `ImageLoader.versionedTag(packageName, lastUpdateTime)` → `"pkg@time"`; the main list (`MainRecyclerAdapter.onBindViewHolder` ~line 464) now builds `iconTag = versionedTag(item.packageName, item.lastUpdateTime)` and passes it to **both** `holder.icon.setTag(iconTag)` **and** `displayImage(iconTag, item, holder.icon)` — they MUST match, because `ImageLoader.LoadImageInImageView`'s recycled-view guard only binds the bitmap when `iv.getTag().equals(queueItem.tag)`; set the plain name on one and the versioned tag on the other and the icon never appears. A reinstall bumps `lastUpdateTime` → new key → misses both cache layers → `ApplicationItem.loadIcon` (which itself queries PM live via `fetchPackageInfo`) loads the current icon. The `@` separator can't occur in a package name or a Java component class name, so versioned tags never collide with the plain-package tags (other screens: AppsFragment, SysConfig, AppUsage, RunningApps, Finder, IconPicker, AppInfoFragment — still bare `packageName`, **not** yet versioned) or the component tags (`AppDetailsComponentsFragment` etc., tag = class name). `PackageInfoImageFetcher`'s in-memory-cache predicate was widened from `tag.equals(mInfo.packageName)` to also accept `tag.startsWith(mInfo.packageName + "@")`, so versioned **package** icons stay memory-cached (scroll perf preserved) while component icons stay out, as before. (2) **Live `lastUpdateTime`** — the crux of the "some icons refresh, some don't" symptom: `item.lastUpdateTime` is populated in `MainViewModel.getNewApplicationItem` from the **DB cache** (`app.lastUpdateTime`), which **lags a reinstall**, leaving the key unchanged for packages whose DB row hadn't refreshed. Fixed by sourcing it live in that same method: the per-item freeze-state PM query (the Commit 6 live-enrichment, already paid per row) was upgraded from `getApplicationInfo` to **`getPackageInfo`**, which returns the live `ApplicationInfo` (for freeze state) **and** the live `lastUpdateTime` in one call; `item.lastUpdateTime` now takes that live value. **LESSON:** the icon cache is keyed by `packageName` only on most screens and has no install-version awareness — to bust it on reinstall, fold a live install signal into the tag via `versionedTag`, and never key cache-busting on a DB-cached `ApplicationItem` field (same staleness class the Commit 6 freeze indicator hit; the live-PM enrichment is the standard fix). Three files: `self/imagecache/ImageLoader.java`, `main/MainRecyclerAdapter.java`, `main/MainViewModel.java`. (Other icon surfaces remain on the bare-`packageName` key — extend `versionedTag` to them if the same staleness surfaces there.)

## Configurable per-element fonts (`io.github.muntashirakon.AppManager.fonts`)

Per-surface fonts (family + weight + size), Settings → **Fonts** (`preferences_main.xml`, icon `ic_font_download`, after Appearance → `FontsPreferences`). Mirrors the ArcaneChat/Jami/HandyRSS forks' pattern.

- **`FontPrefs`** — dedicated `shiroikuma_fonts` SharedPreferences. Per category: `family` (`""`=inherit / system family / `file:<abs path>`), `weight` (0=inherit, else 100–900), `size` (0=inherit, else sp). `effectiveFamily/Weight/Size` resolve **category → DEFAULT → inherit**. Imported-font registry: `getImportedFonts` (drops missing files), `addImportedFont`. Category constants: `DEFAULT, LABEL, PACKAGE, VERSION, APP_TYPE, INSTALL_DATE, UID, SDK, SIGNATURE, BACKUP_INFO` (main list) and `DETAIL_LABEL, DETAIL_PACKAGE, DETAIL_VERSION` (App details header). All declared; the App-details ones plus the whole main list are wired (see below).
- **`FontUtil`** — `resolveTypeface(family, weight, current)` (null when both inherit → leave the view's typeface; system family via `Typeface.create(name, NORMAL)`; `file:` via `createFromFile`, cached; real per-weight on API 28+ via `Typeface.create(base, weight, false)`, BOLD/NORMAL below). `apply(TextView, category)` sets typeface and/or sp size, **non-destructive when inheriting**. `families()`/`WEIGHT_VALUES`/`WEIGHT_LABELS` + label helpers. Size slider 8–96, typed hard cap 300. `clearCache()` after an import.
- **`FontsPreferences`** — plain `Fragment` (SettingsActivity instantiates any Fragment via the factory; the `PreferenceFragment` dual-pane block is simply skipped — so a plain Fragment works as a settings sub-screen). Data-driven `GROUPS` build the section UI (HandyRSS treatment, since `da254a20a`): each **group** header (`view_font_group`) is bold text with a **full-width underline rule**; each **sub-heading** (`view_font_element`) is bold text with a **text-width underline** (wrap_content wrapper so the rule spans only the words); the per-element controls are **indented** (`paddingStart`) beneath the sub-heading. Controls: tap **Font** → family chooser, tap **weight** → weight chooser, **size** label with its value on its own left-aligned line, then the **preview** line, then the **SeekBar** (tap the size value to type beyond 96 / reset). The family chooser previews **each option rendered in its own typeface** at the category's effective weight, with the filename as a caption for imported fonts (`item_font_picker.xml` + a custom `ArrayAdapter`; the trailing "Add custom font…" row is a sentinel value `ADD_MARKER`). "Add custom font…" → SAF `OpenDocument`; `resolveFontPath` maps `primary:`/`volume:`/`raw:` doc ids to a real path (referenced in place), with `copyToInternal` (→ `filesDir/fonts`) as fallback. Preview sample = `font_preview_text` = "AaIiMmOoQqWw 012 白い熊相撲道 áÁčČďĎéÉěĚíÍňŇóÓrŘŠŠťŤúÚůŮÝÝžŽ". (The earlier outlined-box drawables `font_group_box`/`font_element_label_box` were removed in `da254a20a`.)
- **Live update**: changing a font has no effect on the already-bound main list until it re-binds. `FontPrefs` carries a process-wide `sChanged` flag (set by every mutator) consumed by `MainActivity.onResume` via `FontPrefs.consumeChanged()` → `FontUtil.clearCache()` + `mAdapter.notifyDataSetChanged()` (flag-guarded, so a normal resume doesn't re-bind). Pushed in `8616d18ff`.
- Layouts: `fragment_fonts_settings`, `view_font_group`, `view_font_element`. Drawables: `font_group_box`, `font_element_label_box`. Strings under `values/strings.xml` (`pref_font_*`, `font_preview_text`).
- **To add a surface: one `Cat` row in `FontsPreferences.GROUPS` + one `FontUtil.apply(view, FontPrefs.CAT)` at that view's bind/populate site.** Wired so far:
  - **Main app list** (entire row), all in `MainRecyclerAdapter.onBindViewHolder`: `LABEL` (`cc7dfa54c`; applied *before* the frozen-italic line, which is re-derived on top of the chosen typeface so frozen apps keep the italic cue), `PACKAGE` (`cc7dfa54c`), `VERSION`/`INSTALL_DATE`/`SDK`/`SIGNATURE`/`BACKUP_INFO` (`5538bf3d7`; `BACKUP_INFO` applied to all three backup lines), and `UID` (the `holder.userId` UID/app-ID number) + `APP_TYPE` (the `holder.isSystemApp` User/System tag) — the last two completing the row.
  - **App details header** (`AppInfoFragment`, layout `pager_app_info.xml`): `DETAIL_LABEL`/`DETAIL_PACKAGE`/`DETAIL_VERSION` on `mLabelView`/`mPackageNameView`/`mVersionView`. These are their OWN categories (a separate "App details" `GROUPS` entry reusing the same element-label strings), not the list's `LABEL`/`PACKAGE`/`VERSION`, so the two screens are independently controllable; `DEFAULT` still cascades to both.
- **Two refresh idioms**: the **main list** uses the flag-guarded `FontPrefs.consumeChanged()` in `MainActivity.onResume` (re-bind only when something changed, since `notifyDataSetChanged` over a long list isn't free). The **App details** header re-applies its 3 views **unconditionally** in `AppInfoFragment.onResume` (3 `FontUtil.apply` calls — too cheap to bother flag-guarding, and it sidesteps the one-shot nature of `consumeChanged()`, which only the first resuming screen would see). The header is observer-driven, so the apply calls also sit right after each `setText` for the initial load.
- DEFAULT only shows where a surface is wired. Remaining for future increments: other screens (app usage, running apps, debloater, the various dialogs).

## Build log hygiene (keep the log readable so real errors stand out)

Upstream's root `build.gradle` `allprojects` block *enables* `-Xlint:unchecked -Xlint:deprecation`, which buries errors under hundreds of warning lines. The fork silences them:

- **Root `build.gradle`** — `allprojects { tasks.withType(JavaCompile).configureEach { options.compilerArgs << "-Xlint:none" << "-nowarn"; options.deprecation = false } }`. **Use the direct `configureEach` form, NOT `gradle.projectsEvaluated { tasks.withType(JavaCompile)… }`** — the wrapped form only reached `:app`, leaving `:server` and `:libcore:*` still printing the mandatory deprecation/unchecked summary notes ("Note: … uses … API. Recompile with -Xlint:…", localized to the JDK's locale — Japanese here). The direct form is configuration-cache safe and reaches every module lazily. Errors are unaffected by `-nowarn`, so build failures still show.
- **`gradle.properties`** — `android.javaCompile.suppressSourceTargetDeprecationWarning=true` (the "source/target 8 is obsolete" note) and `org.gradle.warning.mode=none` (Gradle's own "Deprecated Gradle features were used…" summary).
- **`strings.xml`** — `formatted="false"` on `memory_usage_accessibility_description`, `swap_usage_accessibility_description`, `selected_items_accessibility_description` (aapt "Multiple substitutions in non-positional format" warnings).
- The **apksigner "WARNING: … not protected by signature"** lines are NOT build output — `apksigner verify` emits them (benign: JAR entries outside the v1 signature). Filter them in the pipeline's verify step (`apksigner verify --verbose … 2>&1 | grep -v 'not protected by signature'`), not in the build.

## Build pipeline (paste-ready block)

### Pipeline conventions (current — supersede the illustrative block below)

- **Wrap the whole pipeline in a function and `return`, never `exit`.** `run() { … }; run` — the user pastes into an interactive Konsole, so `exit` closes the terminal. Every abort is `return 1` (failure) or `return 0` (clean "Aborted.").
- **Gate on `git apply`.** `if ! r git apply "$PATCH"; then …; return 1; fi`, then a **post-apply sentinel grep** (e.g. `grep -q theme_bright_orange …` / check a new file exists). The `r()` helper colours stderr red but does **not** stop the script, so a failed apply otherwise silently builds the *unpatched* tree — this once produced a clean APK missing the just-added feature.
- **Build-success gate** (`if ! r ./gradlew :app:assembleRelease; then …; return 1; fi`) and confirm the `*-unsigned.apk` exists before signing.
- **apksigner verify filtered**: `apksigner verify --verbose /tmp/am-signed.apk 2>&1 | grep -v 'not protected by signature'`.
- **Deploy is push-only**: `adb push` the signed APK to `/sdcard/tmp/` (no `adb install`); precede each `cp`/`adb` with a bright-white (`\e[1;37m`) `>>>` echo showing the full command with complete src+dst paths.
- **APK name**: `shiroikuma-appmanager_${customBaseVersionName}+${customBuildNumber}_arm64-v8a.apk` (read both from `gradle.properties` *after* `bump-build.sh`), e.g. `shiroikuma-appmanager_4.0.5+23_arm64-v8a.apk`.

The block below is the older illustrative form (cyan-echo style); apply the conventions above to it.

```bash
r() { "$@" 2> >(sed $'s/.*/\033[1;31m&\033[0m/' >&2); }

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/home/shiroikuma/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"

echo -e '\033[1;36m>>> cd ~/git/shiroikuma-appmanager\033[0m'
cd ~/git/shiroikuma-appmanager

echo -e '\033[1;36m>>> git status\033[0m'
r git status

echo -e '\033[1;36m>>> git log --oneline -3\033[0m'
r git log --oneline -3

echo -e '\033[1;36m>>> Pre-flight: java -version\033[0m'
r java -version

echo -e '\033[1;36m>>> Pre-flight: SDK components present?\033[0m'
r bash -c '
  test -d "$ANDROID_HOME/platforms/android-36" && echo "  android-36: OK" || echo "  android-36: MISSING (sdkmanager: \"platforms;android-36\")"
  test -d "$ANDROID_HOME/build-tools/36.1.0"   && echo "  build-tools 36.1.0: OK" || echo "  build-tools 36.1.0: MISSING (sdkmanager: \"build-tools;36.1.0\")"
  if ls "$ANDROID_HOME/ndk/" >/dev/null 2>&1; then
    echo "  NDK installed: $(ls $ANDROID_HOME/ndk/ | tr "\n" " ")"
  else
    echo "  NDK: not installed (AGP will auto-download on first build, ~1 GB)"
  fi
'

echo -e '\033[1;36m>>> Pre-flight: submodules clean?\033[0m'
r git submodule status

echo -e '\033[1;36m>>> verify expiry neutered\033[0m'
r grep -A 2 'public static Boolean buildExpired' app/src/main/java/io/github/muntashirakon/AppManager/self/life/BuildExpiryChecker.java

echo -e '\033[1;36m>>> new_ver=$(tools/bump-build.sh)\033[0m'
new_ver=$(tools/bump-build.sh)

echo -e '\033[1;36m>>> echo "$new_ver"\033[0m'
echo "$new_ver"

echo -e "\033[1;36m>>> apk_name=\"shiroikuma-appmanager_\${new_ver}_\$(date '+%Y-%m-%d_%H-%M-%S')_arm64-v8a.apk\"\033[0m"
apk_name="shiroikuma-appmanager_${new_ver}_$(date '+%Y-%m-%d_%H-%M-%S')_arm64-v8a.apk"

echo -e '\033[1;36m>>> echo "$apk_name"\033[0m'
echo "$apk_name"

echo -e '\033[1;36m>>> Write local.properties\033[0m'
cat > local.properties <<EOF
sdk.dir=$ANDROID_HOME
EOF
r cat local.properties

read -p $'\033[1;33m>>> Continue with clean + Gradle release build? (y/n) \033[0m' ans
if [[ "$ans" =~ ^[Yy]$ ]]; then
  echo -e '\033[1;36m>>> ./gradlew clean\033[0m'
  r ./gradlew clean

  echo -e '\033[1;36m>>> ./gradlew :app:assembleRelease\033[0m'
  r ./gradlew :app:assembleRelease

  echo -e '\033[1;36m>>> UNSIGNED_APK=$(find app/build/outputs/apk/release -name "*-unsigned.apk" | head -1)\033[0m'
  UNSIGNED_APK=$(find app/build/outputs/apk/release -name '*-unsigned.apk' | head -1)

  echo -e '\033[1;36m>>> echo "$UNSIGNED_APK"\033[0m'
  echo "$UNSIGNED_APK"

  echo -e '\033[1;36m>>> aapt dump badging "$UNSIGNED_APK" | head -1\033[0m'
  r aapt dump badging "$UNSIGNED_APK" | head -1

  echo -e "\033[1;36m>>> unzip -l \"\$UNSIGNED_APK\" | grep -E 'am\\.jar|main\\.jar'\033[0m"
  r bash -c "unzip -l \"$UNSIGNED_APK\" | grep -E 'am\\.jar|main\\.jar' || echo '  STILL NOT IN APK — commit 5 wiring is broken'"

  read -p $'\033[1;33m>>> APK badging looks right, sign and deploy to phone? (y/n) \033[0m' ans2
  if [[ "$ans2" =~ ^[Yy]$ ]]; then
    echo -e '\033[1;36m>>> zipalign -p -f 4 "$UNSIGNED_APK" /tmp/am-aligned.apk\033[0m'
    r zipalign -p -f 4 "$UNSIGNED_APK" /tmp/am-aligned.apk

    echo -e '\033[1;36m>>> apksigner sign --ks ~/.android-keystores/appmanager-custom.jks --ks-key-alias appmanager --ks-pass pass:appmanager123 --key-pass pass:appmanager123 --out /tmp/am-signed.apk /tmp/am-aligned.apk\033[0m'
    r apksigner sign --ks ~/.android-keystores/appmanager-custom.jks --ks-key-alias appmanager --ks-pass pass:appmanager123 --key-pass pass:appmanager123 --out /tmp/am-signed.apk /tmp/am-aligned.apk

    echo -e '\033[1;36m>>> apksigner verify --verbose /tmp/am-signed.apk\033[0m'
    r apksigner verify --verbose /tmp/am-signed.apk

    echo -e "\033[1;36m>>> cp /tmp/am-signed.apk ~/tmp/$apk_name\033[0m"
    r cp /tmp/am-signed.apk ~/tmp/"$apk_name"

    echo -e "\033[1;36m>>> adb push /tmp/am-signed.apk /sdcard/tmp/$apk_name\033[0m"
    r adb push /tmp/am-signed.apk "/sdcard/tmp/$apk_name"

    echo -e "\033[1;36m>>> ls -lh ~/tmp/$apk_name\033[0m"
    r ls -lh ~/tmp/"$apk_name"
  else
    echo "Aborted before signing."
  fi
else
  echo "Aborted before build."
fi
```

Two gates: one before the clean+build (long), one after the APK is built and badging is shown (verify versionName/versionCode match expectations before signing+pushing). The `./gradlew clean` is always present — incremental builds have caused stale APKs that pass at the filename layer but fail at the manifest content layer (see "Recovery / lessons learned" below). On the rare iteration where you're certain the source hasn't changed since the last successful build and you just want a re-sign or re-push, you can drop `clean` manually.

Upstream's release build has **no `signingConfig`** (only `debug` has one, with a public dev keystore committed in-repo). So `assembleRelease` produces an unsigned APK and we sign post-build with `apksigner` — same pattern as SimpleX. No need to add a signingConfig to build.gradle.

First build with no NDK installed takes 15–30 minutes (NDK download + Gradle dependency download + native CMake compile). Subsequent builds with warm cache: 3–8 minutes for full rebuild, seconds for incremental.

## Deploy

**Always ask before `adb push` (never automatic).** After every successful build, explicitly ask 白い熊 whether to `adb push` the signed APK to the device — end the build report with that question. Never push without asking, and never silently skip the question; push only once 白い熊 confirms. (The build itself — bump, assemble, sign, verify, copy to `~/tmp/` — still runs automatically; only the device deploy waits on a yes.)

Build output goes to `~/tmp/shiroikuma-appmanager_<versionName>_arm64-v8a.apk` (where `versionName` = `<customBaseVersionName>+<customBuildNumber>`, e.g. `shiroikuma-appmanager_4.0.5+21_arm64-v8a.apk` — no datetime, no git sha; read `customBaseVersionName` and the post-bump `customBuildNumber` from `gradle.properties`) and is `adb push`ed to `/sdcard/tmp/` — the pipeline does **not** `adb install` anymore. Install on device via file manager from `/sdcard/tmp/`. Each `cp`/`adb` line in the pipeline is preceded by a bright-white (`\e[1;37m`) `>>>` echo showing the full command with complete source+destination paths. If `adb push` fails (no device connected, USB debugging off), the local `~/tmp/` copy is the fallback — transfer via KDE Connect, Bluetooth, file copy. The pipeline must also **abort if `git apply` fails** (the `r()` helper does not stop on error, so a failed apply otherwise silently builds the unpatched tree — this bit us once, producing a clean APK missing the just-added feature); gate the build behind an explicit apply check.

The custom build's keystore is stable, so updates over an existing custom build install cleanly without uninstall. **Do not** install over the F-Droid official AppManager — different signing keys, Android will refuse. The two coexist because applicationIds differ (`io.github.muntashirakon.AppManager` vs `shiroikuma.appmanager`).

## Upstream sync — rebase `custom` onto fresh master

```bash
r() { "$@" 2> >(sed $'s/.*/\033[1;31m&\033[0m/' >&2); }

echo -e '\033[1;36m>>> cd ~/git/shiroikuma-appmanager\033[0m'
cd ~/git/shiroikuma-appmanager

echo -e '\033[1;36m>>> git fetch upstream\033[0m'
r git fetch upstream

echo -e '\033[1;36m>>> git checkout custom\033[0m'
r git checkout custom

echo -e '\033[1;36m>>> show what is new on upstream\033[0m'
r git log --oneline HEAD..upstream/master | head -50

echo -e '\033[1;36m>>> git rebase upstream/master\033[0m'
r git rebase upstream/master

echo -e '\033[1;36m>>> git submodule update --init --recursive (sync submodule pointers)\033[0m'
r git submodule update --init --recursive

echo -e '\033[1;36m>>> git log --oneline -3\033[0m'
r git log --oneline -3

echo -e '\033[1;36m>>> git push --force-with-lease origin custom\033[0m'
r git push --force-with-lease origin custom
```

Conflicts during rebase: possible if MuntashirAkon refactors any of the touched files. Watch-points by commit:

- **Commit 1 most-likely culprit:** `app/build.gradle` (the splits block, the release `app_name`, or the `applicationId` line).
- **Commit 2 most-likely culprits:** `server/build.gradle` (if MuntashirAkon finishes his CC migration and our capture refactor collides) and `app/build.gradle`'s `buildTime()` function (if he migrates it to `providers.exec` himself, our diff becomes redundant and can be dropped via `git rebase --skip` if appropriate).
- **Commit 3:** the `cleanupDocs` block in `docs/build.gradle`. Low conflict risk — upstream rarely touches this.
- **Commit 4:** `app/build.gradle` `defaultConfig` versionCode/versionName lines (if upstream bumps to 4.0.6 / 446, those lines are where they'd change — update `customBaseVersionName` and `customBaseVersionCode` in `gradle.properties` accordingly during rebase, and adjust our `versionCode = customBaseVersionCode * 10000 + ...` line if upstream's new line takes a different form). Other commit-4 files (the layout XML, Java sources, `tools/bump-build.sh`) almost never conflict — they're entirely additive.
- **Commit 5:** the `applicationVariants.configureEach { ... }` block at the end of `android {}` in `app/build.gradle`. Low conflict risk on its own — it's a self-contained additive block. The deprecation status of `applicationVariants` is the medium-term watch-point: if upstream migrates to the newer `androidComponents { onVariants {} }` API (or if AGP removes the old API), our wiring would need a rewrite. The replacement shape would be roughly `androidComponents.onVariants(selector().all()) { variant -> ... }` plus `tasks.named("merge${variantName}Assets").configure { dependsOn ... }`. Also, if upstream themselves enables CC and fixes the JAR-packaging ordering in server/build.gradle directly (likely by declaring `outputs.file(...)` on the JAR-creation task), our diff becomes redundant and can be dropped — verify by building and confirming `am.jar` + `main.jar` appear in the unzipped APK's `assets/`.
- **Commit 6 most-likely culprits:** `item_main.xml` (any upstream change to the icon column or backup_indicator placement collides with our new freeze_indicator); `MainRecyclerAdapter.java` around the icon/label setup; `MainViewModel.java` `getNewApplicationItem` and the field-population loop near line 795 (any upstream addition/removal of `item.X = app.X` assignments may need our live-PM block re-anchored); `PackageUtils.java` `getInstalledOrBackedUpApplicationsFromDb` near the top of the method (where we insert the bulk PM query) and inside the main loop (where we override `item.isDisabled` / `item.isFrozen`); `AppInfoFragment.doFreeze` / `doUnfreeze` (any upstream refactor of these methods needs our `sendPackageAltered` calls preserved). If upstream itself adds a list-view freeze indicator, our visual layer can probably be dropped — verify by looking for any new `isFrozen` references in `MainRecyclerAdapter` or `item_main.xml` upstream. The live-PM enrichment is still worth keeping even if upstream adds visuals, since the DB-staleness problem affects the existing `isDisabled` border too.
- **Commit 7 most-likely culprits:** `AppPref.java` `PrefKey` enum (any upstream insertion/removal near `PREF_FREEZE_TYPE_INT` may shift the diff context — our entry is purely additive, so resolution is "keep ours and theirs"); the false-default case cluster in the same file (same story); `Prefs.java` `Prefs.Blocking` nested class (upstream may add their own freeze-related accessors near where we added ours); `preferences_rules.xml` (upstream may reorder or restyle preferences); `AppInfoFragment.java` around the freeze-type observer (upstream may refactor `showFreezeDialog` or the `getFreezeTypeLiveData` observer). If upstream itself adds an equivalent "skip dialog" preference our diff becomes redundant — verify by looking for any new `SkipFreezeMethodDialog`-shaped accessors in `Prefs.Blocking` upstream.

Resolution rule: keep our customization values; take any other upstream changes around them. If a commit becomes empty after rebase (because upstream merged the equivalent change — e.g. they enable CC themselves), let git auto-skip it — `custom` will collapse to fewer commits, which is fine. If conflicts feel non-trivial, abort (`git rebase --abort`) and ask for a fresh-eyes look — each commit is small enough to re-derive from this skill rather than fighting `git rerere`.

If MuntashirAkon eventually adds new `BuildConfig.DEBUG`-gated features we want exposed, add them as a new commit on top (or amend an existing customization commit only if the change is corrective and still unpushed). The stack just keeps growing and is rebased onto each new upstream version — don't collapse it.

## Configuration cache — was a known issue, now resolved

(Earlier versions of this skill described the configuration cache as "deferred" because we initially failed to enable it. As of commit 2 it is enabled and validated. The full diffs are in "Commit 2 — Enable Gradle configuration cache" above. No action needed unless a future upstream change introduces a new CC violation.)

## Recovery / lessons learned

- **Submodules:** must clone with `--recurse-submodules` and `git submodule update --init --recursive` after each `git checkout` / `git reset --hard` that crosses submodule pointer changes. Submodules are `scripts/android-libraries` and `scripts/android-debloat-list`.
- **Don't cherry-pick post-tag master commits onto a release tag.** The Unfrozen filter commit (`a58a2f260`) doesn't cherry-pick onto `v4.0.5` cleanly because of intervening structural refactor `f186c4f52`. This is exactly why we sit on master.
- **The release build's `r()` corruption symptom** (`v4.0.433[1;31m * [new branch]...`) is a sign that the `r()` helper is using plain `'...'` quoting around the sed pattern. GNU sed parses `\033` as `\0` (whole-match backreference) + literal `33`. Always use `$'...'` (bash ANSI-C quoting).
- **Configuration cache IS enabled** (since commit 2). If a future upstream change introduces a new CC violation, fix shape is always: capture at config time, use captured local at execution time, replace `file(...)` with `new File(...)`.
- **CC + script-level `def` helpers don't mix.** Any `doLast` closure that calls a `def fn(...)` declared elsewhere in the same `.gradle` file will fail under CC. The closure is serialized for replay and the script binding isn't restored. Replace such calls with Groovy GDK extension methods on the target object (e.g. `file.deleteDir()` instead of a custom `deleteDir(file)`). See commit 3 for the canonical fix.
- **Always `./gradlew clean` after applying a source-changing patch.** Incremental builds have caused stale APKs that report correct filenames (from `bump-build.sh`) but contain pre-patch compiled bytecode — same APK file size, wrong internal `versionCode`/`versionName`. The smoking gun is `aapt dump badging "$UNSIGNED_APK" | head -1` showing `versionCode='445' versionName='4.0.5'` when you expected `versionCode='4450001' versionName='4.0.5+1'`. The build pipeline always includes `clean` for this reason.
- **Groovy DSL scoping inside `android {}` is unreliable.** Helper `def` declarations meant to be used inside `defaultConfig {}` or other nested closures must live at script level (above `android {}`), not inside the `android {}` closure body. First attempt of the versioning system put them inside `android {}`; build silently produced wrong versionCode/versionName until the defs were hoisted out.
- **`AppsProfile.packages` is `String[]`, not `List<String>`.** Use `Collections.addAll(set, profile.packages)` to copy into a Set, NOT `set.addAll(profile.packages)` (which doesn't compile because `String[]` isn't a `Collection`). General rule: before referencing a public field on another class, view the class's source to confirm its type.
- **`--fixup` + `--autosquash` is brittle when commits have overlapping diff context.** Specifically: when fixup-for-commit-N's diff context includes lines added by commit-N+M (a later commit), the autosquash replay onto commit-N's standalone state will fail to find the context. For this project, commits 1 and 2 both touch `app/build.gradle` and `gradle.properties`, so a fixup generated against the combined state can't be cleanly squashed into commit 1 alone. The standing practice is to add a new commit on top (commit 3, 4, ...) rather than amend or squash — the stack is intentionally kept as appended commits and rebased onto new upstream versions, not collapsed.
- **`finalizedBy` is too loose for sibling-module ordering under CC + parallel scheduling.** Producer tasks declared `finalizedBy(consumer)` in module A can finish AFTER a consumer task in module B has already started, because there's no formal dependency relationship for Gradle's scheduler to honor. Symptom in this project: `:server`'s `create<Variant>ServerJars` (a finalizer of compile) wrote `am.jar` and `main.jar` into shared `app/src/main/assets/`, but `:app:mergeReleaseAssets` had already snapshotted that directory by the time the JARs landed → APK shipped without the JARs → `ServerConfig.init()` threw `FileNotFoundException: am.jar` at runtime. The fix is always the same: declare an explicit `dependsOn` from the consumer task to the producer task. See commit 5 for the canonical wiring. Upstream gets away with the loose `finalizedBy` only because they don't enable CC.
- **Default to verifying APK contents.** After every build, `unzip -l "$UNSIGNED_APK" | grep -E 'am\.jar|main\.jar'` should list both. If it doesn't, commit 5's wiring is broken (variant naming mismatch, deprecated API removal in AGP, etc.) and the app will fail at runtime in ways that aren't obvious from looking at source. The build pipeline includes this check by default for this reason.
- **AppManager's `App` DB cache can lag the actual PM state.** `App.isEnabled` is set from `!FreezeUtils.isFrozen(applicationInfo)` only when `App.fromPackageInfo()` runs — which happens during DB sync, not on every read. If you build a UI feature that depends on freeze/disable/suspend/hide state, **don't trust the cached `app.isEnabled` or `app.flags`** for visual indicators. Instead, query `pm.getApplicationInfo(packageName, MATCH_DISABLED_COMPONENTS | MATCH_UNINSTALLED_PACKAGES)` live and use `FreezeUtils.isFrozen(liveAi)`. For list views, do one bulk `pm.getInstalledApplications(...)` call up front and look up by package name. The cost is one binder per package (or one bulk binder for the whole list); the win is correctness regardless of cache freshness. See commit 6 for the canonical pattern in both `PackageUtils.getInstalledOrBackedUpApplicationsFromDb` (bulk) and `MainViewModel.getNewApplicationItem` (single).
- **Don't rely on `Intent.ACTION_PACKAGE_CHANGED` to refresh UI after AppManager's own freeze actions.** It's unreliable for the suspend and hide freeze methods, and on some OEMs doesn't fire at all even for `pm disable`. Instead, fire AppManager's in-process `BroadcastUtils.sendPackageAltered(context, packageNames)` immediately after the action completes — that routes through `MainViewModel.updateInfoForPackages()` deterministically. See commit 6's `AppInfoFragment.doFreeze` / `doUnfreeze` hooks.
- **Pre-patch sandbox state must mirror `origin/custom`.** Whenever generating a patch, first `git fetch origin '+refs/heads/*:refs/remotes/origin/*'` and `git reset --hard origin/custom` in the sandbox. Working from a stale local commit produces patches with subtly wrong diff context that fail to apply at the user's terminal. See "Mandatory pre-patch protocol" section near the top.

