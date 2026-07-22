package dev.androml.core.model

/** A bounded, immutable result from the official Hugging Face model search endpoint. */
data class HuggingFaceSearchHit(
    val modelId: String,
    val revision: String?,
    val pipelineTag: String?,
    val downloads: Long?,
    val likes: Long?,
) {
    init {
        require(modelId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}/[A-Za-z0-9][A-Za-z0-9._-]{0,95}"))) {
            "Hugging Face model ID is invalid"
        }
        require(revision == null || revision.matches(Regex("[0-9a-f]{40}"))) {
            "Hugging Face revision is invalid"
        }
        require(downloads == null || downloads >= 0L) { "downloads must be non-negative" }
        require(likes == null || likes >= 0L) { "likes must be non-negative" }
    }
}
