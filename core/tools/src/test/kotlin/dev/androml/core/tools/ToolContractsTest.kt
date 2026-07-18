package dev.androml.core.tools

import java.net.InetAddress
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolContractsTest {
    private val descriptor = ToolDescriptor(
        id = ToolId.parse("http.fetch"),
        displayName = "Fetch",
        description = "Fetch an explicitly allowlisted HTTPS resource.",
        sideEffect = ToolSideEffect.External,
        requiredScopes = setOf(ToolScope.parse("network.read")),
        input = ToolInputSchema(
            properties = mapOf(
                "url" to ToolProperty(ToolValueType.String, maxLength = 2048),
                "mode" to ToolProperty(ToolValueType.String, enumValues = setOf("text", "json")),
            ),
            required = setOf("url"),
        ),
    )

    @Test
    fun schemaRejectsUnknownMissingAndWrongValues() {
        val errors = ToolInvocation(
            descriptor = descriptor,
            arguments = buildJsonObject {
                put("mode", "binary")
                put("extra", true)
            },
            requestedAtEpochMillis = 1L,
        ).validate()

        assertEquals(3, errors.size)
        assertTrue(errors.any { it.message.contains("url") })
        assertTrue(errors.any { it.message.contains("mode") })
        assertTrue(errors.any { it.message.contains("extra") })
    }

    @Test
    fun approvalExpiresAndDangerousActionsAlwaysRequireFreshConfirmation() {
        val invocation = ToolInvocation(
            descriptor = descriptor,
            arguments = buildJsonObject { put("url", "https://example.test") },
            requestedAtEpochMillis = 1000L,
        )
        val policy = ToolApprovalPolicy(approvalLifetimeMillis = 1000L)
        val approval = policy.issue(invocation, nowEpochMillis = 1000L, rememberScope = true)

        assertTrue(approval.isValidFor(invocation, 1999L))
        assertFalse(approval.isValidFor(invocation, 2000L))
        assertFalse(
            approval.isValidFor(
                invocation.copy(arguments = buildJsonObject { put("url", "https://changed.test") }),
                1500L,
            ),
        )
        assertFalse(approval.requiresFreshConfirmation)
    }

    @Test
    fun auditContainsHashesInsteadOfRawArguments() {
        val invocation = ToolInvocation(
            descriptor = descriptor,
            arguments = buildJsonObject { put("url", "https://example.test/private-value") },
            requestedAtEpochMillis = 1L,
        )
        val audit = ToolAuditEvent.fromInvocation(invocation, success = true, result = "body")

        assertEquals(64, audit.argumentHash.length)
        assertEquals(64, audit.resultHash?.length)
        assertFalse(audit.argumentHash.contains("private-value"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun networkPolicyRejectsLoopbackAndNonHttps() {
        NetworkTargetPolicy.validateUri("http://127.0.0.1:8080/test")
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyAllowlistDeniesExternalTargets() {
        NetworkTargetPolicy.validateUri("https://example.test")
    }

    @Test(expected = IllegalArgumentException::class)
    fun networkPolicyRejectsPrivateResolvedAddress() {
        NetworkTargetPolicy.validateResolvedAddresses(
            listOf(InetAddress.getByName("192.168.1.10")),
        )
    }
}
