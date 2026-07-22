#!/usr/bin/env bash
set -euo pipefail

version_name="${1:?usage: package-test-release.sh <semver>}"
[[ "$version_name" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
apk_source="app/build/outputs/apk/oss/release/app-oss-release.apk"
if test ! -f "$apk_source"; then
    apk_source="$(find app/build/outputs/apk/oss/release -maxdepth 1 -type f -name '*universal*release*.apk' -print -quit)"
fi
arm64_apk_source="$(find app/build/outputs/apk/oss/release -maxdepth 1 -type f -name '*arm64-v8a*release*.apk' -print -quit)"
aab_source="app/build/outputs/bundle/ossRelease/app-oss-release.aab"
artifact_dir="dist/test-release"
artifact_name="androml-oss-universal-v${version_name}.apk"
arm64_artifact_name="androml-oss-arm64-v8a-v${version_name}.apk"
aab_name="androml-oss-v${version_name}.aab"
manifest_name="androml-oss-universal-v${version_name}-manifest.json"

test -f "$apk_source"
test -f "$arm64_apk_source"
test -f "$aab_source"
mkdir -p "$artifact_dir"
find "$artifact_dir" -maxdepth 1 -type f -delete
cp "$apk_source" "$artifact_dir/$artifact_name"
cp "$arm64_apk_source" "$artifact_dir/$arm64_artifact_name"
cp "$aab_source" "$artifact_dir/$aab_name"

./scripts/verify-test-release.sh "$artifact_dir/$artifact_name" "$version_name"
./scripts/verify-test-release.sh "$artifact_dir/$arm64_artifact_name" "$version_name" arm64-v8a
sha256sum "$artifact_dir/$artifact_name" > "$artifact_dir/$artifact_name.sha256"
sha512sum "$artifact_dir/$artifact_name" > "$artifact_dir/$artifact_name.sha512"
sha256sum "$artifact_dir/$arm64_artifact_name" > "$artifact_dir/$arm64_artifact_name.sha256"
sha512sum "$artifact_dir/$arm64_artifact_name" > "$artifact_dir/$arm64_artifact_name.sha512"
apk_sha256="$(sha256sum "$artifact_dir/$artifact_name" | awk '{print $1}')"
arm64_apk_sha256="$(sha256sum "$artifact_dir/$arm64_artifact_name" | awk '{print $1}')"
aab_sha256="$(sha256sum "$artifact_dir/$aab_name" | awk '{print $1}')"

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
apksigner="${sdk_root}/build-tools/37.0.0/apksigner"
certificate_sha256="$($apksigner verify --print-certs "$artifact_dir/$artifact_name" | \
    awk '/certificate SHA-256 digest:/ {print tolower($NF); exit}' | tr -d ':[:space:]')"
[[ "$certificate_sha256" =~ ^[a-f0-9]{64}$ ]]
arm64_certificate_sha256="$($apksigner verify --print-certs "$artifact_dir/$arm64_artifact_name" | \
    awk '/certificate SHA-256 digest:/ {print tolower($NF); exit}' | tr -d ':[:space:]')"
[[ "$arm64_certificate_sha256" == "$certificate_sha256" ]]

# R8 mapping is required to make crash reports from the phone-test build actionable.
mapping_source="app/build/outputs/mapping/ossRelease/mapping.txt"
if test -f "$mapping_source"; then
    cp "$mapping_source" "$artifact_dir/androml-oss-v${version_name}-mapping.txt"
else
    echo "R8 mapping was not produced by the release build" >&2
    exit 1
fi

# Native symbols are emitted by AGP when a bundled native runtime provides them. Keep the
# artifact deterministic even when the current pack has no unstripped symbols.
symbols_source="app/build/outputs/native-debug-symbols/ossRelease/native-debug-symbols.zip"
if test -f "$symbols_source"; then
    cp "$symbols_source" "$artifact_dir/androml-oss-v${version_name}-native-symbols.zip"
else
    printf 'No unstripped native symbols were emitted by this build.\n' \
        > "$artifact_dir/androml-oss-v${version_name}-native-symbols.txt"
fi

source_sha256="$(git ls-files -z | xargs -0 sha256sum | sha256sum | awk '{print $1}')"
build_epoch="$(date -u +%s)"
jq -n \
    --arg version "$version_name" \
    --arg commit "$(git rev-parse HEAD)" \
    --arg source_sha256 "$source_sha256" \
    --arg build_epoch "$build_epoch" \
    --arg apk "$artifact_name" \
    --arg arm64_apk "$arm64_artifact_name" \
    --arg aab "$aab_name" \
    --arg apk_sha256 "$apk_sha256" \
    --arg arm64_apk_sha256 "$arm64_apk_sha256" \
    --arg aab_sha256 "$aab_sha256" \
    '{spdxVersion:"SPDX-2.3",dataLicense:"CC0-1.0",SPDXID:"SPDXRef-DOCUMENT",
      name:("AndroML "+$version+" phone-test build SBOM"),documentNamespace:("https://github.com/breixopd/AndroML/sbom/"+$commit),
      creationInfo:{created:($build_epoch|tonumber|todateiso8601),creators:["Tool: AndroML release pipeline"]},
      packages:[
        {SPDXID:"SPDXRef-Package-AndroML",name:"AndroML",versionInfo:$version,downloadLocation:"https://github.com/breixopd/AndroML",filesAnalyzed:false},
        {SPDXID:"SPDXRef-Package-LiteRT",name:"LiteRT",versionInfo:"1.4.2",downloadLocation:"https://ai.google.dev/edge/litert",filesAnalyzed:false},
        {SPDXID:"SPDXRef-Package-LiteRT-LM",name:"LiteRT-LM",versionInfo:"0.14.0",downloadLocation:"https://ai.google.dev/edge/litert/next/litertlm",filesAnalyzed:false},
        {SPDXID:"SPDXRef-Package-ONNX",name:"ONNX Runtime Mobile",versionInfo:"1.26.0",downloadLocation:"https://onnxruntime.ai",filesAnalyzed:false},
        {SPDXID:"SPDXRef-Package-ExecuTorch",name:"ExecuTorch Android",versionInfo:"0.6.0-rc1",downloadLocation:"https://pytorch.org/executorch",filesAnalyzed:false}
        ,{SPDXID:"SPDXRef-Package-llama.cpp",name:"llama.cpp Android arm64 pack",versionInfo:"b10079",downloadLocation:"https://github.com/ggml-org/llama.cpp/releases/tag/b10079",filesAnalyzed:false}
      ],files:[
        {SPDXID:"SPDXRef-File-APK",fileName:$apk,checksums:[{algorithm:"SHA256",checksumValue:$apk_sha256}]},
        {SPDXID:"SPDXRef-File-APK-Arm64",fileName:$arm64_apk,checksums:[{algorithm:"SHA256",checksumValue:$arm64_apk_sha256}]},
        {SPDXID:"SPDXRef-File-AAB",fileName:$aab,checksums:[{algorithm:"SHA256",checksumValue:$aab_sha256}]}
      ],annotations:[{annotationType:"OTHER",annotator:"Tool: AndroML release pipeline",annotationDate:($build_epoch|tonumber|todateiso8601),comment:("source-manifest-sha256="+$source_sha256)}]}' \
    > "$artifact_dir/androml-oss-v${version_name}-sbom.spdx.json"

jq -n \
    --arg version "$version_name" \
    --arg tag "v${version_name}" \
    --arg commit "$(git rev-parse HEAD)" \
    --arg source_sha256 "$source_sha256" \
    --arg build_epoch "$build_epoch" \
    --arg apk "$artifact_name" \
    --arg arm64_apk "$arm64_artifact_name" \
    --arg aab "$aab_name" \
    --arg apk_sha256 "$apk_sha256" \
    --arg arm64_apk_sha256 "$arm64_apk_sha256" \
    --arg aab_sha256 "$aab_sha256" \
    '{type:"https://in-toto.io/Statement/v1",subject:[{name:$apk,digest:{sha256:$apk_sha256}},{name:$arm64_apk,digest:{sha256:$arm64_apk_sha256}},{name:$aab,digest:{sha256:$aab_sha256}}],predicateType:"https://slsa.dev/provenance/v1",predicate:{buildDefinition:{buildType:"https://github.com/AndroML/phone-test-release",externalParameters:{tag:$tag},resolvedDependencies:[{uri:"git+https://github.com/AndroML",digest:{sha1:$commit}}]},runDetails:{builder:{id:"https://github.com/actions/runner"},metadata:{invocationId:($commit+"-"+$build_epoch)}}},source_sha256:$source_sha256}' \
    > "$artifact_dir/androml-oss-v${version_name}-provenance.intoto.json"

jq -n \
    --arg version "$version_name" \
    --arg tag "v${version_name}" \
    --arg commit "$(git rev-parse HEAD)" \
    --arg package_id "dev.androml.app" \
    --arg apk "$artifact_name" \
    --arg arm64_apk "$arm64_artifact_name" \
    --arg aab "$aab_name" \
    --arg certificate_sha256 "$certificate_sha256" \
    --arg arm64_certificate_sha256 "$arm64_certificate_sha256" \
    --arg apk_sha256 "$apk_sha256" \
    --arg arm64_apk_sha256 "$arm64_apk_sha256" \
    --arg aab_sha256 "$aab_sha256" \
    --arg mapping "androml-oss-v${version_name}-mapping.txt" \
    --arg sbom "androml-oss-v${version_name}-sbom.spdx.json" \
    --arg provenance "androml-oss-v${version_name}-provenance.intoto.json" \
    '{version: $version, tag: $tag, commit: $commit, package_id: $package_id,
      artifacts: {apk: $apk, arm64_apk: $arm64_apk, aab: $aab, mapping: $mapping, sbom: $sbom, provenance: $provenance},
      sha256: {apk: $apk_sha256, arm64_apk: $arm64_apk_sha256, aab: $aab_sha256},
      signing_certificate_sha256: $certificate_sha256, arm64_signing_certificate_sha256: $arm64_certificate_sha256, store_submissions_enabled: false}' \
    > "$artifact_dir/$manifest_name"

test "$(find "$artifact_dir" -maxdepth 1 -type f | wc -l)" -ge 11

printf 'release_artifact_dir=%s\n' "$artifact_dir"
