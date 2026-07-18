package dev.androml.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ModelRecordEntity::class,
        ModelFileEntity::class,
        RagCollectionEntity::class,
        RagDocumentEntity::class,
        RagChunkEntity::class,
        RagChunkSearchEntity::class,
        ApiKeyEntity::class,
        WorkflowEventEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AndroMlDatabase : RoomDatabase() {
    abstract fun modelCatalogDao(): ModelCatalogDao

    abstract fun ragDao(): RagDao

    abstract fun apiKeyDao(): ApiKeyDao

    abstract fun workflowEventDao(): WorkflowEventDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS rag_collections (
                        collectionId TEXT NOT NULL PRIMARY KEY,
                        displayName TEXT NOT NULL,
                        embeddingModelKey TEXT,
                        createdAtEpochMillis INTEGER NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS rag_documents (
                        collectionId TEXT NOT NULL,
                        documentId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        sourceLabel TEXT NOT NULL,
                        contentSha256 TEXT NOT NULL,
                        contentArtifactSha256 TEXT NOT NULL,
                        byteSize INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(collectionId, documentId)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS rag_chunks (
                        collectionId TEXT NOT NULL,
                        documentId TEXT NOT NULL,
                        chunkId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        sourceLabel TEXT NOT NULL,
                        text TEXT NOT NULL,
                        startOffset INTEGER NOT NULL,
                        endOffset INTEGER NOT NULL,
                        page INTEGER,
                        section TEXT,
                        ordinal INTEGER NOT NULL,
                        PRIMARY KEY(collectionId, documentId, chunkId)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS rag_chunk_search USING FTS4(" +
                        "`collectionId` TEXT NOT NULL, `documentId` TEXT NOT NULL, " +
                        "`chunkId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`sourceLabel` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                        "`startOffset` INTEGER NOT NULL, `endOffset` INTEGER NOT NULL, " +
                        "`page` INTEGER, `section` TEXT, `ordinal` INTEGER NOT NULL, " +
                        "tokenize=unicode61, notindexed=`collectionId`, " +
                        "notindexed=`documentId`, notindexed=`chunkId`, notindexed=`title`, " +
                        "notindexed=`sourceLabel`, notindexed=`startOffset`, " +
                        "notindexed=`endOffset`, notindexed=`page`, notindexed=`section`, " +
                        "notindexed=`ordinal`)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_rag_documents_collectionId_updatedAtEpochMillis ON rag_documents(collectionId, updatedAtEpochMillis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_rag_chunks_collectionId_documentId ON rag_chunks(collectionId, documentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_rag_chunks_collectionId_ordinal ON rag_chunks(collectionId, ordinal)")
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS api_keys (
                        id TEXT NOT NULL PRIMARY KEY,
                        displayName TEXT NOT NULL,
                        tokenHash TEXT NOT NULL,
                        scopes TEXT NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        expiresAtEpochMillis INTEGER,
                        revokedAtEpochMillis INTEGER,
                        lastUsedAtEpochMillis INTEGER
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS workflow_events (
                        runId TEXT NOT NULL,
                        sequence INTEGER NOT NULL,
                        idempotencyKey TEXT NOT NULL,
                        eventType TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        appendedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(runId, sequence)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_workflow_events_runId_idempotencyKey " +
                        "ON workflow_events(runId, idempotencyKey)",
                )
            }
        }

        fun open(context: Context): AndroMlDatabase = Room.databaseBuilder(
            context.applicationContext,
            AndroMlDatabase::class.java,
            "androml.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    }
}
