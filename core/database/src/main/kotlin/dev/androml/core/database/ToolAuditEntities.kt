package dev.androml.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Hash-only audit records; tool arguments and results are deliberately never persisted here. */
@Entity(
    tableName = "tool_audit_events",
    indices = [Index(value = ["occurredAtEpochMillis"])],
)
data class ToolAuditEntity(
    @androidx.room.PrimaryKey val eventId: String,
    val eventType: String,
    val toolId: String,
    val sideEffect: String,
    val argumentHash: String,
    val resultHash: String?,
    val success: Boolean,
    val occurredAtEpochMillis: Long,
)

@Dao
interface ToolAuditDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: ToolAuditEntity)

    @Query(
        "SELECT * FROM tool_audit_events ORDER BY occurredAtEpochMillis DESC LIMIT :limit",
    )
    suspend fun recent(limit: Int): List<ToolAuditEntity>

    @Query("DELETE FROM tool_audit_events WHERE occurredAtEpochMillis < :cutoffEpochMillis")
    suspend fun deleteBefore(cutoffEpochMillis: Long)
}
