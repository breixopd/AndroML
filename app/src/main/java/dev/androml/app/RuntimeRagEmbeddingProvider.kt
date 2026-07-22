package dev.androml.app

import android.os.ParcelFileDescriptor
import dev.androml.core.database.ModelCatalogRepository
import dev.androml.core.files.FileArtifactStore
import dev.androml.core.model.ModelRequirements
import dev.androml.core.model.ModelWorkload
import dev.androml.core.model.ModelFormatClassifier
import dev.androml.core.rag.LocalHashEmbedding
import dev.androml.core.rag.RagEmbeddingProvider
import dev.androml.runtime.api.InferenceEvent
import dev.androml.runtime.api.InferenceRequest
import dev.androml.runtime.api.InferenceRequestId
import dev.androml.runtime.api.RuntimeConfiguration
import dev.androml.runtime.api.RuntimeId
import dev.androml.runtime.api.RuntimePackCatalog
import dev.androml.runtime.service.InferenceServiceClient
import dev.androml.runtime.service.OpenedInferenceSession
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect

/**
 * Uses the first verified, bundled text-embedding artifact for RAG. A runtime failure falls back
 * to the deterministic local vectorizer and is intentionally isolated to this indexing request.
 */
class RuntimeRagEmbeddingProvider(
    private val catalogRepository: ModelCatalogRepository,
    private val artifactStore: FileArtifactStore,
    private val inferenceServiceClient: InferenceServiceClient,
    private val deviceProfileProvider: () -> dev.androml.core.model.DeviceProfile,
) : RagEmbeddingProvider {
    @Volatile
    private var selectedModelKey: String = "runtime-auto-text-embedding-v1"
    override val modelKey: String get() = selectedModelKey
    override val dimension: Int? = null
    override val available: Boolean = true
    private val requestMutex = Mutex()
    private var runtimeEnabled: Boolean? = null

    override suspend fun embed(text: String): FloatArray = requestMutex.withLock {
        if (runtimeEnabled == false) return@withLock LocalHashEmbedding.embed(text)
        runCatching { embedWithRuntime(text) }.fold(
            onSuccess = {
                runtimeEnabled = true
                selectedModelKey = "runtime-auto-text-embedding-v1"
                it
            },
            onFailure = {
                if (it is CancellationException) throw it
                runtimeEnabled = false
                selectedModelKey = LocalHashEmbedding.MODEL_KEY
                LocalHashEmbedding.embed(text)
            },
        )
    }

    private suspend fun embedWithRuntime(text: String): FloatArray = withContext(Dispatchers.IO) {
        val artifactHash = catalogRepository.firstVerifiedArtifactFor(ModelWorkload.TextEmbedding)
            ?: error("no verified text-embedding artifact is installed")
        val modelFile = catalogRepository.fileForArtifact(artifactHash.value)
            ?: error("verified embedding metadata is missing")
        val runtimeName = ModelFormatClassifier.forPath(modelFile.path)?.runtimeId
            ?: error("verified embedding format is unsupported")
        val runtimeId = RuntimeId.parse(runtimeName)
        check(RuntimePackCatalog.find(runtimeId)?.usable == true) { "embedding runtime is not bundled" }
        check(artifactStore.contains(artifactHash.value)) { "embedding artifact is missing" }
        val descriptor = ParcelFileDescriptor.open(
            artifactStore.fileFor(artifactHash.value),
            ParcelFileDescriptor.MODE_READ_ONLY,
        )
        var session: OpenedInferenceSession? = null
        try {
            val device = deviceProfileProvider()
            session = inferenceServiceClient.openSession(
                model = ModelRequirements(
                    workload = ModelWorkload.TextEmbedding,
                    weightBytes = modelFile.sizeBytes,
                    contextTokens = 512,
                ),
                configuration = RuntimeConfiguration(
                    cpuThreads = device.cpuCoreCount.coerceIn(1, 8),
                    contextTokens = 512,
                    useAcceleration = false,
                ),
                runtimeId = runtimeId,
                modelFile = descriptor,
            )
            val values = mutableListOf<Float>()
            inferenceServiceClient.stream(
                session,
                InferenceRequest(
                    id = InferenceRequestId.parse("rag-${UUID.randomUUID()}"),
                    prompt = text,
                    maxNewTokens = 1,
                    temperature = 0.0,
                ),
            ).collect { event ->
                when (event) {
                    is InferenceEvent.Token -> values += parseVector(event.text).toList()
                    is InferenceEvent.Failed -> error(event.safeMessage)
                    is InferenceEvent.Cancelled -> error("embedding request was cancelled")
                    is InferenceEvent.Started, is InferenceEvent.Completed -> Unit
                }
            }
            require(values.isNotEmpty() && values.size <= 4096) { "embedding runtime returned no bounded vector" }
            require(values.all { it.isFinite() }) { "embedding runtime returned non-finite values" }
            values.toFloatArray()
        } finally {
            session?.let(inferenceServiceClient::closeSession)
            descriptor.close()
        }
    }

    private companion object {
        fun parseVector(raw: String): FloatArray = raw
            .trim()
            .removePrefix("[")
            .removeSuffix("]")
            .split(',')
            .mapNotNull { it.trim().toFloatOrNull() }
            .toFloatArray()
    }
}
