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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.androml.cluster.core.ClusterPeer
import dev.androml.cluster.core.ClusterWorkload
import dev.androml.cluster.core.NodeCapabilities
import dev.androml.cluster.core.PeerEndpoint
import dev.androml.cluster.core.PeerId
import dev.androml.cluster.core.StoredClusterPeer
import dev.androml.core.database.ClusterPeerRepository
import dev.androml.core.security.TlsIdentityStore
import dev.androml.core.security.TlsIdentitySummary
import dev.androml.core.security.X509CertificateCodec
import dev.androml.core.security.summary
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ClusterScreen(
    modifier: Modifier = Modifier,
    repository: ClusterPeerRepository,
    tlsIdentityStore: TlsIdentityStore,
) {
    val peers by repository.observe().collectAsState(initial = emptyList())
    var localIdentity by remember { mutableStateOf<TlsIdentitySummary?>(null) }
    var peerId by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8788") }
    var certificateText by remember { mutableStateOf("") }
    var modelHashes by remember { mutableStateOf("") }
    var availableRamBytes by remember { mutableStateOf("0") }
    var selectedWorkloads by remember {
        mutableStateOf(setOf(ClusterWorkload.InferenceReplica, ClusterWorkload.WorkflowStage, ClusterWorkload.RagSearch))
    }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(tlsIdentityStore) {
        try {
            localIdentity = withContext(Dispatchers.IO) {
                tlsIdentityStore.loadOrCreate(
                    alias = CLUSTER_TLS_ALIAS,
                    subjectName = CLUSTER_TLS_SUBJECT,
                ).summary()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            message = "Cluster identity could not be loaded"
        }
    }

    fun pairPeer() {
        busy = true
        message = null
        scope.launch {
            try {
                val stored = withContext(Dispatchers.IO) {
                    val certificate = X509CertificateCodec.decode(certificateText)
                    val now = System.currentTimeMillis()
                    val parsedPeerId = PeerId.parse(peerId.trim().lowercase(Locale.ROOT))
                    val parsedPort = port.toIntOrNull()
                        ?: throw IllegalArgumentException("peer port must be a number")
                    val parsedRam = availableRamBytes.toLongOrNull()
                        ?: throw IllegalArgumentException("available RAM must be a number")
                    val parsedHashes = modelHashes
                        .split(',', '\n', ' ', '\t')
                        .filter(String::isNotBlank)
                        .map { dev.androml.cluster.core.ContentHash.parse(it.trim().lowercase(Locale.ROOT)) }
                        .toSet()
                    val peer = ClusterPeer(
                        id = parsedPeerId,
                        fingerprint = X509CertificateCodec.fingerprint(certificate),
                        displayName = displayName.trim().ifBlank { parsedPeerId.value },
                        endpoint = PeerEndpoint(host.trim(), parsedPort),
                        pairedAtEpochMillis = now,
                        certificateExpiresAtEpochMillis = certificate.notAfter.time,
                        paired = true,
                        capabilities = NodeCapabilities(
                            supportedWorkloads = selectedWorkloads,
                            modelHashes = parsedHashes,
                            maxConcurrentJobs = 1,
                            availableRamBytes = parsedRam,
                            queueDepth = 0,
                            lastSeenEpochMillis = 0L,
                        ),
                    )
                    repository.upsert(StoredClusterPeer(peer, certificate.encoded))
                    peer
                }
                message = "Paired ${stored.displayName}; waiting for its first signed heartbeat"
                certificateText = ""
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                message = error.message?.take(256) ?: "Peer could not be paired"
            } finally {
                busy = false
            }
        }
    }

    fun revokePeer(peer: StoredClusterPeer) {
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { repository.revoke(peer.peer.id) }
                message = "${peer.peer.displayName} revoked; its certificate can no longer authorize work"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                message = error.message?.take(256) ?: "Peer could not be revoked"
            } finally {
                busy = false
            }
        }
    }

    fun removePeer(peer: StoredClusterPeer) {
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { repository.remove(peer.peer.id) }
                message = "${peer.peer.displayName} and its stored certificate were removed"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                message = error.message?.take(256) ?: "Peer could not be removed"
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
            Text("Cluster", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Pair trusted phones, inspect capability advertisements, and prepare secure whole-request replica/workflow/RAG placement. WAN federation and tensor sharding are not enabled.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("This phone", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    if (localIdentity == null) {
                        CircularProgressIndicator()
                    } else {
                        Text("mTLS identity: ${localIdentity!!.alias}", fontWeight = FontWeight.Bold)
                        Text("SHA-256 certificate fingerprint", style = MaterialTheme.typography.labelMedium)
                        SelectionContainer { Text(localIdentity!!.fingerprint.value, style = MaterialTheme.typography.bodySmall) }
                        Text(
                            "Valid until ${Instant.ofEpochMilli(localIdentity!!.notAfterEpochMillis)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "The private key stays in Android Keystore. Share only the public certificate when pairing another phone.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Pair a phone manually", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Paste the peer's PEM certificate or base64 DER. The fingerprint is derived locally and the certificate is pinned before any job can be sent.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = peerId,
                        onValueChange = { peerId = it.take(64) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Peer ID") },
                        placeholder = { Text("pixel-2") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it.take(128) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Display name") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it.take(253) },
                            modifier = Modifier.weight(1f),
                            label = { Text("LAN host") },
                            placeholder = { Text("192.168.1.22") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter(Char::isDigit).take(5) },
                            modifier = Modifier.width(112.dp),
                            label = { Text("Port") },
                            singleLine = true,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = certificateText,
                        onValueChange = { certificateText = it.take(64 * 1024) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Peer certificate") },
                        minLines = 5,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = modelHashes,
                        onValueChange = { modelHashes = it.take(16 * 65) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Known model SHA-256 hashes (optional)") },
                        supportingText = { Text("Comma, space, or newline separated; leave empty until the peer advertises capabilities.") },
                        minLines = 2,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = availableRamBytes,
                        onValueChange = { availableRamBytes = it.filter(Char::isDigit).take(20) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Advertised available RAM (bytes)") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Allowed work types", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ClusterWorkload.entries.forEach { workload ->
                            FilterChip(
                                selected = workload in selectedWorkloads,
                                onClick = {
                                    selectedWorkloads = if (workload in selectedWorkloads) {
                                        selectedWorkloads - workload
                                    } else {
                                        selectedWorkloads + workload
                                    }
                                },
                                label = { Text(workload.displayName()) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = ::pairPeer, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "Saving…" else "Pin peer certificate")
                    }
                }
            }
        }
        message?.let { currentMessage ->
            item { Text(currentMessage, color = MaterialTheme.colorScheme.primary) }
        }
        item { Text("Paired peers", style = MaterialTheme.typography.titleMedium) }
        if (peers.isEmpty()) {
            item { Text("No peers are paired. The cluster listener remains unavailable until a peer is trusted and a workload bridge is enabled.") }
        } else {
            items(peers, key = { it.peer.id.value }) { stored ->
                ClusterPeerCard(
                    peer = stored,
                    busy = busy,
                    onRevoke = { revokePeer(stored) },
                    onRemove = { removePeer(stored) },
                )
            }
        }
    }
}

@Composable
private fun ClusterPeerCard(
    peer: StoredClusterPeer,
    busy: Boolean,
    onRevoke: () -> Unit,
    onRemove: () -> Unit,
) {
    val domain = peer.peer
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(domain.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("${domain.id.value} · ${domain.endpoint.host}:${domain.endpoint.port}", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    when {
                        domain.revoked -> "Revoked"
                        !domain.paired -> "Unpaired"
                        domain.capabilities.lastSeenEpochMillis == 0L -> "Awaiting heartbeat"
                        else -> "Paired"
                    },
                    color = if (domain.revoked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text("Certificate fingerprint", style = MaterialTheme.typography.labelMedium)
            SelectionContainer { Text(domain.fingerprint.value, style = MaterialTheme.typography.bodySmall) }
            Text(
                "Workloads: ${domain.capabilities.supportedWorkloads.joinToString { it.displayName() }} · ${domain.capabilities.modelHashes.size} model hash(es)",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Certificate expires ${Instant.ofEpochMilli(domain.certificateExpiresAtEpochMillis)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                TextButton(onClick = onRevoke, enabled = !busy && !domain.revoked) { Text("Revoke") }
                TextButton(onClick = onRemove, enabled = !busy) { Text("Remove trust") }
            }
        }
    }
}

private const val CLUSTER_TLS_ALIAS = "cluster-node"
private const val CLUSTER_TLS_SUBJECT = "AndroML cluster node"

private fun ClusterWorkload.displayName(): String = when (this) {
    ClusterWorkload.InferenceReplica -> "Inference"
    ClusterWorkload.WorkflowStage -> "Workflow"
    ClusterWorkload.RagSearch -> "RAG"
}
