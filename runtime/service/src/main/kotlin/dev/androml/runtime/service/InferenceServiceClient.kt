package dev.androml.runtime.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import dev.androml.core.model.ModelRequirements
import dev.androml.runtime.api.InferenceErrorCode
import dev.androml.runtime.api.InferenceEvent
import dev.androml.runtime.api.InferenceRequest
import dev.androml.runtime.api.InferenceRequestId
import dev.androml.runtime.api.RuntimeConfiguration
import dev.androml.runtime.api.RuntimeId
import dev.androml.runtime.api.SessionId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InferenceServiceException(message: String) : IllegalStateException(message)

data class OpenedInferenceSession(
    val id: SessionId,
    val runtimeId: RuntimeId,
)

/** Lifecycle-aware Messenger client for the isolated runtime process. */
class InferenceServiceClient(context: Context) {
    private val applicationContext = context.applicationContext
    private val bindMutex = Mutex()
    private val openMutex = Mutex()
    private val pendingEvents: ConcurrentMap<String, Channel<InferenceEvent>> = ConcurrentHashMap()
    private val receiver = Messenger(IncomingHandler())
    private var service: Messenger? = null
    private var bindDeferred: CompletableDeferred<Messenger>? = null
    private var openDeferred: CompletableDeferred<OpenedInferenceSession>? = null
    private var healthDeferred: CompletableDeferred<Boolean>? = null
    private var bound = false

    suspend fun connect(): Unit = bindMutex.withLock {
        if (service != null) return
        val existing = bindDeferred
        if (existing != null) {
            existing.await()
            return
        }
        val deferred = CompletableDeferred<Messenger>()
        bindDeferred = deferred
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val messenger = Messenger(binder)
                service = messenger
                bound = true
                deferred.complete(messenger)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
                pendingEvents.values.forEach { it.close(InferenceServiceException("runtime process disconnected")) }
                pendingEvents.clear()
                openDeferred?.completeExceptionally(InferenceServiceException("runtime process disconnected"))
                healthDeferred?.completeExceptionally(InferenceServiceException("runtime process disconnected"))
            }

            override fun onBindingDied(name: ComponentName) {
                onServiceDisconnected(name)
                deferred.completeExceptionally(InferenceServiceException("runtime process binding died"))
            }

            override fun onNullBinding(name: ComponentName) {
                deferred.completeExceptionally(InferenceServiceException("runtime process returned no binding"))
            }
        }
        this.connection = connection
        try {
            val intent = Intent(applicationContext, InferenceProcessService::class.java)
            check(applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                "runtime process could not be bound"
            }
            deferred.invokeOnCompletion {
                if (deferred.isCancelled && bound) {
                    applicationContext.unbindService(connection)
                    bound = false
                    service = null
                }
            }
            deferred.await()
        } finally {
            bindDeferred = null
        }
    }

    suspend fun health(): Boolean {
        connect()
        val deferred = CompletableDeferred<Boolean>()
        healthDeferred = deferred
        send(InferenceServiceProtocol.HEALTH, Bundle())
        return try {
            deferred.await()
        } finally {
            healthDeferred = null
        }
    }

    suspend fun openSession(
        model: ModelRequirements,
        configuration: RuntimeConfiguration,
        runtimeId: RuntimeId = RuntimeId.parse("fake"),
        modelFile: ParcelFileDescriptor? = null,
    ): OpenedInferenceSession = openMutex.withLock {
        connect()
        val deferred = CompletableDeferred<OpenedInferenceSession>()
        openDeferred = deferred
        try {
            send(
                InferenceServiceProtocol.OPEN_SESSION,
                Bundle().apply {
                    putString(InferenceServiceProtocol.MODEL_WORKLOAD_KEY, model.workload.name)
                    putLong(InferenceServiceProtocol.MODEL_WEIGHT_BYTES_KEY, model.weightBytes)
                    putLong(InferenceServiceProtocol.MODEL_KV_BYTES_PER_TOKEN_KEY, model.kvCacheBytesPerToken)
                    putInt(InferenceServiceProtocol.MODEL_CONTEXT_TOKENS_KEY, model.contextTokens)
                    putInt(InferenceServiceProtocol.CPU_THREADS_KEY, configuration.cpuThreads)
                    putBoolean(InferenceServiceProtocol.USE_ACCELERATION_KEY, configuration.useAcceleration)
                    putString(InferenceServiceProtocol.RUNTIME_ID_KEY, runtimeId.value)
                    @Suppress("DEPRECATION")
                    modelFile?.let { putParcelable(InferenceServiceProtocol.MODEL_FD_KEY, it) }
                },
            )
        } finally {
            modelFile?.close()
        }
        return try {
            deferred.await()
        } finally {
            openDeferred = null
        }
    }

    fun stream(
        session: OpenedInferenceSession,
        request: InferenceRequest,
    ): Flow<InferenceEvent> = flow {
        connect()
        val channel = Channel<InferenceEvent>(capacity = Channel.BUFFERED)
        check(pendingEvents.putIfAbsent(request.id.value, channel) == null) {
            "inference request is already being streamed"
        }
        try {
            send(
                InferenceServiceProtocol.GENERATE,
                Bundle().apply {
                    putString(InferenceServiceProtocol.SESSION_ID_KEY, session.id.value)
                    putString(InferenceServiceProtocol.REQUEST_ID_KEY, request.id.value)
                    putString(InferenceServiceProtocol.PROMPT_KEY, request.prompt)
                    putInt(InferenceServiceProtocol.MAX_NEW_TOKENS_KEY, request.maxNewTokens)
                    putDouble(InferenceServiceProtocol.TEMPERATURE_KEY, request.temperature)
                    request.tensorInput?.let { input ->
                        putByteArray(InferenceServiceProtocol.TENSOR_INPUT_DATA_KEY, input.data)
                        putLongArray(InferenceServiceProtocol.TENSOR_INPUT_SHAPE_KEY, input.shape)
                        putString(InferenceServiceProtocol.TENSOR_INPUT_TYPE_KEY, input.dataType.name)
                    }
                },
            )
            for (event in channel) {
                emit(event)
                if (event is InferenceEvent.Completed ||
                    event is InferenceEvent.Failed ||
                    event is InferenceEvent.Cancelled
                ) {
                    break
                }
            }
        } finally {
            pendingEvents.remove(request.id.value)
            if (!currentCoroutineContext().isActive) {
                cancel(session, request.id)
            }
            channel.close()
        }
    }

    fun cancel(session: OpenedInferenceSession, requestId: InferenceRequestId) {
        runCatching {
            send(
                InferenceServiceProtocol.CANCEL,
                Bundle().apply {
                    putString(InferenceServiceProtocol.SESSION_ID_KEY, session.id.value)
                    putString(InferenceServiceProtocol.REQUEST_ID_KEY, requestId.value)
                },
            )
        }
    }

    fun closeSession(session: OpenedInferenceSession) {
        runCatching {
            send(
                InferenceServiceProtocol.CLOSE_SESSION,
                Bundle().apply { putString(InferenceServiceProtocol.SESSION_ID_KEY, session.id.value) },
            )
        }
    }

    fun close() {
        pendingEvents.values.forEach { it.close(InferenceServiceException("runtime client closed")) }
        pendingEvents.clear()
        service = null
        if (bound) {
            runCatching {
                applicationContext.unbindService(connection)
            }
            bound = false
        }
    }

    private lateinit var connection: ServiceConnection

    private fun send(what: Int, data: Bundle) {
        val target = service ?: throw InferenceServiceException("runtime process is not connected")
        data.putInt(InferenceServiceProtocol.VERSION_KEY, InferenceServiceProtocol.PROTOCOL_VERSION)
        try {
            target.send(Message.obtain(null, what).apply {
                this.data = data
                replyTo = receiver
            })
        } catch (error: RemoteException) {
            service = null
            throw InferenceServiceException("runtime process did not accept the request")
        }
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            val data = message.data
            if (data.getInt(InferenceServiceProtocol.VERSION_KEY, -1) !=
                InferenceServiceProtocol.PROTOCOL_VERSION
            ) return
            when (message.what) {
                InferenceServiceProtocol.EVENT_HEALTH -> healthDeferred?.complete(
                    data.getBoolean(InferenceServiceProtocol.READY_KEY, false),
                )
                InferenceServiceProtocol.EVENT_SESSION_OPENED -> handleOpened(data)
                InferenceServiceProtocol.EVENT_FAILED -> handleFailure(data)
                InferenceServiceProtocol.EVENT_STARTED,
                InferenceServiceProtocol.EVENT_TOKEN,
                InferenceServiceProtocol.EVENT_COMPLETED,
                InferenceServiceProtocol.EVENT_CANCELLED,
                -> parseEvent(message.what, data)?.let { event ->
                    pendingEvents[event.requestId.value]?.trySend(event)
                }
            }
        }
    }

    private fun handleOpened(data: Bundle) {
        val sessionId = data.getString(InferenceServiceProtocol.SESSION_ID_KEY)
            ?.let { runCatching { SessionId.parse(it) }.getOrNull() }
        val runtimeId = data.getString(InferenceServiceProtocol.RUNTIME_ID_KEY)
            ?.let { runCatching { RuntimeId.parse(it) }.getOrNull() }
        if (sessionId == null || runtimeId == null) {
            openDeferred?.completeExceptionally(InferenceServiceException("runtime returned an invalid session"))
        } else {
            openDeferred?.complete(OpenedInferenceSession(sessionId, runtimeId))
        }
    }

    private fun handleFailure(data: Bundle) {
        val code = data.getString(InferenceServiceProtocol.ERROR_CODE_KEY)
            ?.let { runCatching { InferenceErrorCode.valueOf(it) }.getOrNull() }
        val message = data.getString(InferenceServiceProtocol.SAFE_MESSAGE_KEY)
            ?.take(512)
            ?: "runtime request failed"
        val requestId = data.getString(InferenceServiceProtocol.REQUEST_ID_KEY)
        if (requestId != null) {
            val parsed = runCatching { InferenceRequestId.parse(requestId) }.getOrNull()
            if (parsed != null) {
                pendingEvents[parsed.value]?.trySend(
                    InferenceEvent.Failed(parsed, code ?: InferenceErrorCode.Unexpected, message),
                )
                return
            }
        }
        openDeferred?.completeExceptionally(InferenceServiceException(message))
    }

    private fun parseEvent(what: Int, data: Bundle): InferenceEvent? {
        val requestId = data.getString(InferenceServiceProtocol.REQUEST_ID_KEY)
            ?.let { runCatching { InferenceRequestId.parse(it) }.getOrNull() }
            ?: return null
        return runCatching {
            when (what) {
                InferenceServiceProtocol.EVENT_STARTED -> InferenceEvent.Started(
                    requestId,
                    RuntimeId.parse(data.getString(InferenceServiceProtocol.RUNTIME_ID_KEY).orEmpty()),
                )
                InferenceServiceProtocol.EVENT_TOKEN -> InferenceEvent.Token(
                    requestId,
                    data.getString(InferenceServiceProtocol.TOKEN_KEY).orEmpty(),
                )
                InferenceServiceProtocol.EVENT_COMPLETED -> InferenceEvent.Completed(
                    requestId,
                    data.getInt(InferenceServiceProtocol.GENERATED_TOKENS_KEY, -1),
                    data.getLong(InferenceServiceProtocol.DURATION_MS_KEY, -1L),
                )
                InferenceServiceProtocol.EVENT_CANCELLED -> InferenceEvent.Cancelled(requestId)
                else -> null
            }
        }.getOrNull()
    }
}
