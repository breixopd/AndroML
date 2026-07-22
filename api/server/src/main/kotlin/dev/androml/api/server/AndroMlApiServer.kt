package dev.androml.api.server

import dev.androml.core.api.ApiAuthResult
import dev.androml.core.api.ApiKeyAuthenticator
import dev.androml.core.api.ApiKeyId
import dev.androml.core.api.ApiKeyRecord
import dev.androml.core.api.ApiRequestClass
import dev.androml.core.api.ApiScope
import dev.androml.core.api.ApiSecurityPolicy
import dev.androml.core.api.BindMode
import dev.androml.core.api.CertificateFingerprint
import dev.androml.core.api.MtlsPeer
import dev.androml.core.security.TlsServerMaterial
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.cio.CIO
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.routing.RoutingCall
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.security.cert.X509Certificate
import io.netty.handler.ssl.SslHandler

data class ApiServerConfig(
    val bindMode: BindMode = BindMode.Loopback,
    val host: String = "127.0.0.1",
    val port: Int = 8787,
    val maxRequestBodyBytes: Int = 1 * 1024 * 1024,
) {
    init {
        require(port in 1024..65_535) { "API port is out of bounds" }
        require(maxRequestBodyBytes in 1024..16 * 1024 * 1024) { "API body limit is out of bounds" }
        if (bindMode == BindMode.Loopback) {
            require(host == "127.0.0.1" || host == "localhost") {
                "loopback mode must bind only to loopback"
            }
        }
    }
}

interface ApiInferenceGateway {
    fun streamChat(request: ChatCompletionRequest): Flow<ChatDelta>

    /** Returns model-backed vectors when the host can execute the requested artifact. */
    suspend fun embeddings(request: EmbeddingsRequest): List<List<Double>> =
        throw IllegalStateException("embedding runtime is not configured")
}

data class ChatMessage(
    val role: String,
    val content: String,
)

data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean,
    val maxTokens: Int,
    val temperature: Double,
) {
    init {
        require(model.isNotBlank() && model.length <= 256) { "model is invalid" }
        require(messages.isNotEmpty() && messages.size <= 128) { "messages are invalid" }
        require(messages.all { it.role in ROLES && it.content.length <= MAX_MESSAGE_CHARS }) {
            "message content or role is invalid"
        }
        require(maxTokens in 1..8192) { "max_tokens is out of bounds" }
        require(temperature.isFinite() && temperature in 0.0..2.0) { "temperature is out of bounds" }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("model", model)
        put("stream", stream)
        put("max_tokens", maxTokens)
        put("temperature", temperature)
        put("messages", buildJsonArray {
            messages.forEach { message ->
                add(buildJsonObject {
                    put("role", message.role)
                    put("content", message.content)
                })
            }
        })
    }

    companion object {
        const val MAX_BODY_CHARS = 1 * 1024 * 1024
        const val MAX_MESSAGE_CHARS = 256 * 1024
        private val ROLES = setOf("system", "user", "assistant")

        fun parse(raw: String): ChatCompletionRequest {
            require(raw.length <= MAX_BODY_CHARS) { "request body exceeds the safety limit" }
            val root = Json.parseToJsonElement(raw).jsonObject
            val model = root.string("model")
            val messages = root.array("messages").map { element ->
                val message = element.jsonObject
                ChatMessage(
                    role = message.string("role"),
                    content = message.string("content"),
                )
            }
            return ChatCompletionRequest(
                model = model,
                messages = messages,
                stream = root.booleanOrDefault("stream", false),
                maxTokens = root.intOrDefault("max_tokens", 256),
                temperature = root.doubleOrDefault("temperature", 0.7),
            )
        }
    }
}

data class ChatDelta(
    val text: String,
    val finishReason: String? = null,
) {
    init {
        require(text.length <= MAX_DELTA_CHARS) { "chat delta is too large" }
        require(finishReason == null || finishReason in setOf("stop", "length", "cancelled")) {
            "finish reason is invalid"
        }
    }

    fun toSseJson(model: String): JsonObject = buildJsonObject {
        put("id", "chatcmpl-androml")
        put("object", "chat.completion.chunk")
        put("model", model)
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("index", 0)
                put("delta", buildJsonObject { put("content", text) })
                finishReason?.let { put("finish_reason", it) }
            })
        })
    }

    companion object {
        const val MAX_DELTA_CHARS = 64 * 1024
    }
}

data class EmbeddingsRequest(
    val model: String,
    val inputs: List<String>,
) {
    init {
        require(model.isNotBlank() && model.length <= 256) { "model is invalid" }
        require(inputs.isNotEmpty() && inputs.size <= 128) { "input is invalid" }
        require(inputs.all { it.length <= 64 * 1024 }) { "input is too large" }
    }

    companion object {
        fun parse(root: JsonObject): EmbeddingsRequest {
            val input = root["input"] ?: throw IllegalArgumentException("input is missing")
            val values = when {
                input is JsonPrimitive -> listOf(input.contentOrNull ?: throw IllegalArgumentException("input is invalid"))
                input is JsonArray -> input.map { it.jsonPrimitive.contentOrNull ?: throw IllegalArgumentException("input is invalid") }
                else -> throw IllegalArgumentException("input is invalid")
            }
            return EmbeddingsRequest(root.string("model"), values)
        }
    }
}

data class ResponsesRequest(
    val model: String,
    val input: String,
    val stream: Boolean,
    val maxTokens: Int,
    val temperature: Double,
) {
    init {
        require(model.isNotBlank() && model.length <= 256) { "model is invalid" }
        require(input.isNotBlank() && input.length <= ChatCompletionRequest.MAX_MESSAGE_CHARS) { "input is invalid" }
        require(maxTokens in 1..8192) { "max_output_tokens is out of bounds" }
        require(temperature.isFinite() && temperature in 0.0..2.0) { "temperature is out of bounds" }
    }

    companion object {
        fun parse(root: JsonObject): ResponsesRequest = ResponsesRequest(
            model = root.string("model"),
            input = root.string("input"),
            stream = root.booleanOrDefault("stream", false),
            maxTokens = root.intOrDefault("max_output_tokens", root.intOrDefault("max_tokens", 256)),
            temperature = root.doubleOrDefault("temperature", 0.7),
        )
    }
}

class LanMtlsRequiredException : IllegalStateException(
    "LAN API binding is disabled until a verified mTLS transport is configured",
)

private class RequestBodyTooLargeException : IllegalStateException("request body too large")

class AndroMlApiServer(
    private val config: ApiServerConfig,
    private val apiKeys: suspend () -> Collection<ApiKeyRecord>,
    private val models: suspend () -> List<String>,
    private val inference: ApiInferenceGateway,
    private val securityPolicy: ApiSecurityPolicy? = null,
    private val onKeyUsed: suspend (ApiKeyId) -> Unit = {},
    private val tlsMaterial: TlsServerMaterial? = null,
    private val features: ApiFeatureGateway = ApiFeatureGateway.Empty,
) {
    private val authenticator = ApiKeyAuthenticator()
    private var engine: EmbeddedServer<*, *>? = null
    private val effectiveSecurityPolicy: ApiSecurityPolicy by lazy {
        securityPolicy ?: ApiSecurityPolicy(tlsMaterial?.trustedClientFingerprints.orEmpty())
    }

    fun start(wait: Boolean = false) {
        check(engine == null) { "API server is already running" }
        engine = if (config.bindMode == BindMode.Lan) {
            val material = tlsMaterial ?: throw LanMtlsRequiredException()
            embeddedServer(
                Netty,
                configure = {
                    sslConnector(
                        keyStore = material.keyStore,
                        keyAlias = material.keyAlias,
                        keyStorePassword = material::keyStorePassword,
                        privateKeyPassword = material::privateKeyPassword,
                    ) {
                        host = config.host
                        port = config.port
                        trustStore = material.trustStore
                        enabledProtocols = listOf("TLSv1.3", "TLSv1.2")
                    }
                },
            ) {
                module()
            }
        } else {
            embeddedServer(CIO, host = config.host, port = config.port) {
                module()
            }
        }.also { it.start(wait = wait) }
    }

    fun stop() {
        engine?.stop(1000, 5000)
        engine = null
    }

    fun Application.module() {
        install(CallLogging)
        install(ContentNegotiation) { json(Json { explicitNulls = false }) }
        routing {
            fun apiGet(path: String, handler: suspend ApplicationCall.() -> Unit) {
                get("/v1$path") { handler(call) }
                get("/api/v1$path") { handler(call) }
            }

            fun apiPost(path: String, handler: suspend ApplicationCall.() -> Unit) {
                post("/v1$path") { handler(call) }
                post("/api/v1$path") { handler(call) }
            }

            get("/openapi.json") {
                call.respondText(OPENAPI_DOCUMENT, ContentType.Application.Json)
            }
            get("/healthz") {
                call.respondText(
                    "{\"status\":\"ok\",\"bind_mode\":\"${config.bindMode.name.lowercase()}\"}",
                    ContentType.Application.Json,
                )
            }
            apiGet("/models") {
                val call = this
                if (!authorize(call, ApiScope.ModelsRead, ApiRequestClass.ReadOnly)) return@apiGet
                val data = buildJsonArray { models().forEach { model -> add(buildJsonObject { put("id", model); put("object", "model") }) } }
                call.respondText(
                    Json.encodeToString(buildJsonObject { put("object", "list"); put("data", data) }),
                    ContentType.Application.Json,
                )
            }
            apiGet("/rag/search") {
                val call = this
                if (!authorize(call, ApiScope.RagRead, ApiRequestClass.ReadOnly)) return@apiGet
                val request = runCatching {
                    ApiRagSearchRequest(
                        collectionId = call.request.queryParameters["collection_id"]
                            ?: throw IllegalArgumentException("collection_id is missing"),
                        query = call.request.queryParameters["q"]
                            ?: throw IllegalArgumentException("q is missing"),
                        topK = call.request.queryParameters["top_k"]?.toIntOrNull() ?: 8,
                    )
                }.getOrElse { error ->
                    call.respondApiFeatureError(error)
                    return@apiGet
                }
                try {
                    val response = features.ragSearch(request)
                    call.respondText(Json.encodeToString(response.toJson()), ContentType.Application.Json)
                } catch (error: Throwable) {
                    call.respondApiFeatureError(error)
                }
            }
            apiGet("/workflows") {
                val call = this
                if (!authorize(call, ApiScope.Agents, ApiRequestClass.ReadOnly)) return@apiGet
                try {
                    val workflows = features.listWorkflows()
                    val data = buildJsonArray {
                        workflows.forEach { workflow ->
                            add(buildJsonObject {
                                put("id", workflow.workflowId)
                                put("version", workflow.version)
                                put("node_count", workflow.nodeCount)
                                put("edge_count", workflow.edgeCount)
                            })
                        }
                    }
                    call.respondText(
                        Json.encodeToString(buildJsonObject { put("object", "list"); put("data", data) }),
                        ContentType.Application.Json,
                    )
                } catch (error: Throwable) {
                    call.respondApiFeatureError(error)
                }
            }
            apiPost("/workflows/runs") {
                val call = this
                if (!authorize(call, ApiScope.Agents, ApiRequestClass.Mutating)) return@apiPost
                val request = runCatching {
                    val root = call.receiveBoundedJson(config.maxRequestBodyBytes)
                    ApiWorkflowRunRequest(
                        workflowId = root.string("workflow_id"),
                        version = root.intOrDefault("version", -1),
                        input = root.string("input"),
                    )
                }.getOrElse { error ->
                    call.respondApiFeatureError(error)
                    return@apiPost
                }
                try {
                    val response = features.runWorkflow(request)
                    call.respondText(Json.encodeToString(response.toJson()), ContentType.Application.Json)
                } catch (error: Throwable) {
                    call.respondApiFeatureError(error)
                }
            }
            apiGet("/tools") {
                val call = this
                if (!authorize(call, ApiScope.Tools, ApiRequestClass.ReadOnly)) return@apiGet
                try {
                    val data = buildJsonArray {
                        features.listTools().forEach { tool -> add(tool.toJson()) }
                    }
                    call.respondText(
                        Json.encodeToString(buildJsonObject { put("object", "list"); put("data", data) }),
                        ContentType.Application.Json,
                    )
                } catch (error: Throwable) {
                    call.respondApiFeatureError(error)
                }
            }
            apiPost("/tools/invoke") {
                val call = this
                if (!authorize(call, ApiScope.Tools, ApiRequestClass.Mutating)) return@apiPost
                val request = runCatching {
                    val root = call.receiveBoundedJson(config.maxRequestBodyBytes)
                    ApiToolInvocationRequest(
                        toolId = root.string("tool_id"),
                        arguments = root["arguments"]?.jsonObject
                            ?: throw IllegalArgumentException("arguments is missing"),
                    )
                }.getOrElse { error ->
                    call.respondApiFeatureError(error)
                    return@apiPost
                }
                try {
                    val response = features.invokeTool(request)
                    call.respondText(Json.encodeToString(response.toJson()), ContentType.Application.Json)
                } catch (error: Throwable) {
                    call.respondApiFeatureError(error)
                }
            }
            apiGet("/agents") {
                val call = this
                if (!authorize(call, ApiScope.Agents, ApiRequestClass.ReadOnly)) return@apiGet
                try {
                    val data = buildJsonArray {
                        features.listAgents().forEach { agent ->
                            add(buildJsonObject {
                                put("id", agent.id)
                                put("display_name", agent.displayName)
                            })
                        }
                    }
                    call.respondText(
                        Json.encodeToString(buildJsonObject { put("object", "list"); put("data", data) }),
                        ContentType.Application.Json,
                    )
                } catch (error: Throwable) {
                    call.respondApiFeatureError(error)
                }
            }
            apiGet("/cluster") {
                val call = this
                if (!authorize(call, ApiScope.Cluster, ApiRequestClass.ReadOnly)) return@apiGet
                try {
                    val status = features.clusterStatus()
                    call.respondText(Json.encodeToString(status.toJson()), ContentType.Application.Json)
                } catch (error: Throwable) {
                    call.respondApiFeatureError(error)
                }
            }
            apiPost("/chat/completions") {
                val call = this
                if (!authorize(call, ApiScope.Inference, ApiRequestClass.Content)) return@apiPost
                val body = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (body != null && body > config.maxRequestBodyBytes) {
                    call.respondText("{\"error\":\"request body too large\"}", status = HttpStatusCode.PayloadTooLarge)
                    return@apiPost
                }
                val request = runCatching {
                    ChatCompletionRequest.parse(call.receiveBoundedText(config.maxRequestBodyBytes))
                }.getOrElse { error ->
                    val status = if (error is RequestBodyTooLargeException) {
                        HttpStatusCode.PayloadTooLarge
                    } else {
                        HttpStatusCode.BadRequest
                    }
                    call.respondText(
                        if (status == HttpStatusCode.PayloadTooLarge) {
                            "{\"error\":\"request body too large\"}"
                        } else {
                            "{\"error\":\"invalid request\"}"
                        },
                        status = status,
                    )
                    return@apiPost
                }
                if (request.stream) {
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        inference.streamChat(request).collect { delta ->
                            write("data: ${Json.encodeToString(delta.toSseJson(request.model))}\n\n")
                            flush()
                        }
                        write("data: [DONE]\n\n")
                        flush()
                    }
                } else {
                    val output = buildString {
                        inference.streamChat(request).collect { append(it.text) }
                    }
                    val response = buildJsonObject {
                        put("id", "chatcmpl-androml")
                        put("object", "chat.completion")
                        put("model", request.model)
                        put("choices", buildJsonArray {
                            add(buildJsonObject {
                                put("index", 0)
                                put("message", buildJsonObject { put("role", "assistant"); put("content", output) })
                                put("finish_reason", "stop")
                            })
                        })
                    }
                    call.respondText(Json.encodeToString(response), ContentType.Application.Json)
                }
            }
            apiPost("/embeddings") {
                val call = this
                if (!authorize(call, ApiScope.Inference, ApiRequestClass.Content)) return@apiPost
                val request = runCatching {
                    EmbeddingsRequest.parse(call.receiveBoundedJson(config.maxRequestBodyBytes))
                }.getOrElse { error ->
                    call.respondApiFeatureError(error)
                    return@apiPost
                }
                val vectors = runCatching { inference.embeddings(request) }.getOrElse { error ->
                    call.respondApiFeatureError(error)
                    return@apiPost
                }
                if (vectors.size != request.inputs.size || vectors.any { vector ->
                        vector.isEmpty() || vector.size > MAX_EMBEDDING_DIMENSION || vector.any { !it.isFinite() }
                    }) {
                    call.respondApiFeatureError(
                        IllegalStateException("embedding runtime returned an invalid vector batch"),
                    )
                    return@apiPost
                }
                val data = buildJsonArray {
                    vectors.forEachIndexed { index, vector ->
                        add(buildJsonObject {
                            put("object", "embedding")
                            put("index", index)
                            put("embedding", buildJsonArray {
                                vector.forEach { value -> add(JsonPrimitive(value)) }
                            })
                        })
                    }
                }
                call.respondText(
                    Json.encodeToString(buildJsonObject {
                        put("object", "list")
                        put("data", data)
                        put("model", request.model)
                        put("usage", buildJsonObject {
                            put("prompt_tokens", request.inputs.sumOf(::estimateTokens))
                            put("total_tokens", request.inputs.sumOf(::estimateTokens))
                        })
                    }),
                    ContentType.Application.Json,
                )
            }
            apiPost("/responses") {
                val call = this
                if (!authorize(call, ApiScope.Inference, ApiRequestClass.Content)) return@apiPost
                val request = runCatching {
                    ResponsesRequest.parse(call.receiveBoundedJson(config.maxRequestBodyBytes))
                }.getOrElse { error ->
                    call.respondApiFeatureError(error)
                    return@apiPost
                }
                val chatRequest = ChatCompletionRequest(
                    model = request.model,
                    messages = listOf(ChatMessage("user", request.input)),
                    stream = request.stream,
                    maxTokens = request.maxTokens,
                    temperature = request.temperature,
                )
                if (request.stream) {
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        inference.streamChat(chatRequest).collect { delta ->
                            write("data: ${Json.encodeToString(buildJsonObject {
                                put("type", "response.output_text.delta")
                                put("delta", delta.text)
                                delta.finishReason?.let { put("finish_reason", it) }
                            })}\n\n")
                            flush()
                        }
                        write("data: [DONE]\n\n")
                        flush()
                    }
                } else {
                    val output = buildString { inference.streamChat(chatRequest).collect { append(it.text) } }
                    call.respondText(
                        Json.encodeToString(buildJsonObject {
                            put("id", "resp-androml")
                            put("object", "response")
                            put("model", request.model)
                            put("output", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "message")
                                    put("role", "assistant")
                                    put("content", buildJsonArray {
                                        add(buildJsonObject {
                                            put("type", "output_text")
                                            put("text", output)
                                        })
                                    })
                                })
                            })
                        }),
                        ContentType.Application.Json,
                    )
                }
            }
        }
    }

    private suspend fun authorize(
        call: ApplicationCall,
        scope: ApiScope,
        requestClass: ApiRequestClass,
    ): Boolean {
        val token = call.request.headers[HttpHeaders.Authorization]
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
            ?.takeIf { it.length <= 256 }
        val auth: ApiAuthResult? = token?.let {
            authenticator.authenticate(it, apiKeys(), scope)
        }
        val peer = if (config.bindMode == BindMode.Lan) peerFrom(call) else null
        val decision = effectiveSecurityPolicy.evaluate(config.bindMode, requestClass, peer = peer, apiAuth = auth)
        if (!decision.allowed) {
            call.respondText("{\"error\":\"${decision.reason}\"}", status = HttpStatusCode.Unauthorized)
            return false
        }
        auth?.let { onKeyUsed(it.record.id) }
        return true
    }

    private fun peerFrom(call: ApplicationCall): MtlsPeer? {
        val engineCall = unwrapEngineCall(call)
        val nettyCall = engineCall as? NettyApplicationCall ?: return null
        val pipeline = nettyCall.context.pipeline()
        val sslHandler = pipeline.get(SslHandler::class.java) ?: return null
        val certificate = runCatching {
            sslHandler.engine().session.peerCertificates.firstOrNull() as? X509Certificate
        }.getOrNull() ?: return null
        val fingerprint = CertificateFingerprint.parse(
            MessageDigest.getInstance("SHA-256")
                .digest(certificate.encoded)
                .joinToString("") { byte -> "%02x".format(byte) },
        )
        return MtlsPeer(
            fingerprint = fingerprint,
            displayName = certificate.subjectX500Principal.name.take(128).ifBlank { "mTLS client" },
            expiresAtEpochMillis = certificate.notAfter.time,
        )
    }

    private tailrec fun unwrapEngineCall(call: ApplicationCall): ApplicationCall = when (call) {
        is RoutingCall -> unwrapEngineCall(call.pipelineCall.engineCall)
        else -> call
    }
}

private fun ApiRagSearchResponse.toJson() = buildJsonObject {
    put("object", "rag.search")
    put("data", buildJsonArray {
        results.forEach { result ->
            add(buildJsonObject {
                put("node_id", result.nodeId)
                put("title", result.title)
                put("source", result.source)
                put("text", result.text)
                put("score", result.score)
                put("lexical_score", result.lexicalScore)
                put("semantic_score", result.semanticScore)
                result.excerptHash?.let { put("excerpt_hash", it) }
                result.startOffset?.let { put("start_offset", it) }
                result.endOffset?.let { put("end_offset", it) }
                result.page?.let { put("page", it) }
                result.section?.let { put("section", it) }
            })
        }
    })
}

private fun ApiWorkflowRunResponse.toJson() = buildJsonObject {
    put("run_id", runId)
    put("status", status)
    output?.let { put("output", it) }
    error?.let { put("error", it) }
    waitingForNode?.let { put("waiting_for_node", it) }
}

private fun ApiToolInfo.toJson() = buildJsonObject {
    put("id", id)
    put("display_name", displayName)
    put("description", description)
    put("side_effect", sideEffect)
    put("scopes", buildJsonArray { scopes.forEach { add(JsonPrimitive(it)) } })
}

private fun ApiToolInvocationResponse.toJson() = buildJsonObject {
    put("status", status)
    result?.let { put("result", it) }
    reason?.let { put("reason", it) }
    approvalId?.let { put("approval_id", it) }
}

private fun ApiClusterStatus.toJson() = buildJsonObject {
    put("enabled", enabled)
    nodeId?.let { put("node_id", it) }
    put("paired_peer_count", pairedPeerCount)
}

private suspend fun ApplicationCall.respondApiFeatureError(error: Throwable) {
    val status = when (error) {
        is RequestBodyTooLargeException -> HttpStatusCode.PayloadTooLarge
        is ApiFeatureUnavailableException -> HttpStatusCode.NotImplemented
        is IllegalArgumentException -> HttpStatusCode.BadRequest
        else -> HttpStatusCode.InternalServerError
    }
    val message = when (status) {
        HttpStatusCode.PayloadTooLarge -> "request body too large"
        HttpStatusCode.NotImplemented -> "API feature is not enabled"
        HttpStatusCode.BadRequest -> "invalid request"
        else -> "API feature failed"
    }
    respondText("{\"error\":\"$message\"}", status = status)
}

private suspend fun ApplicationCall.receiveBoundedText(maxBytes: Int): String {
    val bytes = receiveChannel()
        .readRemaining(maxBytes.toLong() + 1L)
        .readByteArray()
    if (bytes.size > maxBytes) throw RequestBodyTooLargeException()
    return bytes.toString(Charsets.UTF_8)
}

private suspend fun ApplicationCall.receiveBoundedJson(maxBytes: Int): JsonObject =
    Json.parseToJsonElement(receiveBoundedText(maxBytes)).jsonObject

private fun JsonObject.string(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("missing string")

private fun JsonObject.array(name: String): JsonArray =
    this[name]?.jsonArray ?: throw IllegalArgumentException("missing array")

private fun JsonObject.booleanOrDefault(name: String, default: Boolean): Boolean =
    this[name]?.jsonPrimitive?.booleanOrNull ?: default

private fun JsonObject.intOrDefault(name: String, default: Int): Int =
    this[name]?.jsonPrimitive?.intOrNull ?: default

private fun JsonObject.doubleOrDefault(name: String, default: Double): Double =
    this[name]?.jsonPrimitive?.doubleOrNull ?: default

private const val MAX_EMBEDDING_DIMENSION = 4096

private fun estimateTokens(value: String): Int =
    value.split(Regex("\\s+")).count(String::isNotBlank).coerceAtLeast(1)

private val OPENAPI_DOCUMENT: String = Json.encodeToString(buildJsonObject {
    put("openapi", "3.1.0")
    put("info", buildJsonObject {
        put("title", "AndroML local API")
        put("version", "1")
    })
    put("paths", buildJsonObject {
        listOf(
            "/healthz" to "get",
            "/v1/models" to "get",
            "/v1/chat/completions" to "post",
            "/v1/responses" to "post",
            "/v1/embeddings" to "post",
            "/v1/rag/search" to "get",
            "/v1/tools" to "get",
            "/v1/tools/invoke" to "post",
            "/v1/workflows" to "get",
            "/v1/workflows/runs" to "post",
            "/v1/agents" to "get",
            "/v1/cluster" to "get",
        ).flatMap { (path, method) ->
            if (path.startsWith("/v1/")) {
                listOf(path to method, path.replaceFirst("/v1", "/api/v1") to method)
            } else {
                listOf(path to method)
            }
        }.forEach { (path, method) ->
            put(path, buildJsonObject { put(method, buildJsonObject { put("responses", buildJsonObject { put("200", buildJsonObject { put("description", "Success") }) }) }) })
        }
    })
    put("components", buildJsonObject {
        put("securitySchemes", buildJsonObject {
            put("bearerAuth", buildJsonObject {
                put("type", "http")
                put("scheme", "bearer")
            })
        })
    })
})
