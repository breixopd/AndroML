package dev.androml.cluster.core

import dev.androml.core.api.CertificateFingerprint
import dev.androml.core.rag.Citation
import dev.androml.core.rag.ChunkId
import dev.androml.core.rag.CollectionId
import dev.androml.core.rag.DocumentId
import dev.androml.core.rag.RetrievalResult
import dev.androml.core.rag.SourceSpan
import dev.androml.core.rag.TextChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterContractsTest {
    private val fingerprint = CertificateFingerprint.parse("a".repeat(64))

    @Test
    fun routesOnlyToPairedCapableAndFreshNodes() {
        val request = inferenceRequest(now = 60_000L)
        val local = node(
            id = "local",
            availableRamBytes = 3_000L,
            queueDepth = 1,
            now = 60_000L,
            isLocal = true,
        )
        val remote = node(
            id = "remote",
            availableRamBytes = 8_000L,
            queueDepth = 0,
            now = 60_000L,
        )
        val unpaired = node(
            id = "unpaired",
            availableRamBytes = 64_000L,
            queueDepth = 0,
            now = 60_000L,
            paired = false,
        )
        val stale = node(
            id = "stale",
            availableRamBytes = 64_000L,
            queueDepth = 0,
            now = 60_000L,
            lastSeen = 0L,
        )

        val decision = ClusterRouter(nowEpochMillis = { 60_000L }).route(
            request = request,
            nodes = listOf(local, remote, unpaired, stale),
        )

        assertEquals(PeerId.parse("remote"), decision.target)
        assertTrue(decision.explanation.any { it.contains("queue") })
        assertTrue(decision.explanation.none { it.contains("unpaired") })
    }

    @Test
    fun idempotentLedgerDoesNotStartSameAttemptTwice() {
        val ledger = InMemoryClusterJobLedger()
        val key = JobAttemptKey(ClusterJobId.parse("job-1"), attempt = 1)

        assertEquals(BeginAttempt.Started, ledger.begin(key))
        assertEquals(BeginAttempt.AlreadyRunning, ledger.begin(key))
        ledger.complete(key, ContentHash.parse("b".repeat(64)))
        assertEquals(BeginAttempt.Completed, ledger.begin(key))
        assertEquals(JobState.Completed, ledger.state(key))
    }

    @Test
    fun distributedRagMergeKeepsNodeIdentityAndStableOrdering() {
        val first = retrieval("a", fusedScore = 0.8)
        val second = retrieval("b", fusedScore = 0.8)
        val merged = DistributedRagMerger().merge(
            topK = 2,
            shards = listOf(
                NodeRetrievalResult(PeerId.parse("node-b"), listOf(second)),
                NodeRetrievalResult(PeerId.parse("node-a"), listOf(first)),
            ),
        )

        assertEquals(listOf("node-a", "node-b"), merged.map { it.nodeId.value })
        assertEquals(CollectionId.parse("docs"), merged.first().result.chunk.collectionId)
    }

    private fun inferenceRequest(now: Long): ClusterRequest = ClusterRequest(
        jobId = ClusterJobId.parse("job-1"),
        attempt = 1,
        workload = ClusterWorkload.InferenceReplica,
        modelKey = "model-a",
        modelHash = ContentHash.parse("c".repeat(64)),
        requiredRamBytes = 2_000L,
        deadlineEpochMillis = now + 10_000L,
        payloadHash = ContentHash.parse("d".repeat(64)),
        idempotencyKey = "job-1:1",
    )

    private fun node(
        id: String,
        availableRamBytes: Long,
        queueDepth: Int,
        now: Long,
        isLocal: Boolean = false,
        paired: Boolean = true,
        lastSeen: Long = now,
    ): ClusterNode = ClusterNode(
        peer = ClusterPeer(
            id = PeerId.parse(id),
            fingerprint = fingerprint,
            displayName = id,
            endpoint = PeerEndpoint("192.168.1.2", 8788),
            pairedAtEpochMillis = 1L,
            certificateExpiresAtEpochMillis = now + 60_000L,
            paired = paired,
            capabilities = NodeCapabilities(
                supportedWorkloads = setOf(ClusterWorkload.InferenceReplica),
                modelHashes = setOf(ContentHash.parse("c".repeat(64))),
                maxConcurrentJobs = 2,
                availableRamBytes = availableRamBytes,
                queueDepth = queueDepth,
                lastSeenEpochMillis = lastSeen,
            ),
        ),
        isLocal = isLocal,
    )

    private fun retrieval(nodeSuffix: String, fusedScore: Double): RetrievalResult {
        val collection = CollectionId.parse("docs")
        val document = DocumentId.parse("docs:${nodeSuffix.repeat(64)}")
        val chunk = TextChunk(
            id = ChunkId.parse(nodeSuffix.repeat(64)),
            documentId = document,
            collectionId = collection,
            title = "Title $nodeSuffix",
            sourceLabel = "source-$nodeSuffix",
            text = "text $nodeSuffix",
            span = SourceSpan(0, 4),
            ordinal = 0,
        )
        return RetrievalResult(
            chunk = chunk,
            citation = Citation(
                documentId = document,
                title = chunk.title,
                sourceLabel = chunk.sourceLabel,
                span = chunk.span,
                excerptHash = "e".repeat(64),
                lexicalScore = fusedScore,
                semanticScore = 0.0,
                fusedScore = fusedScore,
            ),
        )
    }
}
