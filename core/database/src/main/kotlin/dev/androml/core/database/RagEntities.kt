package dev.androml.core.database

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index

@Entity(tableName = "rag_collections")
data class RagCollectionEntity(
    @androidx.room.PrimaryKey val collectionId: String,
    val displayName: String,
    val embeddingModelKey: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "rag_documents",
    primaryKeys = ["collectionId", "documentId"],
    indices = [Index(value = ["collectionId", "updatedAtEpochMillis"])],
)
data class RagDocumentEntity(
    val collectionId: String,
    val documentId: String,
    val title: String,
    val sourceLabel: String,
    val contentSha256: String,
    val contentArtifactSha256: String,
    val byteSize: Long,
    val status: String,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "rag_chunks",
    primaryKeys = ["collectionId", "documentId", "chunkId"],
    indices = [
        Index(value = ["collectionId", "documentId"]),
        Index(value = ["collectionId", "ordinal"]),
    ],
)
data class RagChunkEntity(
    val collectionId: String,
    val documentId: String,
    val chunkId: String,
    val title: String,
    val sourceLabel: String,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val page: Int?,
    val section: String?,
    val ordinal: Int,
)

/** FTS4 deliberately keeps only bounded chunk fields; document bodies remain content-addressed files. */
@Fts4(
    tokenizer = "unicode61",
    notIndexed = ["collectionId", "documentId", "chunkId", "title", "sourceLabel", "startOffset", "endOffset", "page", "section", "ordinal"],
)
@Entity(tableName = "rag_chunk_search")
data class RagChunkSearchEntity(
    val collectionId: String,
    val documentId: String,
    val chunkId: String,
    val title: String,
    val sourceLabel: String,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val page: Int?,
    val section: String?,
    val ordinal: Int,
)
