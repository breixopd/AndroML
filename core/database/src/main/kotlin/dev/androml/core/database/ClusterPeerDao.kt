package dev.androml.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ClusterPeerDao {
    @Query("SELECT * FROM cluster_peers ORDER BY displayName ASC, peerId ASC")
    fun observe(): Flow<List<ClusterPeerEntity>>

    @Query("SELECT * FROM cluster_peers ORDER BY displayName ASC, peerId ASC")
    suspend fun list(): List<ClusterPeerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ClusterPeerEntity)

    @Query("UPDATE cluster_peers SET revoked = 1, paired = 0 WHERE peerId = :peerId")
    suspend fun revoke(peerId: String): Int

    @Query("DELETE FROM cluster_peers WHERE peerId = :peerId")
    suspend fun delete(peerId: String): Int
}
