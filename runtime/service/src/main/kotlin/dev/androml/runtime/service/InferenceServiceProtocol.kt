package dev.androml.runtime.service

/** Messenger wire values are intentionally primitive and versioned at the service boundary. */
object InferenceServiceProtocol {
    const val PROTOCOL_VERSION = 1

    const val OPEN_SESSION = 1
    const val GENERATE = 2
    const val CANCEL = 3
    const val CLOSE_SESSION = 4
    const val HEALTH = 5

    const val EVENT_SESSION_OPENED = 101
    const val EVENT_HEALTH = 102
    const val EVENT_STARTED = 103
    const val EVENT_TOKEN = 104
    const val EVENT_COMPLETED = 105
    const val EVENT_FAILED = 106
    const val EVENT_CANCELLED = 107
    const val EVENT_SESSION_CLOSED = 108

    const val VERSION_KEY = "protocol_version"
    const val SESSION_ID_KEY = "session_id"
    const val REQUEST_ID_KEY = "request_id"
    const val RUNTIME_ID_KEY = "runtime_id"
    const val MODEL_WORKLOAD_KEY = "model_workload"
    const val MODEL_WEIGHT_BYTES_KEY = "model_weight_bytes"
    const val MODEL_KV_BYTES_PER_TOKEN_KEY = "model_kv_bytes_per_token"
    const val MODEL_CONTEXT_TOKENS_KEY = "model_context_tokens"
    const val CPU_THREADS_KEY = "cpu_threads"
    const val USE_ACCELERATION_KEY = "use_acceleration"
    const val PROMPT_KEY = "prompt"
    const val MAX_NEW_TOKENS_KEY = "max_new_tokens"
    const val TEMPERATURE_KEY = "temperature"
    const val TOKEN_KEY = "token"
    const val GENERATED_TOKENS_KEY = "generated_tokens"
    const val DURATION_MS_KEY = "duration_ms"
    const val ERROR_CODE_KEY = "error_code"
    const val SAFE_MESSAGE_KEY = "safe_message"
    const val READY_KEY = "ready"

    const val MAX_MODEL_WORKLOAD_CHARS = 64
    const val MAX_PROMPT_CHARS = 64 * 1024
}
