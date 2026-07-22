package dev.androml.runtime.litert

import dev.androml.core.model.ModelRequirements
import dev.androml.core.model.ModelWorkload
import dev.androml.runtime.api.RuntimeIncompatibilityReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtRuntimeAdapterTest {
    @Test
    fun descriptorExposesBundledTextEmbeddingWorkload() {
        assertEquals("litert", LiteRtRuntimeDescriptor.value.id.value)
        assertTrue(ModelWorkload.TextEmbedding in LiteRtRuntimeDescriptor.value.workloads)
        assertTrue(ModelWorkload.ImageClassification in LiteRtRuntimeDescriptor.value.workloads)
        assertTrue(ModelWorkload.AudioClassification in LiteRtRuntimeDescriptor.value.workloads)
    }

    @Test
    fun missingArtifactIsRejectedBeforeNativeConstruction() {
        val report = LiteRtRuntimeAdapter("/does/not/exist/model.tflite").inspect(
            ModelRequirements(
                workload = ModelWorkload.TextEmbedding,
                weightBytes = 1L,
                contextTokens = 128,
            ),
        )
        assertTrue(RuntimeIncompatibilityReason.RuntimeUnavailable in report.reasons)
    }
}
