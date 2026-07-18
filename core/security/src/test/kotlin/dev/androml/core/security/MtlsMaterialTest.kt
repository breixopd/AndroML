package dev.androml.core.security

import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MtlsMaterialTest {
    @Test
    fun generatedIdentitiesCompleteMutualTlsHandshake() {
        val serverIdentity = SelfSignedTlsIdentityFactory.generate("server")
        val clientIdentity = SelfSignedTlsIdentityFactory.generate("client")
        assertNotEquals(serverIdentity.fingerprint, clientIdentity.fingerprint)

        val serverContext = MtlsContextFactory.serverContext(
            identity = serverIdentity,
            trustedClientCertificates = listOf(clientIdentity.certificate),
        )
        val clientContext = MtlsContextFactory.clientContext(
            identity = clientIdentity,
            trustedServerCertificates = listOf(serverIdentity.certificate),
        )
        val server = serverContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        server.needClientAuth = true
        val executor = Executors.newSingleThreadExecutor()
        val accepted = executor.submit<Boolean> {
            (server.accept() as SSLSocket).use { socket ->
                socket.startHandshake()
                socket.session.peerCertificates.first().encoded.contentEquals(clientIdentity.certificate.encoded)
            }
        }
        try {
            (clientContext.socketFactory.createSocket(InetAddress.getLoopbackAddress(), server.localPort) as SSLSocket).use { socket ->
                socket.startHandshake()
                assertTrue(socket.session.peerCertificates.first().encoded.contentEquals(serverIdentity.certificate.encoded))
            }
            assertTrue(accepted.get(10, TimeUnit.SECONDS))
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }
}
