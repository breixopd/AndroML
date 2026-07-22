package dev.androml.core.network

import dev.androml.core.model.HuggingFaceModelReference
import dev.androml.core.model.HuggingFaceRepositoryMetadata
import dev.androml.core.model.HuggingFaceSearchHit
import java.io.ByteArrayOutputStream
import java.io.IOException
import okhttp3.Call
import okhttp3.Request
import okhttp3.ResponseBody

enum class HuggingFaceNetworkError {
    Unauthorized,
    Forbidden,
    NotFound,
    RateLimited,
    Server,
    UnexpectedStatus,
    ResponseTooLarge,
    Transport,
    InvalidMetadata,
}

class HuggingFaceNetworkException(
    val code: HuggingFaceNetworkError,
    message: String,
    val httpStatus: Int? = null,
    val retryAfterSeconds: Long? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Synchronous Hub model-info client.
 *
 * Callers must invoke this from a worker/IO thread. The response is bounded
 * before parsing, and OkHttp's response body is always closed by this class.
 */
class HuggingFaceModelClient(
    private val callFactory: Call.Factory,
    private val endpoints: HuggingFaceEndpoints = HuggingFaceEndpoints(),
    private val metadataParser: HuggingFaceMetadataParser = HuggingFaceMetadataParser(),
    private val searchParser: HuggingFaceSearchParser = HuggingFaceSearchParser(),
) {
    fun searchModels(
        query: String,
        limit: Int = 20,
        accessToken: String? = null,
    ): List<HuggingFaceSearchHit> {
        val request = Request.Builder()
            .url(endpoints.searchModels(query, limit).toString())
            .header("Accept", "application/json")
            .apply {
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
                message = "Hugging Face search could not be completed",
                cause = error,
            )
        }
        response.use {
            if (!response.isSuccessful) {
                throw huggingFaceResponseException(response.code, response.header("Retry-After"))
            }
            val body = response.body
            if (body.contentLength() > MAX_SEARCH_RESPONSE_BYTES) {
                throw HuggingFaceNetworkException(
                    code = HuggingFaceNetworkError.ResponseTooLarge,
                    message = "Hugging Face search response exceeds the safety limit",
                    httpStatus = response.code,
                )
            }
            return try {
                searchParser.parse(readBounded(body, MAX_SEARCH_RESPONSE_BYTES))
            } catch (error: HuggingFaceMetadataException) {
                throw HuggingFaceNetworkException(
                    code = HuggingFaceNetworkError.InvalidMetadata,
                    message = "Hugging Face search response failed validation",
                    httpStatus = response.code,
                    cause = error,
                )
            }
        }
    }

    fun fetchMetadata(
        reference: HuggingFaceModelReference,
        accessToken: String? = null,
    ): HuggingFaceRepositoryMetadata {
        val request = Request.Builder()
            .url(endpoints.modelInfo(reference).toString())
            .header("Accept", "application/json")
            .apply {
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
                message = "Hugging Face request could not be completed",
                cause = error,
            )
        }

        response.use {
            if (!response.isSuccessful) {
                throw huggingFaceResponseException(response.code, response.header("Retry-After"))
            }

            val body = response.body
            if (body.contentLength() > MAX_METADATA_RESPONSE_BYTES) {
                throw HuggingFaceNetworkException(
                    code = HuggingFaceNetworkError.ResponseTooLarge,
                    message = "Hugging Face metadata response exceeds the safety limit",
                    httpStatus = response.code,
                )
            }

            val bodyText = readBounded(body, MAX_METADATA_RESPONSE_BYTES)
            return try {
                metadataParser.parse(reference, bodyText)
            } catch (error: HuggingFaceMetadataException) {
                throw HuggingFaceNetworkException(
                    code = HuggingFaceNetworkError.InvalidMetadata,
                    message = "Hugging Face metadata response failed validation",
                    httpStatus = response.code,
                    cause = error,
                )
            }
        }
    }

    private fun readBounded(body: ResponseBody, maxBytes: Long): String {
        val output = ByteArrayOutputStream(INITIAL_BUFFER_BYTES)
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var totalBytes = 0L
        body.byteStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                totalBytes += read
                if (totalBytes > maxBytes) {
                    throw HuggingFaceNetworkException(
                        code = HuggingFaceNetworkError.ResponseTooLarge,
                        message = "Hugging Face metadata response exceeds the safety limit",
                    )
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    private companion object {
        const val MAX_METADATA_RESPONSE_BYTES = 2L * 1024L * 1024L
        const val MAX_SEARCH_RESPONSE_BYTES = 2L * 1024L * 1024L
        const val INITIAL_BUFFER_BYTES = 8 * 1024
        const val READ_BUFFER_BYTES = 16 * 1024
    }
}

internal fun huggingFaceResponseException(
    status: Int,
    retryAfterHeader: String?,
): HuggingFaceNetworkException {
    val error = when (status) {
        401 -> HuggingFaceNetworkError.Unauthorized
        403 -> HuggingFaceNetworkError.Forbidden
        404 -> HuggingFaceNetworkError.NotFound
        429 -> HuggingFaceNetworkError.RateLimited
        in 500..599 -> HuggingFaceNetworkError.Server
        else -> HuggingFaceNetworkError.UnexpectedStatus
    }
    return HuggingFaceNetworkException(
        code = error,
        message = "Hugging Face request failed with HTTP $status",
        httpStatus = status,
        retryAfterSeconds = retryAfterHeader
            ?.toLongOrNull()
            ?.takeIf { it >= 0 },
    )
}
