package dev.androml.core.model

/** A downloaded model file is data until a bundled runtime explicitly claims its format. */
enum class ModelFormat(
    val extension: String,
    val runtimeId: String,
    val workloads: Set<ModelWorkload>,
) {
    LiteRt(
        "tflite",
        "litert",
        setOf(
            ModelWorkload.TextEmbedding,
            ModelWorkload.ImageClassification,
            ModelWorkload.ObjectDetection,
            ModelWorkload.ImageSegmentation,
            ModelWorkload.AudioClassification,
        ),
    ),
    LiteRtLm("litertlm", "litertlm", setOf(ModelWorkload.TextGeneration)),
    Gguf("gguf", "llamacpp", setOf(ModelWorkload.TextGeneration)),
    Onnx(
        "onnx",
        "onnxruntime",
        setOf(
            ModelWorkload.TextEmbedding,
            ModelWorkload.ImageClassification,
            ModelWorkload.ObjectDetection,
            ModelWorkload.ImageSegmentation,
            ModelWorkload.AudioClassification,
        ),
    ),
    Ort(
        "ort",
        "onnxruntime",
        setOf(
            ModelWorkload.TextEmbedding,
            ModelWorkload.ImageClassification,
            ModelWorkload.ObjectDetection,
            ModelWorkload.ImageSegmentation,
            ModelWorkload.AudioClassification,
        ),
    ),
    ExecuTorch(
        "pte",
        "executorch",
        setOf(
            ModelWorkload.TextEmbedding,
            ModelWorkload.ImageClassification,
            ModelWorkload.ObjectDetection,
            ModelWorkload.ImageSegmentation,
            ModelWorkload.AudioClassification,
        ),
    ),
    Mlc("mlc", "mlc", emptySet()),
}

object ModelFormatClassifier {
    fun forPath(path: String): ModelFormat? {
        val extension = path.substringAfterLast('.', "").lowercase()
        return ModelFormat.entries.firstOrNull { it.extension == extension }
    }

    fun supports(path: String, workload: ModelWorkload): Boolean =
        forPath(path)?.workloads?.contains(workload) == true
}
