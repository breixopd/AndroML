package dev.androml.app

import androidx.work.Data
import dev.androml.core.model.HuggingFaceFileDescriptor
import dev.androml.core.model.HuggingFaceModelReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test

class HuggingFaceDownloadWorkTest {
    @Test
    fun requestRoundTripContainsOnlyPinnedArtifactIdentity() {
        val reference = HuggingFaceModelReference.parse(
            "org/tiny-model",
            "0123456789abcdef0123456789abcdef01234567",
        )
        val descriptor = HuggingFaceFileDescriptor(
            path = "model.gguf",
            sizeBytes = 42L,
            sha256 = "a".repeat(64),
        )

        HuggingFaceDownloadWork.createRequest(reference, descriptor)
        val input = Data.Builder()
            .putString(HuggingFaceDownloadWork.MODEL_ID_KEY, reference.modelId.value)
            .putString(HuggingFaceDownloadWork.REVISION_KEY, reference.revision.value)
            .putString(HuggingFaceDownloadWork.PATH_KEY, descriptor.path)
            .putLong(HuggingFaceDownloadWork.SIZE_BYTES_KEY, descriptor.sizeBytes)
            .putString(HuggingFaceDownloadWork.SHA256_KEY, descriptor.sha256)
            .build()
        val parsed = HuggingFaceDownloadWork.parseInput(input)

        assertNotNull(parsed)
        assertEquals(reference, parsed?.reference)
        assertEquals(descriptor, parsed?.descriptor)
        assertFalse(input.keyValueMap.containsKey("access_token"))
    }

    @Test
    fun malformedWorkInputIsRejectedBeforeNetworkAccess() {
        val malformed = Data.Builder()
            .putString(HuggingFaceDownloadWork.MODEL_ID_KEY, "org/tiny-model")
            .putString(HuggingFaceDownloadWork.REVISION_KEY, "main")
            .putString(HuggingFaceDownloadWork.PATH_KEY, "model.gguf")
            .putLong(HuggingFaceDownloadWork.SIZE_BYTES_KEY, 42L)
            .putString(HuggingFaceDownloadWork.SHA256_KEY, "a".repeat(64))
            .build()

        assertNull(HuggingFaceDownloadWork.parseInput(malformed))
    }
}
