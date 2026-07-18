package dev.androml.cluster.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class ClusterInferenceTask(
    val modelHash: ContentHash,
    val prompt: String,
    val maxNewTokens: Int,
    val temperature: Double,
    val stopSequences: List<String> = emptyList(),
    val contextTokens: Int,
    val kvCacheBytesPerToken: Long,
    val cpuThreads: Int,
    val useAcceleration: Boolean,
    val runtimeId: String,
) {
    init {
        require(prompt.length <= MAX_PROMPT_CHARS) { "cluster inference prompt is too large" }
        require(maxNewTokens in 1..MAX_CLUSTER_INFERENCE_NEW_TOKENS) { "cluster max-new-tokens is out of bounds" }
        require(temperature.isFinite() && temperature in 0.0..2.0) {
            "cluster inference temperature is out of bounds"
        }
        require(stopSequences.size <= MAX_STOP_SEQUENCES) { "too many cluster stop sequences" }
        require(stopSequences.all { it.isNotEmpty() && it.length <= MAX_STOP_SEQUENCE_CHARS }) {
            "cluster stop sequence is out of bounds"
        }
        require(contextTokens in 1..MAX_CONTEXT_TOKENS) { "cluster context size is out of bounds" }
        require(kvCacheBytesPerToken >= 0L) { "cluster KV cache size must not be negative" }
        require(cpuThreads in 1..256) { "cluster CPU thread count is out of bounds" }
        require(runtimeId.matches(RUNTIME_ID_PATTERN)) { "cluster runtime ID is invalid" }
    }

    private companion object {
        val RUNTIME_ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        const val MAX_PROMPT_CHARS = 64 * 1024
        const val MAX_STOP_SEQUENCES = 8
        const val MAX_STOP_SEQUENCE_CHARS = 128
        const val MAX_CONTEXT_TOKENS = 131_072
    }
}

data class ClusterInferenceResult(
    val text: String,
    val generatedTokens: Int,
    val runtimeId: String,
) {
    init {
        require(text.length <= MAX_OUTPUT_CHARS) { "cluster inference output is too large" }
        require(generatedTokens in 0..MAX_CLUSTER_INFERENCE_NEW_TOKENS) {
            "cluster generated token count is out of bounds"
        }
        require(runtimeId.matches(RUNTIME_ID_PATTERN)) { "cluster runtime ID is invalid" }
    }

    private companion object {
        val RUNTIME_ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        const val MAX_OUTPUT_CHARS = 512 * 1024
    }
}

object ClusterInferenceCodec {
    private const val MAX_TASK_BYTES = 128 * 1024
    private const val MAX_RESULT_BYTES = 512 * 1024
    private const val TASK_KIND = "inference_task"
    private const val RESULT_KIND = "inference_result"
    private val json = Json { explicitNulls = false }

    fun encodeTask(task: ClusterInferenceTask): ByteArray = encode(
        buildJsonObject {
            put("kind", TASK_KIND)
            put("model_hash", task.modelHash.value)
            put("prompt", task.prompt)
            put("max_new_tokens", task.maxNewTokens)
            put("temperature", task.temperature)
            put("stop_sequences", buildJsonArray { task.stopSequences.forEach { add(JsonPrimitive(it)) } })
            put("context_tokens", task.contextTokens)
            put("kv_cache_bytes_per_token", task.kvCacheBytesPerToken)
            put("cpu_threads", task.cpuThreads)
            put("use_acceleration", task.useAcceleration)
            put("runtime_id", task.runtimeId)
        },
        MAX_TASK_BYTES,
    )

    fun decodeTask(raw: ByteArray): ClusterInferenceTask {
        val root = parse(raw, MAX_TASK_BYTES)
        require(root.requiredString("kind") == TASK_KIND) { "cluster payload kind is not inference" }
        val stopSequences = root.requiredArray("stop_sequences").map { element ->
            element.jsonPrimitive.contentOrNull ?: throw IllegalArgumentException("stop sequence is invalid")
        }
        return ClusterInferenceTask(
            modelHash = ContentHash.parse(root.requiredString("model_hash")),
            prompt = root.requiredString("prompt"),
            maxNewTokens = root.requiredInt("max_new_tokens"),
            temperature = root.requiredDouble("temperature"),
            stopSequences = stopSequences,
            contextTokens = root.requiredInt("context_tokens"),
            kvCacheBytesPerToken = root.requiredLong("kv_cache_bytes_per_token"),
            cpuThreads = root.requiredInt("cpu_threads"),
            useAcceleration = root.requiredBoolean("use_acceleration"),
            runtimeId = root.requiredString("runtime_id"),
        )
    }

    fun encodeResult(result: ClusterInferenceResult): ByteArray = encode(
        buildJsonObject {
            put("kind", RESULT_KIND)
            put("text", result.text)
            put("generated_tokens", result.generatedTokens)
            put("runtime_id", result.runtimeId)
        },
        MAX_RESULT_BYTES,
    )

    fun decodeResult(raw: ByteArray): ClusterInferenceResult {
        val root = parse(raw, MAX_RESULT_BYTES)
        require(root.requiredString("kind") == RESULT_KIND) { "cluster payload kind is not inference result" }
        return ClusterInferenceResult(
            text = root.requiredString("text"),
            generatedTokens = root.requiredInt("generated_tokens"),
            runtimeId = root.requiredString("runtime_id"),
        )
    }

    private fun encode(root: JsonObject, maxBytes: Int): ByteArray {
        val bytes = json.encodeToString(root).toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxBytes) { "cluster workload payload exceeds the safety limit" }
        return bytes
    }

    private fun parse(raw: ByteArray, maxBytes: Int): JsonObject {
        require(raw.size <= maxBytes) { "cluster workload payload exceeds the safety limit" }
        return try {
            Json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject
        } catch (error: Exception) {
            throw IllegalArgumentException("cluster workload payload is invalid", error)
        }
    }

    private fun JsonObject.requiredString(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("cluster workload payload is missing $name")

    private fun JsonObject.requiredInt(name: String): Int =
        this[name]?.jsonPrimitive?.intOrNull
            ?: throw IllegalArgumentException("cluster workload payload is missing $name")

    private fun JsonObject.requiredLong(name: String): Long =
        this[name]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("cluster workload payload is missing $name")

    private fun JsonObject.requiredDouble(name: String): Double =
        this[name]?.jsonPrimitive?.doubleOrNull
            ?: throw IllegalArgumentException("cluster workload payload is missing $name")

    private fun JsonObject.requiredBoolean(name: String): Boolean =
        this[name]?.jsonPrimitive?.booleanOrNull
            ?: throw IllegalArgumentException("cluster workload payload is missing $name")

    private fun JsonObject.requiredArray(name: String): JsonArray =
        this[name]?.jsonArray ?: throw IllegalArgumentException("cluster workload payload is missing $name")
}

private const val MAX_CLUSTER_INFERENCE_NEW_TOKENS = 8_192
