package dev.androml.core.model

enum class ModelWorkload {
    TextGeneration,
    TextEmbedding,
    ImageGeneration,
    SpeechToText,
}

data class ModelRequirements(
    val workload: ModelWorkload,
    val weightBytes: Long,
    val kvCacheBytesPerToken: Long = 0L,
    val contextTokens: Int = 0,
) {
    init {
        require(weightBytes >= 0) { "weightBytes must be non-negative" }
        require(kvCacheBytesPerToken >= 0) { "kvCacheBytesPerToken must be non-negative" }
        require(contextTokens >= 0) { "contextTokens must be non-negative" }
    }

    val estimatedWorkingSetBytes: Long
        get() = saturatingAdd(weightBytes, saturatingMultiply(kvCacheBytesPerToken, contextTokens.toLong()))

    private companion object {
        fun saturatingAdd(left: Long, right: Long): Long =
            if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

        fun saturatingMultiply(left: Long, right: Long): Long =
            if (left == 0L || right == 0L) {
                0L
            } else if (left > Long.MAX_VALUE / right) {
                Long.MAX_VALUE
            } else {
                left * right
            }
    }
}
