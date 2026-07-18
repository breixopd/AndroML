package dev.androml.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "model_files",
    primaryKeys = ["modelId", "revision", "path"],
    foreignKeys = [
        ForeignKey(
            entity = ModelRecordEntity::class,
            parentColumns = ["modelId", "revision"],
            childColumns = ["modelId", "revision"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["modelId", "revision"])],
)
data class ModelFileEntity(
    val modelId: String,
    val revision: String,
    val path: String,
    val sizeBytes: Long,
    val sha256: String?,
    val artifactSha256: String? = null,
    val downloadedAtEpochMillis: Long? = null,
)
