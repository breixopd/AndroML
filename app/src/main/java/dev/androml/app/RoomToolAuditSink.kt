package dev.androml.app

import dev.androml.core.database.ToolAuditDao
import dev.androml.core.database.ToolAuditEntity
import dev.androml.core.tools.ToolAuditEvent
import dev.androml.core.tools.ToolAuditSink
import java.util.UUID

/** Persists only the hashes and policy metadata needed for a reviewable tool audit trail. */
class RoomToolAuditSink(
    private val dao: ToolAuditDao,
) : ToolAuditSink {
    override suspend fun append(event: ToolAuditEvent) {
        dao.insert(
            ToolAuditEntity(
                eventId = UUID.randomUUID().toString(),
                eventType = event.eventType,
                toolId = event.toolId.value,
                sideEffect = event.sideEffect.name,
                argumentHash = event.argumentHash,
                resultHash = event.resultHash,
                success = event.success,
                occurredAtEpochMillis = event.occurredAtEpochMillis,
            ),
        )
    }
}
