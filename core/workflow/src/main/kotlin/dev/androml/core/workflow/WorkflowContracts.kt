package dev.androml.core.workflow

import dev.androml.core.tools.ToolDescriptor
import dev.androml.core.tools.ToolId
import java.util.UUID

@JvmInline
value class WorkflowId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): WorkflowId {
            require(raw.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) {
                "workflow ID contains unsafe characters"
            }
            return WorkflowId(raw)
        }
    }
}

@JvmInline
value class NodeId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): NodeId {
            require(raw.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) {
                "node ID contains unsafe characters"
            }
            return NodeId(raw)
        }
    }
}

@JvmInline
value class RunId private constructor(val value: String) {
    companion object {
        fun new(): RunId = RunId(UUID.randomUUID().toString())

        fun parse(raw: String): RunId {
            require(raw.length in 1..64 && raw.all { it.isLetterOrDigit() || it == '-' }) {
                "run ID contains unsafe characters"
            }
            return RunId(raw)
        }
    }
}

enum class WorkflowValueType {
    Unit,
    Text,
    Json,
    Documents,
    Boolean,
    Any,
}

sealed interface WorkflowNode {
    val id: NodeId
    val inputType: WorkflowValueType
    val outputType: WorkflowValueType
}

data class InputNode(
    override val id: NodeId,
    override val outputType: WorkflowValueType,
) : WorkflowNode {
    override val inputType: WorkflowValueType = WorkflowValueType.Unit
}

data class ModelNode(
    override val id: NodeId,
    val modelKey: String,
    val workload: String,
    override val inputType: WorkflowValueType = WorkflowValueType.Text,
    override val outputType: WorkflowValueType = WorkflowValueType.Text,
) : WorkflowNode {
    init {
        require(modelKey.isNotBlank() && modelKey.length <= 512) { "model key is invalid" }
        require(workload.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}"))) { "model workload is invalid" }
    }
}

data class RagNode(
    override val id: NodeId,
    val collectionKey: String,
    override val inputType: WorkflowValueType = WorkflowValueType.Text,
    override val outputType: WorkflowValueType = WorkflowValueType.Documents,
) : WorkflowNode {
    init {
        require(collectionKey.isNotBlank() && collectionKey.length <= 256) { "collection key is invalid" }
    }
}

data class ToolNode(
    override val id: NodeId,
    val toolId: ToolId,
    override val inputType: WorkflowValueType = WorkflowValueType.Json,
    override val outputType: WorkflowValueType = WorkflowValueType.Json,
) : WorkflowNode

data class BranchNode(
    override val id: NodeId,
    override val inputType: WorkflowValueType = WorkflowValueType.Boolean,
    override val outputType: WorkflowValueType = WorkflowValueType.Any,
) : WorkflowNode

data class LoopNode(
    override val id: NodeId,
    val maxIterations: Int,
    override val inputType: WorkflowValueType = WorkflowValueType.Any,
    override val outputType: WorkflowValueType = WorkflowValueType.Any,
) : WorkflowNode {
    init {
        require(maxIterations in 1..100) { "loop bound must be between 1 and 100" }
    }
}

data class ApprovalNode(
    override val id: NodeId,
    val requiredScopes: Set<String>,
    override val inputType: WorkflowValueType = WorkflowValueType.Any,
    override val outputType: WorkflowValueType = WorkflowValueType.Any,
) : WorkflowNode {
    init {
        require(requiredScopes.size <= 32) { "approval has too many scopes" }
    }
}

data class OutputNode(
    override val id: NodeId,
    override val inputType: WorkflowValueType = WorkflowValueType.Any,
    override val outputType: WorkflowValueType = WorkflowValueType.Unit,
) : WorkflowNode

data class WorkflowEdge(
    val from: NodeId,
    val to: NodeId,
)

data class WorkflowBudget(
    val maxSteps: Int = 100,
    val maxWallTimeSeconds: Int = 15 * 60,
    val maxToolCalls: Int = 32,
    val maxOutputCharacters: Int = 1_000_000,
) {
    init {
        require(maxSteps in 1..100_000) { "workflow step budget is out of bounds" }
        require(maxWallTimeSeconds in 1..24 * 60 * 60) { "workflow time budget is out of bounds" }
        require(maxToolCalls in 0..10_000) { "workflow tool budget is out of bounds" }
        require(maxOutputCharacters in 1..16 * 1024 * 1024) { "workflow output budget is out of bounds" }
    }
}

data class WorkflowDefinition(
    val id: WorkflowId,
    val version: Int,
    val entry: NodeId,
    val nodes: List<WorkflowNode>,
    val edges: List<WorkflowEdge>,
    val budget: WorkflowBudget = WorkflowBudget(),
) {
    init {
        require(version > 0) { "workflow version must be positive" }
        require(nodes.isNotEmpty() && nodes.size <= 512) { "workflow node count is invalid" }
        require(edges.size <= 2048) { "workflow edge count is invalid" }
    }
}

enum class WorkflowValidationCode {
    DuplicateNode,
    MissingEntry,
    MissingNode,
    UnreachableNode,
    MissingOutput,
    TypeMismatch,
    InvalidBranch,
    InvalidLoop,
    UnboundedCycle,
    UnknownTool,
    UnknownModel,
}

data class WorkflowValidationIssue(
    val code: WorkflowValidationCode,
    val nodeId: NodeId? = null,
    val message: String,
)

data class WorkflowValidationResult(
    val issues: List<WorkflowValidationIssue>,
) {
    val isValid: Boolean
        get() = issues.isEmpty()
}

class WorkflowValidator(
    private val availableTools: Map<ToolId, ToolDescriptor> = emptyMap(),
    private val availableModels: Set<String> = emptySet(),
) {
    fun validate(definition: WorkflowDefinition): WorkflowValidationResult {
        val issues = mutableListOf<WorkflowValidationIssue>()
        val byId = mutableMapOf<NodeId, WorkflowNode>()
        definition.nodes.forEach { node ->
            if (byId.put(node.id, node) != null) {
                issues += WorkflowValidationIssue(
                    WorkflowValidationCode.DuplicateNode,
                    node.id,
                    "node ID is duplicated",
                )
            }
        }
        val entry = byId[definition.entry]
        if (entry == null) {
            issues += WorkflowValidationIssue(
                WorkflowValidationCode.MissingEntry,
                definition.entry,
                "workflow entry node does not exist",
            )
            return WorkflowValidationResult(issues)
        }

        val outgoing = definition.edges.groupBy(WorkflowEdge::from)
        definition.edges.forEach { edge ->
            val from = byId[edge.from]
            val to = byId[edge.to]
            if (from == null) {
                issues += WorkflowValidationIssue(WorkflowValidationCode.MissingNode, edge.from, "edge source does not exist")
            }
            if (to == null) {
                issues += WorkflowValidationIssue(WorkflowValidationCode.MissingNode, edge.to, "edge target does not exist")
            }
            if (from != null && to != null && !compatible(from.outputType, to.inputType)) {
                issues += WorkflowValidationIssue(
                    WorkflowValidationCode.TypeMismatch,
                    to.id,
                    "${from.outputType} cannot feed ${to.inputType}",
                )
            }
        }

        definition.nodes.forEach { node ->
            val count = outgoing[node.id].orEmpty().size
            when (node) {
                is BranchNode -> if (count < 2) {
                    issues += WorkflowValidationIssue(WorkflowValidationCode.InvalidBranch, node.id, "branch requires at least two outgoing edges")
                }
                is LoopNode -> if (count < 2) {
                    issues += WorkflowValidationIssue(
                        WorkflowValidationCode.InvalidLoop,
                        node.id,
                        "loop requires body and exit edges",
                    )
                }
                is OutputNode -> Unit
                else -> if (count == 0) {
                    issues += WorkflowValidationIssue(WorkflowValidationCode.MissingOutput, node.id, "non-output node has no outgoing edge")
                }
            }
            if (node is ToolNode && node.toolId !in availableTools) {
                issues += WorkflowValidationIssue(WorkflowValidationCode.UnknownTool, node.id, "tool is not installed")
            }
            if (node is ModelNode && node.modelKey !in availableModels) {
                issues += WorkflowValidationIssue(WorkflowValidationCode.UnknownModel, node.id, "model is not available")
            }
        }

        val reachable = reachableFrom(definition.entry, outgoing)
        definition.nodes.filterNot { it.id in reachable }.forEach { node ->
            issues += WorkflowValidationIssue(WorkflowValidationCode.UnreachableNode, node.id, "node is unreachable from entry")
        }
        if (reachable.none { byId[it] is OutputNode }) {
            issues += WorkflowValidationIssue(WorkflowValidationCode.MissingOutput, null, "workflow has no reachable output node")
        }

        detectCycles(definition.entry, outgoing, byId, issues)
        return WorkflowValidationResult(issues.distinct())
    }

    private fun detectCycles(
        entry: NodeId,
        outgoing: Map<NodeId, List<WorkflowEdge>>,
        nodes: Map<NodeId, WorkflowNode>,
        issues: MutableList<WorkflowValidationIssue>,
    ) {
        val visited = mutableSetOf<NodeId>()
        val stack = mutableListOf<NodeId>()
        fun visit(nodeId: NodeId) {
            val stackIndex = stack.indexOf(nodeId)
            if (stackIndex >= 0) {
                val cycle = stack.subList(stackIndex, stack.size)
                if (cycle.none { nodes[it] is LoopNode }) {
                    issues += WorkflowValidationIssue(
                        WorkflowValidationCode.UnboundedCycle,
                        nodeId,
                        "cycle is not controlled by a bounded loop node",
                    )
                }
                return
            }
            if (!visited.add(nodeId)) return
            stack += nodeId
            outgoing[nodeId].orEmpty().forEach { edge ->
                if (nodes.containsKey(edge.to)) visit(edge.to)
            }
            stack.removeAt(stack.lastIndex)
        }
        visit(entry)
    }

    private fun reachableFrom(
        entry: NodeId,
        outgoing: Map<NodeId, List<WorkflowEdge>>,
    ): Set<NodeId> {
        val reachable = mutableSetOf<NodeId>()
        val pending = ArrayDeque<NodeId>()
        pending += entry
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (!reachable.add(node)) continue
            outgoing[node].orEmpty().forEach { pending += it.to }
        }
        return reachable
    }

    private fun compatible(from: WorkflowValueType, to: WorkflowValueType): Boolean =
        from == WorkflowValueType.Any || to == WorkflowValueType.Any || from == to
}

sealed interface WorkflowEvent {
    val runId: RunId
    val idempotencyKey: String

    data class Started(
        override val runId: RunId,
        override val idempotencyKey: String,
        val workflowId: WorkflowId,
        val workflowVersion: Int,
        val startedAtEpochMillis: Long,
    ) : WorkflowEvent

    data class NodeStarted(
        override val runId: RunId,
        override val idempotencyKey: String,
        val nodeId: NodeId,
        val attempt: Int,
    ) : WorkflowEvent

    data class Checkpoint(
        override val runId: RunId,
        override val idempotencyKey: String,
        val nodeId: NodeId,
        val attempt: Int,
        val outputHash: String,
    ) : WorkflowEvent

    data class NodeCompleted(
        override val runId: RunId,
        override val idempotencyKey: String,
        val nodeId: NodeId,
        val attempt: Int,
    ) : WorkflowEvent

    data class NodeFailed(
        override val runId: RunId,
        override val idempotencyKey: String,
        val nodeId: NodeId,
        val attempt: Int,
        val safeMessage: String,
        val retryable: Boolean,
    ) : WorkflowEvent

    data class ApprovalRequested(
        override val runId: RunId,
        override val idempotencyKey: String,
        val nodeId: NodeId,
        val approvalId: String,
    ) : WorkflowEvent

    data class StatusChanged(
        override val runId: RunId,
        override val idempotencyKey: String,
        val status: WorkflowRunStatus,
    ) : WorkflowEvent
}

enum class WorkflowRunStatus {
    Running,
    Paused,
    WaitingForApproval,
    Completed,
    Failed,
    Cancelled,
}

data class StoredWorkflowEvent(
    val sequence: Long,
    val event: WorkflowEvent,
)

class WorkflowConcurrencyException(message: String) : IllegalStateException(message)

interface WorkflowEventStore {
    fun append(
        runId: RunId,
        expectedSequence: Long,
        events: List<WorkflowEvent>,
    ): List<StoredWorkflowEvent>

    fun read(runId: RunId): List<StoredWorkflowEvent>
}

interface DurableWorkflowEventStore {
    suspend fun append(
        runId: RunId,
        expectedSequence: Long,
        events: List<WorkflowEvent>,
    ): List<StoredWorkflowEvent>

    suspend fun read(runId: RunId): List<StoredWorkflowEvent>
}

class InMemoryWorkflowEventStore : WorkflowEventStore {
    private val events = mutableMapOf<RunId, MutableList<StoredWorkflowEvent>>()

    @Synchronized
    override fun append(
        runId: RunId,
        expectedSequence: Long,
        events: List<WorkflowEvent>,
    ): List<StoredWorkflowEvent> {
        require(events.all { it.runId == runId }) { "event run IDs do not match" }
        val stream = this.events.getOrPut(runId) { mutableListOf() }
        val existingKeys = stream.mapTo(mutableSetOf()) { it.event.idempotencyKey }
        val newEvents = events
            .filterNot { it.idempotencyKey in existingKeys }
            .distinctBy(WorkflowEvent::idempotencyKey)
        if (stream.size.toLong() != expectedSequence && newEvents.isNotEmpty()) {
            throw WorkflowConcurrencyException("workflow event sequence changed")
        }
        val stored = newEvents.mapIndexed { index, event ->
            StoredWorkflowEvent(
                sequence = expectedSequence + index + 1,
                event = event,
            )
        }
        stream += stored
        return stored
    }

    @Synchronized
    override fun read(runId: RunId): List<StoredWorkflowEvent> = events[runId].orEmpty().toList()
}
