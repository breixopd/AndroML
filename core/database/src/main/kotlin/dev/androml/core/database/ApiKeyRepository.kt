package dev.androml.core.database

import dev.androml.core.api.ApiKeyCodec
import dev.androml.core.api.ApiKeyId
import dev.androml.core.api.ApiKeyRecord
import dev.androml.core.api.ApiScope
import dev.androml.core.api.GeneratedApiKey
import java.util.Locale

class ApiKeyRepository(
    private val dao: ApiKeyDao,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun snapshot(): List<ApiKeyRecord> =
        dao.list().map(ApiKeyStorageMapper::toDomain)

    suspend fun create(
        displayName: String,
        scopes: Set<ApiScope>,
        expiresAtEpochMillis: Long? = null,
    ): GeneratedApiKey {
        val generated = ApiKeyCodec.generate(
            displayName = displayName,
            scopes = scopes,
            expiresAtEpochMillis = expiresAtEpochMillis,
            nowEpochMillis = nowEpochMillis(),
        )
        dao.insert(ApiKeyStorageMapper.toEntity(generated.record))
        return generated
    }

    suspend fun revoke(id: ApiKeyId, revokedAtEpochMillis: Long = nowEpochMillis()) {
        check(dao.revoke(id.value, revokedAtEpochMillis) == 1) { "API key does not exist" }
    }

    suspend fun markUsed(id: ApiKeyId, usedAtEpochMillis: Long = nowEpochMillis()) {
        check(dao.markUsed(id.value, usedAtEpochMillis) == 1) { "API key does not exist" }
    }
}

object ApiKeyStorageMapper {
    fun toEntity(record: ApiKeyRecord): ApiKeyEntity = ApiKeyEntity(
        id = record.id.value,
        displayName = record.displayName,
        tokenHash = record.tokenHash,
        scopes = record.scopes
            .map { it.name.lowercase(Locale.ROOT) }
            .sorted()
            .joinToString(","),
        createdAtEpochMillis = record.createdAtEpochMillis,
        expiresAtEpochMillis = record.expiresAtEpochMillis,
        revokedAtEpochMillis = record.revokedAtEpochMillis,
        lastUsedAtEpochMillis = record.lastUsedAtEpochMillis,
    )

    fun toDomain(entity: ApiKeyEntity): ApiKeyRecord {
        val scopes = entity.scopes.split(',')
            .filter(String::isNotBlank)
            .map { raw ->
                ApiScope.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                    ?: throw IllegalArgumentException("unknown persisted API scope")
            }
            .toSet()
        return ApiKeyRecord(
            id = ApiKeyId.parse(entity.id),
            displayName = entity.displayName,
            tokenHash = entity.tokenHash,
            scopes = scopes,
            createdAtEpochMillis = entity.createdAtEpochMillis,
            expiresAtEpochMillis = entity.expiresAtEpochMillis,
            revokedAtEpochMillis = entity.revokedAtEpochMillis,
            lastUsedAtEpochMillis = entity.lastUsedAtEpochMillis,
        )
    }
}
