package dev.androml.core.rag

import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagImportPipelineTest {
    private val pipeline = RagImportPipeline()

    @Test
    fun importsHtmlAndRemovesExecutableMarkup() {
        val document = pipeline.import(
            fileName = "notes.html",
            mimeType = "text/html",
            input = ByteArrayInputStream("<h1>Hello</h1><script>alert(1)</script><p>world</p>".toByteArray()),
        )
        assertEquals(RagSourceFormat.Html, document.format)
        assertEquals("Hello world", document.text)
    }

    @Test
    fun extractsTextFromDocxLikeZipWithEntryAndArchiveBounds() {
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write("<w:t>hello from docx</w:t>".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val document = pipeline.import("memo.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ByteArrayInputStream(bytes))
        assertEquals(RagSourceFormat.Docx, document.format)
        assertTrue(document.text.contains("hello from docx"))
    }

    private class ByteArrayOutputStream : java.io.ByteArrayOutputStream()
}
