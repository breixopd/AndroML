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
import androidx.core.os.BundleCompat
import dev.androml.core.model.ModelRequirements
import dev.androml.core.model.ModelWorkload
import dev.androml.runtime.api.InferenceErrorCode
import dev.androml.runtime.api.InferenceEvent
import dev.androml.runtime.api.InferenceRequest
import dev.androml.runtime.api.InferenceRequestId
import dev.androml.runtime.api.RuntimeConfiguration
import dev.androml.runtime.api.RuntimeSession
import dev.androml.runtime.api.SessionId
import dev.androml.runtime.api.RuntimePackCatalog
import dev.androml.runtime.api.TensorDataType
import dev.androml.runtime.api.TensorInput
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers.IO

/** Non-exported, network-free process boundary for runtime execution. */
class InferenceProcessService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions: ConcurrentMap<String, ActiveSession> = ConcurrentHashMap()
    private val admissionLock = Any()
    private var pendingSessions = 0
    private val activeJobs = AtomicInteger(0)
    private val messenger = Messenger(InferenceHandler())
    private val idleReaper = serviceScope.launch {
        while (true) {
            delay(IDLE_REAP_INTERVAL_MS)
            val cutoff = System.currentTimeMillis() - SESSION_IDLE_TIMEOUT_MS
            sessions.entries
                .filter { (_, session) -> session.jobs.isEmpty() && session.lastUsed() <= cutoff }
                .forEach { (id, session) ->
                    if (sessions.remove(id, session)) session.close()
                }
        }
    }

    /** The service owns adapter construction so callers cannot inject executable code. */
    private fun registry(modelFile: ParcelFileDescriptor): RuntimeRegistry =
        RuntimeRegistry("/proc/self/fd/${modelFile.fd}")

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        sessions.values.forEach { it.requestClose() }
        sessions.clear()
        idleReaper.cancel()
        serviceScope.cancel()
        super.onDestroy()
        killRuntimeProcess()
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
        val modelFile = BundleCompat.getParcelable(
            data,
            InferenceServiceProtocol.MODEL_FD_KEY,
            ParcelFileDescriptor::class.java,
        )
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
        synchronized(admissionLock) {
            if (sessions.size + pendingSessions >= MAX_SESSIONS) {
                modelFile?.close()
                sendFailure(replyTo, null, null, InferenceErrorCode.RuntimeUnavailable, "runtime is busy")
                return
            }
            pendingSessions += 1
        }
        serviceScope.launch {
            val openFinished = AtomicBoolean(false)
            val openWatchdog = serviceScope.launch {
                delay(OPEN_SESSION_TIMEOUT_MS)
                if (openFinished.compareAndSet(false, true)) killRuntimeProcess()
            }
            val model = ModelRequirements(
                workload = workload,
                weightBytes = weightBytes,
                kvCacheBytesPerToken = kvBytes,
                contextTokens = contextTokens,
            )
            var openedSession: RuntimeSession? = null
            val session = try {
                val descriptor = modelFile ?: throw IllegalArgumentException("model file is required")
                registry(descriptor).adapterFor(runtimeId).openSession(model, configuration).also {
                    openedSession = it
                }
            } catch (_: CancellationException) {
                runCatching { openedSession?.close() }
                modelFile?.close()
                synchronized(admissionLock) { pendingSessions -= 1 }
                return@launch
            } catch (_: Exception) {
                runCatching { openedSession?.close() }
                modelFile?.close()
                synchronized(admissionLock) { pendingSessions -= 1 }
                sendFailure(replyTo, null, null, InferenceErrorCode.RuntimeUnavailable, "runtime cannot serve model")
                return@launch
            } finally {
                openFinished.set(true)
                openWatchdog.cancel()
            }
            val active = ActiveSession(session, replyTo, modelFile)
            synchronized(admissionLock) {
                pendingSessions -= 1
                sessions[session.id.value] = active
            }
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
        if (active.isClosing()) {
            sendFailure(replyTo, null, sessionId.value, InferenceErrorCode.SessionUnavailable, "session is closing")
            return
        }
        active.replyTo = replyTo
        active.touch()
        val request = parseRequest(data) ?: run {
            sendFailure(replyTo, null, sessionId.value, InferenceErrorCode.InvalidRequest, "invalid inference request")
            return
        }
        if (active.jobs.size >= MAX_JOBS_PER_SESSION) {
            sendFailure(replyTo, request.id.value, sessionId.value, InferenceErrorCode.RuntimeUnavailable, "runtime is busy")
            return
        }
        if (activeJobs.incrementAndGet() > MAX_GLOBAL_JOBS) {
            activeJobs.decrementAndGet()
            sendFailure(replyTo, request.id.value, sessionId.value, InferenceErrorCode.RuntimeUnavailable, "runtime is busy")
            return
        }
        val terminalSent = AtomicBoolean(false)
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            val timeoutWatcher = serviceScope.launch {
                delay(GENERATION_TIMEOUT_MS)
                if (active.jobs.containsKey(request.id.value) && terminalSent.compareAndSet(false, true)) {
                    active.session.cancel(request.id)
                    sendEvent(
                        active.replyTo,
                        sessionId,
                        InferenceEvent.Failed(
                            request.id,
                            InferenceErrorCode.TimedOut,
                            "inference timed out",
                        ),
                    )
                    delay(PROCESS_KILL_GRACE_MS)
                    killRuntimeProcess()
                }
            }
            try {
                withContext(IO) {
                    active.session.generate(request) { event ->
                        val terminal = event is InferenceEvent.Completed ||
                            event is InferenceEvent.Failed ||
                            event is InferenceEvent.Cancelled
                        if (!terminal || terminalSent.compareAndSet(false, true)) {
                            sendEvent(active.replyTo, sessionId, event)
                        }
                    }
                }
            } catch (_: CancellationException) {
                if (terminalSent.compareAndSet(false, true)) {
                    sendEvent(active.replyTo, sessionId, InferenceEvent.Cancelled(request.id))
                }
            } catch (_: Exception) {
                if (terminalSent.compareAndSet(false, true)) {
                    sendEvent(
                        active.replyTo,
                        sessionId,
                        InferenceEvent.Failed(request.id, InferenceErrorCode.RuntimeCrashed, "runtime execution failed"),
                    )
                }
            } finally {
                timeoutWatcher.cancel()
            }
        }
        job.invokeOnCompletion {
            active.jobs.remove(request.id.value, job)
            activeJobs.decrementAndGet()
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
        val job = active.jobs[requestId.value]
        if (job == null) {
            sendEvent(replyTo, sessionId, InferenceEvent.Cancelled(requestId))
        } else {
            job.cancel()
            serviceScope.launch {
                delay(CANCEL_REQUEST_GRACE_MS)
                if (active.jobs[requestId.value] === job) killRuntimeProcess()
            }
        }
    }

    private fun closeSession(data: Bundle, replyTo: Messenger) {
        val sessionId = parseSessionId(data) ?: return
        val active = sessions.remove(sessionId.value)
        active?.requestClose()
        serviceScope.launch(IO) {
            active?.close()
            send(replyTo, InferenceServiceProtocol.EVENT_SESSION_CLOSED, Bundle().apply {
                putInt(InferenceServiceProtocol.VERSION_KEY, InferenceServiceProtocol.PROTOCOL_VERSION)
                putString(InferenceServiceProtocol.SESSION_ID_KEY, sessionId.value)
            })
        }
    }

    private fun sendHealth(replyTo: Messenger) {
        send(replyTo, InferenceServiceProtocol.EVENT_HEALTH, Bundle().apply {
            putInt(InferenceServiceProtocol.VERSION_KEY, InferenceServiceProtocol.PROTOCOL_VERSION)
            putBoolean(InferenceServiceProtocol.READY_KEY, true)
            putStringArrayList(
                InferenceServiceProtocol.RUNTIME_IDS_KEY,
                RuntimePackCatalog.production.map { it.descriptor.id.value }.toCollection(ArrayList()),
            )
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
        val tensorKeysPresent = data.containsKey(InferenceServiceProtocol.TENSOR_INPUT_DATA_KEY) ||
            data.containsKey(InferenceServiceProtocol.TENSOR_INPUT_SHAPE_KEY) ||
            data.containsKey(InferenceServiceProtocol.TENSOR_INPUT_TYPE_KEY)
        val tensorInput = parseTensorInput(data)
        if (tensorKeysPresent && tensorInput == null) return null
        return runCatching {
            InferenceRequest(
                id = id,
                prompt = prompt,
                maxNewTokens = data.getInt(InferenceServiceProtocol.MAX_NEW_TOKENS_KEY, -1),
                temperature = data.getDouble(InferenceServiceProtocol.TEMPERATURE_KEY, Double.NaN),
                tensorInput = tensorInput,
            )
        }.getOrNull()
    }

    private fun parseTensorInput(data: Bundle): TensorInput? {
        val bytes = data.getByteArray(InferenceServiceProtocol.TENSOR_INPUT_DATA_KEY) ?: return null
        val shape = data.getLongArray(InferenceServiceProtocol.TENSOR_INPUT_SHAPE_KEY) ?: return null
        val type = data.getString(InferenceServiceProtocol.TENSOR_INPUT_TYPE_KEY)
            ?.let { raw -> runCatching { TensorDataType.valueOf(raw) }.getOrNull() }
            ?: return null
        if (bytes.size > InferenceServiceProtocol.MAX_TENSOR_INPUT_BYTES) return null
        return runCatching { TensorInput(bytes, shape, type) }.getOrNull()
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

    private fun killRuntimeProcess() {
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private inner class ActiveSession(
        val session: RuntimeSession,
        @Volatile var replyTo: Messenger,
        private val modelFile: ParcelFileDescriptor?,
    ) {
        val jobs: ConcurrentMap<String, kotlinx.coroutines.Job> = ConcurrentHashMap()
        private val lastUsedEpochMillis = AtomicLong(System.currentTimeMillis())

        fun isClosing(): Boolean = closing.get()
        fun touch() = lastUsedEpochMillis.set(System.currentTimeMillis())
        fun lastUsed(): Long = lastUsedEpochMillis.get()

        fun requestClose() {
            if (closing.compareAndSet(false, true)) {
                jobs.keys.forEach { requestId ->
                    runCatching { session.cancel(InferenceRequestId.parse(requestId)) }
                }
                jobs.values.toList().forEach { it.cancel() }
            }
        }

        suspend fun close() {
            requestClose()
            val stopped = withTimeoutOrNull(CLOSE_SESSION_GRACE_MS) {
                jobs.values.toList().forEach { it.join() }
                true
            } ?: false
            if (!stopped) killRuntimeProcess()
            jobs.clear()
            if (closed.compareAndSet(false, true)) {
                runCatching { session.close() }
                runCatching { modelFile?.close() }
            }
        }

        private val closing = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)
    }

    private companion object {
        const val MAX_SESSIONS = 2
        const val MAX_GLOBAL_JOBS = 2
        const val MAX_JOBS_PER_SESSION = 1
        const val GENERATION_TIMEOUT_MS = 120_000L
        const val OPEN_SESSION_TIMEOUT_MS = 60_000L
        const val CLOSE_SESSION_GRACE_MS = 5_000L
        const val CANCEL_REQUEST_GRACE_MS = 5_000L
        const val PROCESS_KILL_GRACE_MS = 100L
        const val SESSION_IDLE_TIMEOUT_MS = 5 * 60_000L
        const val IDLE_REAP_INTERVAL_MS = 60_000L
    }
}
