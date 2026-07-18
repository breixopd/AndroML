package dev.androml.cluster.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ClusterWorkflowStageTask(
    val stageKind: String,
    val stageKey: String,
    val modelHash: ContentHash?,
    val inputPayload: String,
) {
    init {
        require(stageKind.matches(Regex("[a-z][a-z0-9._-]{0,31}"))) {
            "workflow stage kind is invalid"
        }
        require(stageKey.isNotBlank() && stageKey.length <= 512) {
            "workflow stage key is invalid"
        }
        require(inputPayload.length in 2..MAX_INPUT_CHARS) {
            "workflow stage input is out of bounds"
        }
    }

    companion object {
        const val MAX_INPUT_CHARS = 768 * 1024
    }
}

data class ClusterWorkflowStageResult(
    val outputPayload: String,
) {
    init {
        require(outputPayload.length in 2..MAX_OUTPUT_CHARS) {
            "workflow stage output is out of bounds"
        }
    }

    companion object {
        const val MAX_OUTPUT_CHARS = 768 * 1024
    }
}

/** Bounded, opaque transport for a typed workflow value and one executable stage. */
object ClusterWorkflowCodec {
    private const val MAX_WIRE_BYTES = 1 * 1024 * 1024
    private const val TASK_KIND = "workflow_stage_task"
    private const val RESULT_KIND = "workflow_stage_result"
    private val json = Json { explicitNulls = false }

    fun encodeTask(task: ClusterWorkflowStageTask): ByteArray = encode(
        buildJsonObject {
            put("kind", TASK_KIND)
            put("stage_kind", task.stageKind)
            put("stage_key", task.stageKey)
            task.modelHash?.let { put("model_hash", it.value) }
            put("input", task.inputPayload)
        },
    )

    fun decodeTask(raw: ByteArray): ClusterWorkflowStageTask {
        val root = parse(raw)
        require(root.requiredString("kind") == TASK_KIND) {
            "cluster payload kind is not a workflow stage"
        }
        return ClusterWorkflowStageTask(
            stageKind = root.requiredString("stage_kind", 32),
            stageKey = root.requiredString("stage_key", 512),
            modelHash = root.optionalString("model_hash")?.let(ContentHash::parse),
            inputPayload = root.requiredString("input", ClusterWorkflowStageTask.MAX_INPUT_CHARS),
        )
    }

    fun encodeResult(result: ClusterWorkflowStageResult): ByteArray = encode(
        buildJsonObject {
            put("kind", RESULT_KIND)
            put("output", result.outputPayload)
        },
    )

    fun decodeResult(raw: ByteArray): ClusterWorkflowStageResult {
        val root = parse(raw)
        require(root.requiredString("kind") == RESULT_KIND) {
            "cluster payload kind is not a workflow stage result"
        }
        return ClusterWorkflowStageResult(
            outputPayload = root.requiredString("output", ClusterWorkflowStageResult.MAX_OUTPUT_CHARS),
        )
    }

    private fun encode(root: JsonObject): ByteArray {
        val bytes = json.encodeToString(root).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_WIRE_BYTES) { "workflow stage payload exceeds the safety limit" }
        return bytes
    }

    private fun parse(raw: ByteArray): JsonObject {
        require(raw.size <= MAX_WIRE_BYTES) { "workflow stage payload exceeds the safety limit" }
        return try {
            Json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject
        } catch (error: Exception) {
            throw IllegalArgumentException("workflow stage payload is invalid", error)
        }
    }

    private fun JsonObject.requiredString(name: String, maxLength: Int = 128): String =
        this[name]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && it.length <= maxLength }
            ?: throw IllegalArgumentException("workflow stage payload is missing or invalid $name")

    private fun JsonObject.optionalString(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
}
