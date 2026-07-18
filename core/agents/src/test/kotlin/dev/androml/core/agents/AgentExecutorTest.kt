package dev.androml.core.agents

import dev.androml.core.tools.ToolHandler
import dev.androml.core.tools.ToolId
import dev.androml.core.tools.ToolRegistry
import dev.androml.core.tools.ToolScope
import dev.androml.core.tools.ToolSideEffect
import dev.androml.core.tools.ToolDescriptor
import dev.androml.core.tools.ToolInputSchema
import dev.androml.core.tools.ToolProperty
import dev.androml.core.tools.ToolValueType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentExecutorTest {
    @Test
    fun runsBoundedToolCallingLoopAndReturnsFinalAnswer() = runBlocking {
        val searchTool = ToolId.parse("local.search")
        val registry = ToolRegistry()
        registry.register(
            ToolDescriptor(
                id = searchTool,
                displayName = "Local search",
                description = "Searches the local index.",
                sideEffect = ToolSideEffect.Read,
                requiredScopes = setOf(ToolScope.parse("rag.read")),
                input = ToolInputSchema(
                    properties = mapOf("query" to ToolProperty(ToolValueType.String, maxLength = 64)),
                    required = setOf("query"),
                ),
            ),
            ToolHandler { buildJsonObject { put("matches", 2) } },
        )
        var turn = 0
        val model = AgentModel { _, transcript ->
            turn += 1
            if (turn == 1) {
                AgentModelDecision.CallTool(
                    toolId = searchTool,
                    arguments = buildJsonObject { put("query", transcript.lastPrompt()) },
                )
            } else {
                AgentModelDecision.Final("Found ${transcript.toolResults().single()}")
            }
        }

        val result = AgentExecutor(
            toolRegistry = registry,
            model = model,
        ).run(
            definition = definition(searchTool),
            prompt = "runtime",
            grantedScopes = setOf(ToolScope.parse("rag.read")),
        )

        assertEquals(AgentRunStatus.Completed, result.status)
        assertEquals("Found {\"matches\":2}", result.finalText)
        assertEquals(2, result.turns)
    }

    @Test
    fun refusesToolOutsideAgentAllowlistBeforeModelCanEscalate() = runBlocking {
        val allowed = ToolId.parse("local.search")
        val unexpected = ToolId.parse("local.delete")
        val registry = ToolRegistry()
        registry.register(descriptor(allowed), ToolHandler { buildJsonObject { put("ok", true) } })
        registry.register(descriptor(unexpected), ToolHandler { buildJsonObject { put("ok", true) } })
        val result = AgentExecutor(
            toolRegistry = registry,
            model = AgentModel { _, _ -> AgentModelDecision.CallTool(unexpected, buildJsonObject {}) },
        ).run(
            definition = definition(allowed),
            prompt = "delete",
            grantedScopes = setOf(ToolScope.parse("rag.read")),
        )

        assertEquals(AgentRunStatus.Failed, result.status)
        assertTrue(result.safeError!!.contains("allowlist"))
    }

    @Test
    fun returnsApprovalSuspensionForMutatingTool() = runBlocking {
        val write = ToolId.parse("local.write")
        val registry = ToolRegistry()
        registry.register(
            descriptor(write).copy(sideEffect = ToolSideEffect.Write),
            ToolHandler { buildJsonObject { put("ok", true) } },
        )
        val result = AgentExecutor(
            toolRegistry = registry,
            model = AgentModel { _, _ -> AgentModelDecision.CallTool(write, buildJsonObject {}) },
        ).run(
            definition = definition(write),
            prompt = "write",
            grantedScopes = setOf(ToolScope.parse("rag.read")),
        )

        assertEquals(AgentRunStatus.WaitingForApproval, result.status)
        assertTrue(result.pendingApproval != null)
    }

    private fun definition(toolId: ToolId): AgentDefinition = AgentDefinition(
        id = AgentId.parse("researcher"),
        displayName = "Researcher",
        systemPrompt = "Use only permitted local tools.",
        allowedTools = setOf(toolId),
        maxTurns = 4,
        maxToolCalls = 2,
    )

    private fun descriptor(toolId: ToolId): ToolDescriptor = ToolDescriptor(
        id = toolId,
        displayName = toolId.value,
        description = "A bounded local test tool.",
        sideEffect = ToolSideEffect.Read,
        requiredScopes = setOf(ToolScope.parse("rag.read")),
        input = ToolInputSchema(emptyMap(), allowAdditionalProperties = true),
    )
}

private fun AgentTranscript.lastPrompt(): String = messages
    .filterIsInstance<AgentMessage.User>()
    .last()
    .text

private fun AgentTranscript.toolResults(): List<String> = messages
    .filterIsInstance<AgentMessage.Tool>()
    .map { it.result.toString() }
