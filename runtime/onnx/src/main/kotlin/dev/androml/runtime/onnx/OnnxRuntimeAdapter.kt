package dev.androml.runtime.onnx

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
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
import dev.androml.runtime.api.TensorDataType
import dev.androml.runtime.api.TensorInput
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.DoubleBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ONNX Runtime Mobile adapter for tensor models that expose an embedding-style
 * integer token input. Downloaded models remain data: no custom operators or
 * native libraries are loaded from the artifact path.
 */
class OnnxRuntimeAdapter(
    private val modelPath: String,
) : RuntimeAdapter {
    override val descriptor: RuntimeDescriptor = OnnxRuntimeDescriptor.value

    override fun inspect(model: ModelRequirements): RuntimeCompatibilityReport {
        val reasons = buildList {
            if (!isReadableModelFile(model)) add(RuntimeIncompatibilityReason.RuntimeUnavailable)
            if (model.workload !in descriptor.workloads) {
                add(RuntimeIncompatibilityReason.WorkloadUnsupported)
            }
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
        check(model.workload in descriptor.workloads) {
            "ONNX Runtime Mobile adapter does not serve ${model.workload}"
        }
        check(inspect(model).compatible) { "ONNX Runtime Mobile cannot serve this model" }
        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(configuration.cpuThreads)
            setInterOpNumThreads(1)
            setDeterministicCompute(true)
            if (configuration.useAcceleration) {
                // NNAPI is an optional compatibility backend. The optimizer only
                // enables it when device measurements make it worthwhile.
                runCatching { addNnapi() }
            }
        }
        return try {
            OnnxRuntimeSession(
                environment = environment,
                session = environment.createSession(modelPath, options),
            )
        } catch (error: Exception) {
            options.close()
            throw error
        }
    }

    private fun isReadableModelFile(model: ModelRequirements): Boolean {
        val file = File(modelPath)
        return file.isFile && file.canRead() && (model.weightBytes == 0L || file.length() == model.weightBytes)
    }
}

object OnnxRuntimeDescriptor {
    val value = RuntimeDescriptor(
        id = RuntimeId.parse("onnxruntime"),
        version = "1.26.0",
        supportedAbis = setOf("arm64-v8a", "x86_64"),
        minAndroidApi = 29,
        workloads = setOf(
            ModelWorkload.TextEmbedding,
            ModelWorkload.ImageClassification,
            ModelWorkload.ObjectDetection,
            ModelWorkload.ImageSegmentation,
            ModelWorkload.AudioClassification,
        ),
        acceleration = AccelerationBackend.Cpu,
        requiresVulkan = false,
        memoryOverheadBytes = 96L * 1024L * 1024L,
        maxContextTokens = 4096,
    )
}

private class OnnxRuntimeSession(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
) : RuntimeSession {
    override val id: SessionId = SessionId.new()
    override val runtimeId: RuntimeId = OnnxRuntimeDescriptor.value.id

    private val cancelled = AtomicBoolean(false)

    override fun generate(request: InferenceRequest, emit: (InferenceEvent) -> Unit) {
        emit(InferenceEvent.Started(request.id, runtimeId))
        if (cancelled.get()) {
            emit(InferenceEvent.Cancelled(request.id))
            return
        }
        val startedAt = System.nanoTime()
        try {
            val tokenIds = tokenize(request.prompt)
            if (request.tensorInput != null) require(session.inputInfo.size == 1) {
                "tensor input requires a single ONNX model input"
            }
            val inputs = session.inputInfo.mapValues { (_, node) ->
                createInput(node, tokenIds, request.tensorInput)
            }
            try {
                session.run(inputs).use { result ->
                    val value = result.get(0).getValue()
                    val output = flatten(value).take(MAX_OUTPUT_VALUES)
                    if (output.isNotEmpty()) {
                        emit(InferenceEvent.Token(request.id, output.joinToString(prefix = "[", postfix = "]")))
                    }
                }
            } finally {
                inputs.values.forEach { it.close() }
            }
            val durationMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
            emit(InferenceEvent.Completed(request.id, generatedTokens = 1, durationMs = durationMs))
        } catch (_: InterruptedException) {
            emit(InferenceEvent.Cancelled(request.id))
        } catch (_: Exception) {
            emit(InferenceEvent.Failed(request.id, InferenceErrorCode.RuntimeCrashed, "ONNX Runtime execution failed"))
        }
    }

    override fun cancel(requestId: InferenceRequestId) {
        cancelled.set(true)
    }

    override fun close() {
        session.close()
        // OrtEnvironment is process-global; it must not be closed by one session.
    }

    private fun createInput(node: NodeInfo, tokenIds: LongArray, tensorInput: TensorInput?): OnnxTensor {
        val info = node.info as? TensorInfo ?: error("ONNX input is not a tensor")
        if (tensorInput != null) return createTensorInput(info, tensorInput)
        val shape = normalizedShape(info, tokenIds.size)
        return when (info.type) {
            OnnxJavaType.INT64 -> OnnxTensor.createTensor(environment, LongBuffer.wrap(tokenIds), shape)
            OnnxJavaType.INT32 -> OnnxTensor.createTensor(
                environment,
                IntBuffer.wrap(tokenIds.map(Long::toInt).toIntArray()),
                shape,
            )
            OnnxJavaType.FLOAT -> OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(tokenIds.map(Long::toFloat).toFloatArray()),
                shape,
            )
            OnnxJavaType.DOUBLE -> OnnxTensor.createTensor(
                environment,
                DoubleBuffer.wrap(tokenIds.map(Long::toDouble).toDoubleArray()),
                shape,
            )
            OnnxJavaType.BOOL -> OnnxTensor.createTensor(
                environment,
                ByteBuffer.allocateDirect(tokenIds.size).order(ByteOrder.nativeOrder()).apply {
                    repeat(tokenIds.size) { put(1) }
                    flip()
                },
                shape,
                OnnxJavaType.BOOL,
            )
            else -> error("unsupported ONNX input type ${info.type}")
        }
    }

    private fun createTensorInput(info: TensorInfo, input: TensorInput): OnnxTensor {
        val shape = input.shape
        require(shape.size == info.shape.size) { "tensor input rank does not match the ONNX model" }
        info.shape.forEachIndexed { index, dimension ->
            if (dimension > 0L) require(dimension == shape[index]) {
                "tensor input shape does not match the ONNX model"
            }
        }
        val expectedType = when (input.dataType) {
            TensorDataType.Float32 -> OnnxJavaType.FLOAT
            TensorDataType.Int32 -> OnnxJavaType.INT32
            TensorDataType.Int64 -> OnnxJavaType.INT64
            TensorDataType.UInt8 -> OnnxJavaType.UINT8
            TensorDataType.Int8 -> OnnxJavaType.INT8
        }
        require(info.type == expectedType) {
            "tensor input type does not match the ONNX model"
        }
        val buffer = input.nativeBuffer()
        return when (input.dataType) {
            TensorDataType.Float32 -> OnnxTensor.createTensor(environment, buffer.asFloatBuffer(), shape)
            TensorDataType.Int32 -> OnnxTensor.createTensor(environment, buffer.asIntBuffer(), shape)
            TensorDataType.Int64 -> OnnxTensor.createTensor(environment, buffer.asLongBuffer(), shape)
            TensorDataType.UInt8 -> OnnxTensor.createTensor(environment, buffer, shape, OnnxJavaType.UINT8)
            TensorDataType.Int8 -> OnnxTensor.createTensor(environment, buffer, shape, OnnxJavaType.INT8)
        }
    }

    private fun normalizedShape(info: TensorInfo, tokenCount: Int): LongArray {
        val rank = info.shape.size
        require(rank in 1..2) { "ONNX embedding input rank is unsupported" }
        return if (rank == 1) {
            longArrayOf(tokenCount.toLong())
        } else {
            longArrayOf(1L, tokenCount.toLong())
        }
    }

    private fun tokenize(prompt: String): LongArray {
        val codePoints = prompt.codePoints().limit(MAX_INPUT_TOKENS.toLong()).toArray()
        return if (codePoints.isEmpty()) {
            longArrayOf(0L)
        } else {
            LongArray(codePoints.size) { index -> codePoints[index].toLong() }
        }
    }

    private fun flatten(value: Any?): List<String> = when (value) {
        is FloatArray -> value.map { "%.7g".format(java.util.Locale.ROOT, it) }
        is DoubleArray -> value.map { "%.7g".format(java.util.Locale.ROOT, it) }
        is LongArray -> value.map(Long::toString)
        is IntArray -> value.map(Int::toString)
        is Array<*> -> value.flatMap(::flatten)
        is List<*> -> value.flatMap(::flatten)
        else -> listOf(value?.toString().orEmpty())
    }

    private companion object {
        const val MAX_INPUT_TOKENS = 512
        const val MAX_OUTPUT_VALUES = 1024
    }
}
