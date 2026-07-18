package dev.androml.app

import android.os.ParcelFileDescriptor
import dev.androml.cluster.core.ClusterExecutionHandler
import dev.androml.cluster.core.ClusterExecutionRequest
import dev.androml.cluster.core.ClusterExecutionStatus
import dev.androml.cluster.core.ClusterCapabilityAdvertisement
import dev.androml.cluster.core.ClusterInferenceCodec
import dev.androml.cluster.core.ClusterInferenceResult
import dev.androml.cluster.core.ClusterInferenceTask
import dev.androml.cluster.core.ClusterJobId
import dev.androml.cluster.core.ClusterNode
import dev.androml.cluster.core.ClusterPeer
import dev.androml.cluster.core.ClusterRequest
import dev.androml.cluster.core.ClusterWorkload
import dev.androml.cluster.core.ContentHash
import dev.androml.cluster.core.IdempotentClusterExecutor
import dev.androml.cluster.core.InMemoryClusterJobLedger
import dev.androml.cluster.core.NoEligibleClusterNode
import dev.androml.cluster.core.NodeCapabilities
import dev.androml.cluster.core.PeerEndpoint
import dev.androml.cluster.core.PeerId
import dev.androml.cluster.core.RouteDecision
import dev.androml.cluster.core.ClusterRouter
import dev.androml.cluster.transport.ClusterExecutionClient
import dev.androml.cluster.transport.ClusterExecutionServer
import dev.androml.cluster.transport.ClusterTransportConfig
import dev.androml.core.api.CertificateFingerprint
import dev.androml.core.database.ClusterPeerRepository
import dev.androml.core.database.ModelCatalogRepository
import dev.androml.core.files.FileArtifactStore
import dev.androml.core.model.ModelRequirements
import dev.androml.core.model.ModelWorkload
import dev.androml.core.model.DeviceProfile
import dev.androml.core.model.ThermalStatus
import dev.androml.core.security.MtlsContextFactory
import dev.androml.core.security.TlsIdentityStore
import dev.androml.core.security.X509CertificateCodec
import dev.androml.runtime.api.InferenceEvent
import dev.androml.runtime.api.InferenceRequest
import dev.androml.runtime.api.InferenceRequestId
import dev.androml.runtime.api.RuntimeConfiguration
import dev.androml.runtime.api.RuntimeId
import dev.androml.runtime.service.InferenceServiceClient
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

sealed interface ClusterControllerState {
    data object Disabled : ClusterControllerState

    data class Running(
        val host: String,
        val port: Int,
        val pairedPeerCount: Int,
    ) : ClusterControllerState

    data class Failed(val message: String) : ClusterControllerState
}

data class ClusterInferenceExecution(
    val placement: RouteDecision,
    val result: ClusterInferenceResult,
)

/** Owns the app's mTLS listener and bridges typed inference jobs into the isolated runtime. */
class ClusterController(
    private val peerRepository: ClusterPeerRepository,
    private val tlsIdentityStore: TlsIdentityStore,
    private val inferenceServiceClient: InferenceServiceClient,
    private val catalogRepository: ModelCatalogRepository,
    private val artifactStore: FileArtifactStore,
    private val deviceProfileProvider: () -> DeviceProfile,
) {
    private val _state = MutableStateFlow<ClusterControllerState>(ClusterControllerState.Disabled)
    private val ledger = InMemoryClusterJobLedger()
    private var server: ClusterExecutionServer? = null
    @Volatile
    private var localAdvertisement: ClusterCapabilityAdvertisement? = null
    val state: StateFlow<ClusterControllerState> = _state.asStateFlow()

    @Synchronized
    fun currentState(): ClusterControllerState = _state.value

    suspend fun start(port: Int): ClusterControllerState {
        synchronized(this) {
            if (server != null) return _state.value
        }
        val activePeers = withContext(Dispatchers.IO) {
            peerRepository.snapshot().filter { stored ->
                stored.peer.paired &&
                    !stored.peer.revoked &&
                    stored.peer.certificateExpiresAtEpochMillis > System.currentTimeMillis()
            }
        }
        require(activePeers.isNotEmpty()) { "Pair at least one non-revoked peer before enabling the listener" }
        val identity = withContext(Dispatchers.IO) {
            tlsIdentityStore.loadOrCreate(CLUSTER_TLS_ALIAS, CLUSTER_TLS_SUBJECT)
        }
        val advertisement = withContext(Dispatchers.IO) {
            buildLocalAdvertisement(identity.fingerprint)
        }
        localAdvertisement = advertisement
        val certificates = activePeers.map { stored ->
            X509CertificateCodec.decodeDer(stored.certificateDer)
        }
        val material = MtlsContextFactory.serverMaterial(identity, certificates)
        val pairedPeerMap = activePeers.associate { stored ->
            stored.peer.fingerprint to stored.peer.id
        }
        val candidate = ClusterExecutionServer(
            config = ClusterTransportConfig(host = "0.0.0.0", port = port),
            tlsMaterial = material,
            pairedPeers = { pairedPeerMap },
            executor = IdempotentClusterExecutor(
                ledger = ledger,
                handler = LocalClusterExecutionHandler(),
                nowEpochMillis = { System.currentTimeMillis() },
            ),
            localAdvertisement = { localAdvertisement },
        )
        return try {
            candidate.start()
            synchronized(this) {
                server = candidate
                ClusterControllerState.Running(
                    host = "0.0.0.0",
                    port = port,
                    pairedPeerCount = activePeers.size,
                ).also { _state.value = it }
            }
        } catch (error: Throwable) {
            candidate.stop()
            localAdvertisement = null
            val failed = ClusterControllerState.Failed(
                error.message?.take(256) ?: "cluster listener could not start",
            )
            synchronized(this) { _state.value = failed }
            failed
        }
    }

    fun stop() {
        val oldServer = synchronized(this) {
            val current = server
            server = null
            localAdvertisement = null
            _state.value = ClusterControllerState.Disabled
            current
        }
        oldServer?.stop()
    }

    suspend fun refreshPeer(peerId: PeerId): ClusterCapabilityAdvertisement = withContext(Dispatchers.IO) {
        val stored = peerRepository.snapshot().firstOrNull { it.peer.id == peerId }
            ?: throw IllegalArgumentException("cluster peer does not exist")
        check(stored.peer.paired && !stored.peer.revoked) { "cluster peer is not paired" }
        check(stored.peer.certificateExpiresAtEpochMillis > System.currentTimeMillis()) {
            "cluster peer certificate has expired"
        }
        val identity = tlsIdentityStore.loadOrCreate(CLUSTER_TLS_ALIAS, CLUSTER_TLS_SUBJECT)
        val peerCertificate = X509CertificateCodec.decodeDer(stored.certificateDer)
        val advertisement = ClusterExecutionClient(
            clientIdentity = identity,
            trustedServerCertificate = peerCertificate,
        ).fetchCapabilities(stored.peer.endpoint)
        check(advertisement.nodeId == peerId) {
            "cluster peer advertised a node ID different from its paired identity"
        }
        val updatedPeer = stored.peer.copy(
            capabilities = advertisement.capabilities.copy(lastSeenEpochMillis = System.currentTimeMillis()),
        )
        peerRepository.upsert(stored.copy(peer = updatedPeer))
        advertisement.copy(capabilities = updatedPeer.capabilities)
    }

    /** Refreshes eligible peers, then routes the complete request with bounded failover. */
    suspend fun executeBestInference(
        task: ClusterInferenceTask,
        requiredRamBytes: Long = 0L,
        timeoutMillis: Long = DEFAULT_REMOTE_TIMEOUT_MILLIS,
    ): ClusterInferenceExecution = withContext(Dispatchers.IO) {
        require(requiredRamBytes >= 0L) { "required cluster RAM must not be negative" }
        require(timeoutMillis in 1_000L..10 * 60 * 1_000L) { "cluster execution timeout is out of bounds" }
        val identity = tlsIdentityStore.loadOrCreate(CLUSTER_TLS_ALIAS, CLUSTER_TLS_SUBJECT)
        val payload = ClusterInferenceCodec.encodeTask(task)
        val jobId = ClusterJobId.parse("inference-${UUID.randomUUID()}")
        val deadline = System.currentTimeMillis() + timeoutMillis

        peerRepository.snapshot()
            .filter { it.peer.paired && !it.peer.revoked }
            .forEach { stored ->
                try {
                    refreshPeer(stored.peer.id)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // A stale/offline peer is excluded by the router below.
                }
            }

        val localNode = buildLocalNode(identity)
        val remoteNodes = peerRepository.snapshot().map { stored ->
            ClusterNode(peer = stored.peer, isLocal = false)
        }
        val excluded = mutableSetOf<PeerId>()
        var attempt = 1
        var lastFailure: Exception? = null
        while (attempt <= 1_000 && System.currentTimeMillis() < deadline) {
            val request = ClusterRequest(
                jobId = jobId,
                attempt = attempt,
                workload = ClusterWorkload.InferenceReplica,
                modelKey = null,
                modelHash = task.modelHash,
                requiredRamBytes = requiredRamBytes,
                deadlineEpochMillis = deadline,
                payloadHash = ContentHash.parse(sha256(payload)),
                idempotencyKey = "${jobId.value}:$attempt",
            )
            val candidates = (listOf(localNode) + remoteNodes).filterNot { it.peer.id in excluded }
            val decision = try {
                ClusterRouter().route(request, candidates)
            } catch (error: NoEligibleClusterNode) {
                throw error
            }
            try {
                val result = if (decision.target == localNode.peer.id) {
                    ClusterInferenceCodec.decodeResult(executeLocalInference(task))
                } else {
                    executeRemote(
                        peerId = decision.target,
                        task = task,
                        requiredRamBytes = requiredRamBytes,
                        timeoutMillis = (deadline - System.currentTimeMillis()).coerceAtLeast(1_000L),
                        jobId = jobId,
                        attempt = attempt,
                        deadlineEpochMillis = deadline,
                    )
                }
                return@withContext ClusterInferenceExecution(decision, result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastFailure = error
                excluded += decision.target
                attempt += 1
            }
        }
        throw lastFailure ?: IllegalStateException("cluster inference deadline expired")
    }

    suspend fun executeRemote(
        peerId: PeerId,
        task: ClusterInferenceTask,
        requiredRamBytes: Long = 0L,
        timeoutMillis: Long = DEFAULT_REMOTE_TIMEOUT_MILLIS,
        jobId: ClusterJobId = ClusterJobId.parse("inference-${UUID.randomUUID()}"),
        attempt: Int = 1,
        deadlineEpochMillis: Long? = null,
    ): ClusterInferenceResult = withContext(Dispatchers.IO) {
        require(timeoutMillis in 1_000L..10 * 60 * 1_000L) { "cluster execution timeout is out of bounds" }
        require(attempt in 1..1_000) { "cluster attempt is out of bounds" }
        val peer = peerRepository.snapshot()
            .firstOrNull { it.peer.id == peerId }
            ?: throw IllegalArgumentException("cluster peer does not exist")
        check(peer.peer.paired && !peer.peer.revoked) { "cluster peer is not paired" }
        check(peer.peer.certificateExpiresAtEpochMillis > System.currentTimeMillis()) {
            "cluster peer certificate has expired"
        }
        val identity = tlsIdentityStore.loadOrCreate(CLUSTER_TLS_ALIAS, CLUSTER_TLS_SUBJECT)
        val peerCertificate = X509CertificateCodec.decodeDer(peer.certificateDer)
        val client = ClusterExecutionClient(
            clientIdentity = identity,
            trustedServerCertificate = peerCertificate,
            readTimeoutMillis = timeoutMillis.toInt(),
        )
        val payload = ClusterInferenceCodec.encodeTask(task)
        val deadline = deadlineEpochMillis ?: (System.currentTimeMillis() + timeoutMillis)
        val request = ClusterExecutionRequest(
            sourcePeerId = clusterNodeId(identity.fingerprint),
            request = ClusterRequest(
                jobId = jobId,
                attempt = 1,
                workload = ClusterWorkload.InferenceReplica,
                modelKey = null,
                modelHash = task.modelHash,
                requiredRamBytes = requiredRamBytes,
                deadlineEpochMillis = deadline,
                payloadHash = ContentHash.parse(sha256(payload)),
                idempotencyKey = "${jobId.value}:$attempt",
            ),
            payload = payload,
        )
        val response = client.execute(peer.peer.endpoint, request)
        if (response.status != ClusterExecutionStatus.Completed &&
            response.status != ClusterExecutionStatus.AlreadyCompleted
        ) {
            throw IllegalStateException(response.safeMessage ?: "remote cluster inference failed")
        }
        val output = response.output ?: throw IllegalStateException(
            "remote cluster completed the attempt but did not return its output",
        )
        ClusterInferenceCodec.decodeResult(output)
    }

    fun close() {
        stop()
    }

    private inner class LocalClusterExecutionHandler : ClusterExecutionHandler {
        override fun execute(request: ClusterExecutionRequest): ByteArray {
            require(request.request.workload == ClusterWorkload.InferenceReplica) {
                "this node currently accepts inference replica work only"
            }
            val task = ClusterInferenceCodec.decodeTask(request.payload)
            require(request.request.modelHash == task.modelHash) {
                "inference task model hash does not match the cluster request"
            }
            return runBlocking(Dispatchers.Default) {
                executeLocalInference(task)
            }
        }
    }

    private suspend fun executeLocalInference(task: ClusterInferenceTask): ByteArray {
        val modelFile = catalogRepository.fileForArtifact(task.modelHash.value)
            ?: throw IllegalArgumentException("requested model artifact is not in the local catalog")
        check(modelFile.artifactSha256 == task.modelHash.value && artifactStore.contains(task.modelHash.value)) {
            "requested model artifact is not installed"
        }
        val modelPath = artifactStore.fileFor(task.modelHash.value)
        val descriptor = ParcelFileDescriptor.open(modelPath, ParcelFileDescriptor.MODE_READ_ONLY)
        var session: dev.androml.runtime.service.OpenedInferenceSession? = null
        return try {
            session = inferenceServiceClient.openSession(
                model = ModelRequirements(
                    workload = ModelWorkload.TextGeneration,
                    weightBytes = modelFile.sizeBytes,
                    kvCacheBytesPerToken = task.kvCacheBytesPerToken,
                    contextTokens = task.contextTokens,
                ),
                configuration = RuntimeConfiguration(
                    cpuThreads = task.cpuThreads,
                    contextTokens = task.contextTokens,
                    useAcceleration = task.useAcceleration,
                ),
                runtimeId = RuntimeId.parse(task.runtimeId),
                modelFile = descriptor,
            )
            val requestId = InferenceRequestId.parse("cluster-${UUID.randomUUID()}")
            val text = StringBuilder()
            var generatedTokens = 0
            var runtimeId = task.runtimeId
            inferenceServiceClient.stream(
                session,
                InferenceRequest(
                    id = requestId,
                    prompt = task.prompt,
                    maxNewTokens = task.maxNewTokens,
                    temperature = task.temperature,
                    stopSequences = task.stopSequences,
                ),
            ).collect { event ->
                when (event) {
                    is InferenceEvent.Started -> runtimeId = event.runtimeId.value
                    is InferenceEvent.Token -> text.append(event.text)
                    is InferenceEvent.Completed -> generatedTokens = event.generatedTokens
                    is InferenceEvent.Failed -> throw IllegalStateException(event.safeMessage)
                    is InferenceEvent.Cancelled -> throw CancellationException("cluster inference was cancelled")
                }
            }
            ClusterInferenceCodec.encodeResult(
                ClusterInferenceResult(
                    text = text.toString(),
                    generatedTokens = generatedTokens,
                    runtimeId = runtimeId,
                ),
            )
        } finally {
            session?.let(inferenceServiceClient::closeSession)
            descriptor.close()
        }
    }

    private suspend fun buildLocalAdvertisement(
        fingerprint: CertificateFingerprint,
    ): ClusterCapabilityAdvertisement {
        val profile = deviceProfileProvider()
        val thermalSeverity = when (profile.thermalStatus) {
            ThermalStatus.Unknown,
            ThermalStatus.Nominal,
            -> 0
            ThermalStatus.Warm -> 1
            ThermalStatus.Hot -> 2
            ThermalStatus.Severe -> 3
            ThermalStatus.Critical -> 4
        }
        return ClusterCapabilityAdvertisement(
            nodeId = clusterNodeId(fingerprint),
            capabilities = NodeCapabilities(
                supportedWorkloads = setOf(
                    ClusterWorkload.InferenceReplica,
                    ClusterWorkload.WorkflowStage,
                    ClusterWorkload.RagSearch,
                ),
                modelHashes = catalogRepository.installedArtifactHashes(),
                maxConcurrentJobs = 1,
                availableRamBytes = profile.availableMemoryBytes ?: 0L,
                queueDepth = 0,
                thermalSeverity = thermalSeverity,
                batteryPercent = 100,
                charging = profile.isCharging,
                lastSeenEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun buildLocalNode(identity: dev.androml.core.security.TlsIdentity): ClusterNode {
        val advertisement = buildLocalAdvertisement(identity.fingerprint)
        return ClusterNode(
            peer = ClusterPeer(
                id = advertisement.nodeId,
                fingerprint = identity.fingerprint,
                displayName = "This phone",
                endpoint = PeerEndpoint("127.0.0.1", DEFAULT_CLUSTER_PORT),
                pairedAtEpochMillis = identity.certificate.notBefore.time,
                certificateExpiresAtEpochMillis = identity.certificate.notAfter.time,
                paired = true,
                capabilities = advertisement.capabilities,
            ),
            isLocal = true,
        )
    }

    private companion object {
        const val CLUSTER_TLS_ALIAS = "cluster-node"
        const val CLUSTER_TLS_SUBJECT = "AndroML cluster node"
        const val DEFAULT_REMOTE_TIMEOUT_MILLIS = 60_000L
        const val DEFAULT_CLUSTER_PORT = 8789

        fun sha256(value: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(value)
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}

/** Stable node name that binds the declared sender to the certificate being presented. */
internal fun clusterNodeId(fingerprint: CertificateFingerprint): PeerId =
    PeerId.parse("node-${fingerprint.value.take(48)}")
