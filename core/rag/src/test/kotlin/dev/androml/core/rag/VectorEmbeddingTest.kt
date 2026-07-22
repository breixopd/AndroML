package dev.androml.core.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorEmbeddingTest {
    @Test
    fun embeddingsAreDeterministicAndRoundTrip() {
        val vector = LocalHashEmbedding.embed("Android local inference")
        val decoded = LocalHashEmbedding.decode(
            LocalHashEmbedding.encode(vector),
            LocalHashEmbedding.DIMENSION,
        )
        assertEquals(LocalHashEmbedding.DIMENSION, vector.size)
        assertEquals(1.0, LocalHashEmbedding.cosine(vector, decoded), 0.000001)
        assertTrue(LocalHashEmbedding.cosine(vector, LocalHashEmbedding.embed("Android local inference")) > 0.99)
    }
}
