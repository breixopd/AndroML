package dev.androml.core.agents

import dev.androml.core.tools.ToolId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelOutputParserTest {
    @Test
    fun parsesBoundedToolCallEnvelope() {
        val decision = AgentModelOutputParser.parse(
            "{\"tool_call\":{\"id\":\"calculator.evaluate\",\"arguments\":{\"expression\":\"2+2\"}}}",
        )

        assertTrue(decision is AgentModelDecision.CallTool)
        decision as AgentModelDecision.CallTool
        assertEquals(ToolId.parse("calculator.evaluate"), decision.toolId)
        assertEquals("2+2", decision.arguments["expression"]?.toString()?.trim('"'))
    }

    @Test
    fun treatsOrdinaryJsonAsFinalText() {
        val output = "{\"answer\":\"not a tool call\"}"
        assertEquals(AgentModelDecision.Final(output), AgentModelOutputParser.parse(output))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsafeToolIdInEnvelope() {
        AgentModelOutputParser.parse(
            "{\"tool_call\":{\"id\":\"../shell\",\"arguments\":{}}}",
        )
    }
}
