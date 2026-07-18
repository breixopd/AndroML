package dev.androml.api.server

import dev.androml.core.api.ApiKeyCodec
import dev.androml.core.api.ApiScope
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.Collections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
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
}
