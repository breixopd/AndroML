package dev.androml.runtime.service

import dev.androml.runtime.api.RuntimeId
import dev.androml.runtime.api.RuntimePackCatalog
import dev.androml.runtime.llamacpp.LlamaCppRuntimeAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeRegistryTest {
    @Test
    fun bundledRuntimePacksCreateAdapters() {
        val registry = RuntimeRegistry("/does/not/exist/model.litertlm")
        assertEquals("litert", registry.adapterFor(RuntimeId.parse("litert")).descriptor.id.value)
        assertEquals("litertlm", registry.adapterFor(RuntimeId.parse("litertlm")).descriptor.id.value)
        assertEquals("onnxruntime", registry.adapterFor(RuntimeId.parse("onnxruntime")).descriptor.id.value)
        assertEquals("executorch", registry.adapterFor(RuntimeId.parse("executorch")).descriptor.id.value)
        LlamaCppRuntimeAvailability.advertise()
        if (RuntimePackCatalog.find(RuntimeId.parse("llamacpp"))?.usable == true) {
            assertEquals("llamacpp", registry.adapterFor(RuntimeId.parse("llamacpp")).descriptor.id.value)
        } else {
            assertThrows(IllegalStateException::class.java) { registry.adapterFor(RuntimeId.parse("llamacpp")) }
        }
    }
}
