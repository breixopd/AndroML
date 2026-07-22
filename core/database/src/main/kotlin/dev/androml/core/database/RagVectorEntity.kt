package dev.androml.core.database

import androidx.room.Entity

@Entity(
    tableName = "rag_chunk_vectors",
    primaryKeys = ["collectionId", "documentId", "chunkId", "modelKey"],
)
data class RagVectorEntity(
    val collectionId: String,
    val documentId: String,
    val chunkId: String,
    val modelKey: String,
    val dimension: Int,
    val vector: ByteArray,
    val updatedAtEpochMillis: Long,
)
