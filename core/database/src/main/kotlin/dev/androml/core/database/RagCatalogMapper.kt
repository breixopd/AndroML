package dev.androml.core.database

import dev.androml.core.rag.RagDocument
import dev.androml.core.rag.LocalHashEmbedding
import dev.androml.core.rag.TextChunk

data class RagCatalogSnapshot(
    val document: RagDocumentEntity,
    val chunks: List<RagChunkEntity>,
    val searchRows: List<RagChunkSearchEntity>,
    val vectors: List<RagVectorEntity>,
)

object RagCatalogMapper {
    fun map(
        document: RagDocument,
        chunks: List<TextChunk>,
        contentArtifactSha256: String,
        byteSize: Long,
        observedAtEpochMillis: Long,
    ): RagCatalogSnapshot {
        require(contentArtifactSha256.matches(SHA256_PATTERN)) { "content artifact hash must be SHA-256" }
        require(byteSize >= 0L) { "content byte size must not be negative" }
        require(chunks.all { it.collectionId == document.collectionId && it.documentId == document.id }) {
            "all chunks must belong to the mapped document"
        }
        val mappedChunks = chunks.sortedBy(TextChunk::ordinal).map { chunk ->
            RagChunkEntity(
                collectionId = chunk.collectionId.value,
                documentId = chunk.documentId.value,
                chunkId = chunk.id.value,
                title = chunk.title,
                sourceLabel = chunk.sourceLabel,
                text = chunk.text,
                startOffset = chunk.span.startOffset,
                endOffset = chunk.span.endOffset,
                page = chunk.span.page,
                section = chunk.span.section,
                ordinal = chunk.ordinal,
            )
        }
        return RagCatalogSnapshot(
            document = RagDocumentEntity(
                collectionId = document.collectionId.value,
                documentId = document.id.value,
                title = document.title,
                sourceLabel = document.sourceLabel,
                contentSha256 = document.contentHash,
                contentArtifactSha256 = contentArtifactSha256,
                byteSize = byteSize,
                status = "ready",
                updatedAtEpochMillis = observedAtEpochMillis,
            ),
            chunks = mappedChunks,
            searchRows = mappedChunks.map { chunk ->
                RagChunkSearchEntity(
                    collectionId = chunk.collectionId,
                    documentId = chunk.documentId,
                    chunkId = chunk.chunkId,
                    title = chunk.title,
                    sourceLabel = chunk.sourceLabel,
                    text = chunk.text,
                    startOffset = chunk.startOffset,
                    endOffset = chunk.endOffset,
                    page = chunk.page,
                    section = chunk.section,
                    ordinal = chunk.ordinal,
                )
            },
            vectors = mappedChunks.map { chunk ->
                RagVectorEntity(
                    collectionId = chunk.collectionId,
                    documentId = chunk.documentId,
                    chunkId = chunk.chunkId,
                    modelKey = LocalHashEmbedding.MODEL_KEY,
                    dimension = LocalHashEmbedding.DIMENSION,
                    vector = LocalHashEmbedding.encode(LocalHashEmbedding.embed(chunk.text)),
                    updatedAtEpochMillis = observedAtEpochMillis,
                )
            },
        )
    }

    /** Replaces the fallback vectors with vectors computed by a verified embedding provider. */
    fun mapWithVectors(
        document: RagDocument,
        chunks: List<TextChunk>,
        contentArtifactSha256: String,
        byteSize: Long,
        observedAtEpochMillis: Long,
        vectors: List<RagVectorEntity>,
    ): RagCatalogSnapshot {
        val snapshot = map(
            document = document,
            chunks = chunks,
            contentArtifactSha256 = contentArtifactSha256,
            byteSize = byteSize,
            observedAtEpochMillis = observedAtEpochMillis,
        )
        require(
            vectors.size == snapshot.chunks.size &&
                vectors.map(RagVectorEntity::chunkId).toSet() == snapshot.chunks.map(RagChunkEntity::chunkId).toSet(),
        ) {
            "embedding vectors must cover every chunk exactly once"
        }
        require(vectors.all { it.dimension in 1..4096 && it.vector.size == it.dimension * Float.SIZE_BYTES }) {
            "embedding vector payload is invalid"
        }
        return snapshot.copy(vectors = vectors)
    }

    private val SHA256_PATTERN = Regex("[a-f0-9]{64}")
}
