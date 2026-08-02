package dev.androml.app

import dev.androml.core.model.DeviceProfile
import dev.androml.core.model.HuggingFaceSearchHit
import dev.androml.core.model.ModelWorkload
import dev.androml.runtime.api.RuntimePackCatalog

/** A bounded Hub query used to populate the first screen without making users guess a model ID. */
data class DeviceRecommendationQuery(
    val workload: ModelWorkload,
    val pipelineTag: String,
    val filter: String,
    val search: String,
    val limit: Int = 12,
)

fun deviceRecommendationQueries(device: DeviceProfile): List<DeviceRecommendationQuery> = buildList {
    val availableAbis = device.supportedAbis.toSet()
    val hasTensorRuntime = RuntimePackCatalog.bundled.any { pack ->
        pack.descriptor.supportedAbis.intersect(availableAbis).isNotEmpty() &&
            pack.descriptor.workloads.any {
                it in setOf(
                    ModelWorkload.TextEmbedding,
                    ModelWorkload.ImageClassification,
                    ModelWorkload.AudioClassification,
                )
            }
    }
    if (hasTensorRuntime) {
        add(
            DeviceRecommendationQuery(
                workload = ModelWorkload.TextEmbedding,
                pipelineTag = "feature-extraction",
                filter = "onnx",
                search = "",
            ),
        )
        add(
            DeviceRecommendationQuery(
                workload = ModelWorkload.ImageClassification,
                pipelineTag = "image-classification",
                filter = "onnx",
                search = "",
                limit = 8,
            ),
        )
        add(
            DeviceRecommendationQuery(
                workload = ModelWorkload.AudioClassification,
                pipelineTag = "audio-classification",
                filter = "onnx",
                search = "",
                limit = 8,
            ),
        )
    }

    val hasTextRuntime = "arm64-v8a" in availableAbis &&
        RuntimePackCatalog.bundled.any { pack ->
            pack.descriptor.id.value == "llamacpp" &&
                pack.descriptor.workloads.contains(ModelWorkload.TextGeneration)
        }
    if (hasTextRuntime) {
        add(
            DeviceRecommendationQuery(
                workload = ModelWorkload.TextGeneration,
                pipelineTag = "text-generation",
                filter = "gguf",
                search = recommendedTextSearch(device),
            ),
        )
    }
}

/** Removes duplicate repositories and obvious out-of-memory text models before rendering. */
fun rankDeviceRecommendations(
    hits: List<HuggingFaceSearchHit>,
    device: DeviceProfile,
): List<HuggingFaceSearchHit> = hits
    .asSequence()
    .filter { it.revision != null && it.filePaths.isNotEmpty() }
    .filter { hit -> !isObviousMemoryMismatch(hit, device) }
    .distinctBy { it.modelId }
    .sortedWith(
        compareByDescending<HuggingFaceSearchHit> { workloadPriority(it.pipelineTag) }
            .thenByDescending { supportedFormatCount(it) }
            .thenByDescending { it.downloads ?: 0L }
            .thenBy { it.modelId.lowercase() },
    )
    .toList()

fun modelFormatLabels(hit: HuggingFaceSearchHit): List<String> = buildList {
    if (hit.filePaths.any { it.endsWith(".gguf", ignoreCase = true) }) add("GGUF")
    if (hit.filePaths.any { path ->
            path.endsWith(".onnx", ignoreCase = true) ||
                path.endsWith(".ort", ignoreCase = true)
        }) add("ONNX")
    if (hit.filePaths.any { it.endsWith(".tflite", ignoreCase = true) }) add("TFLite")
    if (hit.filePaths.any { it.endsWith(".pte", ignoreCase = true) }) add("ExecuTorch")
}

private fun recommendedTextSearch(device: DeviceProfile): String {
    val availableBytes = device.availableMemoryBytes
        ?: device.totalMemoryBytes
        ?: Long.MAX_VALUE
    return when {
        availableBytes < 3L * GiB -> "0.5B"
        availableBytes < 6L * GiB -> "1B"
        availableBytes < 10L * GiB -> "3B"
        else -> "7B"
    }
}

private fun supportedFormatCount(hit: HuggingFaceSearchHit): Int = modelFormatLabels(hit).size

private fun workloadPriority(pipelineTag: String?): Int = when (pipelineTag) {
    "text-generation" -> 4
    "feature-extraction" -> 3
    "image-classification" -> 2
    "audio-classification" -> 1
    else -> 0
}

private fun isObviousMemoryMismatch(hit: HuggingFaceSearchHit, device: DeviceProfile): Boolean {
    if (hit.pipelineTag != "text-generation") return false
    val largestParameterHint = Regex("(?i)(\\d+(?:\\.\\d+)?)\\s*[bB]")
        .findAll(buildString {
            append(hit.modelId)
            append(' ')
            append(hit.filePaths.joinToString(" "))
        })
        .mapNotNull { it.groupValues[1].toDoubleOrNull() }
        .maxOrNull()
        ?: return false
    val availableBytes = device.availableMemoryBytes
        ?: device.totalMemoryBytes
        ?: return false
    val maxParametersB = when {
        availableBytes < 3L * GiB -> 2.0
        availableBytes < 6L * GiB -> 5.0
        availableBytes < 10L * GiB -> 9.0
        else -> 14.0
    }
    return largestParameterHint > maxParametersB
}

private const val GiB = 1024L * 1024L * 1024L
