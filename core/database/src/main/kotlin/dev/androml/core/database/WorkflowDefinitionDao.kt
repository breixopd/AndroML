package dev.androml.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkflowDefinitionDao {
    @Query("SELECT * FROM workflow_definitions ORDER BY workflowId ASC, version DESC")
    fun observe(): Flow<List<WorkflowDefinitionEntity>>

    @Query("SELECT * FROM workflow_definitions ORDER BY workflowId ASC, version DESC")
    suspend fun list(): List<WorkflowDefinitionEntity>

    @Query("SELECT * FROM workflow_definitions WHERE workflowId = :workflowId AND version = :version")
    suspend fun find(workflowId: String, version: Int): WorkflowDefinitionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WorkflowDefinitionEntity)
}
