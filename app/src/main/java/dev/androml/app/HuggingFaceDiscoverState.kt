package dev.androml.app

import dev.androml.core.network.HuggingFaceNetworkError
import dev.androml.core.network.HuggingFaceNetworkException
import dev.androml.core.model.HuggingFaceRepositoryMetadata

sealed interface HuggingFaceMetadataUiState {
    data object Idle : HuggingFaceMetadataUiState

    data object Loading : HuggingFaceMetadataUiState

    data class Loaded(
        val metadata: HuggingFaceRepositoryMetadata,
    ) : HuggingFaceMetadataUiState

    data class Failed(
        val message: String,
    ) : HuggingFaceMetadataUiState
}

sealed interface HuggingFaceDownloadUiState {
    data object Idle : HuggingFaceDownloadUiState

    data class Running(
        val path: String,
    ) : HuggingFaceDownloadUiState

    data class Complete(
        val path: String,
        val sizeBytes: Long,
        val sha256: String,
    ) : HuggingFaceDownloadUiState

    data class Failed(
        val path: String,
        val message: String,
    ) : HuggingFaceDownloadUiState
}

fun huggingFaceUserMessage(error: Throwable): String = when (error) {
    is HuggingFaceNetworkException -> when (error.code) {
        HuggingFaceNetworkError.Unauthorized ->
            "Hugging Face rejected the request. Add a read token after approving access to the model."

        HuggingFaceNetworkError.Forbidden ->
            "Hugging Face denied access. Approve the gated model in a browser, then use a read token."

        HuggingFaceNetworkError.NotFound ->
            "That pinned model or revision was not found on Hugging Face."

        HuggingFaceNetworkError.RateLimited -> {
            val retryAfter = error.retryAfterSeconds
            if (retryAfter == null) {
                "Hugging Face rate-limited this request. Try again later."
            } else {
                "Hugging Face rate-limited this request. Try again in about $retryAfter seconds."
            }
        }

        HuggingFaceNetworkError.Server ->
            "Hugging Face is temporarily unavailable. Try again later."

        HuggingFaceNetworkError.ResponseTooLarge ->
            "The metadata response exceeded AndroML's safety limit."

        HuggingFaceNetworkError.InvalidMetadata ->
            "The pinned repository returned metadata that failed validation."

        HuggingFaceNetworkError.Transport ->
            "The network request could not complete. Check connectivity and try again."

        HuggingFaceNetworkError.UnexpectedStatus ->
            "Hugging Face returned an unexpected response. Try again later."
    }

    is IllegalArgumentException -> "The pinned source is invalid. Check the model ID and commit SHA."
    else -> "The operation failed without exposing the underlying error details."
}
