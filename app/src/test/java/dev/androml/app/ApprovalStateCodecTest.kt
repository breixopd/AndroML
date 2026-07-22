package dev.androml.app

import dev.androml.cluster.core.ContentHash
import dev.androml.core.agents.AgentContinuation
import dev.androml.core.agents.AgentMessage
import dev.androml.core.agents.AgentModelDecision
import dev.androml.core.agents.AgentTranscript
import dev.androml.core.database.PendingApprovalDao
import dev.androml.core.database.PendingApprovalEntity
import dev.androml.core.security.SecretStore
import dev.androml.core.tools.ToolApproval
import dev.androml.core.tools.ToolId
import dev.androml.core.tools.ToolScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalStateCodecTest {
    @Test
    fun toolApprovalRoundTripPreservesArgumentsAndHash() {
        val toolId = ToolId.parse("device.write")
        val arguments = buildJsonObject { put("path", "notes.txt") }
        val now = System.currentTimeMillis()
        val approval = approval(toolId, "a".repeat(64)).copy(
            issuedAtEpochMillis = now,
            expiresAtEpochMillis = now + 300_000L,
        )

        val restored = ApprovalStateCodec.decode(
            ApprovalStateCodec.encodeTool(approval, toolId, arguments),
        ) as StoredApproval.Tool

        assertEquals(approval, restored.approval)
        assertEquals(toolId, restored.toolId)
        assertEquals(arguments, restored.arguments)
    }

    @Test
    fun agentContinuationRoundTripPreservesTypedTranscript() {
        val toolId = ToolId.parse("device.write")
        val continuation = AgentContinuation(
            transcript = AgentTranscript(
                listOf(
                    AgentMessage.System("system"),
                    AgentMessage.User("write this"),
                    AgentMessage.AssistantToolCall(toolId, buildJsonObject { put("path", "x") }),
                ),
            ),
            pendingToolCall = AgentModelDecision.CallTool(
                toolId,
                buildJsonObject { put("path", "x") },
            ),
            turns = 2,
            toolCalls = 1,
        )
        val approval = approval(toolId, "b".repeat(64))

        val restored = ApprovalStateCodec.decode(
            ApprovalStateCodec.encodeAgent(approval, ContentHash.parse("c".repeat(64)), continuation),
        ) as StoredApproval.Agent

        assertEquals(approval, restored.approval)
        assertEquals(ContentHash.parse("c".repeat(64)), restored.modelHash)
        assertEquals(continuation, restored.continuation)
    }

    @Test
    fun payloadRejectsOversizedContinuation() {
        assertThrows(IllegalArgumentException::class.java) {
            ApprovalStateCodec.decode("x".repeat(ApprovalStateCodec.MAX_PAYLOAD_CHARS + 1))
        }
    }

    @Test
    fun encryptedToolApprovalSurvivesStoreRecreation() = kotlinx.coroutines.runBlocking {
        val dao = FakePendingApprovalDao()
        val secrets = FakeSecretStore()
        val toolId = ToolId.parse("device.write")
        val arguments = buildJsonObject { put("path", "notes.txt") }
        val now = System.currentTimeMillis()
        val approval = approval(toolId, "a".repeat(64)).copy(
            issuedAtEpochMillis = now,
            expiresAtEpochMillis = now + 300_000L,
        )

        DurableApprovalStore(dao, secrets).saveTool(approval, toolId, arguments)
        val restored = DurableApprovalStore(dao, secrets).consumeTool(approval.approvalId)

        assertEquals(arguments, restored?.arguments)
        assertEquals(approval, restored?.approval)
        assertTrue(secrets.values.isEmpty())
    }

    private fun approval(toolId: ToolId, argumentHash: String): ToolApproval = ToolApproval(
        approvalId = "e".repeat(32),
        toolId = toolId,
        argumentHash = argumentHash,
        scopes = setOf(ToolScope.parse("device.write")),
        issuedAtEpochMillis = 1_000L,
        expiresAtEpochMillis = 301_000L,
        requiresFreshConfirmation = true,
    )

    private class FakePendingApprovalDao : PendingApprovalDao {
        private val rows = linkedMapOf<String, PendingApprovalEntity>()

        override suspend fun find(approvalId: String): PendingApprovalEntity? = rows[approvalId]

        override suspend fun insert(entity: PendingApprovalEntity) {
            rows[entity.approvalId] = entity
        }

        override suspend fun delete(approvalId: String): Int = if (rows.remove(approvalId) != null) 1 else 0

        override suspend fun expired(nowEpochMillis: Long): List<PendingApprovalEntity> =
            rows.values.filter { it.expiresAtEpochMillis <= nowEpochMillis }

        override suspend fun deleteExpired(nowEpochMillis: Long): Int =
            expired(nowEpochMillis).count { rows.remove(it.approvalId) != null }
    }

    private class FakeSecretStore : SecretStore {
        val values = linkedMapOf<String, String>()

        override fun read(name: String): String? = values[name]

        override fun write(name: String, value: String) {
            values[name] = value
        }

        override fun delete(name: String) {
            values.remove(name)
        }
    }
}
