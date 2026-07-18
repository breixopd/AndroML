package dev.androml.api.server

import dev.androml.core.api.ApiKeyCodec
import dev.androml.core.api.ApiScope
import dev.androml.core.security.MtlsContextFactory
import dev.androml.core.security.SelfSignedTlsIdentityFactory
import io.ktor.client.request.header
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
import java.net.ServerSocket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import java.net.URL
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroMlApiServerTest {
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
                assertThrows(SSLException::class.java) { unpairedConnection.responseCode }
            } finally {
                unpairedConnection.disconnect()
            }
        } finally {
            server.stop()
        }
    }
}
