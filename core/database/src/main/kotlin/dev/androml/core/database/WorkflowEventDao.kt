package dev.androml.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.androml.core.workflow.DurableWorkflowEventStore
import dev.androml.core.workflow.RunId
import dev.androml.core.workflow.StoredWorkflowEvent
import dev.androml.core.workflow.WorkflowConcurrencyException
import dev.androml.core.workflow.WorkflowEvent
import dev.androml.core.workflow.WorkflowEventCodec

@Dao
abstract class WorkflowEventDao : DurableWorkflowEventStore {
    @Query("SELECT * FROM workflow_events WHERE runId = :runId ORDER BY sequence ASC")
    protected abstract suspend fun listEntities(runId: String): List<WorkflowEventEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertEntities(events: List<WorkflowEventEntity>)

    @Query("SELECT COUNT(*) FROM workflow_events")
    protected abstract suspend fun countAllEntities(): Long

    @Query("SELECT COUNT(DISTINCT runId) FROM workflow_events")
    protected abstract suspend fun countRuns(): Long

    @Query("SELECT COALESCE(SUM(LENGTH(payload)), 0) FROM workflow_events")
    protected abstract suspend fun totalPayloadCharacters(): Long

    @Transaction
    override suspend fun append(
        runId: RunId,
        expectedSequence: Long,
        events: List<WorkflowEvent>,
    ): List<StoredWorkflowEvent> {
        require(expectedSequence >= 0L) { "expected workflow sequence must be non-negative" }
        require(events.all { it.runId == runId }) { "event run IDs do not match" }
        val existing = listEntities(runId.value)
        val existingKeys = existing.mapTo(mutableSetOf()) { it.idempotencyKey }
        val newEvents = events
            .filterNot { it.idempotencyKey in existingKeys }
            .distinctBy(WorkflowEvent::idempotencyKey)
        if (existing.size.toLong() != expectedSequence && newEvents.isNotEmpty()) {
            throw WorkflowConcurrencyException("workflow event sequence changed")
        }
        val encodedEvents = newEvents.map(WorkflowEventCodec::encode)
        require(existing.size + newEvents.size <= MAX_EVENTS_PER_RUN) {
            "workflow run event limit is exhausted"
        }
        if (existing.isEmpty() && newEvents.isNotEmpty()) {
            require(countRuns() < MAX_STORED_RUNS) { "workflow run storage limit is exhausted" }
        }
        require(countAllEntities() + newEvents.size <= MAX_STORED_EVENTS) {
            "workflow event storage limit is exhausted"
        }
        require(
            totalPayloadCharacters() + encodedEvents.sumOf { it.payload.length.toLong() } <=
                MAX_STORED_PAYLOAD_CHARACTERS,
        ) { "workflow event payload storage limit is exhausted" }
        val stored = newEvents.mapIndexed { index, event ->
            StoredWorkflowEvent(expectedSequence + index + 1L, event)
        }
        if (stored.isNotEmpty()) {
            insertEntities(
                stored.mapIndexed { index, item ->
                    val encoded = encodedEvents[index]
                    WorkflowEventEntity(
                        runId = runId.value,
                        sequence = item.sequence,
                        idempotencyKey = item.event.idempotencyKey,
                        eventType = encoded.eventType,
                        payload = encoded.payload,
                        appendedAtEpochMillis = System.currentTimeMillis(),
                    )
                },
            )
        }
        return stored
    }

    override suspend fun read(runId: RunId): List<StoredWorkflowEvent> =
        listEntities(runId.value).map { entity ->
            StoredWorkflowEvent(
                sequence = entity.sequence,
                event = WorkflowEventCodec.decode(
                    runId = runId,
                    eventType = entity.eventType,
                    payload = entity.payload,
                ),
            )
        }

    private companion object {
        const val MAX_EVENTS_PER_RUN = 512
        const val MAX_STORED_RUNS = 1_024L
        const val MAX_STORED_EVENTS = 8_192L
        const val MAX_STORED_PAYLOAD_CHARACTERS = 32L * 1024 * 1024
    }
}
