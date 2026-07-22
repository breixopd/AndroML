package dev.androml.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RuntimeBenchmarkDao {
    @Query(
        "SELECT * FROM runtime_benchmarks " +
            "WHERE deviceKey = :deviceKey AND modelArtifactSha256 = :modelArtifactSha256 " +
            "ORDER BY profile ASC, runtimeId ASC",
    )
    fun observe(deviceKey: String, modelArtifactSha256: String): Flow<List<RuntimeBenchmarkEntity>>

    @Query(
        "SELECT * FROM runtime_benchmarks " +
            "WHERE deviceKey = :deviceKey AND modelArtifactSha256 = :modelArtifactSha256",
    )
    suspend fun snapshot(deviceKey: String, modelArtifactSha256: String): List<RuntimeBenchmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RuntimeBenchmarkEntity)

    @Query("DELETE FROM runtime_benchmarks WHERE deviceKey = :deviceKey")
    suspend fun deleteForDevice(deviceKey: String)
}
