package dev.androml.core.workflow

import dev.androml.core.tools.ToolId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Stable, bounded persistence format for user-authored workflow definitions. */
object WorkflowDefinitionCodec {
    const val MAX_PAYLOAD_CHARS = 1 * 1024 * 1024

    private const val KIND = "workflow_definition"
    private val json = Json { explicitNulls = false }

    fun encode(definition: WorkflowDefinition): String {
        val encoded = buildJsonObject {
            put("kind", KIND)
            put("id", definition.id.value)
            put("version", definition.version)
            put("entry", definition.entry.value)
            put("budget", encodeBudget(definition.budget))
            put("nodes", buildJsonArray { definition.nodes.forEach { add(encodeNode(it)) } })
            put("edges", buildJsonArray {
                definition.edges.forEach { edge ->
                    add(buildJsonObject {
                        put("from", edge.from.value)
                        put("to", edge.to.value)
                    })
                }
            })
        }.toString()
        require(encoded.length <= MAX_PAYLOAD_CHARS) { "workflow definition is too large" }
        return encoded
    }

    fun decode(payload: String): WorkflowDefinition {
        require(payload.length in 2..MAX_PAYLOAD_CHARS) { "workflow definition payload is out of bounds" }
        val root = try {
            Json.parseToJsonElement(payload).jsonObject
        } catch (error: Exception) {
            throw IllegalArgumentException("workflow definition is not valid JSON", error)
        }
        require(root.requiredString("kind") == KIND) { "workflow definition kind is unknown" }
        val nodes = root.requiredArray("nodes").map { decodeNode(it.jsonObject) }
        val edges = root.requiredArray("edges").map { edge ->
            val edgeObject = edge.jsonObject
            WorkflowEdge(
                from = NodeId.parse(edgeObject.requiredString("from", 64)),
                to = NodeId.parse(edgeObject.requiredString("to", 64)),
            )
        }
        return WorkflowDefinition(
            id = WorkflowId.parse(root.requiredString("id", 64)),
            version = root.requiredInt("version", 1..1_000_000),
            entry = NodeId.parse(root.requiredString("entry", 64)),
            nodes = nodes,
            edges = edges,
            budget = decodeBudget(root["budget"]?.jsonObject ?: throw IllegalArgumentException("workflow budget is missing")),
        )
    }

    private fun encodeBudget(budget: WorkflowBudget) = buildJsonObject {
        put("max_steps", budget.maxSteps)
        put("max_wall_time_seconds", budget.maxWallTimeSeconds)
        put("max_tool_calls", budget.maxToolCalls)
        put("max_output_characters", budget.maxOutputCharacters)
    }

    private fun decodeBudget(root: JsonObject): WorkflowBudget = WorkflowBudget(
        maxSteps = root.requiredInt("max_steps", 1..100_000),
        maxWallTimeSeconds = root.requiredInt("max_wall_time_seconds", 1..24 * 60 * 60),
        maxToolCalls = root.requiredInt("max_tool_calls", 0..10_000),
        maxOutputCharacters = root.requiredInt("max_output_characters", 1..16 * 1024 * 1024),
    )

    private fun encodeNode(node: WorkflowNode) = buildJsonObject {
        put("id", node.id.value)
        when (node) {
            is InputNode -> {
                put("kind", "input")
                put("output_type", node.outputType.name)
            }
            is ModelNode -> {
                put("kind", "model")
                put("model_key", node.modelKey)
                put("workload", node.workload)
                putTypes(node)
            }
            is AgentNode -> {
                put("kind", "agent")
                put("agent_key", node.agentKey)
                putTypes(node)
            }
            is RagNode -> {
                put("kind", "rag")
                put("collection_key", node.collectionKey)
                putTypes(node)
            }
            is ToolNode -> {
                put("kind", "tool")
                put("tool_id", node.toolId.value)
                putTypes(node)
            }
            is BranchNode -> put("kind", "branch")
            is LoopNode -> {
                put("kind", "loop")
                put("max_iterations", node.maxIterations)
                putTypes(node)
            }
            is ApprovalNode -> {
                put("kind", "approval")
                put("required_scopes", buildJsonArray {
                    node.requiredScopes.sorted().forEach { scope -> add(JsonPrimitive(scope)) }
                })
                putTypes(node)
            }
            is OutputNode -> {
                put("kind", "output")
                put("input_type", node.inputType.name)
            }
        }
    }

    private fun JsonObjectBuilder.putTypes(node: WorkflowNode) {
        put("input_type", node.inputType.name)
        put("output_type", node.outputType.name)
    }

    private fun decodeNode(root: JsonObject): WorkflowNode {
        val id = NodeId.parse(root.requiredString("id", 64))
        return when (root.requiredString("kind", 32)) {
            "input" -> InputNode(id, root.requiredType("output_type"))
            "model" -> ModelNode(
                id = id,
                modelKey = root.requiredString("model_key", 512),
                workload = root.requiredString("workload", 64),
                inputType = root.requiredType("input_type"),
                outputType = root.requiredType("output_type"),
            )
            "agent" -> AgentNode(
                id = id,
                agentKey = root.requiredString("agent_key", 512),
                inputType = root.requiredType("input_type"),
                outputType = root.requiredType("output_type"),
            )
            "rag" -> RagNode(
                id = id,
                collectionKey = root.requiredString("collection_key", 256),
                inputType = root.requiredType("input_type"),
                outputType = root.requiredType("output_type"),
            )
            "tool" -> ToolNode(
                id = id,
                toolId = ToolId.parse(root.requiredString("tool_id", 64)),
                inputType = root.requiredType("input_type"),
                outputType = root.requiredType("output_type"),
            )
            "branch" -> BranchNode(id)
            "loop" -> LoopNode(
                id = id,
                maxIterations = root.requiredInt("max_iterations", 1..100),
                inputType = root.requiredType("input_type"),
                outputType = root.requiredType("output_type"),
            )
            "approval" -> ApprovalNode(
                id = id,
                requiredScopes = root.requiredArray("required_scopes")
                    .map { it.jsonPrimitive.contentOrNull ?: throw IllegalArgumentException("approval scope is invalid") }
                    .toSet(),
                inputType = root.requiredType("input_type"),
                outputType = root.requiredType("output_type"),
            )
            "output" -> OutputNode(id, inputType = root.requiredType("input_type"))
            else -> throw IllegalArgumentException("workflow node kind is unknown")
        }
    }

    private fun JsonObject.requiredType(name: String): WorkflowValueType =
        WorkflowValueType.entries.firstOrNull { it.name.equals(requiredString(name, 32), ignoreCase = true) }
            ?: throw IllegalArgumentException("workflow value type is unknown")

    private fun JsonObject.requiredString(name: String, maxLength: Int = 128): String =
        this[name]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && it.length <= maxLength }
            ?: throw IllegalArgumentException("workflow definition is missing or invalid $name")

    private fun JsonObject.requiredInt(name: String, range: IntRange): Int =
        this[name]?.jsonPrimitive?.intOrNull
            ?.also { require(it in range) { "$name is out of bounds" } }
            ?: throw IllegalArgumentException("workflow definition is missing or invalid $name")

    private fun JsonObject.requiredArray(name: String): JsonArray =
        this[name]?.jsonArray ?: throw IllegalArgumentException("workflow definition is missing $name")

}
