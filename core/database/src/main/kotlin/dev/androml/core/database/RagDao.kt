package dev.androml.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class RagDao {
    @Query("SELECT * FROM rag_collections ORDER BY updatedAtEpochMillis DESC, collectionId ASC")
    abstract fun observeCollections(): Flow<List<RagCollectionEntity>>

    @Query(
        "SELECT * FROM rag_documents " +
            "WHERE collectionId = :collectionId ORDER BY updatedAtEpochMillis DESC, documentId ASC",
    )
    abstract fun observeDocuments(collectionId: String): Flow<List<RagDocumentEntity>>

    @Query(
        "SELECT * FROM rag_chunks " +
            "WHERE collectionId = :collectionId AND documentId = :documentId ORDER BY ordinal ASC",
    )
    abstract suspend fun chunksForDocument(collectionId: String, documentId: String): List<RagChunkEntity>

    @Query(
        "SELECT * FROM rag_chunks WHERE collectionId = :collectionId " +
            "ORDER BY ordinal ASC, documentId ASC LIMIT :limit",
    )
    abstract suspend fun chunksForCollection(collectionId: String, limit: Int): List<RagChunkEntity>

    @Query(
        "SELECT collectionId, documentId, chunkId, title, sourceLabel, text, " +
            "startOffset, endOffset, page, section, ordinal FROM rag_chunk_search " +
            "WHERE collectionId = :collectionId AND rag_chunk_search MATCH :matchQuery " +
            "LIMIT :limit",
    )
    abstract suspend fun search(collectionId: String, matchQuery: String, limit: Int): List<RagChunkSearchEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertCollection(collection: RagCollectionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertDocument(document: RagDocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertChunks(chunks: List<RagChunkEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertSearchRows(rows: List<RagChunkSearchEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertVectors(vectors: List<RagVectorEntity>)

    @Query("SELECT * FROM rag_chunk_vectors WHERE collectionId = :collectionId AND modelKey = :modelKey")
    abstract suspend fun vectorsForCollection(collectionId: String, modelKey: String): List<RagVectorEntity>

    @Query("DELETE FROM rag_chunks WHERE collectionId = :collectionId AND documentId = :documentId")
    protected abstract suspend fun deleteChunks(collectionId: String, documentId: String)

    @Query("DELETE FROM rag_chunk_search WHERE collectionId = :collectionId AND documentId = :documentId")
    protected abstract suspend fun deleteSearchRows(collectionId: String, documentId: String)

    @Query("DELETE FROM rag_chunk_vectors WHERE collectionId = :collectionId AND documentId = :documentId")
    protected abstract suspend fun deleteVectors(collectionId: String, documentId: String)

    @Transaction
    open suspend fun replaceDocument(snapshot: RagCatalogSnapshot) {
        upsertDocument(snapshot.document)
        deleteChunks(snapshot.document.collectionId, snapshot.document.documentId)
        deleteSearchRows(snapshot.document.collectionId, snapshot.document.documentId)
        deleteVectors(snapshot.document.collectionId, snapshot.document.documentId)
        if (snapshot.chunks.isNotEmpty()) insertChunks(snapshot.chunks)
        if (snapshot.searchRows.isNotEmpty()) insertSearchRows(snapshot.searchRows)
        if (snapshot.vectors.isNotEmpty()) insertVectors(snapshot.vectors)
    }
}
