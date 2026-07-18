package dev.androml.core.agents

import dev.androml.core.tools.InMemoryToolAuditSink
import dev.androml.core.tools.ToolApproval
import dev.androml.core.tools.ToolAuditSink
import dev.androml.core.tools.ToolExecutionContext
import dev.androml.core.tools.ToolExecutionOutcome
import dev.androml.core.tools.ToolExecutor
import dev.androml.core.tools.ToolId
import dev.androml.core.tools.ToolRegistry
import dev.androml.core.tools.ToolScope
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

@JvmInline
value class AgentId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): AgentId {
            require(raw.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) {
                "agent ID contains unsafe characters"
            }
            return AgentId(raw)
        }
    }
}

data class AgentDefinition(
    val id: AgentId,
    val displayName: String,
    val systemPrompt: String,
    val allowedTools: Set<ToolId> = emptySet(),
    val maxTurns: Int = 8,
    val maxToolCalls: Int = 16,
    val maxOutputCharacters: Int = 256 * 1024,
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= 128) { "agent display name is invalid" }
        require(systemPrompt.length <= 64 * 1024) { "agent system prompt is too large" }
        require(allowedTools.size <= 64) { "agent allowlist is too large" }
        require(maxTurns in 1..64) { "agent turn budget is out of bounds" }
        require(maxToolCalls in 0..128) { "agent tool budget is out of bounds" }
        require(maxOutputCharacters in 1..4 * 1024 * 1024) {
            "agent output budget is out of bounds"
        }
    }
}

sealed interface AgentMessage {
    val textLength: Int

    data class System(val text: String) : AgentMessage {
        init {
            require(text.length <= MAX_MESSAGE_CHARS) { "agent system message is too large" }
        }

        override val textLength: Int = text.length
    }

    data class User(val text: String) : AgentMessage {
        init {
            require(text.isNotBlank() && text.length <= MAX_MESSAGE_CHARS) {
                "agent user message is invalid"
            }
        }

        override val textLength: Int = text.length
    }

    data class Assistant(val text: String) : AgentMessage {
        init {
            require(text.length <= MAX_MESSAGE_CHARS) { "agent assistant message is too large" }
        }

        override val textLength: Int = text.length
    }

    data class AssistantToolCall(
        val toolId: ToolId,
        val arguments: JsonObject,
    ) : AgentMessage {
        init {
            require(arguments.toString().length <= MAX_ARGUMENT_CHARS) {
                "agent tool arguments are too large"
            }
        }

        override val textLength: Int = arguments.toString().length
    }

    data class Tool(
        val toolId: ToolId,
        val result: JsonObject,
    ) : AgentMessage {
        init {
            require(result.toString().length <= MAX_MESSAGE_CHARS) { "agent tool result is too large" }
        }

        override val textLength: Int = result.toString().length
    }

    companion object {
        const val MAX_MESSAGE_CHARS = 256 * 1024
        const val MAX_ARGUMENT_CHARS = 64 * 1024
    }
}

data class AgentTranscript(
    val messages: List<AgentMessage>,
) {
    init {
        require(messages.size <= MAX_MESSAGES) { "agent transcript has too many messages" }
        require(messages.sumOf(AgentMessage::textLength) <= MAX_TRANSCRIPT_CHARS) {
            "agent transcript is too large"
        }
    }

    fun plus(message: AgentMessage): AgentTranscript = AgentTranscript(messages + message)

    companion object {
        const val MAX_MESSAGES = 256
        const val MAX_TRANSCRIPT_CHARS = 4 * 1024 * 1024
    }
}

sealed interface AgentModelDecision {
    data class Final(val text: String) : AgentModelDecision {
        init {
            require(text.length <= AgentMessage.MAX_MESSAGE_CHARS) { "agent final answer is too large" }
        }
    }

    data class CallTool(
        val toolId: ToolId,
        val arguments: JsonObject,
    ) : AgentModelDecision {
        init {
            require(arguments.toString().length <= 64 * 1024) { "agent tool arguments are too large" }
        }
    }
}

fun interface AgentModel {
    suspend fun next(definition: AgentDefinition, transcript: AgentTranscript): AgentModelDecision
}

data class AgentContinuation(
    val transcript: AgentTranscript,
    val pendingToolCall: AgentModelDecision.CallTool,
    val turns: Int,
    val toolCalls: Int,
) {
    init {
        require(turns in 0..64) { "agent continuation turn count is out of bounds" }
        require(toolCalls in 0..128) { "agent continuation tool count is out of bounds" }
    }
}

enum class AgentRunStatus {
    Completed,
    WaitingForApproval,
    Failed,
}

data class AgentRunResult(
    val status: AgentRunStatus,
    val transcript: AgentTranscript,
    val turns: Int,
    val toolCalls: Int,
    val finalText: String? = null,
    val safeError: String? = null,
    val pendingApproval: ToolApproval? = null,
    val continuation: AgentContinuation? = null,
)

class AgentExecutor(
    toolRegistry: ToolRegistry,
    model: AgentModel,
    auditSink: ToolAuditSink = InMemoryToolAuditSink(),
) {
    private val model = model
    private val tools = ToolExecutor(toolRegistry, auditSink = auditSink)

    suspend fun run(
        definition: AgentDefinition,
        prompt: String,
        grantedScopes: Set<ToolScope>,
        continuation: AgentContinuation? = null,
        approval: ToolApproval? = null,
    ): AgentRunResult {
        val initialTranscript = continuation?.transcript ?: AgentTranscript(
            listOf(
                AgentMessage.System(definition.systemPrompt),
                AgentMessage.User(prompt),
            ),
        )
        var transcript = initialTranscript
        var turns = continuation?.turns ?: 0
        var toolCalls = continuation?.toolCalls ?: 0
        var pendingToolCall = continuation?.pendingToolCall
        var nextApproval = approval

        while (true) {
            val decision = if (pendingToolCall != null) {
                pendingToolCall.also {
                    pendingToolCall = null
                }
            } else {
                if (turns >= definition.maxTurns) {
                    return failed(transcript, turns, toolCalls, "agent turn budget exceeded")
                }
                turns += 1
                try {
                    model.next(definition, transcript)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    return failed(transcript, turns, toolCalls, "agent model failed")
                }
            }
            when (decision) {
                is AgentModelDecision.Final -> {
                    if (decision.text.length > definition.maxOutputCharacters) {
                        return failed(transcript, turns, toolCalls, "agent output budget exceeded")
                    }
                    return AgentRunResult(
                        status = AgentRunStatus.Completed,
                        transcript = transcript.plus(AgentMessage.Assistant(decision.text)),
                        turns = turns,
                        toolCalls = toolCalls,
                        finalText = decision.text,
                    )
                }

                is AgentModelDecision.CallTool -> {
                    if (decision.toolId !in definition.allowedTools) {
                        return failed(transcript, turns, toolCalls, "tool is outside the agent allowlist")
                    }
                    if (toolCalls >= definition.maxToolCalls) {
                        return failed(transcript, turns, toolCalls, "agent tool budget exceeded")
                    }
                    transcript = transcript.plus(
                        AgentMessage.AssistantToolCall(decision.toolId, decision.arguments),
                    )
                    toolCalls += 1
                    when (
                        val outcome = tools.execute(
                            toolId = decision.toolId,
                            arguments = decision.arguments,
                            context = ToolExecutionContext(grantedScopes),
                            approval = nextApproval,
                        )
                    ) {
                        is ToolExecutionOutcome.Completed -> {
                            nextApproval = null
                            transcript = transcript.plus(AgentMessage.Tool(decision.toolId, outcome.result))
                        }
                        is ToolExecutionOutcome.ApprovalRequired -> {
                            return AgentRunResult(
                                status = AgentRunStatus.WaitingForApproval,
                                transcript = transcript,
                                turns = turns,
                                toolCalls = toolCalls,
                                pendingApproval = outcome.approval,
                                continuation = AgentContinuation(
                                    transcript = transcript,
                                    pendingToolCall = decision,
                                    turns = turns,
                                    toolCalls = toolCalls,
                                ),
                            )
                        }
                        is ToolExecutionOutcome.Denied -> {
                            return failed(transcript, turns, toolCalls, outcome.reason)
                        }
                        is ToolExecutionOutcome.Failed -> {
                            return failed(transcript, turns, toolCalls, outcome.reason)
                        }
                    }
                }
            }
        }
    }

    private fun failed(
        transcript: AgentTranscript,
        turns: Int,
        toolCalls: Int,
        reason: String,
    ): AgentRunResult = AgentRunResult(
        status = AgentRunStatus.Failed,
        transcript = transcript,
        turns = turns,
        toolCalls = toolCalls,
        safeError = reason.take(MAX_SAFE_ERROR_CHARS),
    )

    companion object {
        private const val MAX_SAFE_ERROR_CHARS = 512
    }
}
