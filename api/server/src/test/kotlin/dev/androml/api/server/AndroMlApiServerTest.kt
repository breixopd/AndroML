package dev.androml.api.server

import dev.androml.core.api.ApiKeyCodec
import dev.androml.core.api.ApiScope
import dev.androml.core.security.MtlsContextFactory
import dev.androml.core.security.SelfSignedTlsIdentityFactory
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import io.ktor.server.testing.testApplication
import java.util.Collections
import java.io.IOException
import java.net.ServerSocket
import javax.net.ssl.HttpsURLConnection
import java.net.URL
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroMlApiServerTest {
    @Test
    fun featureRoutesUseIndependentScopesAndReturnBoundedTypedResults() = testApplication {
        val ragKey = ApiKeyCodec.generate("rag", setOf(ApiScope.RagRead), nowEpochMillis = 1L)
        val toolKey = ApiKeyCodec.generate("tools", setOf(ApiScope.Tools), nowEpochMillis = 1L)
        val featureGateway = object : ApiFeatureGateway {
            override suspend fun ragSearch(request: ApiRagSearchRequest): ApiRagSearchResponse =
                ApiRagSearchResponse(listOf(ApiRagResult("node-local", "Notes", "local", "answer", 0.9)))

            override suspend fun listTools(): List<ApiToolInfo> = listOf(
                ApiToolInfo("device.info", "Device information", "Reads local capability data", "Read", emptyList()),
            )
        }
        val server = AndroMlApiServer(
            config = ApiServerConfig(),
            apiKeys = { listOf(ragKey.record, toolKey.record) },
            models = { listOf("fake") },
            inference = emptyInferenceGateway(),
            features = featureGateway,
        )
        application { with(server) { module() } }

        val denied = client.get("/v1/rag/search?collection_id=docs&q=hello")
        assertEquals(HttpStatusCode.Unauthorized, denied.status)

        val rag = client.get("/v1/rag/search?collection_id=docs&q=hello&top_k=4") {
            header(HttpHeaders.Authorization, "Bearer ${ragKey.plaintextToken}")
        }
        assertEquals(HttpStatusCode.OK, rag.status)
        assertTrue(rag.bodyAsText().contains("answer"))

        val nativeRag = client.get("/api/v1/rag/search?collection_id=docs&q=hello&top_k=4") {
            header(HttpHeaders.Authorization, "Bearer ${ragKey.plaintextToken}")
        }
        assertEquals(HttpStatusCode.OK, nativeRag.status)
        assertTrue(nativeRag.bodyAsText().contains("answer"))

        val tools = client.get("/v1/tools") {
            header(HttpHeaders.Authorization, "Bearer ${toolKey.plaintextToken}")
        }
        assertEquals(HttpStatusCode.OK, tools.status)
        assertTrue(tools.bodyAsText().contains("device.info"))

        val nativeTools = client.get("/api/v1/tools") {
            header(HttpHeaders.Authorization, "Bearer ${toolKey.plaintextToken}")
        }
        assertEquals(HttpStatusCode.OK, nativeTools.status)
        assertTrue(nativeTools.bodyAsText().contains("device.info"))
    }

    @Test
    fun workflowRunRouteRequiresAgentsScopeAndBoundsInput() = testApplication {
        val key = ApiKeyCodec.generate("workflow", setOf(ApiScope.Agents), nowEpochMillis = 1L)
        val server = AndroMlApiServer(
            config = ApiServerConfig(maxRequestBodyBytes = 2_048),
            apiKeys = { listOf(key.record) },
            models = { listOf("fake") },
            inference = emptyInferenceGateway(),
            features = object : ApiFeatureGateway {
                override suspend fun runWorkflow(request: ApiWorkflowRunRequest): ApiWorkflowRunResponse =
                    ApiWorkflowRunResponse("run-1", "Completed", "done", null, null)
            },
        )
        application { with(server) { module() } }

        val response = client.post("/v1/workflows/runs") {
            header(HttpHeaders.Authorization, "Bearer ${key.plaintextToken}")
            contentType(ContentType.Application.Json)
            setBody("{\"workflow_id\":\"demo\",\"version\":1,\"input\":\"hello\"}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("Completed"))

        val oversized = client.post("/v1/workflows/runs") {
            header(HttpHeaders.Authorization, "Bearer ${key.plaintextToken}")
            contentType(ContentType.Application.Json)
            setBody("{\"workflow_id\":\"demo\",\"version\":1,\"input\":\"${"x".repeat(3_000)}\"}")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
    }

    @Test
    fun chatRequiresScopedApiKeyAndSupportsSse() = testApplication {
        val generated = ApiKeyCodec.generate("test", setOf(ApiScope.Inference), nowEpochMillis = 1L)
        val gateway = object : ApiInferenceGateway {
            override fun streamChat(request: ChatCompletionRequest): Flow<ChatDelta> =
                flowOf(ChatDelta("hello"), ChatDelta(" world", finishReason = "stop"))
        }
        val server = AndroMlApiServer(
            config = ApiServerConfig(),
            apiKeys = { Collections.singleton(generated.record) },
            models = { listOf("fake") },
            inference = gateway,
        )
        application { with(server) { module() } }

        val denied = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("{\"model\":\"fake\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
        }
        assertEquals(HttpStatusCode.Unauthorized, denied.status)

        val response = client.post("/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer ${generated.plaintextToken}")
            contentType(ContentType.Application.Json)
            setBody("{\"model\":\"fake\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("data: [DONE]"))

        val nativeResponse = client.post("/api/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer ${generated.plaintextToken}")
            contentType(ContentType.Application.Json)
            setBody("{\"model\":\"fake\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
        }
        assertEquals(HttpStatusCode.OK, nativeResponse.status)
        assertTrue(nativeResponse.bodyAsText().contains("chat.completion"))
    }

    @Test
    fun embeddingsAndResponsesUseTheInferenceScope() = testApplication {
        val generated = ApiKeyCodec.generate("test", setOf(ApiScope.Inference), nowEpochMillis = 1L)
        val gateway = object : ApiInferenceGateway {
            override fun streamChat(request: ChatCompletionRequest): Flow<ChatDelta> = flowOf(ChatDelta("hello"))

            override suspend fun embeddings(request: EmbeddingsRequest): List<List<Double>> =
                request.inputs.map { listOf(it.length.toDouble(), 1.0) }
        }
        val server = AndroMlApiServer(
            config = ApiServerConfig(),
            apiKeys = { listOf(generated.record) },
            models = { listOf("litertlm") },
            inference = gateway,
        )
        application { with(server) { module() } }

        val embeddings = client.post("/v1/embeddings") {
            header(HttpHeaders.Authorization, "Bearer ${generated.plaintextToken}")
            contentType(ContentType.Application.Json)
            setBody("{\"model\":\"local\",\"input\":[\"hello\",\"world\"]}")
        }
        assertEquals(HttpStatusCode.OK, embeddings.status)
        assertTrue(embeddings.bodyAsText().contains("embedding"))
        assertTrue(embeddings.bodyAsText().contains("5.0"))
        assertTrue(embeddings.bodyAsText().contains("prompt_tokens"))

        val responses = client.post("/v1/responses") {
            header(HttpHeaders.Authorization, "Bearer ${generated.plaintextToken}")
            contentType(ContentType.Application.Json)
            setBody("{\"model\":\"local\",\"input\":\"hello\"}")
        }
        assertEquals(HttpStatusCode.OK, responses.status)
        assertTrue(responses.bodyAsText().contains("response"))
        assertTrue(responses.bodyAsText().contains("hello"))

        val openApi = client.get("/openapi.json")
        assertEquals(HttpStatusCode.OK, openApi.status)
        assertTrue(openApi.bodyAsText().contains("/v1/responses"))
        assertTrue(openApi.bodyAsText().contains("/api/v1/responses"))
    }

    @Test
    fun chunkedRequestBodiesAreBoundedEvenWithoutContentLength() = testApplication {
        val generated = ApiKeyCodec.generate("test", setOf(ApiScope.Inference), nowEpochMillis = 1L)
        val server = AndroMlApiServer(
            config = ApiServerConfig(maxRequestBodyBytes = 1024),
            apiKeys = { listOf(generated.record) },
            models = { listOf("fake") },
            inference = object : ApiInferenceGateway {
                override fun streamChat(request: ChatCompletionRequest): Flow<ChatDelta> = flowOf()
            },
        )
        application { with(server) { module() } }

        val response = client.post("/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer ${generated.plaintextToken}")
            contentType(ContentType.Application.Json)
            setBody(object : OutgoingContent.WriteChannelContent() {
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    channel.writeStringUtf8("{" + "x".repeat(2_048) + "}")
                }
            })
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun lanServerRefusesToStartWithoutMtls() {
        val generated = ApiKeyCodec.generate("test", setOf(ApiScope.ModelsRead), nowEpochMillis = 1L)
        val server = AndroMlApiServer(
            config = ApiServerConfig(bindMode = dev.androml.core.api.BindMode.Lan, host = "0.0.0.0"),
            apiKeys = { listOf(generated.record) },
            models = { listOf("fake") },
            inference = object : ApiInferenceGateway {
                override fun streamChat(request: ChatCompletionRequest): Flow<ChatDelta> = flowOf()
            },
        )

        try {
            server.start()
            throw AssertionError("expected LAN mTLS refusal")
        } catch (_: LanMtlsRequiredException) {
            // expected
        }
    }

    @Test
    fun lanServerServesHealthOverMutualTls() {
        val serverIdentity = SelfSignedTlsIdentityFactory.generate("api-server-test")
        val clientIdentity = SelfSignedTlsIdentityFactory.generate("api-client-test")
        val unpairedIdentity = SelfSignedTlsIdentityFactory.generate("unpaired-client-test")
        val generated = ApiKeyCodec.generate(
            displayName = "lan-test",
            scopes = setOf(ApiScope.ModelsRead),
            nowEpochMillis = System.currentTimeMillis(),
        )
        val material = MtlsContextFactory.serverMaterial(
            identity = serverIdentity,
            trustedClientCertificates = listOf(clientIdentity.certificate),
        )
        val port = ServerSocket(0).use { it.localPort }
        val server = AndroMlApiServer(
            config = ApiServerConfig(
                bindMode = dev.androml.core.api.BindMode.Lan,
                host = "127.0.0.1",
                port = port,
            ),
            apiKeys = { listOf(generated.record) },
            models = { listOf("fake") },
            inference = object : ApiInferenceGateway {
                override fun streamChat(request: ChatCompletionRequest): Flow<ChatDelta> = flowOf()
            },
            tlsMaterial = material,
        )

        server.start()
        try {
            val clientContext = MtlsContextFactory.clientContext(
                identity = clientIdentity,
                trustedServerCertificates = listOf(serverIdentity.certificate),
            )
            val connection = (URL("https://127.0.0.1:$port/healthz").openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = clientContext.socketFactory
                connectTimeout = 5_000
                readTimeout = 5_000
            }
            try {
                assertEquals(HttpStatusCode.OK.value, connection.responseCode)
            } finally {
                connection.disconnect()
            }

            val modelsConnection = (URL("https://127.0.0.1:$port/v1/models").openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = clientContext.socketFactory
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty(HttpHeaders.Authorization, "Bearer ${generated.plaintextToken}")
            }
            try {
                val status = modelsConnection.responseCode
                val body = (if (status >= 400) modelsConnection.errorStream else modelsConnection.inputStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                assertEquals("status=$status body=$body", HttpStatusCode.OK.value, status)
            } finally {
                modelsConnection.disconnect()
            }

            val unpairedContext = MtlsContextFactory.clientContext(
                identity = unpairedIdentity,
                trustedServerCertificates = listOf(serverIdentity.certificate),
            )
            val unpairedConnection = (URL("https://127.0.0.1:$port/healthz").openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = unpairedContext.socketFactory
                connectTimeout = 5_000
                readTimeout = 5_000
            }
            try {
                // Netty/JDK may surface a rejected client certificate as either an
                // SSLException or the socket close that follows the failed handshake.
                assertThrows(IOException::class.java) { unpairedConnection.responseCode }
            } finally {
                unpairedConnection.disconnect()
            }
        } finally {
            server.stop()
        }
    }

    private fun emptyInferenceGateway() = object : ApiInferenceGateway {
        override fun streamChat(request: ChatCompletionRequest): Flow<ChatDelta> = flowOf()
    }
}
