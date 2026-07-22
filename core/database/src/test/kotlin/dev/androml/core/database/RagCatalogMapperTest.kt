package dev.androml.core.database

import dev.androml.core.rag.ChunkingOptions
import dev.androml.core.rag.DeterministicChunker
import dev.androml.core.rag.RagDocument
import dev.androml.core.rag.CollectionId
import dev.androml.core.rag.LocalHashEmbedding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagCatalogMapperTest {
    @Test
    fun mapsRevisionStableDocumentAndSearchRows() {
        val document = RagDocument(
            collectionId = CollectionId.parse("research"),
            title = "Notes",
            sourceLabel = "content://notes/1",
            text = "A sufficiently long research note about local inference. ".repeat(8),
        )
        val chunks = DeterministicChunker(
            ChunkingOptions(maxCharacters = 128, overlapCharacters = 16, minCharacters = 32),
        ).chunk(document)

        val snapshot = RagCatalogMapper.map(
            document = document,
            chunks = chunks,
            contentArtifactSha256 = "a".repeat(64),
            byteSize = 512L,
            observedAtEpochMillis = 42L,
        )

        assertEquals(document.id.value, snapshot.document.documentId)
        assertEquals(document.contentHash, snapshot.document.contentSha256)
        assertEquals("a".repeat(64), snapshot.document.contentArtifactSha256)
        assertEquals(chunks.size, snapshot.chunks.size)
        assertEquals(chunks.size, snapshot.searchRows.size)
        assertTrue(snapshot.chunks.zipWithNext().all { (a, b) -> a.ordinal < b.ordinal })
        assertTrue(snapshot.searchRows.all { it.text.isNotBlank() })
    }

    @Test
    fun acceptsVerifiedProviderVectorsWithoutRewritingThemToHashFallback() {
        val document = RagDocument(
            collectionId = CollectionId.parse("provider"),
            title = "Provider",
            sourceLabel = "content://provider/1",
            text = "A provider-backed embedding test document. ".repeat(4),
        )
        val chunks = DeterministicChunker().chunk(document)
        val vector = FloatArray(3) { (it + 1).toFloat() }
        val rows = chunks.map { chunk ->
            RagVectorEntity(
                collectionId = chunk.collectionId.value,
                documentId = chunk.documentId.value,
                chunkId = chunk.id.value,
                modelKey = "onnx:test-model",
                dimension = vector.size,
                vector = LocalHashEmbedding.encode(vector),
                updatedAtEpochMillis = 1L,
            )
        }
        val snapshot = RagCatalogMapper.mapWithVectors(
            document = document,
            chunks = chunks,
            contentArtifactSha256 = "b".repeat(64),
            byteSize = 64L,
            observedAtEpochMillis = 1L,
            vectors = rows,
        )

        assertEquals("onnx:test-model", snapshot.vectors.first().modelKey)
        assertEquals(vector.size, snapshot.vectors.first().dimension)
    }
}
