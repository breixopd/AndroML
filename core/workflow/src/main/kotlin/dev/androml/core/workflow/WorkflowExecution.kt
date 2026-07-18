package dev.androml.core.workflow

import dev.androml.core.tools.ToolDescriptor
import dev.androml.core.tools.ToolId
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Values that can cross a workflow edge or be persisted in a checkpoint. */
sealed interface WorkflowValue {
    val type: WorkflowValueType

    data object UnitValue : WorkflowValue {
        override val type: WorkflowValueType = WorkflowValueType.Unit
    }

    data class Text(val value: String) : WorkflowValue {
        init {
            require(value.length <= MAX_TEXT_CHARS) { "workflow text value is too large" }
        }

        override val type: WorkflowValueType = WorkflowValueType.Text
    }

    data class JsonValue(val value: JsonElement) : WorkflowValue {
        init {
            require(value.toString().length <= MAX_JSON_CHARS) { "workflow JSON value is too large" }
        }

        override val type: WorkflowValueType = WorkflowValueType.Json
    }

    data class Documents(val items: List<WorkflowDocument>) : WorkflowValue {
        init {
            require(items.size <= MAX_DOCUMENTS) { "workflow document value has too many items" }
        }

        override val type: WorkflowValueType = WorkflowValueType.Documents
    }

    data class BooleanValue(val value: Boolean) : WorkflowValue {
        override val type: WorkflowValueType = WorkflowValueType.Boolean
    }

    companion object {
        const val MAX_TEXT_CHARS = 16 * 1024 * 1024
        const val MAX_JSON_CHARS = 4 * 1024 * 1024
        const val MAX_DOCUMENTS = 512
    }
}

data class WorkflowDocument(
    val title: String,
    val sourceLabel: String,
    val text: String,
) {
    init {
        require(title.isNotBlank() && title.length <= 512) { "workflow document title is invalid" }
        require(sourceLabel.isNotBlank() && sourceLabel.length <= 1_024) {
            "workflow document source is invalid"
        }
        require(text.isNotBlank() && text.length <= 1 * 1024 * 1024) {
            "workflow document text is invalid"
        }
    }
}

/** Stable, bounded encoding used by durable workflow checkpoints. */
object WorkflowValueCodec {
    const val MAX_PAYLOAD_CHARS = 16 * 1024 * 1024

    fun encode(value: WorkflowValue): String {
        val encoded = when (value) {
            WorkflowValue.UnitValue -> buildJsonObject { put("type", "unit") }
            is WorkflowValue.Text -> buildJsonObject {
                put("type", "text")
                put("value", value.value)
            }
            is WorkflowValue.JsonValue -> buildJsonObject {
                put("type", "json")
                put("value", value.value)
            }
            is WorkflowValue.Documents -> buildJsonObject {
                put("type", "documents")
                put(
                    "items",
                    JsonArray(value.items.map { document ->
                        buildJsonObject {
                            put("title", document.title)
                            put("source", document.sourceLabel)
                            put("text", document.text)
                        }
                    }),
                )
            }
            is WorkflowValue.BooleanValue -> buildJsonObject {
                put("type", "boolean")
                put("value", value.value)
            }
        }.toString()
        require(encoded.length <= MAX_PAYLOAD_CHARS) { "workflow value payload is too large" }
        return encoded
    }

    fun decode(payload: String): WorkflowValue {
        require(payload.length in 2..MAX_PAYLOAD_CHARS) { "workflow value payload is out of bounds" }
        val root = Json.parseToJsonElement(payload).jsonObject
        return when (root.requiredString("type", 32)) {
            "unit" -> WorkflowValue.UnitValue
            "text" -> WorkflowValue.Text(
                root.requiredString("value", WorkflowValue.MAX_TEXT_CHARS, requireNonBlank = false),
            )
            "json" -> WorkflowValue.JsonValue(
                root["value"] ?: throw IllegalArgumentException("workflow JSON value is missing"),
            )
            "documents" -> WorkflowValue.Documents(
                root["items"]?.jsonArray?.map { item ->
                    val document = item.jsonObject
                    WorkflowDocument(
                        title = document.requiredString("title", 512),
                        sourceLabel = document.requiredString("source", 1_024),
                        text = document.requiredString("text", 1 * 1024 * 1024),
                    )
                } ?: throw IllegalArgumentException("workflow document items are missing"),
            )
            "boolean" -> WorkflowValue.BooleanValue(
                root["value"]?.jsonPrimitive?.booleanOrNull
                    ?: throw IllegalArgumentException("workflow boolean value is invalid"),
            )
            else -> throw IllegalArgumentException("workflow value type is unknown")
        }
    }

    fun hash(value: WorkflowValue): String = sha256(encode(value))

    private fun JsonObject.requiredString(
        name: String,
        maxLength: Int,
        requireNonBlank: Boolean = true,
    ): String =
        this[name]?.jsonPrimitive?.contentOrNull
            ?.also {
                require((!requireNonBlank || it.isNotBlank()) && it.length <= maxLength) {
                    "$name is invalid"
                }
            }
            ?: throw IllegalArgumentException("$name is missing")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
}

data class WorkflowCheckpoint(
    val runId: RunId,
    val nodeId: NodeId,
    val attempt: Int,
    val outputHash: String,
    val value: WorkflowValue,
) {
    init {
        require(attempt in 1..1_000) { "workflow checkpoint attempt is out of bounds" }
        require(outputHash.matches(SHA256_PATTERN)) { "workflow checkpoint hash is invalid" }
        require(outputHash == WorkflowValueCodec.hash(value)) {
            "workflow checkpoint hash does not match its value"
        }
    }

    companion object {
        private val SHA256_PATTERN = Regex("[a-f0-9]{64}")
    }
}

interface WorkflowCheckpointStore {
    suspend fun save(checkpoint: WorkflowCheckpoint)

    suspend fun load(runId: RunId, nodeId: NodeId, attempt: Int): WorkflowCheckpoint?
}

class InMemoryWorkflowCheckpointStore : WorkflowCheckpointStore {
    private val checkpoints = mutableMapOf<Triple<RunId, NodeId, Int>, WorkflowCheckpoint>()

    override suspend fun save(checkpoint: WorkflowCheckpoint) {
        synchronized(this) {
            checkpoints[Triple(checkpoint.runId, checkpoint.nodeId, checkpoint.attempt)] = checkpoint
        }
    }

    override suspend fun load(runId: RunId, nodeId: NodeId, attempt: Int): WorkflowCheckpoint? =
        synchronized(this) { checkpoints[Triple(runId, nodeId, attempt)] }

    @Synchronized
    fun list(runId: RunId): List<WorkflowCheckpoint> = checkpoints.values
        .filter { it.runId == runId }
        .sortedWith(compareBy<WorkflowCheckpoint>({ it.nodeId.value }, { it.attempt }))
}

sealed interface WorkflowApprovalDecision {
    data object Approved : WorkflowApprovalDecision

    data object Pending : WorkflowApprovalDecision

    data object Denied : WorkflowApprovalDecision
}

typealias WorkflowModelRunner = suspend (ModelNode, WorkflowValue) -> WorkflowValue.Text
typealias WorkflowRagRunner = suspend (RagNode, WorkflowValue) -> WorkflowValue.Documents
typealias WorkflowToolRunner = suspend (ToolNode, WorkflowValue) -> WorkflowValue.JsonValue
typealias WorkflowApprovalRunner = suspend (ApprovalNode, WorkflowValue) -> WorkflowApprovalDecision

data class WorkflowNodeRunners(
    val model: WorkflowModelRunner = { node, _ ->
        throw WorkflowNodeExecutionException("model runner is not configured for ${node.modelKey}")
    },
    val rag: WorkflowRagRunner = { node, _ ->
        throw WorkflowNodeExecutionException("RAG runner is not configured for ${node.collectionKey}")
    },
    val tool: WorkflowToolRunner = { node, _ ->
        throw WorkflowNodeExecutionException("tool runner is not configured for ${node.toolId.value}")
    },
    val approval: WorkflowApprovalRunner = { _, _ -> WorkflowApprovalDecision.Pending },
)

class WorkflowNodeExecutionException(
    message: String,
    val retryable: Boolean = false,
) : IllegalStateException(message)

data class WorkflowExecutionResult(
    val runId: RunId,
    val status: WorkflowRunStatus,
    val output: WorkflowValue? = null,
    val waitingForNode: NodeId? = null,
    val completedOutputNode: NodeId? = null,
    val error: String? = null,
    val retryableFailure: Boolean = false,
) {
    val canResume: Boolean
        get() = status == WorkflowRunStatus.WaitingForApproval || status == WorkflowRunStatus.Paused ||
            (status == WorkflowRunStatus.Failed && retryableFailure)
}

class WorkflowExecutor(
    private val eventStore: DurableWorkflowEventStore,
    private val checkpointStore: WorkflowCheckpointStore,
    private val availableTools: Map<ToolId, ToolDescriptor> = emptyMap(),
    private val availableModels: Set<String> = emptySet(),
    private val runners: WorkflowNodeRunners = WorkflowNodeRunners(),
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun execute(
        runId: RunId,
        definition: WorkflowDefinition,
        input: WorkflowValue,
    ): WorkflowExecutionResult {
        val validation = WorkflowValidator(availableTools, availableModels).validate(definition)
        if (!validation.isValid) {
            return WorkflowExecutionResult(
                runId = runId,
                status = WorkflowRunStatus.Failed,
                error = validation.issues.joinToString("; ") { issue -> issue.message.take(MAX_SAFE_MESSAGE_CHARS) },
            )
        }
        val entryType = definition.nodes.first { it.id == definition.entry }.outputType
        if (input.type != entryType && entryType != WorkflowValueType.Any) {
            return WorkflowExecutionResult(
                runId = runId,
                status = WorkflowRunStatus.Failed,
                error = "${input.type} cannot feed $entryType",
            )
        }

        val stream = eventStore.read(runId).toMutableList()
        val eventWriter = EventWriter(runId, eventStore, stream)
        val started = stream.map { it.event }.filterIsInstance<WorkflowEvent.Started>().firstOrNull()
        if (started != null && (started.workflowId != definition.id || started.workflowVersion != definition.version)) {
            return WorkflowExecutionResult(
                runId = runId,
                status = WorkflowRunStatus.Failed,
                error = "run is already bound to another workflow definition",
            )
        }
        if (started == null) {
            eventWriter.append(
                WorkflowEvent.Started(
                    runId = runId,
                    idempotencyKey = "workflow-started:${definition.id.value}:${definition.version}",
                    workflowId = definition.id,
                    workflowVersion = definition.version,
                    startedAtEpochMillis = nowEpochMillis(),
                ),
                WorkflowEvent.StatusChanged(
                    runId = runId,
                    idempotencyKey = "workflow-status:running:0",
                    status = WorkflowRunStatus.Running,
                ),
            )
        }

        val status = latestStatus(stream)
        if (status == WorkflowRunStatus.Completed || status == WorkflowRunStatus.Cancelled) {
            return restoreTerminalResult(runId, definition, stream, status)
        }
        val lastFailure = stream.asReversed().firstNotNullOfOrNull { it.event as? WorkflowEvent.NodeFailed }
        if (status == WorkflowRunStatus.Failed && lastFailure?.retryable != true) {
            return WorkflowExecutionResult(runId, status, error = lastFailure?.safeMessage)
        }

        val nodeById = definition.nodes.associateBy(WorkflowNode::id)
        val outgoing = definition.edges.groupBy(WorkflowEdge::from)
        val completed = stream.map { it.event }.filterIsInstance<WorkflowEvent.NodeCompleted>().toMutableList()
        val startedAttemptsByNode = stream.map { it.event }
            .filterIsInstance<WorkflowEvent.NodeStarted>()
            .groupBy(WorkflowEvent.NodeStarted::nodeId)
            .mapValuesTo(mutableMapOf()) { (_, attempts) -> attempts.toMutableList() }
        val completedAttemptsByNode = completed.groupBy(WorkflowEvent.NodeCompleted::nodeId)
            .mapValuesTo(mutableMapOf()) { (_, attempts) -> attempts.mapTo(mutableSetOf()) { it.attempt } }
        var completedIndex = 0
        var currentNodeId = definition.entry
        var currentValue = input
        var totalSteps = stream.count { it.event is WorkflowEvent.NodeStarted }
        var toolCalls = stream.count { stored ->
            val nodeId = (stored.event as? WorkflowEvent.NodeStarted)?.nodeId ?: return@count false
            nodeById[nodeId] is ToolNode
        }
        val startedAt = started?.startedAtEpochMillis ?: nowEpochMillis()

        while (true) {
            try {
                enforceBudget(definition.budget, startedAt, totalSteps, toolCalls, currentValue)
            } catch (failure: WorkflowNodeExecutionException) {
                return fail(runId, eventWriter, failure.message ?: "workflow budget exceeded")
            }
            val node = nodeById[currentNodeId]
                ?: return fail(runId, eventWriter, "workflow node disappeared during execution")
            val completedEvent = completed.getOrNull(completedIndex)
            if (completedEvent != null && completedEvent.nodeId == node.id) {
                val restored = restoreCheckpoint(runId, node.id, completedEvent.attempt, stream)
                    ?: return fail(runId, eventWriter, "checkpoint is missing for completed node ${node.id.value}")
                currentValue = restored.value
                completedIndex += 1
                if (node is OutputNode) {
                    return complete(runId, eventWriter, node.id, currentValue)
                }
                currentNodeId = nextNode(node, currentValue, completedEvent.attempt, outgoing)
                    ?: return fail(runId, eventWriter, "node ${node.id.value} has no next step")
                continue
            }
            if (completedEvent != null && completedEvent.nodeId != node.id) {
                return fail(runId, eventWriter, "workflow event stream diverged at node ${node.id.value}")
            }

            val priorAttempts = startedAttemptsByNode[node.id].orEmpty()
            val completedAttempts = completedAttemptsByNode.getOrPut(node.id) { mutableSetOf() }
            val activeAttempt = priorAttempts.lastOrNull { it.attempt !in completedAttempts }?.attempt
                ?: ((priorAttempts.maxOfOrNull(WorkflowEvent.NodeStarted::attempt) ?: 0) + 1)
            val pendingCheckpoint = restoreCheckpoint(runId, node.id, activeAttempt, stream)
            if (pendingCheckpoint != null && activeAttempt !in completedAttempts) {
                eventWriter.append(
                    WorkflowEvent.NodeCompleted(
                        runId = runId,
                        idempotencyKey = nodeCompletedKey(node.id, activeAttempt),
                        nodeId = node.id,
                        attempt = activeAttempt,
                    ),
                )
                completed += WorkflowEvent.NodeCompleted(
                    runId,
                    nodeCompletedKey(node.id, activeAttempt),
                    node.id,
                    activeAttempt,
                )
                completedAttempts += activeAttempt
                currentValue = pendingCheckpoint.value
                completedIndex += 1
                if (node is OutputNode) return complete(runId, eventWriter, node.id, currentValue)
                currentNodeId = nextNode(node, currentValue, activeAttempt, outgoing)
                    ?: return fail(runId, eventWriter, "node ${node.id.value} has no next step")
                continue
            }

            if (node is ApprovalNode) {
                val decision = runners.approval(node, currentValue)
                when (decision) {
                    WorkflowApprovalDecision.Pending -> {
                        eventWriter.append(
                            WorkflowEvent.ApprovalRequested(
                                runId = runId,
                                idempotencyKey = "approval:${node.id.value}:$activeAttempt",
                                nodeId = node.id,
                                approvalId = "approval-${runId.value}-${node.id.value}-$activeAttempt",
                            ),
                            WorkflowEvent.StatusChanged(
                                runId = runId,
                                idempotencyKey = statusKey(WorkflowRunStatus.WaitingForApproval, eventWriter.size),
                                status = WorkflowRunStatus.WaitingForApproval,
                            ),
                        )
                        return WorkflowExecutionResult(runId, WorkflowRunStatus.WaitingForApproval, waitingForNode = node.id)
                    }
                    WorkflowApprovalDecision.Denied -> {
                        return failNode(
                            runId,
                            eventWriter,
                            node,
                            activeAttempt,
                            "approval was denied",
                            retryable = false,
                        )
                    }
                    WorkflowApprovalDecision.Approved -> Unit
                }
            }

            if (priorAttempts.none { it.attempt == activeAttempt }) {
                eventWriter.append(
                    WorkflowEvent.NodeStarted(
                        runId = runId,
                        idempotencyKey = nodeStartedKey(node.id, activeAttempt),
                        nodeId = node.id,
                        attempt = activeAttempt,
                    ),
                )
                startedAttemptsByNode.getOrPut(node.id) { mutableListOf() } += WorkflowEvent.NodeStarted(
                    runId,
                    nodeStartedKey(node.id, activeAttempt),
                    node.id,
                    activeAttempt,
                )
                totalSteps += 1
                if (node is ToolNode) toolCalls += 1
                try {
                    enforceBudget(definition.budget, startedAt, totalSteps, toolCalls, currentValue)
                } catch (failure: WorkflowNodeExecutionException) {
                    return fail(runId, eventWriter, failure.message ?: "workflow budget exceeded")
                }
            }

            val produced = try {
                runNode(node, currentValue)
            } catch (failure: WorkflowNodeExecutionException) {
                return failNode(runId, eventWriter, node, activeAttempt, failure.message ?: "node failed", failure.retryable)
            } catch (failure: Exception) {
                return failNode(runId, eventWriter, node, activeAttempt, failure.message ?: "node failed", retryable = false)
            }
            val checkpointValue = if (node is OutputNode) currentValue else produced
            try {
                if (node !is OutputNode) requireCompatible(checkpointValue, node.outputType)
                enforceBudget(definition.budget, startedAt, totalSteps, toolCalls, checkpointValue)
            } catch (failure: WorkflowNodeExecutionException) {
                return failNode(
                    runId,
                    eventWriter,
                    node,
                    activeAttempt,
                    failure.message ?: "workflow budget exceeded",
                    retryable = false,
                )
            }
            val checkpoint = WorkflowCheckpoint(
                runId = runId,
                nodeId = node.id,
                attempt = activeAttempt,
                outputHash = WorkflowValueCodec.hash(checkpointValue),
                value = checkpointValue,
            )
            checkpointStore.save(checkpoint)
            eventWriter.append(
                WorkflowEvent.Checkpoint(
                    runId = runId,
                    idempotencyKey = "checkpoint:${node.id.value}:$activeAttempt",
                    nodeId = node.id,
                    attempt = activeAttempt,
                    outputHash = checkpoint.outputHash,
                ),
            )
            eventWriter.append(
                WorkflowEvent.NodeCompleted(
                    runId = runId,
                    idempotencyKey = nodeCompletedKey(node.id, activeAttempt),
                    nodeId = node.id,
                    attempt = activeAttempt,
                ),
            )
            completed += WorkflowEvent.NodeCompleted(
                runId,
                nodeCompletedKey(node.id, activeAttempt),
                node.id,
                activeAttempt,
            )
            completedAttempts += activeAttempt
            completedIndex += 1
            currentValue = checkpointValue
            if (node is OutputNode) return complete(runId, eventWriter, node.id, currentValue)
            currentNodeId = nextNode(node, currentValue, activeAttempt, outgoing)
                ?: return fail(runId, eventWriter, "node ${node.id.value} has no next step")
        }
    }

    private suspend fun runNode(node: WorkflowNode, input: WorkflowValue): WorkflowValue = when (node) {
        is InputNode -> input
        is ModelNode -> runners.model(node, input)
        is RagNode -> runners.rag(node, input)
        is ToolNode -> runners.tool(node, input)
        is BranchNode -> input
        is LoopNode -> input
        is ApprovalNode -> input
        is OutputNode -> WorkflowValue.UnitValue
    }

    private fun nextNode(
        node: WorkflowNode,
        value: WorkflowValue,
        attempt: Int,
        outgoing: Map<NodeId, List<WorkflowEdge>>,
    ): NodeId? {
        val edges = outgoing[node.id].orEmpty()
        return when (node) {
            is BranchNode -> {
                val branch = value as? WorkflowValue.BooleanValue
                    ?: return null
                edges.getOrNull(if (branch.value) 0 else 1)?.to
            }
            is LoopNode -> {
                if (attempt < node.maxIterations) edges.firstOrNull()?.to else edges.getOrNull(1)?.to
            }
            else -> edges.firstOrNull()?.to
        }
    }

    private suspend fun restoreCheckpoint(
        runId: RunId,
        nodeId: NodeId,
        attempt: Int,
        stream: List<StoredWorkflowEvent>,
    ): WorkflowCheckpoint? {
        val event = stream.asReversed().firstOrNull {
            it.event is WorkflowEvent.Checkpoint &&
                it.event.nodeId == nodeId &&
                it.event.attempt == attempt
        }?.event as? WorkflowEvent.Checkpoint ?: return null
        val checkpoint = checkpointStore.load(runId, nodeId, attempt)
            ?: return null
        if (checkpoint.outputHash != event.outputHash) return null
        return checkpoint
    }

    private suspend fun restoreTerminalResult(
        runId: RunId,
        definition: WorkflowDefinition,
        stream: List<StoredWorkflowEvent>,
        status: WorkflowRunStatus,
    ): WorkflowExecutionResult {
        if (status != WorkflowRunStatus.Completed) return WorkflowExecutionResult(runId, status)
        val completed = stream.asReversed().firstOrNull {
            val event = it.event as? WorkflowEvent.NodeCompleted ?: return@firstOrNull false
            definition.nodes.any { node -> node is OutputNode && node.id == event.nodeId }
        }?.event as? WorkflowEvent.NodeCompleted
            ?: return WorkflowExecutionResult(
                runId = runId,
                status = WorkflowRunStatus.Failed,
                error = "completed workflow output node is missing",
            )
        val outputNode = definition.nodes.firstOrNull { it.id == completed.nodeId } as? OutputNode
            ?: return WorkflowExecutionResult(
                runId = runId,
                status = WorkflowRunStatus.Failed,
                error = "completed workflow output node is missing",
            )
        val output = restoreCheckpoint(runId, outputNode.id, completed.attempt, stream)?.value
            ?: return WorkflowExecutionResult(
                runId = runId,
                status = WorkflowRunStatus.Failed,
                error = "completed workflow output checkpoint is missing",
            )
        return WorkflowExecutionResult(runId, status, output, completedOutputNode = outputNode.id)
    }

    private suspend fun complete(
        runId: RunId,
        eventWriter: EventWriter,
        outputNode: NodeId,
        output: WorkflowValue,
    ): WorkflowExecutionResult {
        eventWriter.append(
            WorkflowEvent.StatusChanged(
                runId = runId,
                idempotencyKey = statusKey(WorkflowRunStatus.Completed, eventWriter.size),
                status = WorkflowRunStatus.Completed,
            ),
        )
        return WorkflowExecutionResult(
            runId = runId,
            status = WorkflowRunStatus.Completed,
            output = output,
            completedOutputNode = outputNode,
        )
    }

    private suspend fun fail(
        runId: RunId,
        eventWriter: EventWriter,
        message: String,
    ): WorkflowExecutionResult {
        eventWriter.append(
            WorkflowEvent.StatusChanged(
                runId,
                statusKey(WorkflowRunStatus.Failed, eventWriter.size),
                WorkflowRunStatus.Failed,
            ),
        )
        return WorkflowExecutionResult(
            runId,
            WorkflowRunStatus.Failed,
            error = message.take(MAX_SAFE_MESSAGE_CHARS),
        )
    }

    private suspend fun failNode(
        runId: RunId,
        eventWriter: EventWriter,
        node: WorkflowNode,
        attempt: Int,
        message: String,
        retryable: Boolean,
    ): WorkflowExecutionResult {
        eventWriter.append(
            WorkflowEvent.NodeFailed(
                runId = runId,
                idempotencyKey = "node-failed:${node.id.value}:$attempt",
                nodeId = node.id,
                attempt = attempt,
                safeMessage = message.take(MAX_SAFE_MESSAGE_CHARS),
                retryable = retryable,
            ),
            WorkflowEvent.StatusChanged(
                runId = runId,
                idempotencyKey = statusKey(WorkflowRunStatus.Failed, eventWriter.size),
                status = WorkflowRunStatus.Failed,
            ),
        )
        return WorkflowExecutionResult(
            runId = runId,
            status = WorkflowRunStatus.Failed,
            error = message.take(MAX_SAFE_MESSAGE_CHARS),
            retryableFailure = retryable,
        )
    }

    private fun enforceBudget(
        budget: WorkflowBudget,
        startedAt: Long,
        totalSteps: Int,
        toolCalls: Int,
        value: WorkflowValue,
    ) {
        if (totalSteps > budget.maxSteps) throw WorkflowNodeExecutionException("workflow step budget exceeded")
        if (toolCalls > budget.maxToolCalls) throw WorkflowNodeExecutionException("workflow tool budget exceeded")
        if (nowEpochMillis() - startedAt > budget.maxWallTimeSeconds * 1_000L) {
            throw WorkflowNodeExecutionException("workflow wall-time budget exceeded")
        }
        if (WorkflowValueCodec.encode(value).length > budget.maxOutputCharacters) {
            throw WorkflowNodeExecutionException("workflow output budget exceeded")
        }
    }

    private fun requireCompatible(value: WorkflowValue, expected: WorkflowValueType) {
        if (expected != WorkflowValueType.Any && value.type != expected) {
            throw WorkflowNodeExecutionException("${value.type} cannot feed $expected")
        }
    }

    private fun latestStatus(stream: List<StoredWorkflowEvent>): WorkflowRunStatus? = stream.asReversed()
        .firstNotNullOfOrNull { (it.event as? WorkflowEvent.StatusChanged)?.status }

    private fun nodeStartedKey(nodeId: NodeId, attempt: Int): String = "node-started:${nodeId.value}:$attempt"

    private fun nodeCompletedKey(nodeId: NodeId, attempt: Int): String = "node-completed:${nodeId.value}:$attempt"

    private fun statusKey(status: WorkflowRunStatus, sequence: Long): String =
        "workflow-status:${status.name.lowercase(Locale.ROOT)}:$sequence"

    private class EventWriter(
        private val runId: RunId,
        private val store: DurableWorkflowEventStore,
        private val stream: MutableList<StoredWorkflowEvent>,
    ) {
        val size: Long
            get() = stream.size.toLong()

        suspend fun append(vararg events: WorkflowEvent) {
            if (events.isEmpty()) return
            val stored = store.append(runId, stream.size.toLong(), events.toList())
            if (stored.isNotEmpty()) stream += stored
        }
    }

    companion object {
        private const val MAX_SAFE_MESSAGE_CHARS = 512
    }
}

/** Suspend-friendly in-memory event store used by executor tests and previews. */
class InMemoryDurableWorkflowEventStore : DurableWorkflowEventStore {
    private val delegate = InMemoryWorkflowEventStore()

    override suspend fun append(
        runId: RunId,
        expectedSequence: Long,
        events: List<WorkflowEvent>,
    ): List<StoredWorkflowEvent> = delegate.append(runId, expectedSequence, events)

    override suspend fun read(runId: RunId): List<StoredWorkflowEvent> = delegate.read(runId)
}

private val SHA256_PATTERN = Regex("[a-f0-9]{64}")
