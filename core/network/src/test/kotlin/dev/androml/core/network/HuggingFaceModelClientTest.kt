package dev.androml.core.network

import dev.androml.core.model.HuggingFaceModelReference
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HuggingFaceModelClientTest {
    private val httpClient = OkHttpClient()
    private val server = MockWebServer()
    private val reference = HuggingFaceModelReference.parse(
        modelId = "org/tiny-model",
        revision = "0123456789abcdef0123456789abcdef01234567",
    )

    @After
    fun tearDown() {
        server.close()
        httpClient.dispatcher.executorService.shutdownNow()
        httpClient.connectionPool.evictAll()
    }

    @Test
    fun fetchesMetadataWithPinnedPathAndBearerToken() {
        server.start()
        server.enqueue(MockResponse(body = validResponse()))

        val metadata = HuggingFaceModelClient(
            callFactory = httpClient,
            endpoints = HuggingFaceEndpoints.forTesting(server.url("/").toUri()),
        ).fetchMetadata(reference, accessToken = "hf_test_read_token")
        val request = server.takeRequest(1, TimeUnit.SECONDS)

        assertEquals(1, server.requestCount)
        assertEquals("GET", request?.method)
        assertEquals(
            "/api/models/org/tiny-model?revision=${reference.revision.value}",
            request?.target,
        )
        assertEquals("application/json", request?.headers?.get("Accept"))
        assertEquals("Bearer hf_test_read_token", request?.headers?.get("Authorization"))
        assertEquals(reference, metadata.reference)
    }

    @Test
    fun doesNotSendAnAuthorizationHeaderWhenNoTokenIsConfigured() {
        server.start()
        server.enqueue(MockResponse(body = validResponse()))

        HuggingFaceModelClient(
            callFactory = httpClient,
            endpoints = HuggingFaceEndpoints.forTesting(server.url("/").toUri()),
        ).fetchMetadata(reference)
        val request = server.takeRequest(1, TimeUnit.SECONDS)

        assertEquals(null, request?.headers?.get("Authorization"))
    }

    @Test
    fun searchesModelsWithBoundedQueryAndBearerToken() {
        server.start()
        server.enqueue(MockResponse(body = "[{\"id\":\"org/tiny-model\",\"sha\":\"0123456789abcdef0123456789abcdef01234567\"}]"))

        val hits = HuggingFaceModelClient(
            callFactory = httpClient,
            endpoints = HuggingFaceEndpoints.forTesting(server.url("/").toUri()),
        ).searchModels("tiny model", accessToken = "hf_search_token")
        val request = server.takeRequest(1, TimeUnit.SECONDS)

        assertEquals("/api/models?search=tiny%20model&limit=20&full=false", request?.target)
        assertEquals("Bearer hf_search_token", request?.headers?.get("Authorization"))
        assertEquals("org/tiny-model", hits.single().modelId)
    }

    @Test
    fun mapsUnauthorizedResponsesWithoutExposingResponseBody() {
        server.start()
        server.enqueue(MockResponse(code = 401, body = "token must not appear in an exception"))

        val error = assertThrows(HuggingFaceNetworkException::class.java) {
            HuggingFaceModelClient(
                callFactory = httpClient,
                endpoints = HuggingFaceEndpoints.forTesting(server.url("/").toUri()),
            ).fetchMetadata(reference, accessToken = "hf_secret_token")
        }

        assertEquals(HuggingFaceNetworkError.Unauthorized, error.code)
        assertEquals(false, error.message.orEmpty().contains("token"))
    }

    @Test
    fun rejectsAnOversizedMetadataResponseBeforeParsingIt() {
        server.start()
        server.enqueue(MockResponse(body = "x".repeat(2 * 1024 * 1024 + 1)))

        val error = assertThrows(HuggingFaceNetworkException::class.java) {
            HuggingFaceModelClient(
                callFactory = httpClient,
                endpoints = HuggingFaceEndpoints.forTesting(server.url("/").toUri()),
            ).fetchMetadata(reference)
        }

        assertEquals(HuggingFaceNetworkError.ResponseTooLarge, error.code)
    }

    private fun validResponse(): String =
        """
        {
          "id": "org/tiny-model",
          "sha": "0123456789abcdef0123456789abcdef01234567",
          "private": false,
          "gated": false,
          "cardData": {"license": "apache-2.0"},
          "siblings": [{"rfilename": "config.json", "size": 128}]
        }
        """.trimIndent()
}
