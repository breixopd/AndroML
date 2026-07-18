package dev.androml.core.workflow

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowExecutorTest {
    @Test
    fun valueCodecRoundTripsEveryCheckpointValueKind() {
        val values = listOf(
            WorkflowValue.UnitValue,
            WorkflowValue.Text(""),
            WorkflowValue.JsonValue(buildJsonObject { put("kind", "json") }),
            WorkflowValue.Documents(listOf(WorkflowDocument("Note", "local", "text"))),
            WorkflowValue.BooleanValue(true),
        )

        values.forEach { value ->
            assertEquals(value, WorkflowValueCodec.decode(WorkflowValueCodec.encode(value)))
        }
    }

    @Test
    fun executesModelAndOutputAndPersistsTypedCheckpoint() = runBlocking {
        val input = InputNode(NodeId.parse("input"), WorkflowValueType.Text)
        val model = ModelNode(NodeId.parse("model"), modelKey = "demo", workload = "text")
        val output = OutputNode(NodeId.parse("output"))
        val definition = WorkflowDefinition(
            id = WorkflowId.parse("basic"),
            version = 1,
            entry = input.id,
            nodes = listOf(input, model, output),
            edges = listOf(
                WorkflowEdge(input.id, model.id),
                WorkflowEdge(model.id, output.id),
            ),
        )
        val events = InMemoryDurableWorkflowEventStore()
        val checkpoints = InMemoryWorkflowCheckpointStore()

        val result = WorkflowExecutor(
            eventStore = events,
            checkpointStore = checkpoints,
            availableModels = setOf("demo"),
            runners = WorkflowNodeRunners(
                model = { _, value ->
                    WorkflowValue.Text("answer:${(value as WorkflowValue.Text).value}")
                },
            ),
        ).execute(
            runId = RunId.parse("run-basic"),
            definition = definition,
            input = WorkflowValue.Text("question"),
        )

        assertEquals(WorkflowRunStatus.Completed, result.status)
        assertEquals(WorkflowValue.Text("answer:question"), result.output)
        assertTrue(events.read(RunId.parse("run-basic")).any { it.event is WorkflowEvent.Checkpoint })
        assertEquals(3, checkpoints.list(RunId.parse("run-basic")).size)
    }

    @Test
    fun waitsForApprovalThenResumesWithoutRestartingInput() = runBlocking {
        val input = InputNode(NodeId.parse("input"), WorkflowValueType.Text)
        val approval = ApprovalNode(NodeId.parse("approval"), requiredScopes = setOf("tools.write"))
        val output = OutputNode(NodeId.parse("output"))
        val definition = WorkflowDefinition(
            id = WorkflowId.parse("approval"),
            version = 1,
            entry = input.id,
            nodes = listOf(input, approval, output),
            edges = listOf(
                WorkflowEdge(input.id, approval.id),
                WorkflowEdge(approval.id, output.id),
            ),
        )
        val approved = AtomicBoolean(false)
        val executor = WorkflowExecutor(
            eventStore = InMemoryDurableWorkflowEventStore(),
            checkpointStore = InMemoryWorkflowCheckpointStore(),
            runners = WorkflowNodeRunners(
                approval = { _, _ ->
                    if (approved.get()) WorkflowApprovalDecision.Approved else WorkflowApprovalDecision.Pending
                },
            ),
        )
        val runId = RunId.parse("run-approval")

        val waiting = executor.execute(definition = definition, runId = runId, input = WorkflowValue.Text("safe"))
        assertEquals(WorkflowRunStatus.WaitingForApproval, waiting.status)
        assertEquals(approval.id, waiting.waitingForNode)

        approved.set(true)
        val completed = executor.execute(definition = definition, runId = runId, input = WorkflowValue.Text("safe"))
        assertEquals(WorkflowRunStatus.Completed, completed.status)
        assertEquals(WorkflowValue.Text("safe"), completed.output)
    }

    @Test
    fun resumesFromCheckpointWithoutRepeatingNodeAfterProcessFailure() = runBlocking {
        val input = InputNode(NodeId.parse("input"), WorkflowValueType.Text)
        val model = ModelNode(NodeId.parse("model"), modelKey = "demo", workload = "text")
        val output = OutputNode(NodeId.parse("output"))
        val definition = WorkflowDefinition(
            id = WorkflowId.parse("resume"),
            version = 1,
            entry = input.id,
            nodes = listOf(input, model, output),
            edges = listOf(
                WorkflowEdge(input.id, model.id),
                WorkflowEdge(model.id, output.id),
            ),
        )
        val eventStore = CrashAfterCheckpointStore()
        val checkpoints = InMemoryWorkflowCheckpointStore()
        val calls = AtomicInteger(0)
        val executor = WorkflowExecutor(
            eventStore = eventStore,
            checkpointStore = checkpoints,
            availableModels = setOf("demo"),
            runners = WorkflowNodeRunners(
                model = { _, _ ->
                    calls.incrementAndGet()
                    WorkflowValue.Text("once")
                },
            ),
        )
        val runId = RunId.parse("run-resume")

        try {
            executor.execute(definition = definition, runId = runId, input = WorkflowValue.Text("go"))
        } catch (_: SimulatedProcessDeath) {
            // The checkpoint was durably written before the simulated process death.
        }

        val result = executor.execute(definition = definition, runId = runId, input = WorkflowValue.Text("go"))
        assertEquals(WorkflowRunStatus.Completed, result.status)
        assertEquals(WorkflowValue.Text("once"), result.output)
        assertEquals(1, calls.get())
    }

    @Test
    fun followsFalseBranchUsingStableEdgeOrder() = runBlocking {
        val input = InputNode(NodeId.parse("input"), WorkflowValueType.Boolean)
        val branch = BranchNode(NodeId.parse("branch"))
        val falseOutput = OutputNode(NodeId.parse("false-output"))
        val trueOutput = OutputNode(NodeId.parse("true-output"))
        val definition = WorkflowDefinition(
            id = WorkflowId.parse("branch"),
            version = 1,
            entry = input.id,
            nodes = listOf(input, branch, falseOutput, trueOutput),
            edges = listOf(
                WorkflowEdge(input.id, branch.id),
                WorkflowEdge(branch.id, trueOutput.id),
                WorkflowEdge(branch.id, falseOutput.id),
            ),
        )

        val result = WorkflowExecutor(
            eventStore = InMemoryDurableWorkflowEventStore(),
            checkpointStore = InMemoryWorkflowCheckpointStore(),
        ).execute(
            runId = RunId.parse("run-branch"),
            definition = definition,
            input = WorkflowValue.BooleanValue(false),
        )

        assertEquals(WorkflowRunStatus.Completed, result.status)
        assertEquals(WorkflowValue.BooleanValue(false), result.output)
        assertEquals(falseOutput.id, result.completedOutputNode)
    }

    private class CrashAfterCheckpointStore : DurableWorkflowEventStore {
        private val delegate = InMemoryDurableWorkflowEventStore()
        private var shouldCrash = true

        override suspend fun append(
            runId: RunId,
            expectedSequence: Long,
            events: List<WorkflowEvent>,
        ): List<StoredWorkflowEvent> {
            val stored = delegate.append(runId, expectedSequence, events)
            if (shouldCrash && events.any { it is WorkflowEvent.Checkpoint }) {
                shouldCrash = false
                throw SimulatedProcessDeath()
            }
            return stored
        }

        override suspend fun read(runId: RunId): List<StoredWorkflowEvent> = delegate.read(runId)
    }

    private class SimulatedProcessDeath : IOException("simulated process death")
}
