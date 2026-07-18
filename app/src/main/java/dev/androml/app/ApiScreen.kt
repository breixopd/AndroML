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
import dev.androml.core.security.TlsIdentityStore
import dev.androml.core.security.TlsIdentitySummary
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
    tlsIdentityStore: TlsIdentityStore,
) {
    val apiState by controller.state.collectAsState()
    var keys by remember { mutableStateOf<List<ApiKeyRecord>>(emptyList()) }
    var displayName by remember { mutableStateOf("phone client") }
    var port by remember { mutableStateOf("8787") }
    var selectedScopes by remember {
        mutableStateOf(setOf(ApiScope.ModelsRead, ApiScope.Inference))
    }
    var generatedToken by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tlsSummary by remember { mutableStateOf<TlsIdentitySummary?>(null) }
    var tlsBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refreshKeys() {
        scope.launch {
            keys = withContext(Dispatchers.IO) { keyRepository.snapshot() }
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

    LaunchedEffect(tlsIdentityStore) {
        try {
            tlsSummary = withContext(Dispatchers.IO) {
                tlsIdentityStore.loadOrCreate(
                    alias = API_TLS_ALIAS,
                    subjectName = API_TLS_SUBJECT,
                ).summary()
            }
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

    fun toggleServer() {
        val running = apiState is LocalApiState.Running
        if (running) {
            controller.stop()
            message = "Loopback API stopped"
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

    fun rotateTlsIdentity() {
        if (apiState is LocalApiState.Running) {
            message = "Stop the API before rotating its mTLS identity"
            return
        }
        tlsBusy = true
        message = null
        scope.launch {
            try {
                tlsSummary = withContext(Dispatchers.IO) {
                    tlsIdentityStore.delete(API_TLS_ALIAS)
                    tlsIdentityStore.loadOrCreate(
                        alias = API_TLS_ALIAS,
                        subjectName = API_TLS_SUBJECT,
                    ).summary()
                }
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
                "Expose the installed runtime to local clients on this phone. The first test period is loopback-only; LAN access stays locked behind verified mTLS transport.",
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
                            is LocalApiState.Running -> "Listening on ${state.host}:${state.port}"
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
                        label = { Text("Loopback port") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Authentication: scoped bearer API key", style = MaterialTheme.typography.bodySmall)
                    Text("Health checks are unauthenticated; models and inference are not.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = ::toggleServer,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy) CircularProgressIndicator()
                        else Text(if (apiState is LocalApiState.Running) "Stop loopback API" else "Enable loopback API")
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
                        "This certificate is reserved for the future LAN transport. LAN binding stays disabled until the verified mTLS server path is enabled.",
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
