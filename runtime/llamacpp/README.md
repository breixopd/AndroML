# llama.cpp runtime pack

AndroML's GGUF adapter is built from the official `ggml-org/llama.cpp` Android arm64 release
`b10079` and a wrapper compiled in this module. The pack is CPU-only and intentionally supports
local text generation only; no server, RPC, or downloaded native code is loaded.

The native objects and headers are ignored from git. Prepare them with:

```sh
./scripts/prepare-llama-cpp.sh
```

Release workflows set `ANDROML_PREPARE_LLAMA_CPP=true` before assembling the OSS APK/AAB. A
checkout without the vendor directory still builds and tests, but reports the engine as not
bundled rather than promising an unusable runtime.
