package dev.androml.runtime.executorch

import dev.androml.core.model.ModelRequirements
import dev.androml.core.model.ModelWorkload
import dev.androml.runtime.api.RuntimeIncompatibilityReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecuTorchRuntimeAdapterTest {
    @Test
    fun descriptorExposesPteTextEmbeddingWorkload() {
        assertEquals("executorch", ExecuTorchRuntimeDescriptor.value.id.value)
        assertTrue(ModelWorkload.TextEmbedding in ExecuTorchRuntimeDescriptor.value.workloads)
        assertTrue(ModelWorkload.ImageClassification in ExecuTorchRuntimeDescriptor.value.workloads)
        assertTrue(ModelWorkload.AudioClassification in ExecuTorchRuntimeDescriptor.value.workloads)
    }

    @Test
    fun missingPteArtifactIsRejectedBeforeNativeConstruction() {
        val report = ExecuTorchRuntimeAdapter("/does/not/exist/model.pte").inspect(
            ModelRequirements(ModelWorkload.TextEmbedding, weightBytes = 1L, contextTokens = 128),
        )
        assertTrue(RuntimeIncompatibilityReason.RuntimeUnavailable in report.reasons)
    }
}
