package dev.androml.core.rag

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

@JvmInline
value class CollectionId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): CollectionId {
            require(raw.matches(ID_PATTERN)) { "collection ID contains unsafe characters" }
            return CollectionId(raw)
        }

        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    }
}

@JvmInline
value class DocumentId private constructor(val value: String) {
    companion object {
        fun fromContent(collectionId: CollectionId, contentHash: String): DocumentId {
            require(contentHash.matches(SHA256_PATTERN)) { "document hash must be SHA-256" }
            return DocumentId("${collectionId.value}:$contentHash")
        }

        fun parse(raw: String): DocumentId {
            require(raw.length in 3..128) { "document ID length is invalid" }
            require(raw.all { it.isLetterOrDigit() || it in ":._-" }) {
                "document ID contains unsafe characters"
            }
            return DocumentId(raw)
        }
    }
}

@JvmInline
value class ChunkId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): ChunkId {
            require(raw.matches(Regex("[a-f0-9]{64}"))) { "chunk ID must be a SHA-256" }
            return ChunkId(raw)
        }
    }
}

data class SourceSpan(
    val startOffset: Int,
    val endOffset: Int,
    val page: Int? = null,
    val section: String? = null,
) {
    init {
        require(startOffset >= 0) { "source span start must be non-negative" }
        require(endOffset >= startOffset) { "source span end must not precede start" }
        require(page == null || page > 0) { "page must be positive when present" }
        require(section == null || section.length <= 256) { "section label is too long" }
    }
}

data class RagDocument(
    val collectionId: CollectionId,
    val title: String,
    val sourceLabel: String,
    val text: String,
    val page: Int? = null,
    val section: String? = null,
) {
    init {
        require(title.isNotBlank() && title.length <= 512) { "document title is invalid" }
        require(sourceLabel.isNotBlank() && sourceLabel.length <= 1024) { "source label is invalid" }
        require(text.length <= MAX_DOCUMENT_CHARS) { "document text exceeds the safety limit" }
    }

    val normalizedText: String
        get() = TextNormalizer.normalize(text)

    val contentHash: String
        get() = sha256(normalizedText)

    val id: DocumentId
        get() = DocumentId.fromContent(collectionId, contentHash)

    companion object {
        const val MAX_DOCUMENT_CHARS = 64 * 1024 * 1024
    }
}

data class TextChunk(
    val id: ChunkId,
    val documentId: DocumentId,
    val collectionId: CollectionId,
    val title: String,
    val sourceLabel: String,
    val text: String,
    val span: SourceSpan,
    val ordinal: Int,
) {
    init {
        require(text.isNotBlank()) { "chunk text must not be blank" }
        require(ordinal >= 0) { "chunk ordinal must be non-negative" }
    }

    val tokenCount: Int
        get() = TextNormalizer.tokens(text).size
}

data class ChunkingOptions(
    val maxCharacters: Int = 1200,
    val overlapCharacters: Int = 160,
    val minCharacters: Int = 80,
) {
    init {
        require(maxCharacters in 128..32_768) { "max chunk size is out of bounds" }
        require(overlapCharacters in 0 until maxCharacters) { "chunk overlap is out of bounds" }
        require(minCharacters in 1..maxCharacters) { "minimum chunk size is out of bounds" }
    }
}

data class RetrievalQuery(
    val text: String,
    val topK: Int = 8,
    val lexicalWeight: Double = 0.55,
    val semanticWeight: Double = 0.45,
    val maxPerDocument: Int = 3,
    val contextCharacterBudget: Int = 12_000,
) {
    init {
        require(text.isNotBlank() && text.length <= 16_384) { "query is invalid" }
        require(topK in 1..100) { "topK is out of bounds" }
        require(lexicalWeight >= 0.0 && semanticWeight >= 0.0) { "retrieval weights must be non-negative" }
        require(lexicalWeight + semanticWeight > 0.0) { "at least one retrieval weight is required" }
        require(maxPerDocument in 1..100) { "max document diversity is out of bounds" }
        require(contextCharacterBudget in 256..1_000_000) { "context budget is out of bounds" }
    }
}

data class Citation(
    val documentId: DocumentId,
    val title: String,
    val sourceLabel: String,
    val span: SourceSpan,
    val excerptHash: String,
    val lexicalScore: Double,
    val semanticScore: Double,
    val fusedScore: Double,
) {
    init {
        require(excerptHash.matches(SHA256_PATTERN)) { "excerpt hash must be SHA-256" }
        require(lexicalScore.isFinite() && semanticScore.isFinite() && fusedScore.isFinite()) {
            "citation scores must be finite"
        }
    }
}

data class RetrievalResult(
    val chunk: TextChunk,
    val citation: Citation,
)

data class PackedContext(
    val results: List<RetrievalResult>,
    val omittedResults: Int,
    val characterCount: Int,
) {
    val citations: List<Citation>
        get() = results.map(RetrievalResult::citation)
}

object TextNormalizer {
    private val TOKEN_PATTERN = Regex("[\\p{L}\\p{N}]+")

    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .joinToString("\n") { it.trim().replace(Regex("[ \\t]+"), " ") }
        .trim()

    fun tokens(text: String): List<String> = TOKEN_PATTERN.findAll(text.lowercase(Locale.ROOT))
        .map { it.value }
        .toList()
}

class DeterministicChunker(
    private val options: ChunkingOptions = ChunkingOptions(),
) {
    fun chunk(document: RagDocument): List<TextChunk> {
        val normalized = document.normalizedText
        if (normalized.isBlank()) return emptyList()
        val ranges = ranges(normalized)
        return ranges.mapIndexed { ordinal, range ->
            val text = normalized.substring(range)
            val span = SourceSpan(
                startOffset = range.first,
                endOffset = range.last + 1,
                page = document.page,
                section = document.section,
            )
            val id = ChunkId.parse(
                sha256(
                    buildString {
                        append(document.id.value)
                        append('|')
                        append(ordinal)
                        append('|')
                        append(span.startOffset)
                        append('|')
                        append(span.endOffset)
                        append('|')
                        append(text)
                    },
                ),
            )
            TextChunk(
                id = id,
                documentId = document.id,
                collectionId = document.collectionId,
                title = document.title,
                sourceLabel = document.sourceLabel,
                text = text,
                span = span,
                ordinal = ordinal,
            )
        }
    }

    private fun ranges(text: String): List<IntRange> {
        val output = mutableListOf<IntRange>()
        var start = 0
        while (start < text.length) {
            val hardEnd = minOf(start + options.maxCharacters, text.length)
            val end = if (hardEnd == text.length) {
                hardEnd
            } else {
                chooseBoundary(text, start, hardEnd)
            }
            if (end <= start) break
            output += start until end
            if (end == text.length) break
            val overlapStart = (end - options.overlapCharacters).coerceAtLeast(start)
            start = if (overlapStart > start && text.substring(overlapStart, end).isNotBlank()) {
                overlapStart
            } else {
                end
            }
        }
        return output
    }

    private fun chooseBoundary(text: String, start: Int, hardEnd: Int): Int {
        val minimumEnd = minOf(start + options.minCharacters, hardEnd)
        val paragraph = text.lastIndexOf("\n\n", hardEnd - 1, ignoreCase = false)
        if (paragraph >= minimumEnd) return paragraph + 2
        val line = text.lastIndexOf('\n', hardEnd - 1)
        if (line >= minimumEnd) return line + 1
        val sentence = listOf(
            text.lastIndexOf('.', hardEnd - 1),
            text.lastIndexOf('?', hardEnd - 1),
            text.lastIndexOf('!', hardEnd - 1),
        ).maxOrNull() ?: -1
        if (sentence + 1 >= minimumEnd) return sentence + 1
        val space = text.lastIndexOf(' ', hardEnd - 1)
        return if (space >= minimumEnd) space else hardEnd
    }
}

class HybridRetriever {
    fun retrieve(
        query: RetrievalQuery,
        chunks: List<TextChunk>,
        semanticScores: Map<ChunkId, Double> = emptyMap(),
    ): List<RetrievalResult> {
        val queryTokens = TextNormalizer.tokens(query.text).toSet()
        if (queryTokens.isEmpty()) return emptyList()
        val raw = chunks.map { chunk ->
            val chunkTokens = TextNormalizer.tokens(chunk.text)
            val frequencies = chunkTokens.groupingBy { it }.eachCount()
            val lexical = queryTokens.sumOf { token ->
                val frequency = frequencies[token] ?: return@sumOf 0.0
                (1.0 + kotlin.math.ln(1.0 + frequency.toDouble())) /
                    kotlin.math.sqrt(chunkTokens.size.coerceAtLeast(1).toDouble())
            }
            val semantic = semanticScores[chunk.id]?.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
            Triple(chunk, lexical, semantic)
        }
        val maxLexical = raw.maxOfOrNull { it.second }?.takeIf { it > 0.0 } ?: 1.0
        val maxSemantic = raw.maxOfOrNull { it.third }?.takeIf { it > 0.0 } ?: 1.0
        return raw
            .map { (chunk, lexical, semantic) ->
                val normalizedLexical = (lexical / maxLexical).coerceIn(0.0, 1.0)
                val normalizedSemantic = (semantic / maxSemantic).coerceIn(0.0, 1.0)
                val fused = (
                    query.lexicalWeight * normalizedLexical +
                        query.semanticWeight * normalizedSemantic
                    ) / (query.lexicalWeight + query.semanticWeight)
                RetrievalResult(
                    chunk = chunk,
                    citation = Citation(
                        documentId = chunk.documentId,
                        title = chunk.title,
                        sourceLabel = chunk.sourceLabel,
                        span = chunk.span,
                        excerptHash = sha256(chunk.text),
                        lexicalScore = normalizedLexical,
                        semanticScore = normalizedSemantic,
                        fusedScore = fused,
                    ),
                )
            }
            .filter { it.citation.fusedScore > 0.0 }
            .sortedWith(
                compareByDescending<RetrievalResult> { it.citation.fusedScore }
                    .thenBy { it.chunk.documentId.value }
                    .thenBy { it.chunk.ordinal },
            )
            .let { ranked -> diversify(ranked, query) }
    }

    fun packContext(
        query: RetrievalQuery,
        results: List<RetrievalResult>,
    ): PackedContext {
        var characterCount = 0
        val selected = mutableListOf<RetrievalResult>()
        for (result in results) {
            val separator = if (selected.isEmpty()) 0 else 2
            if (characterCount + separator + result.chunk.text.length > query.contextCharacterBudget) break
            selected += result
            characterCount += separator + result.chunk.text.length
        }
        return PackedContext(
            results = selected,
            omittedResults = (results.size - selected.size).coerceAtLeast(0),
            characterCount = characterCount,
        )
    }

    private fun diversify(
        ranked: List<RetrievalResult>,
        query: RetrievalQuery,
    ): List<RetrievalResult> {
        val documentCounts = mutableMapOf<DocumentId, Int>()
        return ranked.filter { result ->
            val count = documentCounts.getOrDefault(result.chunk.documentId, 0)
            if (count >= query.maxPerDocument) return@filter false
            documentCounts[result.chunk.documentId] = count + 1
            documentCounts.size <= query.topK || count + 1 <= query.maxPerDocument
        }.take(query.topK)
    }
}

private val SHA256_PATTERN = Regex("[a-f0-9]{64}")

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
