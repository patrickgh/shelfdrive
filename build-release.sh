#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
build_file="$root_dir/app/build.gradle.kts"

production_version_code="$(sed -nE 's/^val productionVersionCode = ([0-9]+)$/\1/p' "$build_file")"
current_version_name="$(sed -nE 's/^val productionVersionName = "([^"]*)"$/\1/p' "$build_file")"

if [[ -z "$production_version_code" || -z "$current_version_name" ]]; then
    printf 'Konnte Version aus %s nicht lesen.\n' "$build_file" >&2
    exit 1
fi

read -r -p "Neue Version [$current_version_name]: " version_name
version_name="${version_name:-$current_version_name}"

if [[ ! "$version_name" =~ ^[0-9A-Za-z][0-9A-Za-z._+-]*$ ]]; then
    printf 'Ungültige Version: %s\n' "$version_name" >&2
    exit 1
fi

next_version_code=$((production_version_code + 1))
temporary_build_file="$(mktemp "$root_dir/.build.gradle.kts.XXXXXX")"
trap 'rm -f "$temporary_build_file"' EXIT

awk -v code="$next_version_code" -v name="$version_name" '
    /^val productionVersionCode = / { print "val productionVersionCode = " code; next }
    /^val productionVersionName = / { print "val productionVersionName = \"" name "\""; next }
    { print }
' "$build_file" > "$temporary_build_file"
mv "$temporary_build_file" "$build_file"
trap - EXIT

printf 'Baue prod (%s) und diagnostics (%s-diagnostics) mit VersionCode %s/%s ...\n' \
    "$version_name" "$version_name" "$next_version_code" "$((next_version_code + 1))"

cd "$root_dir"
./gradlew bundleProdRelease bundleDiagnosticsRelease
