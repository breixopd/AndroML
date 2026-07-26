package dev.androml.core.network

/** Cheap, non-allocating guard run before kotlinx.serialization's recursive JSON parser. */
internal object JsonSafety {
    private const val MAX_DEPTH = 128
    private const val MAX_STRUCTURAL_CHARS = 2_000_000

    fun validate(body: String) {
        var depth = 0
        var structural = 0
        var inString = false
        var escaped = false
        body.forEach { ch ->
            if (inString) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') inString = false
                return@forEach
            }
            if (ch == '"') { inString = true; return@forEach }
            if (ch == '{' || ch == '[') {
                depth++
                structural++
                if (depth > MAX_DEPTH || structural > MAX_STRUCTURAL_CHARS) {
                    throw IllegalArgumentException("JSON structure exceeds safety limits")
                }
            } else if (ch == '}' || ch == ']') {
                depth--
                structural++
                if (depth < 0) throw IllegalArgumentException("invalid JSON structure")
            }
        }
        if (inString || depth != 0) throw IllegalArgumentException("invalid JSON structure")
    }
}
