package dev.androml.runtime.litert

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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/**
 * Standalone LiteRT Interpreter adapter. The first bounded workload is text embeddings:
 * arbitrary downloaded bytes are never treated as code, custom operators, or delegates.
 */
class LiteRtRuntimeAdapter(
    private val modelPath: String,
) : RuntimeAdapter {
    override val descriptor: RuntimeDescriptor = LiteRtRuntimeDescriptor.value

    override fun inspect(model: ModelRequirements): RuntimeCompatibilityReport {
        val reasons = buildList {
            if (!isReadableModelFile(model)) add(RuntimeIncompatibilityReason.RuntimeUnavailable)
            if (model.workload !in descriptor.workloads) add(RuntimeIncompatibilityReason.WorkloadUnsupported)
            descriptor.maxContextTokens?.let { maxContext ->
                if (model.contextTokens > maxContext) add(RuntimeIncompatibilityReason.ContextTooLarge)
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
        check(model.workload == ModelWorkload.TextEmbedding) {
            "LiteRT adapter currently serves text embeddings only"
        }
        check(!configuration.useAcceleration) {
            "LiteRT CPU adapter does not enable an unvalidated delegate"
        }
        check(inspect(model).compatible) { "LiteRT cannot serve this model" }
        val interpreter = Interpreter(
            File(modelPath),
            Interpreter.Options().setNumThreads(configuration.cpuThreads),
        )
        return LiteRtRuntimeSession(interpreter)
    }

    private fun isReadableModelFile(model: ModelRequirements): Boolean {
        val file = File(modelPath)
        return file.isFile && file.canRead() && (model.weightBytes == 0L || file.length() == model.weightBytes)
    }
}

object LiteRtRuntimeDescriptor {
    val value = RuntimeDescriptor(
        id = RuntimeId.parse("litert"),
        version = "1.4.2",
        supportedAbis = setOf("arm64-v8a", "x86_64"),
        minAndroidApi = 29,
        workloads = setOf(ModelWorkload.TextEmbedding),
        acceleration = AccelerationBackend.Cpu,
        requiresVulkan = false,
        memoryOverheadBytes = 64L * 1024L * 1024L,
        maxContextTokens = 4096,
    )
}

private class LiteRtRuntimeSession(
    private val interpreter: Interpreter,
) : RuntimeSession {
    override val id: SessionId = SessionId.new()
    override val runtimeId: RuntimeId = LiteRtRuntimeDescriptor.value.id
    private val cancelled = AtomicBoolean(false)

    override fun generate(request: InferenceRequest, emit: (InferenceEvent) -> Unit) {
        emit(InferenceEvent.Started(request.id, runtimeId))
        if (cancelled.getAndSet(false)) {
            emit(InferenceEvent.Cancelled(request.id))
            return
        }
        val startedAt = System.nanoTime()
        try {
            val input = createInput(request.prompt)
            val output = createOutput()
            interpreter.run(input, output)
            if (cancelled.get()) {
                emit(InferenceEvent.Cancelled(request.id))
                return
            }
            output.flip()
            val values = readOutput(output, interpreter.getOutputTensor(0).dataType())
            if (values.isNotEmpty()) {
                emit(InferenceEvent.Token(request.id, values.joinToString(prefix = "[", postfix = "]")))
            }
            emit(
                InferenceEvent.Completed(
                    request.id,
                    generatedTokens = if (values.isEmpty()) 0 else 1,
                    durationMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L),
                ),
            )
        } catch (_: OutOfMemoryError) {
            emit(InferenceEvent.Failed(request.id, InferenceErrorCode.OutOfMemory, "LiteRT tensor allocation exceeded the safety limit"))
        } catch (_: Throwable) {
            emit(InferenceEvent.Failed(request.id, InferenceErrorCode.RuntimeCrashed, "LiteRT execution failed"))
        }
    }

    override fun cancel(requestId: InferenceRequestId) {
        cancelled.set(true)
    }

    override fun close() {
        interpreter.close()
    }

    private fun createInput(prompt: String): ByteBuffer {
        val tensor = interpreter.getInputTensor(0)
        val elementCount = tensor.numElements().coerceIn(1, MAX_ELEMENTS)
        val values = prompt.codePoints().limit(elementCount.toLong()).toArray()
        val buffer = ByteBuffer.allocateDirect(elementCount * tensor.dataType().byteSize()).order(ByteOrder.nativeOrder())
        repeat(elementCount) { index ->
            val value = values.getOrNull(index)?.toFloat() ?: 0f
            when (tensor.dataType()) {
                DataType.FLOAT32 -> buffer.putFloat(value)
                DataType.INT32 -> buffer.putInt(value.toInt())
                DataType.INT64 -> buffer.putLong(value.toLong())
                DataType.UINT8, DataType.INT8 -> buffer.put(value.toInt().coerceIn(-128, 127).toByte())
                else -> error("unsupported LiteRT input type ${tensor.dataType()}")
            }
        }
        buffer.flip()
        return buffer
    }

    private fun createOutput(): ByteBuffer {
        val tensor = interpreter.getOutputTensor(0)
        val bytes = tensor.numElements().coerceIn(1, MAX_ELEMENTS) * tensor.dataType().byteSize()
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }

    private fun readOutput(buffer: ByteBuffer, dataType: DataType): List<String> {
        val count = (buffer.remaining() / dataType.byteSize()).coerceAtMost(MAX_ELEMENTS)
        return buildList(count) {
            repeat(count) {
                add(
                    when (dataType) {
                        DataType.FLOAT32 -> "%.7g".format(java.util.Locale.ROOT, buffer.float)
                        DataType.INT32 -> buffer.int.toString()
                        DataType.INT64 -> buffer.long.toString()
                        DataType.UINT8, DataType.INT8 -> buffer.get().toInt().toString()
                        else -> return@repeat
                    },
                )
            }
        }
    }

    private companion object {
        const val MAX_ELEMENTS = 1_000_000
    }
}
