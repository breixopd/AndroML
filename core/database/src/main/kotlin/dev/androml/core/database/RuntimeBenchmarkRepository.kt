package dev.androml.core.database

import kotlinx.coroutines.flow.Flow

class RuntimeBenchmarkRepository(
    private val dao: RuntimeBenchmarkDao,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) {
    fun observe(deviceKey: String, modelArtifactSha256: String): Flow<List<RuntimeBenchmarkEntity>> =
        dao.observe(deviceKey, modelArtifactSha256)

    suspend fun snapshot(deviceKey: String, modelArtifactSha256: String): List<RuntimeBenchmarkEntity> =
        dao.snapshot(deviceKey, modelArtifactSha256)

    suspend fun record(
        deviceKey: String,
        runtimeId: String,
        modelArtifactSha256: String,
        profile: String,
        tokensPerSecond: Double,
        firstTokenLatencyMs: Double?,
        outputValid: Boolean,
        failureCount: Int = 0,
    ) {
        dao.upsert(
            RuntimeBenchmarkEntity(
                deviceKey = deviceKey,
                runtimeId = runtimeId,
                modelArtifactSha256 = modelArtifactSha256,
                profile = profile,
                tokensPerSecond = tokensPerSecond,
                firstTokenLatencyMs = firstTokenLatencyMs,
                outputValid = outputValid,
                failureCount = failureCount,
                measuredAtEpochMillis = nowEpochMillis(),
            ),
        )
    }

    suspend fun clearDevice(deviceKey: String) = dao.deleteForDevice(deviceKey)
}
