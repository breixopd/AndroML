package dev.androml.core.network

import dev.androml.core.files.FileArtifactStore
import dev.androml.core.model.HuggingFaceFileDescriptor
import dev.androml.core.model.HuggingFaceModelReference
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HuggingFaceArtifactDownloaderTest {
    private val httpClient = OkHttpClient()
    private val server = MockWebServer()
    private val root = Files.createTempDirectory("androml-download-").toFile()
    private val store = FileArtifactStore(root)
    private val reference = HuggingFaceModelReference.parse(
        modelId = "org/tiny-model",
        revision = "0123456789abcdef0123456789abcdef01234567",
    )

    @After
    fun tearDown() {
        server.close()
        httpClient.dispatcher.executorService.shutdownNow()
        httpClient.connectionPool.evictAll()
        root.walkBottomUp().forEach(File::delete)
    }

    @Test
    fun resumesFromPersistedOffsetAndCommitsVerifiedBytes() {
        server.start()
        val bytes = "0123456789abcdef".toByteArray()
        val descriptor = descriptor(bytes)
        store.beginResumable("job-resume", descriptor.sha256!!, descriptor.sizeBytes).use {
            it.appendFrom(ByteArrayInputStream(bytes.copyOfRange(0, 6)))
        }
        server.enqueue(
            MockResponse(
                code = 206,
                headers = Headers.Builder()
                    .add("Content-Range", "bytes 6-${bytes.lastIndex}/${bytes.size}")
                    .build(),
                body = String(bytes.copyOfRange(6, bytes.size)),
            ),
        )

        val progress = mutableListOf<Long>()
        val artifact = downloader().download(
            reference = reference,
            descriptor = descriptor,
            jobKey = "job-resume",
            onProgress = { progress += it.bytesWritten },
        )
        val request = server.takeRequest(1, TimeUnit.SECONDS)

        assertEquals("bytes=6-", request?.headers?.get("Range"))
        assertEquals(bytes.size.toLong(), artifact.sizeBytes)
        assertArrayEquals(bytes, store.open(artifact.sha256).use { it.readBytes() })
        assertEquals(bytes.size.toLong(), progress.last())
    }

    @Test
    fun replacesPartialWhenTheServerIgnoresTheRangeRequest() {
        server.start()
        val bytes = "complete-response".toByteArray()
        val descriptor = descriptor(bytes)
        store.beginResumable("job-restart", descriptor.sha256!!, descriptor.sizeBytes).use {
            it.appendFrom(ByteArrayInputStream(bytes.copyOfRange(0, 4)))
        }
        server.enqueue(MockResponse(body = String(bytes)))

        val artifact = downloader().download(reference, descriptor, "job-restart")
        val request = server.takeRequest(1, TimeUnit.SECONDS)

        assertEquals("bytes=4-", request?.headers?.get("Range"))
        assertArrayEquals(bytes, store.open(artifact.sha256).use { it.readBytes() })
    }

    @Test
    fun rejectsAContentRangeThatDoesNotStartAtThePersistedOffset() {
        server.start()
        val bytes = "range-check".toByteArray()
        val descriptor = descriptor(bytes)
        store.beginResumable("job-range", descriptor.sha256!!, descriptor.sizeBytes).use {
            it.appendFrom(ByteArrayInputStream(bytes.copyOfRange(0, 3)))
        }
        server.enqueue(
            MockResponse(
                code = 206,
                headers = Headers.Builder()
                    .add("Content-Range", "bytes 2-${bytes.lastIndex}/${bytes.size}")
                    .build(),
                body = String(bytes.copyOfRange(3, bytes.size)),
            ),
        )

        val error = assertThrows(HuggingFaceNetworkException::class.java) {
            downloader().download(reference, descriptor, "job-range")
        }
        val reopened = store.beginResumable("job-range", descriptor.sha256!!, descriptor.sizeBytes)

        assertEquals(HuggingFaceNetworkError.InvalidMetadata, error.code)
        assertEquals(3L, reopened.bytesWritten)
    }

    @Test
    fun refusesToDownloadAFileWithoutRemoteSha256Metadata() {
        server.start()
        val descriptor = HuggingFaceFileDescriptor(path = "model.bin", sizeBytes = 1L)

        val error = assertThrows(HuggingFaceNetworkException::class.java) {
            downloader().download(reference, descriptor, "job-no-hash")
        }

        assertEquals(HuggingFaceNetworkError.InvalidMetadata, error.code)
        assertEquals(0, server.requestCount)
    }

    private fun downloader(): HuggingFaceArtifactDownloader = HuggingFaceArtifactDownloader(
        callFactory = httpClient,
        store = store,
        endpoints = HuggingFaceEndpoints.forTesting(server.url("/").toUri()),
    )

    private fun descriptor(bytes: ByteArray): HuggingFaceFileDescriptor = HuggingFaceFileDescriptor(
        path = "model.bin",
        sizeBytes = bytes.size.toLong(),
        sha256 = sha256(bytes),
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
