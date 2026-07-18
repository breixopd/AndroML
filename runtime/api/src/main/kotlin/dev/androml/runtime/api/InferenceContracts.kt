package dev.androml.runtime.api

import dev.androml.core.model.ModelRequirements
import java.util.UUID

@JvmInline
value class SessionId private constructor(val value: String) {
    companion object {
        fun new(): SessionId = SessionId(UUID.randomUUID().toString())

        fun parse(raw: String): SessionId {
            require(raw.length in 1..MAX_ID_LENGTH) { "session ID length is invalid" }
            require(raw.all { it.isLetterOrDigit() || it == '-' }) {
                "session ID contains unsafe characters"
            }
            return SessionId(raw)
        }

        private const val MAX_ID_LENGTH = 64
    }
}

@JvmInline
value class InferenceRequestId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): InferenceRequestId {
            require(raw.length in 1..64) { "request ID length is invalid" }
            require(raw.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
                "request ID contains unsafe characters"
            }
            return InferenceRequestId(raw)
        }
    }
}

data class RuntimeConfiguration(
    val cpuThreads: Int,
    val contextTokens: Int,
    val useAcceleration: Boolean,
) {
    init {
        require(cpuThreads in 1..256) { "CPU thread count is out of bounds" }
        require(contextTokens in 1..MAX_CONTEXT_TOKENS) {
            "context token count is out of bounds"
        }
    }

    private companion object {
        const val MAX_CONTEXT_TOKENS = 131_072
    }
}

data class InferenceRequest(
    val id: InferenceRequestId,
    val prompt: String,
    val maxNewTokens: Int,
    val temperature: Double,
    val stopSequences: List<String> = emptyList(),
) {
    init {
        require(prompt.length <= MAX_PROMPT_CHARS) { "prompt exceeds the safety limit" }
        require(maxNewTokens in 1..MAX_NEW_TOKENS) {
            "maxNewTokens is out of bounds"
        }
        require(temperature.isFinite() && temperature in 0.0..2.0) {
            "temperature is out of bounds"
        }
        require(stopSequences.size <= MAX_STOP_SEQUENCES) {
            "too many stop sequences"
        }
        require(stopSequences.all { it.isNotEmpty() && it.length <= MAX_STOP_SEQUENCE_CHARS }) {
            "stop sequence is out of bounds"
        }
    }

    companion object {
        const val MAX_PROMPT_CHARS = 64 * 1024
        const val MAX_NEW_TOKENS = 8192
        const val MAX_STOP_SEQUENCES = 8
        const val MAX_STOP_SEQUENCE_CHARS = 128
    }
}

enum class InferenceErrorCode {
    InvalidRequest,
    SessionUnavailable,
    RuntimeUnavailable,
    RuntimeCrashed,
    OutOfMemory,
    TimedOut,
    Cancelled,
    Unexpected,
}

sealed interface InferenceEvent {
    val requestId: InferenceRequestId

    data class Started(
        override val requestId: InferenceRequestId,
        val runtimeId: RuntimeId,
    ) : InferenceEvent

    data class Token(
        override val requestId: InferenceRequestId,
        val text: String,
    ) : InferenceEvent {
        init {
            require(text.isNotEmpty() && text.length <= MAX_TOKEN_CHARS) {
                "token event text is out of bounds"
            }
        }
    }

    data class Completed(
        override val requestId: InferenceRequestId,
        val generatedTokens: Int,
        val durationMs: Long,
    ) : InferenceEvent {
        init {
            require(generatedTokens in 0..InferenceRequest.MAX_NEW_TOKENS) {
                "generated token count is out of bounds"
            }
            require(durationMs >= 0L) { "duration must be non-negative" }
        }
    }

    data class Failed(
        override val requestId: InferenceRequestId,
        val code: InferenceErrorCode,
        val safeMessage: String,
    ) : InferenceEvent {
        init {
            require(safeMessage.length <= MAX_ERROR_MESSAGE_CHARS) {
                "error message is out of bounds"
            }
        }
    }

    data class Cancelled(
        override val requestId: InferenceRequestId,
    ) : InferenceEvent

    private companion object {
        const val MAX_TOKEN_CHARS = 4096
        const val MAX_ERROR_MESSAGE_CHARS = 512
    }
}

interface RuntimeSession : AutoCloseable {
    val id: SessionId
    val runtimeId: RuntimeId

    /** Emits a finite, ordered event stream and returns after completion/cancellation. */
    fun generate(request: InferenceRequest, emit: (InferenceEvent) -> Unit)

    fun cancel(requestId: InferenceRequestId)

    override fun close()
}

/** Deterministic runtime used by service, UI, and protocol tests before native packs exist. */
class FakeRuntimeAdapter(
    override val descriptor: RuntimeDescriptor = DEFAULT_DESCRIPTOR,
) : RuntimeAdapter {
    override fun inspect(model: ModelRequirements): RuntimeCompatibilityReport {
        val reasons = buildList {
            if (model.workload !in descriptor.workloads) {
                add(RuntimeIncompatibilityReason.WorkloadUnsupported)
            }
            descriptor.maxContextTokens?.let { maxContext ->
                if (model.contextTokens > maxContext) {
                    add(RuntimeIncompatibilityReason.ContextTooLarge)
                }
            }
        }
        return RuntimeCompatibilityReport(
            compatible = reasons.isEmpty(),
            reasons = reasons,
            estimatedPeakMemoryBytes = model.estimatedWorkingSetBytes + descriptor.memoryOverheadBytes,
        )
    }

    override fun openSession(
        model: ModelRequirements,
        configuration: RuntimeConfiguration,
    ): RuntimeSession {
        check(inspect(model).compatible) { "fake runtime cannot serve this model" }
        return FakeRuntimeSession(
            id = SessionId.new(),
            runtimeId = descriptor.id,
            configuration = configuration,
        )
    }

    private companion object {
        val DEFAULT_DESCRIPTOR = RuntimeDescriptor(
            id = RuntimeId.parse("fake"),
            version = "1",
            supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"),
            minAndroidApi = 29,
            workloads = setOf(
                dev.androml.core.model.ModelWorkload.TextGeneration,
                dev.androml.core.model.ModelWorkload.TextEmbedding,
                dev.androml.core.model.ModelWorkload.ImageGeneration,
                dev.androml.core.model.ModelWorkload.SpeechToText,
            ),
            acceleration = AccelerationBackend.Cpu,
            requiresVulkan = false,
            memoryOverheadBytes = 32L * 1024L * 1024L,
            maxContextTokens = 32_768,
        )
    }
}

private class FakeRuntimeSession(
    override val id: SessionId,
    override val runtimeId: RuntimeId,
    private val configuration: RuntimeConfiguration,
) : RuntimeSession {
    private val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun generate(request: InferenceRequest, emit: (InferenceEvent) -> Unit) {
        val startedAt = System.nanoTime()
        emit(InferenceEvent.Started(request.id, runtimeId))
        if (cancelled.remove(request.id.value)) {
            emit(InferenceEvent.Cancelled(request.id))
            return
        }

        val response = "[fake:${configuration.cpuThreads}t] " +
            request.prompt.trim().ifEmpty { "AndroML is ready." }
        val stopIndex = request.stopSequences
            .mapNotNull { sequence -> response.indexOf(sequence).takeIf { it >= 0 } }
            .minOrNull()
        val responseBeforeStop = stopIndex?.let { response.substring(0, it) } ?: response
        val pieces = responseBeforeStop
            .split(Regex("(?<=\\s)"))
            .filter(String::isNotEmpty)
            .take(request.maxNewTokens)
        var emitted = 0
        for (piece in pieces) {
            if (cancelled.remove(request.id.value)) {
                emit(InferenceEvent.Cancelled(request.id))
                return
            }
            if (request.stopSequences.any { response.contains(it) && piece.contains(it) }) break
            emit(InferenceEvent.Token(request.id, piece))
            emitted += 1
        }
        val durationMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
        emit(InferenceEvent.Completed(request.id, emitted, durationMs))
    }

    override fun cancel(requestId: InferenceRequestId) {
        cancelled += requestId.value
    }

    override fun close() {
        cancelled.clear()
    }
}
