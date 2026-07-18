package dev.androml.core.network

import dev.androml.core.model.HuggingFaceCommit
import dev.androml.core.model.HuggingFaceFileDescriptor
import dev.androml.core.model.HuggingFaceModelReference
import dev.androml.core.model.HuggingFaceRepositoryMetadata
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

enum class HuggingFaceMetadataError {
    InvalidJson,
    MissingField,
    InvalidField,
    RepositoryMismatch,
    RevisionMismatch,
}

class HuggingFaceMetadataException(
    val code: HuggingFaceMetadataError,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/** Parses the untrusted JSON returned by the Hub model-info endpoint. */
class HuggingFaceMetadataParser {
    private val json = Json

    fun parse(
        reference: HuggingFaceModelReference,
        body: String,
    ): HuggingFaceRepositoryMetadata {
        val root = parseRoot(body)
        val returnedId = root.requiredString("id")
        if (returnedId != reference.modelId.value) {
            throw HuggingFaceMetadataException(
                code = HuggingFaceMetadataError.RepositoryMismatch,
                message = "Hugging Face response repository does not match the request",
            )
        }

        val returnedRevision = root.requiredString("sha")
        val returnedCommit = try {
            HuggingFaceCommit.parse(returnedRevision)
        } catch (error: IllegalArgumentException) {
            throw HuggingFaceMetadataException(
                code = HuggingFaceMetadataError.InvalidField,
                message = "Hugging Face response contains an invalid commit",
                cause = error,
            )
        }
        if (returnedCommit != reference.revision) {
            throw HuggingFaceMetadataException(
                code = HuggingFaceMetadataError.RevisionMismatch,
                message = "Hugging Face response commit does not match the request",
            )
        }

        val files = parseFiles(root.requiredElement("siblings"))
        val cardData = root.optionalObject("cardData")
        val license = cardData?.optionalString("license")

        return HuggingFaceRepositoryMetadata(
            reference = reference,
            files = files,
            isPrivate = root.requiredBoolean("private"),
            isGated = root.optionalGated("gated"),
            license = license,
        )
    }

    private fun parseRoot(body: String): JsonObject = try {
        json.parseToJsonElement(body).jsonObject
    } catch (error: SerializationException) {
        throw HuggingFaceMetadataException(
            code = HuggingFaceMetadataError.InvalidJson,
            message = "Hugging Face response is not valid JSON",
            cause = error,
        )
    } catch (error: IllegalArgumentException) {
        throw HuggingFaceMetadataException(
            code = HuggingFaceMetadataError.InvalidJson,
            message = "Hugging Face response must be a JSON object",
            cause = error,
        )
    }

    private fun parseFiles(element: JsonElement): List<HuggingFaceFileDescriptor> {
        val siblings = try {
            element.jsonArray
        } catch (error: IllegalArgumentException) {
            throw HuggingFaceMetadataException(
                code = HuggingFaceMetadataError.InvalidField,
                message = "Hugging Face response siblings must be an array",
                cause = error,
            )
        }

        val files = siblings.mapIndexed { index, item ->
            val sibling = try {
                item.jsonObject
            } catch (error: IllegalArgumentException) {
                throw HuggingFaceMetadataException(
                    code = HuggingFaceMetadataError.InvalidField,
                    message = "Hugging Face file entry $index must be an object",
                    cause = error,
                )
            }
            parseFile(index, sibling)
        }
        if (files.map { it.path }.toSet().size != files.size) {
            throw HuggingFaceMetadataException(
                code = HuggingFaceMetadataError.InvalidField,
                message = "Hugging Face response contains duplicate file paths",
            )
        }
        return files
    }

    private fun parseFile(index: Int, sibling: JsonObject): HuggingFaceFileDescriptor {
        val path = sibling.requiredString("rfilename")
        val lfs = sibling.optionalObject("lfs")
        val directSize = sibling.optionalLong("size")
        val lfsSize = lfs?.optionalLong("size")
        if (directSize != null && lfsSize != null && directSize != lfsSize) {
            throw HuggingFaceMetadataException(
                code = HuggingFaceMetadataError.InvalidField,
                message = "Hugging Face file entry $index has conflicting sizes",
            )
        }
        val size = directSize ?: lfsSize ?: throw HuggingFaceMetadataException(
            code = HuggingFaceMetadataError.MissingField,
            message = "Hugging Face file entry $index has no bounded size",
        )
        val sha256 = lfs?.optionalString("sha256")

        return try {
            HuggingFaceFileDescriptor(path = path, sizeBytes = size, sha256 = sha256)
        } catch (error: IllegalArgumentException) {
            throw HuggingFaceMetadataException(
                code = HuggingFaceMetadataError.InvalidField,
                message = "Hugging Face file entry $index is unsafe",
                cause = error,
            )
        }
    }

    private fun JsonObject.requiredElement(name: String): JsonElement =
        this[name] ?: throw HuggingFaceMetadataException(
            code = HuggingFaceMetadataError.MissingField,
            message = "Hugging Face response is missing $name",
        )

    private fun JsonObject.requiredPrimitive(name: String): JsonPrimitive {
        val element = requiredElement(name)
        return try {
            element.jsonPrimitive
        } catch (error: IllegalArgumentException) {
            throw HuggingFaceMetadataException(
                code = HuggingFaceMetadataError.InvalidField,
                message = "Hugging Face response field $name must be a primitive",
                cause = error,
            )
        }
    }

    private fun JsonObject.requiredString(name: String): String =
        requiredPrimitive(name).contentOrNull?.takeIf { it.isNotEmpty() }
            ?: throw HuggingFaceMetadataException(
                code = HuggingFaceMetadataError.InvalidField,
                message = "Hugging Face response field $name must be a non-empty string",
            )

    private fun JsonObject.requiredBoolean(name: String): Boolean =
        requiredPrimitive(name).booleanOrNull
            ?: throw HuggingFaceMetadataException(
                code = HuggingFaceMetadataError.InvalidField,
                message = "Hugging Face response field $name must be a boolean",
            )

    private fun JsonObject.optionalString(name: String): String? {
        val element = this[name] ?: return null
        val primitive = try {
            element.jsonPrimitive
        } catch (error: IllegalArgumentException) {
            throw HuggingFaceMetadataException(
                code = HuggingFaceMetadataError.InvalidField,
                message = "Hugging Face response field $name must be a string",
                cause = error,
            )
        }
        return primitive.contentOrNull?.takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.optionalLong(name: String): Long? {
        val element = this[name] ?: return null
        val primitive = try {
            element.jsonPrimitive
        } catch (error: IllegalArgumentException) {
            throw HuggingFaceMetadataException(
                code = HuggingFaceMetadataError.InvalidField,
                message = "Hugging Face response field $name must be a number",
                cause = error,
            )
        }
        return primitive.longOrNull ?: throw HuggingFaceMetadataException(
            code = HuggingFaceMetadataError.InvalidField,
            message = "Hugging Face response field $name must be a bounded integer",
        )
    }

    private fun JsonObject.optionalObject(name: String): JsonObject? {
        val element = this[name] ?: return null
        return try {
            element.jsonObject
        } catch (error: IllegalArgumentException) {
            throw HuggingFaceMetadataException(
                code = HuggingFaceMetadataError.InvalidField,
                message = "Hugging Face response field $name must be an object",
                cause = error,
            )
        }
    }

    private fun JsonObject.optionalGated(name: String): Boolean {
        val element = this[name] ?: return false
        val primitive = try {
            element.jsonPrimitive
        } catch (error: IllegalArgumentException) {
            throw HuggingFaceMetadataException(
                code = HuggingFaceMetadataError.InvalidField,
                message = "Hugging Face response field $name must be a boolean or gate mode",
                cause = error,
            )
        }
        primitive.booleanOrNull?.let { return it }
        return when (primitive.contentOrNull?.lowercase()) {
            "auto", "manual" -> true
            "false", "none" -> false
            else -> throw HuggingFaceMetadataException(
                code = HuggingFaceMetadataError.InvalidField,
                message = "Hugging Face response field $name has an unknown gate mode",
            )
        }
    }
}
