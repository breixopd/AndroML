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
        val bytesWritten: Long = 0L,
        val totalBytes: Long = 0L,
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

fun huggingFaceWorkerUserMessage(errorCode: String?): String = when (errorCode) {
    "invalid-input" -> "The saved download request is invalid and was not sent."
    "application-unavailable" -> "AndroML could not start its download service."
    "secret-integrity", "secret-unavailable" ->
        "The saved Hugging Face token could not be read; the download was not authenticated."

    "Unauthorized" ->
        "Hugging Face rejected the request. Save a read token after approving access to the model."

    "Forbidden" ->
        "Hugging Face denied access. Approve the gated model in a browser, then save a read token."

    "NotFound" -> "That pinned model or revision was not found on Hugging Face."
    "ResponseTooLarge" -> "The file response exceeded AndroML's safety limit."
    "InvalidMetadata" -> "The file response failed integrity or range validation."
    "unexpected" -> "The background download failed without exposing internal error details."
    else -> "The background download failed. Check the connection and try again."
}
