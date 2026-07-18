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
import dev.androml.core.database.ModelFileEntity
import dev.androml.core.database.WorkflowDefinitionRepository
import dev.androml.core.workflow.WorkflowDefinition
import dev.androml.core.workflow.WorkflowDefinitionCodec
import dev.androml.core.workflow.WorkflowRunStatus
import dev.androml.core.workflow.WorkflowValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WorkflowScreen(
    modifier: Modifier = Modifier,
    controller: WorkflowController,
    definitionRepository: WorkflowDefinitionRepository,
    installedModelFiles: List<ModelFileEntity>,
) {
    val definitions by definitionRepository.observe().collectAsState(initial = emptyList())
    val lastRun by controller.lastRun.collectAsState()
    val runnableModels = remember(installedModelFiles) {
        installedModelFiles.filter {
            it.artifactSha256 != null && it.path.endsWith(".litertlm", ignoreCase = true)
        }.distinctBy(ModelFileEntity::artifactSha256)
    }
    var selectedWorkflowId by remember { mutableStateOf<String?>(null) }
    var selectedModelHash by remember { mutableStateOf<String?>(null) }
    var collectionId by remember { mutableStateOf("docs") }
    var input by remember { mutableStateOf("Explain what this model can do.") }
    var definitionJson by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val selectedDefinition = definitions.firstOrNull { it.id.value == selectedWorkflowId }

    LaunchedEffect(runnableModels) {
        if (runnableModels.none { it.artifactSha256 == selectedModelHash }) {
            selectedModelHash = runnableModels.firstOrNull()?.artifactSha256
        }
    }
    LaunchedEffect(selectedDefinition) {
        definitionJson = selectedDefinition?.let(WorkflowDefinitionCodec::encode).orEmpty()
    }

    fun reportFailure(error: Throwable, fallback: String) {
        message = error.message?.take(512) ?: fallback
    }

    fun saveModelTemplate() {
        val hash = selectedModelHash
        if (hash == null) {
            message = "Download and verify a .litertlm model before creating a model workflow"
            return
        }
        busy = true
        scope.launch {
            try {
                val definition = withContext(Dispatchers.IO) { controller.saveStarterModelWorkflow(hash) }
                selectedWorkflowId = definition.id.value
                message = "Starter model workflow saved"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportFailure(error, "Workflow could not be saved")
            } finally {
                busy = false
            }
        }
    }

    fun saveRagTemplate() {
        busy = true
        scope.launch {
            try {
                val definition = withContext(Dispatchers.IO) {
                    controller.saveStarterRagWorkflow(collectionId.trim())
                }
                selectedWorkflowId = definition.id.value
                message = "Starter RAG workflow saved"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportFailure(error, "Workflow could not be saved")
            } finally {
                busy = false
            }
        }
    }

    fun importDefinition() {
        busy = true
        scope.launch {
            try {
                val definition = withContext(Dispatchers.IO) {
                    WorkflowDefinitionCodec.decode(definitionJson)
                }
                withContext(Dispatchers.IO) { controller.save(definition) }
                selectedWorkflowId = definition.id.value
                message = "Workflow JSON validated and saved"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportFailure(error, "Workflow JSON is invalid")
            } finally {
                busy = false
            }
        }
    }

    fun runWorkflow() {
        val definition = selectedDefinition
        if (definition == null) {
            message = "Select or save a workflow first"
            return
        }
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    controller.run(definition, WorkflowValue.Text(input.trim()))
                }
                message = "Workflow ${result.status.name.lowercase()}"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportFailure(error, "Workflow could not run")
            } finally {
                busy = false
            }
        }
    }

    fun approveWorkflow() {
        val definition = selectedDefinition
        val run = lastRun
        val node = run?.waitingForNode
        if (definition == null || run == null || node == null) {
            message = "No workflow approval is waiting"
            return
        }
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    controller.approve(
                        definition = definition,
                        runId = run.runId,
                        nodeId = node,
                        input = WorkflowValue.Text(input.trim()),
                    )
                }
                message = "Approval submitted; workflow ${result.status.name.lowercase()}"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportFailure(error, "Workflow approval could not be submitted")
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
            Text("Workflows", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Durable, bounded runs for models, agents, RAG, tools, branches, loops, and approvals. Model stages can be placed on trusted paired phones.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Create a starter workflow", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (runnableModels.isEmpty()) {
                        Text("No verified .litertlm model is installed yet.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Model artifact", style = MaterialTheme.typography.labelMedium)
                        runnableModels.forEach { file ->
                            val hash = file.artifactSha256 ?: return@forEach
                            FilterChip(
                                selected = selectedModelHash == hash,
                                onClick = { selectedModelHash = hash },
                                label = { Text(hash.take(16) + "…") },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = ::saveModelTemplate, enabled = !busy) {
                            Text("Save model workflow")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = collectionId,
                        onValueChange = { collectionId = it.take(64) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("RAG collection ID") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = ::saveRagTemplate, enabled = !busy) {
                        Text("Save RAG workflow")
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Run selected workflow", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.take(64 * 1024) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Text input") },
                        minLines = 3,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = ::runWorkflow, enabled = !busy && selectedDefinition != null) {
                            if (busy) CircularProgressIndicator() else Text("Run")
                        }
                        if (lastRun?.status == WorkflowRunStatus.WaitingForApproval) {
                            TextButton(onClick = ::approveWorkflow, enabled = !busy) { Text("Approve") }
                        }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Power-user JSON editor", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("Import a versioned WorkflowDefinition JSON document to author advanced graphs.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = definitionJson,
                        onValueChange = { definitionJson = it.take(WorkflowDefinitionCodec.MAX_PAYLOAD_CHARS) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 6,
                        label = { Text("Workflow JSON") },
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = ::importDefinition, enabled = !busy && definitionJson.isNotBlank()) {
                        Text("Validate and save JSON")
                    }
                }
            }
        }
        item { Text("Saved definitions", style = MaterialTheme.typography.titleMedium) }
        if (definitions.isEmpty()) {
            item { Text("No definitions saved yet.") }
        } else {
            items(definitions, key = { "${it.id.value}:${it.version}" }) { definition ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "${definition.id.value} v${definition.version}",
                            fontWeight = FontWeight.Bold,
                        )
                        Text("${definition.nodes.size} node(s) · ${definition.edges.size} edge(s)")
                        TextButton(onClick = { selectedWorkflowId = definition.id.value }) {
                            Text(if (selectedDefinition?.id == definition.id) "Selected" else "Select")
                        }
                    }
                }
            }
        }
        lastRun?.let { run ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Latest run", style = MaterialTheme.typography.titleMedium)
                        Text("${run.runId.value} · ${run.status.name}")
                        run.waitingForNode?.let { Text("Waiting at ${it.value}") }
                        run.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        run.output?.let { output ->
                            Spacer(Modifier.height(8.dp))
                            Text("Output", style = MaterialTheme.typography.labelLarge)
                            SelectionContainer { Text(formatOutput(output)) }
                        }
                    }
                }
            }
        }
        message?.let { currentMessage ->
            item {
                Text(
                    currentMessage,
                    color = if (currentMessage.contains("invalid", true) || currentMessage.contains("could not", true)) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }
}

private fun formatOutput(value: WorkflowValue): String = when (value) {
    WorkflowValue.UnitValue -> "(unit)"
    is WorkflowValue.Text -> value.value
    is WorkflowValue.JsonValue -> value.value.toString()
    is WorkflowValue.Documents -> value.items.joinToString("\n\n") {
        "${it.title} · ${it.sourceLabel}\n${it.text}"
    }
    is WorkflowValue.BooleanValue -> value.value.toString()
}
