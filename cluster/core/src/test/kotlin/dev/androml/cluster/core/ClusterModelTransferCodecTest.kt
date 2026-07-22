package dev.androml.cluster.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterModelTransferCodecTest {
    @Test
    fun chunkAndAckRoundTripPreservesHashOffsetAndMetadata() {
        val chunk = ClusterModelTransferChunk(
            transferId = "transfer-1",
            artifactHash = ContentHash.parse("a".repeat(64)),
            totalSizeBytes = 4,
            offsetBytes = 0,
            chunk = byteArrayOf(1, 2, 3, 4),
            finalChunk = true,
            modelId = "org/model",
            revision = "b".repeat(40),
            path = "model.gguf",
            license = "apache-2.0",
        )
        val restored = ClusterModelTransferCodec.decodeChunk(ClusterModelTransferCodec.encodeChunk(chunk))
        assertEquals(chunk.transferId, restored.transferId)
        assertEquals(chunk.artifactHash, restored.artifactHash)
        assertEquals(chunk.modelId, restored.modelId)
        assertEquals(chunk.revision, restored.revision)
        assertArrayEquals(chunk.chunk, restored.chunk)

        val ack = ClusterModelTransferAck(chunk.transferId, chunk.artifactHash, 4, committed = true)
        assertEquals(ack, ClusterModelTransferCodec.decodeAck(ClusterModelTransferCodec.encodeAck(ack)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAChunkThatClaimsToFinishBeforeTheArtifactEnds() {
        ClusterModelTransferChunk(
            transferId = "transfer-1",
            artifactHash = ContentHash.parse("a".repeat(64)),
            totalSizeBytes = 4,
            offsetBytes = 0,
            chunk = byteArrayOf(1),
            finalChunk = true,
            modelId = "model",
            revision = "b".repeat(40),
            path = "model.gguf",
        )
    }
}
