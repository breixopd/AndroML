# AndroML v1 data flow

```text
User/UI
  │ model selection, approvals, settings
  ▼
App control plane ──► Room metadata + app-private artifact store
  │                         │
  │                         └─ immutable verified model/document bytes
  ▼
Isolated :inference process ──► bundled runtime JNI/native code
  │                                (no network permission)
  ├─ local API (loopback or mTLS LAN) ──► authenticated client
  ├─ cluster mTLS listener ─────────────► explicitly paired phone
  └─ Hugging Face HTTPS client ─────────► pinned commit metadata/files

SAF document URI ──► bounded parser ──► normalized chunks ──► Room FTS/vector metadata
                                                     │
                                                     └─ citations returned to UI/API/agent
```

## Trust-boundary rules

1. Hugging Face metadata is inspected before download. A selected file must have an immutable
   commit revision, bounded size, and expected SHA-256. Credentials are read from Keystore-backed
   storage and are never placed in URLs.
2. SAF providers are read through bounded streams. Parsers remove active markup and reject unsafe
   archive paths, entry counts, and expansion sizes before text reaches RAG.
3. Only verified artifact hashes enter runtime or cluster requests. Runtime adapters receive a
   read-only file descriptor and bounded typed request values.
4. API authorization is evaluated before content-bearing or mutating work. LAN requests must
   pass both certificate trust and the configured API security policy.
5. Cluster discovery is only a hint. A discovered endpoint cannot execute work until the user
   imports/pins its certificate and pairs the peer. Model transfer additionally requires an owner
   approval and license/private metadata.
6. Workflow and agent state is serialized as bounded typed values with durable event/checkpoint
   records. Tool arguments and results remain separate from user/system/model channels.

## Release flow

Protected GitHub source → pinned CI/security checks → exact-tag release build → signed OSS APK/AAB,
mapping, checksums, SBOM, provenance, and manifest → GitHub Release only. Store publication is
explicitly disabled until the owner approves a later policy change.
