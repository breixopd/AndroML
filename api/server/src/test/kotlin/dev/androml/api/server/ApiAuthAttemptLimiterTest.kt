package dev.androml.api.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiAuthAttemptLimiterTest {
    @Test
    fun boundsConcurrentVerificationGloballyAndPerSource() {
        val limiter = ApiAuthAttemptLimiter(maxConcurrent = 2, maxConcurrentPerSource = 1)

        assertTrue(limiter.tryAcquire("peer-a"))
        assertFalse(limiter.tryAcquire("peer-a"))
        assertTrue(limiter.tryAcquire("peer-b"))
        assertFalse(limiter.tryAcquire("peer-c"))

        limiter.release("peer-a")
        assertTrue(limiter.tryAcquire("peer-c"))
    }

    @Test
    fun failureBudgetExpiresAfterItsWindow() {
        var now = 0L
        val limiter = ApiAuthAttemptLimiter(
            maxFailuresPerWindow = 2,
            failureWindowNanos = 100L,
            clockNanos = { now },
        )

        repeat(2) {
            assertTrue(limiter.tryAcquire("peer"))
            limiter.recordFailure("peer")
            limiter.release("peer")
        }
        assertFalse(limiter.tryAcquire("peer"))

        now = 100L
        assertTrue(limiter.tryAcquire("peer"))
    }
}
