package dev.androml.core.database

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "runtime_benchmarks",
    primaryKeys = ["deviceKey", "runtimeId", "modelArtifactSha256", "profile"],
    indices = [Index(value = ["deviceKey", "modelArtifactSha256"])],
)
data class RuntimeBenchmarkEntity(
    val deviceKey: String,
    val runtimeId: String,
    val modelArtifactSha256: String,
    val profile: String,
    val tokensPerSecond: Double,
    val firstTokenLatencyMs: Double?,
    val outputValid: Boolean,
    val failureCount: Int,
    val measuredAtEpochMillis: Long,
) {
    init {
        require(deviceKey.isNotBlank() && deviceKey.length <= 512) { "device key is invalid" }
        require(runtimeId.matches(Regex("[a-z0-9][a-z0-9._-]*"))) { "runtime ID is invalid" }
        require(modelArtifactSha256.matches(Regex("[a-f0-9]{64}"))) { "model artifact hash is invalid" }
        require(profile.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,31}"))) { "benchmark profile is invalid" }
        require(tokensPerSecond >= 0.0 && tokensPerSecond.isFinite()) { "throughput is invalid" }
        require(firstTokenLatencyMs == null || firstTokenLatencyMs >= 0.0) { "latency is invalid" }
        require(failureCount >= 0) { "failure count is invalid" }
        require(measuredAtEpochMillis >= 0L) { "measurement time is invalid" }
    }
}
