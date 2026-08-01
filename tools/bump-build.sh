#!/usr/bin/env bash
# Bump customBuildNumber in gradle.properties by 1. Print new full version "X.Y.Z+N".
#
# Used by the dev build flow so every dev APK gets a fresh build number, which
# surfaces in the app's versionName (e.g. 4.0.5+3) and versionCode (e.g. 4450003)
# so Android knows newer builds are upgrades.
#
# Counter is monotonic. Gaps are fine — if you abort a build at the pause gate
# the bump still happened, that's by design. Use git checkout -- gradle.properties
# to roll back a specific bump if you really want.

set -euo pipefail

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
GP="$REPO_ROOT/gradle.properties"

[[ -f "$GP" ]] || die "gradle.properties not found: $GP"

current=$(grep -oP '^customBuildNumber=\K\d+' "$GP" || true)
[[ -n "$current" ]] || die "customBuildNumber=<int> line not found in $GP"

new=$((current + 1))
sed -i "s/^customBuildNumber=${current}\$/customBuildNumber=${new}/" "$GP"

base=$(grep -oP '^customBaseVersionName=\K.+' "$GP" || true)
[[ -n "$base" ]] || die "customBaseVersionName=<string> line not found in $GP"

# Zero-padded to three digits, matching app/build.gradle's versionName and the
# APK filename convention. Printing the raw integer here made the bump line
# disagree with everything downstream.
printf '%s+%03d\n' "$base" "$new"
