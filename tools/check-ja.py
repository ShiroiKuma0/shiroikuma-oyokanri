#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Fork: compare the Japanese key set with the default one (black-yellow skill, lines 33-34).

Every translatable string and plural of app/src/main/res/values/ must exist in values-ja/, with
the same printf placeholders. Exempt: translatable="false", string-arrays (lists of references
and machine values; the three data arrays included), and strings that are a bare @string/ alias.
Run from the repository root; exits 1 when a key is missing.
"""
import glob
import re
import sys

EL = re.compile(r'<(string|plurals|string-array)\s+name="([^"]+)"([^>]*?)(/>|>(.*?)</\1>)', re.S)
PH = re.compile(r'%(?:\d+\$)?[sdf]')


def load(pattern):
    out = {}
    for path in glob.glob(pattern):
        for m in EL.finditer(open(path, encoding='utf-8').read()):
            out[m.group(2)] = m
    return out


en = load('app/src/main/res/values/*.xml')
ja = load('app/src/main/res/values-ja/*.xml')
missing, placeholders = [], []
for name, m in sorted(en.items()):
    body = m.group(5) or ''
    if m.group(1) == 'string-array' or 'translatable="false"' in m.group(3):
        continue
    if name not in ja:
        missing.append(name)
    elif m.group(1) == 'string' and sorted(PH.findall(body)) != sorted(PH.findall(ja[name].group(5) or '')):
        placeholders.append(name)
print('default keys: %d, Japanese keys: %d' % (len(en), len(ja)))
print('missing in values-ja: %d' % len(missing))
for name in missing:
    print('  ' + name)
print('placeholder mismatches: %d' % len(placeholders))
for name in placeholders:
    print('  ' + name)
sys.exit(1 if missing or placeholders else 0)
