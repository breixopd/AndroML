package dev.androml.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TlsIdentityStoreTest {
    @Test
    fun codecRoundTripsIdentityWithoutChangingCertificateFingerprint() {
        val original = SelfSignedTlsIdentityFactory.generate("test-node")

        val restored = TlsIdentityCodec.decode(TlsIdentityCodec.encode(original))

        assertEquals(original.alias, restored.alias)
        assertEquals(original.fingerprint, restored.fingerprint)
        assertEquals(original.certificate, restored.certificate)
    }

    @Test
    fun storeReusesIdentityUntilCertificateExpires() {
        val secrets = InMemorySecretStore()
        var now = 1_000_000L
        val store = TlsIdentityStore(secrets) { now }

        val first = store.loadOrCreate(
            alias = "api-server",
            validityMillis = 300_000L,
        )

        val second = store.loadOrCreate("api-server")
        assertEquals(first.fingerprint, second.fingerprint)

        now = first.certificate.notAfter.time + 1L
        val rotated = store.loadOrCreate("api-server")
        assertNotEquals(first.fingerprint, rotated.fingerprint)
    }

    @Test
    fun summaryContainsCertificateMetadataWithoutPrivateKeyMaterial() {
        val identity = SelfSignedTlsIdentityFactory.generate("summary-node")

        val summary = identity.summary()

        assertEquals(identity.alias, summary.alias)
        assertEquals(identity.fingerprint, summary.fingerprint)
        assertEquals(identity.certificate.notBefore.time, summary.notBeforeEpochMillis)
        assertEquals(identity.certificate.notAfter.time, summary.notAfterEpochMillis)
    }

    private class InMemorySecretStore : SecretStore {
        private val values = mutableMapOf<String, String>()

        override fun read(name: String): String? = values[name]

        override fun write(name: String, value: String) {
            values[name] = value
        }

        override fun delete(name: String) {
            values.remove(name)
        }
    }
}
