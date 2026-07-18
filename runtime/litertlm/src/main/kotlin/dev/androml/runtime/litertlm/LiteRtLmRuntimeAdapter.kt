package dev.androml.runtime.litertlm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import dev.androml.core.model.ModelRequirements
import dev.androml.core.model.ModelWorkload
import dev.androml.runtime.api.AccelerationBackend
import dev.androml.runtime.api.InferenceErrorCode
import dev.androml.runtime.api.InferenceEvent
import dev.androml.runtime.api.InferenceRequest
import dev.androml.runtime.api.InferenceRequestId
import dev.androml.runtime.api.RuntimeAdapter
import dev.androml.runtime.api.RuntimeCompatibilityReport
import dev.androml.runtime.api.RuntimeConfiguration
import dev.androml.runtime.api.RuntimeDescriptor
import dev.androml.runtime.api.RuntimeId
import dev.androml.runtime.api.RuntimeIncompatibilityReason
import dev.androml.runtime.api.RuntimeSession
import dev.androml.runtime.api.SessionId
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

/**
 * CPU LiteRT-LM adapter. The model path is normally a /proc/self/fd/N path created from a
 * read-only descriptor transferred across the isolated Binder boundary.
 */
class LiteRtLmRuntimeAdapter(
    private val modelPath: String,
    private val cacheDir: String? = null,
) : RuntimeAdapter {
    override val descriptor: RuntimeDescriptor = LiteRtLmRuntimeDescriptor.value

    override fun inspect(model: ModelRequirements): RuntimeCompatibilityReport {
        val reasons = buildList {
            if (!isReadableModelFile(model)) add(RuntimeIncompatibilityReason.RuntimeUnavailable)
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
        check(!configuration.useAcceleration) {
            "LiteRT-LM CPU adapter cannot enable acceleration"
        }
        check(model.workload == ModelWorkload.TextGeneration) {
            "LiteRT-LM adapter currently serves text generation only"
        }
        check(inspect(model).compatible) { "LiteRT-LM cannot serve this model" }
        val engine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(numOfThreads = configuration.cpuThreads),
                maxNumTokens = configuration.contextTokens,
                cacheDir = cacheDir,
            ),
        )
        return try {
            engine.initialize()
            LiteRtLmRuntimeSession(
                engine = engine,
                conversation = engine.createConversation(ConversationConfig()),
            )
        } catch (error: Exception) {
            engine.close()
            throw error
        }
    }

    private fun isReadableModelFile(model: ModelRequirements): Boolean {
        val file = File(modelPath)
        if (!file.isFile || !file.canRead()) return false
        return model.weightBytes == 0L || file.length() == model.weightBytes
    }
}

object LiteRtLmRuntimeDescriptor {
    val value = RuntimeDescriptor(
        id = RuntimeId.parse("litertlm"),
        version = "0.14.0",
        supportedAbis = setOf("arm64-v8a", "x86_64"),
        minAndroidApi = 29,
        workloads = setOf(ModelWorkload.TextGeneration),
        acceleration = AccelerationBackend.Cpu,
        requiresVulkan = false,
        memoryOverheadBytes = 192L * 1024L * 1024L,
        maxContextTokens = 32_768,
    )
}

private class LiteRtLmRuntimeSession(
    private val engine: Engine,
    private val conversation: Conversation,
) : RuntimeSession {
    override val id: SessionId = SessionId.new()
    override val runtimeId: RuntimeId = LiteRtLmRuntimeDescriptor.value.id

    private val cancelled = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    override fun generate(request: InferenceRequest, emit: (InferenceEvent) -> Unit) {
        if (!running.compareAndSet(false, true)) {
            emit(
                InferenceEvent.Failed(
                    request.id,
                    InferenceErrorCode.InvalidRequest,
                    "runtime session is already generating",
                ),
            )
            return
        }
        val startedAt = System.nanoTime()
        cancelled.set(false)
        emit(InferenceEvent.Started(request.id, runtimeId))
        try {
            var generatedTokens = 0
            var emittedCharacters = 0
            runBlocking {
                conversation.sendMessageAsync(request.prompt).collect { message ->
                    if (cancelled.get()) {
                        conversation.cancelProcess()
                        throw CancellationException("generation canceled")
                    }
                    val text = message.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString(separator = "") { it.text }
                    if (text.isEmpty()) return@collect
                    val remaining = MAX_CHARS_PER_TOKEN * request.maxNewTokens - emittedCharacters
                    if (remaining <= 0) {
                        conversation.cancelProcess()
                        throw CancellationException("generation output limit reached")
                    }
                    val bounded = text.take(remaining)
                    val stopIndex = request.stopSequences.mapNotNull { sequence ->
                        bounded.indexOf(sequence).takeIf { it >= 0 }
                    }.minOrNull()
                    val output = stopIndex?.let { bounded.substring(0, it) } ?: bounded
                    if (output.isNotEmpty()) {
                        emit(InferenceEvent.Token(request.id, output))
                        emittedCharacters += output.length
                        generatedTokens += estimateTokenCount(output)
                    }
                    if (stopIndex != null || emittedCharacters >= MAX_CHARS_PER_TOKEN * request.maxNewTokens) {
                        conversation.cancelProcess()
                        throw CancellationException("generation output limit reached")
                    }
                }
            }
            val durationMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
            emit(
                InferenceEvent.Completed(
                    request.id,
                    generatedTokens.coerceIn(0, request.maxNewTokens),
                    durationMs,
                ),
            )
        } catch (_: CancellationException) {
            emit(InferenceEvent.Cancelled(request.id))
        } catch (_: Exception) {
            emit(
                InferenceEvent.Failed(
                    request.id,
                    InferenceErrorCode.RuntimeCrashed,
                    "LiteRT-LM execution failed",
                ),
            )
        } finally {
            running.set(false)
            cancelled.set(false)
        }
    }

    override fun cancel(requestId: InferenceRequestId) {
        cancelled.set(true)
        runCatching { conversation.cancelProcess() }
    }

    override fun close() {
        cancelled.set(true)
        runCatching { conversation.cancelProcess() }
        runCatching { conversation.close() }
        runCatching { engine.close() }
    }

    private fun estimateTokenCount(text: String): Int =
        text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }.coerceAtLeast(1)

    private companion object {
        const val MAX_CHARS_PER_TOKEN = 32
    }
}
