package dev.androml.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.androml.core.network.HuggingFaceNetworkError
import dev.androml.core.network.HuggingFaceNetworkException
import dev.androml.core.security.SecretIntegrityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HuggingFaceDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val request = HuggingFaceDownloadWork.parseInput(inputData)
            ?: return failure("invalid-input")
        val application = applicationContext.applicationContext as? AndroMLApplication
            ?: return failure("application-unavailable")

        val accessToken = try {
            application.secretStore.read(HuggingFaceDownloadWork.HF_READ_TOKEN_SECRET_NAME)
        } catch (_: SecretIntegrityException) {
            return failure("secret-integrity")
        } catch (_: Exception) {
            return failure("secret-unavailable")
        }

        return try {
            var lastReportedBytes = 0L
            var lastReportedAtNanos = 0L
            val artifact = withContext(Dispatchers.IO) {
                application.artifactDownloader.download(
                    reference = request.reference,
                    descriptor = request.descriptor,
                    jobKey = requireNotNull(request.descriptor.sha256),
                    accessToken = accessToken,
                    onProgress = { progress ->
                        val nowNanos = System.nanoTime()
                        val isFinalUpdate = progress.bytesWritten == progress.totalBytes
                        val crossedByteThreshold =
                            progress.bytesWritten - lastReportedBytes >= PROGRESS_BYTE_STEP
                        val crossedTimeThreshold =
                            nowNanos - lastReportedAtNanos >= PROGRESS_TIME_STEP_NANOS
                        if (isFinalUpdate || crossedByteThreshold || crossedTimeThreshold) {
                            lastReportedBytes = progress.bytesWritten
                            lastReportedAtNanos = nowNanos
                            setProgressAsync(
                                workDataOf(
                                    HuggingFaceDownloadWork.PROGRESS_BYTES_KEY to progress.bytesWritten,
                                    HuggingFaceDownloadWork.PROGRESS_TOTAL_BYTES_KEY to progress.totalBytes,
                                ),
                            )
                        }
                    },
                )
            }
            withContext(Dispatchers.IO) {
                application.catalogRepository.markArtifactVerified(
                    reference = request.reference,
                    path = request.descriptor.path,
                    artifactSha256 = artifact.sha256,
                )
            }
            Result.success(
                workDataOf(
                    HuggingFaceDownloadWork.OUTPUT_SIZE_BYTES_KEY to artifact.sizeBytes,
                    HuggingFaceDownloadWork.OUTPUT_SHA256_KEY to artifact.sha256,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: HuggingFaceNetworkException) {
            if (error.code in RETRYABLE_ERRORS) {
                Result.retry()
            } else {
                failure(error.code.name)
            }
        } catch (_: Exception) {
            failure("unexpected")
        }
    }

    private fun failure(code: String): Result = Result.failure(
        Data.Builder()
            .putString(HuggingFaceDownloadWork.ERROR_CODE_KEY, code)
            .build(),
    )

    private companion object {
        const val PROGRESS_BYTE_STEP = 256L * 1024L
        const val PROGRESS_TIME_STEP_NANOS = 500_000_000L

        val RETRYABLE_ERRORS = setOf(
            HuggingFaceNetworkError.Transport,
            HuggingFaceNetworkError.RateLimited,
            HuggingFaceNetworkError.Server,
        )
    }
}
