package dev.androml.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ApiKeyDao {
    @Query("SELECT * FROM api_keys ORDER BY createdAtEpochMillis DESC, id ASC")
    suspend fun list(): List<ApiKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ApiKeyEntity)

    @Query("UPDATE api_keys SET revokedAtEpochMillis = :revokedAtEpochMillis WHERE id = :id")
    suspend fun revoke(id: String, revokedAtEpochMillis: Long): Int

    @Query("UPDATE api_keys SET lastUsedAtEpochMillis = :lastUsedAtEpochMillis WHERE id = :id")
    suspend fun markUsed(id: String, lastUsedAtEpochMillis: Long): Int
}
