package dev.androml.api.server

/** Small in-memory guard around expensive bearer-token verification. */
class ApiAuthAttemptLimiter(
    private val maxConcurrent: Int = 4,
    private val maxConcurrentPerSource: Int = 2,
    private val maxFailuresPerWindow: Int = 8,
    private val failureWindowNanos: Long = 1_000_000_000L,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    init {
        require(maxConcurrent > 0)
        require(maxConcurrentPerSource in 1..maxConcurrent)
        require(maxFailuresPerWindow > 0)
        require(failureWindowNanos > 0L)
    }

    private data class SourceState(
        var inFlight: Int = 0,
        val failures: ArrayDeque<Long> = ArrayDeque(),
    )

    private val lock = Any()
    private val sources = LinkedHashMap<String, SourceState>()
    private var globalInFlight = 0

    fun tryAcquire(source: String): Boolean = synchronized(lock) {
        val now = clockNanos()
        val state = sources.getOrPut(source) { SourceState() }
        trim(state, now)
        if (state.failures.size >= maxFailuresPerWindow ||
            globalInFlight >= maxConcurrent || state.inFlight >= maxConcurrentPerSource
        ) return@synchronized false
        globalInFlight++
        state.inFlight++
        true
    }

    fun release(source: String) = synchronized(lock) {
        sources[source]?.let { state ->
            state.inFlight = (state.inFlight - 1).coerceAtLeast(0)
            globalInFlight = (globalInFlight - 1).coerceAtLeast(0)
            if (state.inFlight == 0 && state.failures.isEmpty()) sources.remove(source)
        }
    }

    fun recordFailure(source: String) = synchronized(lock) {
        val now = clockNanos()
        val state = sources.getOrPut(source) { SourceState() }
        trim(state, now)
        state.failures.addLast(now)
        // Never let attacker-controlled source labels grow memory without bound.
        while (sources.size > 1024) {
            val removable = sources.entries.firstOrNull { it.value.inFlight == 0 } ?: break
            sources.remove(removable.key)
        }
    }

    private fun trim(state: SourceState, now: Long) {
        while (state.failures.firstOrNull()?.let { now - it >= failureWindowNanos } == true) {
            state.failures.removeFirst()
        }
    }
}
