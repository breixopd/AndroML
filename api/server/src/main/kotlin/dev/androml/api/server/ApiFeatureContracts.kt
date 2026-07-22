package dev.androml.api.server

import kotlinx.serialization.json.JsonObject

data class ApiRagSearchRequest(
    val collectionId: String,
    val query: String,
    val topK: Int = 8,
) {
    init {
        require(collectionId.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "collection_id is invalid" }
        require(query.isNotBlank() && query.length <= 16_384) { "query is invalid" }
        require(topK in 1..100) { "top_k is out of bounds" }
    }
}

data class ApiRagResult(
    val nodeId: String,
    val title: String,
    val source: String,
    val text: String,
    val score: Double,
    val lexicalScore: Double = 0.0,
    val semanticScore: Double = 0.0,
    val excerptHash: String? = null,
    val startOffset: Int? = null,
    val endOffset: Int? = null,
    val page: Int? = null,
    val section: String? = null,
) {
    init {
        require(nodeId.isNotBlank() && nodeId.length <= 128) { "RAG node ID is invalid" }
        require(title.isNotBlank() && title.length <= 512) { "RAG title is invalid" }
        require(source.isNotBlank() && source.length <= 1_024) { "RAG source is invalid" }
        require(text.isNotBlank() && text.length <= 1 * 1024 * 1024) { "RAG text is invalid" }
        require(score.isFinite()) { "RAG score is invalid" }
        require(lexicalScore.isFinite() && semanticScore.isFinite()) { "RAG component scores are invalid" }
        require(excerptHash == null || excerptHash.matches(Regex("[a-f0-9]{64}"))) {
            "RAG excerpt hash is invalid"
        }
        require(startOffset == null || startOffset >= 0) { "RAG start offset is invalid" }
        require(endOffset == null || endOffset >= (startOffset ?: 0)) { "RAG end offset is invalid" }
        require(page == null || page > 0) { "RAG page is invalid" }
        require(section == null || section.length <= 256) { "RAG section is too long" }
    }
}

data class ApiRagSearchResponse(val results: List<ApiRagResult>) {
    init {
        require(results.size <= 100) { "too many RAG results" }
    }
}

data class ApiWorkflowInfo(
    val workflowId: String,
    val version: Int,
    val nodeCount: Int,
    val edgeCount: Int,
)

data class ApiWorkflowRunRequest(
    val workflowId: String,
    val version: Int,
    val input: String,
) {
    init {
        require(workflowId.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "workflow_id is invalid" }
        require(version in 1..1_000_000) { "workflow version is out of bounds" }
        require(input.isNotBlank() && input.length <= 64 * 1024) { "workflow input is invalid" }
    }
}

data class ApiWorkflowRunResponse(
    val runId: String,
    val status: String,
    val output: String? = null,
    val error: String? = null,
    val waitingForNode: String? = null,
) {
    init {
        require(runId.length in 1..128) { "workflow run ID is invalid" }
        require(status.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,31}"))) { "workflow status is invalid" }
        require(output == null || output.length <= 768 * 1024) { "workflow output is too large" }
        require(error == null || error.length <= 512) { "workflow error is too large" }
        require(waitingForNode == null || waitingForNode.length <= 64) { "workflow node ID is invalid" }
    }
}

data class ApiToolInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val sideEffect: String,
    val scopes: List<String>,
)

data class ApiToolInvocationRequest(
    val toolId: String,
    val arguments: JsonObject,
)

data class ApiToolInvocationResponse(
    val status: String,
    val result: JsonObject? = null,
    val reason: String? = null,
    val approvalId: String? = null,
)

data class ApiAgentInfo(
    val id: String,
    val displayName: String,
)

data class ApiAgentInvocationRequest(
    val agentId: String,
    val prompt: String,
) {
    init {
        require(agentId.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "agent_id is invalid" }
        require(prompt.isNotBlank() && prompt.length <= 64 * 1024) { "prompt is invalid" }
    }
}

data class ApiAgentInvocationResponse(
    val status: String,
    val output: String? = null,
    val error: String? = null,
    val approvalId: String? = null,
) {
    init {
        require(status.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,31}"))) { "agent status is invalid" }
        require(output == null || output.length <= 256 * 1024) { "agent output is too large" }
        require(error == null || error.length <= 512) { "agent error is too large" }
        require(approvalId == null || approvalId.length in 1..128) { "approval ID is invalid" }
    }
}

data class ApiClusterStatus(
    val enabled: Boolean,
    val nodeId: String?,
    val pairedPeerCount: Int,
)

data class ApiAuditEvent(
    val eventId: String,
    val eventType: String,
    val toolId: String,
    val sideEffect: String,
    val argumentHash: String,
    val resultHash: String?,
    val success: Boolean,
    val occurredAtEpochMillis: Long,
)

class ApiFeatureUnavailableException : IllegalStateException("API feature is not enabled")

interface ApiFeatureGateway {
    suspend fun ragSearch(request: ApiRagSearchRequest): ApiRagSearchResponse = unavailable()

    suspend fun listWorkflows(): List<ApiWorkflowInfo> = unavailable()

    suspend fun runWorkflow(request: ApiWorkflowRunRequest): ApiWorkflowRunResponse = unavailable()

    suspend fun listTools(): List<ApiToolInfo> = unavailable()

    suspend fun invokeTool(request: ApiToolInvocationRequest): ApiToolInvocationResponse = unavailable()

    suspend fun listAgents(): List<ApiAgentInfo> = unavailable()

    suspend fun invokeAgent(request: ApiAgentInvocationRequest): ApiAgentInvocationResponse = unavailable()

    suspend fun clusterStatus(): ApiClusterStatus = unavailable()

    suspend fun listAuditEvents(limit: Int = 100): List<ApiAuditEvent> = unavailable()

    companion object {
        val Empty: ApiFeatureGateway = object : ApiFeatureGateway {}
    }
}

private suspend fun <T> unavailable(): T = throw ApiFeatureUnavailableException()
