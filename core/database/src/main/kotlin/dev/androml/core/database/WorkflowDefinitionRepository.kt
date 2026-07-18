package dev.androml.core.database

import dev.androml.core.workflow.WorkflowDefinition
import dev.androml.core.workflow.WorkflowDefinitionCodec
import dev.androml.core.workflow.WorkflowId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class WorkflowDefinitionRepository(
    private val dao: WorkflowDefinitionDao,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) {
    fun observe(): Flow<List<WorkflowDefinition>> = dao.observe().map { entities ->
        entities.map(WorkflowDefinitionStorageMapper::toDomain)
    }

    suspend fun snapshot(): List<WorkflowDefinition> = dao.list().map(WorkflowDefinitionStorageMapper::toDomain)

    suspend fun load(workflowId: WorkflowId, version: Int): WorkflowDefinition? =
        dao.find(workflowId.value, version)?.let(WorkflowDefinitionStorageMapper::toDomain)

    suspend fun save(definition: WorkflowDefinition) {
        val now = nowEpochMillis()
        val existing = dao.find(definition.id.value, definition.version)
        dao.upsert(
            WorkflowDefinitionStorageMapper.toEntity(
                definition = definition,
                createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = now,
            ),
        )
    }
}

object WorkflowDefinitionStorageMapper {
    fun toEntity(
        definition: WorkflowDefinition,
        createdAtEpochMillis: Long,
        updatedAtEpochMillis: Long,
    ): WorkflowDefinitionEntity = WorkflowDefinitionEntity(
        workflowId = definition.id.value,
        version = definition.version,
        payload = WorkflowDefinitionCodec.encode(definition),
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    fun toDomain(entity: WorkflowDefinitionEntity): WorkflowDefinition {
        val definition = WorkflowDefinitionCodec.decode(entity.payload)
        require(definition.id.value == entity.workflowId && definition.version == entity.version) {
            "persisted workflow definition identity does not match its row"
        }
        return definition
    }
}
