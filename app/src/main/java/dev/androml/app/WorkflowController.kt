package dev.androml.app

import dev.androml.cluster.core.ClusterWorkflowStageTask
import dev.androml.cluster.core.ClusterInferenceTask
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
import dev.androml.core.model.ModelWorkload
import dev.androml.core.model.ModelFormatClassifier
import dev.androml.core.rag.CollectionId
import dev.androml.core.rag.RetrievalQuery
import dev.androml.core.tools.ToolDescriptor
import dev.androml.core.tools.ToolExecutionContext
import dev.androml.core.tools.ToolExecutionOutcome
import dev.androml.core.tools.ToolExecutor
import dev.androml.core.tools.ToolHandler
import dev.androml.core.tools.ToolId
import dev.androml.core.tools.ToolAuditSink
import dev.androml.core.tools.InMemoryToolAuditSink
import dev.androml.core.tools.ToolProperty
import dev.androml.core.tools.ToolScope
import dev.androml.core.tools.ToolSideEffect
import dev.androml.core.tools.ToolInputSchema
import dev.androml.core.tools.ToolValueType
import dev.androml.core.tools.SafeCalculator
import dev.androml.core.tools.ToolRegistry
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
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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
    private val auditSink: ToolAuditSink = InMemoryToolAuditSink(),
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
        registry.register(
            descriptor = ToolDescriptor(
                id = CALCULATOR_TOOL,
                displayName = "Calculator",
                description = "Evaluates bounded arithmetic without code execution or network access.",
                sideEffect = ToolSideEffect.Read,
                requiredScopes = setOf(CALCULATOR_SCOPE),
                input = ToolInputSchema(
                    properties = mapOf("expression" to ToolProperty(ToolValueType.String, maxLength = 512)),
                    required = setOf("expression"),
                ),
                timeoutSeconds = 2,
                maxResultCharacters = 2 * 1024,
            ),
            handler = ToolHandler { invocation ->
                val expression = invocation.arguments.getValue("expression").toString().trim('"')
                buildJsonObject {
                    put("expression", expression)
                    put("value", SafeCalculator.evaluate(expression))
                }
            },
        )
        registry.register(
            descriptor = ToolDescriptor(
                id = DATE_TIME_TOOL,
                displayName = "Date and time",
                description = "Returns the current UTC time from the local device clock.",
                sideEffect = ToolSideEffect.Read,
                requiredScopes = setOf(DATE_TIME_SCOPE),
                input = ToolInputSchema(emptyMap()),
                timeoutSeconds = 2,
                maxResultCharacters = 2 * 1024,
            ),
            handler = ToolHandler {
                buildJsonObject {
                    put("utc", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                    put("offset", ZoneOffset.UTC.id)
                }
            },
        )
        registry.register(
            descriptor = ToolDescriptor(
                id = RAG_SEARCH_TOOL,
                displayName = "Local RAG search",
                description = "Searches a selected local collection and returns citation-bearing snippets.",
                sideEffect = ToolSideEffect.Read,
                requiredScopes = setOf(RAG_READ_SCOPE),
                input = ToolInputSchema(
                    properties = mapOf(
                        "collection_id" to ToolProperty(ToolValueType.String, maxLength = 64),
                        "query" to ToolProperty(ToolValueType.String, maxLength = 16_384),
                        "top_k" to ToolProperty(ToolValueType.Integer),
                    ),
                    required = setOf("collection_id", "query"),
                ),
                timeoutSeconds = 30,
                maxResultCharacters = 256 * 1024,
            ),
            handler = ToolHandler { invocation ->
                val collectionId = invocation.arguments.getValue("collection_id").toString().trim('"')
                val query = invocation.arguments.getValue("query").toString().trim('"')
                val topK = invocation.arguments["top_k"]?.toString()?.toIntOrNull() ?: 8
                val results = clusterController.searchDistributedRag(
                    dev.androml.cluster.core.ClusterRagSearchTask(
                        collectionId = dev.androml.core.rag.CollectionId.parse(collectionId),
                        query = dev.androml.core.rag.RetrievalQuery(query, topK = topK),
                    ),
                )
                buildJsonObject {
                    put("results", kotlinx.serialization.json.buildJsonArray {
                        results.forEach { result ->
                            add(buildJsonObject {
                                put("node_id", result.nodeId.value)
                                put("title", result.result.chunk.title)
                                put("source", result.result.chunk.sourceLabel)
                                put("text", result.result.chunk.text)
                                put("score", result.result.citation.fusedScore)
                                put("excerpt_hash", result.result.citation.excerptHash)
                            })
                        }
                    })
                }
            },
        )
        registry.register(
            descriptor = ToolDescriptor(
                id = MODEL_INVOKE_TOOL,
                displayName = "Model invocation",
                description = "Runs one verified local or paired model request with bounded output.",
                sideEffect = ToolSideEffect.Read,
                requiredScopes = setOf(MODEL_INVOKE_SCOPE),
                input = ToolInputSchema(
                    properties = mapOf(
                        "model_hash" to ToolProperty(ToolValueType.String, maxLength = 64),
                        "prompt" to ToolProperty(ToolValueType.String, maxLength = 64 * 1024),
                        "max_new_tokens" to ToolProperty(ToolValueType.Integer),
                    ),
                    required = setOf("model_hash", "prompt"),
                ),
                timeoutSeconds = 10 * 60,
                maxResultCharacters = 128 * 1024,
            ),
            handler = ToolHandler { invocation ->
                val modelHash = invocation.arguments.getValue("model_hash").toString().trim('"')
                val prompt = invocation.arguments.getValue("prompt").toString().trim('"')
                val maxNewTokens = invocation.arguments["max_new_tokens"]?.toString()?.toIntOrNull()?.coerceIn(1, 8192) ?: 256
                val modelFile = catalogRepository.fileForArtifact(modelHash)
                    ?: throw IllegalArgumentException("verified model artifact is not installed")
                val runtimeId = ModelFormatClassifier.forPath(modelFile.path)?.runtimeId
                    ?: throw IllegalArgumentException("model format is unsupported")
                val execution = clusterController.executeBestInference(
                    ClusterInferenceTask(
                        modelHash = ContentHash.parse(modelHash),
                        prompt = prompt,
                        maxNewTokens = maxNewTokens,
                        temperature = 0.7,
                        contextTokens = 2_048,
                        kvCacheBytesPerToken = 0L,
                        cpuThreads = deviceProfileProvider().cpuCoreCount.coerceIn(1, 8),
                        useAcceleration = false,
                        runtimeId = runtimeId,
                    ),
                )
                buildJsonObject {
                    put("text", execution.result.text)
                    put("generated_tokens", execution.result.generatedTokens)
                    put("runtime_id", execution.result.runtimeId)
                    put("node_id", execution.placement.target.value)
                }
            },
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
        ToolExecutor(tools, auditSink = auditSink).execute(
            toolId = toolId,
            arguments = arguments,
            context = ToolExecutionContext(
                grantedScopes = tools.descriptors().flatMap { it.requiredScopes }.toSet(),
            ),
        )
    }

    suspend fun hasAgentModel(): Boolean = withContext(Dispatchers.IO) {
        catalogRepository.firstVerifiedArtifactFor(ModelWorkload.TextGeneration) != null
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
        val modelHash = catalogRepository.firstVerifiedArtifactFor(ModelWorkload.TextGeneration)?.value
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
            runners = runners(catalogRepository.firstVerifiedArtifactFor(ModelWorkload.TextGeneration)?.value, approvalKey),
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
                    auditSink = auditSink,
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
                grantedScopes = allToolScopes(),
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
                val outcome = ToolExecutor(tools, auditSink = auditSink).execute(
                    toolId = node.toolId,
                    arguments = arguments,
                    context = ToolExecutionContext(grantedScopes = allToolScopes()),
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

    private fun allToolScopes(): Set<ToolScope> = tools.descriptors()
        .flatMap { it.requiredScopes }
        .toSet()

    private data class ApprovalKey(
        val runId: RunId,
        val nodeId: NodeId?,
    )

    companion object {
        const val LOCAL_AGENT_KEY = "local-agent"
        const val MODEL_STAGE = "model"
        private val DEVICE_INFO_TOOL = ToolId.parse("device.info")
        private val DEVICE_READ_SCOPE = ToolScope.parse("device.read")
        private val CALCULATOR_TOOL = ToolId.parse("calculator.evaluate")
        private val CALCULATOR_SCOPE = ToolScope.parse("calculator.read")
        private val DATE_TIME_TOOL = ToolId.parse("date.time")
        private val DATE_TIME_SCOPE = ToolScope.parse("date.read")
        private val RAG_SEARCH_TOOL = ToolId.parse("rag.search")
        private val RAG_READ_SCOPE = ToolScope.parse("rag.read")
        private val MODEL_INVOKE_TOOL = ToolId.parse("model.invoke")
        private val MODEL_INVOKE_SCOPE = ToolScope.parse("model.invoke")
    }
}
