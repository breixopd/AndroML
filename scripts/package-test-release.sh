#!/usr/bin/env bash
set -euo pipefail

version_name="${1:?usage: package-test-release.sh <semver>}"
apk_source="app/build/outputs/apk/oss/release/app-oss-release.apk"
artifact_dir="dist/test-release"
artifact_name="androml-oss-universal-v${version_name}.apk"

test -f "$apk_source"
mkdir -p "$artifact_dir"
cp "$apk_source" "$artifact_dir/$artifact_name"

./scripts/verify-test-release.sh "$artifact_dir/$artifact_name" "$version_name"
sha256sum "$artifact_dir/$artifact_name" > "$artifact_dir/$artifact_name.sha256"
sha512sum "$artifact_dir/$artifact_name" > "$artifact_dir/$artifact_name.sha512"

printf 'release_artifact_dir=%s\n' "$artifact_dir"
