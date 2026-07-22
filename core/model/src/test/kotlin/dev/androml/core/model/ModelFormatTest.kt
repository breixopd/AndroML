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
        assertTrue(ModelFormatClassifier.supports("vision.tflite", ModelWorkload.ImageClassification))
        assertTrue(ModelFormatClassifier.supports("audio.onnx", ModelWorkload.AudioClassification))
        assertEquals(ModelFormat.Onnx, ModelFormatClassifier.forPath("embed.onnx"))
        assertEquals(ModelFormat.Gguf, ModelFormatClassifier.forPath("llama.GGUF"))
        assertEquals(ModelFormat.ExecuTorch, ModelFormatClassifier.forPath("embed.pte"))
        assertTrue(ModelFormatClassifier.supports("embed.pte", ModelWorkload.TextEmbedding))
        assertTrue(ModelFormatClassifier.supports("embed.ort", ModelWorkload.TextEmbedding))
        assertNull(ModelFormatClassifier.forPath("weights.safetensors"))
    }
}
