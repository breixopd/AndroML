package dev.androml.core.database

import dev.androml.core.model.HuggingFaceRepositoryMetadata

data class ModelCatalogSnapshot(
    val model: ModelRecordEntity,
    val files: List<ModelFileEntity>,
)

object ModelCatalogMapper {
    fun map(
        metadata: HuggingFaceRepositoryMetadata,
        observedAtEpochMillis: Long,
    ): ModelCatalogSnapshot {
        val modelId = metadata.reference.modelId.value
        val revision = metadata.reference.revision.value
        return ModelCatalogSnapshot(
            model = ModelRecordEntity(
                modelId = modelId,
                revision = revision,
                isPrivate = metadata.isPrivate,
                isGated = metadata.isGated,
                license = metadata.license,
                observedAtEpochMillis = observedAtEpochMillis,
            ),
            files = metadata.files
                .sortedBy { it.path }
                .map { descriptor ->
                    ModelFileEntity(
                        modelId = modelId,
                        revision = revision,
                        path = descriptor.path,
                        sizeBytes = descriptor.sizeBytes,
                        sha256 = descriptor.sha256,
                    )
                },
        )
    }
}
