package dev.androml.cluster.core

import dev.androml.core.rag.Citation
import dev.androml.core.rag.ChunkId
import dev.androml.core.rag.CollectionId
import dev.androml.core.rag.DocumentId
import dev.androml.core.rag.RetrievalQuery
import dev.androml.core.rag.RetrievalResult
import dev.androml.core.rag.SourceSpan
import dev.androml.core.rag.TextChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ClusterRagCodecTest {
    @Test
    fun searchTaskAndResultsRoundTripWithCitationSpans() {
        val collection = CollectionId.parse("docs")
        val document = DocumentId.fromContent(collection, "a".repeat(64))
        val chunk = TextChunk(
            id = ChunkId.parse("b".repeat(64)),
            documentId = document,
            collectionId = collection,
            title = "Notes",
            sourceLabel = "file://notes.md",
            text = "cluster-safe retrieval",
            span = SourceSpan(10, 34, page = 2, section = "intro"),
            ordinal = 1,
        )
        val result = RetrievalResult(
            chunk = chunk,
            citation = Citation(
                documentId = document,
                title = chunk.title,
                sourceLabel = chunk.sourceLabel,
                span = chunk.span,
                excerptHash = "c".repeat(64),
                lexicalScore = 0.8,
                semanticScore = 0.2,
                fusedScore = 0.53,
            ),
        )
        val task = ClusterRagSearchTask(
            collectionId = collection,
            query = RetrievalQuery("retrieval", topK = 4),
        )

        assertEquals(task, ClusterRagCodec.decodeTask(ClusterRagCodec.encodeTask(task)))
        assertEquals(listOf(result), ClusterRagCodec.decodeResult(ClusterRagCodec.encodeResult(listOf(result))))
    }

    @Test
    fun ragCodecRejectsUnexpectedKindsAndOversizedQueries() {
        assertThrows(IllegalArgumentException::class.java) {
            ClusterRagCodec.decodeTask("{\"kind\":\"inference_task\"}".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClusterRagSearchTask(
                collectionId = CollectionId.parse("docs"),
                query = RetrievalQuery("x".repeat(16_385)),
            )
        }
    }
}
