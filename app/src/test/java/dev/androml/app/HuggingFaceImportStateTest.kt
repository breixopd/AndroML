package dev.androml.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HuggingFaceImportStateTest {
    @Test
    fun validModelIdAndCommitBecomeAnImportReference() {
        val state = HuggingFaceImportState(
            modelId = "org/tiny-model",
            revision = "0123456789abcdef0123456789abcdef01234567",
        ).validate()

        assertTrue(state.isValid)
        assertNotNull(state.reference)
        assertNull(state.errorMessage)
    }

    @Test
    fun mutableBranchReferencesAreRejectedBeforeDownload() {
        val state = HuggingFaceImportState(
            modelId = "org/tiny-model",
            revision = "main",
        ).validate()

        assertEquals(false, state.isValid)
        assertNull(state.reference)
        assertEquals("Use a full 40-character commit SHA.", state.errorMessage)
    }

    @Test
    fun blankInputsProduceAnActionableError() {
        val state = HuggingFaceImportState().validate()

        assertEquals(false, state.isValid)
        assertEquals("Enter a model ID and a full commit SHA.", state.errorMessage)
    }
}
