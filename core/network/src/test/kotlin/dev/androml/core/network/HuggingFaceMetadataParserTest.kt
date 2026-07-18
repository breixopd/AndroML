package dev.androml.core.network

import dev.androml.core.model.HuggingFaceModelReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class HuggingFaceMetadataParserTest {
    private val reference = HuggingFaceModelReference.parse(
        modelId = "org/tiny-model",
        revision = "0123456789abcdef0123456789abcdef01234567",
    )

    @Test
    fun parsesFilesLicenseAndGatedStatusFromHubResponse() {
        val metadata = HuggingFaceMetadataParser().parse(reference, validResponse())

        assertEquals(reference, metadata.reference)
        assertEquals(2, metadata.files.size)
        assertEquals("config.json", metadata.files[0].path)
        assertEquals(128L, metadata.files[0].sizeBytes)
        assertEquals("model.safetensors", metadata.files[1].path)
        assertEquals(4096L, metadata.files[1].sizeBytes)
        assertEquals(
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
            metadata.files[1].sha256,
        )
        assertTrue(metadata.isGated)
        assertEquals("apache-2.0", metadata.license)
    }

    @Test
    fun acceptsLfsSizeWhenTheSiblingSizeIsMissing() {
        val metadata = HuggingFaceMetadataParser().parse(
            reference,
            validResponse().replace("\"size\":4096,", ""),
        )

        assertEquals(4096L, metadata.files[1].sizeBytes)
    }

    @Test
    fun rejectsAResponseForADifferentCommit() {
        val error = assertThrows(HuggingFaceMetadataException::class.java) {
            HuggingFaceMetadataParser().parse(
                reference,
                validResponse().replace(reference.revision.value, "fedcba9876543210fedcba9876543210fedcba98"),
            )
        }

        assertEquals(HuggingFaceMetadataError.RevisionMismatch, error.code)
    }

    @Test
    fun rejectsMissingOrConflictingFileSizes() {
        assertThrows(HuggingFaceMetadataException::class.java) {
            HuggingFaceMetadataParser().parse(
                reference,
                validResponse().replace("\"size\": 128", "\"size\": null"),
            )
        }
        assertThrows(HuggingFaceMetadataException::class.java) {
            HuggingFaceMetadataParser().parse(
                reference,
                validResponse().replaceFirst("\"size\": 4096", "\"size\": 4097"),
            )
        }
    }

    @Test
    fun rejectsMalformedJsonAndMissingRequiredFields() {
        assertThrows(HuggingFaceMetadataException::class.java) {
            HuggingFaceMetadataParser().parse(reference, "not-json")
        }
        assertThrows(HuggingFaceMetadataException::class.java) {
            HuggingFaceMetadataParser().parse(reference, "{\"id\":\"org/tiny-model\"}")
        }
    }

    private fun validResponse(): String =
        """
        {
          "id": "org/tiny-model",
          "sha": "0123456789abcdef0123456789abcdef01234567",
          "private": false,
          "gated": "manual",
          "cardData": {"license": "apache-2.0"},
          "siblings": [
            {"rfilename": "config.json", "size": 128},
            {
              "rfilename": "model.safetensors",
              "size": 4096,
              "lfs": {
                "size": 4096,
                "sha256": "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
              }
            }
          ]
        }
        """.trimIndent()
}
