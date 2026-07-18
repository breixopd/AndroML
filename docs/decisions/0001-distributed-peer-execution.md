# ADR-0001: Use mTLS-authenticated whole-request peer execution for v1

## Status

Accepted

## Date

2026-07-18

## Context

Distributed inference is a mandatory v1 capability. Phones on a local network
must be able to share complete inference replicas, workflow stages, and RAG
search work without introducing an unauthenticated LAN service or requiring a
central coordinator.

The first implementation needs to be testable on two ordinary Android devices,
bounded against malformed or oversized messages, resilient to retries, and
compatible with the existing per-device model store. Tensor/layer sharding and
remote model streaming are substantially more complex and would increase the
failure and privacy surface before the basic cluster is proven.

## Decision

AndroML v1 uses a local peer execution protocol with these properties:

- Each peer has a self-signed P-256 node certificate held by the encrypted
  identity store.
- Transport is HTTPS with mutual TLS. The server trusts only the configured
  client certificate set, and the application layer additionally maps the
  certificate fingerprint to a paired peer ID.
- Requests use versioned JSON with bounded base64 payloads. A SHA-256 payload
  hash is part of the request and is checked before execution.
- A request names the source peer, job ID, attempt, workload, model hash,
  deadline, and idempotency key. The server rejects a source peer ID that does
  not match the client certificate.
- A durable implementation will replace the current in-memory attempt ledger;
  the protocol already exposes `Completed`, `AlreadyRunning`,
  `AlreadyCompleted`, `AlreadyFailed`, `Failed`, and `Rejected` outcomes so
  retries can be handled without silently duplicating work.
- v1 distributes complete requests to replicas or workflow stages. It does not
  claim tensor/layer sharding, remote model transfer, or automatic WAN
  federation.

## Alternatives considered

### Unauthenticated HTTP on the LAN

Rejected. Device discovery and local-network reachability are not an
authorization boundary. A nearby process could submit inference jobs or use a
node as a relay.

### API-key-only peer authentication

Rejected for peer transport. API keys remain useful for user-facing API
clients, but certificates provide device identity, channel encryption, and
revocation/pairing semantics without putting a bearer secret into every peer
request.

### Tensor or layer sharding in the first transport

Deferred. It can improve the ability to run models that do not fit on one
device, but it requires a different execution protocol, partitioned model
artifacts, stream scheduling, failure recovery, and much more extensive
benchmarking. Whole-request distribution establishes the trust, routing, and
retry foundations first.

### Central coordinator service

Rejected for the phone-first test period. The first cluster should work on a
private LAN without a hosted dependency or a new operator-managed service.

## Consequences

Positive:

- Pairing and certificate rotation can be enforced before any model work is
  sent to a peer.
- Hash-addressed payloads and bounded bodies make protocol handling auditable.
- Idempotent attempt states give retries a defined meaning.
- The same transport can carry inference replicas, workflow stages, and
  distributed RAG shard requests.

Trade-offs:

- Each peer must have the target model locally for model-backed workloads.
- Self-signed identity certificates require explicit pairing and certificate
  exchange; they are not suitable for untrusted WAN federation by themselves.
- Whole-request distribution does not solve models larger than one device's
  available memory.
- The current in-memory ledger is only a foundation; process-death recovery
  requires Room-backed job records before production cluster use.
