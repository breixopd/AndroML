package dev.androml.core.database

import dev.androml.core.workflow.InputNode
import dev.androml.core.workflow.NodeId
import dev.androml.core.workflow.OutputNode
import dev.androml.core.workflow.WorkflowDefinition
import dev.androml.core.workflow.WorkflowEdge
import dev.androml.core.workflow.WorkflowId
import dev.androml.core.workflow.WorkflowValueType
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkflowDefinitionStorageMapperTest {
    @Test
    fun mapperPreservesDefinitionIdentityAndPayload() {
        val input = InputNode(NodeId.parse("input"), WorkflowValueType.Text)
        val output = OutputNode(NodeId.parse("output"))
        val definition = WorkflowDefinition(
            id = WorkflowId.parse("mapper"),
            version = 2,
            entry = input.id,
            nodes = listOf(input, output),
            edges = listOf(WorkflowEdge(input.id, output.id)),
        )

        val entity = WorkflowDefinitionStorageMapper.toEntity(definition, 10L, 20L)
        assertEquals(definition, WorkflowDefinitionStorageMapper.toDomain(entity))
        assertEquals(10L, entity.createdAtEpochMillis)
        assertEquals(20L, entity.updatedAtEpochMillis)
    }
}
