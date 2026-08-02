package dev.androml.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HuggingFaceSearchParserTest {
    @Test
    fun parsesBoundedSearchHits() {
        val hits = HuggingFaceSearchParser().parse(
            """
            [{"id":"org/tiny-model","sha":"0123456789abcdef0123456789abcdef01234567","pipeline_tag":"text-generation","downloads":42,"likes":3,"library_name":"llama.cpp","tags":["gguf","text-generation"],"siblings":[{"rfilename":"tiny.Q4_K_M.gguf"}]}]
            """.trimIndent(),
        )
        assertEquals(1, hits.size)
        assertEquals("org/tiny-model", hits.single().modelId)
        assertEquals(42L, hits.single().downloads)
        assertEquals("llama.cpp", hits.single().libraryName)
        assertEquals(listOf("gguf", "text-generation"), hits.single().tags)
        assertEquals(listOf("tiny.Q4_K_M.gguf"), hits.single().filePaths)
    }

    @Test
    fun rejectsSearchHitWithUnsafeModelId() {
        assertThrows(HuggingFaceMetadataException::class.java) {
            HuggingFaceSearchParser().parse("[{\"id\":\"https://evil.example/model\"}]")
        }
    }

    @Test
    fun acceptsNullableHubFieldsOnRecommendations() {
        val hits = HuggingFaceSearchParser().parse(
            """
            [{"id":"org/tiny-model","sha":"0123456789abcdef0123456789abcdef01234567","library_name":null,"tags":null,"siblings":null}]
            """.trimIndent(),
        )

        assertEquals(null, hits.single().libraryName)
        assertEquals(emptyList<String>(), hits.single().tags)
        assertEquals(emptyList<String>(), hits.single().filePaths)
    }
}
