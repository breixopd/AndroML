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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import dev.androml.core.api.ApiScope
import dev.androml.core.api.ApiKeyRecord
import dev.androml.core.database.ApiKeyRepository
import dev.androml.core.database.ToolAuditDao
import dev.androml.core.database.ToolAuditEntity
import dev.androml.core.security.ApiClientCertificateRecord
import dev.androml.core.security.ApiClientCertificateStore
import dev.androml.core.security.TlsIdentityStore
import dev.androml.core.security.TlsIdentitySummary
import dev.androml.core.security.X509CertificateCodec
import dev.androml.core.security.summary
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ApiScreen(
    modifier: Modifier = Modifier,
    controller: LocalApiController,
    keyRepository: ApiKeyRepository,
    auditDao: ToolAuditDao,
    tlsIdentityStore: TlsIdentityStore,
    clientCertificateStore: ApiClientCertificateStore,
) {
    val apiState by controller.state.collectAsState()
    var keys by remember { mutableStateOf<List<ApiKeyRecord>>(emptyList()) }
    var clientCertificates by remember { mutableStateOf<List<ApiClientCertificateRecord>>(emptyList()) }
    var displayName by remember { mutableStateOf("phone client") }
    var clientCertificateName by remember { mutableStateOf("") }
    var clientCertificateText by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8787") }
    var selectedScopes by remember {
        mutableStateOf(setOf(ApiScope.ModelsRead, ApiScope.Inference))
    }
    var generatedToken by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tlsSummary by remember { mutableStateOf<TlsIdentitySummary?>(null) }
    var tlsCertificatePem by remember { mutableStateOf<String?>(null) }
    var tlsBusy by remember { mutableStateOf(false) }
    var auditEvents by remember { mutableStateOf<List<ToolAuditEntity>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun refreshKeys() {
        scope.launch {
            keys = withContext(Dispatchers.IO) { keyRepository.snapshot() }
        }
    }

    fun refreshClientCertificates() {
        scope.launch {
            clientCertificates = withContext(Dispatchers.IO) { clientCertificateStore.snapshot() }
        }
    }

    fun refreshAuditEvents() {
        scope.launch {
            auditEvents = withContext(Dispatchers.IO) { auditDao.recent(50) }
        }
    }

    LaunchedEffect(keyRepository) {
        try {
            keys = withContext(Dispatchers.IO) { keyRepository.snapshot() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            message = "API key storage could not be read"
        }
    }

    LaunchedEffect(clientCertificateStore) {
        try {
            clientCertificates = withContext(Dispatchers.IO) { clientCertificateStore.snapshot() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            message = "Trusted client certificate storage could not be read"
        }
    }

    LaunchedEffect(auditDao) {
        try {
            refreshAuditEvents()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            message = "Audit history could not be read"
        }
    }

    LaunchedEffect(tlsIdentityStore) {
        try {
            val identity = withContext(Dispatchers.IO) {
                tlsIdentityStore.loadOrCreate(
                    alias = API_TLS_ALIAS,
                    subjectName = API_TLS_SUBJECT,
                )
            }
            tlsSummary = identity.summary()
            tlsCertificatePem = X509CertificateCodec.encodePem(identity.certificate)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            message = "mTLS identity could not be loaded"
        }
    }

    fun createKey() {
        if (selectedScopes.isEmpty()) {
            message = "Select at least one permission"
            return
        }
        busy = true
        message = null
        scope.launch {
            try {
                val generated = withContext(Dispatchers.IO) {
                    keyRepository.create(
                        displayName = displayName.trim().ifBlank { "phone client" },
                        scopes = selectedScopes,
                    )
                }
                generatedToken = generated.plaintextToken
                keys = withContext(Dispatchers.IO) { keyRepository.snapshot() }
                message = "Key created. Copy it now; the secret is never stored or shown again."
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                message = error.message?.take(256) ?: "API key could not be created"
            } finally {
                busy = false
            }
        }
    }

    fun stopServer() {
        controller.stop()
        message = "API stopped"
    }

    fun startLoopbackServer() {
        val running = apiState is LocalApiState.Running
        if (running) {
            stopServer()
            return
        }
        val selectedPort = port.toIntOrNull()
        if (selectedPort == null) {
            message = "Port must be a number between 1024 and 65535"
            return
        }
        busy = true
        message = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    controller.startLoopback(selectedPort)
                }
                if (result is LocalApiState.Failed) message = result.message
                else message = "Loopback API enabled; bearer authentication is required."
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                message = error.message?.take(256) ?: "API server could not start"
            } finally {
                busy = false
            }
        }
    }

    fun startLanServer() {
        if (apiState is LocalApiState.Running) {
            stopServer()
            return
        }
        val selectedPort = port.toIntOrNull()
        if (selectedPort == null) {
            message = "Port must be a number between 1024 and 65535"
            return
        }
        if (clientCertificates.none { it.revokedAtEpochMillis == null }) {
            message = "Pair at least one client certificate before enabling LAN API access"
            return
        }
        busy = true
        message = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { controller.startLan(selectedPort) }
                if (result is LocalApiState.Failed) message = result.message
                else message = "LAN API enabled with mTLS and scoped bearer authentication."
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                message = error.message?.take(256) ?: "LAN API could not start"
            } finally {
                busy = false
            }
        }
    }

    fun addClientCertificate() {
        busy = true
        message = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    clientCertificateStore.add(
                        displayName = clientCertificateName.trim().ifBlank { "LAN client" },
                        certificate = X509CertificateCodec.decode(clientCertificateText),
                    )
                }
                clientCertificateName = ""
                clientCertificateText = ""
                refreshClientCertificates()
                message = "Trusted client certificate added. Restart the LAN API to apply it."
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                message = error.message?.take(256) ?: "Client certificate could not be added"
            } finally {
                busy = false
            }
        }
    }

    fun revokeClientCertificate(record: ApiClientCertificateRecord) {
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { clientCertificateStore.revoke(record.id) }
                if (apiState is LocalApiState.Running) controller.stop()
                refreshClientCertificates()
                message = "${record.displayName} revoked; LAN API stopped until restarted."
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                message = error.message?.take(256) ?: "Client certificate could not be revoked"
            } finally {
                busy = false
            }
        }
    }

    fun removeClientCertificate(record: ApiClientCertificateRecord) {
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { clientCertificateStore.remove(record.id) }
                if (apiState is LocalApiState.Running) controller.stop()
                refreshClientCertificates()
                message = "${record.displayName} removed; LAN API stopped until restarted."
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                message = error.message?.take(256) ?: "Client certificate could not be removed"
            } finally {
                busy = false
            }
        }
    }

    fun rotateTlsIdentity() {
        if (apiState is LocalApiState.Running) {
            message = "Stop the API before rotating its mTLS identity"
            return
        }
        tlsBusy = true
        message = null
        scope.launch {
            try {
                val identity = withContext(Dispatchers.IO) {
                    tlsIdentityStore.delete(API_TLS_ALIAS)
                    tlsIdentityStore.loadOrCreate(
                        alias = API_TLS_ALIAS,
                        subjectName = API_TLS_SUBJECT,
                    )
                }
                tlsSummary = identity.summary()
                tlsCertificatePem = X509CertificateCodec.encodePem(identity.certificate)
                message = "mTLS identity rotated; existing clients must be re-paired"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                message = error.message?.take(256) ?: "mTLS identity could not be rotated"
            } finally {
                tlsBusy = false
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Local API", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Expose the installed runtime to local clients. Loopback is the default; LAN access requires explicitly paired mTLS client certificates plus scoped bearer keys.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Server", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when (val state = apiState) {
                            LocalApiState.Disabled -> "Disabled"
                            is LocalApiState.Running -> "Listening on ${state.host}:${state.port} · ${state.bindMode.name}"
                            is LocalApiState.Failed -> "Failed: ${state.message}"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit).take(5) },
                        enabled = apiState !is LocalApiState.Running && !busy,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API port") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Authentication: scoped bearer API key", style = MaterialTheme.typography.bodySmall)
                    Text("Health checks are unauthenticated; models and inference are not.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    if (apiState is LocalApiState.Running) {
                        Button(
                            onClick = ::stopServer,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Stop API")
                        }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Button(
                                onClick = ::startLoopbackServer,
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (busy) CircularProgressIndicator()
                                else Text("Enable loopback")
                            }
                            OutlinedButton(
                                onClick = ::startLanServer,
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Enable LAN")
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Trusted LAN clients", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Paste an end-entity X.509 client certificate in PEM or base64 DER form. LAN requests still need a scoped bearer key.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = clientCertificateName,
                        onValueChange = { clientCertificateName = it.take(128) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Client name") },
                        singleLine = true,
                        enabled = !busy,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = clientCertificateText,
                        onValueChange = { clientCertificateText = it.take(64 * 1024) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Client certificate") },
                        minLines = 5,
                        maxLines = 10,
                        enabled = !busy,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = ::addClientCertificate,
                        enabled = !busy && clientCertificateText.isNotBlank(),
                    ) {
                        Text("Trust client certificate")
                    }
                    Spacer(Modifier.height(8.dp))
                    if (clientCertificates.isEmpty()) {
                        Text("No LAN client certificates are paired.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        clientCertificates.forEach { certificate ->
                            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                Text(certificate.displayName, style = MaterialTheme.typography.titleSmall)
                                SelectionContainer {
                                    Text(certificate.fingerprint.value, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    when {
                                        certificate.revokedAtEpochMillis != null -> "Revoked"
                                        certificate.isActiveAt(System.currentTimeMillis()) -> "Active until ${Instant.ofEpochMilli(certificate.certificate().notAfter.time)}"
                                        else -> "Expired"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (certificate.revokedAtEpochMillis == null) {
                                        TextButton(
                                            onClick = { revokeClientCertificate(certificate) },
                                            enabled = !busy,
                                        ) { Text("Revoke") }
                                    }
                                    TextButton(
                                        onClick = { removeClientCertificate(certificate) },
                                        enabled = !busy,
                                    ) { Text("Remove") }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("mTLS identity", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "This certificate identifies the API server. LAN clients must pin it and present a trusted end-entity certificate; bearer keys still control API scopes.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    val summary = tlsSummary
                    if (summary == null) {
                        CircularProgressIndicator()
                    } else {
                        Text("Alias: ${summary.alias}", style = MaterialTheme.typography.bodySmall)
                        Text("SHA-256 fingerprint", style = MaterialTheme.typography.labelMedium)
                        SelectionContainer {
                            Text(summary.fingerprint.value, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "Valid until ${Instant.ofEpochMilli(summary.notAfterEpochMillis)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        tlsCertificatePem?.let { certificatePem ->
                            Spacer(Modifier.height(8.dp))
                            Text("Server certificate (public)", style = MaterialTheme.typography.labelMedium)
                            SelectionContainer {
                                Text(certificatePem, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = ::rotateTlsIdentity,
                            enabled = !tlsBusy && apiState !is LocalApiState.Running,
                        ) {
                            Text(if (tlsBusy) "Rotating…" else "Rotate identity")
                        }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Tool audit history",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = ::refreshAuditEvents, enabled = !busy) {
                            Text("Refresh")
                        }
                    }
                    Text(
                        "Only event type, tool ID, policy class, success, timestamps, and one-way hashes are retained.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (auditEvents.isEmpty()) {
                        Text("No tool events recorded yet.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        auditEvents.take(12).forEach { event ->
                            Text(
                                "${event.toolId} · ${event.sideEffect} · ${if (event.success) "ok" else "failed"} · ${Instant.ofEpochMilli(event.occurredAtEpochMillis)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "args ${event.argumentHash.take(16)}…",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Create an API key", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it.take(128) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Display name") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("Permissions", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(ApiScope.ModelsRead, ApiScope.Inference).forEach { apiScope ->
                            FilterChip(
                                selected = apiScope in selectedScopes,
                                onClick = {
                                    selectedScopes = if (apiScope in selectedScopes) {
                                        selectedScopes - apiScope
                                    } else {
                                        selectedScopes + apiScope
                                    }
                                },
                                label = { Text(apiScope.displayName()) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(ApiScope.RagRead, ApiScope.RagWrite).forEach { apiScope ->
                            FilterChip(
                                selected = apiScope in selectedScopes,
                                onClick = {
                                    selectedScopes = if (apiScope in selectedScopes) {
                                        selectedScopes - apiScope
                                    } else {
                                        selectedScopes + apiScope
                                    }
                                },
                                label = { Text(apiScope.displayName()) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(ApiScope.Tools, ApiScope.Agents).forEach { apiScope ->
                            FilterChip(
                                selected = apiScope in selectedScopes,
                                onClick = {
                                    selectedScopes = if (apiScope in selectedScopes) {
                                        selectedScopes - apiScope
                                    } else {
                                        selectedScopes + apiScope
                                    }
                                },
                                label = { Text(apiScope.displayName()) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(ApiScope.Cluster, ApiScope.Admin).forEach { apiScope ->
                            FilterChip(
                                selected = apiScope in selectedScopes,
                                onClick = {
                                    selectedScopes = if (apiScope in selectedScopes) {
                                        selectedScopes - apiScope
                                    } else {
                                        selectedScopes + apiScope
                                    }
                                },
                                label = { Text(apiScope.displayName()) },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = ::createKey, enabled = !busy) {
                        Text("Generate key")
                    }
                }
            }
        }
        generatedToken?.let { token ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Copy this secret now", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text("AndroML stores only a hash. Closing or leaving this screen loses the plaintext token.")
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer { Text(token, style = MaterialTheme.typography.bodySmall) }
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = { generatedToken = null }) { Text("Hide secret") }
                    }
                }
            }
        }
        message?.let { currentMessage ->
            item {
                Text(
                    currentMessage,
                    color = if (currentMessage.contains("could not", ignoreCase = true) ||
                        currentMessage.contains("fail", ignoreCase = true)
                    ) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
        item {
            Text("Stored keys", style = MaterialTheme.typography.titleMedium)
        }
        if (keys.isEmpty()) {
            item { Text("No API keys exist. Create one before enabling the server.") }
        } else {
            items(keys, key = { it.id.value }) { key ->
                ApiKeyCard(
                    key = key,
                    onRevoke = {
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) { keyRepository.revoke(key.id) }
                            }.onSuccess {
                                refreshKeys()
                                message = "Key revoked"
                            }.onFailure { error ->
                                message = error.message?.take(256) ?: "Key could not be revoked"
                            }
                        }
                    },
                )
            }
        }
    }
}

private const val API_TLS_ALIAS = "api-server"
private const val API_TLS_SUBJECT = "AndroML API"

@Composable
private fun ApiKeyCard(
    key: ApiKeyRecord,
    onRevoke: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(key.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(key.id.value, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onRevoke, enabled = key.revokedAtEpochMillis == null) {
                    Text(if (key.revokedAtEpochMillis == null) "Revoke" else "Revoked")
                }
            }
            Text(
                key.scopes.sortedBy(ApiScope::name).joinToString(" · ") { it.displayName() },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                when {
                    key.revokedAtEpochMillis != null -> "Revoked"
                    !key.isUsableAt(System.currentTimeMillis()) -> "Expired"
                    else -> "Active"
                },
                color = if (key.isUsableAt(System.currentTimeMillis())) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private fun ApiScope.displayName(): String = when (this) {
    ApiScope.ModelsRead -> "Models"
    ApiScope.Inference -> "Inference"
    ApiScope.RagRead -> "RAG read"
    ApiScope.RagWrite -> "RAG write"
    ApiScope.Tools -> "Tools"
    ApiScope.Agents -> "Agents"
    ApiScope.Cluster -> "Cluster"
    ApiScope.Admin -> "Admin"
}
