---
name: upstream-new-version
description: Sync the shiroikuma.oyokanri fork to the latest upstream AppManager (MuntashirAkon/AppManager) commits — fetch upstream, detect new commits on upstream/master since our base, show 白い熊 a tabular summary of what's new and WAIT for approval, then rebase the custom commit stack onto upstream/master (resolving small conflicts in place, STOPPING for approval on anything irresolvable, uncertain, or that would drop/weaken a fork feature), bump the fork base version + reset the build counter to +1 if upstream's version moved, then build + deliver the new signed APK per the appmanager-fork / build skills. Use whenever 白い熊 runs /upstream-new-version, or asks to "check for a new AppManager version", "pull upstream", "sync to the latest upstream master", "rebase custom onto fresh master", "update to the new upstream", or otherwise wants the fork brought up to newer upstream commits. This is the orchestration layer on top of appmanager-fork — read that skill for every concrete fact (remotes, the build/sign/deploy pipeline, the per-commit conflict watch-points, the version scheme) this one sequences.
---

# Sync the AppManager fork to fresh upstream master

One-command upstream sync for 白い熊's `shiroikuma.oyokanri` fork:
**fetch → detect → summarise → (you OK) → rebase → bump → build → deliver → test → push.**

This is the **orchestration layer**. Every concrete fact — remotes, the build/sign/deploy
pipeline, the server-JAR hard gate, the per-commit conflict watch-points, the CLAUDE.md
chokepoint table, the version scheme — lives in the **`appmanager-fork`** skill (auto-loads on
any task in this repo) and the **`build`** skill. **Read `appmanager-fork` before running this**,
especially its *"Why master, not a tag"*, *"Upstream sync — rebase custom onto fresh master"*,
and *Versioning* sections, plus CLAUDE.md's *Key files & chokepoints* table. This skill sequences
those pieces and adds the two decision gates 白い熊 requires: the **pre-rebase summary** and the
**no-silent-loss rebase rule**.

## Governing discipline — nothing is pushed to GitHub until 白い熊 says "Push"

The entire fetch + rebase + build happens on the **local** working tree as a scratchpad.
**No `git push` — not `master`, not `custom` — until 白い熊 explicitly says to push.** A rebase only
rewrites local `custom` history and is freely re-runnable (`git rebase --abort`, or reset to
`origin/custom`) right up until that point. Build, deliver, let 白い熊 verify on-device first; the
push is a separate step run only on their instruction, never as an automatic continuation of a
successful build. Cutting a *GitHub release* is a further separate step — the **`publish-version`**
skill — never part of this one.

## Two hard rules that override the obvious approach

### 1. ALWAYS `git fetch upstream` first — never trust the cached ref.

The locally-cached `upstream/master` goes stale between sessions, so a check against it can report
"nothing to sync" when a new release in fact exists. (This bit us once: a cached ref hid `v4.1.0`
and 99 new commits until a fresh fetch.) **Step 1 fetches first, then compares against the
freshly-fetched ref.** No detection happens before the fetch.

### 2. Track `upstream/master` HEAD — NEVER pin to a release tag.

Detection and the rebase target are **commit-based** against `upstream/master`; the base version is
read from `upstream/master:app/build.gradle`'s `defaultConfig`, not from any tag name. **Do not
"helpfully" switch this skill to tag-pinning.** Rationale (per 白い熊): MuntashirAkon commits to
master for *years* while the official release tag stays frozen on an old version — `v4.1.0` landed
on one day and will sit there indefinitely while master keeps moving. So tags are useless as a base;
we roll with master's commits. The sibling forks (inure/simplex/fairemail) track tags; **this one
does not** — that's the key structural difference. (See `appmanager-fork` → "Why master, not a tag".)

## Step 0 — Preconditions

- cwd is the repo (`~/git/shiroikuma-oyokanri`). Remotes: `origin` (SSH, fork — push) and `upstream`
  (MuntashirAkon/AppManager — **fetch-only by policy; NEVER push to it**, even though its push URL is
  set). Confirm with `git remote -v`.
- Working tree clean (`git status --short` empty). If dirty, surface it and ask before proceeding —
  uncommitted scratch work would be swept into the rebase. (`local.properties` and the gitignored
  `assets/am.jar`/`main.jar` server JARs never show here.)
- On (or able to check out) `custom`: `git rev-parse --abbrev-ref HEAD` is `custom`.
- Build env (export every run — not set in non-interactive shells):
  ```bash
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  export PATH="$JAVA_HOME/bin:$PATH"
  export ANDROID_HOME=$HOME/android-sdk
  ```

## Step 1 — Detect new upstream commits (fetch FIRST)

```bash
cd ~/git/shiroikuma-oyokanri
git fetch upstream --tags --prune    # MANDATORY FIRST — hard rule 1; never compare against the cached ref
git fetch origin                     # so origin/master + origin/custom are current for the push step

base=$(git merge-base custom upstream/master)        # the upstream commit our stack currently sits on
ahead=$(git rev-list --count "$base"..upstream/master)   # new upstream commits to absorb
replay=$(git rev-list --count "$base"..custom)           # our fork commits to replay

echo "our base:            $(git log --oneline -1 "$base")"
echo "upstream/master:     $(git log --oneline -1 upstream/master)"
echo "new upstream commits: $ahead    fork commits to replay: $replay"

# the fork base version literals currently in gradle.properties...
old_name=$(grep -oP '^customBaseVersionName=\K.+' gradle.properties)
old_code=$(grep -oP '^customBaseVersionCode=\K.+' gradle.properties)
# ...vs upstream's defaultConfig at the new master HEAD
new_name=$(git show upstream/master:app/build.gradle | grep -oP 'versionName\s+"\K[^"]+' | head -1)
new_code=$(git show upstream/master:app/build.gradle | grep -oP 'versionCode\s+\K[0-9]+' | head -1)
echo "fork base version:   $old_name ($old_code)  ->  upstream now: $new_name ($new_code)"
```

- **`ahead == 0`** → `custom` is already on top of the newest `upstream/master`. Report it
  ("custom is current with upstream/master at `<base versionName>` — nothing to sync") and **stop**.
- **`ahead > 0`** → continue to Step 2. Note whether `new_name`/`new_code` differ from
  `old_name`/`old_code` — that decides the Step 4 version bump + counter reset.

## Step 2 — Fast-forward the local `master` mirror (push deferred)

`master` is a pure mirror of `upstream/master`, fast-forward only, never carries our changes.

```bash
git checkout master
git merge --ff-only upstream/master
git checkout custom
```

Do **not** `git push origin master` here — defer it to the push step.

## Step 2.5 — Pre-rebase summary for 白い熊, then WAIT for approval (gate)

**Before touching `custom`, present 白い熊 a summary of what's new upstream and stop for an explicit
OK.** No rebase begins until they approve.

```bash
git log --oneline "$base"..upstream/master                      # everything new upstream
# the conflict tell — OUR patched files that upstream also touched (only these can conflict):
comm -12 <(git diff --name-only "$base"..custom | sort -u) \
         <(git diff --name-only "$base" upstream/master | sort -u)
```

Then write up a **tabular summary** (this is the deliverable of this step):

- A table of the notable upstream changes, **grouped** — e.g. **Features**, **Refactors / API
  changes**, **Build & toolchain** (AGP/Gradle/SDK/NDK/Java bumps), **Fixes**. One row per
  meaningful change with a one-line "what it is"; collapse pure noise (dependency-bot bumps,
  translations, fastlane changelog churn) into a single summary row.
- A second short table or call-out: the **files in the `comm -12` intersection** — the only files
  where our 93-ish fork commits can collide — mapped to which fork feature/commit touches each
  (cross-reference CLAUDE.md's chokepoint table and `appmanager-fork`'s per-commit watch-points).
  This is the honest forecast of where the rebase may need decisions.
- The headline numbers: `ahead` new upstream commits, `replay` fork commits, and the version move
  (`old_name (old_code) -> new_name (new_code)`).

Present it and **stop. Wait for 白い熊's explicit OK before rebasing.** They may want to discuss
scope first; that's the point of this gate.

## Step 3 — Rebase the `custom` stack onto fresh master

Only after 白い熊 OKs. Take a safety backstop first, then rebase:

```bash
git branch "custom-pre-rebase-$(git rev-parse --short upstream/master)"   # backstop; deleted after a clean sync
git rebase upstream/master
```

Then triage the outcome. **The guiding rule for this fork is: re-implement ALL our customizations
faithfully, and never lose one silently.**

### Clean rebase → continue to Step 4.

A commit may go **empty** and auto-drop because upstream did the equivalent thing (e.g. they enable
the configuration cache themselves, or fix something our commit fixed). That's fine — let git skip it
(`git rebase --skip` if it pauses on the empty patch). **Flag any such drop to 白い熊** in the final
report (a fork commit disappearing is "losing something" — they want to know, even when it's correct).

### The recurring, expected conflict: the version lines in `app/build.gradle`

Our version-customization commit replaced upstream's literal `versionCode`/`versionName` in
`defaultConfig` with the `gradle.properties`-driven block:

```groovy
def customBuildNumber     = (project.findProperty('customBuildNumber') ?: '0') as Integer
def customBaseVersionName =  project.findProperty('customBaseVersionName') ?: '4.0.5'
def customBaseVersionCode = (project.findProperty('customBaseVersionCode') ?: '0') as Integer
...
versionCode customBaseVersionCode * 10000 + customBuildNumber
versionName "${customBaseVersionName}+${customBuildNumber}"
```

Upstream bumps its literal `versionCode`/`versionName` whenever it moves, so this conflicts **almost
every sync**. This is **small — resolve in place**: keep OUR `-P`-driven block; discard upstream's new
literal `versionCode`/`versionName` lines (the new upstream values are adopted in Step 4 via
`gradle.properties`, not by pasting them into our block). Keep `applicationId "shiroikuma.oyokanri"`,
the `abiFilters 'arm64-v8a'` block, and any overridable `ndkVersion`; take upstream's surrounding
changes. Then `git add app/build.gradle` and `git rebase --continue`.

### Everything else → "small" vs "significant", and the no-silent-loss rule

**Small — resolve in place, `git add`, `git rebase --continue`:** the version-line conflict above;
pure context-line shifts where our hunk obviously slots into moved-but-equivalent code; a handful of
mechanical conflicts in a feature commit where our change clearly maps onto the new code; an
auto-dropped empty commit (flag it, per above).

**STOP and get 白い熊's approval — do NOT guess, do NOT force a resolution — whenever the conflict is
irresolvable, uncertain, OR a resolution would drop, weaken, or fail to re-implement one of our
features.** This is the explicit rule 白い熊 set: after the rebase, **all** our custom features must be
carefully re-implemented; any deviation from that — losing a feature, partially implementing it, or a
resolution you're not confident preserves it — requires their sign-off first. Concretely, stop when:

- Upstream **refactored / moved / renamed / migrated** something a feature commit depends on, so the
  patch no longer maps cleanly (the CLAUDE.md chokepoints — `FreezeUtils`, `PackageInstallerCompat`,
  `MainToolbarPrefs`, `MainRecyclerAdapter`, `BatchOpsManager`, the process-monitor classifier, etc. —
  are the likely casualties).
- The **same file conflicts repeatedly** across several replayed commits, or many commits conflict.
- A **semantic** conflict: hunks merge textually but an upstream API/behaviour changed, so you can't be
  confident the feature still works without analysis.
- Any resolution whose correctness isn't obvious from `appmanager-fork` / the chokepoint table.

When you stop: gather the picture without changing anything — `git status` (which commit is replaying),
`git diff` (the conflicted hunks), `git log --oneline "$base"..upstream/master -- <file>` and
`git show` (what upstream changed) — identify **which fork commit and which feature** is affected, then
**present a plan and ask** (AskUserQuestion, or EnterPlanMode for a multi-commit mess). Typical options:
resolve together; re-derive the affected commit from `appmanager-fork` + the chokepoint table; defer or
drop the commit (**only with 白い熊's explicit approval** — never unilaterally); or abort the whole sync
(`git rebase --abort` returns the tree to exactly where it was — nothing lost, fully re-runnable). Make
clear that aborting is safe. **Never push through a mis-resolved feature just to get it building** — a
silently broken customization is worse than a paused sync.

When the rebase finishes, confirm no markers remain:
```bash
git grep -nE '^(<<<<<<<|=======|>>>>>>>)' -- '*.gradle' '*.java' '*.xml' '*.kt' || echo "clean"
git log --oneline upstream/master..custom    # our stack, now replayed on the new base
```

## Step 4 — Adopt the new base version + reset the build counter (only if upstream's version moved)

If Step 1 showed `new_name`/`new_code` **differ** from `old_name`/`old_code` (upstream moved its
`defaultConfig` version — e.g. `4.0.5/445 → 4.1.0/450`), update `gradle.properties` to mirror upstream
and **reset the fork build counter so the first build on the new base is `+1`** (白い熊's rule: always
reset on a new version):

```bash
sed -i "s/^customBaseVersionName=.*/customBaseVersionName=${new_name}/" gradle.properties
sed -i "s/^customBaseVersionCode=.*/customBaseVersionCode=${new_code}/" gradle.properties
sed -i "s/^customBuildNumber=.*/customBuildNumber=0/" gradle.properties      # build's bump-build.sh -> +1
```

Commit it as a small, self-describing chore commit on top of the stack (the stack just keeps growing —
do not collapse it):
```bash
git add gradle.properties
git commit -m "chore: adopt upstream ${new_name} as fork base; reset build counter"
```

The next build's `tools/bump-build.sh` increments `0 → 1`, yielding versionName **`${new_name}+1`**
(e.g. `4.1.0+1`) and versionCode `${new_code} * 10000 + 1`.

**If the version did NOT move** (rolling in master commits that didn't touch `defaultConfig`): make no
change here — the counter keeps incrementing normally on the next build. The reset fires **only** on an
actual base-version change.

## Step 5 — Build the new APK (via appmanager-fork / build pipeline)

Run the full pipeline from the **`build`** / **`appmanager-fork`** skills (do not improvise it):
`tools/bump-build.sh` → write `local.properties` → `./gradlew clean` → `:app:assembleRelease` (benign
`ノート:`/`Note:`/`[CXX5304]` filtered) → **server-JAR hard gate** (`assets/am.jar` + `assets/main.jar`
must be present and non-empty, or refuse to sign — ADB mode dies otherwise) → `zipalign` → `apksigner`
sign + verify → copy the signed APK to `~/tmp/shiroikuma-oyokanri_<versionName>_arm64-v8a.apk`. `clean`
is mandatory — a rebase changes res/strings/sources and incremental Gradle has shipped stale APKs.

- If the build **fails on the rebase result** (a compile error in code our commits touch), treat it
  exactly like a significant conflict: diagnose, and if it stems from the rebase, **replan with 白い熊**
  rather than patching blindly. A build failure rooted in a mis-mapped customization is a "losing
  something" event — stop and discuss.
- Verify the manifest before claiming success: `aapt2 dump badging` should show
  `package: name='shiroikuma.oyokanri'`, the expected `versionName='${new_name}+N'`, and label
  `白い熊 応用管理`.

## Step 6 — Deliver automatically, then stop and let 白い熊 test

Deliver via the global **`/after-build`** skill — **no prompt** (current policy): it runs `/adb-check`
UNSANDBOXED, then `/adb-push` to `/sdcard/tmp/` if the phone is connected, else `/scp` to `skhw`,
announcing the filename. Never `adb install`; never ask "is the phone connected?" — `/adb-check`
decides. The `~/tmp/` copy is the sideload fallback if no device.

Then **stop.** 白い熊 installs over the previous fork build (same keystore → in-place update, no
uninstall) and verifies the customizations survived the upstream bump. They may report regressions →
iterate locally (more edits, rebuild a higher `+N`) — still **no push**. Report the build + deliver
result, the version move, anything non-trivial you resolved, and any dropped/empty commits, then wait.

## Step 7 — Only when 白い熊 says "Push"

```bash
git push origin master                         # the deferred ff from Step 2 (only if master moved)
git push --force-with-lease origin custom      # the rebased stack — history rewritten
```

`--force-with-lease` (never bare `--force`) so a surprise update to `origin/custom` aborts instead of
clobbering. **Never** `git push upstream` — upstream is fetch-only. Verify it landed:
```bash
git fetch origin
[ "$(git rev-parse custom)" = "$(git rev-parse origin/custom)" ] && echo "custom landed"
git merge-base --is-ancestor upstream/master origin/custom && echo "custom is on fresh master"
```
Then delete the safety branch: `git branch -D "custom-pre-rebase-$(git rev-parse --short upstream/master)"`.

Cutting a **GitHub release** of this build (README/CHANGELOG/tag) is the separate **`publish-version`**
skill — not part of this sync.

## Step 8 — Refresh the docs to the new base

If the base version moved (Step 4), update the base references so the next session is accurate:

- `CLAUDE.md`: the "upstream base **4.0.5**" lines (Project section, and anywhere else 4.0.5 appears as
  the base).
- `appmanager-fork/SKILL.md`: the base/version examples (`Base`, `Versioning`, the APK-name examples,
  the `customBaseVersion*` block, any `4.0.5`/`445` references).
- This skill's examples, if a value here drifts from reality.

`gradle.properties` was already updated in Step 4. Commit the doc updates on `custom` (plain subject,
e.g. `docs: refresh fork base to ${new_name}`). Treat the sync as incomplete until the docs reflect the
new base. (Push them with the next push, or amend if still local.)

## Reference — conflict watch-points (condensed from appmanager-fork + CLAUDE.md)

- **`app/build.gradle` `defaultConfig`** — keep the `-P`-driven version block (Step 3), `applicationId
  "shiroikuma.oyokanri"`, `abiFilters 'arm64-v8a'`. Conflicts on upstream's literal version bump every
  sync → keep ours; adopt the new numbers via `gradle.properties` in Step 4. Java `namespace`
  (`io.github.muntashirakon.AppManager`) is **unchanged** — never rename it.
- **`BuildExpiryChecker`** — our neuter returns `false` always (release never "expires"). Keep ours;
  the `getBuildType()`/time helpers are left untouched by design (minimal diff for clean rebase).
- **`server/build.gradle`** — the server-JAR generation (per-variant `d8` into a temp dir + atomic
  `Files.move` into `assets/`, plus the `merge<Variant>Assets dependsOn create<Variant>ServerJars`
  ordering edge). Watch if upstream restructures the server build or finishes its configuration-cache
  migration; an empty `main.jar` silently breaks ADB mode (the server-JAR gate in Step 5 catches it).
- **AM Debug feature exposure** (Finder, Crazy Logger, scanner missing-classes, historical operations,
  Unfrozen filter) — the `BuildConfig.DEBUG`-gated sites we flipped on. Some DEBUG-gated sites are
  intentionally left alone (see `appmanager-fork` → "Several other BuildConfig.DEBUG-gated sites") —
  don't reflexively flip them on rebase.
- **The 93-commit feature stack** — CLAUDE.md's *Key files & chokepoints* table is the map for
  re-anchoring each feature if upstream moves the file it hooks: `FreezeUtils` / `PackageInstallerCompat`
  (protected `必要` profile), `MainToolbarPrefs` / `MainRecyclerAdapter` / `MainViewModel` (main list,
  notes, pills, batch snap), `BatchOpsManager` / `BatchOpsProgressMonitor` (batch progress dialog),
  `ForkThemeUtils` / `UIUtils` (theme + toasts), the `processreaper/` package (process monitor),
  `SettingsBackupManager` (export/import). **General rule: small → resolve; significant or lossy → stop
  and get 白い熊's approval (Step 3).**

## Related skills

- **`appmanager-fork`** — the canonical skill this one orchestrates: project identity, remotes/branch
  model, "Why master, not a tag", the full build/sign/deploy pipeline, the server-JAR landmine, the
  versioning scheme, the per-commit conflict watch-points, and the upstream-sync detail. Read it first.
- **`build`** — the release build & deploy pipeline invoked in Step 5.
- **`publish-version`** — cut a GitHub release of the built APK (separate from this sync).
- **`after-build`** — the automatic delivery used in Step 6 (`/adb-check` → `/adb-push` or `/scp`).

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` / "Generated with
Claude" trailer to commit messages or PR bodies; end the message at the last line of the body. This
overrides the harness default. (Global + repo rule: `~/.claude/CLAUDE.md` / `CLAUDE.md`.)
