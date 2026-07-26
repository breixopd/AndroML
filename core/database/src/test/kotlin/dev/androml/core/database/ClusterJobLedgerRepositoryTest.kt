package dev.androml.core.database

import dev.androml.cluster.core.BeginAttempt
import dev.androml.cluster.core.ClusterJobId
import dev.androml.cluster.core.ContentHash
import dev.androml.cluster.core.JobAttemptKey
import dev.androml.cluster.core.JobState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ClusterJobLedgerRepositoryTest {
    @Test
    fun completedAttemptIsIdempotentAndOutputSurvivesRepositoryRecreation() {
        val dao = FakeDao()
        val first = ClusterJobLedgerRepository(dao)
        val key = JobAttemptKey(ClusterJobId.parse("job-1"), 1)
        val output = byteArrayOf(1, 2, 3)
        val hash = ContentHash.parse(
            "039058c6f2c0cb492c533b0a4d14ef77cc0c6d7b2f4f8f4f4c4f4f4f4f4f4f4f",
        )

        assertEquals(BeginAttempt.Started, first.begin(key))
        first.complete(key, hash, output)
        assertEquals(BeginAttempt.Completed, first.begin(key))

        val recreated = ClusterJobLedgerRepository(dao)
        assertEquals(JobState.Completed, recreated.state(key))
        assertEquals(hash, recreated.outputHash(key))
        assertArrayEquals(output, recreated.output(key))
    }

    @Test
    fun expiredRunningLeaseCanBeRecovered() {
        val dao = FakeDao()
        val ledger = ClusterJobLedgerRepository(dao)
        val key = JobAttemptKey(ClusterJobId.parse("job-recover"), 1)
        assertEquals(BeginAttempt.Started, ledger.begin(key, nowEpochMillis = 1_000L, leaseMillis = 1_000L))
        assertEquals(BeginAttempt.AlreadyRunning, ledger.begin(key, nowEpochMillis = 1_500L, leaseMillis = 1_000L))
        assertEquals(BeginAttempt.Started, ledger.begin(key, nowEpochMillis = 2_001L, leaseMillis = 1_000L))
    }

    @Test
    fun capacityFailsClosedWhileReplayRecordsAreWithinRetentionWindow() {
        val now = 2 * 24 * 60 * 60 * 1_000L
        val dao = FakeDao()
        repeat(256) { index ->
            dao.replace(terminalEntity("recent-$index", updatedAtEpochMillis = now - 1_000L))
        }

        val ledger = ClusterJobLedgerRepository(dao) { now }
        try {
            ledger.begin(JobAttemptKey(ClusterJobId.parse("new-job"), 1))
            fail("recent replay records must not be pruned to admit a new attempt")
        } catch (_: IllegalStateException) {
            // Capacity is deliberately fail-closed for the replay-retention window.
        }
        assertEquals(256, dao.countAll())
    }

    @Test
    fun capacityPrunesOnlyTerminalRecordsPastReplayRetention() {
        val now = 2 * 24 * 60 * 60 * 1_000L
        val dao = FakeDao()
        repeat(256) { index ->
            dao.replace(terminalEntity("old-$index", updatedAtEpochMillis = 0L))
        }

        val ledger = ClusterJobLedgerRepository(dao) { now }
        assertEquals(BeginAttempt.Started, ledger.begin(JobAttemptKey(ClusterJobId.parse("new-job"), 1)))
        assertEquals(225, dao.countAll())
    }

    private fun terminalEntity(jobId: String, updatedAtEpochMillis: Long) =
        ClusterJobAttemptEntity(
            jobId = jobId,
            attempt = 1,
            state = JobState.Completed.name,
            outputHash = "0".repeat(64),
            output = null,
            leaseExpiresAtEpochMillis = Long.MAX_VALUE,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )

    private class FakeDao : ClusterJobAttemptDao {
        private val rows = mutableMapOf<Pair<String, Int>, ClusterJobAttemptEntity>()

        override fun find(jobId: String, attempt: Int): ClusterJobAttemptEntity? = rows[jobId to attempt]

        override fun insert(entity: ClusterJobAttemptEntity) {
            check(rows.putIfAbsent(entity.jobId to entity.attempt, entity) == null)
        }

        override fun replace(entity: ClusterJobAttemptEntity) {
            rows[entity.jobId to entity.attempt] = entity
        }

        override fun countAll(): Int = rows.size

        override fun deleteTerminalOlderThan(cutoffEpochMillis: Long, limit: Int): Int {
            val keys = rows.entries
                .filter {
                    it.value.state != JobState.Running.name &&
                        it.value.updatedAtEpochMillis <= cutoffEpochMillis
                }
                .sortedBy { it.value.updatedAtEpochMillis }
                .take(limit)
                .map { it.key }
            keys.forEach(rows::remove)
            return keys.size
        }

        override fun deleteExpiredRunning(nowEpochMillis: Long): Int {
            val keys = rows.entries
                .filter {
                    it.value.state == JobState.Running.name &&
                        it.value.leaseExpiresAtEpochMillis <= nowEpochMillis
                }
                .map { it.key }
            keys.forEach(rows::remove)
            return keys.size
        }
    }
}
