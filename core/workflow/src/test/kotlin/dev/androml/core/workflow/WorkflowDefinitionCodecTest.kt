package dev.androml.core.workflow

import dev.androml.core.tools.ToolId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkflowDefinitionCodecTest {
    @Test
    fun definitionRoundTripsAllNodeKindsAndBudget() {
        val input = InputNode(NodeId.parse("input"), WorkflowValueType.Text)
        val model = ModelNode(NodeId.parse("model"), "model-hash", "text")
        val agent = AgentNode(NodeId.parse("agent"), "researcher")
        val rag = RagNode(NodeId.parse("rag"), "docs")
        val tool = ToolNode(NodeId.parse("tool"), ToolId.parse("web.read"))
        val branch = BranchNode(NodeId.parse("branch"))
        val loop = LoopNode(NodeId.parse("loop"), maxIterations = 3)
        val approval = ApprovalNode(NodeId.parse("approval"), setOf("tools.write"))
        val output = OutputNode(NodeId.parse("output"))
        val definition = WorkflowDefinition(
            id = WorkflowId.parse("all-nodes"),
            version = 4,
            entry = input.id,
            nodes = listOf(input, model, agent, rag, tool, branch, loop, approval, output),
            edges = listOf(WorkflowEdge(input.id, output.id)),
            budget = WorkflowBudget(maxSteps = 42, maxWallTimeSeconds = 120, maxToolCalls = 3),
        )

        assertEquals(definition, WorkflowDefinitionCodec.decode(WorkflowDefinitionCodec.encode(definition)))
    }

    @Test
    fun codecRejectsUnknownKindAndOversizedPayload() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkflowDefinitionCodec.decode("{\"kind\":\"unknown\"}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkflowDefinitionCodec.decode("x".repeat(WorkflowDefinitionCodec.MAX_PAYLOAD_CHARS + 1))
        }
    }
}
