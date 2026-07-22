package dev.androml.core.security

import dev.androml.core.api.CertificateFingerprint
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

data class TlsIdentity(
    val alias: String,
    val keyPair: KeyPair,
    val certificate: X509Certificate,
    val fingerprint: CertificateFingerprint,
) {
    init {
        require(alias.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "TLS alias is invalid" }
        require(certificate.publicKey == keyPair.public) { "certificate key does not match identity key" }
    }

    val privateKey: PrivateKey
        get() = keyPair.private

    val encodedCertificate: ByteArray
        get() = certificate.encoded.copyOf()
}

object SelfSignedTlsIdentityFactory {
    private const val DEFAULT_VALIDITY_MILLIS = 365L * 24 * 60 * 60 * 1_000L
    private const val MIN_VALIDITY_MILLIS = 5L * 60 * 1_000L
    private const val MAX_VALIDITY_MILLIS = 2L * 365 * 24 * 60 * 60 * 1_000L

    fun generate(
        alias: String,
        subjectName: String = "AndroML node",
        nowEpochMillis: Long = Instant.now().toEpochMilli(),
        validityMillis: Long = DEFAULT_VALIDITY_MILLIS,
        random: SecureRandom = SecureRandom(),
    ): TlsIdentity {
        require(subjectName.matches(Regex("[A-Za-z0-9][A-Za-z0-9 ._-]{0,127}"))) { "TLS subject is invalid" }
        require(validityMillis in MIN_VALIDITY_MILLIS..MAX_VALIDITY_MILLIS) {
            "TLS validity is out of bounds"
        }
        val provider = BouncyCastleProvider()
        // RSA keeps the generated self-signed identities interoperable with the JDK/Netty
        // trust-path validator used by the LAN server.  Some JDK 21 providers reject otherwise
        // valid BC ECDSA self-signatures during mutual-TLS client-certificate validation.
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048, random)
        }.generateKeyPair()
        val notBefore = Date(nowEpochMillis - 60_000L)
        val notAfter = Date(nowEpochMillis + validityMillis)
        val issuer = X500Name("CN=$subjectName, O=AndroML")
        val serial = BigInteger(160, random).abs().max(BigInteger.ONE)
        val builder = JcaX509v3CertificateBuilder(
            issuer,
            serial,
            notBefore,
            notAfter,
            issuer,
            keyPair.public,
        )
            .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
            .addExtension(
                Extension.extendedKeyUsage,
                false,
                ExtendedKeyUsage(arrayOf(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth)),
            )
            .addExtension(
                Extension.subjectAlternativeName,
                false,
                GeneralNames(
                    arrayOf(
                        GeneralName(GeneralName.dNSName, "localhost"),
                        GeneralName(GeneralName.iPAddress, "127.0.0.1"),
                    ),
                ),
            )
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .build(keyPair.private)
        val certificate = JcaX509CertificateConverter()
            .setProvider(provider)
            .getCertificate(builder.build(signer))
        certificate.checkValidity(Date(nowEpochMillis))
        certificate.verify(keyPair.public)
        return TlsIdentity(
            alias = alias,
            keyPair = keyPair,
            certificate = certificate,
            fingerprint = CertificateFingerprint.parse(certificateFingerprint(certificate)),
        )
    }

    private fun certificateFingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { byte -> "%02x".format(byte) }
}

/** In-memory key/trust stores for a Ktor server TLS connector. */
class TlsServerMaterial internal constructor(
    val keyStore: java.security.KeyStore,
    val trustStore: java.security.KeyStore,
    val keyAlias: String,
    val trustedClientFingerprints: Set<CertificateFingerprint>,
    private val keyStorePassword: CharArray,
    private val privateKeyPassword: CharArray,
) {
    fun keyStorePassword(): CharArray = keyStorePassword.copyOf()

    fun privateKeyPassword(): CharArray = privateKeyPassword.copyOf()
}

object MtlsContextFactory {
    private const val KEY_STORE_PASSWORD = "androml-in-memory"
    private const val KEY_ALIAS = "identity"

    fun serverContext(
        identity: TlsIdentity,
        trustedClientCertificates: Collection<X509Certificate>,
    ): SSLContext = createContext(identity, trustedClientCertificates)

    fun serverMaterial(
        identity: TlsIdentity,
        trustedClientCertificates: Collection<X509Certificate>,
    ): TlsServerMaterial {
        require(trustedClientCertificates.isNotEmpty()) { "mTLS context requires at least one trusted peer" }
        return TlsServerMaterial(
            keyStore = createKeyStore(identity),
            trustStore = createTrustStore(trustedClientCertificates),
            keyAlias = KEY_ALIAS,
            trustedClientFingerprints = trustedClientCertificates
                .map { CertificateFingerprint.parse(certificateFingerprint(it)) }
                .toSet(),
            keyStorePassword = KEY_STORE_PASSWORD.toCharArray(),
            privateKeyPassword = KEY_STORE_PASSWORD.toCharArray(),
        )
    }

    fun clientContext(
        identity: TlsIdentity,
        trustedServerCertificates: Collection<X509Certificate>,
    ): SSLContext = createContext(identity, trustedServerCertificates)

    private fun createContext(
        identity: TlsIdentity,
        trustedCertificates: Collection<X509Certificate>,
    ): SSLContext {
        require(trustedCertificates.isNotEmpty()) { "mTLS context requires at least one trusted peer" }
        val keyStore = createKeyStore(identity)
        val trustStore = createTrustStore(trustedCertificates)
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, KEY_STORE_PASSWORD.toCharArray())
        }.keyManagers
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }.trustManagers
        val trustManager = trustManagers.filterIsInstance<X509TrustManager>().singleOrNull()
            ?: throw IllegalStateException("mTLS trust manager is unavailable")
        return SSLContext.getInstance("TLS").apply {
            init(keyManagers, arrayOf(PinnedTrustManager(trustManager)), SecureRandom())
        }
    }

    private fun createKeyStore(identity: TlsIdentity): java.security.KeyStore =
        java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry(
                KEY_ALIAS,
                identity.privateKey,
                KEY_STORE_PASSWORD.toCharArray(),
                arrayOf(identity.certificate),
            )
        }

    private fun createTrustStore(
        trustedCertificates: Collection<X509Certificate>,
    ): java.security.KeyStore =
        java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()).apply {
            load(null, null)
            trustedCertificates.distinctBy { certificateFingerprint(it) }.forEach { certificate ->
                setCertificateEntry("peer-${certificateFingerprint(certificate).take(24)}", certificate)
            }
        }

    private fun certificateFingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { byte -> "%02x".format(byte) }
}

private class PinnedTrustManager(
    private val delegate: X509TrustManager,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        delegate.checkClientTrusted(chain, authType)
        requireTrustedLeaf(chain)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        delegate.checkServerTrusted(chain, authType)
        requireTrustedLeaf(chain)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

    private fun requireTrustedLeaf(chain: Array<X509Certificate>) {
        if (chain.isEmpty()) throw CertificateException("peer certificate chain is empty")
        val leaf = chain.first()
        val leafFingerprint = MessageDigest.getInstance("SHA-256")
            .digest(leaf.encoded)
            .joinToString("") { byte -> "%02x".format(byte) }
        if (delegate.acceptedIssuers.none { certificateFingerprint(it) == leafFingerprint }) {
            throw CertificateException("peer certificate is not pinned")
        }
    }

    private fun certificateFingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { byte -> "%02x".format(byte) }
}
