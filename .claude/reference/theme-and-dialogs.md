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
