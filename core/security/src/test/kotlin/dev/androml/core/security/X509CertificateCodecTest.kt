package dev.androml.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class X509CertificateCodecTest {
    @Test
    fun decodesPemAndBase64AndPreservesFingerprint() {
        val identity = SelfSignedTlsIdentityFactory.generate("codec-test")

        val pem = X509CertificateCodec.encodePem(identity.certificate)
        val decodedPem = X509CertificateCodec.decode(pem)
        val base64 = java.util.Base64.getEncoder().encodeToString(identity.certificate.encoded)
        val decodedBase64 = X509CertificateCodec.decode(base64)

        assertEquals(identity.fingerprint, X509CertificateCodec.fingerprint(decodedPem))
        assertEquals(identity.fingerprint, X509CertificateCodec.fingerprint(decodedBase64))
        assertEquals(identity.certificate.encoded.toList(), decodedPem.encoded.toList())
    }

    @Test
    fun rejectsTrailingCertificateBytes() {
        val identity = SelfSignedTlsIdentityFactory.generate("codec-trailing")
        val encoded = java.util.Base64.getEncoder().encodeToString(identity.certificate.encoded + byteArrayOf(1, 2, 3))

        assertThrows(IllegalArgumentException::class.java) {
            X509CertificateCodec.decode(encoded)
        }
    }
}
