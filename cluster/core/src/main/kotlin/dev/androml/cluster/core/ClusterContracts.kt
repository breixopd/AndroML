package dev.androml.cluster.core

import dev.androml.core.api.CertificateFingerprint
import dev.androml.core.rag.RetrievalResult
import java.security.MessageDigest
import java.time.Instant

@JvmInline
value class PeerId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): PeerId {
            require(raw.matches(ID_PATTERN)) { "peer ID contains unsafe characters" }
            return PeerId(raw)
        }

        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    }
}

@JvmInline
value class ClusterJobId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): ClusterJobId {
            require(raw.matches(ID_PATTERN)) { "cluster job ID contains unsafe characters" }
            return ClusterJobId(raw)
        }

        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,127}")
    }
}

@JvmInline
value class ContentHash private constructor(val value: String) {
    companion object {
        fun parse(raw: String): ContentHash {
            require(raw.matches(Regex("[a-f0-9]{64}"))) { "content hash must be SHA-256" }
            return ContentHash(raw)
        }
    }
}

enum class ClusterWorkload {
    InferenceReplica,
    WorkflowStage,
    RagSearch,
    ModelTransfer,
}

data class PeerEndpoint(
    val host: String,
    val port: Int,
) {
    init {
        require(host.isNotBlank() && host.length <= 253) { "peer host is invalid" }
        require(host.none { it.isWhitespace() || it in "/?#" }) { "peer host contains unsafe characters" }
        require(port in 1024..65_535) { "peer port is out of bounds" }
    }
}

data class NodeCapabilities(
    val protocolMajor: Int = 1,
    val protocolMinor: Int = 0,
    val supportedWorkloads: Set<ClusterWorkload>,
    val modelHashes: Set<ContentHash> = emptySet(),
    val maxConcurrentJobs: Int,
    val availableRamBytes: Long,
    val queueDepth: Int,
    val thermalSeverity: Int = 0,
    val batteryPercent: Int = 100,
    val charging: Boolean = true,
    val lastSeenEpochMillis: Long,
) {
    init {
        require(protocolMajor == 1) { "unsupported cluster protocol major" }
        require(protocolMinor in 0..99) { "cluster protocol minor is invalid" }
        require(supportedWorkloads.isNotEmpty()) { "node must advertise at least one workload" }
        require(modelHashes.size <= 10_000) { "node advertises too many models" }
        require(maxConcurrentJobs in 1..64) { "max concurrent jobs is out of bounds" }
        require(availableRamBytes >= 0L) { "available RAM must not be negative" }
        require(queueDepth in 0..100_000) { "queue depth is out of bounds" }
        require(thermalSeverity in 0..4) { "thermal severity is out of bounds" }
        require(batteryPercent in 0..100) { "battery percentage is out of bounds" }
        require(lastSeenEpochMillis >= 0L) { "last-seen time must not be negative" }
    }
}

data class ClusterPeer(
    val id: PeerId,
    val fingerprint: CertificateFingerprint,
    val displayName: String,
    val endpoint: PeerEndpoint,
    val pairedAtEpochMillis: Long,
    val certificateExpiresAtEpochMillis: Long,
    val paired: Boolean,
    val revoked: Boolean = false,
    val capabilities: NodeCapabilities,
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= 128) { "peer display name is invalid" }
        require(pairedAtEpochMillis >= 0L) { "pairing time must not be negative" }
        require(certificateExpiresAtEpochMillis > pairedAtEpochMillis) {
            "certificate expiry must be after pairing"
        }
    }
}

/** Public certificate material persisted alongside a paired peer's metadata. */
data class StoredClusterPeer(
    val peer: ClusterPeer,
    val certificateDer: ByteArray,
) {
    init {
        require(certificateDer.size in 1..MAX_CERTIFICATE_BYTES) {
            "peer certificate bytes are out of bounds"
        }
        require(CertificateFingerprint.parse(sha256(certificateDer)) == peer.fingerprint) {
            "peer certificate does not match its fingerprint"
        }
    }

    private companion object {
        const val MAX_CERTIFICATE_BYTES = 16 * 1024
    }
}

data class ClusterNode(
    val peer: ClusterPeer,
    val isLocal: Boolean,
)

data class ClusterRequest(
    val jobId: ClusterJobId,
    val attempt: Int,
    val workload: ClusterWorkload,
    val modelKey: String?,
    val modelHash: ContentHash?,
    val requiredRamBytes: Long,
    val deadlineEpochMillis: Long,
    val payloadHash: ContentHash,
    val idempotencyKey: String,
) {
    init {
        require(attempt in 1..1_000) { "cluster attempt is out of bounds" }
        require(modelKey == null || modelKey.isNotBlank() && modelKey.length <= 512) {
            "model key is invalid"
        }
        require(requiredRamBytes >= 0L) { "required RAM must not be negative" }
        require(deadlineEpochMillis > 0L) { "deadline must be positive" }
        require(idempotencyKey.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}"))) {
            "idempotency key contains unsafe characters"
        }
    }
}

data class RouteDecision(
    val target: PeerId,
    val score: Double,
    val explanation: List<String>,
)

class NoEligibleClusterNode(message: String) : IllegalStateException(message)

class ClusterRouter(
    private val nowEpochMillis: () -> Long = { Instant.now().toEpochMilli() },
    private val staleAfterMillis: Long = 30_000L,
) {
    init {
        require(staleAfterMillis in 1_000L..10 * 60 * 1_000L) { "stale threshold is out of bounds" }
    }

    fun route(request: ClusterRequest, nodes: List<ClusterNode>): RouteDecision {
        require(nodes.size <= 1_000) { "cluster has too many nodes" }
        val now = nowEpochMillis()
        if (request.deadlineEpochMillis <= now) {
            throw NoEligibleClusterNode("cluster request deadline has expired")
        }
        val eligible = nodes.filter { node ->
            val peer = node.peer
            peer.paired &&
                !peer.revoked &&
                peer.certificateExpiresAtEpochMillis > now &&
                peer.capabilities.lastSeenEpochMillis >= now - staleAfterMillis &&
                request.workload in peer.capabilities.supportedWorkloads &&
                peer.capabilities.availableRamBytes >= request.requiredRamBytes &&
                peer.capabilities.queueDepth < peer.capabilities.maxConcurrentJobs &&
                (request.modelHash == null || request.modelHash in peer.capabilities.modelHashes)
        }
        if (eligible.isEmpty()) {
            throw NoEligibleClusterNode("no paired, fresh node satisfies the workload requirements")
        }
        return eligible
            .map { node -> node to score(node, request) }
            .sortedWith(
                compareByDescending<Pair<ClusterNode, Double>> { it.second }
                    .thenBy { it.first.peer.id.value },
            )
            .first()
            .let { (node, score) ->
                RouteDecision(
                    target = node.peer.id,
                    score = score,
                    explanation = listOf(
                        "paired certificate ${node.peer.fingerprint.value.take(12)}…",
                        "${node.peer.capabilities.queueDepth} queued job(s)",
                        "${node.peer.capabilities.availableRamBytes} bytes available RAM",
                        if (node.peer.capabilities.charging) "charging" else "battery ${node.peer.capabilities.batteryPercent}%",
                        if (node.isLocal) "local placement" else "remote placement",
                    ),
                )
            }
    }

    private fun score(node: ClusterNode, request: ClusterRequest): Double {
        val capabilities = node.peer.capabilities
        val ramHeadroom = (capabilities.availableRamBytes - request.requiredRamBytes)
            .coerceAtLeast(0L)
            .toDouble()
        val queuePenalty = capabilities.queueDepth.toDouble() * 10_000.0
        val thermalPenalty = capabilities.thermalSeverity.toDouble() * 2_000.0
        val batteryBonus = if (capabilities.charging) 500.0 else capabilities.batteryPercent.toDouble()
        val localityBonus = if (node.isLocal) 100.0 else 0.0
        return ramHeadroom - queuePenalty - thermalPenalty + batteryBonus + localityBonus
    }
}

data class JobAttemptKey(
    val jobId: ClusterJobId,
    val attempt: Int,
) {
    init {
        require(attempt in 1..1_000) { "job attempt is out of bounds" }
    }
}

enum class JobState {
    Running,
    Completed,
    Failed,
}

enum class BeginAttempt {
    Started,
    AlreadyRunning,
    Completed,
    Failed,
}

/** Durable/idempotent storage contract for one cluster job attempt. */
interface ClusterJobLedger {
    fun begin(key: JobAttemptKey): BeginAttempt

    /** Starts or recovers an attempt whose worker lease has expired. */
    fun begin(key: JobAttemptKey, nowEpochMillis: Long, leaseMillis: Long): BeginAttempt = begin(key)

    fun complete(key: JobAttemptKey, outputHash: ContentHash, output: ByteArray? = null)

    fun fail(key: JobAttemptKey)

    fun state(key: JobAttemptKey): JobState?

    fun outputHash(key: JobAttemptKey): ContentHash?

    fun output(key: JobAttemptKey): ByteArray?
}

/** In-process ledger used by transport tests and as a safe fallback. */
class InMemoryClusterJobLedger : ClusterJobLedger {
    private data class Record(
        var state: JobState,
        var outputHash: ContentHash?,
        var output: ByteArray?,
        var leaseExpiresAtEpochMillis: Long,
    )

    private val records = mutableMapOf<JobAttemptKey, Record>()

    @Synchronized
    override fun begin(key: JobAttemptKey): BeginAttempt = when (records[key]?.state) {
        null -> {
            records[key] = Record(JobState.Running, outputHash = null, output = null, leaseExpiresAtEpochMillis = Long.MAX_VALUE)
            BeginAttempt.Started
        }

        JobState.Running -> BeginAttempt.AlreadyRunning
        JobState.Completed -> BeginAttempt.Completed
        JobState.Failed -> BeginAttempt.Failed
    }

    @Synchronized
    override fun begin(key: JobAttemptKey, nowEpochMillis: Long, leaseMillis: Long): BeginAttempt {
        require(leaseMillis in 1_000L..24 * 60 * 60 * 1_000L) { "cluster lease is out of bounds" }
        val existing = records[key]
        if (existing?.state == JobState.Running && existing.leaseExpiresAtEpochMillis <= nowEpochMillis) {
            existing.leaseExpiresAtEpochMillis = nowEpochMillis + leaseMillis
            return BeginAttempt.Started
        }
        if (existing == null) {
            records[key] = Record(JobState.Running, outputHash = null, output = null, leaseExpiresAtEpochMillis = nowEpochMillis + leaseMillis)
            return BeginAttempt.Started
        }
        return when (existing.state) {
            JobState.Running -> BeginAttempt.AlreadyRunning
            JobState.Completed -> BeginAttempt.Completed
            JobState.Failed -> BeginAttempt.Failed
        }
    }

    @Synchronized
    override fun complete(key: JobAttemptKey, outputHash: ContentHash, output: ByteArray?) {
        val record = records[key] ?: error("job attempt was not started")
        check(record.state == JobState.Running) { "job attempt is not running" }
        record.state = JobState.Completed
        record.outputHash = outputHash
        record.output = output?.copyOf()
    }

    @Synchronized
    override fun fail(key: JobAttemptKey) {
        val record = records[key] ?: error("job attempt was not started")
        check(record.state == JobState.Running) { "job attempt is not running" }
        record.state = JobState.Failed
    }

    @Synchronized
    override fun state(key: JobAttemptKey): JobState? = records[key]?.state

    @Synchronized
    override fun outputHash(key: JobAttemptKey): ContentHash? = records[key]?.outputHash

    @Synchronized
    override fun output(key: JobAttemptKey): ByteArray? = records[key]?.output?.copyOf()
}

data class NodeRetrievalResult(
    val nodeId: PeerId,
    val results: List<RetrievalResult>,
)

data class DistributedRetrievalResult(
    val nodeId: PeerId,
    val result: RetrievalResult,
)

class DistributedRagMerger {
    fun merge(topK: Int, shards: List<NodeRetrievalResult>): List<DistributedRetrievalResult> {
        require(topK in 1..100) { "distributed RAG topK is out of bounds" }
        require(shards.size <= 1_000) { "too many RAG shards" }
        return shards
            .flatMap { shard -> shard.results.map { result -> DistributedRetrievalResult(shard.nodeId, result) } }
            .distinctBy { result -> "${result.nodeId.value}:${result.result.chunk.id.value}" }
            .sortedWith(
                compareByDescending<DistributedRetrievalResult> { it.result.citation.fusedScore }
                    .thenBy { it.nodeId.value }
                    .thenBy { it.result.chunk.documentId.value }
                    .thenBy { it.result.chunk.ordinal },
            )
            .take(topK)
    }
}

private fun sha256(value: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { byte -> "%02x".format(byte) }
