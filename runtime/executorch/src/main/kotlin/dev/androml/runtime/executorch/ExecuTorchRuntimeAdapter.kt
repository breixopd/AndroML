package dev.androml.runtime.executorch

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
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor

/**
 * ExecuTorch AAR adapter for bounded tensor inference. PTE files are opaque data and are
 * executed only by the packaged runtime; no downloaded native code or RPC surface is loaded.
 */
class ExecuTorchRuntimeAdapter(
    private val modelPath: String,
) : RuntimeAdapter {
    override val descriptor: RuntimeDescriptor = ExecuTorchRuntimeDescriptor.value

    override fun inspect(model: ModelRequirements): RuntimeCompatibilityReport {
        val reasons = buildList {
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
        check(model.workload == ModelWorkload.TextEmbedding) {
            "ExecuTorch adapter currently serves text embeddings only"
        }
        check(!configuration.useAcceleration) { "ExecuTorch XNNPACK adapter does not enable unvalidated delegates" }
        check(inspect(model).compatible) { "ExecuTorch cannot serve this model" }
        return ExecuTorchRuntimeSession(Module.load(modelPath), configuration.cpuThreads)
    }
}

object ExecuTorchRuntimeDescriptor {
    val value = RuntimeDescriptor(
        id = RuntimeId.parse("executorch"),
        version = "0.6.0-rc1",
        supportedAbis = setOf("arm64-v8a", "x86_64"),
        minAndroidApi = 29,
        workloads = setOf(ModelWorkload.TextEmbedding),
        acceleration = AccelerationBackend.Cpu,
        requiresVulkan = false,
        memoryOverheadBytes = 96L * 1024L * 1024L,
        maxContextTokens = 4096,
    )
}

private class ExecuTorchRuntimeSession(
    private val module: Module,
    private val cpuThreads: Int,
) : RuntimeSession {
    override val id: SessionId = SessionId.new()
    override val runtimeId: RuntimeId = ExecuTorchRuntimeDescriptor.value.id
    private val cancelled = AtomicBoolean(false)

    override fun generate(request: InferenceRequest, emit: (InferenceEvent) -> Unit) {
        emit(InferenceEvent.Started(request.id, runtimeId))
        if (cancelled.getAndSet(false)) {
            emit(InferenceEvent.Cancelled(request.id))
            return
        }
        val startedAt = System.nanoTime()
        try {
            // The generic contract accepts text; PTE embedding models commonly consume a
            // one-dimensional float tensor. Keep the tensor bounded and deterministic.
            val values = request.prompt.codePoints().limit(MAX_INPUT_ELEMENTS.toLong())
                .toArray()
                .map(Int::toFloat)
                .toFloatArray()
                .let { input -> if (input.isEmpty()) floatArrayOf(0f) else input }
            val output = module.forward(
                EValue.from(Tensor.fromBlob(values, longArrayOf(values.size.toLong()))),
            )
            if (cancelled.get()) {
                emit(InferenceEvent.Cancelled(request.id))
                return
            }
            val tensor = output.firstOrNull()?.toTensor()
            val result = tensor?.dataAsFloatArray
                ?.take(MAX_OUTPUT_ELEMENTS)
                ?.joinToString(prefix = "[", postfix = "]") { "%.7g".format(java.util.Locale.ROOT, it) }
            if (!result.isNullOrEmpty()) emit(InferenceEvent.Token(request.id, result))
            emit(
                InferenceEvent.Completed(
                    request.id,
                    generatedTokens = if (result.isNullOrEmpty()) 0 else 1,
                    durationMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L),
                ),
            )
        } catch (_: OutOfMemoryError) {
            emit(InferenceEvent.Failed(request.id, InferenceErrorCode.OutOfMemory, "ExecuTorch tensor allocation exceeded the safety limit"))
        } catch (_: Throwable) {
            emit(InferenceEvent.Failed(request.id, InferenceErrorCode.RuntimeCrashed, "ExecuTorch execution failed"))
        }
    }

    override fun cancel(requestId: InferenceRequestId) {
        cancelled.set(true)
    }

    override fun close() {
        module.destroy()
    }

    private companion object {
        const val MAX_INPUT_ELEMENTS = 16_384
        const val MAX_OUTPUT_ELEMENTS = 1_000_000
    }
}
