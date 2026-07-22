package dev.androml.app

import dev.androml.core.agents.AgentContinuation
import dev.androml.core.agents.AgentMessage
import dev.androml.core.agents.AgentModelDecision
import dev.androml.core.agents.AgentTranscript
import dev.androml.core.database.PendingApprovalDao
import dev.androml.core.database.PendingApprovalEntity
import dev.androml.core.security.SecretStore
import dev.androml.core.tools.ToolApproval
import dev.androml.core.tools.ToolId
import dev.androml.core.tools.ToolScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.util.Base64

sealed interface StoredApproval {
    val approval: ToolApproval

    data class Tool(
        override val approval: ToolApproval,
        val toolId: ToolId,
        val arguments: JsonObject,
    ) : StoredApproval

    data class Agent(
        override val approval: ToolApproval,
        val modelHash: dev.androml.cluster.core.ContentHash,
        val continuation: AgentContinuation,
    ) : StoredApproval
}

/** JSON codec for bounded, encrypted approval continuations. */
object ApprovalStateCodec {
    private const val KIND_TOOL = "tool"
    private const val KIND_AGENT = "agent"

    fun encodeTool(approval: ToolApproval, toolId: ToolId, arguments: JsonObject): String =
        buildJsonObject {
            put("kind", KIND_TOOL)
            put("approval", encodeApproval(approval))
            put("tool_id", toolId.value)
            put("arguments", arguments)
        }.toString()

    fun encodeAgent(
        approval: ToolApproval,
        modelHash: dev.androml.cluster.core.ContentHash,
        continuation: AgentContinuation,
    ): String = buildJsonObject {
        put("kind", KIND_AGENT)
        put("approval", encodeApproval(approval))
        put("model_hash", modelHash.value)
        put("continuation", encodeContinuation(continuation))
    }.toString()

    fun decode(payload: String): StoredApproval {
        require(payload.length <= MAX_PAYLOAD_CHARS) { "approval payload is too large" }
        val root = Json.parseToJsonElement(payload).jsonObject
        val approval = decodeApproval(root["approval"]?.jsonObject ?: error("approval is missing"))
        return when (root.string("kind")) {
            KIND_TOOL -> StoredApproval.Tool(
                approval = approval,
                toolId = ToolId.parse(root.string("tool_id")),
                arguments = root["arguments"]?.jsonObject ?: error("tool arguments are missing"),
            )
            KIND_AGENT -> StoredApproval.Agent(
                approval = approval,
                modelHash = dev.androml.cluster.core.ContentHash.parse(root.string("model_hash")),
                continuation = decodeContinuation(root["continuation"]?.jsonObject ?: error("continuation is missing")),
            )
            else -> error("approval payload kind is unsupported")
        }
    }

    private fun encodeApproval(approval: ToolApproval): JsonObject = buildJsonObject {
        put("approval_id", approval.approvalId)
        put("tool_id", approval.toolId.value)
        put("argument_hash", approval.argumentHash)
        put("scopes", buildJsonArray {
            approval.scopes.sortedBy(ToolScope::value).forEach { add(JsonPrimitive(it.value)) }
        })
        put("issued_at", approval.issuedAtEpochMillis)
        put("expires_at", approval.expiresAtEpochMillis)
        put("fresh", approval.requiresFreshConfirmation)
    }

    private fun decodeApproval(root: JsonObject): ToolApproval = ToolApproval(
        approvalId = root.string("approval_id"),
        toolId = ToolId.parse(root.string("tool_id")),
        argumentHash = root.string("argument_hash"),
        scopes = root["scopes"]?.jsonArray?.map { ToolScope.parse(it.jsonPrimitive.content) }?.toSet()
            ?: error("approval scopes are missing"),
        issuedAtEpochMillis = root.long("issued_at"),
        expiresAtEpochMillis = root.long("expires_at"),
        requiresFreshConfirmation = root.boolean("fresh"),
    )

    private fun encodeContinuation(continuation: AgentContinuation): JsonObject = buildJsonObject {
        put("turns", continuation.turns)
        put("tool_calls", continuation.toolCalls)
        put(
            "pending_tool_call",
            buildJsonObject {
                put("tool_id", continuation.pendingToolCall.toolId.value)
                put("arguments", continuation.pendingToolCall.arguments)
            },
        )
        put("transcript", buildJsonArray {
            continuation.transcript.messages.forEach { add(encodeMessage(it)) }
        })
    }

    private fun encodeMessage(message: AgentMessage): JsonObject = buildJsonObject {
        when (message) {
            is AgentMessage.System -> {
                put("type", "system")
                put("text", message.text)
            }
            is AgentMessage.User -> {
                put("type", "user")
                put("text", message.text)
            }
            is AgentMessage.Assistant -> {
                put("type", "assistant")
                put("text", message.text)
            }
            is AgentMessage.AssistantToolCall -> {
                put("type", "assistant_tool_call")
                put("tool_id", message.toolId.value)
                put("arguments", message.arguments)
            }
            is AgentMessage.Tool -> {
                put("type", "tool")
                put("tool_id", message.toolId.value)
                put("result", message.result)
            }
        }
    }

    private fun decodeContinuation(root: JsonObject): AgentContinuation {
        val pending = root["pending_tool_call"]?.jsonObject ?: error("pending tool call is missing")
        val messages = root["transcript"]?.jsonArray?.map(::decodeMessage)
            ?: error("agent transcript is missing")
        return AgentContinuation(
            transcript = AgentTranscript(messages),
            pendingToolCall = AgentModelDecision.CallTool(
                toolId = ToolId.parse(pending.string("tool_id")),
                arguments = pending["arguments"]?.jsonObject ?: error("pending arguments are missing"),
            ),
            turns = root.int("turns"),
            toolCalls = root.int("tool_calls"),
        )
    }

    private fun decodeMessage(value: kotlinx.serialization.json.JsonElement): AgentMessage {
        val root = value.jsonObject
        return when (root.string("type")) {
            "system" -> AgentMessage.System(root.string("text"))
            "user" -> AgentMessage.User(root.string("text"))
            "assistant" -> AgentMessage.Assistant(root.string("text"))
            "assistant_tool_call" -> AgentMessage.AssistantToolCall(
                toolId = ToolId.parse(root.string("tool_id")),
                arguments = root["arguments"]?.jsonObject ?: error("assistant arguments are missing"),
            )
            "tool" -> AgentMessage.Tool(
                toolId = ToolId.parse(root.string("tool_id")),
                result = root["result"]?.jsonObject ?: error("tool result is missing"),
            )
            else -> error("agent message type is unsupported")
        }
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: error("$key is missing")

    private fun JsonObject.long(key: String): Long =
        this[key]?.jsonPrimitive?.longOrNull ?: error("$key is invalid")

    private fun JsonObject.int(key: String): Int =
        this[key]?.jsonPrimitive?.intOrNull ?: error("$key is invalid")

    private fun JsonObject.boolean(key: String): Boolean =
        this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: error("$key is invalid")

    const val MAX_PAYLOAD_CHARS = 6 * 1024 * 1024
}

/** Durable approval storage: Room indexes metadata while Keystore encrypts continuation chunks. */
class DurableApprovalStore(
    private val dao: PendingApprovalDao,
    private val secretStore: SecretStore,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun saveTool(
        approval: ToolApproval,
        toolId: ToolId,
        arguments: JsonObject,
    ) = save(
        approval = approval,
        kind = "tool",
        toolId = toolId,
        payload = ApprovalStateCodec.encodeTool(approval, toolId, arguments),
    )

    suspend fun saveAgent(
        approval: ToolApproval,
        modelHash: dev.androml.cluster.core.ContentHash,
        continuation: AgentContinuation,
    ) = save(
        approval = approval,
        kind = "agent",
        toolId = continuation.pendingToolCall.toolId,
        payload = ApprovalStateCodec.encodeAgent(approval, modelHash, continuation),
    )

    suspend fun consumeTool(approvalId: String): StoredApproval.Tool? =
        consume(approvalId, "tool") as? StoredApproval.Tool

    suspend fun consumeAgent(approvalId: String): StoredApproval.Agent? =
        consume(approvalId, "agent") as? StoredApproval.Agent

    private suspend fun consume(approvalId: String, expectedKind: String): StoredApproval? {
        cleanupExpired()
        val entity = dao.find(approvalId) ?: return null
        if (entity.kind != expectedKind) return null
        val payload = readChunks(entity)
        val decoded = runCatching { ApprovalStateCodec.decode(payload) }.getOrElse {
            removeEntityAndSecrets(entity)
            throw IllegalStateException("stored approval payload is corrupt", it)
        }
        removeEntityAndSecrets(entity)
        require(decoded.approval.approvalId == entity.approvalId) { "stored approval ID does not match its index" }
        require(decoded.approval.argumentHash == entity.argumentHash) { "stored approval hash does not match its index" }
        return decoded
    }

    suspend fun cleanupExpired() {
        val now = nowEpochMillis()
        dao.expired(now).forEach { removeEntityAndSecrets(it) }
    }

    private suspend fun save(
        approval: ToolApproval,
        kind: String,
        toolId: ToolId,
        payload: String,
    ) {
        require(payload.toByteArray(StandardCharsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "approval continuation exceeds the safety limit"
        }
        val approvalId = approval.approvalId
        val prefix = "approval.$approvalId"
        val encoded = Base64.getEncoder().encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        val chunks = encoded.chunked(CHUNK_CHARS)
        require(chunks.size in 1..MAX_CHUNKS) { "approval continuation has too many chunks" }
        val previous = dao.find(approvalId)
        if (previous != null) removeEntityAndSecrets(previous)
        try {
            chunks.forEachIndexed { index, chunk -> secretStore.write("$prefix.$index", chunk) }
            dao.insert(
                PendingApprovalEntity(
                    approvalId = approvalId,
                    kind = kind,
                    toolId = toolId.value,
                    argumentHash = approval.argumentHash,
                    issuedAtEpochMillis = approval.issuedAtEpochMillis,
                    expiresAtEpochMillis = approval.expiresAtEpochMillis,
                    secretPrefix = prefix,
                    chunkCount = chunks.size,
                    createdAtEpochMillis = nowEpochMillis(),
                ),
            )
        } catch (error: Throwable) {
            chunks.indices.forEach { index -> runCatching { secretStore.delete("$prefix.$index") } }
            throw error
        }
    }

    private fun readChunks(entity: PendingApprovalEntity): String {
        require(entity.chunkCount in 1..MAX_CHUNKS) { "stored approval chunk count is invalid" }
        val encoded = buildString {
            repeat(entity.chunkCount) { index ->
                append(secretStore.read("${entity.secretPrefix}.$index") ?: error("stored approval chunk is missing"))
            }
        }
        return Base64.getDecoder().decode(encoded).toString(StandardCharsets.UTF_8)
            .also { require(it.length <= ApprovalStateCodec.MAX_PAYLOAD_CHARS) }
    }

    private suspend fun removeEntityAndSecrets(entity: PendingApprovalEntity) {
        repeat(entity.chunkCount.coerceIn(0, MAX_CHUNKS)) { index ->
            runCatching { secretStore.delete("${entity.secretPrefix}.$index") }
        }
        dao.delete(entity.approvalId)
    }

    private companion object {
        const val CHUNK_CHARS = 8 * 1024
        const val MAX_CHUNKS = 768
        const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024
    }
}
