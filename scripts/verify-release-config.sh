#!/usr/bin/env bash
set -euo pipefail

test "${STORE_SUBMISSIONS_ENABLED:-false}" = 'false'

version_file="VERSION"
config_file="release-please-config.json"
manifest_file=".release-please-manifest.json"

test -s "$version_file"
test -s "$config_file"
test -s "$manifest_file"
test -s CHANGELOG.md

version="$(tr -d '[:space:]' < "$version_file")"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]

jq -e --arg version "$version" \
    '.packages["."]["release-type"] == "simple" and
     .packages["."]["version-file"] == "VERSION" and
     .packages["."]["changelog-path"] == "CHANGELOG.md" and
     .packages["."]["include-v-in-tag"] == true' \
    "$config_file" >/dev/null
jq -e --arg version "$version" '.["."] == $version' "$manifest_file" >/dev/null

workflow_search() {
    local pattern="$1"
    if command -v rg >/dev/null 2>&1; then
        rg -n --glob '*.yml' --glob '*.yaml' "$pattern" .github/workflows
    else
        grep -R -n -E --include='*.yml' --include='*.yaml' "$pattern" .github/workflows
    fi
}

assert_workflow_pattern_absent() {
    local pattern="$1"
    local failure_message="$2"
    local matches
    local search_status

    if matches="$(workflow_search "$pattern")"; then
        printf '%s\n' "$matches" >&2
        echo "$failure_message" >&2
        return 1
    else
        search_status=$?
        if [ "$search_status" -ne 1 ]; then
            echo "workflow policy scan failed with status $search_status" >&2
            return "$search_status"
        fi
    fi
}

assert_workflow_pattern_absent \
    'STORE_SUBMISSIONS_ENABLED:[[:space:]]*['"'"']true['"'"']|google-play|fastlane[[:space:]]+supply|fdroidserver|upload-artifact.*play' \
    'store publication must remain disabled during the private phone-test period'

assert_workflow_pattern_absent \
    '^[[:space:]]+uses: .*@[vV][0-9]' \
    'GitHub Actions must be pinned to immutable commit SHAs'

test -x scripts/package-test-release.sh
test -x scripts/verify-test-release.sh
test -x scripts/build-test-release.sh
test -x scripts/verify-all.sh
printf 'release_config_ok=%s\n' "$version"
