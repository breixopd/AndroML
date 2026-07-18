package dev.androml.app

import android.os.ParcelFileDescriptor
import dev.androml.cluster.core.ClusterExecutionHandler
import dev.androml.cluster.core.ClusterExecutionRequest
import dev.androml.cluster.core.ClusterExecutionStatus
import dev.androml.cluster.core.ClusterInferenceCodec
import dev.androml.cluster.core.ClusterInferenceResult
import dev.androml.cluster.core.ClusterInferenceTask
import dev.androml.cluster.core.ClusterJobId
import dev.androml.cluster.core.ClusterRequest
import dev.androml.cluster.core.ClusterWorkload
import dev.androml.cluster.core.ContentHash
import dev.androml.cluster.core.IdempotentClusterExecutor
import dev.androml.cluster.core.InMemoryClusterJobLedger
import dev.androml.cluster.core.PeerId
import dev.androml.cluster.transport.ClusterExecutionClient
import dev.androml.cluster.transport.ClusterExecutionServer
import dev.androml.cluster.transport.ClusterTransportConfig
import dev.androml.core.database.ClusterPeerRepository
import dev.androml.core.database.ModelCatalogRepository
import dev.androml.core.files.FileArtifactStore
import dev.androml.core.model.ModelRequirements
import dev.androml.core.model.ModelWorkload
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

/** Owns the app's mTLS listener and bridges typed inference jobs into the isolated runtime. */
class ClusterController(
    private val peerRepository: ClusterPeerRepository,
    private val tlsIdentityStore: TlsIdentityStore,
    private val inferenceServiceClient: InferenceServiceClient,
    private val catalogRepository: ModelCatalogRepository,
    private val artifactStore: FileArtifactStore,
) {
    private val _state = MutableStateFlow<ClusterControllerState>(ClusterControllerState.Disabled)
    private val ledger = InMemoryClusterJobLedger()
    private var server: ClusterExecutionServer? = null
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
            _state.value = ClusterControllerState.Disabled
            current
        }
        oldServer?.stop()
    }

    suspend fun executeRemote(
        peerId: PeerId,
        task: ClusterInferenceTask,
        requiredRamBytes: Long = 0L,
        timeoutMillis: Long = DEFAULT_REMOTE_TIMEOUT_MILLIS,
    ): ClusterInferenceResult = withContext(Dispatchers.IO) {
        require(timeoutMillis in 1_000L..10 * 60 * 1_000L) { "cluster execution timeout is out of bounds" }
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
        val jobId = ClusterJobId.parse("inference-${UUID.randomUUID()}")
        val request = ClusterExecutionRequest(
            sourcePeerId = PeerId.parse(CLUSTER_NODE_ID),
            request = ClusterRequest(
                jobId = jobId,
                attempt = 1,
                workload = ClusterWorkload.InferenceReplica,
                modelKey = null,
                modelHash = task.modelHash,
                requiredRamBytes = requiredRamBytes,
                deadlineEpochMillis = System.currentTimeMillis() + timeoutMillis,
                payloadHash = ContentHash.parse(sha256(payload)),
            idempotencyKey = "${jobId.value}:1",
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

    private companion object {
        const val CLUSTER_NODE_ID = "cluster-node"
        const val CLUSTER_TLS_ALIAS = "cluster-node"
        const val CLUSTER_TLS_SUBJECT = "AndroML cluster node"
        const val DEFAULT_REMOTE_TIMEOUT_MILLIS = 60_000L

        fun sha256(value: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(value)
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}
