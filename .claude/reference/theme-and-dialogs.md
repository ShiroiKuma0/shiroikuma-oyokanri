# Fork theme, toasts, dialogs and bottom sheets

`ForkThemeUtils`, themed toasts, dialog and bottom-sheet borders, and the Material You clobber.

> Moved verbatim out of `CLAUDE.md` (2026-11-15) to keep that file under the size limit. `CLAUDE.md` holds the index; add new knowledge about this area **here**, not there.

| Concern | Notes |
|---|---|
| Toast theming (black/yellow) | `app/src/main/java/io/github/muntashirakon/AppManager/utils/UIUtils.java` routes every `displayShortToast` / `displayLongToast` / `displayLongToastPl` through a custom view (`layout/toast_shiroikuma.xml`). Colours/border now come from the **configurable fork theme** at runtime via `ForkThemeUtils` (the static `drawable/bg_toast_shiroikuma.xml` is no longer the source of truth). Custom toast views only render in the foreground on Android 12+. |
| Configurable fork theme | `app/src/main/java/io/github/muntashirakon/AppManager/utils/ForkThemeUtils.java` is the single source of truth for text/background/border colour + border width (prefs `PREF_THEME_*` in `AppPref`, accessors in `Prefs.Appearance`, UI in `preferences_appearance.xml` + `AppearancePreferences`). Styles **both** the themed toasts and the batch-progress dialog. Defaults reproduce the original hard-coded bright-yellow-on-black look. |
| Dialog **and bottom-sheet** borders | Two different mechanisms, and the second exists because the first structurally cannot reach sheets. A dialog's frame replaces the **window background** after `show()` — `UiUtils.applyForkDialogBorder`, attr `forkDialogBorderDrawable`, see the ForkDialog note in the Snooping row. A **bottom sheet is not an `AlertDialog`**: it has no window background to replace, and the dialog helper's 16dp inset would be wrong anyway for something flush with the screen edge. So `UiUtils.applyForkBottomSheetBorder(View)` sets `drawable/bottom_sheet_bg_yellow_on_black` (top corners rounded only — rounding the bottom curves the stroke away and leaves two notches) as the background of the sheet's own container, called once in `CapsuleBottomSheetDialogFragment.onCreateView`, the base **every** sheet in the app inherits. **The attr `forkBottomSheetBorderDrawable` sits on `AppTheme.YellowOnBlack`, NOT on the dialog overlay** — a sheet resolves attributes from the hosting activity's theme, so beside `forkDialogBorderDrawable` it would resolve to nothing and every sheet would stay borderless. `SelectionListBottomSheet` is deliberately exempt: it is a raw `BottomSheetDialog` that already makes the M3 sheet transparent and paints its own `ForkThemeUtils` panel. |
| Fork theme vs. Material You | **LANDMINE — dynamic colour overwrites the fork palette (measured on a Motorola razr 40 ultra, Android 15, 2026-08-01).** `AppearanceUtils.onActivityPreCreated` pins `AppTheme_YellowOnBlack` for the fork's own screens and then called `DynamicColors.applyToActivityIfAvailable` **unconditionally** three lines later, which rewrites `colorPrimary`/`colorSurface`/`colorOnSurface` from the wallpaper — a fresh install rendered **white-on-green**. It hid for months because that call is a **no-op wherever the platform does not offer Material You**: on the Mate XT (EMUI) it never fired, so the bug only appeared on the first phone that supports dynamic colour. Now skipped when `isYellowOnBlackActivity(activity)`, kept for every other screen. This is also the clobber behind the dialog-theming landmine — the yellow-on-black overlay must be passed explicitly at every call site precisely because this wiped `materialAlertDialogTheme`. |

## Menus and switches by the black-yellow skill (+104, 2026-11-15)

- **Every popup row is `utils/PillMenuItemView`.** The app's own copies of AppCompat's
  `abc_popup_menu_item_layout.xml` and `abc_cascading_menu_item_layout.xml` (verbatim 1.7.1, root view swapped)
  override the library's, so popup menus, long-press menus, submenus, the toolbar ⋮ overflow and the
  multi-selection overflow in `libcore/ui` all get it with no call site involved. **When AppCompat is upgraded,
  diff those two files against the new library copies.**
- The row decides everything itself in `initialize()`: action row → full-width outlined pill (1.25 dp), icon
  inside at the left, › when it opens something, warning colour (`ColorPrefs.MONITOR_LEAK`) when destructive,
  38 % when disabled; toggle row (checkable, not exclusive) → plain, 36 dp, with `ForkSwitchDrawable` as the
  checkbox's button; exclusive (radio) rows and the disabled header line stay plain.
- **`utils/MenuPills` holds the tables**: `ICONS` by item id, `TITLE_ICONS` by title string for menus built in
  code (`menu.add(R.string.x)` gives id 0), `OPENS`, `DESTRUCTIVE`. A new menu item gets its icon in XML or a
  line there. `R.id.action_delete` is "Delete" in most menus and "Manage saved logs" in the log viewers — the
  title decides.
- The icon column: `MenuPills.anyIcon` — a menu where no row has an icon (the app-usage sort list, the search
  type list) has no column; otherwise every row keeps it (`setForceShowIcon`). The menu of an item comes from
  `androidx.appcompat.view.menu.ForkMenuAccess` (one class in AppCompat's package: `MenuItemImpl.mMenu` is
  package-private).
- **No-scroll rule:** show a `PopupMenu` with `MenuPills.show(popup, anchor)`, never `popup.show()` — it shifts a
  tall menu up by what is missing below its anchor.
- A popup built by hand uses `MenuPills.pillRow(...)` (the monitor's ⋮ menu); `SortMenuPopup` is a list of pure
  choices and stays iconless, its Reverse row carries the switch.
- On a screen that is not black-yellow (`AppearanceUtils.isYellowOnBlack(context)` false) the rows are still
  pills, in that screen's own text colour.
- **Switches:** `AppTheme.YellowOnBlack` names `utils/ForkViewInflater` as `viewInflaterClass`, which runs
  `ForkSwitch.outline` on every inflated `MaterialSwitch`: hollow track, one dot of 46 % for both states, accent
  when on and an opaque darker accent when off. A page that tints a switch with a meaning-carrying colour (the
  盗み見 tab) does so afterwards and keeps the outline. Never set a filled track tint again.

## The whole black-yellow skill, on every screen (+123, 2026-11-21)

白い熊's decisions that day: **all** screens are black-yellow; the UI page is a screen of its own; the primary
colours are `#FFFF00` / `#000000` but **every item is settable separately, nothing pinned in XML**; glyph-only
buttons stay glyphs; multi-select checkboxes stay selection marks.

- **`utils/ForkPalette` — the colour roles, and how XML follows them.** A theme attribute, drawable or layout can
  only name a colour *resource*. Each role is one (`@color/fork_*`; the text/accent base is the long-standing
  `@color/theme_bright_yellow`, the ground `@color/fork_ground`), and `ForkPalette.apply(context)` lays the stored
  colours over them with a `ResourcesLoader` (Android 11+; below that XML shows the defaults, code-drawn surfaces
  still follow). It runs for the application in `AppearanceUtils.init` and for each activity in
  `onActivityPreCreated`, **before** `setTheme`. A change bumps an epoch: the UI screen recreates itself, every
  other activity recreates in `onActivityResumed` (`ForkPalette.isStale`).
- **The loader comes from `com.google.android.material.color.ForkColorLoader`** — one class in Material's package,
  because `ColorResourcesLoaderCreator` is package-private. Material's public `ColorResourcesOverride` is NOT used:
  it also applies the "personalized colours" overlay, i.e. the wallpaper palette. **On a Material upgrade, check
  that `ColorResourcesLoaderCreator.create(Context, Map)` still exists.**
- **Storage.** Roles live in `ColorPrefs` (`shiroikuma_colors`, exported). The two bases are the old
  `Prefs.Appearance` theme text/background — `ColorPrefs` maps `ForkPalette.TEXT` / `GROUND` onto them, so the
  Appearance page and the UI screen share one store. A role's default cascades from a base (`ROLES` table), so a
  base change moves everything not given a colour of its own. **LANDMINE:** a default must never be read back from
  a resource the palette overrides (it would return the setting as its own default) — `SETTINGS_DESCRIPTION`'s
  default is the literal for that reason.
- **Not settable, and why:** the splash window (`AppTheme.Splash`) — the system draws it from the manifest theme
  before any app code runs, so no loader exists yet; it stays literal black. Home-screen widgets keep the
  upstream themes (they sit on the launcher's surface).
- **All screens:** `isYellowOnBlackActivity` returns true; a `getTransparentBackground()` activity gets
  `AppTheme.YellowOnBlack.Transparent`; `DynamicColors` is no longer applied to any activity (only to widget
  contexts). `KeyStoreActivity` and the splash content themes were moved over by hand (not `PerProcessActivity`).
- **`utils/Pills` + `ForkViewInflater` — buttons, floating buttons, top bars.** The inflater restyles every
  inflated `Button` / `MaterialButton` / extended FAB as an outlined pill **when it is attached** (by then its
  label and checkable state are final): main = a filled style in the layout, a bare `<Button>`, or a dialog's
  `android.R.id.button1` (3 dp, bold); everything else 1.25 dp. A label in `Pills.DESTRUCTIVE` (Delete, Uninstall,
  Purge, …) turns frame and label red (`ForkPalette.DELETE`) — that is also what makes a deleting dialog's main
  pill red, with no call site. **Left alone:** a button with no label (a glyph), an `IconButton` style, and a
  checkable button (a selector segment keeps its fill as the selection mark). An icon beside a label is tinted by
  the role told from the button's id name. A FAB becomes ground + accent ring; `MaterialToolbar` and libcore's
  `AppBarLayout` take the top-bar roles. A button built in code: `Pills.button(b, main)` /
  `Pills.plain(textView, main)`.
- **Menu icons are coloured by role** (`MenuPills.roleOf` → `roleForName`: word fragments of the item's id name,
  delete first; for menus built in code the name of the matching `TITLE_ICONS` string). Frame and label are the
  menu's own roles; a deleting row is red all over (`ForkPalette.DELETE`, no longer `MONITOR_LEAK`, whose orange
  collided with share). A new verb that reads wrong gets a line in `ROLE_WORDS`.
- **Switch colours are roles** (`SWITCH_ON`, `SWITCH_OFF`); `ForkSwitch.outline(sw, on, ground)` remains for a
  switch that carries a meaning in its colour (盗み見).
- **The UI screen** is `fonts/UiSettingsActivity` hosting `FontsPreferences`; Settings' `fonts_prefs` row and the
  two toolbar shortcuts start it. `FontsPreferences` builds each section as a `view_font_group` tagged with an id
  by `header(group, titleRes)` — **the id is the entry name of the title string: never rename a section's title
  string**, it is what the stored order is made of. `UiPageSections.arrange` then lays out the "App settings"
  main pill, the chip index (`UiFlowLayout`; tap = unfold + scroll, long-press = live drag-reorder, sections
  follow on drop) and the sections, **all folded by default**; order and fold state are `ui_section_order` /
  `ui_sections_open` in `shiroikuma_colors`. `UiRowIcons` gives every row an icon as the label's start drawable
  (`auto` from the string's name; `sweep` for the shared element layouts). Action pills hug their label.
  "Reset section order" is in Maintenance.
- **Not checked on the device.** Everything above was verified in the code only (白い熊, 2026-11-21: no
  screenshots). The thirty-five upstream screens were never drawn black-yellow before — expect stray surfaces
  that hard-code a Material colour in their own layouts or code.

## +124 — the four items left open by +123, and the switch (2026-11-21)

- **The switch is drawn whole by `ForkSwitch`** (52 × 28 dp, 2 dp outline as the *track* drawable, a dot of 46 %
  in a `LayerDrawable` thumb). **LANDMINE:** `SwitchCompat` reads a thumb drawable's *padding* as "this much may
  overhang the track"; an `InsetDrawable` reports its insets as padding, so until +123 the dot hung out over the
  left end, and upstream's "medium" style made the whole thing tiny. Never wrap the thumb in an `InsetDrawable`,
  and never set a transparent track tint — recolour through `ForkSwitch.tint(sw, tint)` (the 盗み見 tab does).
- **Hand-built pills follow the Buttons roles** through `Pills.frameFor` / `textFor`: a builder handed the plain
  accent gets `BUTTON_FRAME` / `BUTTON_TEXT`; a meaning-carrying ink is left alone. Wired into
  `RowPills.styleActionPill` (pane, batch pane, shelf), the monitor's two pill builders and the report dialog's
  `styleAction`. Row note/tag pills keep their own `CHIP` / `ADDPILL` roles.
- **The speed dial** (`libcore … FloatingActionButtonGroup`) builds its buttons in code: ground, an accent ring
  as foreground, red glyph while open. libcore cannot see the app's roles, so it asks the theme attributes they
  feed (`colorSurface`, `colorPrimary`, `colorError`).
- **List dialogs.** `AlertDialog.setItems` rows are full-width icon pills (`listItemLayout` →
  `fork_alert_list_item` → `utils/ForkListPillText`); `SearchableItemsDialogBuilder` rows carry an icon without a
  frame (the app's own copy of libcore's `m3_alert_select_dialog_item` → `utils/ForkListItemText` — **diff the two
  when libcore's copy changes**). The role is read from the label with the menus' word table. Single- and
  multi-choice lists keep their marks.
- **Every Material colour attribute names a role** in `AppTheme.YellowOnBlack` (secondary, tertiary, the fixed
  and inverse families, surface variant, outline variant, error, hint, link…) — the defaults were where upstream
  screens found purple and grey. **Night resources are forced** (`Prefs.Appearance.getNightMode()` returns
  `MODE_NIGHT_YES`): the ground is ours on every screen, so the base must be Material's dark one.
- **A role resource must not have a `-night` twin** — a night-qualified value beats the run-time loader's
  default-config entry. libcore's `highlight` has one, so the selection role is `fork_selection` and `highlight`
  is an alias to it in both `values` and `values-night`.
- New roles: `SELECTION` (selected row, search match — was libcore's unreadable pale yellow) and `TRACK`
  (the track behind a bar). The code editor's chrome (plain text, line numbers, current line, selection, cursor)
  takes the roles in `EditorThemes.fixColor`; syntax colours and the terminal's ANSI colours stay.
