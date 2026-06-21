---
name: publish-version
description: Publish the latest local build as a GitHub release of this fork — refresh the README (fork-style, major features), write a very specific CHANGELOG entry, tag the bare versionName, ensure the default branch is `custom`, and create the release with the ~/tmp APK attached. Use when 白い熊 says publish / release / cut a version / ship it to GitHub.
---

# Publish a version of shiroikuma-oyokanri to GitHub

Ship the **latest already-built** APK as a GitHub release, with a polished fork-style README and an
exhaustive CHANGELOG, landing the repo homepage on our fork work (`custom`).

This is **shiroikuma-oyokanri** — 白い熊's downstream-renamed fork of
[AppManager](https://github.com/MuntashirAkon/AppManager) (upstream base **4.0.5**), package
`shiroikuma.oyokanri`, label **白い熊 応用管理**, installable side-by-side with the official
`io.github.muntashirakon.AppManager` from F-Droid. The build / sign / deploy facts live in the
**`appmanager-fork`** and **`build`** skills — this skill is **only** about cutting a GitHub release of
an APK those have already produced.

> **Never rebuild to publish.** Attach the newest APK already in `~/tmp/`. The version you publish =
> that APK's versionName. If you think a fresh build is needed, that's a separate `build` run that
> 白い熊 drives and tests first — not part of publishing.

## 0. Detect the version

- Newest fork APK: `ls -t ~/tmp/shiroikuma-oyokanri_*.apk | head -1`.
- The **versionName** is the filename field between the first `_` and `_arm64` — e.g.
  `shiroikuma-oyokanri_4.0.5+122_arm64-v8a.apk` → **`4.0.5+122`**. Use it verbatim everywhere (tag,
  README latest-release line, release title, changelog heading). The `4.0.5` is `customBaseVersionName`
  (mirrors upstream's base) and the `+122` tail is `customBuildNumber`, bumped per build by
  `tools/bump-build.sh` (see `gradle.properties`). If there is no APK in `~/tmp/`, stop and tell 白い熊
  to build first — do **not** build it yourself.

## 1. Ensure the homepage lands on `custom`

The GitHub repo's **default branch must be `custom`** (so visitors see our fork, not the
upstream-mirroring `master`). The repo currently still defaults to `master` — flip it on the first
publish:
```bash
gh repo view ShiroiKuma0/shiroikuma-oyokanri --json defaultBranchRef --jq '.defaultBranchRef.name'
# if it is not "custom":
gh repo edit ShiroiKuma0/shiroikuma-oyokanri --default-branch custom
```

## 2. Refresh `README.md` (fork-style, major features)

The repo currently carries **upstream's** README (`# App Manager`). Replace it with our fork-style
homepage — the centered-header, "**a fork of X with major additions**" style modelled on the sibling
**shiroikuma-jami** / **shiroikuma-jiyusagyoban** READMEs. Structure:

- **Centered header block** (`<div align="center">`): the app icon
  (`app/src/main/res/mipmap-xxxhdpi/ic_launcher.png`, width 120), the title **白い熊 応用管理**, the name
  gloss, a one-line "Android app manager & control panel" tagline, and a
  **"A fork of [AppManager](https://github.com/MuntashirAkon/AppManager) with major additions: …"**
  sentence that names the headline features.
- The **side-by-side install** note: package `shiroikuma.oyokanri` (renamed from the former
  `shiroikuma.appmanager`), so it installs alongside the official `io.github.muntashirakon.AppManager`
  from F-Droid (different signing key — never installs over official App Manager; arm64-v8a only).
- The **latest-release line** — update the version to the one from step 0:
  `**📥 Latest release: [\`<versionName>\`](…/releases/latest)** — [all releases & APK downloads »](…/releases)`.
- Then a **section per major feature** (emoji heading + a few real sentences each), in importance order.
  **Pick the updates that matter most vs stock App Manager** and describe them invitingly. Maintain/extend
  these to reflect everything currently shipped — at the time of writing the headline set is:
  - 🎨 **Configurable yellow-on-black UI** — the fork's signature look (black backgrounds, `#FFFF00`
    foreground), plus a full **白い熊 応用管理 UI** customization page (long-press the toolbar overflow):
    per-element fonts + colours, main-list column layout, an edge-to-edge separator grid, the
    running-app box, the selected-card frame, themed toasts, and yellow-bordered dialogs/buttons.
  - 🛡️ **Protected profile (`必要`)** — any app in an apps-profile named `必要` is **hard-blocked from
    being frozen or uninstalled** at the two low-level chokepoints, so no UI path (single, batch,
    profile-apply) can bypass it.
  - 📊 **Process monitor / reaper** — a from-scratch replacement for the legacy "Running apps" screen:
    PSS-ranked memory, live instantaneous CPU%, a smart kill router, leak detection & grouping, an
    editable protected list, a per-process detail page, and a faceted filter + search.
  - ⏯️ **Batch-op progress dialog** — an in-app pop-up mirroring the batch notification, with
    **Pause/Continue** and **Cancel**, plus a one-pass main-list snap to the final state on completion.
  - ❌ **Main-list quick actions** — a one-tap **force-stop ✕** on running app rows, a **configurable
    app-icon size**, per-app **notes**, and per-row **profile pills** with quick add-to-profile.
  - 🔧 **AM Debug features in a release build** — Finder, Crazy Logger, the scanner's missing-classes
    view, historical operations, and the Unfrozen filter, with the upstream build-expiry checker
    neutered so the release never "expires".
  - 🇯🇵 **Japanese label & translations** and a full **de-brand** to this fork (package, label, remote).
- A closing **"Built on App Manager"** + license note: the fork inherits App Manager's **GPL-3.0**
  licence (`COPYING`).

Write real, specific prose — not a bullet dump. Keep it inviting, like the jami README.

## 3. Update `CHANGELOG.md` — exhaustive

There is no `CHANGELOG.md` yet — **create it on the first publish**. Add each new section **above** the
previous one:
```
## <versionName> — <YYYY-MM-DD>
```
(use the current date from the environment). **Be very specific — list everything in this release**:

- **First release:** summarize the whole fork stack — cross-check `git log master..custom --oneline`
  (the commit subjects are self-describing) and group the work into `###` subsections (Theme & UI
  customization, Protected profile, Process monitor, Batch operations, Main list, Notes & profiles,
  Debug features, Localization & de-branding, Build/infra, Fixes). Note the upstream base it's built on
  (the `customBaseVersionName`, e.g. `4.0.5`).
- **Subsequent releases:** the previous fork tag is the latest existing GitHub release
  (`gh release list --repo ShiroiKuma0/shiroikuma-oyokanri`); list everything since with
  `git log <lastForkTag>..custom --oneline`, and cross-check the prior CHANGELOG section so nothing is
  missed.

This file is the authoritative, GitHub-readable record. Keep the `bump build number` /
`build: …` counter commits out of the prose — they're noise, not features.

## 4. Commit, tag, push, release

```bash
git add README.md CHANGELOG.md .gitignore
git commit -F - <<'MSG'
docs: changelog + README for <versionName> release
MSG
git push origin custom

# Annotated tag = the bare versionName, NO "v" prefix. Distinct from upstream's vX.Y.Z tags.
git tag -a "<versionName>" -m "白い熊 応用管理 <versionName>"
git push origin "<versionName>"

# Release notes = this version's CHANGELOG section. Use a LITERAL match (index($0,h)==1), NOT a regex:
# the "+N" tail puts a "+" in the versionName, and "+" is a regex metachar, so /^## <versionName>/
# would fail to match. index() treats the header as a plain string.
mkdir -p .scratch
awk -v h="## <versionName>" 'index($0,h)==1{p=1;next} /^## /{if(p)exit} p' CHANGELOG.md > .scratch/release-notes.md
gh release create "<versionName>" \
  --repo ShiroiKuma0/shiroikuma-oyokanri \
  --title "白い熊 応用管理 <versionName>" \
  --notes-file .scratch/release-notes.md \
  ~/tmp/shiroikuma-oyokanri_<versionName>_arm64-v8a.apk
```
Then verify: `gh release list --repo ShiroiKuma0/shiroikuma-oyokanri` shows it as **Latest** and
`gh release view "<versionName>" --repo ShiroiKuma0/shiroikuma-oyokanri --json assets` lists the APK.
Report the release URL.

## Hard rules / invariants
- **Never rebuild to publish** — attach the newest APK already in `~/tmp/` (step 0). Publishing never
  triggers a Gradle build.
- The transient `release-notes.md` goes in the gitignored **`.scratch/`**, never `~/tmp/`. `.scratch/`
  is not yet in this repo's `.gitignore` — add it once if missing:
  `grep -qxF '.scratch/' .gitignore || printf '\n.scratch/\n' >> .gitignore` (stage `.gitignore` with
  the docs commit).
- `gh` / `scp` / `git push` / `git tag … push` run **unsandboxed** (`dangerouslyDisableSandbox: true`).
- **No Claude/Anthropic attribution** in the commit, tag, or release body (global + repo `CLAUDE.md`).
  End the commit/tag message at the last line of its body.
- Tag is the **bare versionName** (e.g. `4.0.5+122`), no `v` — distinct from upstream's `vX.Y.Z` tags
  (e.g. `v4.0.5`) so the two never collide. (The `4.0.5` prefix matches the upstream base, but the
  missing `v` + the `+N` tail keep it unambiguous.)
- The release is cut from **`custom`**. Don't touch `master` here — it only mirrors upstream.
- Attach the **signed** APK from `~/tmp/` (the `build` pipeline signs it and copies it there); never the
  unsigned `app/build/outputs/...` artifact.
