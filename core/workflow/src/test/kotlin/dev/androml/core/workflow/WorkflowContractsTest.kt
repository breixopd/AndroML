package dev.androml.core.workflow

import dev.androml.core.tools.ToolDescriptor
import dev.androml.core.tools.ToolId
import dev.androml.core.tools.ToolInputSchema
import dev.androml.core.tools.ToolSideEffect
import dev.androml.core.tools.ToolValueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowContractsTest {
    private val tool = ToolDescriptor(
        id = ToolId.parse("calculator"),
        displayName = "Calculator",
        description = "Calculate a bounded expression.",
        sideEffect = ToolSideEffect.Read,
        requiredScopes = emptySet(),
        input = ToolInputSchema(
            properties = mapOf("expression" to dev.androml.core.tools.ToolProperty(ToolValueType.String)),
            required = setOf("expression"),
        ),
    )

    @Test
    fun validWorkflowWithToolAndOutputPasses() {
        val entry = InputNode(NodeId.parse("input"), WorkflowValueType.Json)
        val toolNode = ToolNode(NodeId.parse("calc"), tool.id)
        val output = OutputNode(NodeId.parse("output"))
        val definition = WorkflowDefinition(
            id = WorkflowId.parse("research"),
            version = 1,
            entry = entry.id,
            nodes = listOf(entry, toolNode, output),
            edges = listOf(
                WorkflowEdge(entry.id, toolNode.id),
                WorkflowEdge(toolNode.id, output.id),
            ),
        )

        val result = WorkflowValidator(
            availableTools = mapOf(tool.id to tool),
            availableModels = emptySet(),
        ).validate(definition)

        assertTrue(result.isValid)
    }

    @Test
    fun rejectsUnknownToolsTypeMismatchAndUnreachableNodes() {
        val entry = InputNode(NodeId.parse("input"), WorkflowValueType.Text)
        val unknownTool = ToolNode(NodeId.parse("calc"), ToolId.parse("missing"))
        val orphan = OutputNode(NodeId.parse("orphan"))
        val definition = WorkflowDefinition(
            id = WorkflowId.parse("invalid"),
            version = 1,
            entry = entry.id,
            nodes = listOf(entry, unknownTool, orphan),
            edges = listOf(WorkflowEdge(entry.id, unknownTool.id)),
        )

        val result = WorkflowValidator().validate(definition)

        assertFalse(result.isValid)
        assertTrue(result.issues.any { it.code == WorkflowValidationCode.UnknownTool })
        assertTrue(result.issues.any { it.code == WorkflowValidationCode.TypeMismatch })
        assertTrue(result.issues.any { it.code == WorkflowValidationCode.UnreachableNode })
    }

    @Test
    fun boundedLoopMayContainAClosedCycleButPlainCycleIsRejected() {
        val input = InputNode(NodeId.parse("input"), WorkflowValueType.Any)
        val loop = LoopNode(NodeId.parse("loop"), maxIterations = 3)
        val output = OutputNode(NodeId.parse("output"))
        val bounded = WorkflowDefinition(
            id = WorkflowId.parse("bounded"),
            version = 1,
            entry = input.id,
            nodes = listOf(input, loop, output),
            edges = listOf(
                WorkflowEdge(input.id, loop.id),
                WorkflowEdge(loop.id, loop.id),
                WorkflowEdge(loop.id, output.id),
            ),
        )
        assertFalse(WorkflowValidator().validate(bounded).issues.any {
            it.code == WorkflowValidationCode.UnboundedCycle
        })

        val plain = InputNode(NodeId.parse("plain"), WorkflowValueType.Any)
        val plainDefinition = WorkflowDefinition(
            id = WorkflowId.parse("plain"),
            version = 1,
            entry = plain.id,
            nodes = listOf(plain),
            edges = listOf(WorkflowEdge(plain.id, plain.id)),
        )
        assertTrue(WorkflowValidator().validate(plainDefinition).issues.any {
            it.code == WorkflowValidationCode.UnboundedCycle
        })
    }

    @Test
    fun eventStoreIsIdempotentAndDetectsConcurrentAppend() {
        val run = RunId.parse("run-1")
        val first = WorkflowEvent.StatusChanged(run, "status-1", WorkflowRunStatus.Running)
        val store = InMemoryWorkflowEventStore()
        assertEquals(1, store.append(run, 0L, listOf(first)).size)
        assertEquals(0, store.append(run, 0L, listOf(first)).size)
        assertEquals(1L, store.read(run).single().sequence)

        try {
            store.append(
                run,
                0L,
                listOf(WorkflowEvent.StatusChanged(run, "status-2", WorkflowRunStatus.Completed)),
            )
            throw AssertionError("expected concurrency failure")
        } catch (_: WorkflowConcurrencyException) {
            // expected
        }
    }
}
