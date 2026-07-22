package dev.androml.app

import dev.androml.core.database.ToolAuditDao
import dev.androml.core.database.ToolAuditEntity
import dev.androml.core.tools.ToolAuditEvent
import dev.androml.core.tools.ToolId
import dev.androml.core.tools.ToolSideEffect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RoomToolAuditSinkTest {
    @Test
    fun persistsOnlyTheHashAndPolicyMetadata() = runBlocking {
        val dao = FakeToolAuditDao()
        RoomToolAuditSink(dao).append(
            ToolAuditEvent(
                eventType = "tool.invocation",
                toolId = ToolId.parse("device.info"),
                sideEffect = ToolSideEffect.Read,
                argumentHash = "a".repeat(64),
                resultHash = "b".repeat(64),
                success = true,
                occurredAtEpochMillis = 42L,
            ),
        )

        val stored = dao.events.single()
        assertEquals("device.info", stored.toolId)
        assertEquals("a".repeat(64), stored.argumentHash)
        assertEquals("b".repeat(64), stored.resultHash)
        assertEquals("Read", stored.sideEffect)
        assertNotNull(stored.eventId)
    }

    private class FakeToolAuditDao : ToolAuditDao {
        val events = mutableListOf<ToolAuditEntity>()

        override suspend fun insert(event: ToolAuditEntity) {
            events += event
        }

        override suspend fun recent(limit: Int): List<ToolAuditEntity> = events.take(limit)

        override suspend fun deleteBefore(cutoffEpochMillis: Long) {
            events.removeAll { it.occurredAtEpochMillis < cutoffEpochMillis }
        }
    }
}
