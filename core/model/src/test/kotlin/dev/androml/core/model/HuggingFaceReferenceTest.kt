package dev.androml.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HuggingFaceReferenceTest {
    @Test
    fun acceptsFullCommitRevisionAndCanonicalModelId() {
        val reference = HuggingFaceModelReference.parse(
            modelId = "TheBloke/TinyLlama-1.1B-Chat-v1.0",
            revision = "0123456789abcdef0123456789abcdef01234567",
        )

        assertEquals("TheBloke/TinyLlama-1.1B-Chat-v1.0", reference.modelId.value)
        assertEquals("0123456789abcdef0123456789abcdef01234567", reference.revision.value)
    }

    @Test
    fun acceptsAHubModelWithoutAnOrganization() {
        val modelId = HuggingFaceModelId.parse("gpt2")

        assertEquals("gpt2", modelId.value)
    }

    @Test
    fun rejectsMutableOrShortRevisions() {
        listOf("main", "latest", "v1.0", "0123456", "0123456789abcdef0123456789abcdef0123456g").forEach {
            assertThrows(IllegalArgumentException::class.java) {
                HuggingFaceCommit.parse(it)
            }
        }
    }

    @Test
    fun rejectsUnsafeModelIds() {
        listOf(
            "",
            "/model",
            "org/",
            "org/model/extra",
            "../model",
            "org/model name",
            "org\\model",
        ).forEach {
            assertThrows(IllegalArgumentException::class.java) {
                HuggingFaceModelId.parse(it)
            }
        }
    }

    @Test
    fun acceptsSafeRelativeFileDescriptor() {
        val descriptor = HuggingFaceFileDescriptor(
            path = "onnx/model.int8.onnx",
            sizeBytes = 12_345L,
            sha256 = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
        )

        assertEquals("onnx/model.int8.onnx", descriptor.path)
        assertEquals(12_345L, descriptor.sizeBytes)
    }

    @Test
    fun rejectsUnsafeOrUnboundedFileMetadata() {
        assertThrows(IllegalArgumentException::class.java) {
            HuggingFaceFileDescriptor(path = "../model.gguf", sizeBytes = 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HuggingFaceFileDescriptor(path = "/model.gguf", sizeBytes = 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HuggingFaceFileDescriptor(path = "model.gguf", sizeBytes = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HuggingFaceFileDescriptor(path = "model.gguf", sizeBytes = Long.MAX_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HuggingFaceFileDescriptor(path = "model.gguf", sizeBytes = 1L, sha256 = "not-a-hash")
        }
    }
}
