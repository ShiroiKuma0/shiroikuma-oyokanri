#!/usr/bin/env bash
# Print the fork versionName exactly as app/build.gradle computes it:
#
#   <customBaseVersionName>[.<upstream base date>.g<sha8>]+<customBuildNumber, 3 digits>
#
# e.g. 4.1.0.2026-06-29.gfc1e7007+089
#
# This is the SHELL half of the version scheme and the only place it is spelled out for the
# build pipeline: the APK filename and the manifest version agree only while both derive the
# pin the same way. app/build.gradle's upstreamBasePin() is the Gradle half — change one and
# you must change the other.
#
# The pin is the merge-base of HEAD and master (the upstream mirror), i.e. the upstream commit
# our patches sit on — never our own HEAD, never master's tip. The date is that commit's
# committer date in UTC, so every build on one upstream base shares it and the names in ~/tmp
# sort chronologically. Missing git or missing master degrades to no pin at all rather than
# failing: nothing here is worth losing a build over.

set -euo pipefail

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
GP="$REPO_ROOT/gradle.properties"

[[ -f "$GP" ]] || die "gradle.properties not found: $GP"

base_name=$(grep -oP '^customBaseVersionName=\K.+' "$GP" || true)
[[ -n "$base_name" ]] || die "customBaseVersionName=<string> line not found in $GP"

build_no=$(grep -oP '^customBuildNumber=\K\d+' "$GP" || true)
[[ -n "$build_no" ]] || die "customBuildNumber=<int> line not found in $GP"

pin=""
sha=$(git -C "$REPO_ROOT" merge-base HEAD master 2>/dev/null | cut -c1-8 || true)
if [[ ${#sha} -eq 8 ]]; then
    # format-local: renders in $TZ, hence the TZ=UTC — plain format: would use the commit's
    # own offset and pin some commits a day early.
    base_stamp=$(TZ=UTC git -C "$REPO_ROOT" show -s --format=%cd --date=format-local:%Y-%m-%d.%H-%M "$sha" 2>/dev/null || true)
    if [[ ${#base_stamp} -eq 16 ]]; then
        pin="+${base_stamp}.g${sha}"
    else
        pin="+g${sha}"
    fi
fi

printf '%s%s+%03d\n' "$base_name" "$pin" "$build_no"
