#!/usr/bin/env bash
set -euo pipefail

version_name="${1:?usage: package-test-release.sh <semver>}"
apk_source="app/build/outputs/apk/oss/release/app-oss-release.apk"
artifact_dir="dist/test-release"
artifact_name="androml-oss-universal-v${version_name}.apk"
manifest_name="androml-oss-universal-v${version_name}-manifest.json"

test -f "$apk_source"
mkdir -p "$artifact_dir"
find "$artifact_dir" -maxdepth 1 -type f -delete
cp "$apk_source" "$artifact_dir/$artifact_name"

./scripts/verify-test-release.sh "$artifact_dir/$artifact_name" "$version_name"
sha256sum "$artifact_dir/$artifact_name" > "$artifact_dir/$artifact_name.sha256"
sha512sum "$artifact_dir/$artifact_name" > "$artifact_dir/$artifact_name.sha512"

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
apksigner="${sdk_root}/build-tools/37.0.0/apksigner"
certificate_sha256="$($apksigner verify --print-certs "$artifact_dir/$artifact_name" | \
    awk '/certificate SHA-256 digest:/ {print tolower($NF); exit}' | tr -d ':[:space:]')"
[[ "$certificate_sha256" =~ ^[a-f0-9]{64}$ ]]
jq -n \
    --arg version "$version_name" \
    --arg tag "v${version_name}" \
    --arg commit "$(git rev-parse HEAD)" \
    --arg package_id "dev.androml.app" \
    --arg apk "$artifact_name" \
    --arg certificate_sha256 "$certificate_sha256" \
    '{version: $version, tag: $tag, commit: $commit, package_id: $package_id,
      artifact: $apk, signing_certificate_sha256: $certificate_sha256,
      store_submissions_enabled: false}' \
    > "$artifact_dir/$manifest_name"

printf 'release_artifact_dir=%s\n' "$artifact_dir"
