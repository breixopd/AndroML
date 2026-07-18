package dev.androml.runtime.api

import dev.androml.core.model.ModelRequirements
import dev.androml.core.model.ModelWorkload

@JvmInline
value class RuntimeId private constructor(val value: String) {
    companion object {
        private val PATTERN = Regex("[a-z0-9][a-z0-9._-]*")

        fun parse(raw: String): RuntimeId {
            require(raw.matches(PATTERN)) { "runtime ID contains unsafe characters" }
            return RuntimeId(raw)
        }
    }
}

enum class AccelerationBackend {
    Cpu,
    Gpu,
    Npu,
}

enum class RuntimeIncompatibilityReason {
    RuntimeUnavailable,
    AndroidApiTooLow,
    AbiUnsupported,
    WorkloadUnsupported,
    VulkanUnavailable,
    MemoryUnknown,
    InsufficientMemory,
    ContextTooLarge,
}

data class RuntimeDescriptor(
    val id: RuntimeId,
    val version: String,
    val supportedAbis: Set<String>,
    val minAndroidApi: Int,
    val workloads: Set<ModelWorkload>,
    val acceleration: AccelerationBackend,
    val requiresVulkan: Boolean,
    val memoryOverheadBytes: Long,
    val maxContextTokens: Int? = null,
    val isAvailable: Boolean = true,
) {
    init {
        require(version.isNotBlank()) { "runtime version must not be blank" }
        require(supportedAbis.isNotEmpty()) { "runtime must declare at least one ABI" }
        require(minAndroidApi > 0) { "runtime minimum Android API must be positive" }
        require(memoryOverheadBytes >= 0) { "runtime memory overhead must be non-negative" }
        require(maxContextTokens == null || maxContextTokens > 0) {
            "runtime max context must be positive when present"
        }
    }
}

data class RuntimeCompatibilityReport(
    val compatible: Boolean,
    val reasons: List<RuntimeIncompatibilityReason>,
    val estimatedPeakMemoryBytes: Long?,
) {
    init {
        require(compatible == reasons.isEmpty()) {
            "compatible must match whether incompatibility reasons are present"
        }
    }
}

interface RuntimeAdapter {
    val descriptor: RuntimeDescriptor

    fun inspect(model: ModelRequirements): RuntimeCompatibilityReport

    fun openSession(
        model: ModelRequirements,
        configuration: RuntimeConfiguration,
    ): RuntimeSession
}
