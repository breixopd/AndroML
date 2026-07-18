#!/usr/bin/env bash
set -euo pipefail

version_file="VERSION"
config_file="release-please-config.json"
manifest_file=".release-please-manifest.json"

test -s "$version_file"
test -s "$config_file"
test -s "$manifest_file"

version="$(tr -d '[:space:]' < "$version_file")"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]

jq -e --arg version "$version" \
    '.packages["."]["release-type"] == "simple" and
     .packages["."]["version-file"] == "VERSION" and
     .packages["."]["changelog-path"] == "CHANGELOG.md"' \
    "$config_file" >/dev/null
jq -e --arg version "$version" '.["."] == $version' "$manifest_file" >/dev/null

if rg -n --glob '*.yml' --glob '*.yaml' \
    'STORE_SUBMISSIONS_ENABLED:[[:space:]]*['"'"']true['"'"']|google-play|fastlane[[:space:]]+supply|fdroidserver|upload-artifact.*play' \
    .github/workflows; then
    echo "store publication must remain disabled during the private phone-test period" >&2
    exit 1
fi

if rg -n --glob '*.yml' --glob '*.yaml' '^[[:space:]]+uses: .*@[vV][0-9]' .github/workflows; then
    echo "GitHub Actions must be pinned to immutable commit SHAs" >&2
    exit 1
fi

test -x scripts/package-test-release.sh
test -x scripts/verify-test-release.sh
printf 'release_config_ok=%s\n' "$version"
