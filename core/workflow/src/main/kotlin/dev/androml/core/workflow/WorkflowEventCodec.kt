package dev.androml.core.workflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class EncodedWorkflowEvent(
    val eventType: String,
    val payload: String,
)

/** Stable, bounded persistence format for workflow events. */
object WorkflowEventCodec {
    const val MAX_PAYLOAD_CHARS = 64 * 1024

    fun encode(event: WorkflowEvent): EncodedWorkflowEvent {
        val encoded = when (event) {
            is WorkflowEvent.Started -> EncodedWorkflowEvent(
                eventType = "started",
                payload = buildJsonObject {
                    common(event)
                    put("workflow_id", event.workflowId.value)
                    put("workflow_version", event.workflowVersion)
                    put("started_at", event.startedAtEpochMillis)
                }.toString(),
            )
            is WorkflowEvent.NodeStarted -> EncodedWorkflowEvent(
                eventType = "node_started",
                payload = buildJsonObject {
                    common(event)
                    put("node_id", event.nodeId.value)
                    put("attempt", event.attempt)
                }.toString(),
            )
            is WorkflowEvent.Checkpoint -> EncodedWorkflowEvent(
                eventType = "checkpoint",
                payload = buildJsonObject {
                    common(event)
                    put("node_id", event.nodeId.value)
                    put("attempt", event.attempt)
                    put("output_hash", event.outputHash)
                }.toString(),
            )
            is WorkflowEvent.NodeCompleted -> EncodedWorkflowEvent(
                eventType = "node_completed",
                payload = buildJsonObject {
                    common(event)
                    put("node_id", event.nodeId.value)
                    put("attempt", event.attempt)
                }.toString(),
            )
            is WorkflowEvent.NodeFailed -> EncodedWorkflowEvent(
                eventType = "node_failed",
                payload = buildJsonObject {
                    common(event)
                    put("node_id", event.nodeId.value)
                    put("attempt", event.attempt)
                    put("safe_message", event.safeMessage)
                    put("retryable", event.retryable)
                }.toString(),
            )
            is WorkflowEvent.ApprovalRequested -> EncodedWorkflowEvent(
                eventType = "approval_requested",
                payload = buildJsonObject {
                    common(event)
                    put("node_id", event.nodeId.value)
                    put("approval_id", event.approvalId)
                }.toString(),
            )
            is WorkflowEvent.StatusChanged -> EncodedWorkflowEvent(
                eventType = "status_changed",
                payload = buildJsonObject {
                    common(event)
                    put("status", event.status.name.lowercase())
                }.toString(),
            )
        }
        require(encoded.payload.length <= MAX_PAYLOAD_CHARS) { "workflow event payload is too large" }
        return encoded
    }

    fun decode(
        runId: RunId,
        eventType: String,
        payload: String,
    ): WorkflowEvent {
        require(payload.length in 2..MAX_PAYLOAD_CHARS) { "workflow event payload is out of bounds" }
        val root = Json.parseToJsonElement(payload).jsonObject
        val idempotencyKey = root.string("idempotency_key", 256)
        return when (eventType) {
            "started" -> WorkflowEvent.Started(
                runId = runId,
                idempotencyKey = idempotencyKey,
                workflowId = WorkflowId.parse(root.string("workflow_id", 64)),
                workflowVersion = root.int("workflow_version", 1..1_000_000),
                startedAtEpochMillis = root.long("started_at", 0L..Long.MAX_VALUE),
            )
            "node_started" -> WorkflowEvent.NodeStarted(
                runId,
                idempotencyKey,
                NodeId.parse(root.string("node_id", 64)),
                root.int("attempt", 1..1_000),
            )
            "checkpoint" -> WorkflowEvent.Checkpoint(
                runId,
                idempotencyKey,
                NodeId.parse(root.string("node_id", 64)),
                root.int("attempt", 1..1_000),
                root.string("output_hash", 128),
            )
            "node_completed" -> WorkflowEvent.NodeCompleted(
                runId,
                idempotencyKey,
                NodeId.parse(root.string("node_id", 64)),
                root.int("attempt", 1..1_000),
            )
            "node_failed" -> WorkflowEvent.NodeFailed(
                runId,
                idempotencyKey,
                NodeId.parse(root.string("node_id", 64)),
                root.int("attempt", 1..1_000),
                root.string("safe_message", 512),
                root.boolean("retryable"),
            )
            "approval_requested" -> WorkflowEvent.ApprovalRequested(
                runId,
                idempotencyKey,
                NodeId.parse(root.string("node_id", 64)),
                root.string("approval_id", 128),
            )
            "status_changed" -> WorkflowEvent.StatusChanged(
                runId,
                idempotencyKey,
                WorkflowRunStatus.entries.firstOrNull {
                    it.name.equals(root.string("status", 32), ignoreCase = true)
                } ?: throw IllegalArgumentException("workflow status is unknown"),
            )
            else -> throw IllegalArgumentException("workflow event type is unknown")
        }
    }

    private fun JsonObjectBuilder.common(event: WorkflowEvent) {
        put("idempotency_key", event.idempotencyKey)
    }

    private fun JsonObject.string(name: String, maxLength: Int): String =
        jsonPrimitive(name).contentOrNull
            ?.also { require(it.isNotBlank() && it.length <= maxLength) { "$name is invalid" } }
            ?: throw IllegalArgumentException("$name is missing")

    private fun JsonObject.int(name: String, range: IntRange): Int =
        jsonPrimitive(name).intOrNull?.also { require(it in range) { "$name is out of bounds" } }
            ?: throw IllegalArgumentException("$name is invalid")

    private fun JsonObject.long(name: String, range: LongRange): Long =
        jsonPrimitive(name).longOrNull?.also { require(it in range) { "$name is out of bounds" } }
            ?: throw IllegalArgumentException("$name is invalid")

    private fun JsonObject.boolean(name: String): Boolean =
        jsonPrimitive(name).booleanOrNull ?: throw IllegalArgumentException("$name is invalid")

    private fun JsonObject.jsonPrimitive(name: String) =
        this[name]?.jsonPrimitive ?: throw IllegalArgumentException("$name is missing")
}
