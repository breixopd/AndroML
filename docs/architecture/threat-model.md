# AndroML v1 threat model

## Security boundary

AndroML treats downloaded models, imported documents, retrieved text, tool output, and
paired phones as untrusted inputs. The Android UI and persistence layer are the control plane;
the isolated `:inference` process is the execution boundary; the local API and cluster listener
are authenticated ingress points. App-private storage, Android Keystore keys, and the release
signing key are separate trust domains.

## Threats and controls

| Threat | Required control in v1 |
| --- | --- |
| Malicious model or native parser input | Content-addressed storage, immutable SHA-256 verification, bounded parsers, read-only model descriptors, isolated inference process, no downloaded native libraries |
| Prompt injection from documents or tools | Typed workflow/agent channels, citation-bearing RAG results, explicit tool descriptors/scopes, model output treated as untrusted |
| SSRF or unsafe external tools | HTTPS-only and host/address policy primitives; no arbitrary local-process/MCP stdio execution on Android |
| Tool abuse | JSON-schema validation, side-effect classes, scopes, bounded time/results, approval tokens, hashed audit events, and an emergency stop surface |
| LAN attacker | LAN API requires TLS plus a pinned client certificate and scoped bearer key; cluster peers require pairing, certificate pinning, expiry, and revocation |
| Stolen API key | Keystore-backed storage, Argon2id verification, expiry/revocation, per-scope authorization, and no plaintext persistence |
| Replay/downgrade | Immutable HF commit references, content hashes, request deadlines, idempotency keys, attempt numbers, and versioned wire contracts |
| Paired-device compromise | Explicit owner-approved model transfer, encrypted mTLS transport, resumable chunks, final hash verification, and peer revoke/remove controls |
| Supply-chain tampering | Pinned GitHub Actions, pinned native toolchain, release checksums, SBOM, in-toto provenance, signing-certificate verification, and CodeQL/security CI |
| Logs/backups leaking content | App backup is disabled; audit records retain hashes and safe metadata rather than tool arguments/results; pending approval continuations are chunked and Keystore-encrypted, and secrets stay in Keystore-backed storage |
| Release-signing compromise | Test-period store publication is fail-closed; signing inputs are supplied only through protected CI secrets; no keystore is committed |

## Deliberate v1 limits

Pending tool and agent approvals survive process death through a Room metadata index and
Keystore-encrypted continuation chunks; expiry and argument-hash checks are revalidated before
execution. MLC is represented as unavailable until a model-specific signed pack is shipped. The app does
not pretend that an unavailable engine or unsupported workload succeeded. Bundled tensor engines
accept only bounded caller-supplied buffers after strict shape/type/size validation; preprocessing
and labels are explicit rather than inferred from an untrusted model. Cluster execution is
whole-request replication and bounded workflow/RAG placement; arbitrary tensor sharding and WAN
listeners are not enabled.

## Verification

Security-relevant behavior is covered by unit/contract tests, Android lint, CodeQL, secret
scanning, exact-commit release manifests, and the signed APK verifier. Physical-device runtime
and mTLS smoke testing remains part of the owner-controlled phone-test period.
