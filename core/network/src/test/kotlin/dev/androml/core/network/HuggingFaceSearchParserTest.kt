package dev.androml.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HuggingFaceSearchParserTest {
    @Test
    fun parsesBoundedSearchHits() {
        val hits = HuggingFaceSearchParser().parse(
            """
            [{"id":"org/tiny-model","sha":"0123456789abcdef0123456789abcdef01234567","pipeline_tag":"text-generation","downloads":42,"likes":3}]
            """.trimIndent(),
        )
        assertEquals(1, hits.size)
        assertEquals("org/tiny-model", hits.single().modelId)
        assertEquals(42L, hits.single().downloads)
    }

    @Test
    fun rejectsSearchHitWithUnsafeModelId() {
        assertThrows(HuggingFaceMetadataException::class.java) {
            HuggingFaceSearchParser().parse("[{\"id\":\"https://evil.example/model\"}]")
        }
    }
}
