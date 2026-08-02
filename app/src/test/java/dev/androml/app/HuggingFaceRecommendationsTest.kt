package dev.androml.app

import dev.androml.core.model.DeviceProfile
import dev.androml.core.model.HuggingFaceSearchHit
import dev.androml.core.model.ThermalStatus
import dev.androml.core.model.ModelWorkload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HuggingFaceRecommendationsTest {
    @Test
    fun lowMemoryArm64DeviceGetsSmallTextRecommendations() {
        System.setProperty("androml.runtime.llamacpp.bundled", "true")
        val queries = try {
            deviceRecommendationQueries(device(memoryGiB = 2, abis = listOf("arm64-v8a")))
        } finally {
            System.clearProperty("androml.runtime.llamacpp.bundled")
        }

        val text = queries.single { it.workload == ModelWorkload.TextGeneration }

        assertEquals("0.5B", text.search)
        assertEquals("text-generation", text.pipelineTag)
        assertEquals("gguf", text.filter)
    }

    @Test
    fun x86DeviceDoesNotSeeArmOnlyTextGenerationRecommendations() {
        val queries = deviceRecommendationQueries(device(memoryGiB = 8, abis = listOf("x86_64")))

        assertFalse(queries.any { it.workload == ModelWorkload.TextGeneration })
        assertTrue(queries.any { it.workload == ModelWorkload.TextEmbedding })
    }

    @Test
    fun rankingRemovesObviousOversizedModelsAndDuplicates() {
        val device = device(memoryGiB = 2, abis = listOf("arm64-v8a"))
        val small = hit(
            modelId = "demo/Qwen-0.5B-GGUF",
            downloads = 20,
            paths = listOf("Qwen-0.5B-Q4.gguf"),
        )
        val large = hit(
            modelId = "demo/Qwen-30B-GGUF",
            downloads = 200,
            paths = listOf("Qwen-30B-Q4.gguf"),
        )

        val ranked = rankDeviceRecommendations(listOf(large, small, small), device)

        assertEquals(listOf(small.modelId), ranked.map { it.modelId })
    }

    private fun device(memoryGiB: Int, abis: List<String>): DeviceProfile = DeviceProfile(
        manufacturer = "Test",
        model = "Phone",
        androidApi = 35,
        supportedAbis = abis,
        cpuCoreCount = 8,
        totalMemoryBytes = memoryGiB * GiB,
        availableMemoryBytes = memoryGiB * GiB,
        availableStorageBytes = 32L * GiB,
        isCharging = true,
        thermalStatus = ThermalStatus.Nominal,
        hasVulkan = true,
    )

    private fun hit(modelId: String, downloads: Long, paths: List<String>) = HuggingFaceSearchHit(
        modelId = modelId,
        revision = "0123456789abcdef0123456789abcdef01234567",
        pipelineTag = "text-generation",
        downloads = downloads,
        likes = 1,
        tags = listOf("gguf"),
        filePaths = paths,
    )

    private companion object {
        const val GiB = 1024L * 1024L * 1024L
    }
}
