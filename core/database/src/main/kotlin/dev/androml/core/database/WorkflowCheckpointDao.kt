package dev.androml.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.androml.core.workflow.NodeId
import dev.androml.core.workflow.RunId
import dev.androml.core.workflow.WorkflowCheckpoint
import dev.androml.core.workflow.WorkflowCheckpointStore
import dev.androml.core.workflow.WorkflowValueCodec

@Dao
abstract class WorkflowCheckpointDao : WorkflowCheckpointStore {
    @Query(
        "SELECT * FROM workflow_checkpoints " +
            "WHERE runId = :runId AND nodeId = :nodeId AND attempt = :attempt",
    )
    protected abstract suspend fun findEntity(
        runId: String,
        nodeId: String,
        attempt: Int,
    ): WorkflowCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertEntity(entity: WorkflowCheckpointEntity)

    override suspend fun save(checkpoint: WorkflowCheckpoint) {
        insertEntity(WorkflowCheckpointStorageMapper.toEntity(checkpoint))
    }

    override suspend fun load(runId: RunId, nodeId: NodeId, attempt: Int): WorkflowCheckpoint? =
        findEntity(runId.value, nodeId.value, attempt)?.let(WorkflowCheckpointStorageMapper::toDomain)
}

object WorkflowCheckpointStorageMapper {
    fun toEntity(checkpoint: WorkflowCheckpoint): WorkflowCheckpointEntity = WorkflowCheckpointEntity(
        runId = checkpoint.runId.value,
        nodeId = checkpoint.nodeId.value,
        attempt = checkpoint.attempt,
        outputHash = checkpoint.outputHash,
        valuePayload = WorkflowValueCodec.encode(checkpoint.value),
        updatedAtEpochMillis = System.currentTimeMillis(),
    )

    fun toDomain(entity: WorkflowCheckpointEntity): WorkflowCheckpoint = WorkflowCheckpoint(
        runId = RunId.parse(entity.runId),
        nodeId = NodeId.parse(entity.nodeId),
        attempt = entity.attempt,
        outputHash = entity.outputHash,
        value = WorkflowValueCodec.decode(entity.valuePayload),
    )
}
