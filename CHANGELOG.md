# Changelog

All notable changes to AndroML are documented here.

## [1.0.4](https://github.com/breixopd/AndroML/compare/v1.0.3...v1.0.4) (2026-08-02)


### Bug Fixes

* **ux:** make model installation one tap ([eb2758b](https://github.com/breixopd/AndroML/commit/eb2758b7b8d00dd2886a45d4d806368db5efa015))

## [1.0.3](https://github.com/breixopd/AndroML/compare/v1.0.2...v1.0.3) (2026-08-02)


### Bug Fixes

* **ux:** streamline end-to-end app flows ([82edaf0](https://github.com/breixopd/AndroML/commit/82edaf0bf5a5d7123a3f1c1d6219471941c3e390))

## [1.0.2](https://github.com/breixopd/AndroML/compare/v1.0.1...v1.0.2) (2026-08-02)


### Bug Fixes

* **discover:** repair model downloads and recommendations ([#5](https://github.com/breixopd/AndroML/issues/5)) ([e5a5be8](https://github.com/breixopd/AndroML/commit/e5a5be888a537524aa8f2ff63db0f443ba1c096f))

## [1.0.1](https://github.com/breixopd/AndroML/compare/v1.0.0...v1.0.1) (2026-07-26)


### Bug Fixes

* **ui:** add system theme and guided setup ([a5341d1](https://github.com/breixopd/AndroML/commit/a5341d1ac2d8ddb7de42c494bff824167e49acfb))

## [Unreleased]

## [1.0.0] - 2026-07-26

- Ship the complete power-user Compose interface for model discovery, library management, optimization, local and distributed inference, RAG, workflows, agents, tools, APIs, settings, and cluster control.
- Add pinned-commit Hugging Face search and resumable, size-bounded, SHA-256 verified model downloads.
- Bundle LiteRT, LiteRT-LM, ONNX Runtime Mobile, ExecuTorch, and a pinned arm64 llama.cpp runtime with device-aware compatibility checks and automatic runtime selection.
- Add scoped bearer authentication, bounded Argon2 verification, request throttling, loopback/LAN policies, mTLS, OpenAI-compatible endpoints, OpenAPI, and durable privacy-safe audit history.
- Add document RAG with bounded PDF, EPUB, DOCX, XLSX, PPTX, HTML, JSON, CSV, and text ingestion plus citation-preserving retrieval.
- Add durable workflow checkpoints, explicit crash-retry semantics, encrypted one-shot approval continuations, allowlisted tools, and bounded local agents.
- Add certificate-pinned peer pairing, authenticated discovery, capability routing, distributed inference/RAG/workflow stages, bounded model transfer, replay isolation, quotas, and deadline-aware admission.
- Harden native and parser boundaries with tensor/output limits, JSON and archive depth/size guards, isolated-runtime watchdogs, session/job admission, storage quotas, stale-state cleanup, and overflow-safe memory accounting.
- Add OSS-only GitHub phone-test releases with signed universal and arm64 APKs, AAB, checksums, SBOM, provenance, R8 mapping, exact-source manifests, and store-publication hard gates.

## [0.1.0] - 2026-07-18

- Initial runnable foundation build.
