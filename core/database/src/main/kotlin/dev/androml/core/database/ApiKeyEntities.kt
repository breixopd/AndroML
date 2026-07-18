package dev.androml.core.database

import androidx.room.Entity

@Entity(tableName = "api_keys")
data class ApiKeyEntity(
    @androidx.room.PrimaryKey val id: String,
    val displayName: String,
    val tokenHash: String,
    val scopes: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long?,
    val revokedAtEpochMillis: Long?,
    val lastUsedAtEpochMillis: Long?,
)
