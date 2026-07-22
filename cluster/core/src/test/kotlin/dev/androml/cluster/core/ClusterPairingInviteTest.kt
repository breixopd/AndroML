package dev.androml.cluster.core

import dev.androml.core.api.CertificateFingerprint
import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterPairingInviteTest {
    @Test
    fun qrPayloadRoundTripsAndTokenIsSingleUse() {
        val certificate = "test-certificate".toByteArray()
        val fingerprint = CertificateFingerprint.parse(
            MessageDigest.getInstance("SHA-256").digest(certificate)
                .joinToString("") { byte -> "%02x".format(byte) },
        )
        val issuer = ClusterPairingInviteIssuer()
        val invite = issuer.issue(
            peerId = PeerId.parse("pixel-2"),
            endpoint = PeerEndpoint("192.168.1.2", 8788),
            certificate = certificate,
            fingerprint = fingerprint,
            nowEpochMillis = 1_000L,
        )
        val decoded = issuer.decodeQrPayload(issuer.encodeQrPayload(invite))
        assertTrue(issuer.consume(decoded.pairingId, decoded.token, 2_000L))
        assertFalse(issuer.consume(decoded.pairingId, decoded.token, 2_001L))
    }
}
