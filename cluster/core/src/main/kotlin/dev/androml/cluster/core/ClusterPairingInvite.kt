package dev.androml.cluster.core

import dev.androml.core.api.CertificateFingerprint
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Short-lived, single-use payload suitable for a QR/deep-link pairing handoff. */
data class ClusterPairingInvite(
    val pairingId: String,
    val peerId: PeerId,
    val endpoint: PeerEndpoint,
    val certificateFingerprint: CertificateFingerprint,
    val certificateDerBase64: String,
    val token: String,
    val expiresAtEpochMillis: Long,
) {
    init {
        require(pairingId.matches(Regex("pair-[a-f0-9]{16}"))) { "pairing ID is invalid" }
        require(certificateDerBase64.length in 1..24_000) { "pairing certificate is invalid" }
        require(token.length in 32..256) { "pairing token is invalid" }
        require(expiresAtEpochMillis > 0L) { "pairing expiry is invalid" }
    }
}

class ClusterPairingInviteIssuer(
    private val random: SecureRandom = SecureRandom(),
    private val lifetimeMillis: Long = 5 * 60 * 1_000L,
) {
    private val pending = mutableMapOf<String, Pending>()

    init {
        require(lifetimeMillis in 30_000L..30 * 60 * 1_000L) { "pairing lifetime is out of bounds" }
    }

    @Synchronized
    fun issue(
        peerId: PeerId,
        endpoint: PeerEndpoint,
        certificate: ByteArray,
        fingerprint: CertificateFingerprint,
        nowEpochMillis: Long,
    ): ClusterPairingInvite {
        require(certificate.isNotEmpty() && certificate.size <= 16 * 1024) { "pairing certificate is out of bounds" }
        require(CertificateFingerprint.parse(sha256(certificate)) == fingerprint) {
            "pairing certificate fingerprint does not match"
        }
        val pairingId = "pair-${randomBytes(8).toHex()}"
        val token = base64Url(randomBytes(32))
        val expires = nowEpochMillis + lifetimeMillis
        pending[pairingId] = Pending(sha256(token.toByteArray(Charsets.UTF_8)), expires)
        return ClusterPairingInvite(
            pairingId = pairingId,
            peerId = peerId,
            endpoint = endpoint,
            certificateFingerprint = fingerprint,
            certificateDerBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(certificate),
            token = token,
            expiresAtEpochMillis = expires,
        )
    }

    @Synchronized
    fun consume(pairingId: String, token: String, nowEpochMillis: Long): Boolean {
        val record = pending[pairingId] ?: return false
        if (record.used || nowEpochMillis >= record.expiresAtEpochMillis) {
            record.used = true
            return false
        }
        record.used = true
        return MessageDigest.isEqual(
            record.tokenHash.toByteArray(Charsets.US_ASCII),
            sha256(token.toByteArray(Charsets.UTF_8)).toByteArray(Charsets.US_ASCII),
        )
    }

    fun encodeQrPayload(invite: ClusterPairingInvite): String {
        val json = buildJsonObject {
            put("v", 1)
            put("pairing_id", invite.pairingId)
            put("peer_id", invite.peerId.value)
            put("host", invite.endpoint.host)
            put("port", invite.endpoint.port)
            put("fingerprint", invite.certificateFingerprint.value)
            put("certificate", invite.certificateDerBase64)
            put("token", invite.token)
            put("expires", invite.expiresAtEpochMillis)
        }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
            Json.encodeToString(json).toByteArray(Charsets.UTF_8),
        )
        require(encoded.length <= MAX_QR_PAYLOAD_CHARS) { "pairing QR payload is too large" }
        return "androml://pair?v=1&p=$encoded"
    }

    fun decodeQrPayload(payload: String): ClusterPairingInvite {
        require(payload.length in 1..MAX_QR_PAYLOAD_CHARS) { "pairing QR payload is invalid" }
        val encoded = payload.substringAfter("androml://pair?v=1&p=", "")
        require(encoded.isNotBlank() && payload.startsWith("androml://pair?v=1&p=")) {
            "pairing QR scheme is invalid"
        }
        val root = Json.parseToJsonElement(
            Base64.getUrlDecoder().decode(encoded).toString(Charsets.UTF_8),
        ).jsonObject
        val certificate = root.required("certificate")
        val fingerprint = CertificateFingerprint.parse(root.required("fingerprint"))
        val certificateBytes = Base64.getUrlDecoder().decode(certificate)
        require(CertificateFingerprint.parse(sha256(certificateBytes)) == fingerprint) {
            "pairing QR certificate fingerprint does not match"
        }
        return ClusterPairingInvite(
            pairingId = root.required("pairing_id"),
            peerId = PeerId.parse(root.required("peer_id")),
            endpoint = PeerEndpoint(root.required("host"), root.requiredInt("port")),
            certificateFingerprint = fingerprint,
            certificateDerBase64 = certificate,
            token = root.required("token"),
            expiresAtEpochMillis = root.requiredLong("expires"),
        )
    }

    private data class Pending(
        val tokenHash: String,
        val expiresAtEpochMillis: Long,
        var used: Boolean = false,
    )

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    private companion object {
        const val MAX_QR_PAYLOAD_CHARS = 32_000
    }
}

private fun kotlinx.serialization.json.JsonObject.required(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("pairing field $name is missing")

private fun kotlinx.serialization.json.JsonObject.requiredInt(name: String): Int =
    this[name]?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("pairing field $name is invalid")

private fun kotlinx.serialization.json.JsonObject.requiredLong(name: String): Long =
    this[name]?.jsonPrimitive?.longOrNull ?: throw IllegalArgumentException("pairing field $name is invalid")

private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }

private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
