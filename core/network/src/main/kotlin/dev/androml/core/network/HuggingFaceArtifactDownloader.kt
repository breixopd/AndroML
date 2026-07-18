package dev.androml.core.network

import dev.androml.core.files.ArtifactIntegrityException
import dev.androml.core.files.ArtifactSizeException
import dev.androml.core.files.FileArtifactStore
import dev.androml.core.files.StoredArtifact
import dev.androml.core.model.HuggingFaceFileDescriptor
import dev.androml.core.model.HuggingFaceModelReference
import java.io.IOException
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response

data class DownloadProgress(
    val bytesWritten: Long,
    val totalBytes: Long,
)

/** Streams one pinned Hub file into durable app-private storage with range resume. */
class HuggingFaceArtifactDownloader(
    private val callFactory: Call.Factory,
    private val store: FileArtifactStore,
    private val endpoints: HuggingFaceEndpoints = HuggingFaceEndpoints(),
) {
    fun download(
        reference: HuggingFaceModelReference,
        descriptor: HuggingFaceFileDescriptor,
        jobKey: String,
        accessToken: String? = null,
        onProgress: (DownloadProgress) -> Unit = {},
    ): StoredArtifact {
        val expectedSha256 = descriptor.sha256 ?: throw HuggingFaceNetworkException(
            code = HuggingFaceNetworkError.InvalidMetadata,
            message = "Hugging Face file has no remote SHA-256 metadata",
        )
        val partial = store.beginResumable(jobKey, expectedSha256, descriptor.sizeBytes)
        return try {
            var offset = partial.bytesWritten
            onProgress(DownloadProgress(offset, descriptor.sizeBytes))

            if (offset == descriptor.sizeBytes) {
                try {
                    return partial.commit()
                } catch (_: ArtifactIntegrityException) {
                    partial.reset()
                    offset = 0L
                }
            }

            val request = Request.Builder()
                .url(endpoints.fileDownload(reference, descriptor).toString())
                .header("Accept", "application/octet-stream")
                .apply {
                    if (offset > 0) header("Range", "bytes=$offset-")
                    accessToken?.takeIf { it.isNotBlank() }?.let { token ->
                        header("Authorization", "Bearer $token")
                    }
                }
                .build()

            val response = try {
                callFactory.newCall(request).execute()
            } catch (error: IOException) {
                throw HuggingFaceNetworkException(
                    code = HuggingFaceNetworkError.Transport,
                    message = "Hugging Face download could not be completed",
                    cause = error,
                )
            }

            response.use {
                if (!response.isSuccessful) {
                    throw huggingFaceResponseException(response.code, response.header("Retry-After"))
                }

                offset = prepareResponse(response, offset, descriptor.sizeBytes, partial)
                val remaining = descriptor.sizeBytes - offset
                val contentLength = response.body.contentLength()
                if (contentLength >= 0 && contentLength > remaining) {
                    throw HuggingFaceNetworkException(
                        code = HuggingFaceNetworkError.ResponseTooLarge,
                        message = "Hugging Face file response exceeds the declared size",
                        httpStatus = response.code,
                    )
                }

                try {
                    partial.appendFrom(
                        input = response.body.byteStream(),
                        maxBytes = remaining,
                    ) { bytesWritten ->
                        onProgress(DownloadProgress(bytesWritten, descriptor.sizeBytes))
                    }
                } catch (error: ArtifactSizeException) {
                    throw HuggingFaceNetworkException(
                        code = HuggingFaceNetworkError.ResponseTooLarge,
                        message = "Hugging Face file response exceeds the declared size",
                        httpStatus = response.code,
                        cause = error,
                    )
                } catch (error: IOException) {
                    throw HuggingFaceNetworkException(
                        code = HuggingFaceNetworkError.Transport,
                        message = "Hugging Face file stream was interrupted",
                        httpStatus = response.code,
                        cause = error,
                    )
                }
            }

            if (partial.bytesWritten != descriptor.sizeBytes) {
                throw HuggingFaceNetworkException(
                    code = HuggingFaceNetworkError.InvalidMetadata,
                    message = "Hugging Face file ended before its declared size",
                )
            }
            partial.commit()
        } finally {
            partial.close()
        }
    }

    private fun prepareResponse(
        response: Response,
        requestedOffset: Long,
        expectedSize: Long,
        partial: FileArtifactStore.ResumableArtifact,
    ): Long {
        return when (response.code) {
            200 -> {
                if (requestedOffset > 0) partial.reset()
                0L
            }

            206 -> {
                val range = parseContentRange(response.header("Content-Range"))
                if (range.start != requestedOffset || range.total != expectedSize) {
                    throw HuggingFaceNetworkException(
                        code = HuggingFaceNetworkError.InvalidMetadata,
                        message = "Hugging Face range response does not match the requested offset",
                        httpStatus = response.code,
                    )
                }
                val contentLength = response.body.contentLength()
                val rangeLength = range.end - range.start + 1
                if (contentLength >= 0 && contentLength != rangeLength) {
                    throw HuggingFaceNetworkException(
                        code = HuggingFaceNetworkError.InvalidMetadata,
                        message = "Hugging Face range response length is inconsistent",
                        httpStatus = response.code,
                    )
                }
                requestedOffset
            }

            else -> throw huggingFaceResponseException(response.code, response.header("Retry-After"))
        }
    }

    private fun parseContentRange(raw: String?): ByteRange {
        val match = CONTENT_RANGE_PATTERN.matchEntire(raw.orEmpty())
            ?: throw HuggingFaceNetworkException(
                code = HuggingFaceNetworkError.InvalidMetadata,
                message = "Hugging Face range response is missing a valid Content-Range",
                httpStatus = 206,
            )
        val start = match.groupValues[1].toLongOrNull()
        val end = match.groupValues[2].toLongOrNull()
        val total = match.groupValues[3].toLongOrNull()
        if (start == null || end == null || total == null || start < 0 || end < start || total <= end) {
            throw HuggingFaceNetworkException(
                code = HuggingFaceNetworkError.InvalidMetadata,
                message = "Hugging Face range response has invalid bounds",
                httpStatus = 206,
            )
        }
        return ByteRange(start, end, total)
    }

    private data class ByteRange(
        val start: Long,
        val end: Long,
        val total: Long,
    )

    private companion object {
        val CONTENT_RANGE_PATTERN = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
    }
}
