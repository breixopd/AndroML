package dev.androml.core.database

import dev.androml.core.api.ApiKeyCodec
import dev.androml.core.api.ApiScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeyStorageMapperTest {
    @Test
    fun storesOnlyHashAndRoundTripsAllAuthorizationMetadata() {
        val generated = ApiKeyCodec.generate(
            displayName = "phone client",
            scopes = setOf(ApiScope.Inference, ApiScope.ModelsRead),
            expiresAtEpochMillis = 9_000L,
            nowEpochMillis = 1_000L,
        )

        val entity = ApiKeyStorageMapper.toEntity(generated.record)
        assertFalse(entity.tokenHash == generated.plaintextToken)
        assertEquals("inference,modelsread", entity.scopes)

        val restored = ApiKeyStorageMapper.toDomain(entity)
        assertEquals(generated.record, restored)
        assertTrue(restored.isUsableAt(2_000L))
    }
}
