package dev.androml.core.database

import dev.androml.core.model.HuggingFaceFileDescriptor
import dev.androml.core.model.HuggingFaceModelReference
import dev.androml.core.model.HuggingFaceRepositoryMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogMapperTest {
    @Test
    fun metadataMapsToRevisionScopedRowsWithoutDroppingIntegrityData() {
        val reference = HuggingFaceModelReference.parse(
            modelId = "org/tiny-model",
            revision = "0123456789abcdef0123456789abcdef01234567",
        )
        val metadata = HuggingFaceRepositoryMetadata(
            reference = reference,
            files = listOf(
                HuggingFaceFileDescriptor(
                    path = "model.gguf",
                    sizeBytes = 12_345L,
                    sha256 = "a".repeat(64),
                ),
                HuggingFaceFileDescriptor(
                    path = "README.md",
                    sizeBytes = 55L,
                ),
            ),
            isPrivate = false,
            isGated = true,
            license = "apache-2.0",
        )

        val snapshot = ModelCatalogMapper.map(metadata, observedAtEpochMillis = 123L)

        assertEquals("org/tiny-model", snapshot.model.modelId)
        assertEquals(reference.revision.value, snapshot.model.revision)
        assertFalse(snapshot.model.isPrivate)
        assertTrue(snapshot.model.isGated)
        assertEquals("apache-2.0", snapshot.model.license)
        assertEquals(123L, snapshot.model.observedAtEpochMillis)
        assertEquals(listOf("README.md", "model.gguf"), snapshot.files.map { it.path })
        assertEquals("a".repeat(64), snapshot.files[1].sha256)
        assertEquals(12_345L, snapshot.files[1].sizeBytes)
        assertEquals(null, snapshot.files[0].artifactSha256)
    }
}
