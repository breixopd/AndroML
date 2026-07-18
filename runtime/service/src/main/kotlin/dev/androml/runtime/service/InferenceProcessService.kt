package dev.androml.runtime.service

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import dev.androml.core.model.ModelRequirements
import dev.androml.core.model.ModelWorkload
import dev.androml.runtime.api.FakeRuntimeAdapter
import dev.androml.runtime.api.InferenceErrorCode
import dev.androml.runtime.api.InferenceEvent
import dev.androml.runtime.api.InferenceRequest
import dev.androml.runtime.api.InferenceRequestId
import dev.androml.runtime.api.RuntimeConfiguration
import dev.androml.runtime.api.RuntimeSession
import dev.androml.runtime.api.SessionId
import dev.androml.runtime.litertlm.LiteRtLmRuntimeAdapter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Non-exported, network-free process boundary for runtime execution. */
class InferenceProcessService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions: ConcurrentMap<String, ActiveSession> = ConcurrentHashMap()
    private val messenger = Messenger(InferenceHandler())

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    private inner class InferenceHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            val replyTo = message.replyTo
            if (replyTo == null) return
            val version = message.data.getInt(InferenceServiceProtocol.VERSION_KEY, -1)
            if (version != InferenceServiceProtocol.PROTOCOL_VERSION) {
                sendFailure(replyTo, null, null, InferenceErrorCode.InvalidRequest, "unsupported protocol")
                return
            }
            when (message.what) {
                InferenceServiceProtocol.OPEN_SESSION -> openSession(message.data, replyTo)
                InferenceServiceProtocol.GENERATE -> generate(message.data, replyTo)
                InferenceServiceProtocol.CANCEL -> cancel(message.data, replyTo)
                InferenceServiceProtocol.CLOSE_SESSION -> closeSession(message.data, replyTo)
                InferenceServiceProtocol.HEALTH -> sendHealth(replyTo)
                else -> sendFailure(replyTo, null, null, InferenceErrorCode.InvalidRequest, "unknown operation")
            }
        }
    }

    private fun openSession(data: Bundle, replyTo: Messenger) {
        val runtimeId = data.getString(InferenceServiceProtocol.RUNTIME_ID_KEY)
            ?.let { runCatching { dev.androml.runtime.api.RuntimeId.parse(it) }.getOrNull() }
        @Suppress("DEPRECATION")
        val modelFile = data.getParcelable(InferenceServiceProtocol.MODEL_FD_KEY) as? ParcelFileDescriptor
        val workload = data.getString(InferenceServiceProtocol.MODEL_WORKLOAD_KEY)
            ?.takeIf { it.length <= InferenceServiceProtocol.MAX_MODEL_WORKLOAD_CHARS }
            ?.let { raw -> runCatching { ModelWorkload.valueOf(raw) }.getOrNull() }
        val weightBytes = data.getLong(InferenceServiceProtocol.MODEL_WEIGHT_BYTES_KEY, -1L)
        val kvBytes = data.getLong(InferenceServiceProtocol.MODEL_KV_BYTES_PER_TOKEN_KEY, -1L)
        val contextTokens = data.getInt(InferenceServiceProtocol.MODEL_CONTEXT_TOKENS_KEY, -1)
        val cpuThreads = data.getInt(InferenceServiceProtocol.CPU_THREADS_KEY, -1)
        val useAcceleration = data.getBoolean(InferenceServiceProtocol.USE_ACCELERATION_KEY, false)
        if (runtimeId == null || workload == null || weightBytes < 0L || kvBytes < 0L || contextTokens < 0) {
            modelFile?.close()
            sendFailure(replyTo, null, null, InferenceErrorCode.InvalidRequest, "invalid model requirements")
            return
        }
        val configuration = runCatching {
            RuntimeConfiguration(
                cpuThreads = cpuThreads,
                contextTokens = contextTokens.coerceAtLeast(1),
                useAcceleration = useAcceleration,
            )
        }.getOrElse {
            modelFile?.close()
            sendFailure(replyTo, null, null, InferenceErrorCode.InvalidRequest, "invalid runtime configuration")
            return
        }
        serviceScope.launch {
            val model = ModelRequirements(
                workload = workload,
                weightBytes = weightBytes,
                kvCacheBytesPerToken = kvBytes,
                contextTokens = contextTokens,
            )
            val session = try {
                withTimeout(60_000L) {
                    when (runtimeId.value) {
                        "fake" -> {
                            modelFile?.close()
                            FakeRuntimeAdapter().openSession(model, configuration)
                        }
                        "litertlm" -> {
                            val descriptor = modelFile ?: throw IllegalArgumentException("model file is required")
                            LiteRtLmRuntimeAdapter("/proc/self/fd/${descriptor.fd}").openSession(model, configuration)
                        }
                        else -> throw IllegalArgumentException("runtime is not installed")
                    }
                }
            } catch (_: CancellationException) {
                modelFile?.close()
                return@launch
            } catch (_: Exception) {
                modelFile?.close()
                sendFailure(replyTo, null, null, InferenceErrorCode.RuntimeUnavailable, "runtime cannot serve model")
                return@launch
            }
            val active = ActiveSession(session, replyTo, modelFile)
            sessions[session.id.value] = active
            send(
                replyTo,
                InferenceServiceProtocol.EVENT_SESSION_OPENED,
                Bundle().apply {
                    putInt(InferenceServiceProtocol.VERSION_KEY, InferenceServiceProtocol.PROTOCOL_VERSION)
                    putString(InferenceServiceProtocol.SESSION_ID_KEY, session.id.value)
                    putString(InferenceServiceProtocol.RUNTIME_ID_KEY, session.runtimeId.value)
                },
            )
        }
    }

    private fun generate(data: Bundle, replyTo: Messenger) {
        val sessionId = parseSessionId(data) ?: run {
            sendFailure(replyTo, null, null, InferenceErrorCode.InvalidRequest, "invalid session ID")
            return
        }
        val active = sessions[sessionId.value]
        if (active == null) {
            sendFailure(replyTo, null, sessionId.value, InferenceErrorCode.SessionUnavailable, "session unavailable")
            return
        }
        active.replyTo = replyTo
        val request = parseRequest(data) ?: run {
            sendFailure(replyTo, null, sessionId.value, InferenceErrorCode.InvalidRequest, "invalid inference request")
            return
        }
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
                try {
                    active.session.generate(request) { event -> sendEvent(active.replyTo, sessionId, event) }
                } catch (_: CancellationException) {
                    sendEvent(active.replyTo, sessionId, InferenceEvent.Cancelled(request.id))
                } catch (_: Exception) {
                    sendEvent(
                        active.replyTo,
                        sessionId,
                        InferenceEvent.Failed(request.id, InferenceErrorCode.RuntimeCrashed, "runtime execution failed"),
                    )
                } finally {
                    active.jobs.remove(request.id.value)
                }
            }
        if (active.jobs.putIfAbsent(request.id.value, job) != null) {
            job.cancel()
            sendFailure(replyTo, request.id.value, sessionId.value, InferenceErrorCode.InvalidRequest, "request already running")
        } else {
            job.start()
        }
    }

    private fun cancel(data: Bundle, replyTo: Messenger) {
        val sessionId = parseSessionId(data) ?: return
        val requestId = parseRequestId(data) ?: return
        val active = sessions[sessionId.value] ?: return
        active.replyTo = replyTo
        active.session.cancel(requestId)
        val job = active.jobs.remove(requestId.value)
        if (job == null) {
            sendEvent(replyTo, sessionId, InferenceEvent.Cancelled(requestId))
        } else {
            job.cancel()
        }
    }

    private fun closeSession(data: Bundle, replyTo: Messenger) {
        val sessionId = parseSessionId(data) ?: return
        sessions.remove(sessionId.value)?.close()
        send(replyTo, InferenceServiceProtocol.EVENT_SESSION_CLOSED, Bundle().apply {
            putInt(InferenceServiceProtocol.VERSION_KEY, InferenceServiceProtocol.PROTOCOL_VERSION)
            putString(InferenceServiceProtocol.SESSION_ID_KEY, sessionId.value)
        })
    }

    private fun sendHealth(replyTo: Messenger) {
        send(replyTo, InferenceServiceProtocol.EVENT_HEALTH, Bundle().apply {
            putInt(InferenceServiceProtocol.VERSION_KEY, InferenceServiceProtocol.PROTOCOL_VERSION)
            putBoolean(InferenceServiceProtocol.READY_KEY, true)
            putString(InferenceServiceProtocol.RUNTIME_ID_KEY, "litertlm")
        })
    }

    private fun parseSessionId(data: Bundle): SessionId? = data
        .getString(InferenceServiceProtocol.SESSION_ID_KEY)
        ?.let { runCatching { SessionId.parse(it) }.getOrNull() }

    private fun parseRequestId(data: Bundle): InferenceRequestId? = data
        .getString(InferenceServiceProtocol.REQUEST_ID_KEY)
        ?.let { runCatching { InferenceRequestId.parse(it) }.getOrNull() }

    private fun parseRequest(data: Bundle): InferenceRequest? {
        val id = parseRequestId(data) ?: return null
        val prompt = data.getString(InferenceServiceProtocol.PROMPT_KEY) ?: return null
        if (prompt.length > InferenceServiceProtocol.MAX_PROMPT_CHARS) return null
        return runCatching {
            InferenceRequest(
                id = id,
                prompt = prompt,
                maxNewTokens = data.getInt(InferenceServiceProtocol.MAX_NEW_TOKENS_KEY, -1),
                temperature = data.getDouble(InferenceServiceProtocol.TEMPERATURE_KEY, Double.NaN),
            )
        }.getOrNull()
    }

    private fun sendEvent(replyTo: Messenger, sessionId: SessionId, event: InferenceEvent) {
        val data = Bundle().apply {
            putInt(InferenceServiceProtocol.VERSION_KEY, InferenceServiceProtocol.PROTOCOL_VERSION)
            putString(InferenceServiceProtocol.SESSION_ID_KEY, sessionId.value)
            putString(InferenceServiceProtocol.REQUEST_ID_KEY, event.requestId.value)
        }
        val type = when (event) {
            is InferenceEvent.Started -> {
                data.putString(InferenceServiceProtocol.RUNTIME_ID_KEY, event.runtimeId.value)
                InferenceServiceProtocol.EVENT_STARTED
            }
            is InferenceEvent.Token -> {
                data.putString(InferenceServiceProtocol.TOKEN_KEY, event.text)
                InferenceServiceProtocol.EVENT_TOKEN
            }
            is InferenceEvent.Completed -> {
                data.putInt(InferenceServiceProtocol.GENERATED_TOKENS_KEY, event.generatedTokens)
                data.putLong(InferenceServiceProtocol.DURATION_MS_KEY, event.durationMs)
                InferenceServiceProtocol.EVENT_COMPLETED
            }
            is InferenceEvent.Failed -> {
                data.putString(InferenceServiceProtocol.ERROR_CODE_KEY, event.code.name)
                data.putString(InferenceServiceProtocol.SAFE_MESSAGE_KEY, event.safeMessage)
                InferenceServiceProtocol.EVENT_FAILED
            }
            is InferenceEvent.Cancelled -> InferenceServiceProtocol.EVENT_CANCELLED
        }
        send(replyTo, type, data)
    }

    private fun sendFailure(
        replyTo: Messenger,
        requestId: String?,
        sessionId: String?,
        code: InferenceErrorCode,
        message: String,
    ) {
        send(replyTo, InferenceServiceProtocol.EVENT_FAILED, Bundle().apply {
            putInt(InferenceServiceProtocol.VERSION_KEY, InferenceServiceProtocol.PROTOCOL_VERSION)
            requestId?.let { putString(InferenceServiceProtocol.REQUEST_ID_KEY, it) }
            sessionId?.let { putString(InferenceServiceProtocol.SESSION_ID_KEY, it) }
            putString(InferenceServiceProtocol.ERROR_CODE_KEY, code.name)
            putString(InferenceServiceProtocol.SAFE_MESSAGE_KEY, message)
        })
    }

    private fun send(replyTo: Messenger, what: Int, data: Bundle) {
        try {
            replyTo.send(Message.obtain(null, what).apply { this.data = data })
        } catch (_: RemoteException) {
            // The client disappeared. The session remains bounded and is reclaimed on close/process death.
        }
    }

    private class ActiveSession(
        val session: RuntimeSession,
        @Volatile var replyTo: Messenger,
        private val modelFile: ParcelFileDescriptor?,
    ) {
        val jobs: ConcurrentMap<String, kotlinx.coroutines.Job> = ConcurrentHashMap()

        fun close() {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
            session.close()
            modelFile?.close()
        }
    }
}
