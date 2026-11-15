# Device policy — hard locks delegated by 白い熊 雫 (`devicepolicy/`)

> Moved verbatim out of `CLAUDE.md` (2026-11-15) to keep that file under the size limit. `CLAUDE.md` holds the index; add new knowledge about this area **here**, not there.


Everything the Snooping page did before +57 is **soft**: an op we write or a permission we revoke can
be put back by Settings, by another tool, sometimes by the app. 白い熊 made the sister app **白い熊 雫**
(`shiroikuma.shizuku`, repo `shiroikuma-shizuku`) **Device Owner**; it delegates policy scopes to us,
and a decision can then be made **hard**.

- **Two channels, and the split is load-bearing.** `DevicePolicyBridge` covers what the platform will
  delegate (`DELEGATION_PERMISSION_GRANT`, `DELEGATION_PACKAGE_ACCESS`, `DELEGATION_BLOCK_UNINSTALL`)
  and calls the **public SDK with a `null` admin** — no hidden API, no binder relay, and it keeps
  working while 雫 is stopped, because `system_server` persists the delegation. `PolicyApiClient`
  covers what **no scope can carry** (`setPermittedAccessibilityServices`, `addUserRestriction`,
  `setAlwaysOnVpnPackage`, `setCameraDisabled`, `setUserControlDisabledPackages`) via
  `ContentResolver.call` on 雫's provider, authority **`shiroikuma.shizuku.policy`**. **Prefer the
  delegated path wherever it reaches** — routing a delegatable call through 雫 trades a persistent
  grant for a live dependency. Method names on the wire (`status`, `set_accessibility_blocked`,
  `set_user_control_disabled`, `set_user_restriction`, `set_always_on_vpn`, `set_camera_disabled`,
  `clear_all_locks`) are shared with 雫 — **never rename one**.
- **Why a provider call and not a broadcast** (unlike the 保存復元 contract): it is **synchronous** and
  answers with a `Bundle`, which is what a switch the user just tapped needs; and
  `Binder.getCallingUid()` identifies us on the far side, so no shared token is needed — the token in
  保存復元 exists precisely because a broadcast has no trustworthy sender.
- **`setDelegatedScopes` can only be called by the owner app**; there is no `dpm` shell command for it
  (checked against `dpm help` on Android 13 and 15). 白い熊 authorises us in 雫 → Settings → **Feature
  Hub** → **Device policy powers** (a `CollapsiblePreferenceCategory`, **collapsed by default** like
  every category on that page — `android:defaultValue` is read as *defaultExpanded*) → the 白い熊 応用管理
  row → *Allow device-policy powers*. One switch does both halves: 雫's own policy allowlist **and**
  `setDelegatedScopes`.
- **LANDMINE — the CALLEE mints the job id, and the caller must adopt it (白い熊, +116).** Every sister app answers `export`/`import` with `OK:<its own job id>` (`AutomationJobs.begin()`) and broadcasts completion carrying **that** id; `AppDataClient` invented a UUID, sent it, and then filtered replies on it — so the reply arrived, failed the correlation, was dropped, and the transfer sat until the two-minute silence watchdog killed it. A 22 MB ArcaneChat export that had already finished on disk was reported as **"Could not backup"**, which reads as a broken door rather than a broken client. `transfer()` now adopts the returned id (still accepting its own, which costs nothing) and holds terminal replies that arrive **before `call()` returns** in an `unclaimed` map, claiming them the moment the id is known — without that the fix works for a slow export and fails for a fast one.
- **LANDMINE — `setPermissionGrantState` accepts dangerous RUNTIME permissions and nothing else
  (measured 2026-08-01).** Having *a* permission is not enough: `GET_USAGE_STATS` is backed by
  `android.permission.PACKAGE_USAGE_STATS`, protection `signature|privileged|development|appop|
  retailDemo`, so a lock offered on *Read app usage* was accepted by our UI and thrown away by the
  platform. `AppDetailsSnoopingItem.policyLockable` is resolved from the protection level up front
  (`PROTECTION_DANGEROUS`) **and** requires the app to actually request the permission — the same
  discipline `SnoopingResolver#canWritePermission` applies to the ordinary write path. App-op-only
  rows can never be locked and show no padlock at all.
- **Read the lock from the platform, never from our own store.** `isPermissionLocked` asks
  `getPermissionGrantState`; a lock set by 雫 directly, or left by an older build, shows the padlock
  just the same. Every write re-reads afterwards and reports whether it landed;
  `setPackagesSuspended`'s return array is the packages it could **not** touch, so a non-empty return
  is a failure, not a success with a note.
- **LANDMINE — refreshing the card is not refreshing the row.** `loadPolicyState` ends in
  `notifyPolicyChanged`, which invalidates **row 0 only** (the policy card); a row's `policyLocked` is
  filled in by `SnoopingResolver`, i.e. only on a full reload. A lock landed, the toast said
  "Applied", and the padlock kept drawing its stale hollow self. Row-level writes go through
  `runRowPolicy`, which calls `refreshPolicyLockState(packageName)` (the cheap half, needing only the
  package name) and rebinds that row.
- **UI.** A policy card is pinned above the capabilities (`item_app_details_snooping_policy.xml`,
  adapter `TYPE_POLICY` at index 0 — added **only when there is at least one capability row**, because
  `AppDetailsFragment` drives the empty view off the item count). It states whether the powers are
  live and, when not, *why* — no Device Owner on this phone versus authorised in 雫 but not for us —
  carries the **suspend** switch (a harder freeze than hiding) and a pill into *More device-policy
  controls…*. Each capability row gets a **padlock column left of the toggle**, hollow where a lock
  could land and filled where one does, yellow in both states; **tap locks, tap again releases, no
  confirmation either way** (白い熊: the explanation belongs in the legend, not in a dialog dismissed
  every time).
- **`DangerDialog` has two shapes, and the distinction is deliberate.** `confirm` — 危険 heading,
  **Cancel in the positive slot**, destructive choice red in the quiet negative slot — is reserved for
  powers that can leave the phone hard to operate or recover (accessibility blocking, user
  restrictions, always-on VPN lockdown). `confirmReversible` — no heading, normal button placement —
  is for what this page releases in one tap. Calling suspension 危険 was simply wrong: it is undone by
  the same switch that applied it.
- **The way back must always exist.** `clear_all_locks` lives in 雫 because it must work when 応用管理
  is gone, and because the Device Owner can undo what its delegate did by walking a package's
  permissions for anything not at the default grant state — no ledger needed on either side. Our
  fallback releases what this page can see (its own locked rows, suspension, uninstall block) and
  reports honestly when the remote half did not answer.
- **The legend is the documentation.** ⋮ → *What the marks mean* explains the card frame, the remember
  box, the padlock, the status pill and what locking actually does. Add to it rather than inventing a
  new explanatory surface.
- **Remember/forget is NOT a device-policy matter** and no longer sits in that menu. It is the row's
  **long-press**, shown as a small rounded box around the switch (`rememberedBox`, built in code so it
  takes `ForkThemeUtils.getTextColor()`). `AppDetailsViewModel.rememberSnoopingSetting` deliberately
  stores a state equal to the platform default — the "store only departures" rule governs writes made
  through the switch, but here the user asked explicitly, and pinning a default is how you say *keep it
  this way even if the default changes*.
