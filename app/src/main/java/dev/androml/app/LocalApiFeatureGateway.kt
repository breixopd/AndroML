package dev.androml.app

import dev.androml.api.server.ApiAgentInfo
import dev.androml.api.server.ApiAgentInvocationRequest
import dev.androml.api.server.ApiAgentInvocationResponse
import dev.androml.api.server.ApiAuditEvent
import dev.androml.api.server.ApiClusterStatus
import dev.androml.api.server.ApiFeatureGateway
import dev.androml.api.server.ApiRagResult
import dev.androml.api.server.ApiRagSearchRequest
import dev.androml.api.server.ApiRagSearchResponse
import dev.androml.api.server.ApiToolInfo
import dev.androml.api.server.ApiToolInvocationRequest
import dev.androml.api.server.ApiToolInvocationResponse
import dev.androml.api.server.ApiWorkflowInfo
import dev.androml.api.server.ApiWorkflowRunRequest
import dev.androml.api.server.ApiWorkflowRunResponse
import dev.androml.cluster.core.ClusterRagSearchTask
import dev.androml.core.database.WorkflowDefinitionRepository
import dev.androml.core.database.ToolAuditDao
import dev.androml.core.rag.CollectionId
import dev.androml.core.rag.RetrievalQuery
import dev.androml.core.tools.ToolId
import dev.androml.core.workflow.WorkflowId
import dev.androml.core.workflow.WorkflowValue
import dev.androml.core.workflow.WorkflowValueCodec

class LocalApiFeatureGateway(
    private val workflowController: WorkflowController,
    private val workflowRepository: WorkflowDefinitionRepository,
    private val clusterController: ClusterController,
    private val auditDao: ToolAuditDao,
) : ApiFeatureGateway {
    override suspend fun ragSearch(request: ApiRagSearchRequest): ApiRagSearchResponse {
        val results = clusterController.searchDistributedRag(
            ClusterRagSearchTask(
                collectionId = CollectionId.parse(request.collectionId),
                query = RetrievalQuery(request.query, topK = request.topK),
            ),
        )
        return ApiRagSearchResponse(
            results.map { result ->
                ApiRagResult(
                    nodeId = result.nodeId.value,
                    title = result.result.chunk.title,
                    source = result.result.chunk.sourceLabel,
                    text = result.result.chunk.text,
                    score = result.result.citation.fusedScore,
                    lexicalScore = result.result.citation.lexicalScore,
                    semanticScore = result.result.citation.semanticScore,
                    excerptHash = result.result.citation.excerptHash,
                    startOffset = result.result.citation.span.startOffset,
                    endOffset = result.result.citation.span.endOffset,
                    page = result.result.citation.span.page,
                    section = result.result.citation.span.section,
                )
            },
        )
    }

    override suspend fun listWorkflows(): List<ApiWorkflowInfo> = workflowRepository.snapshot().map { definition ->
        ApiWorkflowInfo(
            workflowId = definition.id.value,
            version = definition.version,
            nodeCount = definition.nodes.size,
            edgeCount = definition.edges.size,
        )
    }

    override suspend fun runWorkflow(request: ApiWorkflowRunRequest): ApiWorkflowRunResponse {
        val definition = workflowRepository.load(WorkflowId.parse(request.workflowId), request.version)
            ?: throw IllegalArgumentException("workflow definition does not exist")
        val result = workflowController.run(
            definition = definition,
            input = WorkflowValue.Text(request.input),
        )
        return ApiWorkflowRunResponse(
            runId = result.runId.value,
            status = result.status.name,
            output = result.output?.let(WorkflowValueCodec::encode),
            error = result.error,
            waitingForNode = result.waitingForNode?.value,
        )
    }

    override suspend fun listTools(): List<ApiToolInfo> = workflowController.toolDescriptors().map { descriptor ->
        ApiToolInfo(
            id = descriptor.id.value,
            displayName = descriptor.displayName,
            description = descriptor.description,
            sideEffect = descriptor.sideEffect.name,
            scopes = descriptor.requiredScopes.map { it.value }.sorted(),
        )
    }

    override suspend fun invokeTool(request: ApiToolInvocationRequest): ApiToolInvocationResponse {
        return when (val outcome = workflowController.invokeTool(ToolId.parse(request.toolId), request.arguments)) {
            is dev.androml.core.tools.ToolExecutionOutcome.Completed -> ApiToolInvocationResponse(
                status = "Completed",
                result = outcome.result,
            )
            is dev.androml.core.tools.ToolExecutionOutcome.ApprovalRequired -> ApiToolInvocationResponse(
                status = "ApprovalRequired",
                reason = "tool approval is required",
                approvalId = outcome.approval.approvalId,
            )
            is dev.androml.core.tools.ToolExecutionOutcome.Denied -> ApiToolInvocationResponse(
                status = "Denied",
                reason = outcome.reason,
            )
            is dev.androml.core.tools.ToolExecutionOutcome.Failed -> ApiToolInvocationResponse(
                status = "Failed",
                reason = outcome.reason,
            )
        }
    }

    override suspend fun listAgents(): List<ApiAgentInfo> = if (workflowController.hasAgentModel()) {
        listOf(ApiAgentInfo(WorkflowController.LOCAL_AGENT_KEY, "Local AndroML agent"))
    } else {
        emptyList()
    }

    override suspend fun invokeAgent(request: ApiAgentInvocationRequest): ApiAgentInvocationResponse {
        val result = workflowController.invokeAgent(request.agentId, request.prompt)
        return ApiAgentInvocationResponse(
            status = result.status,
            output = result.output,
            error = result.error,
            approvalId = result.approvalId,
        )
    }

    override suspend fun clusterStatus(): ApiClusterStatus {
        val state = clusterController.currentState()
        val nodeId = clusterController.localNodeId().value
        return when (state) {
            ClusterControllerState.Disabled -> ApiClusterStatus(false, nodeId, 0)
            is ClusterControllerState.Running -> ApiClusterStatus(true, nodeId, state.pairedPeerCount)
            is ClusterControllerState.Failed -> ApiClusterStatus(false, nodeId, 0)
        }
    }

    override suspend fun listAuditEvents(limit: Int): List<ApiAuditEvent> {
        require(limit in 1..500) { "audit limit is out of bounds" }
        return auditDao.recent(limit).map { event ->
            ApiAuditEvent(
                eventId = event.eventId,
                eventType = event.eventType,
                toolId = event.toolId,
                sideEffect = event.sideEffect,
                argumentHash = event.argumentHash,
                resultHash = event.resultHash,
                success = event.success,
                occurredAtEpochMillis = event.occurredAtEpochMillis,
            )
        }
    }
}
