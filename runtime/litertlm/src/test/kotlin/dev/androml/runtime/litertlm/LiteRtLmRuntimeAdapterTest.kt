package dev.androml.runtime.litertlm

import dev.androml.core.model.ModelRequirements
import dev.androml.core.model.ModelWorkload
import dev.androml.runtime.api.RuntimeIncompatibilityReason
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtLmRuntimeAdapterTest {
    @Test
    fun descriptorIsTruthfulAboutCurrentCpuOnlySupport() {
        assertEquals("litertlm", LiteRtLmRuntimeDescriptor.value.id.value)
        assertEquals(setOf(ModelWorkload.TextGeneration), LiteRtLmRuntimeDescriptor.value.workloads)
        assertEquals("Cpu", LiteRtLmRuntimeDescriptor.value.acceleration.name)
        assertTrue(LiteRtLmRuntimeDescriptor.value.supportedAbis.contains("arm64-v8a"))
    }

    @Test
    fun missingArtifactIsRejectedBeforeEngineInitialization() {
        val adapter = LiteRtLmRuntimeAdapter("/does/not/exist/model.litertlm")
        val report = adapter.inspect(
            ModelRequirements(
                workload = ModelWorkload.TextGeneration,
                weightBytes = 10L,
                contextTokens = 2048,
            ),
        )

        assertFalse(report.compatible)
        assertTrue(RuntimeIncompatibilityReason.RuntimeUnavailable in report.reasons)
    }

    @Test
    fun artifactSizeMismatchIsRejectedBeforeEngineInitialization() {
        val file = Files.createTempFile("androml-litertlm", ".model").toFile()
        try {
            file.writeBytes(ByteArray(8))
            val report = LiteRtLmRuntimeAdapter(file.path).inspect(
                ModelRequirements(
                    workload = ModelWorkload.TextGeneration,
                    weightBytes = 9L,
                    contextTokens = 2048,
                ),
            )
            assertFalse(report.compatible)
            assertTrue(RuntimeIncompatibilityReason.RuntimeUnavailable in report.reasons)
        } finally {
            file.delete()
        }
    }
}
