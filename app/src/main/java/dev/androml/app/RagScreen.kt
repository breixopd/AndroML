package dev.androml.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.androml.core.database.RagRepository
import dev.androml.core.files.FileArtifactStore
import dev.androml.cluster.core.ClusterRagSearchTask
import dev.androml.core.rag.CollectionId
import dev.androml.core.rag.DeterministicChunker
import dev.androml.core.rag.RagDocument
import dev.androml.core.rag.RetrievalQuery
import dev.androml.core.rag.TextChunk
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RagScreen(
    modifier: Modifier = Modifier,
    repository: RagRepository,
    artifactStore: FileArtifactStore,
    clusterController: ClusterController,
) {
    val collections by repository.observeCollections().collectAsState(initial = emptyList())
    var selectedCollectionId by remember { mutableStateOf<String?>(null) }
    var collectionName by remember { mutableStateOf("Phone notes") }
    var collectionId by remember { mutableStateOf("phone-notes") }
    var title by remember { mutableStateOf("Test note") }
    var sourceLabel by remember { mutableStateOf("manual://phone") }
    var documentText by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<RagScreenResult>>(emptyList()) }
    var searchPairedPhones by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (selectedCollectionId == null && collections.isNotEmpty()) {
        selectedCollectionId = collections.first().collectionId
    }
    if (selectedCollectionId != null && collections.none { it.collectionId == selectedCollectionId }) {
        selectedCollectionId = collections.firstOrNull()?.collectionId
    }

    fun createCollection() {
        busy = true
        message = null
        scope.launch {
            try {
                val id = CollectionId.parse(collectionId.trim().lowercase(Locale.ROOT))
                withContext(Dispatchers.IO) {
                    repository.upsertCollection(id, collectionName.trim(), embeddingModelKey = null)
                }
                selectedCollectionId = id.value
                message = "Collection ready"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                message = error.message?.take(256) ?: "Collection could not be created"
            } finally {
                busy = false
            }
        }
    }

    fun ingestDocument() {
        val targetCollection = selectedCollectionId
        if (targetCollection == null) {
            message = "Create or select a collection first"
            return
        }
        if (documentText.isBlank()) {
            message = "Enter some document text first"
            return
        }
        busy = true
        message = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val document = RagDocument(
                        collectionId = CollectionId.parse(targetCollection),
                        title = title.trim().ifBlank { "Untitled note" },
                        sourceLabel = sourceLabel.trim().ifBlank { "manual://phone" },
                        text = documentText.take(RagDocument.MAX_DOCUMENT_CHARS),
                    )
                    val normalizedBytes = document.normalizedText.toByteArray(Charsets.UTF_8)
                    val artifactHash = sha256(normalizedBytes)
                    val staged = artifactStore.stage(artifactHash, normalizedBytes.size.toLong())
                    try {
                        staged.copyFrom(ByteArrayInputStream(normalizedBytes))
                        staged.commit()
                        repository.replaceDocument(
                            document = document,
                            chunks = DeterministicChunker().chunk(document),
                            contentArtifactSha256 = artifactHash,
                            byteSize = normalizedBytes.size.toLong(),
                        )
                    } catch (error: Throwable) {
                        staged.discard()
                        throw error
                    }
                }
                message = "Document stored, chunked, and indexed"
                documentText = ""
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                message = error.message?.take(256) ?: "Document could not be indexed"
            } finally {
                busy = false
            }
        }
    }

    fun search() {
        val targetCollection = selectedCollectionId
        if (targetCollection == null || query.isBlank()) {
            message = "Select a collection and enter a search query"
            return
        }
        busy = true
        scope.launch {
            try {
                if (searchPairedPhones) {
                    val merged = withContext(Dispatchers.IO) {
                        clusterController.searchDistributedRag(
                            ClusterRagSearchTask(
                                collectionId = CollectionId.parse(targetCollection),
                                query = RetrievalQuery(query.trim()),
                            ),
                        )
                    }
                    results = merged.map { result ->
                        RagScreenResult(
                            nodeId = result.nodeId.value,
                            chunk = result.result.chunk,
                            fusedScore = result.result.citation.fusedScore,
                        )
                    }
                    message = "${results.size} merged result(s) from ${results.map(RagScreenResult::nodeId).distinct().size} node(s)"
                } else {
                    results = withContext(Dispatchers.IO) {
                        repository.search(CollectionId.parse(targetCollection), query.trim())
                    }.map { chunk -> RagScreenResult("this phone", chunk, null) }
                    message = "${results.size} lexical result(s)"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                message = error.message?.take(256) ?: "Search failed"
            } finally {
                busy = false
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("RAG workspace", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Create local collections, keep source bytes content-addressed, and inspect deterministic lexical retrieval before embedding packs are installed.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Collections", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = collectionName,
                        onValueChange = { collectionName = it.take(256) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Display name") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = collectionId,
                        onValueChange = { collectionId = it.take(64) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Stable collection ID") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = ::createCollection, enabled = !busy) {
                        Text("Create or update collection")
                    }
                    if (collections.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Active collection", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            collections.forEach { collection ->
                                FilterChip(
                                    selected = collection.collectionId == selectedCollectionId,
                                    onClick = { selectedCollectionId = collection.collectionId },
                                    label = { Text(collection.displayName) },
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Index a local note", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it.take(512) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Title") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sourceLabel,
                        onValueChange = { sourceLabel = it.take(1024) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Source label") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = documentText,
                        onValueChange = { documentText = it.take(256 * 1024) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Document text") },
                        minLines = 5,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = ::ingestDocument, enabled = !busy) {
                        Text("Store and index")
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Search", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(16_384) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Query") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    FilterChip(
                        selected = searchPairedPhones,
                        onClick = { searchPairedPhones = !searchPairedPhones },
                        label = { Text("Fan out to paired phones") },
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = ::search, enabled = !busy) { Text("Search collection") }
                }
            }
        }
        message?.let { currentMessage ->
            item { Text(currentMessage, color = MaterialTheme.colorScheme.primary) }
        }
        if (results.isNotEmpty()) {
            item { Text("Results", style = MaterialTheme.typography.titleMedium) }
            items(results, key = { "${it.nodeId}:${it.chunk.id.value}" }) { result ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(result.chunk.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("${result.nodeId} · ${result.chunk.sourceLabel}", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(result.chunk.text)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            buildString {
                                append("chunk ${result.chunk.ordinal + 1} · ")
                                append("${result.chunk.span.startOffset}-${result.chunk.span.endOffset}")
                                result.fusedScore?.let { append(" · score %.3f".format(Locale.ROOT, it)) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        if (collections.isEmpty()) {
            item { Text("No collections yet. Create one to begin the local RAG workflow.") }
        }
    }
}

private data class RagScreenResult(
    val nodeId: String,
    val chunk: TextChunk,
    val fusedScore: Double?,
)

@Suppress("MagicNumber")
private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
