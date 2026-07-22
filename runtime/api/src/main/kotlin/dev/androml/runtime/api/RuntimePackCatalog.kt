package dev.androml.runtime.api

import dev.androml.core.model.ModelWorkload

/**
 * Product-facing inventory of engine packs. A pack is only advertised as usable when its
 * native implementation is actually shipped in the current APK. Keeping this catalogue
 * separate from adapter construction prevents the UI and API from promising an engine that
 * cannot execute a model on the device. LiteRT and ONNX currently expose bounded text
 * embeddings; other workloads remain explicit compatibility gaps.
 */
enum class RuntimePackState {
    Bundled,
    NotBundled,
    Disabled,
}

data class RuntimePackInfo(
    val descriptor: RuntimeDescriptor,
    val state: RuntimePackState,
    val note: String,
) {
    val usable: Boolean
        get() = state == RuntimePackState.Bundled && descriptor.isAvailable
}

object RuntimePackCatalog {
    /** Stable IDs are part of the API and must not be renamed between releases. */
    val production: List<RuntimePackInfo>
        get() = listOf(
        RuntimePackInfo(
            descriptor = RuntimeDescriptor(
                id = RuntimeId.parse("litert"),
                version = "1.4.2",
                supportedAbis = setOf("arm64-v8a", "x86_64"),
                minAndroidApi = 29,
                workloads = setOf(ModelWorkload.TextEmbedding),
                acceleration = AccelerationBackend.Cpu,
                requiresVulkan = false,
                memoryOverheadBytes = 64L * 1024L * 1024L,
                maxContextTokens = 4096,
            ),
            state = RuntimePackState.Bundled,
            note = "CPU text embeddings for .tflite models",
        ),
        RuntimePackInfo(
            descriptor = RuntimeDescriptor(
                id = RuntimeId.parse("litertlm"),
                version = "0.14.0",
                supportedAbis = setOf("arm64-v8a", "x86_64"),
                minAndroidApi = 29,
                workloads = setOf(ModelWorkload.TextGeneration),
                acceleration = AccelerationBackend.Cpu,
                requiresVulkan = false,
                memoryOverheadBytes = 192L * 1024L * 1024L,
                maxContextTokens = 32_768,
            ),
            state = RuntimePackState.Bundled,
            note = "CPU text generation",
        ),
        RuntimePackInfo(
            descriptor = RuntimeDescriptor(
                id = RuntimeId.parse("onnxruntime"),
                version = "1.26.0",
                supportedAbis = setOf("arm64-v8a", "x86_64"),
                minAndroidApi = 29,
                workloads = setOf(ModelWorkload.TextEmbedding),
                acceleration = AccelerationBackend.Cpu,
                requiresVulkan = false,
                memoryOverheadBytes = 96L * 1024L * 1024L,
                maxContextTokens = 4096,
            ),
            state = RuntimePackState.Bundled,
            note = "CPU text embeddings; optional NNAPI compatibility backend",
        ),
        RuntimePackInfo(
            descriptor = RuntimeDescriptor(
                id = RuntimeId.parse("executorch"),
                version = "0.6.0-rc1",
                supportedAbis = setOf("arm64-v8a", "x86_64"),
                minAndroidApi = 29,
                workloads = setOf(ModelWorkload.TextEmbedding),
                acceleration = AccelerationBackend.Cpu,
                requiresVulkan = false,
                memoryOverheadBytes = 96L * 1024L * 1024L,
                maxContextTokens = 4096,
            ),
            state = RuntimePackState.Bundled,
            note = "CPU tensor embeddings for .pte models",
        ),
        llamaPack(),
        RuntimePackInfo(
            descriptor = unavailableDescriptor(
                id = "mlc",
                version = "pending",
                workloads = setOf(ModelWorkload.TextGeneration),
                note = "Native pack is not included in this build",
            ),
            state = RuntimePackState.NotBundled,
            note = "Install a future signed runtime pack before use",
        ),
    )

    val bundled: List<RuntimePackInfo>
        get() = production.filter(RuntimePackInfo::usable)

    fun find(id: RuntimeId): RuntimePackInfo? = production.firstOrNull { it.descriptor.id == id }

    private fun llamaPack(): RuntimePackInfo {
        val bundled = System.getProperty("androml.runtime.llamacpp.bundled") == "true"
        return RuntimePackInfo(
            descriptor = RuntimeDescriptor(
                id = RuntimeId.parse("llamacpp"),
                version = if (bundled) "b10079" else "pending",
                supportedAbis = setOf("arm64-v8a"),
                minAndroidApi = 29,
                workloads = setOf(ModelWorkload.TextGeneration),
                acceleration = AccelerationBackend.Cpu,
                requiresVulkan = false,
                memoryOverheadBytes = 256L * 1024L * 1024L,
                maxContextTokens = 32_768,
                isAvailable = bundled,
            ),
            state = if (bundled) RuntimePackState.Bundled else RuntimePackState.NotBundled,
            note = if (bundled) "Pinned llama.cpp b10079 arm64 CPU pack" else "Install a future signed runtime pack before use",
        )
    }

    private fun unavailableDescriptor(
        id: String,
        version: String,
        workloads: Set<ModelWorkload>,
        note: String,
    ): RuntimeDescriptor = RuntimeDescriptor(
        id = RuntimeId.parse(id),
        version = version,
        supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"),
        minAndroidApi = 29,
        workloads = workloads,
        acceleration = AccelerationBackend.Cpu,
        requiresVulkan = false,
        memoryOverheadBytes = 0L,
        isAvailable = false,
    )
}
