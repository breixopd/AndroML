package dev.androml.core.database

import androidx.room.Entity

@Entity(
    tableName = "cluster_job_attempts",
    primaryKeys = ["jobId", "attempt"],
)
data class ClusterJobAttemptEntity(
    val jobId: String,
    val attempt: Int,
    val state: String,
    val outputHash: String?,
    val output: ByteArray?,
    val leaseExpiresAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
