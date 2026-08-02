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
class HuggingFaceEndpoints private constructor(
    private val baseUri: URI,
    private val allowTestOrigin: Boolean,
) {
    constructor() : this(URI.create(DEFAULT_BASE_URL), false)

    constructor(baseUri: URI) : this(baseUri, false)

    private val origin: String

    init {
        val isOfficialOrigin =
            baseUri.scheme == "https" && baseUri.host == OFFICIAL_HOST && baseUri.port == -1
        val isLocalTestOrigin =
            allowTestOrigin && baseUri.scheme == "http" && baseUri.host in LOCAL_TEST_HOSTS
        require(isOfficialOrigin || isLocalTestOrigin) {
            "Hugging Face endpoint must use the official HTTPS origin"
        }
        require(baseUri.host != null) { "Hugging Face endpoint must have a host" }
        require(baseUri.userInfo == null) { "Hugging Face endpoint must not contain credentials" }
        require(baseUri.query == null && baseUri.fragment == null) {
            "Hugging Face endpoint must not contain query or fragment data"
        }
        require(baseUri.path.isEmpty() || baseUri.path == "/") {
            "Hugging Face endpoint must be an origin"
        }
        origin = "${baseUri.scheme}://${baseUri.rawAuthority}"
    }

    fun modelInfo(reference: HuggingFaceModelReference): URI =
        URI.create(
            "$origin/api/models/${encodePath(reference.modelId.value)}" +
                "?revision=${reference.revision.value}&blobs=true",
        )

    fun searchModels(
        query: String = "",
        limit: Int = 20,
        sort: HuggingFaceModelSort = HuggingFaceModelSort.Popular,
        pipelineTag: String? = null,
        filter: String? = null,
    ): URI {
        require(query.length <= 256) { "search query is invalid" }
        require(limit in 1..50) { "search limit is out of bounds" }
        require(pipelineTag == null || pipelineTag.matches(TAG_PATTERN)) {
            "pipeline tag is invalid"
        }
        require(filter == null || filter.matches(TAG_PATTERN)) { "model filter is invalid" }
        val parameters = buildList {
            if (query.isNotBlank()) add("search=${encodeQuery(query)}")
            pipelineTag?.let { add("pipeline_tag=${encodeQuery(it)}") }
            filter?.let { add("filter=${encodeQuery(it)}") }
            add("sort=${encodeQuery(sort.queryValue)}")
            add("direction=${sort.direction}")
            add("limit=$limit")
            // Full records carry immutable SHAs and sibling paths, which lets the app
            // recommend only repositories that contain a bundled model format.
            add("full=true")
        }
        return URI.create("$origin/api/models?${parameters.joinToString("&")}")
    }

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

    private fun encodeQuery(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val code = byte.toInt() and 0xff
            if (code.toChar() in QUERY_SAFE_ASCII) {
                append(code.toChar())
            } else {
                append('%')
                append(HEX[code ushr 4])
                append(HEX[code and 0x0f])
            }
        }
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://huggingface.co"
        private const val OFFICIAL_HOST = "huggingface.co"
        private val TAG_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        private const val HEX = "0123456789ABCDEF"
        private val LOCAL_TEST_HOSTS = setOf("localhost", "127.0.0.1")
        private val SAFE_ASCII = buildSet {
            addAll('0'..'9')
            addAll('A'..'Z')
            addAll('a'..'z')
            addAll(charArrayOf('-', '.', '_', '~').toList())
        }
        private val QUERY_SAFE_ASCII = SAFE_ASCII + setOf(' ', '+').filter { it != ' ' }

        internal fun forTesting(baseUri: URI): HuggingFaceEndpoints =
            HuggingFaceEndpoints(baseUri, allowTestOrigin = true)
    }
}
