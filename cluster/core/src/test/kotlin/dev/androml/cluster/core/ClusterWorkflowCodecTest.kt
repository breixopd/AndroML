package dev.androml.cluster.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ClusterWorkflowCodecTest {
    @Test
    fun stageTaskAndResultRoundTrip() {
        val task = ClusterWorkflowStageTask(
            stageKind = "model",
            stageKey = "demo-model",
            modelHash = ContentHash.parse("a".repeat(64)),
            inputPayload = "{\"type\":\"text\",\"value\":\"hello\"}",
        )
        val result = ClusterWorkflowStageResult(
            outputPayload = "{\"type\":\"text\",\"value\":\"world\"}",
        )

        assertEquals(task, ClusterWorkflowCodec.decodeTask(ClusterWorkflowCodec.encodeTask(task)))
        assertEquals(result, ClusterWorkflowCodec.decodeResult(ClusterWorkflowCodec.encodeResult(result)))
    }

    @Test
    fun codecRejectsUnexpectedKindAndOversizedValue() {
        assertThrows(IllegalArgumentException::class.java) {
            ClusterWorkflowCodec.decodeTask("{\"kind\":\"rag_search_task\"}".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClusterWorkflowStageTask(
                stageKind = "model",
                stageKey = "demo",
                modelHash = null,
                inputPayload = "x".repeat(ClusterWorkflowStageTask.MAX_INPUT_CHARS + 1),
            )
        }
    }
}
