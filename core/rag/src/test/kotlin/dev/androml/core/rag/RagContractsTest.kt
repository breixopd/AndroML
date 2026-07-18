package dev.androml.core.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagContractsTest {
    private val collection = CollectionId.parse("research")

    @Test
    fun chunkingIsStableAndOverlapsWithoutLosingSourceOffsets() {
        val document = RagDocument(
            collectionId = collection,
            title = "Guide",
            sourceLabel = "guide.md",
            text = buildString {
                append("Paragraph one has enough words to create a chunk and keep its source location. ".repeat(3))
                append("\n\n")
                append("Paragraph two repeats a boundary and keeps its source location. ".repeat(3))
            },
        )
        val options = ChunkingOptions(maxCharacters = 128, overlapCharacters = 16, minCharacters = 32)
        val first = DeterministicChunker(options).chunk(document)
        val second = DeterministicChunker(options).chunk(document)

        assertTrue(first.size >= 2)
        assertEquals(first.map { it.id }, second.map { it.id })
        assertTrue(first.all { it.span.endOffset <= document.normalizedText.length })
        assertTrue(first.drop(1).zip(first.dropLast(1)).any { (current, previous) ->
            current.text.firstOrNull()?.let(previous.text::contains) == true
        })
    }

    @Test
    fun hybridRetrievalProducesDiverseCitationsAndStableTieBreaks() {
        val documents = listOf(
            RagDocument(collection, "A", "a.md", "Android inference uses a runtime process boundary."),
            RagDocument(collection, "B", "b.md", "Android inference chooses a backend after profiling."),
        )
        val chunks = documents.flatMap { DeterministicChunker(ChunkingOptions(maxCharacters = 256)).chunk(it) }
        val results = HybridRetriever().retrieve(
            query = RetrievalQuery("Android inference", topK = 2, maxPerDocument = 1),
            chunks = chunks,
        )

        assertEquals(2, results.size)
        assertEquals(setOf("A", "B"), results.map { it.chunk.title }.toSet())
        assertTrue(results.all { it.citation.excerptHash.length == 64 })
        assertTrue(results.zipWithNext().all { (left, right) ->
            left.citation.fusedScore >= right.citation.fusedScore
        })
    }

    @Test
    fun semanticScoresParticipateWithoutAllowingNaNToPoisonRanking() {
        val document = RagDocument(collection, "A", "a.md", "retrieval signal")
        val chunk = DeterministicChunker().chunk(document).single()
        val results = HybridRetriever().retrieve(
            query = RetrievalQuery("unrelated", lexicalWeight = 0.1, semanticWeight = 0.9),
            chunks = listOf(chunk),
            semanticScores = mapOf(chunk.id to Double.NaN),
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun contextPackingHonorsBudgetAndReportsOmissions() {
        val document = RagDocument(
            collection,
            "A",
            "a.md",
            "one two three four five six seven eight nine ten ".repeat(12),
        )
        val chunks = DeterministicChunker(ChunkingOptions(maxCharacters = 128, overlapCharacters = 0, minCharacters = 32))
            .chunk(document)
        val results = HybridRetriever().retrieve(RetrievalQuery("one two", contextCharacterBudget = 256), chunks)
        val packed = HybridRetriever().packContext(
            RetrievalQuery("one two", contextCharacterBudget = 256),
            results,
        )

        assertTrue(packed.characterCount <= 256)
        assertEquals(results.size - packed.results.size, packed.omittedResults)
        assertEquals(packed.results.map { it.citation }, packed.citations)
    }
}
