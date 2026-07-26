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

    @Query("SELECT COUNT(*) FROM cluster_job_attempts")
    fun countAll(): Int

    @Query(
        "DELETE FROM cluster_job_attempts WHERE rowid IN " +
            "(SELECT rowid FROM cluster_job_attempts WHERE state != 'Running' " +
            "AND updatedAtEpochMillis <= :cutoffEpochMillis " +
            "ORDER BY updatedAtEpochMillis ASC LIMIT :limit)",
    )
    fun deleteTerminalOlderThan(cutoffEpochMillis: Long, limit: Int): Int

    @Query("DELETE FROM cluster_job_attempts WHERE state = 'Running' AND leaseExpiresAtEpochMillis <= :nowEpochMillis")
    fun deleteExpiredRunning(nowEpochMillis: Long): Int
}

/**
 * Room-backed idempotency ledger. Calls are synchronized so a single app process cannot race
 * begin/complete transitions; state survives listener restarts and process recreation.
 */
class ClusterJobLedgerRepository(
    private val dao: ClusterJobAttemptDao,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) : ClusterJobLedger {
    @Synchronized
    override fun begin(key: JobAttemptKey): BeginAttempt = when (val existing = dao.find(key.jobId.value, key.attempt)) {
        null -> {
            makeRoomForAttempt(nowEpochMillis())
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
            makeRoomForAttempt(nowEpochMillis)
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
            updatedAtEpochMillis = nowEpochMillis(),
        ))
    }

    @Synchronized
    override fun fail(key: JobAttemptKey) {
        val existing = requireNotNull(dao.find(key.jobId.value, key.attempt)) { "job attempt was not started" }
        check(existing.state == JobState.Running.name) { "job attempt is not running" }
        dao.replace(existing.copy(state = JobState.Failed.name, updatedAtEpochMillis = nowEpochMillis()))
    }

    override fun state(key: JobAttemptKey): JobState? = dao.find(key.jobId.value, key.attempt)?.state?.let(::parseState)

    override fun outputHash(key: JobAttemptKey): ContentHash? = dao.find(key.jobId.value, key.attempt)?.outputHash?.let(ContentHash::parse)

    override fun output(key: JobAttemptKey): ByteArray? = dao.find(key.jobId.value, key.attempt)?.output?.copyOf()

    private fun makeRoomForAttempt(nowEpochMillis: Long) {
        if (dao.countAll() < MAX_LEDGER_RECORDS) return
        dao.deleteExpiredRunning(nowEpochMillis)
        dao.deleteTerminalOlderThan(nowEpochMillis - REPLAY_RETENTION_MILLIS, PRUNE_BATCH)
        check(dao.countAll() < MAX_LEDGER_RECORDS) { "cluster job ledger capacity is exhausted" }
    }

    private fun parseState(raw: String): JobState = JobState.entries.firstOrNull { it.name == raw }
        ?: error("unknown persisted cluster job state")

    private companion object {
        const val MAX_LEDGER_RECORDS = 256
        const val PRUNE_BATCH = 32
        const val REPLAY_RETENTION_MILLIS = 24 * 60 * 60 * 1_000L
    }
}
