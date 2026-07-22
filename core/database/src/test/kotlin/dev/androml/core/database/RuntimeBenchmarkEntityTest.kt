package dev.androml.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeBenchmarkEntityTest {
    @Test
    fun acceptsAStableMeasurementKey() {
        val entity = RuntimeBenchmarkEntity(
            deviceKey = "google|pixel|37|arm64-v8a",
            runtimeId = "litertlm",
            modelArtifactSha256 = "a".repeat(64),
            profile = "Balanced",
            tokensPerSecond = 18.2,
            firstTokenLatencyMs = 420.0,
            outputValid = true,
            failureCount = 0,
            measuredAtEpochMillis = 1L,
        )
        assertEquals("litertlm", entity.runtimeId)
    }

    @Test
    fun rejectsMalformedArtifactKey() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeBenchmarkEntity(
                deviceKey = "device",
                runtimeId = "litertlm",
                modelArtifactSha256 = "not-a-hash",
                profile = "Balanced",
                tokensPerSecond = 1.0,
                firstTokenLatencyMs = null,
                outputValid = true,
                failureCount = 0,
                measuredAtEpochMillis = 1L,
            )
        }
    }
}
