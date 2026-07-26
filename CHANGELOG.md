# Changelog

All notable changes to AndroML are documented here.

## [1.1.0](https://github.com/breixopd/AndroML/compare/v1.0.0...v1.1.0) (2026-07-26)


### Features

* add API key pairing and mTLS policy contracts ([83b309a](https://github.com/breixopd/AndroML/commit/83b309a4fab783fd40c9d0ae5abab3675c5ffa45))
* add authenticated lan discovery hints ([60d1a04](https://github.com/breixopd/AndroML/commit/60d1a04ed9e915999a6267314339ed0dc1251aee))
* add authenticated loopback API server ([516e6ee](https://github.com/breixopd/AndroML/commit/516e6ee794a060699eda9041d6ef70d151c26058))
* add bounded tool-calling agents ([8dae171](https://github.com/breixopd/AndroML/commit/8dae1717f73c5aea87fade5d7aa5f3e7c5ae2a17))
* add deterministic hybrid rag core ([89fe7b5](https://github.com/breixopd/AndroML/commit/89fe7b58034f3b3678a91f02ebfda2fbf28c0dde))
* add device capability profiling ([2d3a212](https://github.com/breixopd/AndroML/commit/2d3a212fc22983fca71c862f2aa70d9d94fae97c))
* add distributed placement contracts ([e79e6a4](https://github.com/breixopd/AndroML/commit/e79e6a4a0f74e4c2b12d570bd679aea23645cd30))
* add executorch tensor runtime pack ([2029e3b](https://github.com/breixopd/AndroML/commit/2029e3b72f75cd7c8f04bdc2601153266d1c2faa))
* add Hugging Face metadata boundary ([fe72ec5](https://github.com/breixopd/AndroML/commit/fe72ec5fcf2604320467a37c878ba4b65520f366))
* add Hugging Face metadata client ([c4cd004](https://github.com/breixopd/AndroML/commit/c4cd0044937ac00a2dfc58001fa273472a2e1f26))
* add isolated litertlm runtime pack ([e02ae20](https://github.com/breixopd/AndroML/commit/e02ae208629cbadce4c28aa67a5ea0ca6de140df))
* add isolated runtime playground client ([72aac24](https://github.com/breixopd/AndroML/commit/72aac240d2f795ab865732add0595f404388b668))
* add mTLS cluster execution transport ([eefe29b](https://github.com/breixopd/AndroML/commit/eefe29b32507026f9ad82959994fd12102f6f50e))
* add one-time cluster pairing invites ([a52d768](https://github.com/breixopd/AndroML/commit/a52d7685b5e0c2352001e78b534449ba938ca889))
* add onnx embeddings and persistent hybrid rag ([8133799](https://github.com/breixopd/AndroML/commit/8133799b4f968c3c5d6aad0c3d0c711018ddd5b1))
* add permissioned tool execution contracts ([0210cd9](https://github.com/breixopd/AndroML/commit/0210cd9b34a21c8fd4f1ba035c487d78e333ce8e))
* add pinned Hugging Face discover form ([d230cb2](https://github.com/breixopd/AndroML/commit/d230cb21d29cff8e9548a3494f20584c3bfd29e2))
* add pinned mutual tls material ([f7381ca](https://github.com/breixopd/AndroML/commit/f7381caf6cbc008b9c20ed50aca1bc98837eb266))
* add resumable artifact storage ([6aecb84](https://github.com/breixopd/AndroML/commit/6aecb84b3a45396d572efc22f107b5fad20ce69c))
* add resumable Hugging Face downloads ([4063ad8](https://github.com/breixopd/AndroML/commit/4063ad81c1c04e920f40ef8de632962494ce91cb))
* add runtime optimizer contracts ([d0ede73](https://github.com/breixopd/AndroML/commit/d0ede7332988413d40947c5ecdb37fdda26fbe44))
* add standalone litert embedding runtime ([5650428](https://github.com/breixopd/AndroML/commit/5650428041fd7ef3c9d084c03ca3c9f5f57ed826))
* add validated durable workflow core ([38336cc](https://github.com/breixopd/AndroML/commit/38336cc2cdd3cff41eecee3ff062b4342488ea37))
* add verified artifact storage boundary ([f9e1e8e](https://github.com/breixopd/AndroML/commit/f9e1e8e0dd9e7050fb4c33efaad6dfb4392bd7fb))
* **agents:** enable structured local tool calls ([6d31275](https://github.com/breixopd/AndroML/commit/6d312757c554b9c992cd61780af948bda4a39dca))
* **api:** expose agent invocation ([4b1fc91](https://github.com/breixopd/AndroML/commit/4b1fc91008b69b89e3f24329fbc1db92d24cce2b))
* **api:** expose authenticated native v1 routes ([1fc3540](https://github.com/breixopd/AndroML/commit/1fc35403458872d9bb91825b5ee5132a13d69a9b))
* **api:** expose hash-only audit events ([5985cc6](https://github.com/breixopd/AndroML/commit/5985cc61c239bfec043145e82cb355424ec74cfa))
* **api:** show durable audit history ([c69679d](https://github.com/breixopd/AndroML/commit/c69679d007a8c2d7950811fe701eb52ebda97cab))
* **approvals:** persist encrypted continuations ([ac42d6e](https://github.com/breixopd/AndroML/commit/ac42d6e504a475297ae1449f3cc0c9147b5a3b6c))
* **approvals:** resume tool and agent executions ([ef56fcf](https://github.com/breixopd/AndroML/commit/ef56fcf3bc693fb8cbd23012303ad726b2a9fea8))
* **audit:** persist hash-only tool events ([c2da0b1](https://github.com/breixopd/AndroML/commit/c2da0b10eab7324db6eeb22012bfa8d038c0daaf))
* bridge cluster inference to runtime ([dee5677](https://github.com/breixopd/AndroML/commit/dee5677ee885d9eb6f263d4c0fcfc4d271f835d8))
* **cluster:** add approved resumable model transfer ([3ba52c9](https://github.com/breixopd/AndroML/commit/3ba52c9229808bb99cc8f73554ea9d12ac7c7423))
* **cluster:** expose distributed playground mode ([f240221](https://github.com/breixopd/AndroML/commit/f2402218b15cff09e8467f492b019ac9e7194f6b))
* complete AndroML v1 phone-test build ([2aa133f](https://github.com/breixopd/AndroML/commit/2aa133f4a37703e0427d1d24cd5fbbcc24dc9ee8))
* complete v1 runtime and api product flows ([9867fc3](https://github.com/breixopd/AndroML/commit/9867fc3ee4eba55f3f2d60f27c2a55058ff2e6f5))
* connect Hugging Face discovery to verified downloads ([db63a27](https://github.com/breixopd/AndroML/commit/db63a27659bf65d35be62f59e7c973626ff24ab0))
* enforce tool execution policy ([8b75f19](https://github.com/breixopd/AndroML/commit/8b75f19ea07e54050fe250f4e1b9d6521f30e32a))
* establish private phone-test foundation ([0f596e9](https://github.com/breixopd/AndroML/commit/0f596e95423c358466ad29824dd1385a430de1b6))
* execute durable workflows ([dae3a97](https://github.com/breixopd/AndroML/commit/dae3a97d777af22768050b13f1798a31d666cffc))
* execute durable workflows on cluster ([e66de6f](https://github.com/breixopd/AndroML/commit/e66de6ffd9f62e540ebf672d266629aeacb77dc6))
* expose api mTLS identity controls ([fac2c06](https://github.com/breixopd/AndroML/commit/fac2c064bce525e90ef9fe2ced4afd7a526c075b))
* expose local rag and api controls ([c1a628d](https://github.com/breixopd/AndroML/commit/c1a628de0d37261827cbfa7227301ab1cfcc8f2d))
* expose scoped api and lan client auth ([07d02aa](https://github.com/breixopd/AndroML/commit/07d02aa54b11319f2595877fb06b468e9f25710c))
* fan out distributed rag search ([2cd6c0d](https://github.com/breixopd/AndroML/commit/2cd6c0dbaa008a57bcc91c61d55aca91021b4e84))
* isolate inference behind a bounded runtime service ([b04d0f8](https://github.com/breixopd/AndroML/commit/b04d0f8f558772b656e643b15c9184a994c07afa))
* make Hugging Face downloads durable and protect tokens ([2960f29](https://github.com/breixopd/AndroML/commit/2960f29eaa99f2b375ac77ba8349b2b27dd62ebe))
* persist cluster job idempotency ledger ([bf68023](https://github.com/breixopd/AndroML/commit/bf680239426253a36ff4a8b13d83f07ed1d0342d))
* persist model catalog and library state ([d7145ae](https://github.com/breixopd/AndroML/commit/d7145ae0a46f8060d7c766f7068181c71898eb6b))
* persist paired cluster peers ([61eadae](https://github.com/breixopd/AndroML/commit/61eadaebc74a80bf5c82cb92729c4120fdbf75e5))
* persist rag collections and lexical index ([43b29ce](https://github.com/breixopd/AndroML/commit/43b29ceb04729acce9abd7905d8564b309eb177a))
* persist runtime benchmark observations ([98854e3](https://github.com/breixopd/AndroML/commit/98854e358f299fb8c8075f5c42a0b4275b7cce60))
* persist scoped API keys ([559e23c](https://github.com/breixopd/AndroML/commit/559e23c483a59814a0ee51312f5dd19f6225011e))
* persist tls identities securely ([4fc2deb](https://github.com/breixopd/AndroML/commit/4fc2deb907847beba705e937a593dff16498d55c))
* persist workflow event checkpoints ([70eef6f](https://github.com/breixopd/AndroML/commit/70eef6fbc39d641e144aa6102c3f82fe490452df))
* pin Hugging Face model references ([8d26dca](https://github.com/breixopd/AndroML/commit/8d26dca67c7543c102fcb3b8567c4abcc3394cb8))
* **rag:** route indexing through verified embedding providers ([3670f56](https://github.com/breixopd/AndroML/commit/3670f56af68293be36eed6ee0004e47fb5ba2b26))
* refresh cluster capabilities with workmanager ([481c39c](https://github.com/breixopd/AndroML/commit/481c39c7fba727a411b10930d25ee14202a83eee))
* release hardened AndroML v1 ([2c3f76d](https://github.com/breixopd/AndroML/commit/2c3f76d25d0bb22e45604401bca04d59ba63afe7))
* route api embeddings through selected runtime ([aa4044e](https://github.com/breixopd/AndroML/commit/aa4044ee02d51d75c2c6b9169a480225494a65c1))
* route inference across paired nodes ([2ae5db7](https://github.com/breixopd/AndroML/commit/2ae5db778e35ab13c0f74a5f87b999251e2aed07))
* route playground through auto optimizer ([18c322d](https://github.com/breixopd/AndroML/commit/18c322d92b42b1d6d8ffd172d7eec0d41d0ef136))
* **runtime:** add bounded tensor inference ([8195fda](https://github.com/breixopd/AndroML/commit/8195fda581353ba59499907779f7edc255341fbf))
* **runtime:** bundle pinned llama.cpp arm64 pack ([752b9a2](https://github.com/breixopd/AndroML/commit/752b9a2bdc0050f60a575ac96555de5bc83baac1))
* select chat runtime from model format ([ba7f3c7](https://github.com/breixopd/AndroML/commit/ba7f3c74b6657681e57a03a5ef9a599270d93a40))
* serve LAN API over mutual TLS ([6cd25d8](https://github.com/breixopd/AndroML/commit/6cd25d82f4a1bca76d949793012e58eebbb1be69))
* **tools:** add bounded built-in model utilities ([f8089cb](https://github.com/breixopd/AndroML/commit/f8089cb892a87f37116d393afac7f2288614b6ae))
* **workflows:** add starter agent workflow ([5732fb9](https://github.com/breixopd/AndroML/commit/5732fb9aaa22a48b17286ae355c4f5e7736f83e7))


### Bug Fixes

* accept legacy api key hashes during migration ([0a7f81c](https://github.com/breixopd/AndroML/commit/0a7f81c0dbcdd7974eeb1d763c4fa7bcda7d8e0a))
* **api:** fail closed without embedding runtime ([5757747](https://github.com/breixopd/AndroML/commit/57577477dea9c2f0af8a64679b0c7ae21783c5f4))
* enforce api request body limits ([07261ce](https://github.com/breixopd/AndroML/commit/07261ce45a691c6eeaa8d9681bfea0bc79f9c179))
* expose local cluster identity in api status ([4a64d8b](https://github.com/breixopd/AndroML/commit/4a64d8bf80fdc0e84e3b29b3efbb65c2c217da72))
* persist rsa mutual tls identities ([5970c00](https://github.com/breixopd/AndroML/commit/5970c00c4773fc4484cd817919ca3dc985231dfa))
* **rag:** stabilize provider fallback vectors ([9c90361](https://github.com/breixopd/AndroML/commit/9c90361887aa12ab7901770f321d5b2701b9b367))
* record benchmark latency and preview runtime count ([866fdcd](https://github.com/breixopd/AndroML/commit/866fdcd19d5bb69b4f60fe19aba75b41edd629b8))
* recover expired cluster execution leases ([327ea9f](https://github.com/breixopd/AndroML/commit/327ea9f1595c3302c0c298e2d6ad89d1b7e832b2))
* **release:** harden native pack and signing ([8c1092f](https://github.com/breixopd/AndroML/commit/8c1092fd2fe8fa7f969d4c05f026e23bcc89dcd0))
* require generation models for agent workflows ([4bf0aad](https://github.com/breixopd/AndroML/commit/4bf0aad1500b7ac5451bbdb6a51acda50eecdb35))
* **runtime:** align optimization with artifact formats ([5061184](https://github.com/breixopd/AndroML/commit/5061184444bd778ef8c948323d0f4709e0e9c2a0))
* **settings:** apply runtime safety controls ([88f83dc](https://github.com/breixopd/AndroML/commit/88f83dc0757f13d5b888d1f5a7457b12eadd903e))
* **ui:** remove stale capability messaging ([6b08c1c](https://github.com/breixopd/AndroML/commit/6b08c1c0dc01a3c8a350b4590876a4ee2bcfb688))
* validate runtime embedding batches ([fbd71c1](https://github.com/breixopd/AndroML/commit/fbd71c1febbcb67ad065e7c43f1e501a8db210c6))

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
