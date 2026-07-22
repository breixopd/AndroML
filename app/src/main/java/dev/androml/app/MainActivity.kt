package dev.androml.app

import android.os.Bundle
import android.os.ParcelFileDescriptor
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
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
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.androml.core.database.ModelCatalogRepository
import dev.androml.core.database.ModelFileEntity
import dev.androml.core.database.ModelRecordEntity
import dev.androml.core.device.AndroidDeviceProfileCollector
import dev.androml.core.files.FileArtifactStore
import dev.androml.core.model.DeviceProfile
import dev.androml.core.model.ModelRequirements
import dev.androml.core.model.ModelWorkload
import dev.androml.core.model.ReleasePolicy
import dev.androml.core.network.HuggingFaceEndpoints
import dev.androml.core.network.HuggingFaceModelClient
import dev.androml.core.model.HuggingFaceSearchHit
import dev.androml.core.security.SecretStore
import dev.androml.runtime.api.InferenceEvent
import dev.androml.runtime.api.InferenceRequest
import dev.androml.runtime.api.InferenceRequestId
import dev.androml.runtime.api.RuntimeConfiguration
import dev.androml.runtime.api.RuntimeId
import dev.androml.runtime.service.InferenceServiceClient
import dev.androml.runtime.service.OpenedInferenceSession
import dev.androml.runtime.api.RuntimePackCatalog
import dev.androml.optimizer.AutoOptimizer
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val destinations = listOf("Home", "Playground", "Discover", "Library", "RAG", "Workflows", "API", "Cluster", "Settings")
    var selectedDestination by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val application = context.applicationContext as AndroMLApplication
    val deviceProfile = remember(context) {
        AndroidDeviceProfileCollector(context.applicationContext).collect()
    }
    val huggingFaceClient = application.huggingFaceClient
    val inferenceServiceClient = application.inferenceServiceClient
    val workManager = WorkManager.getInstance(context)
    val catalogRepository = application.catalogRepository
    val catalogModels by catalogRepository.observeModels().collectAsState(initial = emptyList())
    val catalogFiles by catalogRepository.observeAllFiles().collectAsState(initial = emptyList())
    val apiController = application.apiController
    val apiKeyRepository = application.apiKeyRepository
    val ragRepository = application.ragRepository
    val clusterPeerRepository = application.clusterPeerRepository
    val workflowController = application.workflowController
    val workflowDefinitionRepository = application.workflowDefinitionRepository
    val apiState by apiController.state.collectAsState()

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
                modelCount = catalogModels.size,
                bundledRuntimeCount = RuntimePackCatalog.bundled.size,
            )
        } else if (selectedDestination == 1) {
            PlaygroundScreen(
                modifier = Modifier.padding(paddingValues),
                serviceClient = inferenceServiceClient,
                deviceProfile = deviceProfile,
                installedModelFiles = catalogFiles,
                artifactStore = application.artifactStore,
                benchmarkRepository = application.runtimeBenchmarkRepository,
            )
        } else if (selectedDestination == 2) {
            DiscoverScreen(
                modifier = Modifier.padding(paddingValues),
                modelClient = huggingFaceClient,
                workManager = workManager,
                catalogRepository = catalogRepository,
                secretStore = application.secretStore,
            )
        } else if (selectedDestination == 3) {
            LibraryScreen(
                modifier = Modifier.padding(paddingValues),
                models = catalogModels,
                files = catalogFiles,
            )
        } else if (selectedDestination == 4) {
            RagScreen(
                modifier = Modifier.padding(paddingValues),
                repository = ragRepository,
                artifactStore = application.artifactStore,
                clusterController = application.clusterController,
            )
        } else if (selectedDestination == 5) {
            WorkflowScreen(
                modifier = Modifier.padding(paddingValues),
                controller = workflowController,
                definitionRepository = workflowDefinitionRepository,
                installedModelFiles = catalogFiles,
            )
        } else if (selectedDestination == 6) {
            ApiScreen(
                modifier = Modifier.padding(paddingValues),
                controller = apiController,
                keyRepository = apiKeyRepository,
                tlsIdentityStore = application.apiTlsIdentityStore,
                clientCertificateStore = application.apiClientCertificateStore,
            )
        } else if (selectedDestination == 7) {
            ClusterScreen(
                modifier = Modifier.padding(paddingValues),
                repository = clusterPeerRepository,
                tlsIdentityStore = application.clusterTlsIdentityStore,
                controller = application.clusterController,
            )
        } else {
            SettingsScreen(
                modifier = Modifier.padding(paddingValues),
                context = context,
                deviceProfile = deviceProfile,
                releasePolicy = ReleasePolicy.testPeriod(),
                runtimePacks = RuntimePackCatalog.production,
                apiState = apiState,
            )
        }
    }
}

@Composable
private fun PlaygroundScreen(
    modifier: Modifier = Modifier,
    serviceClient: InferenceServiceClient,
    deviceProfile: DeviceProfile,
    installedModelFiles: List<ModelFileEntity>,
    artifactStore: FileArtifactStore,
    benchmarkRepository: dev.androml.core.database.RuntimeBenchmarkRepository,
) {
    var prompt by remember { mutableStateOf("Say hello from the isolated runtime.") }
    var output by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Runtime service not checked") }
    var isRunning by remember { mutableStateOf(false) }
    var runJob by remember { mutableStateOf<Job?>(null) }
    val runnableFiles = remember(installedModelFiles) {
        installedModelFiles
            .filter { it.artifactSha256 != null && it.path.endsWith(".litertlm", ignoreCase = true) }
            .take(16)
    }
    var selectedArtifactSha256 by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(runnableFiles) {
        if (runnableFiles.none { it.artifactSha256 == selectedArtifactSha256 }) {
            selectedArtifactSha256 = runnableFiles.firstOrNull()?.artifactSha256
        }
    }
    val selectedFile = runnableFiles.firstOrNull { it.artifactSha256 == selectedArtifactSha256 }
    val scope = rememberCoroutineScope()
    val optimizer = remember { AutoOptimizer() }
    val model = remember(selectedFile?.artifactSha256, selectedFile?.sizeBytes) {
        ModelRequirements(
            workload = ModelWorkload.TextGeneration,
            weightBytes = selectedFile?.sizeBytes ?: 1L,
            contextTokens = 2048,
        )
    }
    val optimization = remember(deviceProfile, selectedFile?.artifactSha256, selectedFile?.sizeBytes) {
        optimizer.select(
            device = deviceProfile,
            model = model,
        runtimes = if (selectedFile == null) {
                emptyList()
            } else {
                RuntimePackCatalog.bundled.map { it.descriptor }
            },
        )
    }
    val benchmarkEntities by remember(deviceProfile.stableKey, selectedFile?.artifactSha256) {
        selectedFile?.artifactSha256?.let { hash ->
            benchmarkRepository.observe(deviceProfile.stableKey, hash)
        } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val benchmarkObservations = remember(benchmarkEntities) {
        benchmarkEntities.filter { it.outputValid }.mapNotNull { entity ->
            runCatching {
                dev.androml.optimizer.BenchmarkObservation(
                    runtimeId = RuntimeId.parse(entity.runtimeId),
                    tokensPerSecond = entity.tokensPerSecond,
                    firstTokenLatencyMs = entity.firstTokenLatencyMs,
                )
            }.getOrNull()
        }
    }
    val optimizedWithBenchmarks = remember(optimization, benchmarkObservations) {
        if (benchmarkObservations.isEmpty()) {
            optimization
        } else {
            optimizer.select(
                device = deviceProfile,
                model = model,
                runtimes = RuntimePackCatalog.bundled.map { it.descriptor },
                benchmarks = benchmarkObservations,
            )
        }
    }

    LaunchedEffect(serviceClient) {
        status = try {
            if (serviceClient.health()) "Isolated runtime ready · bundled packs only" else "Runtime service is not ready"
        } catch (_: Throwable) {
            "Runtime service is unavailable"
        }
    }

    fun stop() {
        runJob?.cancel()
        isRunning = false
        status = "Stopping runtime request…"
    }

    fun runPrompt() {
        if (isRunning) {
            stop()
            return
        }
        output = ""
        isRunning = true
        status = "Opening an isolated session…"
        runJob = scope.launch {
            var session: OpenedInferenceSession? = null
            try {
                session = serviceClient.openSession(
                    model = model,
                    configuration = RuntimeConfiguration(
                        cpuThreads = optimizedWithBenchmarks.configuration?.cpuThreads
                            ?: deviceProfile.cpuCoreCount.coerceIn(1, 8),
                        contextTokens = 2048,
                        useAcceleration = optimizedWithBenchmarks.configuration?.useAcceleration ?: false,
                    ),
                    runtimeId = RuntimeId.parse("litertlm"),
                    modelFile = selectedFile?.artifactSha256?.let { hash ->
                        ParcelFileDescriptor.open(
                            artifactStore.fileFor(hash),
                            ParcelFileDescriptor.MODE_READ_ONLY,
                        )
                    },
                )
                status = "Auto-picked ${session.runtimeId.value} · ${optimizedWithBenchmarks.configuration?.cpuThreads ?: 1} CPU threads in :inference"
                val request = InferenceRequest(
                    id = InferenceRequestId.parse("ui-${System.nanoTime()}"),
                    prompt = prompt,
                    maxNewTokens = 256,
                    temperature = 0.7,
                )
                serviceClient.stream(session, request).collect { event ->
                    when (event) {
                        is InferenceEvent.Started -> status = "Streaming from ${event.runtimeId.value} in :inference"
                        is InferenceEvent.Token -> output += event.text
                        is InferenceEvent.Completed -> {
                            status = "Complete · ${event.generatedTokens} tokens · ${event.durationMs} ms"
                            val hash = selectedFile?.artifactSha256
                            val seconds = event.durationMs.toDouble() / 1_000.0
                            if (hash != null && seconds > 0.0) {
                                scope.launch(Dispatchers.IO) {
                                    runCatching {
                                        benchmarkRepository.record(
                                            deviceKey = deviceProfile.stableKey,
                                            runtimeId = session?.runtimeId?.value ?: "litertlm",
                                            modelArtifactSha256 = hash,
                                            profile = "Balanced",
                                            tokensPerSecond = event.generatedTokens / seconds,
                                            firstTokenLatencyMs = event.durationMs.toDouble(),
                                            outputValid = output.isNotBlank(),
                                        )
                                    }
                                }
                            }
                        }

                        is InferenceEvent.Failed -> status = "Runtime error: ${event.safeMessage}"
                        is InferenceEvent.Cancelled -> status = "Request canceled"
                    }
                }
            } catch (error: CancellationException) {
                status = "Request canceled"
            } catch (_: Throwable) {
                status = "The runtime request failed without exposing internal details"
            } finally {
                session?.let(serviceClient::closeSession)
                isRunning = false
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Playground", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Run a verified model through a bundled isolated runtime. AndroML never substitutes a fake result for a real model.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Installed text models", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    if (runnableFiles.isEmpty()) {
                        Text("No verified .litertlm artifact is installed. Discover and verify one from Hugging Face first.")
                    } else {
                        Text("Select a content-addressed model artifact:", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        runnableFiles.forEach { file ->
                            FilterChip(
                                selected = file.artifactSha256 == selectedArtifactSha256,
                                onClick = { selectedArtifactSha256 = file.artifactSha256 },
                                label = { Text(file.path.take(48)) },
                            )
                        }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Runtime boundary", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("$status\nNetwork access is absent from the runtime-service module.")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        optimizedWithBenchmarks.selected?.let { candidate ->
                            "Auto-pick: ${candidate.descriptor.id.value} · ${candidate.descriptor.acceleration.name.lowercase(Locale.ROOT)} · score ${"%.1f".format(Locale.ROOT, candidate.score ?: 0.0)}"
                        } ?: "Auto-pick: no compatible runtime can be proven on this device",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(if (selectedFile == null) "No runtime selected" else "LiteRT-LM · CPU")
                        },
                    )
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Text generation smoke test", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it.take(InferenceRequest.MAX_PROMPT_CHARS) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Prompt") },
                        minLines = 3,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = ::runPrompt,
                        enabled = isRunning || optimizedWithBenchmarks.selected != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            when {
                                isRunning -> "Stop"
                                optimizedWithBenchmarks.selected == null -> "No compatible runtime"
                                else -> "Run with auto-optimisation"
                            },
                        )
                    }
                }
            }
        }
        if (output.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Output", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        SelectionContainer { Text(output) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier = Modifier,
    releasePolicy: ReleasePolicy,
    deviceProfile: DeviceProfile,
    modelCount: Int,
    bundledRuntimeCount: Int,
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
                value = if (modelCount == 0) "No models installed" else "$modelCount revisions in library",
                detail = "Use Discover to pin a Hugging Face commit, inspect its files, and create verified downloads.",
            )
        }
        item {
            StatusCard(
                title = "Runtime packs",
                value = if (bundledRuntimeCount == 0) "No runtime packs bundled" else "$bundledRuntimeCount runtime pack bundled",
                detail = "Only verified, bundled engines can run. Open Settings for the full engine compatibility matrix.",
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
    workManager: WorkManager,
    catalogRepository: ModelCatalogRepository,
    secretStore: SecretStore,
) {
    var importState by remember { mutableStateOf(HuggingFaceImportState()) }
    var accessToken by remember { mutableStateOf("") }
    var tokenStored by remember { mutableStateOf(false) }
    var tokenDirty by remember { mutableStateOf(false) }
    var tokenStorageMessage by remember { mutableStateOf<String?>(null) }
    var metadataState by remember {
        mutableStateOf<HuggingFaceMetadataUiState>(HuggingFaceMetadataUiState.Idle)
    }
    var downloadState by remember {
        mutableStateOf<HuggingFaceDownloadUiState>(HuggingFaceDownloadUiState.Idle)
    }
    var metadataJob by remember { mutableStateOf<Job?>(null) }
    var metadataRequestId by remember { mutableIntStateOf(0) }
    var activeWorkId by remember { mutableStateOf<UUID?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<HuggingFaceSearchHit>>(emptyList()) }
    var searchMessage by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(secretStore) {
        try {
            val savedToken = withContext(Dispatchers.IO) {
                secretStore.read(HuggingFaceDownloadWork.HF_READ_TOKEN_SECRET_NAME)
            }
            if (savedToken != null && accessToken.isBlank()) {
                accessToken = savedToken
                tokenStored = true
                tokenDirty = false
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            tokenStorageMessage = "The saved Hugging Face token could not be read; it was not sent."
        }
    }
    val endpoint = importState.reference?.let { reference ->
        HuggingFaceEndpoints().modelInfo(reference).toString()
    }

    LaunchedEffect(activeWorkId) {
        val workId = activeWorkId ?: return@LaunchedEffect
        workManager.getWorkInfoByIdFlow(workId).collect { info ->
            if (info == null) return@collect
            val current = downloadState as? HuggingFaceDownloadUiState.Running
                ?: return@collect
            when (info.state) {
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.BLOCKED,
                -> Unit

                WorkInfo.State.RUNNING -> {
                    downloadState = current.copy(
                        bytesWritten = info.progress.getLong(
                            HuggingFaceDownloadWork.PROGRESS_BYTES_KEY,
                            current.bytesWritten,
                        ),
                        totalBytes = info.progress.getLong(
                            HuggingFaceDownloadWork.PROGRESS_TOTAL_BYTES_KEY,
                            current.totalBytes,
                        ),
                    )
                }

                WorkInfo.State.SUCCEEDED -> {
                    downloadState = HuggingFaceDownloadUiState.Complete(
                        path = current.path,
                        sizeBytes = info.outputData.getLong(
                            HuggingFaceDownloadWork.OUTPUT_SIZE_BYTES_KEY,
                            current.totalBytes,
                        ),
                        sha256 = info.outputData.getString(
                            HuggingFaceDownloadWork.OUTPUT_SHA256_KEY,
                        ).orEmpty(),
                    )
                    activeWorkId = null
                }

                WorkInfo.State.FAILED -> {
                    downloadState = HuggingFaceDownloadUiState.Failed(
                        path = current.path,
                        message = huggingFaceWorkerUserMessage(
                            info.outputData.getString(HuggingFaceDownloadWork.ERROR_CODE_KEY),
                        ),
                    )
                    activeWorkId = null
                }

                WorkInfo.State.CANCELLED -> {
                    downloadState = HuggingFaceDownloadUiState.Failed(
                        path = current.path,
                        message = "The background download was canceled.",
                    )
                    activeWorkId = null
                }
            }
        }
    }

    fun cancelActiveDownload() {
        activeWorkId?.let { workManager.cancelWorkById(it) }
        activeWorkId = null
    }

    fun clearResolvedSource() {
        metadataRequestId += 1
        metadataJob?.cancel()
        cancelActiveDownload()
        metadataState = HuggingFaceMetadataUiState.Idle
        downloadState = HuggingFaceDownloadUiState.Idle
    }

    fun inspectPinnedSource() {
        val validatedState = importState.validate()
        importState = validatedState
        cancelActiveDownload()
        metadataState = HuggingFaceMetadataUiState.Idle
        downloadState = HuggingFaceDownloadUiState.Idle
        val reference = validatedState.reference ?: return
        metadataJob?.cancel()
        val requestId = ++metadataRequestId
        metadataState = HuggingFaceMetadataUiState.Loading
        val token = accessToken.trim().takeIf { it.isNotEmpty() }
        metadataJob = scope.launch {
            try {
                val metadata = withContext(Dispatchers.IO) {
                    modelClient.fetchMetadata(reference, token).also { fetchedMetadata ->
                        catalogRepository.saveMetadata(fetchedMetadata)
                    }
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

    fun saveAccessToken() {
        val token = accessToken.trim()
        if (token.isEmpty()) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    secretStore.write(HuggingFaceDownloadWork.HF_READ_TOKEN_SECRET_NAME, token)
                }
                tokenStored = true
                tokenDirty = false
                tokenStorageMessage = "Token saved in Android Keystore-backed storage."
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                tokenStorageMessage = "The token could not be saved securely. It remains memory-only."
            }
        }
    }

    fun removeSavedAccessToken() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    secretStore.delete(HuggingFaceDownloadWork.HF_READ_TOKEN_SECRET_NAME)
                }
                tokenStored = false
                tokenDirty = false
                accessToken = ""
                tokenStorageMessage = "Saved token removed."
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                tokenStorageMessage = "The saved token could not be removed."
            }
        }
    }

    fun downloadFile(
        reference: dev.androml.core.model.HuggingFaceModelReference,
        descriptor: dev.androml.core.model.HuggingFaceFileDescriptor,
    ) {
        val sha256 = descriptor.sha256 ?: return
        if (accessToken.isNotBlank() && (!tokenStored || tokenDirty)) {
            tokenStorageMessage = "Save the current token securely before queueing a background download."
            return
        }
        val request = HuggingFaceDownloadWork.createRequest(reference, descriptor)
        val uniqueWorkName = "hf-download-$sha256"
        try {
            workManager.enqueueUniqueWork(
                uniqueWorkName,
                ExistingWorkPolicy.REPLACE,
                request,
            )
            activeWorkId = request.id
            downloadState = HuggingFaceDownloadUiState.Running(
                path = descriptor.path,
                totalBytes = descriptor.sizeBytes,
            )
        } catch (_: Exception) {
            downloadState = HuggingFaceDownloadUiState.Failed(
                path = descriptor.path,
                message = "AndroML could not queue the background download.",
            )
        }
    }

    fun searchHub() {
        if (searchQuery.isBlank()) {
            searchMessage = "Enter a model name or task first"
            return
        }
        searching = true
        searchMessage = null
        scope.launch {
            try {
                searchResults = withContext(Dispatchers.IO) {
                    modelClient.searchModels(searchQuery.trim(), accessToken = accessToken.trim().takeIf(String::isNotEmpty))
                }
                searchMessage = if (searchResults.isEmpty()) "No public models matched this search" else null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                searchResults = emptyList()
                searchMessage = huggingFaceUserMessage(error)
            } finally {
                searching = false
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
                    Text("Search the Hub", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it.take(256) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Model or task") },
                        placeholder = { Text("llama, text-generation, whisper…") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = ::searchHub, enabled = !searching, modifier = Modifier.fillMaxWidth()) {
                        if (searching) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(20.dp).height(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Search Hugging Face")
                    }
                    searchMessage?.let { message ->
                        Spacer(Modifier.height(6.dp))
                        Text(message, style = MaterialTheme.typography.bodySmall)
                    }
                    searchResults.forEach { result ->
                        Spacer(Modifier.height(8.dp))
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(result.modelId, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    buildString {
                                        result.pipelineTag?.let { append(it) }
                                        result.downloads?.let { if (isNotEmpty()) append(" · "); append("$it downloads") }
                                        result.likes?.let { if (isNotEmpty()) append(" · "); append("$it likes") }
                                    }.ifBlank { "Public model" },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(Modifier.height(4.dp))
                                TextButton(
                                    onClick = {
                                        val revision = result.revision
                                        if (revision == null) {
                                            searchMessage = "This result has no immutable commit; enter a SHA manually."
                                        } else {
                                            importState = HuggingFaceImportState(
                                                modelId = result.modelId,
                                                revision = revision,
                                            )
                                            clearResolvedSource()
                                            searchMessage = "Pinned ${result.modelId} at ${revision.take(12)}…"
                                        }
                                    },
                                ) { Text("Use immutable revision") }
                            }
                        }
                    }
                }
            }
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
                        onValueChange = {
                            accessToken = it
                            tokenDirty = tokenStored
                            tokenStorageMessage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("HF read token (optional)") },
                        supportingText = { Text("Use only after browser approval for a gated model; credentials never go in URLs.") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = ::saveAccessToken,
                            enabled = accessToken.isNotBlank() && (!tokenStored || tokenDirty),
                        ) {
                            Text("Save securely")
                        }
                        if (tokenStored) {
                            TextButton(onClick = ::removeSavedAccessToken) {
                                Text("Remove saved token")
                            }
                        }
                    }
                    Text(
                        if (tokenStored && !tokenDirty) {
                            "A saved token is encrypted with Android Keystore and referenced only by name."
                        } else if (tokenStored) {
                            "This field has unsaved edits; choose Save securely to replace the encrypted token."
                        } else {
                            "The token is memory-only until you choose Save securely."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    tokenStorageMessage?.let { message ->
                        Spacer(Modifier.height(4.dp))
                        Text(message, style = MaterialTheme.typography.bodySmall)
                    }
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
                        "Gated model approval happens on Hugging Face. Metadata inspection may use an in-memory read token; background downloads use only the Keystore-backed token and never place credentials in a URL.",
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
                            if (state.totalBytes > 0L) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "${formatBytes(state.bytesWritten)} / ${formatBytes(state.totalBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "The partial file and WorkManager job survive navigation and can resume after a connection stop.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = ::cancelActiveDownload) {
                                Text("Cancel download")
                            }
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

@Composable
private fun LibraryScreen(
    modifier: Modifier = Modifier,
    models: List<ModelRecordEntity>,
    files: List<ModelFileEntity>,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Library", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Pinned revisions and verified artifact status persist locally on this phone.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (models.isEmpty()) {
            item {
                StatusCard(
                    title = "No pinned revisions",
                    value = "Library is empty",
                    detail = "Inspect a Hugging Face revision from Discover to add its provenance and file list.",
                )
            }
        } else {
            items(
                items = models,
                key = { model -> "${model.modelId}@${model.revision}" },
            ) { model ->
                val modelFiles = files.filter {
                    it.modelId == model.modelId && it.revision == model.revision
                }
                val verifiedFiles = modelFiles.count { it.artifactSha256 != null }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(model.modelId, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(4.dp))
                        SelectionContainer {
                            Text("Commit ${model.revision}", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            buildString {
                                append("${verifiedFiles}/${modelFiles.size} files verified")
                                append(if (model.isPrivate) " · private" else " · public")
                                if (model.isGated) append(" · gated")
                                model.license?.let { append(" · license: $it") }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        modelFiles.take(MAX_LIBRARY_FILE_PREVIEW).forEach { file ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(file.path, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Text(
                                    if (file.artifactSha256 == null) "not downloaded" else "verified",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        if (modelFiles.size > MAX_LIBRARY_FILE_PREVIEW) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${modelFiles.size - MAX_LIBRARY_FILE_PREVIEW} more files available in Discover.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val MAX_LIBRARY_FILE_PREVIEW = 8

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
            modelCount = 0,
            bundledRuntimeCount = RuntimePackCatalog.bundled.size,
        )
    }
}
