package dev.androml.core.tools

import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@JvmInline
value class ToolId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): ToolId {
            require(raw.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) {
                "tool ID contains unsafe characters"
            }
            return ToolId(raw)
        }
    }
}

@JvmInline
value class ToolScope private constructor(val value: String) {
    companion object {
        fun parse(raw: String): ToolScope {
            require(raw.matches(Regex("[a-z0-9][a-z0-9._:-]{0,95}"))) {
                "tool scope contains unsafe characters"
            }
            return ToolScope(raw)
        }
    }
}

enum class ToolSideEffect {
    Read,
    Write,
    External,
    Dangerous,
}

enum class ToolValueType {
    String,
    Integer,
    Number,
    Boolean,
    Object,
    Array,
    Null,
}

data class ToolProperty(
    val type: ToolValueType,
    val description: String? = null,
    val enumValues: Set<String> = emptySet(),
    val maxLength: Int? = null,
    val maxItems: Int? = null,
) {
    init {
        require(description == null || description.length <= 1024) { "tool description is too long" }
        require(enumValues.size <= 128) { "tool enum is too large" }
        require(enumValues.all { it.length <= 256 }) { "tool enum value is too long" }
        require(maxLength == null || maxLength >= 0) { "tool max length must be non-negative" }
        require(maxItems == null || maxItems >= 0) { "tool max items must be non-negative" }
    }
}

data class ToolInputSchema(
    val properties: Map<String, ToolProperty>,
    val required: Set<String> = emptySet(),
    val allowAdditionalProperties: Boolean = false,
) {
    init {
        require(properties.size <= 128) { "tool schema has too many properties" }
        require(properties.keys.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) }) {
            "tool property name is unsafe"
        }
        require(required.all { it in properties }) { "tool schema requires an unknown property" }
    }

    fun validate(arguments: JsonObject): List<ToolValidationError> {
        val errors = mutableListOf<ToolValidationError>()
        required.filterNot(arguments::containsKey).forEach { name ->
            errors += ToolValidationError("missing required property '$name'")
        }
        arguments.keys.filterNot(properties::containsKey).forEach { name ->
            if (!allowAdditionalProperties) errors += ToolValidationError("unknown property '$name'")
        }
        properties.forEach { (name, property) ->
            val element = arguments[name] ?: return@forEach
            validateProperty(name, element, property, errors)
        }
        return errors
    }

    private fun validateProperty(
        name: String,
        element: JsonElement,
        property: ToolProperty,
        errors: MutableList<ToolValidationError>,
    ) {
        val matchesType = when (property.type) {
            ToolValueType.String -> element is JsonPrimitive && element.isString
            ToolValueType.Integer -> element.jsonPrimitive.intOrNull != null
            ToolValueType.Number -> element.jsonPrimitive.doubleOrNull != null
            ToolValueType.Boolean -> element.jsonPrimitive.booleanOrNull != null
            ToolValueType.Object -> element is JsonObject
            ToolValueType.Array -> element is JsonArray
            ToolValueType.Null -> element.toString() == "null"
        }
        if (!matchesType) {
            errors += ToolValidationError("property '$name' has the wrong type")
            return
        }
        if (property.type == ToolValueType.String) {
            val value = element.jsonPrimitive.content
            if (property.maxLength != null && value.length > property.maxLength) {
                errors += ToolValidationError("property '$name' exceeds its length limit")
            }
            if (property.enumValues.isNotEmpty() && value !in property.enumValues) {
                errors += ToolValidationError("property '$name' is not an allowed value")
            }
        }
        if (property.type == ToolValueType.Array &&
            property.maxItems != null &&
            element.jsonArray.size > property.maxItems
        ) {
            errors += ToolValidationError("property '$name' exceeds its item limit")
        }
    }
}

data class ToolValidationError(val message: String)

data class ToolDescriptor(
    val id: ToolId,
    val displayName: String,
    val description: String,
    val sideEffect: ToolSideEffect,
    val requiredScopes: Set<ToolScope>,
    val input: ToolInputSchema,
    val timeoutSeconds: Int = 30,
    val maxResultCharacters: Int = 64 * 1024,
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= 128) { "tool display name is invalid" }
        require(description.isNotBlank() && description.length <= 4096) { "tool description is invalid" }
        require(requiredScopes.size <= 32) { "too many tool scopes" }
        require(timeoutSeconds in 1..600) { "tool timeout is out of bounds" }
        require(maxResultCharacters in 1..4 * 1024 * 1024) { "tool result limit is out of bounds" }
    }
}

data class ToolInvocation(
    val descriptor: ToolDescriptor,
    val arguments: JsonObject,
    val requestedAtEpochMillis: Long,
) {
    fun validate(): List<ToolValidationError> = descriptor.input.validate(arguments)
}

data class ToolApproval(
    val approvalId: String,
    val toolId: ToolId,
    val argumentHash: String,
    val scopes: Set<ToolScope>,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val requiresFreshConfirmation: Boolean,
) {
    init {
        require(approvalId.matches(Regex("[a-f0-9-]{16,64}"))) { "approval ID is invalid" }
        require(argumentHash.matches(Regex("[a-f0-9]{64}"))) { "approval argument hash must be SHA-256" }
        require(expiresAtEpochMillis > issuedAtEpochMillis) { "approval expiry must be in the future" }
    }

    fun isValidFor(
        invocation: ToolInvocation,
        nowEpochMillis: Long,
    ): Boolean = invocation.descriptor.id == toolId &&
        sha256(invocation.arguments.toString()) == argumentHash &&
        nowEpochMillis in issuedAtEpochMillis until expiresAtEpochMillis &&
        invocation.descriptor.requiredScopes.all(scopes::contains)
}

class ToolApprovalPolicy(
    private val approvalLifetimeMillis: Long = 5 * 60 * 1000L,
) {
    init {
        require(approvalLifetimeMillis in 1_000L..24 * 60 * 60 * 1000L) {
            "approval lifetime is out of bounds"
        }
    }

    fun requiresApproval(descriptor: ToolDescriptor): Boolean =
        descriptor.sideEffect != ToolSideEffect.Read

    fun issue(
        invocation: ToolInvocation,
        nowEpochMillis: Long = Instant.now().toEpochMilli(),
        rememberScope: Boolean = false,
    ): ToolApproval {
        require(invocation.validate().isEmpty()) { "cannot approve invalid tool arguments" }
        return ToolApproval(
            approvalId = sha256("${invocation.descriptor.id.value}|$nowEpochMillis|${invocation.arguments}")
                .take(32),
            toolId = invocation.descriptor.id,
            argumentHash = sha256(invocation.arguments.toString()),
            scopes = invocation.descriptor.requiredScopes,
            issuedAtEpochMillis = nowEpochMillis,
            expiresAtEpochMillis = nowEpochMillis + approvalLifetimeMillis,
            requiresFreshConfirmation = !rememberScope || invocation.descriptor.sideEffect == ToolSideEffect.Dangerous,
        )
    }
}

data class ToolAuditEvent(
    val eventType: String,
    val toolId: ToolId,
    val sideEffect: ToolSideEffect,
    val argumentHash: String,
    val resultHash: String? = null,
    val success: Boolean,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(eventType.matches(Regex("[a-z0-9._-]{1,64}"))) { "audit event type is invalid" }
        require(argumentHash.matches(Regex("[a-f0-9]{64}"))) { "argument hash must be SHA-256" }
        require(resultHash == null || resultHash.matches(Regex("[a-f0-9]{64}"))) {
            "result hash must be SHA-256"
        }
    }

    companion object {
        fun fromInvocation(
            invocation: ToolInvocation,
            success: Boolean,
            result: String? = null,
            occurredAtEpochMillis: Long = Instant.now().toEpochMilli(),
        ) = ToolAuditEvent(
            eventType = "tool.invocation",
            toolId = invocation.descriptor.id,
            sideEffect = invocation.descriptor.sideEffect,
            argumentHash = sha256(invocation.arguments.toString()),
            resultHash = result?.let(::sha256),
            success = success,
            occurredAtEpochMillis = occurredAtEpochMillis,
        )
    }
}

object NetworkTargetPolicy {
    fun validateUri(
        raw: String,
        allowedHosts: Set<String> = emptySet(),
        allowLoopback: Boolean = false,
    ): URI {
        val uri = runCatching { URI(raw) }.getOrElse {
            throw NetworkPolicyException("URL is invalid")
        }
        require(uri.scheme.equals("https", ignoreCase = true)) { "only HTTPS tool targets are allowed" }
        require(uri.userInfo == null && uri.fragment == null) { "URL userinfo and fragments are not allowed" }
        val host = uri.host?.lowercase(Locale.ROOT)
        require(!host.isNullOrBlank()) { "URL host is required" }
        require(host in allowedHosts) { "URL host is not allowlisted" }
        if (!allowLoopback) {
            require(!isLoopbackHost(host)) { "loopback targets are blocked" }
        }
        return uri
    }

    fun validateResolvedAddresses(
        addresses: List<InetAddress>,
        allowPrivateLocal: Boolean = false,
    ) {
        require(addresses.isNotEmpty()) { "host did not resolve" }
        if (!allowPrivateLocal) {
            require(addresses.none { address ->
                address.isAnyLocalAddress ||
                    address.isLoopbackAddress ||
                    address.isLinkLocalAddress ||
                    address.isSiteLocalAddress ||
                    address.isMulticastAddress
            }) { "resolved target is private, local, or multicast" }
        }
    }

    private fun isLoopbackHost(host: String): Boolean =
        host == "localhost" || host == "[::1]" || host == "::1" ||
            host == "127.0.0.1" || host.startsWith("127.")
}

class NetworkPolicyException(message: String) : IllegalArgumentException(message)

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
