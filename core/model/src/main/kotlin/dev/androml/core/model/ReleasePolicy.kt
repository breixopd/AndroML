package dev.androml.core.model

/** Distribution targets known to the release pipeline. */
enum class ReleaseChannel {
    GitHubRelease,
    GooglePlay,
    Fdroid,
    ProjectFdroidRepository,
    Accrescent,
    IzzyOnDroid,
}

/**
 * Product-level safety gate for distributing builds during the private phone test period.
 *
 * The default factory deliberately permits only GitHub Releases. Store publication requires
 * constructing a separately approved policy in a future release-management boundary; it is not
 * possible to enable it through an app setting or downloaded configuration.
 */
data class ReleasePolicy(
    val permittedChannels: Set<ReleaseChannel>,
    val storeSubmissionStatus: String,
) {
    fun allows(channel: ReleaseChannel): Boolean = channel in permittedChannels

    companion object {
        fun testPeriod(): ReleasePolicy = ReleasePolicy(
            permittedChannels = setOf(ReleaseChannel.GitHubRelease),
            storeSubmissionStatus = "Store submission is disabled pending user approval.",
        )
    }
}

