package dev.androml.cluster.core

import dev.androml.core.rag.Citation
import dev.androml.core.rag.ChunkId
import dev.androml.core.rag.CollectionId
import dev.androml.core.rag.DocumentId
import dev.androml.core.rag.RetrievalQuery
import dev.androml.core.rag.RetrievalResult
import dev.androml.core.rag.SourceSpan
import dev.androml.core.rag.TextChunk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ClusterRagSearchTask(
    val collectionId: CollectionId,
    val query: RetrievalQuery,
)

data class ClusterRagSearchResult(
    val results: List<RetrievalResult>,
) {
    init {
        require(results.size <= MAX_RESULTS) { "too many cluster RAG results" }
    }

    private companion object {
        const val MAX_RESULTS = 100
    }
}

/** Bounded citation-preserving payloads for distributed local RAG search. */
object ClusterRagCodec {
    private const val MAX_TASK_BYTES = 64 * 1024
    private const val MAX_RESULT_BYTES = 512 * 1024
    private const val MAX_RESULTS = 100
    private const val TASK_KIND = "rag_search_task"
    private const val RESULT_KIND = "rag_search_result"
    private val json = Json { explicitNulls = false }

    fun encodeTask(task: ClusterRagSearchTask): ByteArray = encode(
        buildJsonObject {
            put("kind", TASK_KIND)
            put("collection_id", task.collectionId.value)
            put("query", task.query.text)
            put("top_k", task.query.topK)
            put("lexical_weight", task.query.lexicalWeight)
            put("semantic_weight", task.query.semanticWeight)
            put("max_per_document", task.query.maxPerDocument)
            put("context_character_budget", task.query.contextCharacterBudget)
        },
        MAX_TASK_BYTES,
    )

    fun decodeTask(raw: ByteArray): ClusterRagSearchTask {
        val root = parse(raw, MAX_TASK_BYTES)
        require(root.requiredString("kind") == TASK_KIND) { "cluster payload kind is not RAG search" }
        return ClusterRagSearchTask(
            collectionId = CollectionId.parse(root.requiredString("collection_id")),
            query = RetrievalQuery(
                text = root.requiredString("query", 16_384),
                topK = root.requiredInt("top_k"),
                lexicalWeight = root.requiredDouble("lexical_weight"),
                semanticWeight = root.requiredDouble("semantic_weight"),
                maxPerDocument = root.requiredInt("max_per_document"),
                contextCharacterBudget = root.requiredInt("context_character_budget"),
            ),
        )
    }

    fun encodeResult(results: List<RetrievalResult>): ByteArray {
        require(results.size <= MAX_RESULTS) { "too many cluster RAG results" }
        return encode(
            buildJsonObject {
                put("kind", RESULT_KIND)
                put("results", buildJsonArray { results.forEach { add(encode(it)) } })
            },
            MAX_RESULT_BYTES,
        )
    }

    fun decodeResult(raw: ByteArray): List<RetrievalResult> {
        val root = parse(raw, MAX_RESULT_BYTES)
        require(root.requiredString("kind") == RESULT_KIND) { "cluster payload kind is not RAG result" }
        return ClusterRagSearchResult(
            root.requiredArray("results").map { element -> decodeResult(element.jsonObject) },
        ).results
    }

    private fun encode(result: RetrievalResult) = buildJsonObject {
        put("chunk", buildJsonObject {
            put("id", result.chunk.id.value)
            put("document_id", result.chunk.documentId.value)
            put("collection_id", result.chunk.collectionId.value)
            put("title", result.chunk.title)
            put("source", result.chunk.sourceLabel)
            put("text", result.chunk.text)
            put("span", encodeSpan(result.chunk.span))
            put("ordinal", result.chunk.ordinal)
        })
        put("citation", buildJsonObject {
            put("document_id", result.citation.documentId.value)
            put("collection_id", result.chunk.collectionId.value)
            put("title", result.citation.title)
            put("source", result.citation.sourceLabel)
            put("span", encodeSpan(result.citation.span))
            put("excerpt_hash", result.citation.excerptHash)
            put("lexical_score", result.citation.lexicalScore)
            put("semantic_score", result.citation.semanticScore)
            put("fused_score", result.citation.fusedScore)
        })
    }

    private fun decodeResult(root: JsonObject): RetrievalResult {
        val chunk = root["chunk"]?.jsonObject ?: throw IllegalArgumentException("RAG result chunk is missing")
        val citation = root["citation"]?.jsonObject ?: throw IllegalArgumentException("RAG result citation is missing")
        val decodedChunk = TextChunk(
            id = ChunkId.parse(chunk.requiredString("id")),
            documentId = DocumentId.parse(chunk.requiredString("document_id")),
            collectionId = CollectionId.parse(chunk.requiredString("collection_id")),
            title = chunk.requiredString("title", 512),
            sourceLabel = chunk.requiredString("source", 1_024),
            text = chunk.requiredString("text", 1 * 1024 * 1024),
            span = chunk.requiredSpan("span"),
            ordinal = chunk.requiredInt("ordinal"),
        )
        val decodedCitation = Citation(
            documentId = DocumentId.parse(citation.requiredString("document_id")),
            title = citation.requiredString("title", 512),
            sourceLabel = citation.requiredString("source", 1_024),
            span = citation.requiredSpan("span"),
            excerptHash = citation.requiredString("excerpt_hash", 64),
            lexicalScore = citation.requiredDouble("lexical_score"),
            semanticScore = citation.requiredDouble("semantic_score"),
            fusedScore = citation.requiredDouble("fused_score"),
        )
        require(decodedChunk.documentId == decodedCitation.documentId) {
            "RAG citation document binding is invalid"
        }
        require(decodedChunk.collectionId.value == citation.requiredString("collection_id")) {
            "RAG citation collection binding is invalid"
        }
        require(decodedChunk.title == decodedCitation.title && decodedChunk.sourceLabel == decodedCitation.sourceLabel) {
            "RAG citation source binding is invalid"
        }
        return RetrievalResult(decodedChunk, decodedCitation)
    }

    private fun encodeSpan(span: SourceSpan) = buildJsonObject {
        put("start", span.startOffset)
        put("end", span.endOffset)
        span.page?.let { put("page", it) }
        span.section?.let { put("section", it) }
    }

    private fun encode(root: JsonObject, maxBytes: Int): ByteArray {
        val bytes = json.encodeToString(root).toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxBytes) { "cluster RAG payload exceeds the safety limit" }
        return bytes
    }

    private fun parse(raw: ByteArray, maxBytes: Int): JsonObject {
        require(raw.size <= maxBytes) { "cluster RAG payload exceeds the safety limit" }
        return try {
            Json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject
        } catch (error: Exception) {
            throw IllegalArgumentException("cluster RAG payload is invalid", error)
        }
    }

    private fun JsonObject.requiredString(name: String, maxLength: Int = 1_024): String =
        this[name]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && it.length <= maxLength }
            ?: throw IllegalArgumentException("cluster RAG payload is missing or invalid $name")

    private fun JsonObject.requiredInt(name: String): Int =
        this[name]?.jsonPrimitive?.intOrNull
            ?: throw IllegalArgumentException("cluster RAG payload is missing $name")

    private fun JsonObject.requiredDouble(name: String): Double =
        this[name]?.jsonPrimitive?.doubleOrNull
            ?: throw IllegalArgumentException("cluster RAG payload is missing $name")

    private fun JsonObject.requiredArray(name: String): JsonArray =
        this[name]?.jsonArray ?: throw IllegalArgumentException("cluster RAG payload is missing $name")

    private fun JsonObject.requiredSpan(name: String): SourceSpan =
        this[name]?.jsonObject?.let { span ->
            SourceSpan(
                startOffset = span.requiredInt("start"),
                endOffset = span.requiredInt("end"),
                page = span["page"]?.jsonPrimitive?.intOrNull,
                section = span["section"]?.jsonPrimitive?.contentOrNull,
            )
        } ?: throw IllegalArgumentException("cluster RAG payload is missing $name")
}
