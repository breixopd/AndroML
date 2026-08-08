package dev.androml.app

import dev.androml.cluster.core.ClusterInferenceTask
import dev.androml.cluster.core.ClusterNode
import dev.androml.cluster.core.ClusterPeer
import dev.androml.cluster.core.ClusterWorkload
import dev.androml.cluster.core.ContentHash
import dev.androml.cluster.core.NodeCapabilities
import dev.androml.cluster.core.PeerEndpoint
import dev.androml.cluster.core.PeerId
import dev.androml.core.api.CertificateFingerprint
import dev.androml.core.model.DeviceProfile
import dev.androml.core.model.ThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ClusterPlacementTest {
    @Test
    fun remoteOnlyPlacementNeverReturnsTheCaller() {
        val local = node("local", isLocal = true)
        val remote = node("remote", isLocal = false)

        assertEquals(
            listOf(remote),
            clusterCandidates(local, listOf(remote), ClusterPlacementPolicy.RemoteOnly),
        )
        assertEquals(
            listOf(local, remote),
            clusterCandidates(local, listOf(remote), ClusterPlacementPolicy.Auto),
        )
    }

    @Test
    fun receivingPhoneUsesItsOwnSafeRuntimeLimits() {
        val configuration = clusterRuntimeConfiguration(
            task = task(cpuThreads = 2, useAcceleration = true),
            profile = profile(cpuCoreCount = 12, hasVulkan = false),
        )

        assertEquals(8, configuration.cpuThreads)
        assertEquals(2_048, configuration.contextTokens)
        assertFalse(configuration.useAcceleration)
    }

    private fun task(cpuThreads: Int, useAcceleration: Boolean) = ClusterInferenceTask(
        modelHash = ContentHash.parse("b".repeat(64)),
        prompt = "hello",
        maxNewTokens = 32,
        temperature = 0.7,
        contextTokens = 2_048,
        kvCacheBytesPerToken = 0L,
        cpuThreads = cpuThreads,
        useAcceleration = useAcceleration,
        runtimeId = "llama.cpp",
    )

    private fun profile(cpuCoreCount: Int, hasVulkan: Boolean) = DeviceProfile(
        manufacturer = "Test",
        model = "Phone",
        androidApi = 37,
        supportedAbis = listOf("arm64-v8a"),
        cpuCoreCount = cpuCoreCount,
        totalMemoryBytes = 8L shl 30,
        availableMemoryBytes = 4L shl 30,
        availableStorageBytes = 32L shl 30,
        isCharging = true,
        thermalStatus = ThermalStatus.Nominal,
        hasVulkan = hasVulkan,
    )

    private fun node(id: String, isLocal: Boolean): ClusterNode = ClusterNode(
        peer = ClusterPeer(
            id = PeerId.parse(id),
            fingerprint = CertificateFingerprint.parse("a".repeat(64)),
            displayName = id,
            endpoint = PeerEndpoint(if (isLocal) "127.0.0.1" else "192.168.1.2", 8789),
            pairedAtEpochMillis = 1L,
            certificateExpiresAtEpochMillis = Long.MAX_VALUE,
            paired = true,
            capabilities = NodeCapabilities(
                supportedWorkloads = setOf(ClusterWorkload.InferenceReplica),
                maxConcurrentJobs = 1,
                availableRamBytes = 4L shl 30,
                queueDepth = 0,
                lastSeenEpochMillis = 1L,
            ),
        ),
        isLocal = isLocal,
    )
}
