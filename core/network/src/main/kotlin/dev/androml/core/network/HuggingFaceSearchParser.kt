package dev.androml.core.network

import dev.androml.core.model.HuggingFaceSearchHit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Parses the untrusted, bounded response from /api/models. */
class HuggingFaceSearchParser {
    fun parse(body: String): List<HuggingFaceSearchHit> {
        val elements = try {
            JsonSafety.validate(body)
            Json.parseToJsonElement(body).jsonArray
        } catch (error: SerializationException) {
            throw HuggingFaceMetadataException(
                HuggingFaceMetadataError.InvalidJson,
                "Hugging Face search response is not valid JSON",
                error,
            )
        } catch (error: IllegalArgumentException) {
            throw HuggingFaceMetadataException(
                HuggingFaceMetadataError.InvalidJson,
                "Hugging Face search response must be an array",
                error,
            )
        } catch (error: StackOverflowError) {
            throw HuggingFaceMetadataException(HuggingFaceMetadataError.InvalidJson, "Hugging Face search JSON is too deeply nested", error)
        }
        if (elements.size > MAX_SEARCH_RESULTS) {
            throw HuggingFaceMetadataException(
                HuggingFaceMetadataError.InvalidField,
                "Hugging Face search response contains too many entries",
            )
        }
        return elements.mapIndexed { index, element ->
            val objectValue = runCatching { element.jsonObject }.getOrElse {
                throw HuggingFaceMetadataException(
                    HuggingFaceMetadataError.InvalidField,
                    "Hugging Face search entry $index must be an object",
                    it,
                )
            }
            parseEntry(index, objectValue)
        }
    }

    private fun parseEntry(index: Int, value: JsonObject): HuggingFaceSearchHit {
        fun string(name: String): String? = value[name]
            ?.takeUnless { it is JsonNull }
            ?.jsonPrimitive
            ?.contentOrNull
        fun count(name: String): Long? = value[name]
            ?.takeUnless { it is JsonNull }
            ?.jsonPrimitive
            ?.longOrNull
        fun strings(name: String, maxItems: Int): List<String> {
            val element = value[name] ?: return emptyList()
            if (element is JsonNull) return emptyList()
            val array = runCatching { element.jsonArray }.getOrElse {
                throw HuggingFaceMetadataException(
                    HuggingFaceMetadataError.InvalidField,
                    "Hugging Face search field $name must be an array",
                    it,
                )
            }
            if (array.size > maxItems) {
                throw HuggingFaceMetadataException(
                    HuggingFaceMetadataError.InvalidField,
                    "Hugging Face search field $name contains too many entries",
                )
            }
            return array.mapIndexed { itemIndex, item ->
                item.jsonPrimitive.contentOrNull?.takeIf { it.length in 1..512 }
                    ?: throw HuggingFaceMetadataException(
                        HuggingFaceMetadataError.InvalidField,
                        "Hugging Face search field $name entry $itemIndex is invalid",
                    )
            }
        }
        val filePaths = value["siblings"]
            ?.takeUnless { it is JsonNull }
            ?.let { siblingsElement ->
                val siblings = runCatching { siblingsElement.jsonArray }.getOrElse {
                    throw HuggingFaceMetadataException(
                        HuggingFaceMetadataError.InvalidField,
                        "Hugging Face search field siblings must be an array",
                        it,
                    )
                }
                if (siblings.size > MAX_SIBLINGS) {
                    throw HuggingFaceMetadataException(
                        HuggingFaceMetadataError.InvalidField,
                        "Hugging Face search field siblings contains too many entries",
                    )
                }
                siblings.mapIndexed { siblingIndex, sibling ->
                    runCatching { sibling.jsonObject["rfilename"]?.jsonPrimitive?.contentOrNull }
                        .getOrElse {
                            throw HuggingFaceMetadataException(
                                HuggingFaceMetadataError.InvalidField,
                                "Hugging Face search sibling $siblingIndex is invalid",
                                it,
                            )
                        }?.takeIf { it.length in 1..512 }
                        ?: throw HuggingFaceMetadataException(
                            HuggingFaceMetadataError.InvalidField,
                            "Hugging Face search sibling $siblingIndex has no valid path",
                        )
                }
            }.orEmpty()
        return try {
            HuggingFaceSearchHit(
                modelId = string("id") ?: throw IllegalArgumentException("id is missing"),
                revision = string("sha"),
                pipelineTag = string("pipeline_tag"),
                downloads = count("downloads"),
                likes = count("likes"),
                libraryName = string("library_name"),
                tags = strings("tags", MAX_TAGS),
                filePaths = filePaths,
            )
        } catch (error: IllegalArgumentException) {
            throw HuggingFaceMetadataException(
                HuggingFaceMetadataError.InvalidField,
                "Hugging Face search entry $index is invalid",
                error,
            )
        }
    }

    private companion object {
        const val MAX_SEARCH_RESULTS = 1_000
        const val MAX_TAGS = 256
        const val MAX_SIBLINGS = 2_000
    }
}
