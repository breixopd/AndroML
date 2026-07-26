package dev.androml.core.agents

import dev.androml.core.tools.ToolId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses the deliberately small structured-output contract used by local agents.
 *
 * A model may request a tool with:
 * {"tool_call":{"id":"calculator.evaluate","arguments":{"expression":"2+2"}}}
 * Any other output remains an ordinary final answer. Parsing never executes a tool.
 */
object AgentModelOutputParser {
    private const val MAX_OUTPUT_CHARS = 256 * 1024

    fun parse(output: String): AgentModelDecision {
        require(output.length <= MAX_OUTPUT_CHARS) { "agent model output is too large" }
        val candidate = output.trim()
        if (!candidate.startsWith("{")) return AgentModelDecision.Final(output)
        if (!hasSafeJsonStructure(candidate)) return AgentModelDecision.Final(output)
        val root = try {
            Json.parseToJsonElement(candidate).jsonObject
        } catch (_: Exception) {
            null
        } catch (_: StackOverflowError) {
            null
        }
            ?: return AgentModelDecision.Final(output)
        val toolCall = root["tool_call"]?.let { element ->
            runCatching { element.jsonObject }.getOrNull()
        } ?: return AgentModelDecision.Final(output)
        val id = toolCall["id"]?.jsonPrimitive?.contentOrNull
            ?: return AgentModelDecision.Final(output)
        val arguments = toolCall["arguments"] as? JsonObject
            ?: return AgentModelDecision.Final(output)
        return AgentModelDecision.CallTool(
            toolId = ToolId.parse(id),
            arguments = arguments,
        )
    }

    private fun hasSafeJsonStructure(value: String): Boolean {
        var depth = 0
        var structuralTokens = 0
        var inString = false
        var escaped = false
        value.forEach { character ->
            if (inString) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') inString = false
            } else {
                when (character) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth += 1
                        structuralTokens += 1
                        if (depth > MAX_JSON_DEPTH || structuralTokens > MAX_STRUCTURAL_TOKENS) return false
                    }
                    '}', ']' -> {
                        depth -= 1
                        structuralTokens += 1
                        if (depth < 0 || structuralTokens > MAX_STRUCTURAL_TOKENS) return false
                    }
                    ',', ':' -> {
                        structuralTokens += 1
                        if (structuralTokens > MAX_STRUCTURAL_TOKENS) return false
                    }
                }
            }
        }
        return !inString && depth == 0
    }

    private const val MAX_JSON_DEPTH = 32
    private const val MAX_STRUCTURAL_TOKENS = 8_192
}
