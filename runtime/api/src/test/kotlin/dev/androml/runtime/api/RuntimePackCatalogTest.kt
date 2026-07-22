package dev.androml.runtime.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePackCatalogTest {
    @Test
    fun productionCatalogueOnlyMarksShippedPacksAsUsable() {
        assertEquals(listOf("litert", "litertlm", "onnxruntime"), RuntimePackCatalog.bundled.map { it.descriptor.id.value })
        assertTrue(RuntimePackCatalog.find(RuntimeId.parse("litert"))!!.usable)
        assertTrue(RuntimePackCatalog.find(RuntimeId.parse("litertlm"))!!.usable)
        assertTrue(RuntimePackCatalog.find(RuntimeId.parse("onnxruntime"))!!.usable)
    }

    @Test
    fun allPlannedEnginesHaveStableIdsAndTruthfulUnavailableState() {
        assertEquals(
            setOf("litert", "litertlm", "llamacpp", "onnxruntime", "executorch", "mlc"),
            RuntimePackCatalog.production.map { it.descriptor.id.value }.toSet(),
        )
        RuntimePackCatalog.production
            .filter { it.state == RuntimePackState.NotBundled }
            .forEach { pack ->
                assertFalse(pack.descriptor.isAvailable)
                assertTrue(pack.note.contains("future", ignoreCase = true))
            }
    }
}
