package dev.androml.core.database

import dev.androml.core.rag.ChunkId
import dev.androml.core.rag.CollectionId
import dev.androml.core.rag.DocumentId
import dev.androml.core.rag.RagDocument
import dev.androml.core.rag.TextChunk
import dev.androml.core.rag.TextNormalizer
import dev.androml.core.rag.SourceSpan
import kotlinx.coroutines.flow.Flow

class RagRepository(
    private val dao: RagDao,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) {
    fun observeCollections(): Flow<List<RagCollectionEntity>> = dao.observeCollections()

    fun observeDocuments(collectionId: CollectionId): Flow<List<RagDocumentEntity>> =
        dao.observeDocuments(collectionId.value)

    suspend fun upsertCollection(
        collectionId: CollectionId,
        displayName: String,
        embeddingModelKey: String?,
        createdAtEpochMillis: Long = nowEpochMillis(),
    ) {
        require(displayName.isNotBlank() && displayName.length <= 256) { "collection display name is invalid" }
        require(embeddingModelKey == null || embeddingModelKey.length <= 512) {
            "embedding model key is too long"
        }
        dao.upsertCollection(
            RagCollectionEntity(
                collectionId = collectionId.value,
                displayName = displayName,
                embeddingModelKey = embeddingModelKey,
                createdAtEpochMillis = createdAtEpochMillis,
                updatedAtEpochMillis = nowEpochMillis(),
            ),
        )
    }

    suspend fun replaceDocument(
        document: RagDocument,
        chunks: List<TextChunk>,
        contentArtifactSha256: String,
        byteSize: Long,
    ) {
        dao.replaceDocument(
            RagCatalogMapper.map(
                document = document,
                chunks = chunks,
                contentArtifactSha256 = contentArtifactSha256,
                byteSize = byteSize,
                observedAtEpochMillis = nowEpochMillis(),
            ),
        )
    }

    suspend fun search(
        collectionId: CollectionId,
        query: String,
        limit: Int = 32,
    ): List<TextChunk> {
        require(limit in 1..100) { "RAG search limit is out of bounds" }
        val terms = TextNormalizer.tokens(query).distinct().take(64)
        if (terms.isEmpty()) return emptyList()
        val matchQuery = terms.joinToString(" OR ") { term -> "\"${term.replace("\"", "\"\"")}\"" }
        return dao.search(collectionId.value, matchQuery, limit).map { row ->
            TextChunk(
                id = ChunkId.parse(row.chunkId),
                documentId = DocumentId.parse(row.documentId),
                collectionId = CollectionId.parse(row.collectionId),
                title = row.title,
                sourceLabel = row.sourceLabel,
                text = row.text,
                span = SourceSpan(
                    startOffset = row.startOffset,
                    endOffset = row.endOffset,
                    page = row.page,
                    section = row.section,
                ),
                ordinal = row.ordinal,
            )
        }
    }
}
