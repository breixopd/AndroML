package dev.androml.core.database

import androidx.room.Entity

@Entity(
    tableName = "workflow_definitions",
    primaryKeys = ["workflowId", "version"],
)
data class WorkflowDefinitionEntity(
    val workflowId: String,
    val version: Int,
    val payload: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
