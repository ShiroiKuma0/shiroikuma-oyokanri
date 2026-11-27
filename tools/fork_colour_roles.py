#!/usr/bin/env python3
"""
白い熊 応用管理 — give every layout, shape and style a colour role of its own (+129).

The XML names shared colours: a role (@color/fork_ground, @color/theme_bright_yellow) or a theme
attribute that a role feeds (?attr/colorPrimary). Two items that share such a name cannot be
coloured apart. This script rewrites each of those references to a role that belongs to the item:

    layout    @color/fork_l__<area>__<base>__<kind>     area = the screens the layout belongs to,
                                                        kind = t text · i icon · g ground · f frame · x other
    shape     @color/fork_d__<file>__<base>__<kind>
    style     @color/fork_s__<Style_Name>__<attribute>  one role per colour attribute of a style

and writes the two files that declare them:

    res/values/fork_item_roles.xml        each role, its default an alias of the base it replaced
    java/.../utils/ForkItemRoles.java     the table ForkPalette reads

A role's default is the base it replaced, so nothing changes on screen until 白い熊 sets one.

THE SOURCES ARE NEVER REWRITTEN. The Gradle task `forkItemRoles` (app/build.gradle) runs this
before every build with `--out <dir>`: `res/` is copied to `<dir>/res`, the copy is rewritten, and
the build takes its resources from the copy. The layouts in the repository keep their names, so an
upstream rebase conflicts no more than before, and a layout upstream adds gets its roles by itself.

Same method as 白い熊 臨電話's tools/sk_colour_roles.py.

    python3 tools/fork_colour_roles.py --out app/build/generated/fork-item-roles
"""
import glob
import os
import re
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE_RES = os.path.join(ROOT, "app/src/main/res")
PALETTE = os.path.join(ROOT, "app/src/main/java/io/github/muntashirakon/AppManager/utils/ForkPalette.java")
PACKAGE_DIR = "io/github/muntashirakon/AppManager/utils"

if len(sys.argv) != 3 or sys.argv[1] != "--out":
    sys.exit("usage: fork_colour_roles.py --out <directory>   (the sources are never rewritten)")
OUT = os.path.abspath(sys.argv[2])
RES = os.path.join(OUT, "res")
JAVA_OUT = os.path.join(OUT, "java", PACKAGE_DIR)
shutil.rmtree(OUT, ignore_errors=True)
shutil.copytree(SOURCE_RES, RES)
os.makedirs(JAVA_OUT)

# ---- the bases: colour resource -> role key, read from ForkPalette's own table
palette = open(PALETTE, encoding="utf-8").read()
CONST = dict(re.findall(r'public static final String (\w+) = "(\w+)";', palette))
BASE = {}  # colour resource name -> role key
for const, res in re.findall(r"new Role\((\w+), R\.color\.(\w+),", palette):
    BASE[res] = CONST[const]
BASE["settings_description"] = "settings_description"  # ColorPrefs.SETTINGS_DESCRIPTION
RES_OF = {key: res for res, key in BASE.items()}
# A colour that is only another name of a role (<color name="highlight">@color/fork_selection</color>).
colors_xml = open(os.path.join(SOURCE_RES, "values", "colors.xml"), encoding="utf-8").read()
for name, target in re.findall(r'<color name="(\w+)">@color/(\w+)</color>', colors_xml):
    if target in BASE and name not in BASE:
        BASE[name] = BASE[target]

MAIN_THEME = "AppTheme.YellowOnBlack"
DIALOG_THEME = "ThemeOverlay.AppTheme.MaterialAlertDialog.YellowOnBlack"
MENU_THEME = "ThemeOverlay.AppManager.PopupMenu"

AREAS = [  # first match wins; the area is where the UI page lists the role
    (r"^(app_widget|widget_)", None),  # drawn by the launcher: the palette cannot reach them
    (r"^(dialog_|fragment_dialog|m3_alert|fork_alert|item_dialog)", "dialogs"),
    (r"^abc_|popup_menu", "menus"),
    (r"(fonts_settings|^view_font|^view_color_row|^view_legend|^view_separator|^view_selection"
     r"|main_toolbar_pref|ui_settings)", "uipage"),
    (r"(app_details|app_info|^pager_app|shared_lib|^view_freeze|^view_policy)", "details"),
    (r"(process|monitor)", "monitor"),
    (r"(batch_ops|op_log|op_history)", "oplog"),
    (r"(_fm|^item_path|file_)", "fm"),
    (r"(logcat|log_viewer|send_log)", "logs"),
    (r"(virus|vt_|scanner|class_lister)", "vt"),
    (r"(settings|preference|^item_switch|^item_right|^item_title_action|^item_text|^item_icon_title"
     r"|^item_checkbox)", "settings"),
    (r"^(activity_main|item_main|appbar|activity_list_screen|bottom_sheet_selected|item_selected_app"
     r"|main_|item_chip|view_toolbar)", "main"),
]


def area_of(name):
    for pattern, area in AREAS:
        if re.search(pattern, name):
            return area
    return "other"


def kind_of_attribute(attr):
    a = attr.split(":")[-1].lower()
    if "textcolor" in a or a == "hintcolor":
        return "t"
    if "stroke" in a or "divider" in a or "outline" in a:
        return "f"
    if "background" in a or "surface" in a:
        return "g"
    if "tint" in a:
        return "i"
    return "x"


found = {}  # role name -> (base key, area, kind, what, attribute)


def register(name, base, area, kind, what, attribute):
    found[name] = (base, area, kind, what, attribute)
    return "@color/" + name


# ---- styles: one role per colour attribute of a style
styles_path = os.path.join(RES, "values", "styles.xml")
styles = open(styles_path, encoding="utf-8").read()
THEME_ATTR = {}  # style name -> {attribute as written -> role name}


def style_block(m):
    style = m.group(1)
    slug = style.replace(".", "_")

    def item(i):
        attr, value = i.group(1), i.group(2).strip()
        c = re.fullmatch(r"@color/(\w+)", value)
        if not c or c.group(1) not in BASE:
            return i.group(0)
        name = f"fork_s__{slug}__{attr.replace(':', '_')}"
        THEME_ATTR.setdefault(style, {})[attr] = name
        return f'<item name="{attr}">{register(name, BASE[c.group(1)], "theme", "x", style, attr)}</item>'

    body = re.sub(r'<item name="([\w:]+)">([^<]*)</item>', item, m.group(2))
    return m.group(0)[: m.start(2) - m.start(0)] + body + "</style>"


new_styles = re.sub(r'<style name="([\w.]+)"[^>]*>(.*?)</style>', style_block, styles, flags=re.S)
changed = 0
if new_styles != styles:
    open(styles_path, "w", encoding="utf-8").write(new_styles)
    changed += 1

# An attribute that an overlay (dialog, popup menu) gives another colour means different things in
# different windows. Such a reference is split only in a layout whose window is known.
OVERLAID = set(THEME_ATTR.get(DIALOG_THEME, {})) | set(THEME_ATTR.get(MENU_THEME, {}))
WINDOW_KNOWN = re.compile(r"^(activity_|appbar|pager_|window_)")
# Rows and fragments that are only ever inflated on a screen, never in a dialog or a menu (+131):
# their window is known too. The value moves the layout to the area it is shown in, where the
# file name alone would file it elsewhere. A row that a dialog also shows (item_title_action,
# item_icon_title_subtitle, item_changelog_*) stays out and keeps the shared attribute.
SCREEN_ONLY = {
    "fragment_scanner": None,                  # ScannerFragment
    "item_shared_lib": None,                   # AppDetailsOtherFragment
    "item_right_summary": "details",           # AppInfoRecyclerAdapter
    "item_right_standalone_action": "details", # AppInfoRecyclerAdapter, and FmAdapter's rows
}

TOKEN = re.compile(r"\?(?:android:)?(?:attr/)?\w+|@color/\w+")


def attribute_role(token, area, layout):
    """The style role a ?attr reference resolves to in this layout's window, or None."""
    m = re.fullmatch(r"\?(android:)?(?:attr/)?(\w+)", token)
    attr = (m.group(1) or "") + m.group(2)
    order = [MAIN_THEME]
    if area == "dialogs":
        order.insert(0, DIALOG_THEME)
    elif area == "menus":
        order.insert(0, MENU_THEME)
    elif attr in OVERLAID and not (layout and (WINDOW_KNOWN.search(layout) or layout in SCREEN_ONLY)):
        return None
    for style in order:
        role = THEME_ATTR.get(style, {}).get(attr)
        if role:
            return role
    return None


def rewrite_value(value, prefix, kind, area, what, layout):
    def one(m):
        token = m.group(0)
        if token.startswith("@color/"):
            res = token[len("@color/"):]
            if res not in BASE:
                return token
            base, code = BASE[res], BASE[res]
        else:
            base = attribute_role(token, area, layout)
            if base is None:
                return token
            code = "a_" + base.split("__")[-1]
        return register(f"{prefix}__{code}__{kind}", base, area, kind, what, None)

    return TOKEN.sub(one, value)


ATTRIBUTE = re.compile(r'(?<![\w:])([\w:]+)="([^"]*)"')


def process(path, prefix, area, what, layout=None, kind_from_tag=False):
    source = open(path, encoding="utf-8").read()
    if kind_from_tag:
        out, pos, kind = [], 0, "x"
        for m in re.finditer(r"<(\w+)|" + ATTRIBUTE.pattern, source):
            out.append(source[pos:m.start()])
            pos = m.end()
            if m.group(1):
                kind = {"solid": "g", "stroke": "f"}.get(m.group(1), "x")
                out.append(m.group(0))
            else:
                out.append(f'{m.group(2)}="{rewrite_value(m.group(3), prefix, kind, area, what, layout)}"')
        out.append(source[pos:])
        result = "".join(out)
    else:
        def attribute(m):
            if m.group(1).startswith("tools:"):
                return m.group(0)
            kind = kind_of_attribute(m.group(1))
            return f'{m.group(1)}="{rewrite_value(m.group(2), prefix, kind, area, what, layout)}"'

        result = ATTRIBUTE.sub(attribute, source)
    if result != source:
        open(path, "w", encoding="utf-8").write(result)
        return 1
    return 0


for path in sorted(glob.glob(os.path.join(RES, "layout", "*.xml"))):
    stem = os.path.basename(path)[:-4]
    area = SCREEN_ONLY.get(stem) or area_of(stem)
    if area is not None:
        changed += process(path, f"fork_l__{area}", area, None, layout=stem)

for path in sorted(glob.glob(os.path.join(RES, "drawable", "*.xml"))):
    stem = os.path.basename(path)[:-4]
    if "<vector" in open(path, encoding="utf-8").read():
        continue  # an icon is a mask, tinted where it is used
    if area_of(stem) is None or stem.startswith("ic_splash"):
        continue  # the launcher's, and what the system draws before any code runs
    changed += process(path, f"fork_d__{stem}", "shapes", stem, kind_from_tag=True)

# ---- the two generated files
names = sorted(found)


def default_of(name):
    base = found[name][0]
    return "@color/" + (base if base in found else RES_OF[base])


with open(os.path.join(RES, "values", "fork_item_roles.xml"), "w", encoding="utf-8") as out:
    out.write('<?xml version="1.0" encoding="utf-8"?>\n'
              "<!-- GENERATED by tools/fork_colour_roles.py — do not edit. One colour role per item of the\n"
              "     XML; each defaults to the role it replaced. ForkPalette lays the stored colours over\n"
              "     them at run time. Default values/ only: a qualified twin would beat the loader. -->\n"
              "<resources>\n")
    for name in names:
        out.write(f'    <color name="{name}">{default_of(name)}</color>\n')
    out.write("</resources>\n")


def lit(text):
    return "null" if text is None else '"' + text + '"'


CHUNK = 120
with open(os.path.join(JAVA_OUT, "ForkItemRoles.java"), "w", encoding="utf-8") as out:
    out.write("// SPDX-License-Identifier: GPL-3.0-or-later\n\n"
              "package io.github.muntashirakon.AppManager.utils;\n\n"
              "import java.util.ArrayList;\nimport java.util.List;\n\n"
              "import io.github.muntashirakon.AppManager.R;\n\n"
              "/**\n * GENERATED by tools/fork_colour_roles.py — do not edit.\n *\n"
              " * One colour role for every item of the XML: a layout's text, icons, grounds and frames by\n"
              " * screen, every shape, every colour attribute of a style. Each falls back to the role it\n"
              " * replaced, so a role that was never set looks exactly as before.\n */\n"
              "final class ForkItemRoles {\n    private ForkItemRoles() {\n    }\n\n"
              "    static List<ForkPalette.Item> all() {\n"
              f"        List<ForkPalette.Item> list = new ArrayList<>({len(names)});\n")
    parts = [names[i:i + CHUNK] for i in range(0, len(names), CHUNK)]
    for i in range(len(parts)):
        out.write(f"        part{i}(list);\n")
    out.write("        return list;\n    }\n")
    for i, part in enumerate(parts):
        out.write(f"\n    private static void part{i}(List<ForkPalette.Item> list) {{\n")
        for name in part:
            base, area, kind, what, attribute = found[name]
            out.write(f'        list.add(new ForkPalette.Item("{name}", R.color.{name}, "{base}", "{area}", '
                      f"'{kind}', {lit(what)}, {lit(attribute)}));\n")
        out.write("    }\n")
    out.write("}\n")

by_area = {}
for name in names:
    by_area[found[name][1]] = by_area.get(found[name][1], 0) + 1
print(f"{changed} files rewritten, {len(names)} item roles: "
      + " ".join(f"{a}={n}" for a, n in sorted(by_area.items())))
