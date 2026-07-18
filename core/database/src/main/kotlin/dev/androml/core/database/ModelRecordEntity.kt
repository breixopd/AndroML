package dev.androml.core.database

import androidx.room.Entity

@Entity(
    tableName = "model_records",
    primaryKeys = ["modelId", "revision"],
)
data class ModelRecordEntity(
    val modelId: String,
    val revision: String,
    val isPrivate: Boolean,
    val isGated: Boolean,
    val license: String?,
    val observedAtEpochMillis: Long,
)
