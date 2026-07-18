#!/usr/bin/env bash
set -euo pipefail

apk_path="${1:?usage: verify-test-release.sh <apk> [expected-version-name]}"
expected_version_name="${2:-}"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"

test -f "$apk_path"
test -n "$sdk_root"

build_tools_dir="${sdk_root}/build-tools/37.0.0"
apksigner="${build_tools_dir}/apksigner"
aapt="${build_tools_dir}/aapt"
test -x "$apksigner"
test -x "$aapt"

signing_report="$(mktemp)"
trap 'rm -f "$signing_report"' EXIT
"$apksigner" verify --verbose "$apk_path" >"$signing_report"
grep -q 'Verified using v2 scheme (APK Signature Scheme v2): true' "$signing_report"

badging="$($aapt dump badging "$apk_path")"
grep -q "package: name='dev.androml.app'" <<<"$badging"
if grep -q "application-debuggable" <<<"$badging"; then
    echo "release APK must not be debuggable" >&2
    exit 1
fi

if [ -n "$expected_version_name" ]; then
    grep -q "versionName='$expected_version_name'" <<<"$badging"
fi

printf 'verified_test_release=%s\n' "$apk_path"

