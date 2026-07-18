package dev.androml.app

import android.app.Application
import dev.androml.api.server.AndroMlApiServer
import dev.androml.api.server.ApiInferenceGateway
import dev.androml.api.server.ApiServerConfig
import dev.androml.api.server.ChatCompletionRequest
import dev.androml.api.server.ChatDelta
import dev.androml.core.database.ApiKeyRepository
import dev.androml.core.database.AndroMlDatabase
import dev.androml.core.database.ClusterPeerRepository
import dev.androml.core.database.ModelCatalogRepository
import dev.androml.core.database.RagRepository
import dev.androml.core.device.AndroidDeviceProfileCollector
import dev.androml.core.files.FileArtifactStore
import dev.androml.core.model.ModelRequirements
import dev.androml.core.model.ModelWorkload
import dev.androml.core.network.HuggingFaceArtifactDownloader
import dev.androml.core.network.HuggingFaceModelClient
import dev.androml.core.security.AndroidKeystoreSecretStore
import dev.androml.core.security.SecretStore
import dev.androml.core.security.TlsIdentityStore
import dev.androml.runtime.api.InferenceEvent
import dev.androml.runtime.api.InferenceRequest
import dev.androml.runtime.api.InferenceRequestId
import dev.androml.runtime.api.RuntimeConfiguration
import dev.androml.runtime.service.InferenceServiceClient
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

    val apiKeyRepository: ApiKeyRepository by lazy {
        ApiKeyRepository(catalogDatabase.apiKeyDao())
    }

    val clusterPeerRepository: ClusterPeerRepository by lazy {
        ClusterPeerRepository(catalogDatabase.clusterPeerDao())
    }

    val apiTlsIdentityStore: TlsIdentityStore by lazy {
        TlsIdentityStore(secretStore)
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
        )
    }

    val apiController: LocalApiController by lazy {
        LocalApiController(
            apiKeyRepository = apiKeyRepository,
            catalogRepository = catalogRepository,
            inferenceServiceClient = inferenceServiceClient,
            deviceProfileProvider = {
                AndroidDeviceProfileCollector(applicationContext).collect()
            },
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
    ) : LocalApiState

    data class Failed(val message: String) : LocalApiState
}

class LocalApiController(
    private val apiKeyRepository: ApiKeyRepository,
    private val catalogRepository: ModelCatalogRepository,
    private val inferenceServiceClient: InferenceServiceClient,
    private val deviceProfileProvider: () -> dev.androml.core.model.DeviceProfile,
) {
    private var server: AndroMlApiServer? = null
    private val _state = MutableStateFlow<LocalApiState>(LocalApiState.Disabled)
    val state: StateFlow<LocalApiState> = _state.asStateFlow()

    @Synchronized
    fun currentState(): LocalApiState = _state.value

    suspend fun startLoopback(port: Int): LocalApiState {
        synchronized(this) {
            if (server != null) return _state.value
        }
        check(apiKeyRepository.snapshot().any { it.isUsableAt(System.currentTimeMillis()) }) {
            "Create at least one active API key before enabling the API"
        }
        val apiServer = AndroMlApiServer(
            config = ApiServerConfig(port = port),
            apiKeys = { apiKeyRepository.snapshot() },
            models = {
                catalogRepository.snapshotModels().map { model ->
                    "${model.modelId}@${model.revision}"
                }
            },
            inference = IsolatedRuntimeApiGateway(
                inferenceServiceClient = inferenceServiceClient,
                deviceProfileProvider = deviceProfileProvider,
            ),
            onKeyUsed = { id ->
                try {
                    apiKeyRepository.markUsed(id)
                } catch (_: Throwable) {
                    // Usage telemetry must not turn an authorized request into a failed request.
                }
            },
        )
        return try {
            apiServer.start()
            synchronized(this) {
                server = apiServer
                val running = LocalApiState.Running("127.0.0.1", port)
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

private class IsolatedRuntimeApiGateway(
    private val inferenceServiceClient: InferenceServiceClient,
    private val deviceProfileProvider: () -> dev.androml.core.model.DeviceProfile,
) : ApiInferenceGateway {
    override fun streamChat(request: ChatCompletionRequest): Flow<ChatDelta> = flow {
        val device = deviceProfileProvider()
        val session = inferenceServiceClient.openSession(
            model = ModelRequirements(
                workload = ModelWorkload.TextGeneration,
                weightBytes = 1L,
                contextTokens = 2048,
            ),
            configuration = RuntimeConfiguration(
                cpuThreads = device.cpuCoreCount.coerceIn(1, 8),
                contextTokens = 2048,
                useAcceleration = false,
            ),
        )
        try {
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
            inferenceServiceClient.closeSession(session)
        }
    }
}
