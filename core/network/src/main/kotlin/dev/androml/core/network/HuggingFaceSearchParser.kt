package dev.androml.core.network

import dev.androml.core.model.HuggingFaceSearchHit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Parses the untrusted, bounded response from /api/models. */
class HuggingFaceSearchParser {
    fun parse(body: String): List<HuggingFaceSearchHit> {
        val elements = try {
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
        fun string(name: String): String? = value[name]?.jsonPrimitive?.contentOrNull
        fun count(name: String): Long? = value[name]?.jsonPrimitive?.longOrNull
        return try {
            HuggingFaceSearchHit(
                modelId = string("id") ?: throw IllegalArgumentException("id is missing"),
                revision = string("sha"),
                pipelineTag = string("pipeline_tag"),
                downloads = count("downloads"),
                likes = count("likes"),
            )
        } catch (error: IllegalArgumentException) {
            throw HuggingFaceMetadataException(
                HuggingFaceMetadataError.InvalidField,
                "Hugging Face search entry $index is invalid",
                error,
            )
        }
    }
}
