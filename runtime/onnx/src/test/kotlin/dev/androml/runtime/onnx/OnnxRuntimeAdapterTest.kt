package dev.androml.runtime.onnx

import dev.androml.core.model.ModelRequirements
import dev.androml.core.model.ModelWorkload
import dev.androml.runtime.api.RuntimeIncompatibilityReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnnxRuntimeAdapterTest {
    @Test
    fun descriptorIsBundledForEmbeddingWorkloadsOnly() {
        assertEquals("onnxruntime", OnnxRuntimeDescriptor.value.id.value)
        assertTrue(ModelWorkload.TextEmbedding in OnnxRuntimeDescriptor.value.workloads)
        assertTrue(ModelWorkload.ImageClassification in OnnxRuntimeDescriptor.value.workloads)
        assertTrue(ModelWorkload.AudioClassification in OnnxRuntimeDescriptor.value.workloads)
        assertFalse(ModelWorkload.TextGeneration in OnnxRuntimeDescriptor.value.workloads)
    }

    @Test
    fun inspectRejectsMissingModelBeforeNativeSessionCreation() {
        val report = OnnxRuntimeAdapter("/does/not/exist/model.onnx").inspect(
            ModelRequirements(
                workload = ModelWorkload.TextEmbedding,
                weightBytes = 0L,
                contextTokens = 128,
            ),
        )
        assertFalse(report.compatible)
        assertTrue(RuntimeIncompatibilityReason.RuntimeUnavailable in report.reasons)
    }
}
