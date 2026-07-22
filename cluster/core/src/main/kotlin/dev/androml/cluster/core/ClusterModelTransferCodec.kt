package dev.androml.cluster.core

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A bounded, resumable model transfer chunk. The bytes remain opaque to the cluster protocol. */
data class ClusterModelTransferChunk(
    val transferId: String,
    val artifactHash: ContentHash,
    val totalSizeBytes: Long,
    val offsetBytes: Long,
    val chunk: ByteArray,
    val finalChunk: Boolean,
    val modelId: String,
    val revision: String,
    val path: String,
    val license: String? = null,
    val isPrivate: Boolean = false,
    val isGated: Boolean = false,
) {
    init {
        require(transferId.matches(TRANSFER_ID_PATTERN)) { "transfer ID is invalid" }
        require(totalSizeBytes in 1..MAX_ARTIFACT_BYTES) { "transfer size is out of bounds" }
        require(offsetBytes in 0 until totalSizeBytes) { "transfer offset is out of bounds" }
        require(chunk.isNotEmpty() && chunk.size <= MAX_CHUNK_BYTES) { "transfer chunk is out of bounds" }
        require(offsetBytes + chunk.size <= totalSizeBytes) { "transfer chunk exceeds the artifact" }
        require(!finalChunk || offsetBytes + chunk.size == totalSizeBytes) {
            "final transfer chunk does not finish the artifact"
        }
        require(modelId.matches(MODEL_ID_PATTERN)) { "model ID is invalid" }
        require(revision.matches(REVISION_PATTERN)) { "model revision is invalid" }
        require(path.length in 1..512 && !path.startsWith('/') && !path.contains('\\')) {
            "model path is invalid"
        }
        require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "model path contains an unsafe segment"
        }
        require(license == null || license.length <= 256) { "model license is too long" }
    }

    companion object {
        const val MAX_CHUNK_BYTES = 512 * 1024
        const val MAX_ARTIFACT_BYTES = 1L shl 40
        private val TRANSFER_ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,127}")
        private val MODEL_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._-]*)?")
        private val REVISION_PATTERN = Regex("[0-9a-f]{40}")
    }
}

data class ClusterModelTransferAck(
    val transferId: String,
    val artifactHash: ContentHash,
    val nextOffsetBytes: Long,
    val committed: Boolean,
) {
    init {
        require(transferId.matches(Regex("[a-z0-9][a-z0-9._-]{0,127}"))) { "transfer ID is invalid" }
        require(nextOffsetBytes >= 0L) { "transfer offset is invalid" }
    }
}

object ClusterModelTransferCodec {
    private const val CHUNK_KIND = "model_transfer_chunk"
    private const val ACK_KIND = "model_transfer_ack"
    private const val MAX_CHUNK_JSON_BYTES = 768 * 1024
    private const val MAX_ACK_BYTES = 4 * 1024
    private val json = Json { explicitNulls = false }

    fun encodeChunk(chunk: ClusterModelTransferChunk): ByteArray = encode(
        buildJsonObject {
            put("kind", CHUNK_KIND)
            put("transfer_id", chunk.transferId)
            put("artifact_hash", chunk.artifactHash.value)
            put("total_size", chunk.totalSizeBytes)
            put("offset", chunk.offsetBytes)
            put("bytes", Base64.getEncoder().encodeToString(chunk.chunk))
            put("final", chunk.finalChunk)
            put("model_id", chunk.modelId)
            put("revision", chunk.revision)
            put("path", chunk.path)
            chunk.license?.let { put("license", it) }
            put("private", chunk.isPrivate)
            put("gated", chunk.isGated)
        },
        MAX_CHUNK_JSON_BYTES,
    )

    fun decodeChunk(raw: ByteArray): ClusterModelTransferChunk {
        val root = parse(raw, MAX_CHUNK_JSON_BYTES)
        require(root.requiredString("kind") == CHUNK_KIND) { "payload is not a model transfer chunk" }
        val encoded = root.requiredString("bytes")
        require(encoded.length <= ((ClusterModelTransferChunk.MAX_CHUNK_BYTES + 2) / 3) * 4 + 4) {
            "transfer bytes encoding is too large"
        }
        val bytes = Base64.getDecoder().decode(encoded)
        return ClusterModelTransferChunk(
            transferId = root.requiredString("transfer_id"),
            artifactHash = ContentHash.parse(root.requiredString("artifact_hash")),
            totalSizeBytes = root.requiredLong("total_size"),
            offsetBytes = root.requiredLong("offset"),
            chunk = bytes,
            finalChunk = root.requiredBoolean("final"),
            modelId = root.requiredString("model_id"),
            revision = root.requiredString("revision"),
            path = root.requiredString("path"),
            license = root.optionalString("license"),
            isPrivate = root.requiredBoolean("private"),
            isGated = root.requiredBoolean("gated"),
        )
    }

    fun encodeAck(ack: ClusterModelTransferAck): ByteArray = encode(
        buildJsonObject {
            put("kind", ACK_KIND)
            put("transfer_id", ack.transferId)
            put("artifact_hash", ack.artifactHash.value)
            put("next_offset", ack.nextOffsetBytes)
            put("committed", ack.committed)
        },
        MAX_ACK_BYTES,
    )

    fun decodeAck(raw: ByteArray): ClusterModelTransferAck {
        val root = parse(raw, MAX_ACK_BYTES)
        require(root.requiredString("kind") == ACK_KIND) { "payload is not a model transfer acknowledgement" }
        return ClusterModelTransferAck(
            transferId = root.requiredString("transfer_id"),
            artifactHash = ContentHash.parse(root.requiredString("artifact_hash")),
            nextOffsetBytes = root.requiredLong("next_offset"),
            committed = root.requiredBoolean("committed"),
        )
    }

    private fun encode(root: JsonObject, maxBytes: Int): ByteArray = json.encodeToString(root).toByteArray(Charsets.UTF_8)
        .also { require(it.size <= maxBytes) { "model transfer payload exceeds the safety limit" } }

    private fun parse(raw: ByteArray, maxBytes: Int): JsonObject {
        require(raw.size <= maxBytes) { "model transfer payload exceeds the safety limit" }
        return try {
            Json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject
        } catch (error: Exception) {
            throw IllegalArgumentException("model transfer payload is invalid", error)
        }
    }

    private fun JsonObject.requiredString(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("model transfer payload is missing $name")

    private fun JsonObject.optionalString(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)

    private fun JsonObject.requiredLong(name: String): Long =
        this[name]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("model transfer payload is missing $name")

    private fun JsonObject.requiredBoolean(name: String): Boolean =
        this[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: throw IllegalArgumentException("model transfer payload is missing $name")
}
