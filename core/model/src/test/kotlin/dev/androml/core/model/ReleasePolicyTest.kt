package dev.androml.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasePolicyTest {
    @Test
    fun testPeriodAllowsGitHubReleases() {
        assertTrue(ReleasePolicy.testPeriod().allows(ReleaseChannel.GitHubRelease))
    }

    @Test
    fun testPeriodBlocksEveryStoreSubmission() {
        val policy = ReleasePolicy.testPeriod()

        assertFalse(policy.allows(ReleaseChannel.GooglePlay))
        assertFalse(policy.allows(ReleaseChannel.Fdroid))
        assertFalse(policy.allows(ReleaseChannel.ProjectFdroidRepository))
        assertFalse(policy.allows(ReleaseChannel.Accrescent))
    }

    @Test
    fun testPeriodExplainsTheApprovalGate() {
        val policy = ReleasePolicy.testPeriod()

        assertTrue(policy.storeSubmissionStatus.contains("disabled", ignoreCase = true))
        assertTrue(policy.storeSubmissionStatus.contains("approval", ignoreCase = true))
    }
}

