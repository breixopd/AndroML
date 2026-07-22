package dev.androml.core.rag

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Small, deterministic on-device vectorizer used when no embedding runtime is installed.
 * It is intentionally content-only and model-free, so indexing never executes downloaded
 * code. A future bundled embedding pack can replace this provider without changing IDs.
 */
object LocalHashEmbedding {
    const val DIMENSION = 64
    const val MODEL_KEY = "local-hash-64-v1"

    fun embed(text: String): FloatArray {
        val vector = FloatArray(DIMENSION)
        val tokens = TextNormalizer.tokens(text)
        tokens.forEach { token ->
            val hash = token.hashCode()
            val index = (hash and Int.MAX_VALUE) % DIMENSION
            vector[index] += 1f
            if (token.length > 2) {
                val trigram = token.windowed(3, 1, partialWindows = false)
                trigram.forEach { part ->
                    val ngramIndex = (part.hashCode() and Int.MAX_VALUE) % DIMENSION
                    vector[ngramIndex] += 0.25f
                }
            }
        }
        val norm = sqrt(vector.sumOf { it.toDouble() * it.toDouble() }).toFloat()
        if (norm > 0f) vector.indices.forEach { index -> vector[index] /= norm }
        return vector
    }

    fun cosine(left: FloatArray, right: FloatArray): Double {
        require(left.size == right.size) { "embedding dimensions do not match" }
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        left.indices.forEach { index ->
            dot += left[index] * right[index]
            leftNorm += left[index] * left[index]
            rightNorm += right[index] * right[index]
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) return 0.0
        return (dot / sqrt(leftNorm * rightNorm)).coerceIn(0.0, 1.0)
    }

    fun encode(vector: FloatArray): ByteArray = ByteBuffer
        .allocate(vector.size * Float.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply { vector.forEach(::putFloat) }
        .array()

    fun decode(bytes: ByteArray, dimension: Int): FloatArray {
        require(dimension in 1..1024) { "embedding dimension is out of bounds" }
        require(bytes.size == dimension * Float.SIZE_BYTES) { "embedding payload size is invalid" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dimension) { buffer.float }
    }
}

/** Embedding backend used by persistent RAG indexing and retrieval. */
interface RagEmbeddingProvider {
    val modelKey: String
    /** Null means the backend determines the dimension at inference time. */
    val dimension: Int?
    val available: Boolean

    suspend fun embed(text: String): FloatArray
}

/** Safe deterministic fallback when no verified embedding model is installed. */
object LocalHashEmbeddingProvider : RagEmbeddingProvider {
    override val modelKey: String = LocalHashEmbedding.MODEL_KEY
    override val dimension: Int = LocalHashEmbedding.DIMENSION
    override val available: Boolean = true

    override suspend fun embed(text: String): FloatArray = LocalHashEmbedding.embed(text)
}
