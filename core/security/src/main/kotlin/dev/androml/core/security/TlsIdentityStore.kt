package dev.androml.core.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64

/** Non-secret certificate metadata safe to expose in UI and diagnostics. */
data class TlsIdentitySummary(
    val alias: String,
    val fingerprint: dev.androml.core.api.CertificateFingerprint,
    val notBeforeEpochMillis: Long,
    val notAfterEpochMillis: Long,
) {
    init {
        require(notAfterEpochMillis > notBeforeEpochMillis) {
            "TLS certificate validity interval is invalid"
        }
    }
}

fun TlsIdentity.summary(): TlsIdentitySummary = TlsIdentitySummary(
    alias = alias,
    fingerprint = fingerprint,
    notBeforeEpochMillis = certificate.notBefore.time,
    notAfterEpochMillis = certificate.notAfter.time,
)

/** Compact, versioned encoding for a TLS private key and its certificate. */
object TlsIdentityCodec {
    private const val FORMAT_VERSION: Int = 1
    private const val MAX_ALIAS_BYTES = 64
    private const val MAX_PRIVATE_KEY_BYTES = 8 * 1024
    private const val MAX_CERTIFICATE_BYTES = 16 * 1024

    fun encode(identity: TlsIdentity): String {
        val aliasBytes = identity.alias.toByteArray(Charsets.UTF_8)
        val privateKeyBytes = identity.privateKey.encoded
            ?: throw IllegalArgumentException("TLS private key is not exportable")
        val certificateBytes = identity.encodedCertificate
        require(aliasBytes.size in 1..MAX_ALIAS_BYTES) { "TLS alias is too long to store" }
        require(privateKeyBytes.size in 1..MAX_PRIVATE_KEY_BYTES) { "TLS private key is too large" }
        require(certificateBytes.size in 1..MAX_CERTIFICATE_BYTES) { "TLS certificate is too large" }

        val payload = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(FORMAT_VERSION)
                data.writeInt(aliasBytes.size)
                data.write(aliasBytes)
                data.writeInt(privateKeyBytes.size)
                data.write(privateKeyBytes)
                data.writeInt(certificateBytes.size)
                data.write(certificateBytes)
            }
            output.toByteArray()
        }
        return Base64.getEncoder().withoutPadding().encodeToString(payload)
    }

    fun decode(encoded: String): TlsIdentity {
        require(encoded.length <= 64 * 1024) { "TLS identity payload is too large" }
        val payload = Base64.getDecoder().decode(encoded)
        return DataInputStream(ByteArrayInputStream(payload)).use { data ->
            require(data.readInt() == FORMAT_VERSION) { "TLS identity format is unsupported" }
            val alias = data.readBoundedUtf8(MAX_ALIAS_BYTES, "TLS alias")
            val privateKeyBytes = data.readBoundedBytes(MAX_PRIVATE_KEY_BYTES, "TLS private key")
            val certificateBytes = data.readBoundedBytes(MAX_CERTIFICATE_BYTES, "TLS certificate")
            require(data.available() == 0) { "TLS identity payload has trailing data" }

            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certificateBytes)) as X509Certificate
            val keyAlgorithm = certificate.publicKey.algorithm
            require(keyAlgorithm == "EC" || keyAlgorithm == "RSA") {
                "TLS certificate key algorithm is unsupported"
            }
            val privateKey = KeyFactory.getInstance(keyAlgorithm)
                .generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
            certificate.verify(certificate.publicKey)
            val signatureAlgorithm = when (keyAlgorithm) {
                "EC" -> "SHA256withECDSA"
                "RSA" -> "SHA256withRSA"
                else -> error("unreachable")
            }
            val proof = Signature.getInstance(signatureAlgorithm).run {
                initSign(privateKey)
                update(certificate.tbsCertificate)
                sign()
            }
            require(Signature.getInstance(signatureAlgorithm).run {
                initVerify(certificate.publicKey)
                update(certificate.tbsCertificate)
                verify(proof)
            }) { "TLS private key does not match certificate" }
            TlsIdentity(
                alias = alias,
                keyPair = KeyPair(certificate.publicKey, privateKey),
                certificate = certificate,
                fingerprint = certificate.fingerprint(),
            )
        }
    }

    private fun DataInputStream.readBoundedBytes(maxBytes: Int, label: String): ByteArray {
        val size = readInt()
        require(size in 1..maxBytes) { "$label payload is out of bounds" }
        return ByteArray(size).also(::readFully)
    }

    private fun DataInputStream.readBoundedUtf8(maxBytes: Int, label: String): String {
        val bytes = readBoundedBytes(maxBytes, label)
        return bytes.toString(Charsets.UTF_8).also {
            require(it.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "$label is not valid UTF-8" }
        }
    }
}

/** Persists a node identity through the app's encrypted Android Keystore secret store. */
class TlsIdentityStore(
    private val secretStore: SecretStore,
    private val nowEpochMillis: () -> Long = { Instant.now().toEpochMilli() },
) {
    fun loadOrCreate(
        alias: String,
        subjectName: String = "AndroML node",
        validityMillis: Long = 365L * 24 * 60 * 60 * 1_000L,
    ): TlsIdentity {
        val secretName = secretName(alias)
        val stored = secretStore.read(secretName)
        if (stored != null) {
            val identity = TlsIdentityCodec.decode(stored)
            if (identity.certificate.notBefore.time <= nowEpochMillis() &&
                identity.certificate.notAfter.time > nowEpochMillis()
            ) {
                return identity
            }
        }
        val generated = SelfSignedTlsIdentityFactory.generate(
            alias = alias,
            subjectName = subjectName,
            nowEpochMillis = nowEpochMillis(),
            validityMillis = validityMillis,
        )
        secretStore.write(secretName, TlsIdentityCodec.encode(generated))
        return generated
    }

    fun delete(alias: String) {
        secretStore.delete(secretName(alias))
    }

    private fun secretName(alias: String): String {
        require(alias.matches(Regex("[a-z0-9][a-z0-9._-]{0,56}"))) { "TLS alias is invalid" }
        return "tls.$alias"
    }
}

private fun X509Certificate.fingerprint(): dev.androml.core.api.CertificateFingerprint =
    dev.androml.core.api.CertificateFingerprint.parse(
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(encoded)
            .joinToString("") { byte -> "%02x".format(byte) },
    )
