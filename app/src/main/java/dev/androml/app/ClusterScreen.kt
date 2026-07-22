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
import dev.androml.cluster.core.ClusterPairingInviteIssuer
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
import java.util.Base64
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
    controller: ClusterController,
    discovery: ClusterDiscoveryController,
) {
    val peers by repository.observe().collectAsState(initial = emptyList())
    val discoveredServices by discovery.services.collectAsState()
    val listenerState by controller.state.collectAsState()
    var localIdentity by remember { mutableStateOf<TlsIdentitySummary?>(null) }
    var peerId by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8788") }
    var certificateText by remember { mutableStateOf("") }
    var modelHashes by remember { mutableStateOf("") }
    var availableRamBytes by remember { mutableStateOf("0") }
    var listenerPort by remember { mutableStateOf("8789") }
    var advertisedHost by remember { mutableStateOf("") }
    var pairingPayload by remember { mutableStateOf("") }
    var generatedPairingPayload by remember { mutableStateOf<String?>(null) }
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
                controller.stop()
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

    fun importPairingPayload() {
        try {
            val invite = ClusterPairingInviteIssuer().decodeQrPayload(pairingPayload.trim())
            require(invite.expiresAtEpochMillis > System.currentTimeMillis()) { "pairing invite has expired" }
            peerId = invite.peerId.value
            host = invite.endpoint.host
            port = invite.endpoint.port.toString()
            certificateText = Base64.getEncoder().encodeToString(
                Base64.getUrlDecoder().decode(invite.certificateDerBase64),
            )
            message = "Invite verified for ${invite.peerId.value}; review the endpoint and pin it below"
        } catch (error: Throwable) {
            message = error.message?.take(256) ?: "Pairing invite could not be decoded"
        }
    }

    fun generatePairingPayload() {
        val hostValue = advertisedHost.trim()
        val parsedPort = listenerPort.toIntOrNull()
        if (hostValue.isBlank()) {
            message = "Enter a LAN hostname or IP that the other phone can reach"
            return
        }
        if (parsedPort == null || parsedPort !in 1024..65_535) {
            message = "Listener port must be between 1024 and 65535"
            return
        }
        busy = true
        message = null
        scope.launch {
            try {
                generatedPairingPayload = withContext(Dispatchers.IO) {
                    controller.createPairingInvite(hostValue, parsedPort)
                }
                message = "Invite created; it expires in five minutes and can be used once"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                message = error.message?.take(256) ?: "Pairing invite could not be created"
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
                controller.stop()
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
                controller.stop()
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

    fun refreshCapabilities() {
        val candidates = peers.filter { it.peer.paired && !it.peer.revoked }
        if (candidates.isEmpty()) {
            message = "No active peers need a capability refresh"
            return
        }
        busy = true
        message = null
        scope.launch {
            try {
                val failures = mutableListOf<String>()
                var refreshed = 0
                withContext(Dispatchers.IO) {
                    candidates.forEach { peer ->
                        try {
                            controller.refreshPeer(peer.peer.id)
                            refreshed += 1
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            failures += "${peer.peer.displayName}: ${error.message ?: "unavailable"}"
                        }
                    }
                }
                message = if (failures.isEmpty()) {
                    "Refreshed capabilities from $refreshed peer(s)"
                } else {
                    "Refreshed $refreshed peer(s); ${failures.joinToString(limit = 2)}"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                message = error.message?.take(256) ?: "Capabilities could not be refreshed"
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
                    Text("Cluster listener", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Enable the local mTLS endpoint after pairing at least one phone. Verified whole-request inference replicas, distributed RAG fan-out, and workflow-stage placement use the same trusted peer ledger.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    when (val state = listenerState) {
                        ClusterControllerState.Disabled -> {
                            Text("Disabled", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        is ClusterControllerState.Running -> {
                            Text(
                                "Listening on ${state.host}:${state.port} · ${state.pairedPeerCount} trusted peer(s)",
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        is ClusterControllerState.Failed -> {
                            Text("Failed: ${state.message}", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = listenerPort,
                            onValueChange = { listenerPort = it.filter(Char::isDigit).take(5) },
                            modifier = Modifier.weight(1f),
                            label = { Text("Listener port") },
                            singleLine = true,
                            enabled = listenerState !is ClusterControllerState.Running && !busy,
                        )
                        Button(
                            onClick = {
                                busy = true
                                message = null
                                scope.launch {
                                    try {
                                        if (listenerState is ClusterControllerState.Running) {
                                            controller.stop()
                                            message = "Cluster listener stopped"
                                        } else {
                                            val parsedPort = listenerPort.toIntOrNull()
                                                ?: throw IllegalArgumentException("listener port must be a number")
                                            require(parsedPort in 1024..65535) {
                                                "listener port must be between 1024 and 65535"
                                            }
                                            val nextState = withContext(Dispatchers.IO) {
                                                controller.start(parsedPort)
                                            }
                                            if (nextState is ClusterControllerState.Failed) {
                                                message = nextState.message
                                            } else {
                                                message = "Cluster listener enabled"
                                            }
                                        }
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Throwable) {
                                        message = error.message?.take(256) ?: "Cluster listener could not be changed"
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically),
                        ) {
                            Text(if (listenerState is ClusterControllerState.Running) "Stop" else "Start")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = advertisedHost,
                        onValueChange = { advertisedHost = it.take(253) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Advertised LAN host") },
                        placeholder = { Text("192.168.1.22 or phone.local") },
                        supportingText = { Text("Used in generated invites; do not use 127.0.0.1 for another phone") },
                        singleLine = true,
                        enabled = !busy,
                    )
                    Button(onClick = ::generatePairingPayload, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Generate one-time pairing invite")
                    }
                    generatedPairingPayload?.let { payload ->
                        Text("Share this QR/deep-link payload", style = MaterialTheme.typography.labelLarge)
                        SelectionContainer { Text(payload, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("LAN discovery", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Find AndroML listeners on this Wi-Fi network. Discovery never trusts a peer; import its invite and pin the certificate before use.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { discovery.startDiscovery() }, enabled = !busy) { Text("Scan") }
                        TextButton(onClick = { discovery.stopDiscovery() }, enabled = discoveredServices.isNotEmpty()) { Text("Clear") }
                    }
                    discoveredServices.forEach { service ->
                        Text("${service.serviceName} · ${service.host}:${service.port}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (discoveredServices.isEmpty()) {
                        Text("No untrusted listeners discovered yet", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
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
                        Text("Node ID: ${clusterNodeId(localIdentity!!.fingerprint).value}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Use this exact node ID when pairing this phone from another device.",
                            style = MaterialTheme.typography.bodySmall,
                        )
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
                        value = pairingPayload,
                        onValueChange = { pairingPayload = it.take(32_000) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("QR/deep-link invite (optional)") },
                        supportingText = { Text("Import fills the peer endpoint and certificate; verify them before pinning") },
                        minLines = 3,
                        enabled = !busy,
                    )
                    Button(onClick = ::importPairingPayload, enabled = !busy && pairingPayload.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text("Import and verify invite")
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = peerId,
                        onValueChange = { peerId = it.take(64) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Peer ID") },
                        placeholder = { Text("node-7f3a…") },
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
        item {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Paired peers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = ::refreshCapabilities, enabled = !busy && peers.isNotEmpty()) {
                    Text("Refresh capabilities")
                }
            }
        }
        if (peers.isEmpty()) {
            item { Text("No peers are paired. Pair a phone before starting the cluster listener.") }
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
