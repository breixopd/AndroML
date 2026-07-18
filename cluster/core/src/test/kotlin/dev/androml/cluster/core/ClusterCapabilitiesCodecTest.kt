package dev.androml.cluster.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterCapabilitiesCodecTest {
    @Test
    fun capabilityAdvertisementRoundTripsAndKeepsStableEnums() {
        val advertisement = ClusterCapabilityAdvertisement(
            nodeId = PeerId.parse("node-a"),
            capabilities = NodeCapabilities(
                supportedWorkloads = setOf(ClusterWorkload.InferenceReplica, ClusterWorkload.RagSearch),
                modelHashes = setOf(ContentHash.parse("a".repeat(64)), ContentHash.parse("b".repeat(64))),
                maxConcurrentJobs = 2,
                availableRamBytes = 4_000_000_000L,
                queueDepth = 1,
                thermalSeverity = 1,
                batteryPercent = 86,
                charging = true,
                lastSeenEpochMillis = 1234L,
            ),
        )

        val decoded = ClusterCapabilitiesCodec.decode(ClusterCapabilitiesCodec.encode(advertisement))

        assertEquals(advertisement, decoded)
    }

    @Test
    fun capabilityAdvertisementRejectsUnknownProtocol() {
        val raw = """
            {"protocol_major":2,"protocol_minor":0,"node_id":"node-a","supported_workloads":["inference_replica"],"model_hashes":[],"max_concurrent_jobs":1,"available_ram_bytes":1,"queue_depth":0,"thermal_severity":0,"battery_percent":100,"charging":true,"last_seen_epoch_millis":1}
        """.trimIndent()

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ClusterCapabilitiesCodec.decode(raw)
        }
    }
}
