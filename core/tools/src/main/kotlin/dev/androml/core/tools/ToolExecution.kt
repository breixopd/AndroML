package dev.androml.core.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

data class ToolExecutionContext(
    val grantedScopes: Set<ToolScope>,
    val rememberApproval: Boolean = false,
) {
    init {
        require(grantedScopes.size <= 64) { "too many granted tool scopes" }
    }
}

fun interface ToolHandler {
    suspend fun invoke(invocation: ToolInvocation): JsonObject
}

class ToolRegistry {
    internal data class Registration(
        val descriptor: ToolDescriptor,
        val handler: ToolHandler,
    )

    private val registrations = mutableMapOf<ToolId, Registration>()

    @Synchronized
    fun register(descriptor: ToolDescriptor, handler: ToolHandler) {
        require(registrations.putIfAbsent(descriptor.id, Registration(descriptor, handler)) == null) {
            "tool is already registered: ${descriptor.id.value}"
        }
    }

    @Synchronized
    fun descriptors(): List<ToolDescriptor> = registrations.values
        .map(Registration::descriptor)
        .sortedBy { it.id.value }

    @Synchronized
    internal fun registration(toolId: ToolId): Registration? = registrations[toolId]
}

sealed interface ToolExecutionOutcome {
    data class Completed(
        val result: JsonObject,
        val audit: ToolAuditEvent,
    ) : ToolExecutionOutcome

    data class ApprovalRequired(
        val approval: ToolApproval,
    ) : ToolExecutionOutcome

    data class Denied(
        val reason: String,
        val audit: ToolAuditEvent,
    ) : ToolExecutionOutcome

    data class Failed(
        val reason: String,
        val audit: ToolAuditEvent,
    ) : ToolExecutionOutcome
}

interface ToolAuditSink {
    suspend fun append(event: ToolAuditEvent)
}

class InMemoryToolAuditSink : ToolAuditSink {
    private val recorded = mutableListOf<ToolAuditEvent>()

    override suspend fun append(event: ToolAuditEvent) {
        synchronized(this) {
            recorded += event
        }
    }

    @Synchronized
    fun events(): List<ToolAuditEvent> = recorded.toList()
}

class ToolExecutor(
    private val registry: ToolRegistry,
    private val approvalPolicy: ToolApprovalPolicy = ToolApprovalPolicy(),
    private val auditSink: ToolAuditSink = NoopToolAuditSink,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun execute(
        toolId: ToolId,
        arguments: JsonObject,
        context: ToolExecutionContext,
        approval: ToolApproval? = null,
    ): ToolExecutionOutcome {
        val registration = registry.registration(toolId)
            ?: return unavailable(toolId, arguments, "tool is not installed")
        val invocation = ToolInvocation(
            descriptor = registration.descriptor,
            arguments = arguments,
            requestedAtEpochMillis = nowEpochMillis(),
        )
        val validationErrors = invocation.validate()
        if (validationErrors.isNotEmpty()) {
            return denied(invocation, "invalid tool arguments")
        }
        if (!registration.descriptor.requiredScopes.all(context.grantedScopes::contains)) {
            return denied(invocation, "required tool scope is not granted")
        }
        if (approvalPolicy.requiresApproval(registration.descriptor)) {
            if (approval == null) {
                return ToolExecutionOutcome.ApprovalRequired(
                    approvalPolicy.issue(
                        invocation = invocation,
                        nowEpochMillis = invocation.requestedAtEpochMillis,
                        rememberScope = context.rememberApproval,
                    ),
                )
            }
            if (!approval.isValidFor(invocation, invocation.requestedAtEpochMillis)) {
                return denied(invocation, "tool approval is missing, expired, or does not match")
            }
        }

        val result = try {
            withTimeout(registration.descriptor.timeoutSeconds * 1_000L) {
                registration.handler.invoke(invocation)
            }
        } catch (_: TimeoutCancellationException) {
            return failed(invocation, "tool execution timed out")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return failed(invocation, "tool execution failed")
        }
        if (result.toString().length > registration.descriptor.maxResultCharacters) {
            return failed(invocation, "tool result exceeded its size limit")
        }
        val audit = ToolAuditEvent.fromInvocation(
            invocation = invocation,
            success = true,
            result = result.toString(),
            occurredAtEpochMillis = nowEpochMillis(),
        )
        return try {
            auditSink.append(audit)
            ToolExecutionOutcome.Completed(result, audit)
        } catch (_: Exception) {
            ToolExecutionOutcome.Failed("tool audit could not be persisted", audit.copy(success = false, resultHash = null))
        }
    }

    private suspend fun unavailable(
        toolId: ToolId,
        arguments: JsonObject,
        reason: String,
    ): ToolExecutionOutcome {
        val descriptor = ToolDescriptor(
            id = toolId,
            displayName = "Unavailable tool",
            description = "Synthetic descriptor for an unavailable tool.",
            sideEffect = ToolSideEffect.Read,
            requiredScopes = emptySet(),
            input = ToolInputSchema(emptyMap()),
        )
        return denied(
            ToolInvocation(descriptor, arguments, nowEpochMillis()),
            reason,
        )
    }

    private suspend fun denied(invocation: ToolInvocation, reason: String): ToolExecutionOutcome {
        val audit = ToolAuditEvent.fromInvocation(invocation, success = false, occurredAtEpochMillis = nowEpochMillis())
        auditSink.append(audit)
        return ToolExecutionOutcome.Denied(reason, audit)
    }

    private suspend fun failed(invocation: ToolInvocation, reason: String): ToolExecutionOutcome {
        val audit = ToolAuditEvent.fromInvocation(invocation, success = false, occurredAtEpochMillis = nowEpochMillis())
        auditSink.append(audit)
        return ToolExecutionOutcome.Failed(reason, audit)
    }

    private object NoopToolAuditSink : ToolAuditSink {
        override suspend fun append(event: ToolAuditEvent) = Unit
    }
}
