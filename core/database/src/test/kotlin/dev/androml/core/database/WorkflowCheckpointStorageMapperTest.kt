package dev.androml.core.database

import dev.androml.core.workflow.NodeId
import dev.androml.core.workflow.RunId
import dev.androml.core.workflow.WorkflowCheckpoint
import dev.androml.core.workflow.WorkflowValue
import dev.androml.core.workflow.WorkflowValueCodec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkflowCheckpointStorageMapperTest {
    @Test
    fun preservesHashAndTypedJsonValueAcrossRoomMapping() {
        val value = WorkflowValue.JsonValue(
            buildJsonObject {
                put("answer", "local")
                put("confidence", 0.9)
            },
        )
        val checkpoint = WorkflowCheckpoint(
            runId = RunId.parse("run-storage"),
            nodeId = NodeId.parse("tool"),
            attempt = 2,
            outputHash = WorkflowValueCodec.hash(value),
            value = value,
        )

        val restored = WorkflowCheckpointStorageMapper.toDomain(
            WorkflowCheckpointStorageMapper.toEntity(checkpoint),
        )

        assertEquals(checkpoint, restored)
    }

    @Test
    fun rejectsPayloadWhoseHashDoesNotMatch() {
        val value = WorkflowValue.Text("original")
        val checkpoint = WorkflowCheckpoint(
            runId = RunId.parse("run-storage"),
            nodeId = NodeId.parse("model"),
            attempt = 1,
            outputHash = WorkflowValueCodec.hash(value),
            value = value,
        )
        val entity = WorkflowCheckpointStorageMapper.toEntity(checkpoint).copy(
            valuePayload = WorkflowValueCodec.encode(WorkflowValue.Text("tampered")),
        )

        assertThrows(IllegalArgumentException::class.java) {
            WorkflowCheckpointStorageMapper.toDomain(entity)
        }
    }
}
