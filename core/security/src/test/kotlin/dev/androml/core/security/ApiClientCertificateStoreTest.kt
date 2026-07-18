package dev.androml.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ApiClientCertificateStoreTest {
    @Test
    fun trustedClientCertificatesSurviveReloadAndRevocation() {
        val secrets = InMemorySecretStore()
        val now = 1_000_000L
        val identity = SelfSignedTlsIdentityFactory.generate(
            alias = "api-client",
            subjectName = "Test client",
            nowEpochMillis = now,
        )
        val store = ApiClientCertificateStore(secrets) { now }

        val created = store.add("Laptop", identity.certificate)

        val reloaded = ApiClientCertificateStore(secrets) { now }
        assertEquals(created.id, reloaded.snapshot().single().id)
        assertEquals(created.fingerprint, reloaded.snapshot().single().fingerprint)
        assertEquals(
            identity.certificate.encoded.toList(),
            reloaded.activeCertificates().single().encoded.toList(),
        )

        reloaded.revoke(created.id)

        assertTrue(reloaded.snapshot().single().revokedAtEpochMillis != null)
        assertTrue(reloaded.activeCertificates().isEmpty())
    }

    @Test
    fun duplicateActiveFingerprintsAreRejected() {
        val secrets = InMemorySecretStore()
        val now = 1_000_000L
        val identity = SelfSignedTlsIdentityFactory.generate(
            alias = "api-client",
            subjectName = "Test client",
            nowEpochMillis = now,
        )
        val store = ApiClientCertificateStore(secrets) { now }

        store.add("Laptop", identity.certificate)

        assertFalse(store.snapshot().isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            store.add("Laptop again", identity.certificate)
        }
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
