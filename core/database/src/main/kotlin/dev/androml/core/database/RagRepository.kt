package dev.androml.core.database

import dev.androml.core.rag.ChunkId
import dev.androml.core.rag.CollectionId
import dev.androml.core.rag.DocumentId
import dev.androml.core.rag.RagDocument
import dev.androml.core.rag.TextChunk
import dev.androml.core.rag.SourceSpan
import dev.androml.core.rag.HybridRetriever
import dev.androml.core.rag.LocalHashEmbedding
import dev.androml.core.rag.LocalHashEmbeddingProvider
import dev.androml.core.rag.RagEmbeddingProvider
import dev.androml.core.rag.RetrievalQuery
import kotlinx.coroutines.flow.Flow

class RagRepository(
    private val dao: RagDao,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    private val embeddingProvider: RagEmbeddingProvider = LocalHashEmbeddingProvider,
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
        val provider = if (embeddingProvider.available) embeddingProvider else LocalHashEmbeddingProvider
        val vectors = chunks.map { chunk ->
            val values = provider.embed(chunk.text)
            validateVector(values, provider.dimension)
            RagVectorEntity(
                collectionId = chunk.collectionId.value,
                documentId = chunk.documentId.value,
                chunkId = chunk.id.value,
                modelKey = provider.modelKey,
                dimension = values.size,
                vector = LocalHashEmbedding.encode(values),
                updatedAtEpochMillis = nowEpochMillis(),
            )
        }
        dao.replaceDocument(
            RagCatalogMapper.mapWithVectors(
                document = document,
                chunks = chunks,
                contentArtifactSha256 = contentArtifactSha256,
                byteSize = byteSize,
                observedAtEpochMillis = nowEpochMillis(),
                vectors = vectors,
            ),
        )
    }

    suspend fun search(
        collectionId: CollectionId,
        query: String,
        limit: Int = 32,
    ): List<TextChunk> {
        require(limit in 1..100) { "RAG search limit is out of bounds" }
        val retrievalQuery = RetrievalQuery(text = query, topK = limit)
        val chunks = dao.chunksForCollection(collectionId.value, MAX_RETRIEVAL_CHUNKS).map(::toTextChunk)
        val provider = if (embeddingProvider.available) embeddingProvider else LocalHashEmbeddingProvider
        val storedVectors = dao.vectorsForCollection(collectionId.value, provider.modelKey)
        val vectors = (if (storedVectors.isEmpty() && provider.modelKey != LocalHashEmbedding.MODEL_KEY) {
            dao.vectorsForCollection(collectionId.value, LocalHashEmbedding.MODEL_KEY)
        } else storedVectors)
            .associate { vector ->
                ChunkId.parse(vector.chunkId) to LocalHashEmbedding.decode(vector.vector, vector.dimension)
            }
        val queryVector = if (provider.modelKey == LocalHashEmbedding.MODEL_KEY || storedVectors.isEmpty()) {
            LocalHashEmbedding.embed(query)
        } else {
            provider.embed(query).also { validateVector(it, provider.dimension) }
        }
        val semanticScores = vectors.mapValues { (_, vector) -> LocalHashEmbedding.cosine(queryVector, vector) }
        return HybridRetriever().retrieve(retrievalQuery, chunks, semanticScores)
            .take(limit)
            .map { it.chunk }
    }

    private fun toTextChunk(row: RagChunkEntity): TextChunk = TextChunk(
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

    private companion object {
        const val MAX_RETRIEVAL_CHUNKS = 10_000

        fun validateVector(values: FloatArray, expectedDimension: Int?) {
            require(values.isNotEmpty() && (expectedDimension == null || values.size == expectedDimension) && values.size <= 4096) {
                "embedding provider returned an invalid dimension"
            }
            require(values.all { it.isFinite() }) { "embedding provider returned non-finite values" }
        }
    }
}
