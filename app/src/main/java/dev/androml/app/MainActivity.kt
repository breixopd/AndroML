package dev.androml.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.androml.core.files.FileArtifactStore
import dev.androml.core.device.AndroidDeviceProfileCollector
import dev.androml.core.model.DeviceProfile
import dev.androml.core.model.ReleasePolicy
import dev.androml.core.network.DownloadProgress
import dev.androml.core.network.HuggingFaceArtifactDownloader
import dev.androml.core.network.HuggingFaceEndpoints
import dev.androml.core.network.HuggingFaceModelClient
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroMLTheme {
                AndroMLApp()
            }
        }
    }
}

@Composable
private fun AndroMLTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AndroMLApp() {
    val destinations = listOf("Home", "Discover", "Library", "More")
    var selectedDestination by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val deviceProfile = remember(context) {
        AndroidDeviceProfileCollector(context.applicationContext).collect()
    }
    val httpClient = remember { OkHttpClient() }
    val huggingFaceClient = remember(httpClient) {
        HuggingFaceModelClient(httpClient)
    }
    val artifactStore = remember(context) {
        FileArtifactStore(File(context.filesDir, "model-artifacts"))
    }
    val artifactDownloader = remember(httpClient, artifactStore) {
        HuggingFaceArtifactDownloader(httpClient, artifactStore)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AndroML", fontWeight = FontWeight.Bold)
                        Text(
                            text = "On-device model control",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                destinations.forEachIndexed { index, destination ->
                    NavigationBarItem(
                        selected = selectedDestination == index,
                        onClick = { selectedDestination = index },
                        icon = {},
                        label = { Text(destination) },
                    )
                }
            }
        },
    ) { paddingValues ->
        if (selectedDestination == 0) {
            HomeScreen(
                modifier = Modifier.padding(paddingValues),
                releasePolicy = ReleasePolicy.testPeriod(),
                deviceProfile = deviceProfile,
            )
        } else if (selectedDestination == 1) {
            DiscoverScreen(
                modifier = Modifier.padding(paddingValues),
                modelClient = huggingFaceClient,
                artifactDownloader = artifactDownloader,
            )
        } else {
            PlaceholderDestination(
                name = destinations[selectedDestination],
                modifier = Modifier.padding(paddingValues),
            )
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier = Modifier,
    releasePolicy: ReleasePolicy,
    deviceProfile: DeviceProfile,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Private phone test", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(releasePolicy.storeSubmissionStatus)
                    Spacer(Modifier.height(8.dp))
                    AssistChip(
                        onClick = {},
                        label = { Text("GitHub Releases only") },
                    )
                }
            }
        }
        item {
            StatusCard(
                title = "Device readiness",
                value = "${deviceProfile.deviceName} · ${deviceProfile.readiness}",
                detail = deviceProfile.resourceSummary,
            )
        }
        item {
            StatusCard(
                title = "Models",
                value = "No models installed",
                detail = "Use Discover to pin a Hugging Face commit before creating a verified download job.",
            )
        }
        item {
            StatusCard(
                title = "Runtime packs",
                value = "Optimizer contracts ready",
                detail = "No native runtime pack is installed yet; impossible device/model combinations are rejected first.",
            )
        }
        item {
            Text("Recent activity", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
        }
        items(listOf("Test-only release gate enabled", "No network services running")) { activity ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(activity, modifier = Modifier.weight(1f))
                Text("now", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun DiscoverScreen(
    modifier: Modifier = Modifier,
    modelClient: HuggingFaceModelClient,
    artifactDownloader: HuggingFaceArtifactDownloader,
) {
    var importState by remember { mutableStateOf(HuggingFaceImportState()) }
    var accessToken by remember { mutableStateOf("") }
    var metadataState by remember {
        mutableStateOf<HuggingFaceMetadataUiState>(HuggingFaceMetadataUiState.Idle)
    }
    var downloadState by remember {
        mutableStateOf<HuggingFaceDownloadUiState>(HuggingFaceDownloadUiState.Idle)
    }
    var metadataJob by remember { mutableStateOf<Job?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var metadataRequestId by remember { mutableIntStateOf(0) }
    var downloadRequestId by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val progressFlow = remember { MutableStateFlow<DownloadProgress?>(null) }
    val downloadProgress by progressFlow.collectAsState()
    val endpoint = importState.reference?.let { reference ->
        HuggingFaceEndpoints().modelInfo(reference).toString()
    }

    fun clearResolvedSource() {
        metadataRequestId += 1
        downloadRequestId += 1
        metadataJob?.cancel()
        downloadJob?.cancel()
        metadataState = HuggingFaceMetadataUiState.Idle
        downloadState = HuggingFaceDownloadUiState.Idle
        progressFlow.value = null
    }

    fun inspectPinnedSource() {
        val validatedState = importState.validate()
        importState = validatedState
        metadataState = HuggingFaceMetadataUiState.Idle
        downloadState = HuggingFaceDownloadUiState.Idle
        progressFlow.value = null
        val reference = validatedState.reference ?: return
        metadataJob?.cancel()
        val requestId = ++metadataRequestId
        metadataState = HuggingFaceMetadataUiState.Loading
        val token = accessToken.trim().takeIf { it.isNotEmpty() }
        metadataJob = scope.launch {
            try {
                val metadata = withContext(Dispatchers.IO) {
                    modelClient.fetchMetadata(reference, token)
                }
                if (requestId == metadataRequestId) {
                    metadataState = HuggingFaceMetadataUiState.Loaded(metadata)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (requestId == metadataRequestId) {
                    metadataState = HuggingFaceMetadataUiState.Failed(huggingFaceUserMessage(error))
                }
            }
        }
    }

    fun downloadFile(
        reference: dev.androml.core.model.HuggingFaceModelReference,
        descriptor: dev.androml.core.model.HuggingFaceFileDescriptor,
    ) {
        val sha256 = descriptor.sha256 ?: return
        downloadJob?.cancel()
        val requestId = ++downloadRequestId
        val token = accessToken.trim().takeIf { it.isNotEmpty() }
        progressFlow.value = null
        downloadState = HuggingFaceDownloadUiState.Running(descriptor.path)
        downloadJob = scope.launch {
            try {
                val artifact = withContext(Dispatchers.IO) {
                    artifactDownloader.download(
                        reference = reference,
                        descriptor = descriptor,
                        jobKey = sha256,
                        accessToken = token,
                        onProgress = { progress -> progressFlow.value = progress },
                    )
                }
                if (requestId == downloadRequestId) {
                    downloadState = HuggingFaceDownloadUiState.Complete(
                        path = descriptor.path,
                        sizeBytes = artifact.sizeBytes,
                        sha256 = artifact.sha256,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (requestId == downloadRequestId) {
                    downloadState = HuggingFaceDownloadUiState.Failed(
                        path = descriptor.path,
                        message = huggingFaceUserMessage(error),
                    )
                }
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Hugging Face direct import", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Resolve a repository to an immutable commit, inspect its signed metadata, then download only files with a verified SHA-256.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Pinned source", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importState.modelId,
                        onValueChange = {
                            importState = importState.copy(
                                modelId = it,
                                reference = null,
                                errorMessage = null,
                            )
                            clearResolvedSource()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Model ID") },
                        placeholder = { Text("organization/model-name") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importState.revision,
                        onValueChange = {
                            importState = importState.copy(
                                revision = it,
                                reference = null,
                                errorMessage = null,
                            )
                            clearResolvedSource()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Commit SHA") },
                        placeholder = { Text("40 lowercase hexadecimal characters") },
                        singleLine = true,
                        isError = importState.errorMessage != null,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = accessToken,
                        onValueChange = { accessToken = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("HF read token (optional, memory-only)") },
                        supportingText = { Text("Use only after browser approval for a gated model; never saved by this screen.") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = ::inspectPinnedSource,
                        enabled = metadataState !is HuggingFaceMetadataUiState.Loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (metadataState is HuggingFaceMetadataUiState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(20.dp).height(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Inspect pinned metadata")
                    }
                    importState.errorMessage?.let { errorMessage ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Access and safety", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Gated model approval happens on Hugging Face. The app uses a least-privilege read token for this in-memory test flow and never places credentials in a URL.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        endpoint?.let { pinnedEndpoint ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Pinned metadata endpoint", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        SelectionContainer {
                            Text(pinnedEndpoint, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "The commit SHA is fixed for this inspection and all later download requests.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        when (val state = metadataState) {
            HuggingFaceMetadataUiState.Idle -> Unit
            HuggingFaceMetadataUiState.Loading -> {
                item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Fetching pinned repository metadata…", style = MaterialTheme.typography.bodySmall)
                }
            }

            is HuggingFaceMetadataUiState.Failed -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            state.message,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            is HuggingFaceMetadataUiState.Loaded -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Repository metadata", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${state.metadata.files.size} files · ${formatBytes(state.metadata.files.totalBytes())}",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                buildString {
                                    append(if (state.metadata.isPrivate) "Private" else "Public")
                                    append(if (state.metadata.isGated) " · gated" else "")
                                    state.metadata.license?.let { append(" · license: $it") }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                items(state.metadata.files, key = { it.path }) { descriptor ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(descriptor.path, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${formatBytes(descriptor.sizeBytes)} · SHA-256 ${descriptor.sha256 ?: "not provided"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    downloadFile(state.metadata.reference, descriptor)
                                },
                                enabled = descriptor.sha256 != null &&
                                    downloadState !is HuggingFaceDownloadUiState.Running,
                            ) {
                                Text(if (descriptor.sha256 == null) "Integrity data required" else "Download file")
                            }
                        }
                    }
                }
            }
        }
        when (val state = downloadState) {
            HuggingFaceDownloadUiState.Idle -> Unit
            is HuggingFaceDownloadUiState.Running -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Downloading ${state.path}", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            downloadProgress?.let { progress ->
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "${formatBytes(progress.bytesWritten)} / ${formatBytes(progress.totalBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "The partial file is retained for a later range-resume if the connection stops.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            is HuggingFaceDownloadUiState.Complete -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Verified download complete", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${state.path} · ${formatBytes(state.sizeBytes)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            SelectionContainer {
                                Text("SHA-256 ${state.sha256}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            is HuggingFaceDownloadUiState.Failed -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Download failed: ${state.path}", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                state.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun List<dev.androml.core.model.HuggingFaceFileDescriptor>.totalBytes(): Long = fold(0L) { total, file ->
    if (Long.MAX_VALUE - total < file.sizeBytes) Long.MAX_VALUE else total + file.sizeBytes
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[unitIndex])
}

@Composable
private fun StatusCard(title: String, value: String, detail: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PlaceholderDestination(name: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Text(name, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("This surface is reserved for the next implementation slice.")
        Spacer(Modifier.width(1.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    AndroMLTheme {
        HomeScreen(
            releasePolicy = ReleasePolicy.testPeriod(),
            deviceProfile = DeviceProfile(
                manufacturer = "Google",
                model = "Pixel Preview",
                androidApi = 37,
                supportedAbis = listOf("arm64-v8a"),
                cpuCoreCount = 8,
                totalMemoryBytes = 8L * 1024L * 1024L * 1024L,
                availableMemoryBytes = 4L * 1024L * 1024L * 1024L,
                availableStorageBytes = 64L * 1024L * 1024L * 1024L,
                isCharging = true,
                thermalStatus = dev.androml.core.model.ThermalStatus.Nominal,
                hasVulkan = true,
            ),
        )
    }
}
