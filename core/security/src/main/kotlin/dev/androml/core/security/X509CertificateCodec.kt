package dev.androml.core.security

import dev.androml.core.api.CertificateFingerprint
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

object X509CertificateCodec {
    const val MAX_CERTIFICATE_BYTES = 16 * 1024
    private const val MAX_TEXT_CHARS = 64 * 1024

    fun decode(raw: String): X509Certificate {
        require(raw.length in 1..MAX_TEXT_CHARS) { "certificate text is out of bounds" }
        val compact = raw.trim()
        val encoded = if (compact.contains("BEGIN CERTIFICATE")) {
            require(compact.contains("-----BEGIN CERTIFICATE-----")) { "certificate PEM header is invalid" }
            require(compact.contains("-----END CERTIFICATE-----")) { "certificate PEM footer is invalid" }
            compact
                .substringAfter("-----BEGIN CERTIFICATE-----")
                .substringBefore("-----END CERTIFICATE-----")
                .filterNot(Char::isWhitespace)
        } else {
            compact.filterNot(Char::isWhitespace)
        }
        val der = try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("certificate is not valid base64", error)
        }
        require(der.size in 1..MAX_CERTIFICATE_BYTES) { "certificate bytes are out of bounds" }
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        require(certificate.encoded.contentEquals(der)) { "certificate has trailing or non-canonical bytes" }
        certificate.checkValidity()
        return certificate
    }

    fun encodePem(certificate: X509Certificate): String {
        val der = certificate.encoded
        require(der.size in 1..MAX_CERTIFICATE_BYTES) { "certificate bytes are out of bounds" }
        val body = Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(der)
        return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----"
    }

    fun fingerprint(certificate: X509Certificate): CertificateFingerprint =
        CertificateFingerprint.parse(
            MessageDigest.getInstance("SHA-256")
                .digest(certificate.encoded)
                .joinToString("") { byte -> "%02x".format(byte) },
        )
}
