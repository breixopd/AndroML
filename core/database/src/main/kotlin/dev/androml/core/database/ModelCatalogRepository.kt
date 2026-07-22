package dev.androml.core.database

import dev.androml.cluster.core.ContentHash
import dev.androml.core.model.HuggingFaceModelReference
import dev.androml.core.model.HuggingFaceRepositoryMetadata
import dev.androml.core.model.ModelFormatClassifier
import dev.androml.core.model.ModelWorkload
import kotlinx.coroutines.flow.Flow

class ModelCatalogRepository(
    private val dao: ModelCatalogDao,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) {
    fun observeModels(): Flow<List<ModelRecordEntity>> = dao.observeModels()

    suspend fun snapshotModels(): List<ModelRecordEntity> = dao.listModels()

    suspend fun fileForArtifact(artifactSha256: String): ModelFileEntity? {
        require(artifactSha256.matches(Regex("[a-f0-9]{64}"))) { "artifact hash must be SHA-256" }
        return dao.fileForArtifact(artifactSha256)
    }

    suspend fun fileForModelKey(modelKey: String): ModelFileEntity? {
        require(modelKey.isNotBlank() && modelKey.length <= 512) { "model key is invalid" }
        return if (modelKey.matches(Regex("[a-f0-9]{64}"))) {
            fileForArtifact(modelKey)
        } else {
            dao.fileForModelKey(modelKey)
        }
    }

    suspend fun filesForModelKey(modelKey: String): List<ModelFileEntity> {
        require(modelKey.isNotBlank() && modelKey.length <= 512) { "model key is invalid" }
        return if (modelKey.matches(Regex("[a-f0-9]{64}"))) {
            fileForArtifact(modelKey)?.let(::listOf).orEmpty()
        } else {
            dao.filesForModelKey(modelKey)
        }
    }

    suspend fun installedArtifactHashes(): Set<ContentHash> = dao.listVerifiedArtifactHashes()
        .map(ContentHash::parse)
        .toSet()

    suspend fun firstVerifiedArtifactFor(workload: ModelWorkload): ContentHash? = dao.listVerifiedFiles()
        .firstOrNull { ModelFormatClassifier.supports(it.path, workload) }
        ?.artifactSha256
        ?.let(ContentHash::parse)

    suspend fun runnableModelKeys(): List<String> = dao.listRunnableModelKeys()

    fun observeAllFiles(): Flow<List<ModelFileEntity>> = dao.observeAllFiles()

    fun observeFiles(reference: HuggingFaceModelReference): Flow<List<ModelFileEntity>> =
        dao.observeFiles(reference.modelId.value, reference.revision.value)

    suspend fun saveMetadata(metadata: HuggingFaceRepositoryMetadata) {
        dao.replaceRepository(
            ModelCatalogMapper.map(
                metadata = metadata,
                observedAtEpochMillis = nowEpochMillis(),
            ),
        )
    }

    suspend fun markArtifactVerified(
        reference: HuggingFaceModelReference,
        path: String,
        artifactSha256: String,
    ) {
        val updatedRows = dao.markArtifactVerified(
            modelId = reference.modelId.value,
            revision = reference.revision.value,
            path = path,
            artifactSha256 = artifactSha256,
            downloadedAtEpochMillis = nowEpochMillis(),
        )
        check(updatedRows == 1) {
            "verified artifact is not registered in the model catalog"
        }
    }
}
