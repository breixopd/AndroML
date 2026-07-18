package dev.androml.core.database

import dev.androml.core.rag.ChunkingOptions
import dev.androml.core.rag.DeterministicChunker
import dev.androml.core.rag.RagDocument
import dev.androml.core.rag.CollectionId
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
}
