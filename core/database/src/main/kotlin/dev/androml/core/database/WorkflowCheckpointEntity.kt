package dev.androml.core.database

import androidx.room.Entity

@Entity(
    tableName = "workflow_checkpoints",
    primaryKeys = ["runId", "nodeId", "attempt"],
)
data class WorkflowCheckpointEntity(
    val runId: String,
    val nodeId: String,
    val attempt: Int,
    val outputHash: String,
    val valuePayload: String,
    val updatedAtEpochMillis: Long,
)
