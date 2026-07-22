package dev.androml.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ModelCatalogDao {
    @Query("SELECT * FROM model_records ORDER BY observedAtEpochMillis DESC, modelId ASC, revision ASC")
    abstract fun observeModels(): Flow<List<ModelRecordEntity>>

    @Query("SELECT * FROM model_records ORDER BY observedAtEpochMillis DESC, modelId ASC, revision ASC")
    abstract suspend fun listModels(): List<ModelRecordEntity>

    @Query("SELECT * FROM model_files ORDER BY modelId ASC, revision ASC, path ASC")
    abstract fun observeAllFiles(): Flow<List<ModelFileEntity>>

    @Query("SELECT * FROM model_files WHERE artifactSha256 = :artifactSha256 LIMIT 1")
    abstract suspend fun fileForArtifact(artifactSha256: String): ModelFileEntity?

    @Query(
        "SELECT * FROM model_files " +
            "WHERE modelId || '@' || revision = :modelKey AND artifactSha256 IS NOT NULL " +
            "ORDER BY CASE WHEN path LIKE '%.litertlm' THEN 0 WHEN path LIKE '%.tflite' THEN 1 ELSE 2 END, path ASC LIMIT 1",
    )
    abstract suspend fun fileForModelKey(modelKey: String): ModelFileEntity?

    @Query("SELECT artifactSha256 FROM model_files WHERE artifactSha256 IS NOT NULL")
    abstract suspend fun listVerifiedArtifactHashes(): List<String>

    @Query(
        "SELECT DISTINCT modelId || '@' || revision FROM model_files " +
            "WHERE artifactSha256 IS NOT NULL AND (path LIKE '%.litertlm' OR path LIKE '%.tflite' OR path LIKE '%.onnx' OR path LIKE '%.ort') " +
            "ORDER BY modelId ASC, revision ASC",
    )
    abstract suspend fun listRunnableModelKeys(): List<String>

    @Query(
        "SELECT * FROM model_files " +
            "WHERE modelId = :modelId AND revision = :revision " +
            "ORDER BY path ASC",
    )
    abstract fun observeFiles(modelId: String, revision: String): Flow<List<ModelFileEntity>>

    @Query("SELECT * FROM model_files WHERE modelId = :modelId AND revision = :revision")
    protected abstract suspend fun filesForRevision(
        modelId: String,
        revision: String,
    ): List<ModelFileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertModel(model: ModelRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertFiles(files: List<ModelFileEntity>)

    @Query("DELETE FROM model_files WHERE modelId = :modelId AND revision = :revision")
    protected abstract suspend fun deleteFiles(modelId: String, revision: String)

    @Query(
        "UPDATE model_files SET artifactSha256 = :artifactSha256, " +
            "downloadedAtEpochMillis = :downloadedAtEpochMillis " +
            "WHERE modelId = :modelId AND revision = :revision AND path = :path " +
            "AND sha256 = :artifactSha256",
    )
    abstract suspend fun markArtifactVerified(
        modelId: String,
        revision: String,
        path: String,
        artifactSha256: String,
        downloadedAtEpochMillis: Long,
    ): Int

    @Transaction
    open suspend fun replaceRepository(snapshot: ModelCatalogSnapshot) {
        val existingFiles = filesForRevision(
            modelId = snapshot.model.modelId,
            revision = snapshot.model.revision,
        ).associateBy { it.path }
        val filesWithExistingArtifacts = snapshot.files.map { file ->
            val existing = existingFiles[file.path]
            file.copy(
                artifactSha256 = existing?.artifactSha256,
                downloadedAtEpochMillis = existing?.downloadedAtEpochMillis,
            )
        }
        upsertModel(snapshot.model)
        deleteFiles(snapshot.model.modelId, snapshot.model.revision)
        insertFiles(filesWithExistingArtifacts)
    }
}
