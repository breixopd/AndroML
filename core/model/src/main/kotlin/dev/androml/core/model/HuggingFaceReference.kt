package dev.androml.core.model

/**
 * A validated Hugging Face model repository identifier.
 *
 * The Hub accepts either a bare model name or an organization/name pair. The
 * value is kept as entered after validation so it can be used in URLs without
 * treating arbitrary path input as a repository identifier.
 */
@JvmInline
value class HuggingFaceModelId private constructor(val value: String) {
    companion object {
        private const val MAX_LENGTH = 96
        private val SEGMENT_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

        fun parse(raw: String): HuggingFaceModelId {
            require(raw == raw.trim()) { "Hugging Face model ID must not contain surrounding whitespace" }
            require(raw.length in 1..MAX_LENGTH) { "Hugging Face model ID has an invalid length" }

            val segments = raw.split('/')
            require(segments.size in 1..2) {
                "Hugging Face model ID must be name or organization/name"
            }
            require(segments.all { it.matches(SEGMENT_PATTERN) }) {
                "Hugging Face model ID contains an unsafe segment"
            }

            return HuggingFaceModelId(raw)
        }
    }
}

/**
 * An immutable, full-length Hub commit revision.
 *
 * The Hub also permits branches, tags, and pull-request refs, but this app
 * stores model installations by commit so a later branch update cannot change
 * what the user runs. The Hub download guide requires a full-length hash when
 * a commit revision is used:
 * https://huggingface.co/docs/huggingface_hub/guides/download#download-from-specific-version
 */
@JvmInline
value class HuggingFaceCommit private constructor(val value: String) {
    companion object {
        private val COMMIT_PATTERN = Regex("[0-9a-f]{40}")

        fun parse(raw: String): HuggingFaceCommit {
            require(raw.matches(COMMIT_PATTERN)) {
                "Hugging Face revision must be a 40-character lowercase commit hash"
            }
            return HuggingFaceCommit(raw)
        }
    }
}

data class HuggingFaceModelReference(
    val modelId: HuggingFaceModelId,
    val revision: HuggingFaceCommit,
) {
    companion object {
        fun parse(modelId: String, revision: String): HuggingFaceModelReference =
            HuggingFaceModelReference(
                modelId = HuggingFaceModelId.parse(modelId),
                revision = HuggingFaceCommit.parse(revision),
            )
    }
}

/**
 * File metadata obtained from a Hub repository listing before a download.
 *
 * Requiring a known, bounded size gives the download queue a finite storage
 * reservation and makes progress reporting safe before any bytes are written.
 */
data class HuggingFaceFileDescriptor(
    val path: String,
    val sizeBytes: Long,
    val sha256: String? = null,
) {
    init {
        require(path.length in 1..MAX_PATH_LENGTH) { "Hugging Face file path has an invalid length" }
        require(path == path.trim()) { "Hugging Face file path must not contain surrounding whitespace" }
        require(!path.startsWith('/') && !path.startsWith('\\')) {
            "Hugging Face file path must be relative"
        }
        require(!path.contains('\\')) { "Hugging Face file path must use forward slashes" }
        require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "Hugging Face file path contains an unsafe segment"
        }
        require(path.none { it.code < 0x20 || it.code == 0x7f }) {
            "Hugging Face file path contains a control character"
        }
        require(sizeBytes in 0..MAX_FILE_SIZE_BYTES) {
            "Hugging Face file size must be known and within the supported bound"
        }
        require(sha256 == null || sha256.matches(SHA256_PATTERN)) {
            "Hugging Face file SHA-256 must be a 64-character lowercase hex string"
        }
    }

    companion object {
        private const val MAX_PATH_LENGTH = 512
        private const val MAX_FILE_SIZE_BYTES = 1L shl 40
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}
