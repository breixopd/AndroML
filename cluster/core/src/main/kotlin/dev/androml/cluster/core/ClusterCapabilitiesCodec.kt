package dev.androml.cluster.core

import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class ClusterCapabilityAdvertisement(
    val nodeId: PeerId,
    val capabilities: NodeCapabilities,
)

/** Bounded JSON for authenticated capability heartbeats. */
object ClusterCapabilitiesCodec {
    const val MAX_BYTES = 256 * 1024

    private val json = Json { explicitNulls = false }

    fun encode(advertisement: ClusterCapabilityAdvertisement): String {
        val encoded = json.encodeToString(
            buildJsonObject {
                put("protocol_major", advertisement.capabilities.protocolMajor)
                put("protocol_minor", advertisement.capabilities.protocolMinor)
                put("node_id", advertisement.nodeId.value)
                put(
                    "supported_workloads",
                    buildJsonArray {
                        advertisement.capabilities.supportedWorkloads
                            .map { it.name.lowercase(Locale.ROOT) }
                            .sorted()
                            .forEach { add(JsonPrimitive(it)) }
                    },
                )
                put(
                    "model_hashes",
                    buildJsonArray {
                        advertisement.capabilities.modelHashes
                            .map(ContentHash::value)
                            .sorted()
                            .forEach { add(JsonPrimitive(it)) }
                    },
                )
                put("max_concurrent_jobs", advertisement.capabilities.maxConcurrentJobs)
                put("available_ram_bytes", advertisement.capabilities.availableRamBytes)
                put("queue_depth", advertisement.capabilities.queueDepth)
                put("thermal_severity", advertisement.capabilities.thermalSeverity)
                put("battery_percent", advertisement.capabilities.batteryPercent)
                put("charging", advertisement.capabilities.charging)
                put("last_seen_epoch_millis", advertisement.capabilities.lastSeenEpochMillis)
            },
        )
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "cluster capabilities exceed the safety limit"
        }
        return encoded
    }

    fun decode(raw: String): ClusterCapabilityAdvertisement {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "cluster capabilities exceed the safety limit"
        }
        val root = try {
            Json.parseToJsonElement(raw).jsonObject
        } catch (error: Exception) {
            throw IllegalArgumentException("cluster capabilities are invalid", error)
        }
        val protocolMajor = root.requiredInt("protocol_major")
        require(protocolMajor == 1) { "unsupported cluster capabilities protocol" }
        val protocolMinor = root.requiredInt("protocol_minor")
        val workloads = root.requiredArray("supported_workloads").map { element ->
            val rawWorkload = element.jsonPrimitive.contentOrNull
                ?: throw IllegalArgumentException("cluster workload is invalid")
            ClusterWorkload.entries.firstOrNull { it.name.equals(rawWorkload, ignoreCase = true) }
                ?: throw IllegalArgumentException("cluster workload is unknown")
        }.toSet()
        val modelHashes = root.requiredArray("model_hashes").map { element ->
            ContentHash.parse(
                element.jsonPrimitive.contentOrNull
                    ?: throw IllegalArgumentException("cluster model hash is invalid"),
            )
        }.toSet()
        return ClusterCapabilityAdvertisement(
            nodeId = PeerId.parse(root.requiredString("node_id")),
            capabilities = NodeCapabilities(
                protocolMajor = protocolMajor,
                protocolMinor = protocolMinor,
                supportedWorkloads = workloads,
                modelHashes = modelHashes,
                maxConcurrentJobs = root.requiredInt("max_concurrent_jobs"),
                availableRamBytes = root.requiredLong("available_ram_bytes"),
                queueDepth = root.requiredInt("queue_depth"),
                thermalSeverity = root.requiredInt("thermal_severity"),
                batteryPercent = root.requiredInt("battery_percent"),
                charging = root.requiredBoolean("charging"),
                lastSeenEpochMillis = root.requiredLong("last_seen_epoch_millis"),
            ),
        )
    }

    private fun JsonObject.requiredString(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("cluster capabilities are missing $name")

    private fun JsonObject.requiredInt(name: String): Int =
        this[name]?.jsonPrimitive?.intOrNull
            ?: throw IllegalArgumentException("cluster capabilities are missing $name")

    private fun JsonObject.requiredLong(name: String): Long =
        this[name]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("cluster capabilities are missing $name")

    private fun JsonObject.requiredBoolean(name: String): Boolean =
        this[name]?.jsonPrimitive?.booleanOrNull
            ?: throw IllegalArgumentException("cluster capabilities are missing $name")

    private fun JsonObject.requiredArray(name: String): JsonArray =
        this[name]?.jsonArray ?: throw IllegalArgumentException("cluster capabilities are missing $name")
}
