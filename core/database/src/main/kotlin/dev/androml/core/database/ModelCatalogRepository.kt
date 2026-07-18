package dev.androml.core.database

import dev.androml.core.model.HuggingFaceModelReference
import dev.androml.core.model.HuggingFaceRepositoryMetadata
import kotlinx.coroutines.flow.Flow

class ModelCatalogRepository(
    private val dao: ModelCatalogDao,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) {
    fun observeModels(): Flow<List<ModelRecordEntity>> = dao.observeModels()

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
