package dev.androml.core.model

data class HuggingFaceRepositoryMetadata(
    val reference: HuggingFaceModelReference,
    val files: List<HuggingFaceFileDescriptor>,
    val isPrivate: Boolean,
    val isGated: Boolean,
    val license: String?,
)
