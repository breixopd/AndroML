package dev.androml.core.database

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "workflow_events",
    primaryKeys = ["runId", "sequence"],
    indices = [Index(value = ["runId", "idempotencyKey"], unique = true)],
)
data class WorkflowEventEntity(
    val runId: String,
    val sequence: Long,
    val idempotencyKey: String,
    val eventType: String,
    val payload: String,
    val appendedAtEpochMillis: Long,
)
