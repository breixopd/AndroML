package dev.androml.app

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.workDataOf
import dev.androml.core.model.HuggingFaceFileDescriptor
import dev.androml.core.model.HuggingFaceModelReference
import java.util.concurrent.TimeUnit

data class HuggingFaceDownloadRequest(
    val reference: HuggingFaceModelReference,
    val descriptor: HuggingFaceFileDescriptor,
)

object HuggingFaceDownloadWork {
    const val MODEL_ID_KEY = "model_id"
    const val REVISION_KEY = "revision"
    const val PATH_KEY = "path"
    const val SIZE_BYTES_KEY = "size_bytes"
    const val SHA256_KEY = "sha256"
    const val PROGRESS_BYTES_KEY = "progress_bytes"
    const val PROGRESS_TOTAL_BYTES_KEY = "progress_total_bytes"
    const val OUTPUT_SIZE_BYTES_KEY = "output_size_bytes"
    const val OUTPUT_SHA256_KEY = "output_sha256"
    const val ERROR_CODE_KEY = "error_code"
    const val HF_READ_TOKEN_SECRET_NAME = "huggingface.read-token"
    const val TAG = "huggingface-download"

    fun createRequest(
        reference: HuggingFaceModelReference,
        descriptor: HuggingFaceFileDescriptor,
    ): OneTimeWorkRequest {
        val sha256 = requireNotNull(descriptor.sha256) {
            "a download work request requires a remote SHA-256"
        }
        return OneTimeWorkRequestBuilder<HuggingFaceDownloadWorker>()
            .setInputData(createInputData(reference, descriptor, sha256))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
    }

    private fun createInputData(
        reference: HuggingFaceModelReference,
        descriptor: HuggingFaceFileDescriptor,
        sha256: String,
    ): Data = workDataOf(
        MODEL_ID_KEY to reference.modelId.value,
        REVISION_KEY to reference.revision.value,
        PATH_KEY to descriptor.path,
        SIZE_BYTES_KEY to descriptor.sizeBytes,
        SHA256_KEY to sha256,
    )

    fun parseInput(data: Data): HuggingFaceDownloadRequest? {
        val modelId = data.getString(MODEL_ID_KEY) ?: return null
        val revision = data.getString(REVISION_KEY) ?: return null
        val path = data.getString(PATH_KEY) ?: return null
        val sha256 = data.getString(SHA256_KEY) ?: return null
        val sizeBytes = data.getLong(SIZE_BYTES_KEY, Long.MIN_VALUE)
        if (sizeBytes == Long.MIN_VALUE) return null
        return try {
            HuggingFaceDownloadRequest(
                reference = HuggingFaceModelReference.parse(modelId, revision),
                descriptor = HuggingFaceFileDescriptor(
                    path = path,
                    sizeBytes = sizeBytes,
                    sha256 = sha256,
                ),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
