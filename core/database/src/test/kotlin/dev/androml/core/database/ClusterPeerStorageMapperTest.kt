package dev.androml.core.database

import dev.androml.cluster.core.ClusterPeer
import dev.androml.cluster.core.ClusterWorkload
import dev.androml.cluster.core.ContentHash
import dev.androml.cluster.core.NodeCapabilities
import dev.androml.cluster.core.PeerEndpoint
import dev.androml.cluster.core.PeerId
import dev.androml.cluster.core.StoredClusterPeer
import dev.androml.core.api.CertificateFingerprint
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterPeerStorageMapperTest {
    @Test
    fun storesTrustMaterialAndCapabilitySnapshotWithoutLoss() {
        val certificate = "certificate bytes".toByteArray()
        val peer = StoredClusterPeer(
            peer = ClusterPeer(
                id = PeerId.parse("pixel-2"),
                fingerprint = CertificateFingerprint.parse(sha256(certificate)),
                displayName = "Pixel 2",
                endpoint = PeerEndpoint("192.168.1.22", 8788),
                pairedAtEpochMillis = 1_000L,
                certificateExpiresAtEpochMillis = 10_000L,
                paired = true,
                capabilities = NodeCapabilities(
                    supportedWorkloads = setOf(ClusterWorkload.InferenceReplica, ClusterWorkload.RagSearch),
                    modelHashes = setOf(ContentHash.parse("a".repeat(64))),
                    maxConcurrentJobs = 2,
                    availableRamBytes = 4_000L,
                    queueDepth = 1,
                    thermalSeverity = 1,
                    batteryPercent = 80,
                    charging = false,
                    lastSeenEpochMillis = 2_000L,
                ),
            ),
            certificateDer = certificate,
        )

        val restored = ClusterPeerStorageMapper.toDomain(ClusterPeerStorageMapper.toEntity(peer))

        assertEquals(peer.peer, restored.peer)
        assertArrayEquals(certificate, restored.certificateDer)
    }

    private fun sha256(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString("") { byte -> "%02x".format(byte) }
}
