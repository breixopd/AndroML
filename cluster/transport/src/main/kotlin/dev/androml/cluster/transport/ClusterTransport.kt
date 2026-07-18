package dev.androml.cluster.transport

import dev.androml.cluster.core.ClusterExecutionRequest
import dev.androml.cluster.core.ClusterExecutionResponse
import dev.androml.cluster.core.ClusterWireCodec
import dev.androml.cluster.core.IdempotentClusterExecutor
import dev.androml.cluster.core.PeerEndpoint
import dev.androml.cluster.core.PeerId
import dev.androml.core.api.CertificateFingerprint
import dev.androml.core.security.TlsIdentity
import dev.androml.core.security.TlsServerMaterial
import dev.androml.core.security.MtlsContextFactory
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.utils.io.readRemaining
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import io.netty.handler.ssl.SslHandler
import kotlinx.io.readByteArray

data class ClusterTransportConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8788,
    val maxRequestBodyBytes: Int = ClusterWireCodec.MAX_WIRE_BYTES,
    val maxResponseBodyBytes: Int = ClusterWireCodec.MAX_WIRE_BYTES,
) {
    init {
        require(host.isNotBlank() && host.length <= 253) { "cluster bind host is invalid" }
        require(host.none { it.isWhitespace() || it in "/?#" }) { "cluster bind host contains unsafe characters" }
        require(port in 1024..65_535) { "cluster port is out of bounds" }
        require(maxRequestBodyBytes in 1_024..ClusterWireCodec.MAX_WIRE_BYTES) {
            "cluster request body limit is out of bounds"
        }
        require(maxResponseBodyBytes in 1_024..ClusterWireCodec.MAX_WIRE_BYTES) {
            "cluster response body limit is out of bounds"
        }
    }
}

class ClusterTransportException(
    val httpStatus: Int? = null,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** mTLS-only peer endpoint for complete replica or workflow-stage jobs. */
class ClusterExecutionServer(
    private val config: ClusterTransportConfig,
    private val tlsMaterial: TlsServerMaterial,
    private val pairedPeers: () -> Map<CertificateFingerprint, PeerId>,
    private val executor: IdempotentClusterExecutor,
) {
    private var engine: EmbeddedServer<*, *>? = null

    fun start(wait: Boolean = false) {
        check(engine == null) { "cluster execution server is already running" }
        engine = embeddedServer(
            Netty,
            configure = {
                sslConnector(
                    keyStore = tlsMaterial.keyStore,
                    keyAlias = tlsMaterial.keyAlias,
                    keyStorePassword = tlsMaterial::keyStorePassword,
                    privateKeyPassword = tlsMaterial::privateKeyPassword,
                ) {
                    host = config.host
                    port = config.port
                    trustStore = tlsMaterial.trustStore
                    enabledProtocols = listOf("TLSv1.3", "TLSv1.2")
                }
            },
        ) {
            module()
        }.also { it.start(wait = wait) }
    }

    fun stop() {
        engine?.stop(1_000, 5_000)
        engine = null
    }

    fun Application.module() {
        routing {
            post("/cluster/v1/execute") {
                val peerFingerprint = peerFingerprint(call)
                val expectedPeer = peerFingerprint?.let { pairedPeers()[it] }
                if (expectedPeer == null) {
                    call.respondText(
                        "{\"error\":\"peer certificate is not paired\"}",
                        status = HttpStatusCode.Forbidden,
                    )
                    return@post
                }
                val declaredLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (declaredLength != null && declaredLength > config.maxRequestBodyBytes) {
                    call.respondText(
                        "{\"error\":\"request body too large\"}",
                        status = HttpStatusCode.PayloadTooLarge,
                    )
                    return@post
                }
                val raw = runCatching {
                    call.receiveBoundedText(config.maxRequestBodyBytes)
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
                    return@post
                }
                val request = runCatching { ClusterWireCodec.decodeRequest(raw) }
                    .getOrElse {
                        call.respondText(
                            "{\"error\":\"invalid request\"}",
                            status = HttpStatusCode.BadRequest,
                        )
                        return@post
                    }
                if (request.sourcePeerId != expectedPeer) {
                    call.respondText(
                        "{\"error\":\"source peer identity does not match certificate\"}",
                        status = HttpStatusCode.Forbidden,
                    )
                    return@post
                }
                val response = executor.execute(request)
                val responseBody = ClusterWireCodec.encodeResponse(response)
                if (responseBody.toByteArray(Charsets.UTF_8).size > config.maxResponseBodyBytes) {
                    call.respondText(
                        "{\"error\":\"response body too large\"}",
                        status = HttpStatusCode.InternalServerError,
                    )
                    return@post
                }
                call.respondText(responseBody, ContentType.Application.Json)
            }
        }
    }

    private fun peerFingerprint(call: ApplicationCall): CertificateFingerprint? {
        val engineCall = unwrapEngineCall(call)
        val nettyCall = engineCall as? NettyApplicationCall ?: return null
        val sslHandler = nettyCall.context.pipeline().get(SslHandler::class.java) ?: return null
        val certificate = runCatching {
            sslHandler.engine().session.peerCertificates.firstOrNull() as? X509Certificate
        }.getOrNull() ?: return null
        return CertificateFingerprint.parse(
            MessageDigest.getInstance("SHA-256")
                .digest(certificate.encoded)
                .joinToString("") { byte -> "%02x".format(byte) },
        )
    }

    private tailrec fun unwrapEngineCall(call: ApplicationCall): ApplicationCall = when (call) {
        is RoutingCall -> unwrapEngineCall(call.pipelineCall.engineCall)
        else -> call
    }
}

/** Synchronous client intended for an IO dispatcher or WorkManager worker. */
class ClusterExecutionClient(
    clientIdentity: TlsIdentity,
    trustedServerCertificate: X509Certificate,
    private val connectTimeoutMillis: Int = 5_000,
    private val readTimeoutMillis: Int = 60_000,
    private val maxResponseBodyBytes: Int = ClusterWireCodec.MAX_WIRE_BYTES,
) {
    private val sslContext = MtlsContextFactory.clientContext(
        identity = clientIdentity,
        trustedServerCertificates = listOf(trustedServerCertificate),
    )
    private val trustedServerFingerprint = certificateFingerprint(trustedServerCertificate)
    private val hostnameVerifier = HostnameVerifier { _, session ->
        val certificate = session.peerCertificates.firstOrNull() as? X509Certificate
        certificate != null && certificateFingerprint(certificate) == trustedServerFingerprint
    }

    init {
        require(connectTimeoutMillis in 1..120_000) { "cluster connect timeout is out of bounds" }
        require(readTimeoutMillis in 1..10 * 60 * 1_000) { "cluster read timeout is out of bounds" }
        require(maxResponseBodyBytes in 1_024..ClusterWireCodec.MAX_WIRE_BYTES) {
            "cluster response body limit is out of bounds"
        }
    }

    fun execute(endpoint: PeerEndpoint, request: ClusterExecutionRequest): ClusterExecutionResponse {
        val body = ClusterWireCodec.encodeRequest(request).toByteArray(Charsets.UTF_8)
        val connection = (URL(endpoint.toHttpsUrl()).openConnection() as? HttpsURLConnection)
            ?: throw ClusterTransportException(message = "cluster peer did not provide HTTPS")
        connection.apply {
            sslSocketFactory = sslContext.socketFactory
            hostnameVerifier = this@ClusterExecutionClient.hostnameVerifier
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            requestMethod = "POST"
            doInput = true
            doOutput = true
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setRequestProperty(HttpHeaders.Accept, ContentType.Application.Json.toString())
            setFixedLengthStreamingMode(body.size)
        }
        return try {
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw ClusterTransportException(
                    httpStatus = status,
                    message = "cluster peer rejected execution request",
                )
            }
            val responseBody = readBounded(connection.inputStream, maxResponseBodyBytes)
            runCatching { ClusterWireCodec.decodeResponse(responseBody) }
                .getOrElse { error ->
                    throw ClusterTransportException(
                        message = "cluster peer returned an invalid response",
                        cause = error,
                    )
                }
        } catch (error: ClusterTransportException) {
            throw error
        } catch (error: IOException) {
            throw ClusterTransportException(
                message = "cluster peer connection failed",
                cause = error,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: java.io.InputStream, maxBytes: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        input.use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count == -1) break
                total += count
                if (total > maxBytes) {
                    throw ClusterTransportException(message = "cluster response body is too large")
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    private fun certificateFingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { byte -> "%02x".format(byte) }
}

private class RequestBodyTooLargeException : IllegalStateException("cluster request body too large")

private suspend fun ApplicationCall.receiveBoundedText(maxBytes: Int): String {
    val bytes = receiveChannel()
        .readRemaining(maxBytes.toLong() + 1L)
        .readByteArray()
    if (bytes.size > maxBytes) throw RequestBodyTooLargeException()
    return bytes.toString(Charsets.UTF_8)
}

private fun PeerEndpoint.toHttpsUrl(): String {
    val formattedHost = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
    return "https://$formattedHost:$port/cluster/v1/execute"
}
