package dev.androml.runtime.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeContractsTest {
    @Test
    fun runtimeIdsAreStableAndSafe() {
        assertEquals("llama.cpp", RuntimeId.parse("llama.cpp").value)
        assertThrows(IllegalArgumentException::class.java) { RuntimeId.parse("../engine") }
        assertThrows(IllegalArgumentException::class.java) { RuntimeId.parse("runtime name") }
    }
}
