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
        RagVectorEntity::class,
        ApiKeyEntity::class,
        WorkflowEventEntity::class,
        WorkflowCheckpointEntity::class,
        WorkflowDefinitionEntity::class,
        ClusterPeerEntity::class,
        ClusterJobAttemptEntity::class,
        RuntimeBenchmarkEntity::class,
    ],
    version = 11,
    exportSchema = true,
)
abstract class AndroMlDatabase : RoomDatabase() {
    abstract fun modelCatalogDao(): ModelCatalogDao

    abstract fun ragDao(): RagDao

    abstract fun apiKeyDao(): ApiKeyDao

    abstract fun workflowEventDao(): WorkflowEventDao

    abstract fun workflowCheckpointDao(): WorkflowCheckpointDao

    abstract fun workflowDefinitionDao(): WorkflowDefinitionDao

    abstract fun clusterPeerDao(): ClusterPeerDao

    abstract fun clusterJobAttemptDao(): ClusterJobAttemptDao

    abstract fun runtimeBenchmarkDao(): RuntimeBenchmarkDao

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

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS workflow_checkpoints (
                        runId TEXT NOT NULL,
                        nodeId TEXT NOT NULL,
                        attempt INTEGER NOT NULL,
                        outputHash TEXT NOT NULL,
                        valuePayload TEXT NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(runId, nodeId, attempt)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cluster_peers (
                        peerId TEXT NOT NULL PRIMARY KEY,
                        fingerprint TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        host TEXT NOT NULL,
                        port INTEGER NOT NULL,
                        pairedAtEpochMillis INTEGER NOT NULL,
                        certificateExpiresAtEpochMillis INTEGER NOT NULL,
                        paired INTEGER NOT NULL,
                        revoked INTEGER NOT NULL,
                        certificateDer BLOB NOT NULL,
                        protocolMajor INTEGER NOT NULL,
                        protocolMinor INTEGER NOT NULL,
                        supportedWorkloads TEXT NOT NULL,
                        modelHashes TEXT NOT NULL,
                        maxConcurrentJobs INTEGER NOT NULL,
                        availableRamBytes INTEGER NOT NULL,
                        queueDepth INTEGER NOT NULL,
                        thermalSeverity INTEGER NOT NULL,
                        batteryPercent INTEGER NOT NULL,
                        charging INTEGER NOT NULL,
                        lastSeenEpochMillis INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS workflow_definitions (
                        workflowId TEXT NOT NULL,
                        version INTEGER NOT NULL,
                        payload TEXT NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(workflowId, version)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS runtime_benchmarks (
                        deviceKey TEXT NOT NULL,
                        runtimeId TEXT NOT NULL,
                        modelArtifactSha256 TEXT NOT NULL,
                        profile TEXT NOT NULL,
                        tokensPerSecond REAL NOT NULL,
                        firstTokenLatencyMs REAL,
                        outputValid INTEGER NOT NULL,
                        failureCount INTEGER NOT NULL,
                        measuredAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(deviceKey, runtimeId, modelArtifactSha256, profile)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_runtime_benchmarks_deviceKey_modelArtifactSha256 " +
                        "ON runtime_benchmarks(deviceKey, modelArtifactSha256)",
                )
            }
        }

        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS rag_chunk_vectors (
                        collectionId TEXT NOT NULL,
                        documentId TEXT NOT NULL,
                        chunkId TEXT NOT NULL,
                        modelKey TEXT NOT NULL,
                        dimension INTEGER NOT NULL,
                        vector BLOB NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(collectionId, documentId, chunkId, modelKey)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_rag_chunk_vectors_collectionId_modelKey " +
                        "ON rag_chunk_vectors(collectionId, modelKey)",
                )
            }
        }

        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cluster_job_attempts (
                        jobId TEXT NOT NULL,
                        attempt INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        outputHash TEXT,
                        output BLOB,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(jobId, attempt)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE cluster_job_attempts ADD COLUMN leaseExpiresAtEpochMillis INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        fun open(context: Context): AndroMlDatabase = Room.databaseBuilder(
            context.applicationContext,
            AndroMlDatabase::class.java,
            "androml.db",
        ).addMigrations(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
        ).build()
    }
}
