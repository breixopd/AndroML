#!/usr/bin/env bash
set -euo pipefail

# Reproducible, source-pinned arm64 llama.cpp pack. The large native objects are deliberately
# ignored from git and are fetched by CI/release builds, then included in the APK as data-only
# native libraries. No llama.cpp RPC/server binary is copied.
root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
vendor_dir="$root_dir/runtime/llamacpp/vendor"
version="b10079"
archive_sha256="3be1b7254664bef37727e4ae8fe845526d821946d82a25165ebbb225d8edd8e9"
source_commit="40b740ad05c531b9d57aca6698c3ed553a9e784c"
archive_url="https://github.com/ggml-org/llama.cpp/releases/download/${version}/llama-${version}-bin-android-arm64.tar.gz"
cache_dir="${XDG_CACHE_HOME:-/tmp}/androml-llamacpp/${version}"

mkdir -p "$cache_dir" "$vendor_dir/include" "$vendor_dir/jni/arm64-v8a"
archive="$cache_dir/llama-${version}-android-arm64.tar.gz"
if [[ ! -f "$archive" ]]; then
  curl --fail --location --retry 3 --silent --show-error "$archive_url" -o "$archive"
fi
printf '%s  %s\n' "$archive_sha256" "$archive" | sha256sum --check --status

extract_dir="$cache_dir/extracted"
if [[ ! -d "$extract_dir/llama-${version}" ]]; then
  rm -rf "$extract_dir"
  mkdir -p "$extract_dir"
  tar -xzf "$archive" -C "$extract_dir"
fi
src="$extract_dir/llama-${version}"
for library in \
  libggml-base.so libggml.so libggml-cpu-android_armv8.0_1.so libllama.so; do
  test -f "$src/$library"
  install -m 0644 "$src/$library" "$vendor_dir/jni/arm64-v8a/$library"
done
install -m 0644 "$src/LICENSE" "$vendor_dir/LLAMA_LICENSE"
mkdir -p "$root_dir/runtime/llamacpp/src/main/assets"
install -m 0644 "$src/LICENSE" "$root_dir/runtime/llamacpp/src/main/assets/llama.cpp.LICENSE"

for header in llama.h; do
  curl --fail --location --silent --show-error \
    "https://raw.githubusercontent.com/ggml-org/llama.cpp/${source_commit}/include/${header}" \
    -o "$vendor_dir/include/${header}"
done
for header in ggml.h ggml-alloc.h ggml-backend.h ggml-cpu.h ggml-opt.h gguf.h; do
  curl --fail --location --silent --show-error \
    "https://raw.githubusercontent.com/ggml-org/llama.cpp/${source_commit}/ggml/include/${header}" \
    -o "$vendor_dir/include/${header}"
done

# Keep the pack auditable and fail if an RPC/server object accidentally enters the APK.
if find "$vendor_dir/jni" -type f \( -iname '*rpc*' -o -iname '*server*' \) | grep -q .; then
  echo "refusing to stage llama.cpp RPC/server objects" >&2
  exit 1
fi
printf 'llama_cpp_pack_ready=%s version=%s abi=arm64-v8a commit=%s\n' "$vendor_dir" "$version" "$source_commit"
