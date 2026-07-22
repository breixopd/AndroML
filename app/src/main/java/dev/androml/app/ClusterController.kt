package dev.androml.app

import android.os.ParcelFileDescriptor
import dev.androml.cluster.core.ClusterExecutionHandler
import dev.androml.cluster.core.ClusterExecutionRequest
import dev.androml.cluster.core.ClusterExecutionStatus
import dev.androml.cluster.core.ClusterCapabilityAdvertisement
import dev.androml.cluster.core.ClusterInferenceCodec
import dev.androml.cluster.core.ClusterInferenceResult
import dev.androml.cluster.core.ClusterInferenceTask
import dev.androml.cluster.core.ClusterModelTransferAck
import dev.androml.cluster.core.ClusterModelTransferChunk
import dev.androml.cluster.core.ClusterModelTransferCodec
import dev.androml.cluster.core.ClusterJobId
import dev.androml.cluster.core.ClusterNode
import dev.androml.cluster.core.ClusterPeer
import dev.androml.cluster.core.ClusterRagCodec
import dev.androml.cluster.core.ClusterRagSearchTask
import dev.androml.cluster.core.ClusterWorkflowCodec
import dev.androml.cluster.core.ClusterWorkflowStageResult
import dev.androml.cluster.core.ClusterWorkflowStageTask
import dev.androml.cluster.core.ClusterPairingInviteIssuer
import dev.androml.cluster.core.ClusterRequest
import dev.androml.cluster.core.ClusterWorkload
import dev.androml.cluster.core.ContentHash
import dev.androml.cluster.core.DistributedRagMerger
import dev.androml.cluster.core.IdempotentClusterExecutor
import dev.androml.cluster.core.ClusterJobLedger
import dev.androml.cluster.core.NoEligibleClusterNode
import dev.androml.cluster.core.NodeRetrievalResult
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
import dev.androml.core.database.RagRepository
import dev.androml.core.files.FileArtifactStore
import dev.androml.core.rag.HybridRetriever
import dev.androml.core.rag.CollectionId
import dev.androml.core.rag.RetrievalQuery
import dev.androml.core.model.ModelRequirements
import dev.androml.core.model.ModelWorkload
import dev.androml.core.model.ModelFormatClassifier
import dev.androml.core.model.DeviceProfile
import dev.androml.core.model.ThermalStatus
import dev.androml.core.model.HuggingFaceFileDescriptor
import dev.androml.core.model.HuggingFaceModelReference
import dev.androml.core.model.HuggingFaceRepositoryMetadata
import dev.androml.core.security.MtlsContextFactory
import dev.androml.core.security.TlsIdentityStore
import dev.androml.core.security.X509CertificateCodec
import dev.androml.runtime.api.InferenceEvent
import dev.androml.runtime.api.InferenceRequest
import dev.androml.runtime.api.InferenceRequestId
import dev.androml.runtime.api.RuntimeConfiguration
import dev.androml.runtime.api.RuntimeId
import dev.androml.runtime.api.RuntimePackCatalog
import dev.androml.runtime.service.InferenceServiceClient
import dev.androml.core.workflow.WorkflowDocument
import dev.androml.core.workflow.WorkflowValue
import dev.androml.core.workflow.WorkflowValueCodec
import java.util.UUID
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

data class ClusterWorkflowStageExecution(
    val placement: RouteDecision,
    val result: ClusterWorkflowStageResult,
)

/** Owns the app's mTLS listener and bridges typed inference jobs into the isolated runtime. */
class ClusterController(
    private val peerRepository: ClusterPeerRepository,
    private val tlsIdentityStore: TlsIdentityStore,
    private val inferenceServiceClient: InferenceServiceClient,
    private val catalogRepository: ModelCatalogRepository,
    private val artifactStore: FileArtifactStore,
    private val ragRepository: RagRepository,
    private val deviceProfileProvider: () -> DeviceProfile,
    private val ledger: ClusterJobLedger,
    private val discovery: ClusterDiscoveryController,
) {
    private val _state = MutableStateFlow<ClusterControllerState>(ClusterControllerState.Disabled)
    private val pairingInvites = ClusterPairingInviteIssuer()
    private var server: ClusterExecutionServer? = null
    @Volatile
    private var localAdvertisement: ClusterCapabilityAdvertisement? = null
    val state: StateFlow<ClusterControllerState> = _state.asStateFlow()

    @Synchronized
    fun currentState(): ClusterControllerState = _state.value

    suspend fun localNodeId(): PeerId = withContext(Dispatchers.IO) {
        val identity = tlsIdentityStore.loadOrCreate(CLUSTER_TLS_ALIAS, CLUSTER_TLS_SUBJECT)
        clusterNodeId(identity.fingerprint)
    }

    /** Creates a short-lived QR/deep-link payload for onboarding another phone. */
    suspend fun createPairingInvite(host: String, port: Int): String = withContext(Dispatchers.IO) {
        val advertisedHost = host.trim()
        require(advertisedHost.length in 1..253 && !advertisedHost.any(Char::isWhitespace)) {
            "cluster pairing host is invalid"
        }
        require(port in 1024..65_535) { "cluster pairing port is out of bounds" }
        val identity = tlsIdentityStore.loadOrCreate(CLUSTER_TLS_ALIAS, CLUSTER_TLS_SUBJECT)
        val invite = pairingInvites.issue(
            peerId = clusterNodeId(identity.fingerprint),
            endpoint = PeerEndpoint(advertisedHost, port),
            certificate = identity.encodedCertificate,
            fingerprint = identity.fingerprint,
            nowEpochMillis = System.currentTimeMillis(),
        )
        pairingInvites.encodeQrPayload(invite)
    }

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
                discovery.register(localAdvertisement?.nodeId?.value ?: "unknown", port)
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
        discovery.stopDiscovery()
        discovery.unregister()
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

    /** Refreshes the local capability snapshot exposed to paired peers. */
    suspend fun refreshLocalCapabilities(): ClusterCapabilityAdvertisement = withContext(Dispatchers.IO) {
        val identity = tlsIdentityStore.loadOrCreate(CLUSTER_TLS_ALIAS, CLUSTER_TLS_SUBJECT)
        val advertisement = buildLocalAdvertisement(identity.fingerprint)
        synchronized(this@ClusterController) {
            if (server != null) localAdvertisement = advertisement
        }
        advertisement
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

    /** Fans a bounded query out to fresh trusted nodes and merges citation-bearing local results. */
    suspend fun searchDistributedRag(
        task: ClusterRagSearchTask,
        timeoutMillis: Long = DEFAULT_REMOTE_TIMEOUT_MILLIS,
    ): List<dev.androml.cluster.core.DistributedRetrievalResult> = withContext(Dispatchers.IO) {
        require(timeoutMillis in 1_000L..10 * 60 * 1_000L) { "cluster RAG timeout is out of bounds" }
        val identity = tlsIdentityStore.loadOrCreate(CLUSTER_TLS_ALIAS, CLUSTER_TLS_SUBJECT)
        val localNodeId = clusterNodeId(identity.fingerprint)
        val payload = ClusterRagCodec.encodeTask(task)
        val jobId = ClusterJobId.parse("rag-${UUID.randomUUID()}")
        val deadline = System.currentTimeMillis() + timeoutMillis

        peerRepository.snapshot()
            .filter { it.peer.paired && !it.peer.revoked }
            .forEach { stored ->
                try {
                    refreshPeer(stored.peer.id)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Offline nodes are omitted from the fan-out.
                }
            }
        val peers = peerRepository.snapshot()
            .filter { stored ->
                stored.peer.paired &&
                    !stored.peer.revoked &&
                    stored.peer.capabilities.lastSeenEpochMillis >= System.currentTimeMillis() - CAPABILITY_STALE_AFTER_MILLIS &&
                    ClusterWorkload.RagSearch in stored.peer.capabilities.supportedWorkloads
            }
        val shards = mutableListOf<NodeRetrievalResult>()
        val localResults = executeLocalRag(payload)
        shards += NodeRetrievalResult(localNodeId, ClusterRagCodec.decodeResult(localResults))
        coroutineScope {
            peers.map { peer ->
                async(Dispatchers.IO) {
                    try {
                        val results = executeRemoteRag(
                            peerId = peer.peer.id,
                            task = task,
                            jobId = jobId,
                            timeoutMillis = (deadline - System.currentTimeMillis()).coerceAtLeast(1_000L),
                            deadlineEpochMillis = deadline,
                        )
                        NodeRetrievalResult(peer.peer.id, results)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull().forEach(shards::add)
        }
        DistributedRagMerger().merge(task.query.topK, shards)
    }

    /** Routes one bounded model/RAG workflow stage as a complete request to a trusted node. */
    suspend fun executeBestWorkflowStage(
        task: ClusterWorkflowStageTask,
        requiredRamBytes: Long = 0L,
        timeoutMillis: Long = DEFAULT_REMOTE_TIMEOUT_MILLIS,
    ): ClusterWorkflowStageExecution = withContext(Dispatchers.IO) {
        require(task.stageKind == WORKFLOW_MODEL_STAGE || task.stageKind == WORKFLOW_RAG_STAGE) {
            "unsupported distributed workflow stage"
        }
        if (task.stageKind == WORKFLOW_MODEL_STAGE) {
            require(task.modelHash != null) { "distributed model stages require a model hash" }
        }
        require(requiredRamBytes >= 0L) { "required workflow RAM must not be negative" }
        require(timeoutMillis in 1_000L..10 * 60 * 1_000L) {
            "workflow execution timeout is out of bounds"
        }
        val identity = tlsIdentityStore.loadOrCreate(CLUSTER_TLS_ALIAS, CLUSTER_TLS_SUBJECT)
        val payload = ClusterWorkflowCodec.encodeTask(task)
        val jobId = ClusterJobId.parse("workflow-${UUID.randomUUID()}")
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
                workload = ClusterWorkload.WorkflowStage,
                modelKey = task.stageKey,
                modelHash = task.modelHash,
                requiredRamBytes = requiredRamBytes,
                deadlineEpochMillis = deadline,
                payloadHash = ContentHash.parse(sha256(payload)),
                idempotencyKey = "${jobId.value}:$attempt",
            )
            val candidates = (listOf(localNode) + remoteNodes).filterNot { it.peer.id in excluded }
            val decision = ClusterRouter().route(request, candidates)
            try {
                val output = if (decision.target == localNode.peer.id) {
                    executeLocalWorkflowStage(task)
                } else {
                    executeRemoteWorkflowStage(
                        peerId = decision.target,
                        task = task,
                        requiredRamBytes = requiredRamBytes,
                        timeoutMillis = (deadline - System.currentTimeMillis()).coerceAtLeast(1_000L),
                        jobId = jobId,
                        attempt = attempt,
                        deadlineEpochMillis = deadline,
                    )
                }
                return@withContext ClusterWorkflowStageExecution(
                    placement = decision,
                    result = ClusterWorkflowCodec.decodeResult(output),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastFailure = error
                excluded += decision.target
                attempt += 1
            }
        }
        throw lastFailure ?: IllegalStateException("workflow stage deadline expired")
    }

    /** Transfers a verified artifact to a paired node only after explicit owner approval. */
    suspend fun transferModel(
        peerId: PeerId,
        artifactHash: ContentHash,
        ownerApproved: Boolean,
        timeoutMillis: Long = 10 * 60 * 1_000L,
    ): ClusterModelTransferAck = withContext(Dispatchers.IO) {
        require(ownerApproved) { "model transfer requires explicit owner approval" }
        require(timeoutMillis in 1_000L..30 * 60 * 1_000L) { "model transfer timeout is out of bounds" }
        check(artifactStore.contains(artifactHash.value)) { "model artifact is not installed" }
        val file = catalogRepository.fileForArtifact(artifactHash.value)
            ?: throw IllegalArgumentException("model artifact metadata is unavailable")
        val model = catalogRepository.snapshotModels().firstOrNull { record ->
            record.modelId == file.modelId && record.revision == file.revision
        } ?: throw IllegalArgumentException("model repository metadata is unavailable")
        val reference = HuggingFaceModelReference.parse(file.modelId, file.revision)
        val totalSize = file.sizeBytes
        require(totalSize > 0L) { "empty model artifacts cannot be transferred" }
        val peer = peerRepository.snapshot().firstOrNull { it.peer.id == peerId }
            ?: throw IllegalArgumentException("cluster peer does not exist")
        check(peer.peer.paired && !peer.peer.revoked) { "cluster peer is not paired" }
        check(ClusterWorkload.ModelTransfer in peer.peer.capabilities.supportedWorkloads) {
            "cluster peer does not support model transfer"
        }
        val identity = tlsIdentityStore.loadOrCreate(CLUSTER_TLS_ALIAS, CLUSTER_TLS_SUBJECT)
        val peerCertificate = X509CertificateCodec.decodeDer(peer.certificateDer)
        val transferId = "transfer-${UUID.randomUUID()}"
        val deadline = System.currentTimeMillis() + timeoutMillis
        var offset = 0L
        var lastAck = ClusterModelTransferAck(transferId, artifactHash, 0L, committed = false)
        while (offset < totalSize && System.currentTimeMillis() < deadline) {
            val bytes = readChunk(artifactHash.value, offset, totalSize)
            val transferChunk = ClusterModelTransferChunk(
                transferId = transferId,
                artifactHash = artifactHash,
                totalSizeBytes = totalSize,
                offsetBytes = offset,
                chunk = bytes,
                finalChunk = offset + bytes.size == totalSize,
                modelId = reference.modelId.value,
                revision = reference.revision.value,
                path = file.path,
                license = model.license,
                isPrivate = model.isPrivate,
                isGated = model.isGated,
            )
            val payload = ClusterModelTransferCodec.encodeChunk(transferChunk)
            val request = ClusterExecutionRequest(
                sourcePeerId = clusterNodeId(identity.fingerprint),
                request = ClusterRequest(
                    jobId = ClusterJobId.parse("$transferId-${offset.toString(16)}"),
                    attempt = 1,
                    workload = ClusterWorkload.ModelTransfer,
                    modelKey = artifactHash.value,
                    modelHash = null,
                    requiredRamBytes = 0L,
                    deadlineEpochMillis = deadline,
                    payloadHash = ContentHash.parse(sha256(payload)),
                    idempotencyKey = "$transferId:$offset",
                ),
                payload = payload,
            )
            val response = ClusterExecutionClient(
                clientIdentity = identity,
                trustedServerCertificate = peerCertificate,
                readTimeoutMillis = (deadline - System.currentTimeMillis()).coerceAtLeast(1_000L).toInt(),
            ).execute(peer.peer.endpoint, request)
            if (response.status != ClusterExecutionStatus.Completed &&
                response.status != ClusterExecutionStatus.AlreadyCompleted
            ) {
                throw IllegalStateException(response.safeMessage ?: "model transfer failed")
            }
            val output = response.output ?: throw IllegalStateException("model transfer returned no acknowledgement")
            lastAck = ClusterModelTransferCodec.decodeAck(output)
            require(lastAck.transferId == transferId && lastAck.artifactHash == artifactHash) {
                "model transfer acknowledgement does not match the request"
            }
            require(lastAck.nextOffsetBytes in (offset + 1)..totalSize) {
                "model transfer acknowledgement did not advance"
            }
            offset = lastAck.nextOffsetBytes
        }
        check(lastAck.committed && offset == totalSize) { "model transfer did not commit before its deadline" }
        lastAck
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
                attempt = attempt,
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
            return when (request.request.workload) {
                ClusterWorkload.InferenceReplica -> {
                    val task = ClusterInferenceCodec.decodeTask(request.payload)
                    require(request.request.modelHash == task.modelHash) {
                        "inference task model hash does not match the cluster request"
                    }
                    runBlocking(Dispatchers.Default) {
                        executeLocalInference(task)
                    }
                }
                ClusterWorkload.RagSearch -> runBlocking(Dispatchers.Default) {
                    executeLocalRag(request.payload)
                }
                ClusterWorkload.WorkflowStage -> {
                    runBlocking(Dispatchers.Default) {
                        executeLocalWorkflowStage(ClusterWorkflowCodec.decodeTask(request.payload))
                    }
                }
                ClusterWorkload.ModelTransfer -> runBlocking(Dispatchers.IO) {
                    executeLocalModelTransfer(request.payload)
                }
            }
        }
    }

    private suspend fun executeLocalModelTransfer(payload: ByteArray): ByteArray {
        val chunk = ClusterModelTransferCodec.decodeChunk(payload)
        require(chunk.isPrivate || chunk.license != null) {
            "model transfer metadata must include a license or private declaration"
        }
        if (artifactStore.contains(chunk.artifactHash.value)) {
            if (catalogRepository.fileForArtifact(chunk.artifactHash.value) == null) {
                registerTransferredMetadata(chunk)
            }
            return ClusterModelTransferCodec.encodeAck(
                ClusterModelTransferAck(chunk.transferId, chunk.artifactHash, chunk.totalSizeBytes, committed = true),
            )
        }
        val staged = artifactStore.beginResumable(
            key = "cluster-${chunk.transferId}-${chunk.artifactHash.value.take(16)}",
            expectedSha256 = chunk.artifactHash.value,
            expectedSizeBytes = chunk.totalSizeBytes,
        )
        staged.use { resumable ->
            val current = resumable.bytesWritten
            if (current == chunk.totalSizeBytes && chunk.finalChunk) {
                resumable.commit()
                registerTransferredMetadata(chunk)
                return ClusterModelTransferCodec.encodeAck(
                    ClusterModelTransferAck(chunk.transferId, chunk.artifactHash, chunk.totalSizeBytes, committed = true),
                )
            }
            if (current > chunk.offsetBytes) {
                return ClusterModelTransferCodec.encodeAck(
                    ClusterModelTransferAck(chunk.transferId, chunk.artifactHash, current, committed = false),
                )
            }
            require(current == chunk.offsetBytes) { "model transfer chunk is not contiguous" }
            resumable.appendFrom(ByteArrayInputStream(chunk.chunk), maxBytes = chunk.chunk.size.toLong())
            val nextOffset = resumable.bytesWritten
            val committed = if (chunk.finalChunk) {
                require(nextOffset == chunk.totalSizeBytes) { "final model transfer chunk is incomplete" }
                resumable.commit()
                registerTransferredMetadata(chunk)
                true
            } else {
                false
            }
            return ClusterModelTransferCodec.encodeAck(
                ClusterModelTransferAck(chunk.transferId, chunk.artifactHash, nextOffset, committed),
            )
        }
    }

    private suspend fun registerTransferredMetadata(chunk: ClusterModelTransferChunk) {
        val reference = HuggingFaceModelReference.parse(chunk.modelId, chunk.revision)
        catalogRepository.saveMetadata(
            HuggingFaceRepositoryMetadata(
                reference = reference,
                files = listOf(
                    HuggingFaceFileDescriptor(
                        path = chunk.path,
                        sizeBytes = chunk.totalSizeBytes,
                        sha256 = chunk.artifactHash.value,
                    ),
                ),
                isPrivate = chunk.isPrivate,
                isGated = chunk.isGated,
                license = chunk.license,
            ),
        )
        catalogRepository.markArtifactVerified(reference, chunk.path, chunk.artifactHash.value)
    }

    private fun readChunk(artifactHash: String, offset: Long, totalSize: Long): ByteArray {
        val length = minOf(ClusterModelTransferChunk.MAX_CHUNK_BYTES.toLong(), totalSize - offset).toInt()
        val bytes = ByteArray(length)
        artifactStore.open(artifactHash).use { input ->
            var skipped = 0L
            while (skipped < offset) {
                val count = input.skip(offset - skipped)
                if (count <= 0L) throw IllegalStateException("model artifact could not seek to transfer offset")
                skipped += count
            }
            var read = 0
            while (read < bytes.size) {
                val count = input.read(bytes, read, bytes.size - read)
                if (count < 0) throw IllegalStateException("model artifact ended before its declared size")
                read += count
            }
        }
        return bytes
    }

    private suspend fun executeLocalRag(taskPayload: ByteArray): ByteArray {
        val task = ClusterRagCodec.decodeTask(taskPayload)
        val chunks = ragRepository.search(
            collectionId = task.collectionId,
            query = task.query.text,
            limit = task.query.topK,
        )
        return ClusterRagCodec.encodeResult(HybridRetriever().retrieve(task.query, chunks))
    }

    private suspend fun executeRemoteRag(
        peerId: PeerId,
        task: ClusterRagSearchTask,
        jobId: ClusterJobId,
        timeoutMillis: Long,
        deadlineEpochMillis: Long,
    ): List<dev.androml.core.rag.RetrievalResult> = withContext(Dispatchers.IO) {
        val peer = peerRepository.snapshot().firstOrNull { it.peer.id == peerId }
            ?: throw IllegalArgumentException("cluster peer does not exist")
        check(peer.peer.paired && !peer.peer.revoked) { "cluster peer is not paired" }
        val identity = tlsIdentityStore.loadOrCreate(CLUSTER_TLS_ALIAS, CLUSTER_TLS_SUBJECT)
        val peerCertificate = X509CertificateCodec.decodeDer(peer.certificateDer)
        val payload = ClusterRagCodec.encodeTask(task)
        val request = ClusterExecutionRequest(
            sourcePeerId = clusterNodeId(identity.fingerprint),
            request = ClusterRequest(
                jobId = jobId,
                attempt = 1,
                workload = ClusterWorkload.RagSearch,
                modelKey = task.collectionId.value,
                modelHash = null,
                requiredRamBytes = 0L,
                deadlineEpochMillis = deadlineEpochMillis,
                payloadHash = ContentHash.parse(sha256(payload)),
                idempotencyKey = "${jobId.value}:${peerId.value}",
            ),
            payload = payload,
        )
        val response = ClusterExecutionClient(
            clientIdentity = identity,
            trustedServerCertificate = peerCertificate,
            readTimeoutMillis = timeoutMillis.toInt(),
        ).execute(peer.peer.endpoint, request)
        if (response.status != ClusterExecutionStatus.Completed &&
            response.status != ClusterExecutionStatus.AlreadyCompleted
        ) {
            throw IllegalStateException(response.safeMessage ?: "remote cluster RAG failed")
        }
        ClusterRagCodec.decodeResult(
            response.output ?: throw IllegalStateException("remote cluster RAG returned no results"),
        )
    }

    private suspend fun executeRemoteWorkflowStage(
        peerId: PeerId,
        task: ClusterWorkflowStageTask,
        requiredRamBytes: Long,
        timeoutMillis: Long,
        jobId: ClusterJobId,
        attempt: Int,
        deadlineEpochMillis: Long,
    ): ByteArray = withContext(Dispatchers.IO) {
        val peer = peerRepository.snapshot().firstOrNull { it.peer.id == peerId }
            ?: throw IllegalArgumentException("cluster peer does not exist")
        check(peer.peer.paired && !peer.peer.revoked) { "cluster peer is not paired" }
        val identity = tlsIdentityStore.loadOrCreate(CLUSTER_TLS_ALIAS, CLUSTER_TLS_SUBJECT)
        val peerCertificate = X509CertificateCodec.decodeDer(peer.certificateDer)
        val payload = ClusterWorkflowCodec.encodeTask(task)
        val request = ClusterExecutionRequest(
            sourcePeerId = clusterNodeId(identity.fingerprint),
            request = ClusterRequest(
                jobId = jobId,
                attempt = attempt,
                workload = ClusterWorkload.WorkflowStage,
                modelKey = task.stageKey,
                modelHash = task.modelHash,
                requiredRamBytes = requiredRamBytes,
                deadlineEpochMillis = deadlineEpochMillis,
                payloadHash = ContentHash.parse(sha256(payload)),
                idempotencyKey = "${jobId.value}:$attempt",
            ),
            payload = payload,
        )
        val response = ClusterExecutionClient(
            clientIdentity = identity,
            trustedServerCertificate = peerCertificate,
            readTimeoutMillis = timeoutMillis.toInt(),
        ).execute(peer.peer.endpoint, request)
        if (response.status != ClusterExecutionStatus.Completed &&
            response.status != ClusterExecutionStatus.AlreadyCompleted
        ) {
            throw IllegalStateException(response.safeMessage ?: "remote workflow stage failed")
        }
        response.output ?: throw IllegalStateException("remote workflow stage returned no output")
    }

    private suspend fun executeLocalWorkflowStage(task: ClusterWorkflowStageTask): ByteArray {
        val input = WorkflowValueCodec.decode(task.inputPayload)
        val output = when (task.stageKind) {
            WORKFLOW_MODEL_STAGE -> {
                val text = input as? WorkflowValue.Text
                    ?: throw IllegalArgumentException("model workflow stage requires text input")
                val modelHash = task.modelHash
                    ?: throw IllegalArgumentException("model workflow stage requires a model hash")
                val profile = deviceProfileProvider()
                val modelFile = catalogRepository.fileForArtifact(modelHash.value)
                    ?: throw IllegalArgumentException("workflow model artifact is not in the local catalog")
                val runtimeId = ModelFormatClassifier.forPath(modelFile.path)?.runtimeId
                    ?.let(RuntimeId::parse)
                    ?: throw IllegalArgumentException("workflow model format is unsupported")
                check(RuntimePackCatalog.find(runtimeId)?.usable == true) {
                    "workflow model runtime is not bundled in this build"
                }
                val result = ClusterInferenceCodec.decodeResult(
                    executeLocalInference(
                        ClusterInferenceTask(
                            modelHash = modelHash,
                            prompt = text.value,
                            maxNewTokens = 512,
                            temperature = 0.7,
                            contextTokens = 2_048,
                            kvCacheBytesPerToken = 0L,
                            cpuThreads = profile.cpuCoreCount.coerceIn(1, 8),
                            useAcceleration = false,
                            runtimeId = runtimeId.value,
                        ),
                    ),
                )
                WorkflowValue.Text(result.text)
            }
            WORKFLOW_RAG_STAGE -> {
                val text = input as? WorkflowValue.Text
                    ?: throw IllegalArgumentException("RAG workflow stage requires text input")
                val collectionId = CollectionId.parse(task.stageKey)
                val ragTask = ClusterRagSearchTask(
                    collectionId = collectionId,
                    query = RetrievalQuery(text.value),
                )
                val results = ClusterRagCodec.decodeResult(executeLocalRag(ClusterRagCodec.encodeTask(ragTask)))
                WorkflowValue.Documents(
                    results.map { result ->
                        WorkflowDocument(
                            title = result.chunk.title,
                            sourceLabel = result.chunk.sourceLabel,
                            text = result.chunk.text,
                        )
                    },
                )
            }
            else -> throw IllegalArgumentException("unsupported distributed workflow stage")
        }
        return ClusterWorkflowCodec.encodeResult(
            ClusterWorkflowStageResult(WorkflowValueCodec.encode(output)),
        )
    }

    private suspend fun executeLocalInference(task: ClusterInferenceTask): ByteArray {
        val modelFile = catalogRepository.fileForArtifact(task.modelHash.value)
            ?: throw IllegalArgumentException("requested model artifact is not in the local catalog")
        check(modelFile.artifactSha256 == task.modelHash.value && artifactStore.contains(task.modelHash.value)) {
            "requested model artifact is not installed"
        }
        val expectedRuntimeId = ModelFormatClassifier.forPath(modelFile.path)?.runtimeId
            ?: throw IllegalArgumentException("requested model format is unsupported")
        require(expectedRuntimeId == task.runtimeId) {
            "requested runtime does not match the model artifact format"
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
                    ClusterWorkload.ModelTransfer,
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
        const val CAPABILITY_STALE_AFTER_MILLIS = 30_000L
        const val WORKFLOW_MODEL_STAGE = "model"
        const val WORKFLOW_RAG_STAGE = "rag"

        fun sha256(value: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(value)
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}

/** Stable node name that binds the declared sender to the certificate being presented. */
internal fun clusterNodeId(fingerprint: CertificateFingerprint): PeerId =
    PeerId.parse("node-${fingerprint.value.take(48)}")
