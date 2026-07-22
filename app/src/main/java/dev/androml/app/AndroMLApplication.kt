package dev.androml.app

import android.app.Application
import android.os.ParcelFileDescriptor
import dev.androml.api.server.AndroMlApiServer
import dev.androml.api.server.ApiInferenceGateway
import dev.androml.api.server.ApiServerConfig
import dev.androml.api.server.ChatCompletionRequest
import dev.androml.api.server.ChatDelta
import dev.androml.api.server.ApiFeatureGateway
import dev.androml.core.api.BindMode
import dev.androml.core.database.ApiKeyRepository
import dev.androml.core.database.AndroMlDatabase
import dev.androml.core.database.ClusterPeerRepository
import dev.androml.core.database.ModelCatalogRepository
import dev.androml.core.database.RagRepository
import dev.androml.core.database.RuntimeBenchmarkRepository
import dev.androml.core.device.AndroidDeviceProfileCollector
import dev.androml.core.files.FileArtifactStore
import dev.androml.core.model.ModelRequirements
import dev.androml.core.model.ModelWorkload
import dev.androml.core.network.HuggingFaceArtifactDownloader
import dev.androml.core.network.HuggingFaceModelClient
import dev.androml.core.security.AndroidKeystoreSecretStore
import dev.androml.core.security.ApiClientCertificateStore
import dev.androml.core.security.MtlsContextFactory
import dev.androml.core.security.SecretStore
import dev.androml.core.security.TlsIdentityStore
import dev.androml.core.security.TlsServerMaterial
import dev.androml.runtime.api.InferenceEvent
import dev.androml.runtime.api.InferenceRequest
import dev.androml.runtime.api.InferenceRequestId
import dev.androml.runtime.api.RuntimeConfiguration
import dev.androml.runtime.api.RuntimeId
import dev.androml.runtime.service.InferenceServiceClient
import dev.androml.runtime.service.OpenedInferenceSession
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import okhttp3.OkHttpClient

class AndroMLApplication : Application() {
    val secretStore: SecretStore by lazy {
        AndroidKeystoreSecretStore(this)
    }

    val httpClient: OkHttpClient by lazy { OkHttpClient() }

    val huggingFaceClient: HuggingFaceModelClient by lazy {
        HuggingFaceModelClient(httpClient)
    }

    val artifactStore: FileArtifactStore by lazy {
        FileArtifactStore(File(filesDir, "model-artifacts"))
    }

    val artifactDownloader: HuggingFaceArtifactDownloader by lazy {
        HuggingFaceArtifactDownloader(httpClient, artifactStore)
    }

    val catalogDatabase: AndroMlDatabase by lazy {
        AndroMlDatabase.open(this)
    }

    val catalogRepository: ModelCatalogRepository by lazy {
        ModelCatalogRepository(catalogDatabase.modelCatalogDao())
    }

    val ragRepository: RagRepository by lazy {
        RagRepository(catalogDatabase.ragDao())
    }

    val runtimeBenchmarkRepository: RuntimeBenchmarkRepository by lazy {
        RuntimeBenchmarkRepository(catalogDatabase.runtimeBenchmarkDao())
    }

    val apiKeyRepository: ApiKeyRepository by lazy {
        ApiKeyRepository(catalogDatabase.apiKeyDao())
    }

    val clusterPeerRepository: ClusterPeerRepository by lazy {
        ClusterPeerRepository(catalogDatabase.clusterPeerDao())
    }

    val workflowDefinitionRepository: dev.androml.core.database.WorkflowDefinitionRepository by lazy {
        dev.androml.core.database.WorkflowDefinitionRepository(catalogDatabase.workflowDefinitionDao())
    }

    val apiTlsIdentityStore: TlsIdentityStore by lazy {
        TlsIdentityStore(secretStore)
    }

    val apiClientCertificateStore: ApiClientCertificateStore by lazy {
        ApiClientCertificateStore(secretStore)
    }

    val clusterTlsIdentityStore: TlsIdentityStore by lazy {
        TlsIdentityStore(secretStore)
    }

    val inferenceServiceClient: InferenceServiceClient by lazy {
        InferenceServiceClient(this)
    }

    val clusterController: ClusterController by lazy {
        ClusterController(
            peerRepository = clusterPeerRepository,
            tlsIdentityStore = clusterTlsIdentityStore,
            inferenceServiceClient = inferenceServiceClient,
            catalogRepository = catalogRepository,
            artifactStore = artifactStore,
            ragRepository = ragRepository,
            deviceProfileProvider = {
                AndroidDeviceProfileCollector(applicationContext).collect()
            },
        )
    }

    val workflowController: WorkflowController by lazy {
        WorkflowController(
            definitionRepository = workflowDefinitionRepository,
            eventStore = catalogDatabase.workflowEventDao(),
            checkpointStore = catalogDatabase.workflowCheckpointDao(),
            catalogRepository = catalogRepository,
            clusterController = clusterController,
            deviceProfileProvider = {
                AndroidDeviceProfileCollector(applicationContext).collect()
            },
        )
    }

    val apiController: LocalApiController by lazy {
        LocalApiController(
            apiKeyRepository = apiKeyRepository,
            catalogRepository = catalogRepository,
            artifactStore = artifactStore,
            inferenceServiceClient = inferenceServiceClient,
            deviceProfileProvider = {
                AndroidDeviceProfileCollector(applicationContext).collect()
            },
            apiTlsIdentityStore = apiTlsIdentityStore,
            clientCertificateStore = apiClientCertificateStore,
            features = LocalApiFeatureGateway(
                workflowController = workflowController,
                workflowRepository = workflowDefinitionRepository,
                clusterController = clusterController,
            ),
        )
    }

    override fun onTerminate() {
        apiController.close()
        clusterController.close()
        inferenceServiceClient.close()
        catalogDatabase.close()
        super.onTerminate()
    }
}

sealed interface LocalApiState {
    data object Disabled : LocalApiState

    data class Running(
        val host: String,
        val port: Int,
        val bindMode: BindMode,
    ) : LocalApiState

    data class Failed(val message: String) : LocalApiState
}

class LocalApiController(
    private val apiKeyRepository: ApiKeyRepository,
    private val catalogRepository: ModelCatalogRepository,
    private val artifactStore: FileArtifactStore,
    private val inferenceServiceClient: InferenceServiceClient,
    private val deviceProfileProvider: () -> dev.androml.core.model.DeviceProfile,
    private val apiTlsIdentityStore: TlsIdentityStore,
    private val clientCertificateStore: ApiClientCertificateStore,
    private val features: ApiFeatureGateway,
) {
    private var server: AndroMlApiServer? = null
    private val _state = MutableStateFlow<LocalApiState>(LocalApiState.Disabled)
    val state: StateFlow<LocalApiState> = _state.asStateFlow()

    @Synchronized
    fun currentState(): LocalApiState = _state.value

    suspend fun startLoopback(port: Int): LocalApiState {
        return startServer(
            config = ApiServerConfig(port = port),
            tlsMaterial = null,
            displayHost = "127.0.0.1",
        )
    }

    suspend fun startLan(port: Int): LocalApiState {
        val clientCertificates = clientCertificateStore.activeCertificates()
        check(clientCertificates.isNotEmpty()) {
            "Pair at least one client certificate before enabling LAN API access"
        }
        val serverIdentity = apiTlsIdentityStore.loadOrCreate(
            alias = API_TLS_ALIAS,
            subjectName = API_TLS_SUBJECT,
        )
        return startServer(
            config = ApiServerConfig(
                bindMode = BindMode.Lan,
                host = "0.0.0.0",
                port = port,
            ),
            tlsMaterial = MtlsContextFactory.serverMaterial(serverIdentity, clientCertificates),
            displayHost = "0.0.0.0",
        )
    }

    private suspend fun startServer(
        config: ApiServerConfig,
        tlsMaterial: TlsServerMaterial?,
        displayHost: String,
    ): LocalApiState {
        synchronized(this) {
            if (server != null) return _state.value
        }
        check(apiKeyRepository.snapshot().any { it.isUsableAt(System.currentTimeMillis()) }) {
            "Create at least one active API key before enabling the API"
        }
        val apiServer = AndroMlApiServer(
            config = config,
            apiKeys = { apiKeyRepository.snapshot() },
            models = { catalogRepository.runnableModelKeys() },
            inference = IsolatedRuntimeApiGateway(
                inferenceServiceClient = inferenceServiceClient,
                catalogRepository = catalogRepository,
                artifactStore = artifactStore,
                deviceProfileProvider = deviceProfileProvider,
            ),
            onKeyUsed = { id ->
                try {
                    apiKeyRepository.markUsed(id)
                } catch (_: Throwable) {
                    // Usage telemetry must not turn an authorized request into a failed request.
                }
            },
            tlsMaterial = tlsMaterial,
            features = features,
        )
        return try {
            apiServer.start()
            synchronized(this) {
                server = apiServer
                val running = LocalApiState.Running(displayHost, config.port, config.bindMode)
                _state.value = running
                running
            }
        } catch (error: Throwable) {
            apiServer.stop()
            val failed = LocalApiState.Failed(error.message?.take(256) ?: "API server could not start")
            synchronized(this) { _state.value = failed }
            failed
        }
    }

    fun stop() {
        val oldServer = synchronized(this) {
            val current = server
            server = null
            _state.value = LocalApiState.Disabled
            current
        }
        oldServer?.stop()
    }

    fun close() = stop()
}

private const val API_TLS_ALIAS = "api-server"
private const val API_TLS_SUBJECT = "AndroML API"

private class IsolatedRuntimeApiGateway(
    private val inferenceServiceClient: InferenceServiceClient,
    private val catalogRepository: ModelCatalogRepository,
    private val artifactStore: FileArtifactStore,
    private val deviceProfileProvider: () -> dev.androml.core.model.DeviceProfile,
) : ApiInferenceGateway {
    override fun streamChat(request: ChatCompletionRequest): Flow<ChatDelta> = flow {
        val device = deviceProfileProvider()
        val modelFile = catalogRepository.fileForModelKey(request.model)
            ?: throw IllegalArgumentException("requested model is not installed")
        require(modelFile.path.endsWith(".litertlm", ignoreCase = true)) {
            "requested model does not have a LiteRT-LM artifact"
        }
        val artifactHash = modelFile.artifactSha256
            ?: throw IllegalArgumentException("requested model artifact is not verified")
        check(artifactStore.contains(artifactHash)) { "requested model artifact is missing" }
        val descriptor = ParcelFileDescriptor.open(
            artifactStore.fileFor(artifactHash),
            ParcelFileDescriptor.MODE_READ_ONLY,
        )
        var session: OpenedInferenceSession? = null
        try {
            session = inferenceServiceClient.openSession(
                model = ModelRequirements(
                    workload = ModelWorkload.TextGeneration,
                    weightBytes = modelFile.sizeBytes,
                    contextTokens = 2048,
                ),
                configuration = RuntimeConfiguration(
                    cpuThreads = device.cpuCoreCount.coerceIn(1, 8),
                    contextTokens = 2048,
                    useAcceleration = false,
                ),
                runtimeId = RuntimeId.parse("litertlm"),
                modelFile = descriptor,
            )
            val prompt = request.messages.joinToString("\n") { message ->
                "${message.role}: ${message.content}"
            }
            val inferenceRequest = InferenceRequest(
                id = InferenceRequestId.parse("api-${UUID.randomUUID()}"),
                prompt = prompt,
                maxNewTokens = request.maxTokens,
                temperature = request.temperature,
            )
            inferenceServiceClient.stream(session, inferenceRequest).collect { event ->
                when (event) {
                    is InferenceEvent.Token -> emit(ChatDelta(event.text))
                    is InferenceEvent.Failed -> error(event.safeMessage)
                    is InferenceEvent.Cancelled -> error("inference request was cancelled")
                    is InferenceEvent.Started,
                    is InferenceEvent.Completed,
                    -> Unit
                }
            }
        } finally {
            session?.let(inferenceServiceClient::closeSession)
            descriptor.close()
        }
    }
}
