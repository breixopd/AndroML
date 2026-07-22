package dev.androml.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFormatTest {
    @Test
    fun classifiesSupportedArtifactFormatsWithoutExecutingThem() {
        assertEquals(ModelFormat.LiteRtLm, ModelFormatClassifier.forPath("models/chat.litertlm"))
        assertTrue(ModelFormatClassifier.supports("embed.tflite", ModelWorkload.TextEmbedding))
        assertEquals(ModelFormat.Onnx, ModelFormatClassifier.forPath("embed.onnx"))
        assertEquals(ModelFormat.Gguf, ModelFormatClassifier.forPath("llama.GGUF"))
        assertTrue(ModelFormatClassifier.supports("embed.ort", ModelWorkload.TextEmbedding))
        assertNull(ModelFormatClassifier.forPath("weights.safetensors"))
    }
}
