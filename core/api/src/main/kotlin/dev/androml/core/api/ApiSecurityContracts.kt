package dev.androml.core.api

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Locale

@JvmInline
value class ApiKeyId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): ApiKeyId {
            require(raw.matches(Regex("ak-[a-z0-9][a-z0-9-]{7,31}"))) {
                "API key ID is invalid"
            }
            return ApiKeyId(raw)
        }
    }
}

enum class ApiScope {
    ModelsRead,
    Inference,
    RagRead,
    RagWrite,
    Tools,
    Agents,
    Cluster,
    Admin,
}

enum class ApiRequestClass {
    Health,
    ReadOnly,
    Content,
    Mutating,
}

data class ApiKeyRecord(
    val id: ApiKeyId,
    val displayName: String,
    val tokenHash: String,
    val scopes: Set<ApiScope>,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long?,
    val revokedAtEpochMillis: Long? = null,
    val lastUsedAtEpochMillis: Long? = null,
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= 128) { "API key display name is invalid" }
        require(tokenHash.matches(SHA256_PATTERN)) { "API key hash must be SHA-256" }
        require(scopes.isNotEmpty()) { "API key must have at least one scope" }
        require(expiresAtEpochMillis == null || expiresAtEpochMillis > createdAtEpochMillis) {
            "API key expiry must be after creation"
        }
        require(revokedAtEpochMillis == null || revokedAtEpochMillis >= createdAtEpochMillis) {
            "API key revocation time is invalid"
        }
    }

    fun isUsableAt(nowEpochMillis: Long): Boolean =
        revokedAtEpochMillis == null &&
            (expiresAtEpochMillis == null || nowEpochMillis < expiresAtEpochMillis)
}

data class GeneratedApiKey(
    val record: ApiKeyRecord,
    /** Display exactly once; callers must not persist this value in logs or normal app state. */
    val plaintextToken: String,
)

object ApiKeyCodec {
    private const val TOKEN_BYTES = 32
    private const val TOKEN_PREFIX = "amk_"

    fun generate(
        displayName: String,
        scopes: Set<ApiScope>,
        expiresAtEpochMillis: Long? = null,
        nowEpochMillis: Long = Instant.now().toEpochMilli(),
        random: SecureRandom = SecureRandom(),
    ): GeneratedApiKey {
        val id = ApiKeyId.parse("ak-${randomBytes(random, 12).toHex().take(16)}")
        val secret = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val plaintext = TOKEN_PREFIX + base64Url(secret)
        return GeneratedApiKey(
            record = ApiKeyRecord(
                id = id,
                displayName = displayName,
                tokenHash = hash(plaintext),
                scopes = scopes,
                createdAtEpochMillis = nowEpochMillis,
                expiresAtEpochMillis = expiresAtEpochMillis,
            ),
            plaintextToken = plaintext,
        )
    }

    fun hash(plaintextToken: String): String {
        require(plaintextToken.startsWith(TOKEN_PREFIX)) { "API token prefix is invalid" }
        require(plaintextToken.length <= 256) { "API token is too long" }
        return sha256(plaintextToken)
    }

    private fun randomBytes(random: SecureRandom, size: Int): ByteArray =
        ByteArray(size).also(random::nextBytes)

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

data class ApiAuthResult(
    val record: ApiKeyRecord,
    val scope: ApiScope,
)

class ApiKeyAuthenticator {
    fun authenticate(
        plaintextToken: String,
        records: Collection<ApiKeyRecord>,
        requiredScope: ApiScope,
        nowEpochMillis: Long = Instant.now().toEpochMilli(),
    ): ApiAuthResult? {
        val suppliedHash = runCatching { ApiKeyCodec.hash(plaintextToken) }.getOrNull() ?: return null
        return records.firstOrNull { record ->
            record.isUsableAt(nowEpochMillis) &&
                requiredScope in record.scopes &&
                MessageDigest.isEqual(
                    suppliedHash.toByteArray(Charsets.US_ASCII),
                    record.tokenHash.toByteArray(Charsets.US_ASCII),
                )
        }?.let { ApiAuthResult(it, requiredScope) }
    }
}

enum class BindMode {
    Loopback,
    Lan,
}

@JvmInline
value class CertificateFingerprint private constructor(val value: String) {
    companion object {
        fun parse(raw: String): CertificateFingerprint {
            val normalized = raw.lowercase(Locale.ROOT).replace(":", "")
            require(normalized.matches(SHA256_PATTERN)) { "certificate fingerprint must be SHA-256" }
            return CertificateFingerprint(normalized)
        }
    }
}

data class MtlsPeer(
    val fingerprint: CertificateFingerprint,
    val displayName: String,
    val expiresAtEpochMillis: Long,
    val revoked: Boolean = false,
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= 128) { "peer display name is invalid" }
    }
}

data class ApiSecurityDecision(
    val allowed: Boolean,
    val reason: String,
    val requiresClientCertificate: Boolean,
    val requiresApiKey: Boolean,
)

class ApiSecurityPolicy(
    private val trustedPeers: Set<CertificateFingerprint> = emptySet(),
) {
    fun evaluate(
        bindMode: BindMode,
        requestClass: ApiRequestClass,
        peer: MtlsPeer?,
        apiAuth: ApiAuthResult?,
    ): ApiSecurityDecision {
        val requiresCertificate = bindMode == BindMode.Lan
        if (requiresCertificate) {
            if (peer == null) return denied("LAN API requires a client certificate", true, requestClass != ApiRequestClass.Health)
            if (peer.revoked || peer.expiresAtEpochMillis <= Instant.now().toEpochMilli()) {
                return denied("client certificate is expired or revoked", true, requestClass != ApiRequestClass.Health)
            }
            if (peer.fingerprint !in trustedPeers) {
                return denied("client certificate is not paired", true, requestClass != ApiRequestClass.Health)
            }
        }
        if (requestClass == ApiRequestClass.Health) {
            return ApiSecurityDecision(true, "health checks are allowed", requiresCertificate, false)
        }
        if (apiAuth == null) {
            return denied("a scoped API key is required", requiresCertificate, true)
        }
        return ApiSecurityDecision(true, "authorized", requiresCertificate, true)
    }

    private fun denied(reason: String, requiresCertificate: Boolean, requiresApiKey: Boolean) =
        ApiSecurityDecision(false, reason, requiresCertificate, requiresApiKey)
}

data class PairingOffer(
    val pairingId: String,
    val oneTimeToken: String,
    val expiresAtEpochMillis: Long,
)

data class PairingResult(
    val pairingId: String,
    val accepted: Boolean,
    val reason: String,
)

class OneTimePairing(
    private val lifetimeMillis: Long = 5 * 60 * 1000L,
    private val random: SecureRandom = SecureRandom(),
) {
    private data class Pending(
        val tokenHash: String,
        val expiresAtEpochMillis: Long,
        var used: Boolean,
    )

    private val pending = mutableMapOf<String, Pending>()

    @Synchronized
    fun issue(nowEpochMillis: Long = Instant.now().toEpochMilli()): PairingOffer {
        val pairingId = "pair-${randomBytes(8).toHex()}"
        val token = "${pairingId}_${base64Url(randomBytes(24))}"
        val expires = nowEpochMillis + lifetimeMillis
        pending[pairingId] = Pending(sha256(token), expires, used = false)
        return PairingOffer(pairingId, token, expires)
    }

    @Synchronized
    fun consume(
        pairingId: String,
        token: String,
        nowEpochMillis: Long = Instant.now().toEpochMilli(),
    ): PairingResult {
        val record = pending[pairingId] ?: return PairingResult(pairingId, false, "pairing code is unknown")
        if (record.used) return PairingResult(pairingId, false, "pairing code was already used")
        if (nowEpochMillis >= record.expiresAtEpochMillis) {
            record.used = true
            return PairingResult(pairingId, false, "pairing code expired")
        }
        val valid = MessageDigest.isEqual(
            record.tokenHash.toByteArray(Charsets.US_ASCII),
            sha256(token).toByteArray(Charsets.US_ASCII),
        )
        record.used = true
        return if (valid) {
            PairingResult(pairingId, true, "pairing accepted")
        } else {
            PairingResult(pairingId, false, "pairing code is invalid")
        }
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private val SHA256_PATTERN = Regex("[a-f0-9]{64}")

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
