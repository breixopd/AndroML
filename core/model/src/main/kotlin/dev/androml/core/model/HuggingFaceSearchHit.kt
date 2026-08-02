package dev.androml.core.model

/** A bounded, immutable result from the official Hugging Face model search endpoint. */
data class HuggingFaceSearchHit(
    val modelId: String,
    val revision: String?,
    val pipelineTag: String?,
    val downloads: Long?,
    val likes: Long?,
    val libraryName: String? = null,
    val tags: List<String> = emptyList(),
    val filePaths: List<String> = emptyList(),
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
        require(tags.size <= MAX_TAGS) { "too many model tags" }
        require(tags.all { it.length <= MAX_TAG_LENGTH }) { "model tag is too long" }
        require(filePaths.size <= MAX_FILE_PATHS) { "too many model files" }
        require(filePaths.all { it.length in 1..MAX_FILE_PATH_LENGTH }) {
            "model file path is too long"
        }
    }

    companion object {
        private const val MAX_TAGS = 256
        private const val MAX_TAG_LENGTH = 256
        private const val MAX_FILE_PATHS = 2_000
        private const val MAX_FILE_PATH_LENGTH = 512
    }
}
