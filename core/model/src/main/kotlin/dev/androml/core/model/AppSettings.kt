package dev.androml.core.model

/** User-controlled product switches. Security-sensitive defaults are deliberately restrictive. */
data class AppSettings(
    val expertMode: Boolean = true,
    val autoOptimize: Boolean = true,
    val allowBackgroundDownloads: Boolean = true,
    val thermalGuard: Boolean = true,
)
