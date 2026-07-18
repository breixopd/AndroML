package dev.androml.cluster.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ClusterInferenceCodecTest {
    @Test
    fun inferenceTaskAndResultRoundTripThroughBoundedPayloads() {
        val task = ClusterInferenceTask(
            modelHash = ContentHash.parse("a".repeat(64)),
            prompt = "hello from the cluster",
            maxNewTokens = 64,
            temperature = 0.4,
            stopSequences = listOf("<stop>"),
            contextTokens = 2_048,
            kvCacheBytesPerToken = 512L,
            cpuThreads = 4,
            useAcceleration = false,
            runtimeId = "litertlm",
        )
        val result = ClusterInferenceResult(
            text = "hello back",
            generatedTokens = 3,
            runtimeId = "litertlm",
        )

        assertEquals(task, ClusterInferenceCodec.decodeTask(ClusterInferenceCodec.encodeTask(task)))
        assertEquals(result, ClusterInferenceCodec.decodeResult(ClusterInferenceCodec.encodeResult(result)))
    }

    @Test
    fun inferenceCodecRejectsAnUnexpectedPayloadKind() {
        val raw = "{\"kind\":\"rag_search\"}".toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            ClusterInferenceCodec.decodeTask(raw)
        }
    }
}
