package dev.androml.core.network

import dev.androml.core.model.HuggingFaceFileDescriptor
import dev.androml.core.model.HuggingFaceModelReference
import java.net.URI

/**
 * Builds the small set of Hub URLs used by the app.
 *
 * The base is intentionally fixed to the public Hugging Face origin for v1.
 * A future mirror feature must introduce its own explicit trust policy rather
 * than accepting arbitrary user-provided URLs here.
 */
class HuggingFaceEndpoints(
    baseUri: URI = URI.create(DEFAULT_BASE_URL),
) {
    private val origin: String

    init {
        require(baseUri.scheme == "https") { "Hugging Face endpoint must use HTTPS" }
        require(baseUri.host == OFFICIAL_HOST) { "Hugging Face endpoint must use the official host" }
        require(baseUri.port == -1) { "Hugging Face endpoint must not specify a custom port" }
        require(baseUri.userInfo == null) { "Hugging Face endpoint must not contain credentials" }
        require(baseUri.query == null && baseUri.fragment == null) {
            "Hugging Face endpoint must not contain query or fragment data"
        }
        require(baseUri.path.isEmpty() || baseUri.path == "/") {
            "Hugging Face endpoint must be an origin"
        }
        origin = "https://$OFFICIAL_HOST"
    }

    fun modelInfo(reference: HuggingFaceModelReference): URI =
        URI.create(
            "$origin/api/models/${encodePath(reference.modelId.value)}" +
                "?revision=${reference.revision.value}",
        )

    fun fileDownload(
        reference: HuggingFaceModelReference,
        descriptor: HuggingFaceFileDescriptor,
    ): URI = URI.create(
        "$origin/${encodePath(reference.modelId.value)}/resolve/${reference.revision.value}/" +
            encodePath(descriptor.path),
    )

    private fun encodePath(value: String): String =
        value.split('/').joinToString("/") { encodeSegment(it) }

    private fun encodeSegment(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val code = byte.toInt() and 0xff
            if (code.toChar() in SAFE_ASCII) {
                append(code.toChar())
            } else {
                append('%')
                append(HEX[code ushr 4])
                append(HEX[code and 0x0f])
            }
        }
    }

    private companion object {
        const val DEFAULT_BASE_URL = "https://huggingface.co"
        const val OFFICIAL_HOST = "huggingface.co"
        const val HEX = "0123456789ABCDEF"
        val SAFE_ASCII = buildSet {
            addAll('0'..'9')
            addAll('A'..'Z')
            addAll('a'..'z')
            addAll(charArrayOf('-', '.', '_', '~').toList())
        }
    }
}
