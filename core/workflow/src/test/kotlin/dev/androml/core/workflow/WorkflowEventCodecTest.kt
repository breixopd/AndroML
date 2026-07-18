package dev.androml.core.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowEventCodecTest {
    @Test
    fun roundTripsEveryEventVariant() {
        val run = RunId.parse("run-1")
        val events = listOf(
            WorkflowEvent.Started(run, "start", WorkflowId.parse("research"), 2, 123L),
            WorkflowEvent.NodeStarted(run, "node-start", NodeId.parse("model"), 1),
            WorkflowEvent.Checkpoint(run, "checkpoint", NodeId.parse("model"), 1, "hash"),
            WorkflowEvent.NodeCompleted(run, "node-done", NodeId.parse("model"), 1),
            WorkflowEvent.NodeFailed(run, "node-failed", NodeId.parse("model"), 2, "safe", true),
            WorkflowEvent.ApprovalRequested(run, "approval", NodeId.parse("approve"), "approval-1"),
            WorkflowEvent.StatusChanged(run, "status", WorkflowRunStatus.WaitingForApproval),
        )

        events.forEach { event ->
            val encoded = WorkflowEventCodec.encode(event)
            assertTrue(encoded.payload.length <= WorkflowEventCodec.MAX_PAYLOAD_CHARS)
            assertEquals(event, WorkflowEventCodec.decode(run, encoded.eventType, encoded.payload))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownEventTypes() {
        WorkflowEventCodec.decode(RunId.parse("run-1"), "unknown", "{}")
    }
}
