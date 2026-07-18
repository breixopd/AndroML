package dev.androml.app

import dev.androml.cluster.core.ClusterWorkflowStageTask
import dev.androml.cluster.core.ContentHash
import dev.androml.core.agents.AgentDefinition
import dev.androml.core.agents.AgentExecutor
import dev.androml.core.agents.AgentId
import dev.androml.core.agents.AgentMessage
import dev.androml.core.agents.AgentModel
import dev.androml.core.agents.AgentModelDecision
import dev.androml.core.agents.AgentTranscript
import dev.androml.core.database.WorkflowDefinitionRepository
import dev.androml.core.database.WorkflowEventDao
import dev.androml.core.database.WorkflowCheckpointDao
import dev.androml.core.model.DeviceProfile
import dev.androml.core.rag.CollectionId
import dev.androml.core.rag.RetrievalQuery
import dev.androml.core.tools.ToolDescriptor
import dev.androml.core.tools.ToolExecutionContext
import dev.androml.core.tools.ToolExecutionOutcome
import dev.androml.core.tools.ToolExecutor
import dev.androml.core.tools.ToolHandler
import dev.androml.core.tools.ToolId
import dev.androml.core.tools.ToolInputSchema
import dev.androml.core.tools.ToolRegistry
import dev.androml.core.tools.ToolScope
import dev.androml.core.tools.ToolSideEffect
import dev.androml.core.workflow.InputNode
import dev.androml.core.workflow.ModelNode
import dev.androml.core.workflow.NodeId
import dev.androml.core.workflow.OutputNode
import dev.androml.core.workflow.RagNode
import dev.androml.core.workflow.RunId
import dev.androml.core.workflow.WorkflowApprovalDecision
import dev.androml.core.workflow.WorkflowDefinition
import dev.androml.core.workflow.WorkflowEdge
import dev.androml.core.workflow.WorkflowExecutor
import dev.androml.core.workflow.WorkflowNodeExecutionException
import dev.androml.core.workflow.WorkflowNodeRunners
import dev.androml.core.workflow.WorkflowRunStatus
import dev.androml.core.workflow.WorkflowValue
import dev.androml.core.workflow.WorkflowValueCodec
import dev.androml.core.workflow.WorkflowValueType
import dev.androml.core.workflow.WorkflowId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class WorkflowRunSnapshot(
    val runId: RunId,
    val workflowId: WorkflowId,
    val workflowVersion: Int,
    val status: WorkflowRunStatus,
    val output: WorkflowValue? = null,
    val waitingForNode: NodeId? = null,
    val error: String? = null,
)

/** Connects durable workflow execution to local tools, installed models, RAG, and cluster routing. */
class WorkflowController(
    private val definitionRepository: WorkflowDefinitionRepository,
    private val eventStore: WorkflowEventDao,
    private val checkpointStore: WorkflowCheckpointDao,
    private val catalogRepository: dev.androml.core.database.ModelCatalogRepository,
    private val clusterController: ClusterController,
    private val deviceProfileProvider: () -> DeviceProfile,
) {
    private val _lastRun = MutableStateFlow<WorkflowRunSnapshot?>(null)
    val lastRun: StateFlow<WorkflowRunSnapshot?> = _lastRun.asStateFlow()

    private val tools = ToolRegistry().also { registry ->
        registry.register(
            descriptor = ToolDescriptor(
                id = DEVICE_INFO_TOOL,
                displayName = "Device information",
                description = "Returns bounded local device capability information.",
                sideEffect = ToolSideEffect.Read,
                requiredScopes = setOf(DEVICE_READ_SCOPE),
                input = ToolInputSchema(emptyMap()),
                timeoutSeconds = 5,
                maxResultCharacters = 8 * 1024,
            ),
            handler = ToolHandler { buildDeviceInfo(deviceProfileProvider()) },
        )
    }

    fun observeDefinitions() = definitionRepository.observe()

    suspend fun toolDescriptors(): List<ToolDescriptor> = withContext(Dispatchers.IO) {
        tools.descriptors()
    }

    suspend fun invokeTool(
        toolId: ToolId,
        arguments: JsonObject,
    ): ToolExecutionOutcome = withContext(Dispatchers.IO) {
        ToolExecutor(tools).execute(
            toolId = toolId,
            arguments = arguments,
            context = ToolExecutionContext(grantedScopes = setOf(DEVICE_READ_SCOPE)),
        )
    }

    suspend fun hasAgentModel(): Boolean = withContext(Dispatchers.IO) {
        catalogRepository.installedArtifactHashes().isNotEmpty()
    }

    suspend fun save(definition: WorkflowDefinition) = withContext(Dispatchers.IO) {
        definitionRepository.save(definition)
    }

    suspend fun saveStarterModelWorkflow(modelHash: String): WorkflowDefinition = withContext(Dispatchers.IO) {
        val hash = ContentHash.parse(modelHash)
        val input = InputNode(NodeId.parse("input"), WorkflowValueType.Text)
        val model = ModelNode(NodeId.parse("model"), modelKey = hash.value, workload = "text")
        val output = OutputNode(NodeId.parse("output"))
        val definition = WorkflowDefinition(
            id = WorkflowId.parse("starter-model"),
            version = 1,
            entry = input.id,
            nodes = listOf(input, model, output),
            edges = listOf(WorkflowEdge(input.id, model.id), WorkflowEdge(model.id, output.id)),
        )
        definitionRepository.save(definition)
        definition
    }

    suspend fun saveStarterRagWorkflow(collectionId: String): WorkflowDefinition = withContext(Dispatchers.IO) {
        val collection = CollectionId.parse(collectionId)
        val input = InputNode(NodeId.parse("input"), WorkflowValueType.Text)
        val rag = RagNode(NodeId.parse("rag"), collectionKey = collection.value)
        val output = OutputNode(NodeId.parse("output"), inputType = WorkflowValueType.Documents)
        val definition = WorkflowDefinition(
            id = WorkflowId.parse("starter-rag"),
            version = 1,
            entry = input.id,
            nodes = listOf(input, rag, output),
            edges = listOf(WorkflowEdge(input.id, rag.id), WorkflowEdge(rag.id, output.id)),
        )
        definitionRepository.save(definition)
        definition
    }

    suspend fun run(
        definition: WorkflowDefinition,
        input: WorkflowValue,
        runId: RunId = RunId.parse("run-${UUID.randomUUID()}"),
    ): WorkflowRunSnapshot = withContext(Dispatchers.IO) {
        definitionRepository.save(definition)
        val installedModels = catalogRepository.installedArtifactHashes().map(ContentHash::value).toSet()
        val modelHash = installedModels.firstOrNull()
        val approvalKey = ApprovalKey(runId, null)
        val executor = WorkflowExecutor(
            eventStore = eventStore,
            checkpointStore = checkpointStore,
            availableTools = tools.descriptors().associateBy { it.id },
            availableModels = installedModels,
            availableAgents = if (modelHash == null) emptySet() else setOf(LOCAL_AGENT_KEY),
            runners = runners(modelHash, approvalKey),
        )
        val result = executor.execute(runId, definition, input)
        WorkflowRunSnapshot(
            runId = result.runId,
            workflowId = definition.id,
            workflowVersion = definition.version,
            status = result.status,
            output = result.output,
            waitingForNode = result.waitingForNode,
            error = result.error,
        ).also { _lastRun.value = it }
    }

    suspend fun resume(
        definition: WorkflowDefinition,
        runId: RunId,
        input: WorkflowValue,
    ): WorkflowRunSnapshot = run(definition, input, runId)

    suspend fun approve(
        definition: WorkflowDefinition,
        runId: RunId,
        nodeId: NodeId,
        input: WorkflowValue,
    ): WorkflowRunSnapshot = withContext(Dispatchers.IO) {
        val approvalKey = ApprovalKey(runId, nodeId)
        val installedModels = catalogRepository.installedArtifactHashes().map(ContentHash::value).toSet()
        val executor = WorkflowExecutor(
            eventStore = eventStore,
            checkpointStore = checkpointStore,
            availableTools = tools.descriptors().associateBy { it.id },
            availableModels = installedModels,
            availableAgents = if (installedModels.isEmpty()) emptySet() else setOf(LOCAL_AGENT_KEY),
            runners = runners(installedModels.firstOrNull(), approvalKey),
        )
        val result = executor.execute(runId, definition, input)
        WorkflowRunSnapshot(
            runId = result.runId,
            workflowId = definition.id,
            workflowVersion = definition.version,
            status = result.status,
            output = result.output,
            waitingForNode = result.waitingForNode,
            error = result.error,
        ).also { _lastRun.value = it }
    }

    private fun runners(
        defaultModelHash: String?,
        approvalKey: ApprovalKey,
    ): WorkflowNodeRunners = WorkflowNodeRunners(
        model = { node, value ->
            val input = value as? WorkflowValue.Text
                ?: throw WorkflowNodeExecutionException("model workflow node requires text input")
            val hash = ContentHash.parse(node.modelKey)
            val stage = clusterController.executeBestWorkflowStage(
                ClusterWorkflowStageTask(
                    stageKind = MODEL_STAGE,
                    stageKey = node.modelKey,
                    modelHash = hash,
                    inputPayload = WorkflowValueCodec.encode(input),
                ),
            )
            WorkflowValueCodec.decode(stage.result.outputPayload) as? WorkflowValue.Text
                ?: throw WorkflowNodeExecutionException("model workflow node returned a non-text value")
        },
        agent = { _, value ->
            val hash = defaultModelHash?.let(ContentHash::parse)
                ?: throw WorkflowNodeExecutionException("an installed model is required for the local agent")
            val prompt = value as? WorkflowValue.Text
                ?: throw WorkflowNodeExecutionException("agent workflow node requires text input")
            val definition = AgentDefinition(
                id = AgentId.parse(LOCAL_AGENT_KEY),
                displayName = "Local AndroML agent",
                systemPrompt = "Answer using the supplied context. Do not claim to have used unavailable tools.",
            )
            val agent = AgentExecutor(
                toolRegistry = tools,
                model = AgentModel { _, transcript ->
                    val transcriptText = transcriptText(transcript)
                    val stage = clusterController.executeBestWorkflowStage(
                        ClusterWorkflowStageTask(
                            stageKind = MODEL_STAGE,
                            stageKey = hash.value,
                            modelHash = hash,
                            inputPayload = WorkflowValueCodec.encode(WorkflowValue.Text(transcriptText)),
                        ),
                    )
                    AgentModelDecision.Final(
                        (WorkflowValueCodec.decode(stage.result.outputPayload) as? WorkflowValue.Text)?.value
                            ?: throw WorkflowNodeExecutionException("agent model returned a non-text value"),
                    )
                },
            ).run(
                definition = definition,
                prompt = prompt.value,
                grantedScopes = setOf(DEVICE_READ_SCOPE),
            )
            val finalText = agent.finalText
                ?: throw WorkflowNodeExecutionException(agent.safeError ?: "agent execution did not complete")
            WorkflowValue.Text(finalText)
        },
        rag = { node, value ->
            val query = value as? WorkflowValue.Text
                ?: throw WorkflowNodeExecutionException("RAG workflow node requires text input")
            val merged = clusterController.searchDistributedRag(
                dev.androml.cluster.core.ClusterRagSearchTask(
                    collectionId = CollectionId.parse(node.collectionKey),
                    query = RetrievalQuery(query.value),
                ),
            )
            WorkflowValue.Documents(
                merged.map { result ->
                    dev.androml.core.workflow.WorkflowDocument(
                        title = result.result.chunk.title,
                        sourceLabel = "${result.nodeId.value} · ${result.result.chunk.sourceLabel}",
                        text = result.result.chunk.text,
                    )
                },
            )
        },
        tool = { node, value ->
            val arguments = (value as? WorkflowValue.JsonValue)?.value as? JsonObject
                ?: throw WorkflowNodeExecutionException("tool workflow node requires JSON input")
            when (
                val outcome = ToolExecutor(tools).execute(
                    toolId = node.toolId,
                    arguments = arguments,
                    context = ToolExecutionContext(grantedScopes = setOf(DEVICE_READ_SCOPE)),
                )
            ) {
                is ToolExecutionOutcome.Completed -> WorkflowValue.JsonValue(outcome.result)
                is ToolExecutionOutcome.ApprovalRequired -> throw WorkflowNodeExecutionException(
                    "tool approval is required; add an Approval node before this tool",
                )
                is ToolExecutionOutcome.Denied -> throw WorkflowNodeExecutionException(outcome.reason)
                is ToolExecutionOutcome.Failed -> throw WorkflowNodeExecutionException(outcome.reason)
            }
        },
        approval = { node, _ ->
            if (approvalKey.nodeId == node.id) WorkflowApprovalDecision.Approved
            else WorkflowApprovalDecision.Pending
        },
    )

    private fun transcriptText(transcript: AgentTranscript): String = transcript.messages.joinToString("\n") { message ->
        when (message) {
            is AgentMessage.System -> "system: ${message.text}"
            is AgentMessage.User -> "user: ${message.text}"
            is AgentMessage.Assistant -> "assistant: ${message.text}"
            is AgentMessage.AssistantToolCall -> "assistant tool call ${message.toolId.value}: ${message.arguments}"
            is AgentMessage.Tool -> "tool ${message.toolId.value}: ${message.result}"
        }
    }.take(64 * 1024)

    private fun buildDeviceInfo(profile: DeviceProfile) = buildJsonObject {
        put("device", profile.deviceName)
        put("android_api", profile.androidApi)
        put("cpu_cores", profile.cpuCoreCount)
        put("available_memory_bytes", profile.availableMemoryBytes ?: 0L)
        put("charging", profile.isCharging)
        put("thermal_status", profile.thermalStatus.name)
        put("has_vulkan", profile.hasVulkan)
    }

    private data class ApprovalKey(
        val runId: RunId,
        val nodeId: NodeId?,
    )

    companion object {
        const val LOCAL_AGENT_KEY = "local-agent"
        const val MODEL_STAGE = "model"
        private val DEVICE_INFO_TOOL = ToolId.parse("device.info")
        private val DEVICE_READ_SCOPE = ToolScope.parse("device.read")
    }
}
