package dev.androml.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.androml.cluster.core.BeginAttempt
import dev.androml.cluster.core.ClusterJobLedger
import dev.androml.cluster.core.ContentHash
import dev.androml.cluster.core.JobAttemptKey
import dev.androml.cluster.core.JobState

@Dao
interface ClusterJobAttemptDao {
    @Query("SELECT * FROM cluster_job_attempts WHERE jobId = :jobId AND attempt = :attempt")
    fun find(jobId: String, attempt: Int): ClusterJobAttemptEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(entity: ClusterJobAttemptEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun replace(entity: ClusterJobAttemptEntity)
}

/**
 * Room-backed idempotency ledger. Calls are synchronized so a single app process cannot race
 * begin/complete transitions; state survives listener restarts and process recreation.
 */
class ClusterJobLedgerRepository(
    private val dao: ClusterJobAttemptDao,
) : ClusterJobLedger {
    @Synchronized
    override fun begin(key: JobAttemptKey): BeginAttempt = when (val existing = dao.find(key.jobId.value, key.attempt)) {
        null -> {
            dao.insert(
                ClusterJobAttemptEntity(
                    jobId = key.jobId.value,
                    attempt = key.attempt,
                    state = JobState.Running.name,
                    outputHash = null,
                    output = null,
                    leaseExpiresAtEpochMillis = Long.MAX_VALUE,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                ),
            )
            BeginAttempt.Started
        }
        else -> when (existing.state) {
            JobState.Running.name -> BeginAttempt.AlreadyRunning
            JobState.Completed.name -> BeginAttempt.Completed
            JobState.Failed.name -> BeginAttempt.Failed
            else -> error("unknown persisted cluster job state")
        }
    }

    @Synchronized
    override fun begin(key: JobAttemptKey, nowEpochMillis: Long, leaseMillis: Long): BeginAttempt {
        require(leaseMillis in 1_000L..24 * 60 * 60 * 1_000L) { "cluster lease is out of bounds" }
        val existing = dao.find(key.jobId.value, key.attempt)
        if (existing == null) {
            dao.insert(
                ClusterJobAttemptEntity(
                    jobId = key.jobId.value,
                    attempt = key.attempt,
                    state = JobState.Running.name,
                    outputHash = null,
                    output = null,
                    leaseExpiresAtEpochMillis = nowEpochMillis + leaseMillis,
                    updatedAtEpochMillis = nowEpochMillis,
                ),
            )
            return BeginAttempt.Started
        }
        if (existing.state == JobState.Running.name && existing.leaseExpiresAtEpochMillis <= nowEpochMillis) {
            dao.replace(existing.copy(leaseExpiresAtEpochMillis = nowEpochMillis + leaseMillis, updatedAtEpochMillis = nowEpochMillis))
            return BeginAttempt.Started
        }
        return when (existing.state) {
            JobState.Running.name -> BeginAttempt.AlreadyRunning
            JobState.Completed.name -> BeginAttempt.Completed
            JobState.Failed.name -> BeginAttempt.Failed
            else -> error("unknown persisted cluster job state")
        }
    }

    @Synchronized
    override fun complete(key: JobAttemptKey, outputHash: ContentHash, output: ByteArray?) {
        val existing = requireNotNull(dao.find(key.jobId.value, key.attempt)) { "job attempt was not started" }
        check(existing.state == JobState.Running.name) { "job attempt is not running" }
        require(output == null || output.size <= 1 * 1024 * 1024) { "cluster output exceeds the safety limit" }
        dao.replace(existing.copy(
            state = JobState.Completed.name,
            outputHash = outputHash.value,
            output = output?.copyOf(),
            leaseExpiresAtEpochMillis = existing.leaseExpiresAtEpochMillis,
            updatedAtEpochMillis = System.currentTimeMillis(),
        ))
    }

    @Synchronized
    override fun fail(key: JobAttemptKey) {
        val existing = requireNotNull(dao.find(key.jobId.value, key.attempt)) { "job attempt was not started" }
        check(existing.state == JobState.Running.name) { "job attempt is not running" }
        dao.replace(existing.copy(state = JobState.Failed.name, updatedAtEpochMillis = System.currentTimeMillis()))
    }

    override fun state(key: JobAttemptKey): JobState? = dao.find(key.jobId.value, key.attempt)?.state?.let(::parseState)

    override fun outputHash(key: JobAttemptKey): ContentHash? = dao.find(key.jobId.value, key.attempt)?.outputHash?.let(ContentHash::parse)

    override fun output(key: JobAttemptKey): ByteArray? = dao.find(key.jobId.value, key.attempt)?.output?.copyOf()

    private fun parseState(raw: String): JobState = JobState.entries.firstOrNull { it.name == raw }
        ?: error("unknown persisted cluster job state")
}
