package dev.androml.cluster.transport

import dev.androml.cluster.core.ClusterExecutionHandler
import dev.androml.cluster.core.ClusterExecutionRequest
import dev.androml.cluster.core.ClusterExecutionStatus
import dev.androml.cluster.core.ClusterJobId
import dev.androml.cluster.core.ClusterRequest
import dev.androml.cluster.core.ClusterWorkload
import dev.androml.cluster.core.ContentHash
import dev.androml.cluster.core.IdempotentClusterExecutor
import dev.androml.cluster.core.InMemoryClusterJobLedger
import dev.androml.cluster.core.PeerEndpoint
import dev.androml.cluster.core.PeerId
import dev.androml.core.api.CertificateFingerprint
import dev.androml.core.security.MtlsContextFactory
import dev.androml.core.security.SelfSignedTlsIdentityFactory
import java.net.ServerSocket
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterTransportTest {
    @Test
    fun mutualTlsPeerExecutionIsIdempotent() {
        val serverIdentity = SelfSignedTlsIdentityFactory.generate("cluster-server")
        val clientIdentity = SelfSignedTlsIdentityFactory.generate("cluster-client")
        val port = availablePort()
        var executions = 0
        val server = ClusterExecutionServer(
            config = ClusterTransportConfig(host = "127.0.0.1", port = port),
            tlsMaterial = MtlsContextFactory.serverMaterial(
                identity = serverIdentity,
                trustedClientCertificates = listOf(clientIdentity.certificate),
            ),
            pairedPeers = {
                mapOf(clientIdentity.fingerprint to PeerId.parse("node-a"))
            },
            executor = IdempotentClusterExecutor(
                ledger = InMemoryClusterJobLedger(),
                nowEpochMillis = { 60_000L },
                handler = ClusterExecutionHandler {
                    executions += 1
                    it.payload.reversedArray()
                },
            ),
        )

        server.start()
        try {
            val client = ClusterExecutionClient(
                clientIdentity = clientIdentity,
                trustedServerCertificate = serverIdentity.certificate,
            )
            val request = executionRequest(sourcePeer = "node-a")

            val first = client.execute(PeerEndpoint("127.0.0.1", port), request)
            val second = client.execute(PeerEndpoint("127.0.0.1", port), request)

            assertEquals(ClusterExecutionStatus.Completed, first.status)
            assertEquals("payload".reversed(), first.output!!.toString(Charsets.UTF_8))
            assertEquals(ClusterExecutionStatus.AlreadyCompleted, second.status)
            assertEquals(first.outputHash, second.outputHash)
            assertEquals(1, executions)
        } finally {
            server.stop()
        }
    }

    @Test
    fun trustedButUnpairedPeerIsRejectedByTheTransport() {
        val serverIdentity = SelfSignedTlsIdentityFactory.generate("cluster-server-unpaired")
        val pairedClient = SelfSignedTlsIdentityFactory.generate("cluster-client-paired")
        val unpairedClient = SelfSignedTlsIdentityFactory.generate("cluster-client-unpaired")
        val port = availablePort()
        val server = ClusterExecutionServer(
            config = ClusterTransportConfig(host = "127.0.0.1", port = port),
            tlsMaterial = MtlsContextFactory.serverMaterial(
                identity = serverIdentity,
                trustedClientCertificates = listOf(pairedClient.certificate, unpairedClient.certificate),
            ),
            pairedPeers = {
                mapOf(pairedClient.fingerprint to PeerId.parse("node-paired"))
            },
            executor = testExecutor(now = 60_000L),
        )

        server.start()
        try {
            val client = ClusterExecutionClient(
                clientIdentity = unpairedClient,
                trustedServerCertificate = serverIdentity.certificate,
            )
            val error = runCatching {
                client.execute(PeerEndpoint("127.0.0.1", port), executionRequest(sourcePeer = "node-unpaired"))
            }.exceptionOrNull()

            assertTrue(error is ClusterTransportException)
            assertEquals(403, (error as ClusterTransportException).httpStatus)
        } finally {
            server.stop()
        }
    }

    private fun testExecutor(now: Long) = IdempotentClusterExecutor(
        ledger = InMemoryClusterJobLedger(),
        nowEpochMillis = { now },
        handler = ClusterExecutionHandler { it.payload },
    )

    private fun executionRequest(sourcePeer: String): ClusterExecutionRequest {
        val payload = "payload".toByteArray()
        return ClusterExecutionRequest(
            sourcePeerId = PeerId.parse(sourcePeer),
            request = ClusterRequest(
                jobId = ClusterJobId.parse("job-transport"),
                attempt = 1,
                workload = ClusterWorkload.InferenceReplica,
                modelKey = "model-a",
                modelHash = ContentHash.parse("c".repeat(64)),
                requiredRamBytes = 1_024L,
                deadlineEpochMillis = 70_000L,
                payloadHash = ContentHash.parse(sha256(payload)),
                idempotencyKey = "job-transport:1",
            ),
            payload = payload,
        )
    }

    private fun availablePort(): Int = ServerSocket(0).use { it.localPort }

    private fun sha256(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString("") { byte -> "%02x".format(byte) }
}
