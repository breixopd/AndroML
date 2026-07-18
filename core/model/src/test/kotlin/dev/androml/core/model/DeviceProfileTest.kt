package dev.androml.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {
    @Test
    fun healthyProfileReportsNominalMemoryAndReadyStatus() {
        val profile = DeviceProfile(
            manufacturer = "Google",
            model = "Pixel Test",
            androidApi = 37,
            supportedAbis = listOf("arm64-v8a"),
            cpuCoreCount = 8,
            totalMemoryBytes = 8.gibibytes,
            availableMemoryBytes = 4.gibibytes,
            availableStorageBytes = 64.gibibytes,
            isCharging = true,
            thermalStatus = ThermalStatus.Nominal,
            hasVulkan = true,
        )

        assertEquals(MemoryPressure.Nominal, profile.memoryPressure)
        assertEquals(DeviceReadiness.Ready, profile.readiness)
        assertTrue(profile.resourceSummary.contains("8 CPU cores"))
    }

    @Test
    fun lowMemoryProfileReportsConstrainedReadiness() {
        val profile = DeviceProfile(
            manufacturer = "Test",
            model = "Low RAM",
            androidApi = 29,
            supportedAbis = listOf("arm64-v8a"),
            cpuCoreCount = 4,
            totalMemoryBytes = 4.gibibytes,
            availableMemoryBytes = 512.mebibytes,
            availableStorageBytes = 2.gibibytes,
            isCharging = false,
            thermalStatus = ThermalStatus.Nominal,
            hasVulkan = false,
        )

        assertEquals(MemoryPressure.Critical, profile.memoryPressure)
        assertEquals(DeviceReadiness.Constrained, profile.readiness)
    }

    @Test
    fun unknownMemoryDoesNotClaimTheDeviceIsHealthy() {
        val profile = DeviceProfile(
            manufacturer = "Unknown",
            model = "Device",
            androidApi = 37,
            supportedAbis = emptyList(),
            cpuCoreCount = 0,
            totalMemoryBytes = null,
            availableMemoryBytes = null,
            availableStorageBytes = null,
            isCharging = false,
            thermalStatus = ThermalStatus.Unknown,
            hasVulkan = false,
        )

        assertEquals(MemoryPressure.Unknown, profile.memoryPressure)
        assertEquals(DeviceReadiness.Unknown, profile.readiness)
    }

    private val Int.gibibytes: Long
        get() = this.toLong() * 1024L * 1024L * 1024L

    private val Int.mebibytes: Long
        get() = this.toLong() * 1024L * 1024L
}

