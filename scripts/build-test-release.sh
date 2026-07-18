#!/usr/bin/env bash
set -euo pipefail

test -n "${ANDROML_RELEASE_KEYSTORE:-}"
test -f "$ANDROML_RELEASE_KEYSTORE"
test -n "${ANDROML_RELEASE_STORE_PASSWORD:-}"
test -n "${ANDROML_RELEASE_KEY_ALIAS:-}"
test -n "${ANDROML_RELEASE_KEY_PASSWORD:-}"

gradle_args=(
    :app:assembleOssRelease
    --no-daemon
    --console=plain
    --stacktrace
)

if ./gradlew "${gradle_args[@]}"; then
    printf 'test_release_build_ok=first_attempt\n'
    exit 0
fi

printf 'test_release_build_retry=transient_build_failure\n' >&2
./gradlew "${gradle_args[@]}"
printf 'test_release_build_ok=second_attempt\n'
