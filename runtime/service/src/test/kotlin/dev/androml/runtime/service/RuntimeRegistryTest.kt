package dev.androml.runtime.service

import dev.androml.runtime.api.RuntimeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeRegistryTest {
    @Test
    fun bundledRuntimePacksCreateAdapters() {
        val registry = RuntimeRegistry("/does/not/exist/model.litertlm")
        assertEquals("litertlm", registry.adapterFor(RuntimeId.parse("litertlm")).descriptor.id.value)
        assertEquals("onnxruntime", registry.adapterFor(RuntimeId.parse("onnxruntime")).descriptor.id.value)
        assertThrows(IllegalStateException::class.java) { registry.adapterFor(RuntimeId.parse("llamacpp")) }
    }
}
