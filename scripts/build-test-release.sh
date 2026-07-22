#!/usr/bin/env bash
set -euo pipefail

if [[ "${ANDROML_PREPARE_LLAMA_CPP:-false}" == "true" ]]; then
  "$(dirname "$0")/prepare-llama-cpp.sh"
fi

test -n "${ANDROML_RELEASE_KEYSTORE:-}"
test -f "$ANDROML_RELEASE_KEYSTORE"
test -n "${ANDROML_RELEASE_STORE_PASSWORD:-}"
test -n "${ANDROML_RELEASE_KEY_ALIAS:-}"
test -n "${ANDROML_RELEASE_KEY_PASSWORD:-}"

gradle_args=(
    :app:assembleOssRelease
    :app:bundleOssRelease
    --no-daemon
    --console=plain
    --stacktrace
)

# The native pack is materialized immediately before this invocation. Do not reuse a
# configuration-cache graph created by the no-vendor verification job.
if [[ "${ANDROML_PREPARE_LLAMA_CPP:-false}" == "true" ]]; then
    gradle_args+=(--no-configuration-cache)
fi

if ./gradlew "${gradle_args[@]}"; then
    printf 'test_release_build_ok=first_attempt\n'
    exit 0
fi

printf 'test_release_build_retry=transient_build_failure\n' >&2
./gradlew "${gradle_args[@]}"
printf 'test_release_build_ok=second_attempt\n'
