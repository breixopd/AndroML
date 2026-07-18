package dev.androml.optimizer

import dev.androml.core.model.DeviceProfile
import dev.androml.core.model.MemoryPressure
import dev.androml.core.model.ModelRequirements
import dev.androml.core.model.ThermalStatus
import dev.androml.runtime.api.AccelerationBackend
import dev.androml.runtime.api.RuntimeCompatibilityReport
import dev.androml.runtime.api.RuntimeDescriptor
import dev.androml.runtime.api.RuntimeId
import dev.androml.runtime.api.RuntimeIncompatibilityReason
import kotlin.math.ceil

data class BenchmarkObservation(
    val runtimeId: RuntimeId,
    val tokensPerSecond: Double,
    val firstTokenLatencyMs: Double? = null,
) {
    init {
        require(tokensPerSecond >= 0.0) { "tokensPerSecond must be non-negative" }
        require(firstTokenLatencyMs == null || firstTokenLatencyMs >= 0.0) {
            "firstTokenLatencyMs must be non-negative when present"
        }
    }
}

data class OptimizationPolicy(
    val preferredBackends: List<AccelerationBackend> = listOf(
        AccelerationBackend.Npu,
        AccelerationBackend.Gpu,
        AccelerationBackend.Cpu,
    ),
    val memorySafetyMarginRatio: Double = 0.20,
    val maxCpuThreads: Int = 8,
) {
    init {
        require(preferredBackends.isNotEmpty()) { "preferredBackends must not be empty" }
        require(memorySafetyMarginRatio in 0.0..1.0) {
            "memorySafetyMarginRatio must be between zero and one"
        }
        require(maxCpuThreads > 0) { "maxCpuThreads must be positive" }
    }
}

data class RuntimeCandidate(
    val descriptor: RuntimeDescriptor,
    val compatibility: RuntimeCompatibilityReport,
    val score: Double?,
)

data class OptimizationConfiguration(
    val cpuThreads: Int,
    val contextTokens: Int,
    val useAcceleration: Boolean,
)

data class OptimizationResult(
    val selected: RuntimeCandidate?,
    val candidates: List<RuntimeCandidate>,
    val configuration: OptimizationConfiguration?,
)

class AutoOptimizer {
    fun select(
        device: DeviceProfile,
        model: ModelRequirements,
        runtimes: List<RuntimeDescriptor>,
        benchmarks: List<BenchmarkObservation> = emptyList(),
        policy: OptimizationPolicy = OptimizationPolicy(),
    ): OptimizationResult {
        val benchmarkByRuntime = benchmarks
            .groupBy { it.runtimeId }
            .mapValues { (_, values) -> values.maxByOrNull { it.tokensPerSecond } }

        val candidates = runtimes
            .map { runtime ->
                val compatibility = compatibility(runtime, device, model, policy)
                RuntimeCandidate(
                    descriptor = runtime,
                    compatibility = compatibility,
                    score = if (compatibility.compatible) {
                        score(runtime, compatibility, device, benchmarkByRuntime[runtime.id], policy)
                    } else {
                        null
                    },
                )
            }
            .sortedWith(
                compareBy<RuntimeCandidate> { if (it.compatibility.compatible) 0 else 1 }
                    .thenByDescending { it.score ?: Double.NEGATIVE_INFINITY }
                    .thenBy { it.descriptor.id.value },
            )

        val selected = candidates.firstOrNull { it.compatibility.compatible }
        return OptimizationResult(
            selected = selected,
            candidates = candidates,
            configuration = selected?.let { configuration(device, model, it.descriptor, policy) },
        )
    }

    private fun compatibility(
        runtime: RuntimeDescriptor,
        device: DeviceProfile,
        model: ModelRequirements,
        policy: OptimizationPolicy,
    ): RuntimeCompatibilityReport {
        val reasons = mutableListOf<RuntimeIncompatibilityReason>().apply {
            if (!runtime.isAvailable) add(RuntimeIncompatibilityReason.RuntimeUnavailable)
            if (device.androidApi < runtime.minAndroidApi) {
                add(RuntimeIncompatibilityReason.AndroidApiTooLow)
            }
            if (runtime.supportedAbis.intersect(device.supportedAbis.toSet()).isEmpty()) {
                add(RuntimeIncompatibilityReason.AbiUnsupported)
            }
            if (model.workload !in runtime.workloads) {
                add(RuntimeIncompatibilityReason.WorkloadUnsupported)
            }
            if (runtime.requiresVulkan && !device.hasVulkan) {
                add(RuntimeIncompatibilityReason.VulkanUnavailable)
            }
            val maxContextTokens = runtime.maxContextTokens
            if (maxContextTokens != null && model.contextTokens > maxContextTokens) {
                add(RuntimeIncompatibilityReason.ContextTooLarge)
            }
        }

        val baseMemory = saturatingAdd(model.estimatedWorkingSetBytes, runtime.memoryOverheadBytes)
        val estimatedPeak = withMargin(baseMemory, policy.memorySafetyMarginRatio)
        val availableMemory = device.availableMemoryBytes
        if (availableMemory == null) {
            reasons += RuntimeIncompatibilityReason.MemoryUnknown
        } else if (estimatedPeak > availableMemory) {
            reasons += RuntimeIncompatibilityReason.InsufficientMemory
        }

        return RuntimeCompatibilityReport(
            compatible = reasons.isEmpty(),
            reasons = reasons.distinct(),
            estimatedPeakMemoryBytes = estimatedPeak,
        )
    }

    private fun score(
        runtime: RuntimeDescriptor,
        compatibility: RuntimeCompatibilityReport,
        device: DeviceProfile,
        benchmark: BenchmarkObservation?,
        policy: OptimizationPolicy,
    ): Double {
        val preferenceIndex = policy.preferredBackends.indexOf(runtime.acceleration)
        val backendPriority = if (preferenceIndex >= 0) {
            (policy.preferredBackends.size - preferenceIndex) * 100.0
        } else {
            0.0
        }
        val measuredPerformance = benchmark?.let { 10_000.0 + it.tokensPerSecond * 100.0 } ?: 0.0
        val memoryHeadroom = device.availableMemoryBytes?.let { available ->
            val peak = compatibility.estimatedPeakMemoryBytes ?: return@let 0.0
            if (peak == 0L) 2.0 else (available.toDouble() / peak.toDouble()).coerceIn(0.0, 2.0)
        } ?: 0.0
        val thermalPenalty = when (device.thermalStatus) {
            ThermalStatus.Hot -> 25.0
            ThermalStatus.Severe -> 75.0
            ThermalStatus.Critical -> 150.0
            else -> 0.0
        }
        val memoryPenalty = when (device.memoryPressure) {
            MemoryPressure.Constrained -> 25.0
            MemoryPressure.Critical -> 75.0
            else -> 0.0
        }
        return measuredPerformance + backendPriority + memoryHeadroom * 10.0 - thermalPenalty - memoryPenalty
    }

    private fun configuration(
        device: DeviceProfile,
        model: ModelRequirements,
        runtime: RuntimeDescriptor,
        policy: OptimizationPolicy,
    ): OptimizationConfiguration {
        val baselineThreads = minOf(device.cpuCoreCount.coerceAtLeast(1), policy.maxCpuThreads)
        val pressureLimitedThreads = when {
            device.thermalStatus == ThermalStatus.Severe || device.thermalStatus == ThermalStatus.Critical ->
                (baselineThreads / 2).coerceAtLeast(1)
            device.thermalStatus == ThermalStatus.Hot ->
                (baselineThreads * 3 / 4).coerceAtLeast(1)
            device.memoryPressure == MemoryPressure.Constrained || device.memoryPressure == MemoryPressure.Critical ->
                (baselineThreads / 2).coerceAtLeast(1)
            else -> baselineThreads
        }
        return OptimizationConfiguration(
            cpuThreads = pressureLimitedThreads,
            contextTokens = model.contextTokens,
            useAcceleration = runtime.acceleration != AccelerationBackend.Cpu,
        )
    }

    private fun withMargin(base: Long, ratio: Double): Long {
        if (base == Long.MAX_VALUE || base == 0L) return base
        val margin = ceil(base.toDouble() * ratio)
        return if (margin >= Long.MAX_VALUE - base) Long.MAX_VALUE else base + margin.toLong()
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
