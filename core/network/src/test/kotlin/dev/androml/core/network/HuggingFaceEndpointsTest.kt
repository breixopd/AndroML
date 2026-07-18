package dev.androml.core.network

import dev.androml.core.model.HuggingFaceFileDescriptor
import dev.androml.core.model.HuggingFaceModelReference
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HuggingFaceEndpointsTest {
    private val reference = HuggingFaceModelReference.parse(
        modelId = "org/tiny-model",
        revision = "0123456789abcdef0123456789abcdef01234567",
    )

    @Test
    fun modelInfoUsesOfficialHttpsAndPinnedRevision() {
        val uri = HuggingFaceEndpoints().modelInfo(reference)

        assertEquals("https", uri.scheme)
        assertEquals("huggingface.co", uri.host)
        assertEquals("/api/models/org/tiny-model", uri.rawPath)
        assertEquals("revision=0123456789abcdef0123456789abcdef01234567", uri.rawQuery)
        assertEquals(null, uri.userInfo)
    }

    @Test
    fun fileDownloadKeepsNestedPathAndPinsCommit() {
        val descriptor = HuggingFaceFileDescriptor(
            path = "onnx/model int8.onnx",
            sizeBytes = 42L,
        )

        val uri = HuggingFaceEndpoints().fileDownload(reference, descriptor)

        assertEquals(
            "/org/tiny-model/resolve/0123456789abcdef0123456789abcdef01234567/onnx/model%20int8.onnx",
            uri.rawPath,
        )
        assertEquals("huggingface.co", uri.host)
    }

    @Test
    fun rejectsNonOfficialOrNonHttpsBaseUrls() {
        listOf(
            URI("http://huggingface.co"),
            URI("https://example.com"),
            URI("https://user:secret@huggingface.co"),
            URI("https://huggingface.co/alternate"),
        ).forEach { baseUri ->
            assertThrows(IllegalArgumentException::class.java) {
                HuggingFaceEndpoints(baseUri)
            }
        }
    }
}
