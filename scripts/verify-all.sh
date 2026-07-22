#!/usr/bin/env bash
set -euo pipefail

mode="local"
if [[ "${1:-}" == "--ci" ]]; then
    mode="ci"
    shift
fi
test "$#" -eq 0
test "${STORE_SUBMISSIONS_ENABLED:-false}" = 'false'

./scripts/verify-release-config.sh

gradle_args=(
    :core:model:testDebugUnitTest
    :core:files:testDebugUnitTest
    :core:database:testDebugUnitTest
    :core:security:testDebugUnitTest
    :core:device:testDebugUnitTest
    :core:network:testDebugUnitTest
    :core:rag:testDebugUnitTest
    :core:tools:testDebugUnitTest
    :core:agents:testDebugUnitTest
    :core:workflow:testDebugUnitTest
    :core:api:testDebugUnitTest
    :api:server:testDebugUnitTest
    :cluster:core:testDebugUnitTest
    :cluster:transport:testDebugUnitTest
    :runtime:api:testDebugUnitTest
    :runtime:litert:testDebugUnitTest
    :runtime:litertlm:testDebugUnitTest
    :runtime:onnx:testDebugUnitTest
    :runtime:executorch:testDebugUnitTest
    :runtime:service:testDebugUnitTest
    :optimizer:testDebugUnitTest
    :app:testOssDebugUnitTest
    :core:model:lintDebug
    :core:files:lintDebug
    :core:database:lintDebug
    :core:security:lintDebug
    :core:device:lintDebug
    :core:network:lintDebug
    :core:rag:lintDebug
    :core:tools:lintDebug
    :core:agents:lintDebug
    :core:workflow:lintDebug
    :core:api:lintDebug
    :api:server:lintDebug
    :cluster:core:lintDebug
    :cluster:transport:lintDebug
    :runtime:api:lintDebug
    :runtime:litert:lintDebug
    :runtime:litertlm:lintDebug
    :runtime:onnx:lintDebug
    :runtime:executorch:lintDebug
    :runtime:service:lintDebug
    :optimizer:lintDebug
    :app:lintOssDebug
    :app:assembleOssDebug
)

gradle_options=(--no-daemon --console=plain)
if [[ "$mode" == "local" ]]; then
    gradle_options+=(--stacktrace)
fi

./gradlew "${gradle_args[@]}" "${gradle_options[@]}"
printf 'verification_ok=mode:%s\n' "$mode"
