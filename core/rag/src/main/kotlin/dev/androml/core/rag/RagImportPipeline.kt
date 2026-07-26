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
        var expanded = 0L
        var textBytes = 0L
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += 1
                if (entries > MAX_ZIP_ENTRIES) throw RagImportException("document archive has too many entries")
                if (entry.isDirectory) continue
                val entryName = entry.name.replace('\\', '/')
                if (entryName.contains("..")) throw RagImportException("document archive contains an unsafe path")
                if (isTextEntry(entryName)) {
                    val content = readZipEntryBounded(zip, MAX_ZIP_TEXT_ENTRY_BYTES)
                    expanded += content.size
                    textBytes += content.size
                    if (textBytes > MAX_ZIP_TEXT_BYTES) {
                        throw RagImportException("document archive contains too much text data")
                    }
                    output.append(' ').append(stripMarkup(content.toString(Charsets.UTF_8)))
                } else {
                    expanded += drainZipEntryBounded(zip, MAX_ZIP_BINARY_ENTRY_BYTES)
                }
                if (expanded > MAX_ZIP_EXPANDED_BYTES) {
                    throw RagImportException("document archive extracts too much data")
                }
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

    private fun drainZipEntryBounded(input: InputStream, limit: Int): Long {
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > limit) throw RagImportException("document archive binary entry is too large")
        }
        return total
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

    /** Single-pass markup extraction. It never backtracks over attacker-controlled '<' runs. */
    private fun stripMarkup(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        var suppressed: String? = null
        while (i < raw.length) {
            if (suppressed != null) {
                val close = raw.indexOf("</${suppressed}>", i, ignoreCase = true)
                if (close < 0) break
                i = close + suppressed.length + 3
                suppressed = null
                out.append(' ')
                continue
            }
            if (raw[i] == '<') {
                val end = raw.indexOf('>', i + 1)
                if (end < 0) {
                    // The remaining malformed tag-like text has no terminator. Stop in
                    // one pass rather than rescanning the same suffix for every '<'.
                    while (i < raw.length) {
                        out.append(if (raw[i] == '<') ' ' else raw[i])
                        i += 1
                    }
                    break
                }
                val tag = raw.substring(i + 1, end).trimStart().lowercase(Locale.ROOT)
                when {
                    tag.startsWith("script") && (tag.length == 6 || tag[6].isWhitespace() || tag[6] == '>') -> suppressed = "script"
                    tag.startsWith("style") && (tag.length == 5 || tag[5].isWhitespace() || tag[5] == '>') -> suppressed = "style"
                }
                i = end + 1
                out.append(' ')
            } else if (raw[i] == '&') {
                val end = raw.indexOf(';', i + 1)
                if (end in (i + 2)..(i + 12)) {
                    out.append(decodeEntity(raw.substring(i + 1, end)))
                    i = end + 1
                } else { out.append(raw[i++]) }
            } else out.append(raw[i++])
        }
        return out.toString()
    }

    private fun decodeEntity(entity: String): String = when (entity.lowercase(Locale.ROOT)) {
        "nbsp" -> " "; "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "#39" -> "'"
        else -> if (entity.startsWith("#x", true)) entity.substring(2).toIntOrNull(16)?.toChar()?.toString() ?: "&$entity;"
        else if (entity.startsWith("#")) entity.substring(1).toIntOrNull()?.toChar()?.toString() ?: "&$entity;"
        else "&$entity;"
    }

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
        const val MAX_ZIP_TEXT_ENTRY_BYTES = 4 * 1024 * 1024
        const val MAX_ZIP_BINARY_ENTRY_BYTES = 64 * 1024 * 1024
        const val MAX_ZIP_TEXT_BYTES = 16L * 1024L * 1024L
        const val MAX_ZIP_EXPANDED_BYTES = 128L * 1024L * 1024L
    }
}
