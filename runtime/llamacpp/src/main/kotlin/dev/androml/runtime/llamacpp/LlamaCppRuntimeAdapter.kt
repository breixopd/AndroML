package dev.androml.runtime.llamacpp

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

/**
 * Pinned llama.cpp Android arm64 pack. GGUF remains data; only the packaged JNI wrapper can
 * execute it. The wrapper deliberately exposes local generation/embeddings only and no RPC.
 */
class LlamaCppRuntimeAdapter(private val modelPath: String) : RuntimeAdapter {
    override val descriptor: RuntimeDescriptor = LlamaCppRuntimeDescriptor.value

    override fun inspect(model: ModelRequirements): RuntimeCompatibilityReport {
        val reasons = buildList {
            if (!BuildConfig.LLAMA_CPP_BUNDLED) add(RuntimeIncompatibilityReason.RuntimeUnavailable)
            val file = File(modelPath)
            if (!file.isFile || !file.canRead() || (model.weightBytes != 0L && file.length() != model.weightBytes)) {
                add(RuntimeIncompatibilityReason.RuntimeUnavailable)
            }
            if (model.workload !in descriptor.workloads) add(RuntimeIncompatibilityReason.WorkloadUnsupported)
            descriptor.maxContextTokens?.let { if (model.contextTokens > it) add(RuntimeIncompatibilityReason.ContextTooLarge) }
        }
        return RuntimeCompatibilityReport(
            compatible = reasons.isEmpty(),
            reasons = reasons,
            estimatedPeakMemoryBytes = model.estimatedWorkingSetBytes + descriptor.memoryOverheadBytes,
        )
    }

    override fun openSession(model: ModelRequirements, configuration: RuntimeConfiguration): RuntimeSession {
        check(model.workload == ModelWorkload.TextGeneration) { "llama.cpp currently serves text generation only" }
        check(inspect(model).compatible) { "llama.cpp cannot serve this model" }
        return LlamaCppRuntimeSession(modelPath, configuration)
    }
}

object LlamaCppRuntimeDescriptor {
    val value = RuntimeDescriptor(
        id = RuntimeId.parse("llamacpp"),
        version = "b10079",
        supportedAbis = setOf("arm64-v8a"),
        minAndroidApi = 29,
        workloads = setOf(ModelWorkload.TextGeneration),
        acceleration = AccelerationBackend.Cpu,
        requiresVulkan = false,
        memoryOverheadBytes = 256L * 1024L * 1024L,
        maxContextTokens = 32_768,
        isAvailable = BuildConfig.LLAMA_CPP_BUNDLED,
    )
}

/** Called by the application before the shared runtime catalogue is first rendered. */
object LlamaCppRuntimeAvailability {
    fun advertise() {
        System.setProperty("androml.runtime.llamacpp.bundled", BuildConfig.LLAMA_CPP_BUNDLED.toString())
    }
}

private class LlamaCppRuntimeSession(
    modelPath: String,
    configuration: RuntimeConfiguration,
) : RuntimeSession {
    override val id: SessionId = SessionId.new()
    override val runtimeId: RuntimeId = LlamaCppRuntimeDescriptor.value.id
    private val cancelled = AtomicBoolean(false)
    private val native = LlamaCppNative.open(modelPath, configuration.contextTokens, configuration.cpuThreads)

    override fun generate(request: InferenceRequest, emit: (InferenceEvent) -> Unit) {
        emit(InferenceEvent.Started(request.id, runtimeId))
        if (cancelled.getAndSet(false)) {
            emit(InferenceEvent.Cancelled(request.id))
            return
        }
        val started = System.nanoTime()
        try {
            val text = native.generate(request.prompt, request.maxNewTokens, request.temperature)
                .let { generated -> request.stopSequences.fold(generated) { acc, stop -> acc.substringBefore(stop) } }
            if (text.isNotEmpty()) emit(InferenceEvent.Token(request.id, text.take(MAX_TOKEN_CHARS)))
            emit(InferenceEvent.Completed(request.id, if (text.isEmpty()) 0 else request.maxNewTokens, (System.nanoTime() - started) / 1_000_000L))
        } catch (_: OutOfMemoryError) {
            emit(InferenceEvent.Failed(request.id, InferenceErrorCode.OutOfMemory, "llama.cpp model exceeded the memory budget"))
        } catch (_: Throwable) {
            emit(InferenceEvent.Failed(request.id, InferenceErrorCode.RuntimeCrashed, "llama.cpp execution failed"))
        }
    }

    override fun cancel(requestId: InferenceRequestId) { cancelled.set(true); native.cancel() }

    override fun close() { native.close() }

    private companion object { const val MAX_TOKEN_CHARS = 4096 }
}

private class LlamaCppNative private constructor(private val handle: Long) {
    companion object {
        init { if (BuildConfig.LLAMA_CPP_BUNDLED) System.loadLibrary("androml_llamacpp") }
        fun open(path: String, contextTokens: Int, threads: Int): LlamaCppNative =
            LlamaCppNative(nativeOpen(path, contextTokens, threads))

        @JvmStatic private external fun nativeOpen(path: String, contextTokens: Int, threads: Int): Long
    }

    fun generate(prompt: String, maxNewTokens: Int, temperature: Double): String = nativeGenerate(handle, prompt, maxNewTokens, temperature)
    fun cancel() = nativeCancel(handle)
    fun close() = nativeClose(handle)

    private external fun nativeGenerate(handle: Long, prompt: String, maxNewTokens: Int, temperature: Double): String
    private external fun nativeCancel(handle: Long)
    private external fun nativeClose(handle: Long)
}
