package dev.androml.cluster.core

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.FutureTask
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

data class ClusterExecutionRequest(
    val sourcePeerId: PeerId,
    val request: ClusterRequest,
    val payload: ByteArray,
) {
    init {
        require(payload.size <= ClusterWireCodec.MAX_PAYLOAD_BYTES) {
            "cluster execution payload exceeds the safety limit"
        }
        require(ContentHash.parse(sha256(payload)) == request.payloadHash) {
            "cluster execution payload hash does not match the request"
        }
    }
}

enum class ClusterExecutionStatus {
    Completed,
    AlreadyRunning,
    AlreadyCompleted,
    AlreadyFailed,
    Failed,
    Rejected,
}

data class ClusterExecutionResponse(
    val jobId: ClusterJobId,
    val attempt: Int,
    val status: ClusterExecutionStatus,
    val output: ByteArray? = null,
    val outputHash: ContentHash? = null,
    val safeMessage: String? = null,
) {
    init {
        require(attempt in 1..1_000) { "cluster attempt is out of bounds" }
        require(output == null || output.size <= ClusterWireCodec.MAX_OUTPUT_BYTES) {
            "cluster execution output exceeds the safety limit"
        }
        require(safeMessage == null || safeMessage.isNotBlank() && safeMessage.length <= MAX_SAFE_MESSAGE_CHARS) {
            "cluster execution message is invalid"
        }
        if (output != null) {
            require(outputHash == ContentHash.parse(sha256(output))) {
                "cluster execution output hash does not match the output"
            }
        }
        when (status) {
            ClusterExecutionStatus.Completed -> {
                require(output != null && outputHash != null) { "completed response must include output" }
                require(safeMessage == null) { "completed response must not include an error" }
            }

            ClusterExecutionStatus.AlreadyCompleted -> {
                require(outputHash != null) { "already-completed response must include an output hash" }
                require(safeMessage == null) { "already-completed response must not include an error" }
            }

            ClusterExecutionStatus.Failed,
            ClusterExecutionStatus.Rejected,
            -> require(safeMessage != null) { "failed responses must include a safe message" }

            ClusterExecutionStatus.AlreadyRunning,
            ClusterExecutionStatus.AlreadyFailed,
            -> require(output == null && outputHash == null && safeMessage == null) {
                "in-progress and previously failed responses cannot include output"
            }
        }
    }

    private companion object {
        const val MAX_SAFE_MESSAGE_CHARS = 512
    }
}

fun interface ClusterExecutionHandler {
    fun execute(request: ClusterExecutionRequest): ByteArray
}

/** Runs one attempt at most once and exposes only bounded, hash-addressed results. */
class IdempotentClusterExecutor(
    private val ledger: ClusterJobLedger,
    private val handler: ClusterExecutionHandler,
    private val nowEpochMillis: () -> Long,
    maxConcurrentExecutions: Int = 1,
    private val maxConcurrentPerPeer: Int = maxConcurrentExecutions,
) {
    private val globalSlots = Semaphore(maxConcurrentExecutions.also { require(it in 1..64) })
    private val peerSlots = java.util.concurrent.ConcurrentHashMap<PeerId, Semaphore>()
    private val activeAttempts = java.util.concurrent.ConcurrentHashMap.newKeySet<JobAttemptKey>()

    init { require(maxConcurrentPerPeer in 1..64) }

    fun execute(request: ClusterExecutionRequest): ClusterExecutionResponse {
        val now = nowEpochMillis()
        if (request.request.deadlineEpochMillis <= now) {
            return rejected(request, "cluster execution deadline has expired")
        }
        if (request.request.deadlineEpochMillis - now > MAX_FUTURE_DEADLINE_MILLIS) {
            return rejected(request, "cluster execution deadline is too far in the future")
        }
        val peerSlot = peerSlots.computeIfAbsent(request.sourcePeerId) { Semaphore(maxConcurrentPerPeer) }
        val globalAcquired = globalSlots.tryAcquire()
        if (!globalAcquired) return rejected(request, "cluster execution capacity is exhausted")
        val peerAcquired = peerSlot.tryAcquire()
        if (!peerAcquired) { globalSlots.release(); return rejected(request, "cluster peer execution capacity is exhausted") }

        val key = scopedAttemptKey(request)
        if (!activeAttempts.add(key)) {
            peerSlot.release()
            globalSlots.release()
            return response(request, ClusterExecutionStatus.AlreadyRunning)
        }
        val released = AtomicBoolean(false)
        val releaseSlots = {
            if (released.compareAndSet(false, true)) {
                activeAttempts.remove(key)
                peerSlot.release()
                globalSlots.release()
            }
        }
        var workerOwnsSlots = false
        try {
        return when (ledger.begin(key, now, ATTEMPT_LEASE_MILLIS)) {
            BeginAttempt.Started -> {
                workerOwnsSlots = true
                runStarted(key, request, releaseSlots)
            }
            BeginAttempt.AlreadyRunning -> response(request, ClusterExecutionStatus.AlreadyRunning)
            BeginAttempt.Completed -> response(
                request,
                status = ClusterExecutionStatus.AlreadyCompleted,
                output = ledger.output(key),
                outputHash = requireNotNull(ledger.outputHash(key)) {
                    "completed cluster attempt has no output hash"
                },
            )

            BeginAttempt.Failed -> response(request, ClusterExecutionStatus.AlreadyFailed)
        }
        } finally {
            if (!workerOwnsSlots) releaseSlots()
        }
    }

    private fun runStarted(
        key: JobAttemptKey,
        request: ClusterExecutionRequest,
        releaseSlots: () -> Unit,
    ): ClusterExecutionResponse {
        val task = FutureTask {
            try {
                val output = handler.execute(request)
                require(output.size <= ClusterWireCodec.MAX_OUTPUT_BYTES) {
                    "cluster execution output exceeds the safety limit"
                }
                val outputHash = ContentHash.parse(sha256(output))
                ledger.complete(key, outputHash, output)
                response(
                    request,
                    status = ClusterExecutionStatus.Completed,
                    output = output,
                    outputHash = outputHash,
                )
            } catch (_: Exception) {
                ledger.fail(key)
                response(
                    request,
                    status = ClusterExecutionStatus.Failed,
                    safeMessage = "cluster execution failed",
                )
            } finally {
                releaseSlots()
            }
        }
        try {
            Thread(task, "androml-cluster-execution").apply {
                isDaemon = true
                start()
            }
        } catch (_: Exception) {
            ledger.fail(key)
            releaseSlots()
            return response(
                request,
                status = ClusterExecutionStatus.Failed,
                safeMessage = "cluster execution could not start",
            )
        }
        return try {
            task.get(
                (request.request.deadlineEpochMillis - nowEpochMillis()).coerceAtLeast(1L),
                TimeUnit.MILLISECONDS,
            )
        } catch (_: TimeoutException) {
            // Do not cancel or release admission: native work may ignore interruption.
            // The worker owns its slots until it actually exits.
            response(
                request,
                status = ClusterExecutionStatus.Failed,
                safeMessage = "cluster execution deadline expired",
            )
        } catch (_: Exception) {
            response(
                request,
                status = ClusterExecutionStatus.Failed,
                safeMessage = "cluster execution failed",
            )
        }
    }

    private fun rejected(request: ClusterExecutionRequest, message: String) = response(
        request,
        status = ClusterExecutionStatus.Rejected,
        safeMessage = message,
    )

    private fun response(
        request: ClusterExecutionRequest,
        status: ClusterExecutionStatus,
        output: ByteArray? = null,
        outputHash: ContentHash? = null,
        safeMessage: String? = null,
    ) = ClusterExecutionResponse(
        jobId = request.request.jobId,
        attempt = request.request.attempt,
        status = status,
        output = output,
        outputHash = outputHash,
        safeMessage = safeMessage,
    )

    private fun scopedAttemptKey(request: ClusterExecutionRequest): JobAttemptKey {
        val identity = buildString {
            append(request.sourcePeerId.value)
            append('\u0000')
            append(request.request.jobId.value)
            append('\u0000')
            append(request.request.workload.name)
            append('\u0000')
            append(request.request.modelKey.orEmpty())
            append('\u0000')
            append(request.request.modelHash?.value.orEmpty())
            append('\u0000')
            append(request.request.requiredRamBytes)
            append('\u0000')
            append(request.request.payloadHash.value)
            append('\u0000')
            append(request.request.idempotencyKey)
        }
        return JobAttemptKey(
            ClusterJobId.parse("scoped-${sha256(identity.toByteArray(Charsets.UTF_8))}"),
            request.request.attempt,
        )
    }

    private companion object {
        const val MAX_FUTURE_DEADLINE_MILLIS = 10 * 60 * 1_000L
        const val ATTEMPT_LEASE_MILLIS = 11 * 60 * 1_000L
    }
}

/** Versioned JSON codec for peer messages. The payload itself remains opaque to transport. */
object ClusterWireCodec {
    const val MAX_PAYLOAD_BYTES = 1 * 1024 * 1024
    const val MAX_OUTPUT_BYTES = 1 * 1024 * 1024
    const val MAX_WIRE_BYTES = 3 * 1024 * 1024

    private const val PROTOCOL_MAJOR = 1
    private const val PROTOCOL_MINOR = 0
    private val json = Json { explicitNulls = false }

    fun encodeRequest(request: ClusterExecutionRequest): String {
        requirePayloadHash(request)
        val encoded = json.encodeToString(requestJson(request))
        requireWireSize(encoded)
        return encoded
    }

    fun decodeRequest(raw: String): ClusterExecutionRequest {
        requireWireSize(raw)
        val root = parseRoot(raw)
        requireProtocol(root)
        val payload = decodeBytes(root.requiredString("payload_base64"), MAX_PAYLOAD_BYTES, "payload")
        val request = ClusterRequest(
            jobId = ClusterJobId.parse(root.requiredString("job_id")),
            attempt = root.requiredInt("attempt"),
            workload = root.requiredEnum<ClusterWorkload>("workload"),
            modelKey = root.optionalString("model_key"),
            modelHash = root.optionalString("model_hash")?.let(ContentHash::parse),
            requiredRamBytes = root.requiredLong("required_ram_bytes"),
            deadlineEpochMillis = root.requiredLong("deadline_epoch_millis"),
            payloadHash = ContentHash.parse(root.requiredString("payload_hash")),
            idempotencyKey = root.requiredString("idempotency_key"),
        )
        return ClusterExecutionRequest(
            sourcePeerId = PeerId.parse(root.requiredString("source_peer_id")),
            request = request,
            payload = payload,
        )
    }

    fun encodeResponse(response: ClusterExecutionResponse): String {
        val encoded = json.encodeToString(buildJsonObject {
            put("protocol_major", PROTOCOL_MAJOR)
            put("protocol_minor", PROTOCOL_MINOR)
            put("job_id", response.jobId.value)
            put("attempt", response.attempt)
            put("status", response.status.name)
            response.output?.let { put("output_base64", Base64.getEncoder().encodeToString(it)) }
            response.outputHash?.let { put("output_hash", it.value) }
            response.safeMessage?.let { put("safe_message", it) }
        })
        requireWireSize(encoded)
        return encoded
    }

    fun decodeResponse(raw: String): ClusterExecutionResponse {
        requireWireSize(raw)
        val root = parseRoot(raw)
        requireProtocol(root)
        val output = root.optionalString("output_base64")?.let {
            decodeBytes(it, MAX_OUTPUT_BYTES, "output")
        }
        return ClusterExecutionResponse(
            jobId = ClusterJobId.parse(root.requiredString("job_id")),
            attempt = root.requiredInt("attempt"),
            status = root.requiredEnum("status"),
            output = output,
            outputHash = root.optionalString("output_hash")?.let(ContentHash::parse),
            safeMessage = root.optionalString("safe_message"),
        )
    }

    private fun requestJson(request: ClusterExecutionRequest): JsonObject = buildJsonObject {
        put("protocol_major", PROTOCOL_MAJOR)
        put("protocol_minor", PROTOCOL_MINOR)
        put("source_peer_id", request.sourcePeerId.value)
        put("job_id", request.request.jobId.value)
        put("attempt", request.request.attempt)
        put("workload", request.request.workload.name)
        request.request.modelKey?.let { put("model_key", it) }
        request.request.modelHash?.let { put("model_hash", it.value) }
        put("required_ram_bytes", request.request.requiredRamBytes)
        put("deadline_epoch_millis", request.request.deadlineEpochMillis)
        put("payload_hash", request.request.payloadHash.value)
        put("idempotency_key", request.request.idempotencyKey)
        put("payload_base64", Base64.getEncoder().encodeToString(request.payload))
    }

    private fun parseRoot(raw: String): JsonObject = try {
        validateClusterJson(raw)
        Json.parseToJsonElement(raw).jsonObject
    } catch (error: Exception) {
        throw IllegalArgumentException("cluster message is not valid JSON", error)
    } catch (error: StackOverflowError) {
        throw IllegalArgumentException("cluster message is too deeply nested", error)
    }

    private fun requireProtocol(root: JsonObject) {
        require(root.requiredInt("protocol_major") == PROTOCOL_MAJOR) {
            "unsupported cluster protocol major"
        }
        require(root.requiredInt("protocol_minor") in 0..99) {
            "unsupported cluster protocol minor"
        }
    }

    private fun requirePayloadHash(request: ClusterExecutionRequest) {
        require(ContentHash.parse(sha256(request.payload)) == request.request.payloadHash) {
            "cluster execution payload hash does not match the request"
        }
    }

    private fun decodeBytes(encoded: String, maxBytes: Int, label: String): ByteArray {
        require(encoded.length <= ((maxBytes + 2) / 3) * 4 + 4) {
            "$label encoding exceeds the safety limit"
        }
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("$label is not valid base64", error)
        }
        require(bytes.size <= maxBytes) { "$label exceeds the safety limit" }
        return bytes
    }

    private fun requireWireSize(raw: String) {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_WIRE_BYTES) {
            "cluster message exceeds the safety limit"
        }
    }

    private inline fun <reified T : Enum<T>> JsonObject.requiredEnum(name: String): T =
        enumValueOf(requiredString(name))

    private fun JsonObject.requiredString(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("cluster message is missing $name")

    private fun JsonObject.optionalString(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)

    private fun JsonObject.requiredInt(name: String): Int =
        this[name]?.jsonPrimitive?.intOrNull
            ?: throw IllegalArgumentException("cluster message is missing $name")

    private fun JsonObject.requiredLong(name: String): Long =
        this[name]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("cluster message is missing $name")
}

internal fun validateClusterJson(raw: String) {
    var depth = 0
    var structural = 0
    var inString = false
    var escaped = false
    raw.forEach { character ->
        if (inString) {
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') inString = false
        } else {
            when (character) {
                '"' -> inString = true
                '{', '[' -> {
                    depth += 1
                    structural += 1
                    require(depth <= 64 && structural <= 100_000) {
                        "cluster JSON structure exceeds safety limits"
                    }
                }
                '}', ']' -> {
                    depth -= 1
                    structural += 1
                    require(depth >= 0 && structural <= 100_000) {
                        "cluster JSON structure is invalid"
                    }
                }
            }
        }
    }
    require(!inString && depth == 0) { "cluster JSON structure is invalid" }
}

private fun sha256(value: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { byte -> "%02x".format(byte) }
