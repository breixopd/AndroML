package dev.androml.core.security

import dev.androml.core.api.CertificateFingerprint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

@JvmInline
value class ApiClientCertificateId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): ApiClientCertificateId {
            require(raw.matches(Regex("[a-f0-9]{32}"))) { "API client certificate ID is invalid" }
            return ApiClientCertificateId(raw)
        }

        fun generate(): ApiClientCertificateId =
            parse(UUID.randomUUID().toString().replace("-", ""))
    }
}

data class ApiClientCertificateRecord(
    val id: ApiClientCertificateId,
    val displayName: String,
    val fingerprint: CertificateFingerprint,
    val certificateDer: ByteArray,
    val addedAtEpochMillis: Long,
    val revokedAtEpochMillis: Long? = null,
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= 128) {
            "API client certificate display name is invalid"
        }
        require(certificateDer.isNotEmpty() && certificateDer.size <= X509CertificateCodec.MAX_CERTIFICATE_BYTES) {
            "API client certificate is invalid"
        }
        require(addedAtEpochMillis > 0L) { "API client certificate creation time is invalid" }
        require(revokedAtEpochMillis == null || revokedAtEpochMillis >= addedAtEpochMillis) {
            "API client certificate revocation time is invalid"
        }
    }

    fun certificate(): X509Certificate = decodeCertificateAllowExpired(certificateDer)

    fun isActiveAt(nowEpochMillis: Long): Boolean =
        revokedAtEpochMillis == null && certificate().notAfter.time > nowEpochMillis
}

/** Encrypted persistence for the public certificates allowed to reach the LAN API. */
class ApiClientCertificateStore(
    private val secretStore: SecretStore,
    private val nowEpochMillis: () -> Long = { Instant.now().toEpochMilli() },
) {
    fun snapshot(): List<ApiClientCertificateRecord> = readIndex()

    fun activeCertificates(): List<X509Certificate> = readIndex()
        .filter { it.isActiveAt(nowEpochMillis()) }
        .map(ApiClientCertificateRecord::certificate)

    fun add(displayName: String, certificate: X509Certificate): ApiClientCertificateRecord {
        val now = nowEpochMillis()
        certificate.checkValidity(Date(now))
        require(certificate.basicConstraints < 0) {
            "API client certificate must be an end-entity certificate"
        }
        val der = certificate.encoded
        require(der.size in 1..MAX_STORED_CERTIFICATE_BYTES) {
            "API client certificate is too large to store"
        }
        val fingerprint = X509CertificateCodec.fingerprint(certificate)
        val existing = readIndex()
        require(existing.none { it.fingerprint == fingerprint && it.isActiveAt(now) }) {
            "API client certificate is already paired"
        }
        val record = ApiClientCertificateRecord(
            id = ApiClientCertificateId.generate(),
            displayName = displayName.trim(),
            fingerprint = fingerprint,
            certificateDer = der.copyOf(),
            addedAtEpochMillis = now,
        )
        val secretName = certificateSecretName(record.id)
        try {
            secretStore.write(
                secretName,
                Base64.getEncoder().withoutPadding().encodeToString(record.certificateDer),
            )
            writeIndex(existing + record)
        } catch (error: Throwable) {
            secretStore.delete(secretName)
            throw error
        }
        return record
    }

    fun revoke(id: ApiClientCertificateId, revokedAtEpochMillis: Long = nowEpochMillis()) {
        val records = readIndex()
        val index = records.indexOfFirst { it.id == id }
        check(index >= 0) { "API client certificate does not exist" }
        val record = records[index]
        check(record.revokedAtEpochMillis == null) { "API client certificate is already revoked" }
        require(revokedAtEpochMillis >= record.addedAtEpochMillis) {
            "API client certificate revocation time is invalid"
        }
        writeIndex(records.toMutableList().also { it[index] = record.copy(revokedAtEpochMillis = revokedAtEpochMillis) })
    }

    fun remove(id: ApiClientCertificateId) {
        val records = readIndex()
        check(records.any { it.id == id }) { "API client certificate does not exist" }
        secretStore.delete(certificateSecretName(id))
        writeIndex(records.filterNot { it.id == id })
    }

    private fun readIndex(): List<ApiClientCertificateRecord> {
        val encoded = secretStore.read(INDEX_SECRET_NAME) ?: return emptyList()
        val payload = try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("API client certificate index is corrupt", error)
        }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == FORMAT_VERSION) { "API client certificate index version is unsupported" }
            val count = input.readInt()
            require(count in 0..MAX_CERTIFICATE_COUNT) { "API client certificate index is too large" }
            buildList(count) {
                repeat(count) {
                    val id = ApiClientCertificateId.parse(input.readBoundedUtf8(MAX_ID_BYTES, "certificate ID"))
                    val displayName = input.readBoundedUtf8(MAX_DISPLAY_NAME_BYTES, "certificate display name")
                    val fingerprint = CertificateFingerprint.parse(
                        input.readBoundedUtf8(MAX_FINGERPRINT_BYTES, "certificate fingerprint"),
                    )
                    val certificate = loadCertificate(id)
                    require(X509CertificateCodec.fingerprint(certificate) == fingerprint) {
                        "API client certificate fingerprint does not match its stored certificate"
                    }
                    add(
                        ApiClientCertificateRecord(
                            id = id,
                            displayName = displayName,
                            fingerprint = fingerprint,
                            certificateDer = certificate.encoded,
                            addedAtEpochMillis = input.readLong(),
                            revokedAtEpochMillis = input.readNullableLong(),
                        ),
                    )
                }
            }.also { require(input.available() == 0) { "API client certificate index has trailing data" } }
        }
    }

    private fun writeIndex(records: List<ApiClientCertificateRecord>) {
        require(records.size <= MAX_CERTIFICATE_COUNT) { "too many API client certificates" }
        val payload = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(FORMAT_VERSION)
                data.writeInt(records.size)
                records.forEach { record ->
                    data.writeUtf8(record.id.value, MAX_ID_BYTES, "certificate ID")
                    data.writeUtf8(record.displayName, MAX_DISPLAY_NAME_BYTES, "certificate display name")
                    data.writeUtf8(record.fingerprint.value, MAX_FINGERPRINT_BYTES, "certificate fingerprint")
                    data.writeLong(record.addedAtEpochMillis)
                    data.writeNullableLong(record.revokedAtEpochMillis)
                }
            }
            output.toByteArray()
        }
        val encoded = Base64.getEncoder().withoutPadding().encodeToString(payload)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_INDEX_SECRET_BYTES) {
            "API client certificate index is too large to store"
        }
        secretStore.write(INDEX_SECRET_NAME, encoded)
    }

    private fun loadCertificate(id: ApiClientCertificateId): X509Certificate {
        val encoded = secretStore.read(certificateSecretName(id))
            ?: throw IllegalStateException("API client certificate payload is missing")
        val der = try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("API client certificate payload is corrupt", error)
        }
        return decodeCertificateAllowExpired(der)
    }

    private fun certificateSecretName(id: ApiClientCertificateId): String =
        "api.client.${id.value}"

    private companion object {
        const val FORMAT_VERSION = 1
        const val INDEX_SECRET_NAME = "api.client.index"
        const val MAX_CERTIFICATE_COUNT = 32
        const val MAX_DISPLAY_NAME_BYTES = 512
        const val MAX_FINGERPRINT_BYTES = 64
        const val MAX_ID_BYTES = 32
        const val MAX_INDEX_SECRET_BYTES = 8 * 1024
        const val MAX_STORED_CERTIFICATE_BYTES = 12 * 1024
    }
}

private fun decodeCertificateAllowExpired(der: ByteArray): X509Certificate {
    require(der.size in 1..X509CertificateCodec.MAX_CERTIFICATE_BYTES) {
        "certificate bytes are out of bounds"
    }
    val certificate = CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    require(certificate.encoded.contentEquals(der)) { "certificate has trailing or non-canonical bytes" }
    return certificate
}

private fun DataOutputStream.writeUtf8(value: String, maxBytes: Int, label: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    require(bytes.isNotEmpty() && bytes.size <= maxBytes) { "$label is out of bounds" }
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readBoundedUtf8(maxBytes: Int, label: String): String {
    val size = readInt()
    require(size in 1..maxBytes) { "$label is out of bounds" }
    val bytes = ByteArray(size).also(::readFully)
    return bytes.toString(Charsets.UTF_8).also {
        require(it.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "$label is not valid UTF-8" }
    }
}

private fun DataOutputStream.writeNullableLong(value: Long?) {
    writeBoolean(value != null)
    if (value != null) writeLong(value)
}

private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null
