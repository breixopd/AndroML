package dev.androml.core.rag

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

private const val MAX_DOCUMENT_BYTES = 32L * 1024L * 1024L

enum class RagSourceFormat {
    PlainText,
    Markdown,
    Html,
    Json,
    Csv,
    Pdf,
    Epub,
    Docx,
    Xlsx,
    Pptx,
}

data class RagImportedDocument(
    val format: RagSourceFormat,
    val text: String,
    val byteSize: Long,
) {
    init {
        require(text.isNotBlank()) { "imported document is empty" }
        require(byteSize in 1..MAX_DOCUMENT_BYTES) { "document size is out of bounds" }
    }
}

class RagImportException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Bounded local-file ingestion. This deliberately produces text only; model execution and
 * OCR/audio transcription stay behind runtime packs and are never invoked while parsing a file.
 */
class RagImportPipeline(
    private val maxDocumentBytes: Long = MAX_DOCUMENT_BYTES,
) {
    init {
        require(maxDocumentBytes in 1L..MAX_DOCUMENT_BYTES)
    }

    fun import(
        fileName: String,
        mimeType: String?,
        input: InputStream,
    ): RagImportedDocument {
        val bytes = readBounded(input, maxDocumentBytes)
        val format = detectFormat(fileName, mimeType)
        val text = try {
            when (format) {
                RagSourceFormat.PlainText,
                RagSourceFormat.Markdown,
                RagSourceFormat.Json,
                RagSourceFormat.Csv,
                -> bytes.toString(Charsets.UTF_8)

                RagSourceFormat.Html -> stripMarkup(bytes.toString(Charsets.UTF_8))
                RagSourceFormat.Pdf -> parsePdf(bytes)
                RagSourceFormat.Epub,
                RagSourceFormat.Docx,
                RagSourceFormat.Xlsx,
                RagSourceFormat.Pptx,
                -> parseOfficeZip(bytes)
            }
        } catch (error: RagImportException) {
            throw error
        } catch (error: Throwable) {
            throw RagImportException("document could not be parsed", error)
        }
        val normalized = normalize(text)
        if (normalized.isBlank()) throw RagImportException("document contains no extractable text")
        return RagImportedDocument(format, normalized.take(MAX_EXTRACTED_CHARS), bytes.size.toLong())
    }

    fun detectFormat(fileName: String, mimeType: String? = null): RagSourceFormat {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            mimeType?.contains("html", ignoreCase = true) == true || extension in setOf("html", "htm") -> RagSourceFormat.Html
            mimeType?.contains("pdf", ignoreCase = true) == true || extension == "pdf" -> RagSourceFormat.Pdf
            extension == "md" || extension == "markdown" -> RagSourceFormat.Markdown
            extension == "json" -> RagSourceFormat.Json
            extension == "csv" -> RagSourceFormat.Csv
            extension == "epub" -> RagSourceFormat.Epub
            extension == "docx" -> RagSourceFormat.Docx
            extension == "xlsx" -> RagSourceFormat.Xlsx
            extension == "pptx" -> RagSourceFormat.Pptx
            else -> RagSourceFormat.PlainText
        }
    }

    private fun readBounded(input: InputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 64L * 1024L).toInt())
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        input.use { source ->
            while (true) {
                val read = source.read(buffer)
                if (read == -1) break
                total += read
                if (total > limit) throw RagImportException("document exceeds the safety limit")
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun parseOfficeZip(bytes: ByteArray): String {
        val output = StringBuilder()
        var entries = 0
        var extracted = 0L
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += 1
                if (entries > MAX_ZIP_ENTRIES) throw RagImportException("document archive has too many entries")
                if (entry.isDirectory) continue
                val entryName = entry.name.replace('\\', '/')
                if (entryName.contains("..")) throw RagImportException("document archive contains an unsafe path")
                if (!isTextEntry(entryName)) continue
                val content = readZipEntryBounded(zip, MAX_ZIP_ENTRY_BYTES)
                extracted += content.size
                if (extracted > MAX_ZIP_EXTRACTED_BYTES) throw RagImportException("document archive extracts too much data")
                output.append(' ').append(stripMarkup(content.toString(Charsets.UTF_8)))
            }
        }
        return output.toString()
    }

    private fun readZipEntryBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > limit) throw RagImportException("document archive entry is too large")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun parsePdf(bytes: ByteArray): String {
        val raw = bytes.toString(Charsets.ISO_8859_1)
        val textOperators = Regex("\\(([^()]*)\\)\\s*T[Jj]")
        return textOperators.findAll(raw).joinToString(" ") { match ->
            match.groupValues[1]
                .replace(Regex("\\\\([()\\\\])"), "$1")
                .replace(Regex("\\\\[nrt]"), " ")
        }.ifBlank {
            raw.filter { it == '\n' || it == '\r' || it == '\t' || it in ' '..'~' }
        }
    }

    private fun stripMarkup(raw: String): String = raw
        .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
        .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

    private fun normalize(text: String): String = text
        .replace('\u0000', ' ')
        .replace(Regex("[ \\t\\x0B\\f]+"), " ")
        .replace(Regex("\\n{3,}"), "\\n\\n")
        .trim()

    private fun isTextEntry(name: String): Boolean =
        name.endsWith(".xml", ignoreCase = true) ||
            name.endsWith(".html", ignoreCase = true) ||
            name.endsWith(".xhtml", ignoreCase = true) ||
            name.endsWith(".txt", ignoreCase = true) ||
            name.endsWith(".rels", ignoreCase = true) ||
            name.endsWith(".json", ignoreCase = true)

    private companion object {
        const val MAX_EXTRACTED_CHARS = 2 * 1024 * 1024
        const val MAX_ZIP_ENTRIES = 512
        const val MAX_ZIP_ENTRY_BYTES = 4 * 1024 * 1024
        const val MAX_ZIP_EXTRACTED_BYTES = 16L * 1024L * 1024L
    }
}
