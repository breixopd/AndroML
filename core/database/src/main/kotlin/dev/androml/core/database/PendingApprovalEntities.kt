package dev.androml.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Metadata for a pending destructive tool/agent approval.
 *
 * The continuation itself is encrypted in the Android Keystore-backed secret store. This table
 * intentionally contains only hashes, expiry metadata, and the encrypted payload index.
 */
@Entity(
    tableName = "pending_approvals",
    indices = [Index(value = ["expiresAtEpochMillis"])],
)
data class PendingApprovalEntity(
    @PrimaryKey val approvalId: String,
    val kind: String,
    val toolId: String,
    val argumentHash: String,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val secretPrefix: String,
    val chunkCount: Int,
    val createdAtEpochMillis: Long,
)

@Dao
interface PendingApprovalDao {
    @Query("SELECT * FROM pending_approvals WHERE approvalId = :approvalId")
    suspend fun find(approvalId: String): PendingApprovalEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PendingApprovalEntity)

    @Query("DELETE FROM pending_approvals WHERE approvalId = :approvalId")
    suspend fun delete(approvalId: String): Int

    @Query("SELECT * FROM pending_approvals WHERE expiresAtEpochMillis <= :nowEpochMillis")
    suspend fun expired(nowEpochMillis: Long): List<PendingApprovalEntity>

    @Query("DELETE FROM pending_approvals WHERE expiresAtEpochMillis <= :nowEpochMillis")
    suspend fun deleteExpired(nowEpochMillis: Long): Int
}
