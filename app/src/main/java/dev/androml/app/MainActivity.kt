package dev.androml.app

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import dev.androml.cluster.core.ClusterInferenceTask
import dev.androml.cluster.core.ContentHash
import dev.androml.core.device.AndroidDeviceProfileCollector
import dev.androml.core.files.FileArtifactStore
import dev.androml.core.model.DeviceProfile
import dev.androml.core.model.AppSettings
import dev.androml.core.model.ModelRequirements
import dev.androml.core.model.ModelFormatClassifier
import dev.androml.core.model.ModelWorkload
import dev.androml.core.model.ReleasePolicy
import dev.androml.core.network.HuggingFaceEndpoints
import dev.androml.core.network.HuggingFaceModelClient
import dev.androml.core.network.HuggingFaceModelSort
import dev.androml.core.model.HuggingFaceSearchHit
import dev.androml.core.model.HuggingFaceRepositoryMetadata
import dev.androml.core.security.SecretStore
import dev.androml.runtime.api.InferenceEvent
import dev.androml.runtime.api.InferenceRequest
import dev.androml.runtime.api.InferenceRequestId
import dev.androml.runtime.api.RuntimeConfiguration
import dev.androml.runtime.api.RuntimeId
import dev.androml.runtime.service.InferenceServiceClient
import dev.androml.runtime.service.OpenedInferenceSession
import dev.androml.runtime.api.RuntimePackCatalog
import dev.androml.runtime.api.TensorInput
import dev.androml.optimizer.AutoOptimizer
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

private enum class AppDestination(
    val label: String,
    val description: String,
    val icon: ImageVector,
) {
    Home("Home", "Device and model status", Icons.Outlined.Home),
    Playground("Playground", "Run local inference", Icons.Outlined.PlayArrow),
    Discover("Discover", "Find and download models", Icons.Outlined.Explore),
    Library("Library", "Installed model revisions", Icons.Outlined.Inventory2),
    Rag("RAG", "Local knowledge collections", Icons.AutoMirrored.Outlined.MenuBook),
    Workflows("Workflows", "Tools and agent automation", Icons.Outlined.AccountTree),
    Api("API", "Secure local API server", Icons.Outlined.Api),
    Cluster("Cluster", "Distributed inference peers", Icons.Outlined.Hub),
    Settings("Settings", "Device and engine controls", Icons.Outlined.Settings),
}

private val appDestinationSections = listOf(
    "Models" to listOf(
        AppDestination.Home,
        AppDestination.Playground,
        AppDestination.Discover,
        AppDestination.Library,
    ),
    "Build" to listOf(AppDestination.Rag, AppDestination.Workflows),
    "Connect" to listOf(AppDestination.Api, AppDestination.Cluster),
    "System" to listOf(AppDestination.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AndroMLApp() {
    var selectedDestination by rememberSaveable { mutableStateOf(AppDestination.Home) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val appScope = rememberCoroutineScope()
    val context = LocalContext.current
    var appSettings by remember(context) {
        mutableStateOf(AppSettingsStore.load(context))
    }
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

    fun navigateTo(destination: AppDestination) {
        selectedDestination = destination
        appScope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.widthIn(max = 360.dp)) {
                Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("AndroML", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Local AI control plane",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    appDestinationSections.forEach { (section, destinations) ->
                        item(key = "section-$section") {
                            Text(
                                section,
                                modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        items(
                            items = destinations,
                            key = { it.name },
                        ) { destination ->
                            NavigationDrawerItem(
                                selected = selectedDestination == destination,
                                onClick = { navigateTo(destination) },
                                icon = {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = null,
                                    )
                                },
                                label = {
                                    Column {
                                        Text(destination.label)
                                        Text(
                                            destination.description,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { appScope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Outlined.Menu,
                                contentDescription = "Open navigation",
                            )
                        }
                    },
                    title = {
                        Column {
                            Text(selectedDestination.label, fontWeight = FontWeight.Bold)
                            Text(
                                text = selectedDestination.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            },
        ) { paddingValues ->
            when (selectedDestination) {
                AppDestination.Home -> HomeScreen(
                    modifier = Modifier.padding(paddingValues),
                    releasePolicy = ReleasePolicy.testPeriod(),
                    deviceProfile = deviceProfile,
                    modelCount = catalogFiles
                        .asSequence()
                        .filter { it.artifactSha256 != null }
                        .map { it.modelId to it.revision }
                        .distinct()
                        .count(),
                    bundledRuntimeCount = RuntimePackCatalog.bundled.size,
                    onBrowseModels = { navigateTo(AppDestination.Discover) },
                    onOpenPlayground = { navigateTo(AppDestination.Playground) },
                )

                AppDestination.Playground -> PlaygroundScreen(
                    modifier = Modifier.padding(paddingValues),
                    serviceClient = inferenceServiceClient,
                    clusterController = application.clusterController,
                    deviceProfile = deviceProfile,
                    installedModelFiles = catalogFiles,
                    artifactStore = application.artifactStore,
                    benchmarkRepository = application.runtimeBenchmarkRepository,
                    settings = appSettings,
                )

                AppDestination.Discover -> DiscoverScreen(
                    modifier = Modifier.padding(paddingValues),
                    modelClient = huggingFaceClient,
                    workManager = workManager,
                    catalogRepository = catalogRepository,
                    secretStore = application.secretStore,
                    deviceProfile = deviceProfile,
                )

                AppDestination.Library -> LibraryScreen(
                    modifier = Modifier.padding(paddingValues),
                    models = catalogModels,
                    files = catalogFiles,
                    onBrowseModels = { navigateTo(AppDestination.Discover) },
                )

                AppDestination.Rag -> RagScreen(
                    modifier = Modifier.padding(paddingValues),
                    repository = ragRepository,
                    artifactStore = application.artifactStore,
                    clusterController = application.clusterController,
                )

                AppDestination.Workflows -> WorkflowScreen(
                    modifier = Modifier.padding(paddingValues),
                    controller = workflowController,
                    definitionRepository = workflowDefinitionRepository,
                    installedModelFiles = catalogFiles,
                )

                AppDestination.Api -> ApiScreen(
                    modifier = Modifier.padding(paddingValues),
                    controller = apiController,
                    keyRepository = apiKeyRepository,
                    auditDao = application.catalogDatabase.toolAuditDao(),
                    tlsIdentityStore = application.apiTlsIdentityStore,
                    clientCertificateStore = application.apiClientCertificateStore,
                )

                AppDestination.Cluster -> ClusterScreen(
                    modifier = Modifier.padding(paddingValues),
                    repository = clusterPeerRepository,
                    tlsIdentityStore = application.clusterTlsIdentityStore,
                    controller = application.clusterController,
                    discovery = application.clusterDiscovery,
                )

                AppDestination.Settings -> SettingsScreen(
                    modifier = Modifier.padding(paddingValues),
                    deviceProfile = deviceProfile,
                    releasePolicy = ReleasePolicy.testPeriod(),
                    runtimePacks = RuntimePackCatalog.production,
                    apiState = apiState,
                    settings = appSettings,
                    onBrowseModels = { navigateTo(AppDestination.Discover) },
                    onSettingsChanged = { next ->
                        appSettings = next
                        AppSettingsStore.save(context, next)
                    }
                )
            }
        }
    }
}

@Composable
private fun PlaygroundScreen(
    modifier: Modifier = Modifier,
    serviceClient: InferenceServiceClient,
    clusterController: ClusterController,
    deviceProfile: DeviceProfile,
    installedModelFiles: List<ModelFileEntity>,
    artifactStore: FileArtifactStore,
    benchmarkRepository: dev.androml.core.database.RuntimeBenchmarkRepository,
    settings: AppSettings,
) {
    var prompt by remember { mutableStateOf("Say hello from the isolated runtime.") }
    var output by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Runtime service not checked") }
    var isRunning by remember { mutableStateOf(false) }
    var runJob by remember { mutableStateOf<Job?>(null) }
    var selectedWorkload by remember { mutableStateOf(ModelWorkload.TextGeneration) }
    var tensorInput by remember { mutableStateOf<TensorInput?>(null) }
    var tensorInputLabel by remember { mutableStateOf<String?>(null) }
    var distributed by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                if (selectedWorkload == ModelWorkload.AudioClassification) {
                    TensorPreprocessors.wav(stream)
                } else {
                    val bitmap = BitmapFactory.decodeStream(stream)
                        ?: error("selected image could not be decoded")
                    try {
                        TensorPreprocessors.image(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
            } ?: error("selected file could not be opened")
        }.onSuccess { input ->
            tensorInput = input
            tensorInputLabel = if (selectedWorkload == ModelWorkload.AudioClassification) {
                "16 kHz mono PCM16 WAV"
            } else {
                "224 × 224 RGB NHWC"
            }
            status = "Prepared ${input.elementCount} ${input.dataType.name} tensor"
        }.onFailure { error ->
            tensorInput = null
            tensorInputLabel = null
            status = error.message?.take(256) ?: "Media preprocessing failed"
        }
    }
    LaunchedEffect(selectedWorkload) {
        tensorInput = null
        tensorInputLabel = null
        if (selectedWorkload != ModelWorkload.TextGeneration) distributed = false
    }
    val runnableFiles = remember(installedModelFiles, selectedWorkload) {
        installedModelFiles
            .filter {
                it.artifactSha256 != null && ModelFormatClassifier.supports(it.path, selectedWorkload)
            }
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
    val selectedRuntimeId = selectedFile?.let { ModelFormatClassifier.forPath(it.path)?.runtimeId }
    val compatibleRuntimeDescriptors = selectedRuntimeId
        ?.let { RuntimePackCatalog.find(RuntimeId.parse(it)) }
        ?.takeIf { it.usable }
        ?.let { listOf(it.descriptor) }
        .orEmpty()
    val model = remember(selectedFile?.artifactSha256, selectedFile?.sizeBytes, selectedWorkload) {
        ModelRequirements(
            workload = selectedWorkload,
            weightBytes = selectedFile?.sizeBytes ?: 1L,
            contextTokens = 2048,
        )
    }
    val optimizationDevice = remember(deviceProfile, settings.thermalGuard) {
        if (settings.thermalGuard) {
            deviceProfile
        } else {
            deviceProfile.copy(thermalStatus = dev.androml.core.model.ThermalStatus.Nominal)
        }
    }
    val optimization = remember(
        optimizationDevice,
        selectedFile?.artifactSha256,
        selectedFile?.sizeBytes,
        selectedWorkload,
        settings.autoOptimize,
    ) {
        optimizer.select(
            device = optimizationDevice,
            model = model,
            runtimes = compatibleRuntimeDescriptors,
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
                device = optimizationDevice,
                model = model,
                runtimes = compatibleRuntimeDescriptors,
                benchmarks = if (settings.autoOptimize) benchmarkObservations else emptyList(),
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
                if (distributed && selectedWorkload == ModelWorkload.TextGeneration) {
                    val artifactHash = selectedFile?.artifactSha256
                        ?: error("a verified model artifact is required for distributed inference")
                    val runtimeId = selectedRuntimeId ?: error("the model format has no runtime")
                    val execution = withContext(Dispatchers.IO) {
                        clusterController.executeBestInference(
                            ClusterInferenceTask(
                                modelHash = ContentHash.parse(artifactHash),
                                prompt = prompt,
                                maxNewTokens = 256,
                                temperature = 0.7,
                                contextTokens = 2_048,
                                kvCacheBytesPerToken = 0L,
                                cpuThreads = optimizedWithBenchmarks.configuration?.cpuThreads
                                    ?: deviceProfile.cpuCoreCount.coerceIn(1, 8),
                                useAcceleration = optimizedWithBenchmarks.configuration?.useAcceleration ?: false,
                                runtimeId = runtimeId,
                            ),
                        )
                    }
                    output = execution.result.text
                    status = "Complete · ${execution.placement.target.value} · ${execution.result.runtimeId}"
                    return@launch
                }
                session = serviceClient.openSession(
                    model = model,
                    configuration = RuntimeConfiguration(
                        cpuThreads = optimizedWithBenchmarks.configuration?.cpuThreads
                            ?: deviceProfile.cpuCoreCount.coerceIn(1, 8),
                        contextTokens = 2048,
                        useAcceleration = optimizedWithBenchmarks.configuration?.useAcceleration ?: false,
                    ),
                    runtimeId = RuntimeId.parse(selectedRuntimeId ?: error("no runtime is selected")),
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
                    tensorInput = tensorInput,
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
                                            runtimeId = session?.runtimeId?.value ?: selectedRuntimeId.orEmpty(),
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
                    Text("Installed runnable models", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = selectedWorkload == ModelWorkload.TextGeneration,
                            onClick = { selectedWorkload = ModelWorkload.TextGeneration },
                            label = { Text("Chat") },
                        )
                        FilterChip(
                            selected = selectedWorkload == ModelWorkload.TextEmbedding,
                            onClick = { selectedWorkload = ModelWorkload.TextEmbedding },
                            label = { Text("Embeddings") },
                        )
                        if (selectedWorkload == ModelWorkload.TextGeneration) {
                            FilterChip(
                                selected = distributed,
                                onClick = { distributed = !distributed },
                                label = { Text("Distributed") },
                            )
                        }
                    }
                    listOf(
                        listOf(ModelWorkload.ImageClassification, ModelWorkload.ObjectDetection),
                        listOf(ModelWorkload.ImageSegmentation, ModelWorkload.AudioClassification),
                    ).forEach { rowWorkloads ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowWorkloads.forEach { workload ->
                                FilterChip(
                                    selected = selectedWorkload == workload,
                                    onClick = { selectedWorkload = workload },
                                    label = {
                                        Text(
                                            when (workload) {
                                                ModelWorkload.ImageClassification -> "Image class."
                                                ModelWorkload.ObjectDetection -> "Detection"
                                                ModelWorkload.ImageSegmentation -> "Segmentation"
                                                ModelWorkload.AudioClassification -> "Audio class."
                                                else -> workload.name
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (runnableFiles.isEmpty()) {
                        Text(
                            "No verified ${selectedWorkload.name} model artifact is installed. Discover and verify one from Hugging Face first.",
                        )
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
                    if (distributed && selectedWorkload == ModelWorkload.TextGeneration) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Distributed mode sends the complete verified request to a paired, freshly-capable node over mTLS; the model must already be installed there.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        optimizedWithBenchmarks.selected?.let { candidate ->
                            "${if (settings.autoOptimize) "Auto-pick" else "Runtime default"}: ${candidate.descriptor.id.value} · ${candidate.descriptor.acceleration.name.lowercase(Locale.ROOT)} · score ${"%.1f".format(Locale.ROOT, candidate.score ?: 0.0)}"
                        } ?: "Auto-pick: no compatible runtime can be proven on this device",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                if (selectedFile == null) "No runtime selected"
                                else "${selectedRuntimeId ?: "unknown"} · ${selectedWorkload.name}",
                            )
                        },
                    )
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        when (selectedWorkload) {
                            ModelWorkload.TextGeneration -> "Text generation"
                            ModelWorkload.TextEmbedding -> "Text embedding"
                            ModelWorkload.AudioClassification -> "Audio classification"
                            ModelWorkload.ImageClassification -> "Image classification"
                            ModelWorkload.ObjectDetection -> "Object detection"
                            ModelWorkload.ImageSegmentation -> "Image segmentation"
                            else -> selectedWorkload.name
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (selectedWorkload == ModelWorkload.TextGeneration ||
                        selectedWorkload == ModelWorkload.TextEmbedding
                    ) {
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it.take(InferenceRequest.MAX_PROMPT_CHARS) },
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Text(if (selectedWorkload == ModelWorkload.TextGeneration) "Prompt" else "Text to embed")
                            },
                            minLines = 3,
                        )
                    } else {
                        Button(
                            onClick = {
                                mediaPicker.launch(
                                    if (selectedWorkload == ModelWorkload.AudioClassification) {
                                        arrayOf("audio/wav", "audio/x-wav")
                                    } else {
                                        arrayOf("image/*")
                                    },
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                when {
                                    tensorInput != null -> "Replace input media"
                                    selectedWorkload == ModelWorkload.AudioClassification -> "Choose WAV audio"
                                    else -> "Choose image"
                                },
                            )
                        }
                        tensorInputLabel?.let { label ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "$label · ${tensorInput?.elementCount ?: 0} values",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            "Preprocessing is explicit and bounded: images become 224×224 RGB float32 [0,1]; WAV audio becomes mono 16 kHz float32.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = ::runPrompt,
                        enabled = isRunning || (optimizedWithBenchmarks.selected != null &&
                            (selectedWorkload == ModelWorkload.TextGeneration ||
                                selectedWorkload == ModelWorkload.TextEmbedding ||
                                tensorInput != null)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            when {
                                isRunning -> "Stop"
                                optimizedWithBenchmarks.selected == null -> "No compatible runtime"
                                else -> if (settings.autoOptimize) "Run with auto-optimisation" else "Run with runtime defaults"
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
    onBrowseModels: () -> Unit,
    onOpenPlayground: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text(
                    if (modelCount == 0) "Set up your first local model" else "Your local AI workspace",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (modelCount == 0) {
                        "Choose a model from Hugging Face. AndroML will verify the download and select a compatible included engine."
                    } else {
                        "Models, runtimes, APIs, automations, and cluster peers stay under your control."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onBrowseModels,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (modelCount == 0) "Find and install a model" else "Browse more models")
                }
                if (modelCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onOpenPlayground,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Bolt, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open playground")
                    }
                }
            }
        }
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
                value = if (bundledRuntimeCount == 0) {
                    "No inference engines included"
                } else {
                    "$bundledRuntimeCount inference engines included"
                },
                detail = "Included engines are ready to use; auto-optimisation picks the best compatible option for each model.",
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
    deviceProfile: DeviceProfile,
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
    var lastDownloadRequest by remember { mutableStateOf<HuggingFaceDownloadRequest?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var browseSortKey by rememberSaveable { mutableStateOf(HuggingFaceModelSort.Popular.name) }
    var browseResults by remember { mutableStateOf<List<HuggingFaceSearchHit>>(emptyList()) }
    var browseMessage by remember { mutableStateOf<String?>(null) }
    var browseMessageIsError by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    var browseJob by remember { mutableStateOf<Job?>(null) }
    var showAdvancedImport by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val browseSort = HuggingFaceModelSort.entries.firstOrNull { it.name == browseSortKey }
        ?: HuggingFaceModelSort.Popular

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

    fun cancelBrowse() {
        browseJob?.cancel()
        browseJob = null
        browsing = false
    }

    fun loadRecommendations(sort: HuggingFaceModelSort = browseSort) {
        cancelBrowse()
        browseJob = scope.launch {
            browsing = true
            browseMessage = null
            browseMessageIsError = false
            try {
                val queries = deviceRecommendationQueries(deviceProfile)
                if (queries.isEmpty()) {
                    browseResults = emptyList()
                    browseMessage = "No bundled runtime matches this device's CPU architecture yet."
                    return@launch
                }
                val responses = withContext(Dispatchers.IO) {
                    coroutineScope {
                        queries.map { query ->
                            async {
                                runCatching {
                                    modelClient.searchModels(
                                        query = query.search,
                                        limit = query.limit,
                                        sort = sort,
                                        pipelineTag = query.pipelineTag,
                                        filter = query.filter,
                                        accessToken = accessToken.trim().takeIf(String::isNotEmpty),
                                    )
                                }
                            }
                        }.awaitAll()
                    }
                }
                val successful = responses.mapNotNull { it.getOrNull() }.flatten()
                val failures = responses.count { it.isFailure }
                browseResults = rankDeviceRecommendations(successful, deviceProfile)
                browseMessage = when {
                    browseResults.isEmpty() && failures == responses.size ->
                        "Hugging Face could not load recommendations. Check your connection and try again."
                    browseResults.isEmpty() ->
                        "No compatible public models were found for this device yet. Try a specific model search."
                    failures > 0 ->
                        "Showing the recommendations that loaded. Some task categories were unavailable."
                    else -> null
                }
                browseMessageIsError = browseResults.isEmpty() && failures == responses.size
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                browseResults = emptyList()
                browseMessage = huggingFaceUserMessage(error)
                browseMessageIsError = true
            } finally {
                browsing = false
            }
        }
    }

    fun searchHub(sort: HuggingFaceModelSort = browseSort) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            loadRecommendations(sort)
            return
        }
        cancelBrowse()
        browseJob = scope.launch {
            browsing = true
            browseMessage = null
            browseMessageIsError = false
            try {
                browseResults = withContext(Dispatchers.IO) {
                    modelClient.searchModels(
                        query = query,
                        limit = 30,
                        sort = sort,
                        accessToken = accessToken.trim().takeIf(String::isNotEmpty),
                    )
                }
                browseMessage = if (browseResults.isEmpty()) {
                    "No public models matched \"$query\". Try a broader name or task."
                } else {
                    null
                }
                browseMessageIsError = false
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                browseResults = emptyList()
                browseMessage = huggingFaceUserMessage(error)
                browseMessageIsError = true
            } finally {
                browsing = false
            }
        }
    }

    LaunchedEffect(searchQuery.isBlank(), browseSort, deviceProfile.stableKey) {
        if (searchQuery.isBlank()) loadRecommendations(browseSort)
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

    fun inspectPinnedSource(sourceState: HuggingFaceImportState = importState) {
        val validatedState = sourceState.validate()
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

    fun selectSearchResult(result: HuggingFaceSearchHit) {
        val revision = result.revision
        if (revision == null) {
            browseMessage = "This repository has no immutable commit and cannot be installed safely."
            browseMessageIsError = true
            return
        }
        val selectedState = HuggingFaceImportState(
            modelId = result.modelId,
            revision = revision,
        )
        showAdvancedImport = false
        clearResolvedSource()
        browseResults = emptyList()
        browseMessage = null
        browseMessageIsError = false
        inspectPinnedSource(selectedState)
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
        lastDownloadRequest = HuggingFaceDownloadRequest(reference, descriptor)
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

    val installableFiles: (HuggingFaceRepositoryMetadata) -> List<dev.androml.core.model.HuggingFaceFileDescriptor> = { metadata ->
        metadata.files
            .filter { it.sha256 != null }
            .filter { descriptor ->
                ModelFormatClassifier.forPath(descriptor.path)?.let { format ->
                    val pack = RuntimePackCatalog.production.firstOrNull {
                        it.descriptor.id.value == format.runtimeId
                    }
                    pack?.usable == true &&
                        pack.descriptor.supportedAbis.intersect(deviceProfile.supportedAbis.toSet()).isNotEmpty()
                } ?: false
            }
            .sortedWith(compareBy({ it.sizeBytes }, { it.path }))
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Hugging Face direct import", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Pick a model that fits this device. AndroML pins the repository, checks its files, and downloads only a verified model artifact.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Find a model", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (searchQuery.isBlank()) {
                            "Recommended models for ${deviceProfile.deviceName}"
                        } else {
                            "Search results for \"${searchQuery.trim()}\""
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (searchQuery.isBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Based on this phone's memory, CPU architecture, and bundled runtimes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it.take(256) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Search by model name or task") },
                        placeholder = { Text("Leave blank for recommendations") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HuggingFaceModelSort.entries.forEach { sort ->
                            FilterChip(
                                selected = browseSort == sort,
                                onClick = {
                                    browseSortKey = sort.name
                                    if (searchQuery.isNotBlank()) searchHub(sort)
                                },
                                label = { Text(sort.label) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { searchHub(browseSort) },
                        enabled = !browsing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (browsing) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(20.dp).height(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Outlined.Search, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (searchQuery.isBlank()) "Refresh recommendations" else "Search Hugging Face")
                    }
                    browseMessage?.let { message ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (browseMessageIsError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (importState.reference == null && !showAdvancedImport) {
            item {
                TextButton(onClick = { showAdvancedImport = true }) {
                    Icon(Icons.Outlined.PushPin, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Advanced: enter a repository and commit manually")
                }
            }
        }
        if (showAdvancedImport) {
            item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("Selected source", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showAdvancedImport = false }) { Text("Close") }
                    }
                    Text(
                        "Selecting a result above fills this in and starts inspection automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                        onClick = { inspectPinnedSource() },
                        enabled = metadataState !is HuggingFaceMetadataUiState.Loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (metadataState is HuggingFaceMetadataUiState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(20.dp).height(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Outlined.Search, contentDescription = null)
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
        }
        if (importState.reference != null && !showAdvancedImport) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text("Pinned source", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { showAdvancedImport = true }) { Text("Edit") }
                        }
                        Text(importState.modelId, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Commit ${importState.revision.take(12)}… · inspection started automatically",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Safe downloads", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Public models work without a token. For gated models, approve access on Hugging Face and optionally save a read token. AndroML keeps it in Android Keystore storage and never puts it in a URL.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (browsing && browseResults.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(16.dp)) {
                        CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Loading models that match the bundled engines…")
                    }
                }
            }
        }
        if (browseResults.isNotEmpty()) {
            item {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (searchQuery.isBlank()) "Recommended for this device" else "Search results",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${browseResults.size} models",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(
                items = browseResults,
                key = { result -> "${result.modelId}@${result.revision.orEmpty()}" },
            ) { result ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(result.modelId, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            buildString {
                                result.pipelineTag?.let { append(it.replace('-', ' ')) }
                                result.downloads?.let { if (isNotEmpty()) append(" · "); append("${formatCount(it)} downloads") }
                                result.likes?.let { if (isNotEmpty()) append(" · "); append("${formatCount(it)} likes") }
                            }.ifBlank { "Public model" }.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val formats = modelFormatLabels(result)
                        if (formats.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                formats.forEach { format ->
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                    ) {
                                        Text(
                                            format,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { selectSearchResult(result) },
                            enabled = result.revision != null && !browsing,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Search, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Inspect and choose a model file")
                        }
                    }
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
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text("Ready to install", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
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
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Choose one compatible model artifact below. AndroML verifies its size and SHA-256 before it appears in Library.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                val runnableFiles = installableFiles(state.metadata)
                if (runnableFiles.isEmpty()) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "This repository has no compatible, integrity-verifiable model file for this device. Try a result marked GGUF, ONNX, TFLite, or ExecuTorch.",
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                items(runnableFiles, key = { it.path }) { descriptor ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(descriptor.path, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text("${formatBytes(descriptor.sizeBytes)} · verified integrity available", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    downloadFile(state.metadata.reference, descriptor)
                                },
                                enabled = descriptor.sha256 != null &&
                                    downloadState !is HuggingFaceDownloadUiState.Running,
                            ) {
                                if (descriptor.sha256 != null) {
                                    Icon(Icons.Outlined.Download, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text("Install this model")
                            }
                        }
                    }
                }
                val hiddenFileCount = state.metadata.files.size - runnableFiles.size
                if (hiddenFileCount > 0) {
                    item {
                        Text(
                            "$hiddenFileCount repository files hidden because they are support files or not runnable on this device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                            Text(
                                if (state.bytesWritten == 0L) "Download queued" else "Downloading ${state.path}",
                                style = MaterialTheme.typography.titleMedium,
                            )
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
                                "The verified transfer continues in the background and can resume after a connection stop.",
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
                            lastDownloadRequest?.let { request ->
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { downloadFile(request.reference, request.descriptor) }) {
                                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Try again")
                                }
                            }
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
    onBrowseModels: () -> Unit,
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
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Inventory2,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("No models installed", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Search Hugging Face, inspect a pinned revision, then download a verified model file.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = onBrowseModels,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Explore, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Browse Hugging Face")
                        }
                    }
                }
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

private fun formatCount(count: Long): String = when {
    count >= 1_000_000_000L -> "%.1fB".format(Locale.ROOT, count / 1_000_000_000.0)
    count >= 1_000_000L -> "%.1fM".format(Locale.ROOT, count / 1_000_000.0)
    count >= 1_000L -> "%.1fK".format(Locale.ROOT, count / 1_000.0)
    else -> count.toString()
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
            onBrowseModels = {},
            onOpenPlayground = {},
        )
    }
}
