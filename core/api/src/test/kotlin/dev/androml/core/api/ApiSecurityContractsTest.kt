package dev.androml.core.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiSecurityContractsTest {
    @Test
    fun generatedApiKeyAuthenticatesOnlyForItsScopeAndExpiry() {
        val generated = ApiKeyCodec.generate(
            displayName = "phone test",
            scopes = setOf(ApiScope.Inference),
            expiresAtEpochMillis = 2000L,
            nowEpochMillis = 1000L,
        )
        val authenticator = ApiKeyAuthenticator()

        assertNotNull(
            authenticator.authenticate(
                generated.plaintextToken,
                listOf(generated.record),
                requiredScope = ApiScope.Inference,
                nowEpochMillis = 1500L,
            ),
        )
        assertEquals(
            null,
            authenticator.authenticate(
                generated.plaintextToken,
                listOf(generated.record),
                requiredScope = ApiScope.Admin,
                nowEpochMillis = 1500L,
            ),
        )
        assertEquals(
            null,
            authenticator.authenticate(
                generated.plaintextToken,
                listOf(generated.record),
                requiredScope = ApiScope.Inference,
                nowEpochMillis = 2000L,
            ),
        )
        assertEquals(
            null,
            authenticator.authenticate(
                generated.plaintextToken.dropLast(1) + "x",
                listOf(generated.record),
                requiredScope = ApiScope.Inference,
                nowEpochMillis = 1500L,
            ),
        )
    }

    @Test
    fun apiKeyHashUsesArgon2idAndDoesNotAcceptTampering() {
        val generated = ApiKeyCodec.generate("argon", setOf(ApiScope.Inference), nowEpochMillis = 1L)
        assertTrue(generated.record.tokenHash.startsWith("${'$'}argon2id${'$'}"))
        assertNotNull(
            ApiKeyAuthenticator().authenticate(
                generated.plaintextToken,
                listOf(generated.record),
                ApiScope.Inference,
                nowEpochMillis = 2L,
            ),
        )
        assertEquals(
            null,
            ApiKeyAuthenticator().authenticate(
                generated.plaintextToken.dropLast(1) + "x",
                listOf(generated.record),
                ApiScope.Inference,
                nowEpochMillis = 2L,
            ),
        )
    }

    @Test
    fun loopbackAndLanPolicyHaveDifferentCertificateRequirements() {
        val loopback = ApiSecurityPolicy().evaluate(
            bindMode = BindMode.Loopback,
            requestClass = ApiRequestClass.Health,
            peer = null,
            apiAuth = null,
        )
        assertTrue(loopback.allowed)
        assertFalse(loopback.requiresClientCertificate)

        val lan = ApiSecurityPolicy().evaluate(
            bindMode = BindMode.Lan,
            requestClass = ApiRequestClass.Content,
            peer = null,
            apiAuth = null,
        )
        assertFalse(lan.allowed)
        assertTrue(lan.requiresClientCertificate)
        assertTrue(lan.requiresApiKey)
    }

    @Test
    fun oneTimePairingRejectsReplayAndWrongToken() {
        val pairing = OneTimePairing(lifetimeMillis = 1000L)
        val offer = pairing.issue(nowEpochMillis = 1000L)

        val wrong = pairing.consume(offer.pairingId, "wrong", nowEpochMillis = 1100L)
        assertFalse(wrong.accepted)
        assertFalse(pairing.consume(offer.pairingId, offer.oneTimeToken, nowEpochMillis = 1100L).accepted)

        val secondOffer = pairing.issue(nowEpochMillis = 1000L)
        assertTrue(pairing.consume(secondOffer.pairingId, secondOffer.oneTimeToken, nowEpochMillis = 1999L).accepted)
        assertFalse(pairing.consume(secondOffer.pairingId, secondOffer.oneTimeToken, nowEpochMillis = 1999L).accepted)
    }

    @Test
    fun certificateFingerprintNormalizesColonSeparatedInput() {
        val raw = "AA:" + "bb".repeat(31)
        assertEquals("aa" + "bb".repeat(31), CertificateFingerprint.parse(raw).value)
    }
}
