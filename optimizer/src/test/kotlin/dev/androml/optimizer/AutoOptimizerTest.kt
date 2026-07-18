package dev.androml.optimizer

import dev.androml.core.model.DeviceProfile
import dev.androml.core.model.ModelRequirements
import dev.androml.core.model.ModelWorkload
import dev.androml.core.model.ThermalStatus
import dev.androml.runtime.api.AccelerationBackend
import dev.androml.runtime.api.RuntimeDescriptor
import dev.androml.runtime.api.RuntimeId
import dev.androml.runtime.api.RuntimeIncompatibilityReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoOptimizerTest {
    @Test
    fun measuredPerformanceCanOutrankDefaultBackendPreference() {
        val result = optimizer().select(
            device = device(),
            model = ModelRequirements(ModelWorkload.TextGeneration, weightBytes = 1.gibibytes),
            runtimes = listOf(
                runtime("cpu", AccelerationBackend.Cpu),
                runtime("gpu", AccelerationBackend.Gpu, requiresVulkan = true),
            ),
            benchmarks = listOf(
                BenchmarkObservation(RuntimeId.parse("cpu"), tokensPerSecond = 80.0),
                BenchmarkObservation(RuntimeId.parse("gpu"), tokensPerSecond = 20.0),
            ),
        )

        assertEquals("cpu", result.selected?.descriptor?.id?.value)
        assertTrue(result.candidates.all { it.compatibility.compatible })
    }

    @Test
    fun rejectsIncompatibleRuntimesWithStableReasonsAndSelectsFallback() {
        val result = optimizer().select(
            device = device(availableMemoryBytes = 2.gibibytes, hasVulkan = false),
            model = ModelRequirements(ModelWorkload.TextGeneration, weightBytes = 1.gibibytes),
            runtimes = listOf(
                runtime("npu", AccelerationBackend.Npu, minAndroidApi = 40, requiresVulkan = true),
                runtime("gpu", AccelerationBackend.Gpu, requiresVulkan = true),
                runtime("cpu", AccelerationBackend.Cpu),
            ),
        )

        val npu = result.candidates.first { it.descriptor.id.value == "npu" }
        val gpu = result.candidates.first { it.descriptor.id.value == "gpu" }
        assertEquals("cpu", result.selected?.descriptor?.id?.value)
        assertTrue(RuntimeIncompatibilityReason.AndroidApiTooLow in npu.compatibility.reasons)
        assertTrue(RuntimeIncompatibilityReason.VulkanUnavailable in npu.compatibility.reasons)
        assertTrue(RuntimeIncompatibilityReason.VulkanUnavailable in gpu.compatibility.reasons)
    }

    @Test
    fun returnsNoSelectionWhenMemoryOrWorkloadCannotBeProvenCompatible() {
        val result = optimizer().select(
            device = device(availableMemoryBytes = null),
            model = ModelRequirements(ModelWorkload.ImageGeneration, weightBytes = 1.gibibytes),
            runtimes = listOf(runtime("cpu", AccelerationBackend.Cpu)),
        )

        assertNull(result.selected)
        assertNotNull(result.candidates.single().compatibility)
        assertTrue(RuntimeIncompatibilityReason.WorkloadUnsupported in result.candidates.single().compatibility.reasons)
        assertTrue(RuntimeIncompatibilityReason.MemoryUnknown in result.candidates.single().compatibility.reasons)
    }

    @Test
    fun thermalPressureReducesTheSelectedCpuThreadBudget() {
        val result = optimizer().select(
            device = device(thermalStatus = ThermalStatus.Severe),
            model = ModelRequirements(ModelWorkload.TextEmbedding, weightBytes = 256.megabytes),
            runtimes = listOf(runtime("cpu", AccelerationBackend.Cpu)),
        )

        assertEquals(4, result.configuration?.cpuThreads)
    }

    private fun optimizer() = AutoOptimizer()

    private fun runtime(
        id: String,
        acceleration: AccelerationBackend,
        minAndroidApi: Int = 29,
        requiresVulkan: Boolean = false,
    ) = RuntimeDescriptor(
        id = RuntimeId.parse(id),
        version = "test",
        supportedAbis = setOf("arm64-v8a"),
        minAndroidApi = minAndroidApi,
        workloads = setOf(ModelWorkload.TextGeneration, ModelWorkload.TextEmbedding),
        acceleration = acceleration,
        requiresVulkan = requiresVulkan,
        memoryOverheadBytes = 256.megabytes,
    )

    private fun device(
        availableMemoryBytes: Long? = 6.gibibytes,
        hasVulkan: Boolean = true,
        thermalStatus: ThermalStatus = ThermalStatus.Nominal,
    ) = DeviceProfile(
        manufacturer = "Test",
        model = "Optimizer",
        androidApi = 37,
        supportedAbis = listOf("arm64-v8a"),
        cpuCoreCount = 8,
        totalMemoryBytes = 8.gibibytes,
        availableMemoryBytes = availableMemoryBytes,
        availableStorageBytes = 64.gibibytes,
        isCharging = true,
        thermalStatus = thermalStatus,
        hasVulkan = hasVulkan,
    )

    private val Int.megabytes: Long
        get() = this.toLong() * 1024L * 1024L

    private val Int.gibibytes: Long
        get() = this.toLong() * 1024L * 1024L * 1024L
}
