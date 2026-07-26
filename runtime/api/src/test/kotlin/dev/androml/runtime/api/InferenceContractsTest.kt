package dev.androml.runtime.api

import dev.androml.core.model.ModelRequirements
import dev.androml.core.model.ModelWorkload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceContractsTest {
    @Test
    fun acceptsBoundedFloatTensorInput() {
        val input = TensorInput(
            data = ByteArray(2 * 3 * TensorDataType.Float32.byteSize),
            shape = longArrayOf(1, 2, 3),
        )
        val request = InferenceRequest(
            id = InferenceRequestId.parse("tensor-1"),
            prompt = "image",
            maxNewTokens = 1,
            temperature = 0.0,
            tensorInput = input,
        )

        assertEquals(6L, request.tensorInput?.elementCount)
        assertArrayEquals(input.data, request.tensorInput?.nativeBuffer()?.let { buffer ->
            ByteArray(buffer.remaining()).also(buffer::get)
        })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTensorShapeByteLengthMismatch() {
        TensorInput(
            data = ByteArray(4),
            shape = longArrayOf(2),
            dataType = TensorDataType.Float32,
        )
    }

    @Test
    fun fakeRuntimeEmitsBoundedOrderedEvents() {
        val session = FakeRuntimeAdapter().openSession(
            ModelRequirements(ModelWorkload.TextGeneration, weightBytes = 1L, contextTokens = 64),
            RuntimeConfiguration(cpuThreads = 2, contextTokens = 64, useAcceleration = false),
        )
        val request = InferenceRequest(
            id = InferenceRequestId.parse("request-1"),
            prompt = "hello world",
            maxNewTokens = 8,
            temperature = 0.7,
        )
        val events = buildList {
            session.generate(request) { add(it) }
        }

        assertTrue(events.first() is InferenceEvent.Started)
        assertTrue(events.drop(1).dropLast(1).all { it is InferenceEvent.Token })
        assertTrue(events.last() is InferenceEvent.Completed)
        assertEquals(request.id, events.first().requestId)
        assertEquals(request.id, events.last().requestId)
        session.close()
    }

    @Test
    fun cancellationBeforeGenerationProducesNoTokens() {
        val session = FakeRuntimeAdapter().openSession(
            ModelRequirements(ModelWorkload.TextGeneration, weightBytes = 1L, contextTokens = 64),
            RuntimeConfiguration(cpuThreads = 2, contextTokens = 64, useAcceleration = false),
        )
        val request = InferenceRequest(
            id = InferenceRequestId.parse("request-2"),
            prompt = "hello world",
            maxNewTokens = 8,
            temperature = 0.7,
        )
        session.cancel(request.id)
        val events = buildList {
            session.generate(request) { add(it) }
        }

        assertTrue(events[1] is InferenceEvent.Cancelled)
        assertTrue(events.none { it is InferenceEvent.Token })
        session.close()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedPromptsBeforeRuntime() {
        InferenceRequest(
            id = InferenceRequestId.parse("request-1"),
            prompt = "x".repeat(InferenceRequest.MAX_PROMPT_CHARS + 1),
            maxNewTokens = 1,
            temperature = 0.0,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteTemperature() {
        InferenceRequest(
            id = InferenceRequestId.parse("request-1"),
            prompt = "hello",
            maxNewTokens = 1,
            temperature = Double.NaN,
        )
    }
}
