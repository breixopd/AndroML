package dev.androml.core.tools

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionTest {
    @Test
    fun executesReadToolWithGrantedScopesAndAuditsOnlyHashes() = runBlocking {
        val sink = InMemoryToolAuditSink()
        val registry = ToolRegistry()
        registry.register(descriptor(sideEffect = ToolSideEffect.Read), ToolHandler {
            buildJsonObject { put("ok", true) }
        })

        val result = ToolExecutor(registry, auditSink = sink).execute(
            toolId = ToolId.parse("local.read"),
            arguments = buildJsonObject { put("query", "status") },
            context = ToolExecutionContext(grantedScopes = setOf(ToolScope.parse("local.read"))),
        )

        assertTrue(result is ToolExecutionOutcome.Completed)
        assertEquals(1, sink.events().size)
        assertTrue(sink.events().single().success)
        assertTrue(sink.events().single().resultHash != null)
    }

    @Test
    fun requiresAndThenAcceptsFreshApprovalForWriteTool() = runBlocking {
        val registry = ToolRegistry()
        registry.register(descriptor(sideEffect = ToolSideEffect.Write), ToolHandler {
            buildJsonObject { put("written", true) }
        })
        val executor = ToolExecutor(
            registry = registry,
            approvalPolicy = ToolApprovalPolicy(approvalLifetimeMillis = 30_000L),
            nowEpochMillis = { 10_000L },
        )
        val context = ToolExecutionContext(
            grantedScopes = setOf(ToolScope.parse("local.read")),
        )
        val arguments = buildJsonObject { put("query", "status") }

        val approvalRequest = executor.execute(ToolId.parse("local.read"), arguments, context)
        assertTrue(approvalRequest is ToolExecutionOutcome.ApprovalRequired)
        val approval = (approvalRequest as ToolExecutionOutcome.ApprovalRequired).approval

        val completed = executor.execute(ToolId.parse("local.read"), arguments, context, approval)
        assertTrue(completed is ToolExecutionOutcome.Completed)
    }

    @Test
    fun rejectsMissingScopesAndInvalidArgumentsBeforeCallingHandler() = runBlocking {
        val calls = AtomicInteger(0)
        val registry = ToolRegistry()
        registry.register(
            descriptor(
                sideEffect = ToolSideEffect.Read,
                requiredScopes = setOf(ToolScope.parse("local.admin")),
            ),
            ToolHandler {
                calls.incrementAndGet()
                buildJsonObject { put("ok", true) }
            },
        )
        val executor = ToolExecutor(registry)

        val denied = executor.execute(
            ToolId.parse("local.read"),
            buildJsonObject { put("query", "status") },
            ToolExecutionContext(grantedScopes = emptySet()),
        )
        assertTrue(denied is ToolExecutionOutcome.Denied)
        assertEquals(0, calls.get())

        val invalid = executor.execute(
            ToolId.parse("local.read"),
            buildJsonObject { put("query", 42) },
            ToolExecutionContext(grantedScopes = setOf(ToolScope.parse("local.admin"))),
        )
        assertTrue(invalid is ToolExecutionOutcome.Denied)
        assertEquals(0, calls.get())
    }

    @Test
    fun rejectsOversizedResultAndRecordsFailureWithoutResultContent() = runBlocking {
        val sink = InMemoryToolAuditSink()
        val registry = ToolRegistry()
        registry.register(
            descriptor(sideEffect = ToolSideEffect.Read).copy(maxResultCharacters = 8),
            ToolHandler { buildJsonObject { put("long", "too much") } },
        )

        val result = ToolExecutor(registry, auditSink = sink).execute(
            ToolId.parse("local.read"),
            buildJsonObject { put("query", "status") },
            ToolExecutionContext(grantedScopes = setOf(ToolScope.parse("local.read"))),
        )

        assertTrue(result is ToolExecutionOutcome.Failed)
        assertEquals(false, sink.events().single().success)
        assertEquals(null, sink.events().single().resultHash)
    }

    private fun descriptor(
        sideEffect: ToolSideEffect,
        requiredScopes: Set<ToolScope> = setOf(ToolScope.parse("local.read")),
    ): ToolDescriptor = ToolDescriptor(
        id = ToolId.parse("local.read"),
        displayName = "Local read",
        description = "Reads a bounded local value.",
        sideEffect = sideEffect,
        requiredScopes = requiredScopes,
        input = ToolInputSchema(
            properties = mapOf(
                "query" to ToolProperty(ToolValueType.String, maxLength = 64),
            ),
            required = setOf("query"),
        ),
    )
}
