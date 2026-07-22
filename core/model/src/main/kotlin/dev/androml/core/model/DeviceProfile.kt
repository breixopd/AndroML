package dev.androml.core.model

enum class ThermalStatus {
    Unknown,
    Nominal,
    Warm,
    Hot,
    Severe,
    Critical,
}
enum class MemoryPressure {
    Unknown,
    Nominal,
    Constrained,
    Critical,
}

enum class DeviceReadiness {
    Unknown,
    Ready,
    Constrained,
    Unsupported,
}

data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val androidApi: Int,
    val supportedAbis: List<String>,
    val cpuCoreCount: Int,
    val totalMemoryBytes: Long?,
    val availableMemoryBytes: Long?,
    val availableStorageBytes: Long?,
    val isCharging: Boolean,
    val thermalStatus: ThermalStatus,
    val hasVulkan: Boolean,
) {
    val stableKey: String
        get() = listOf(
            manufacturer.trim().lowercase(),
            model.trim().lowercase(),
            androidApi.toString(),
            supportedAbis.sorted().joinToString(","),
        ).joinToString("|")
    val deviceName: String
        get() = listOf(manufacturer, model)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString(" ")
            .ifEmpty { "Unknown device" }

    val memoryPressure: MemoryPressure
        get() {
            val total = totalMemoryBytes ?: return MemoryPressure.Unknown
            val available = availableMemoryBytes ?: return MemoryPressure.Unknown
            if (total <= 0 || available < 0) return MemoryPressure.Unknown

            val availableRatio = available.toDouble() / total.toDouble()
            return when {
                availableRatio < 0.15 -> MemoryPressure.Critical
                availableRatio < 0.30 -> MemoryPressure.Constrained
                else -> MemoryPressure.Nominal
            }
        }

    val readiness: DeviceReadiness
        get() = when {
            androidApi < MIN_SUPPORTED_API -> DeviceReadiness.Unsupported
            cpuCoreCount <= 0 || supportedAbis.isEmpty() -> DeviceReadiness.Unknown
            memoryPressure == MemoryPressure.Unknown -> DeviceReadiness.Unknown
            memoryPressure == MemoryPressure.Critical -> DeviceReadiness.Constrained
            thermalStatus == ThermalStatus.Severe || thermalStatus == ThermalStatus.Critical ->
                DeviceReadiness.Constrained
            else -> DeviceReadiness.Ready
        }

    val resourceSummary: String
        get() = buildList {
            add("$cpuCoreCount CPU cores")
            add("API $androidApi")
            add("ABI ${supportedAbis.joinToString(", ").ifEmpty { "unknown" }}")
            availableMemoryBytes?.let { add("${formatBytes(it)} memory free") }
            availableStorageBytes?.let { add("${formatBytes(it)} storage free") }
            add(if (hasVulkan) "Vulkan available" else "Vulkan unavailable")
        }.joinToString(" · ")

    companion object {
        private const val MIN_SUPPORTED_API = 29

        private fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024L * 1024L -> "%.1f GiB".format(bytes / 1024.0 / 1024.0 / 1024.0)
            bytes >= 1024L * 1024L -> "%.0f MiB".format(bytes / 1024.0 / 1024.0)
            else -> "$bytes B"
        }
    }
}
