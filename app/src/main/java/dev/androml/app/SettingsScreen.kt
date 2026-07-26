package dev.androml.app

import android.content.Context
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.androml.core.model.AppSettings
import dev.androml.core.model.DeviceProfile
import dev.androml.core.model.ReleasePolicy
import dev.androml.runtime.api.RuntimePackInfo

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    deviceProfile: DeviceProfile,
    releasePolicy: ReleasePolicy,
    runtimePacks: List<RuntimePackInfo>,
    apiState: LocalApiState,
    settings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit,
) {
    fun update(next: AppSettings) {
        onSettingsChanged(next)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Power-user controls are visible during the private phone-test period. Changes are stored locally on this device.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Product controls", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    SettingToggle(
                        title = "Expert mode",
                        detail = "Keep runtime, API, cluster, RAG, tools, and workflow controls visible.",
                        checked = settings.expertMode,
                        onCheckedChange = { update(settings.copy(expertMode = it)) },
                    )
                    SettingToggle(
                        title = "Auto-optimise runtime",
                        detail = "Pick a compatible bundled engine using device memory, ABI, thermal, and benchmark data.",
                        checked = settings.autoOptimize,
                        onCheckedChange = { update(settings.copy(autoOptimize = it)) },
                    )
                    SettingToggle(
                        title = "Background downloads",
                        detail = "Allow WorkManager to resume verified Hugging Face downloads after navigation or reconnects.",
                        checked = settings.allowBackgroundDownloads,
                        onCheckedChange = { update(settings.copy(allowBackgroundDownloads = it)) },
                    )
                    SettingToggle(
                        title = "Thermal guard",
                        detail = "Reduce threads or refuse runs when the device reports severe thermal pressure.",
                        checked = settings.thermalGuard,
                        onCheckedChange = { update(settings.copy(thermalGuard = it)) },
                    )
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Release and privacy gate", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(releasePolicy.storeSubmissionStatus, fontWeight = FontWeight.Bold)
                    Text("Store submissions are disabled in this build. Only owner-approved GitHub Releases are allowed during phone testing.")
                    Spacer(Modifier.height(6.dp))
                    Text("No telemetry, model bytes, prompts, or API credentials leave the app unless you explicitly enable a network API or paired cluster.")
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Device diagnostics", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(deviceProfile.deviceName)
                    Text(deviceProfile.resourceSummary, style = MaterialTheme.typography.bodySmall)
                    Text("Readiness: ${deviceProfile.readiness}", style = MaterialTheme.typography.bodySmall)
                    Text("API server: ${apiStateLabel(apiState)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Text("Runtime packs", style = MaterialTheme.typography.titleMedium) }
        items(runtimePacks, key = { it.descriptor.id.value }) { pack ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(pack.descriptor.id.value, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Text(if (pack.usable) "bundled" else "not bundled", style = MaterialTheme.typography.labelMedium)
                    }
                    Text("${pack.descriptor.version} · ${pack.note}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Workloads: ${pack.descriptor.workloads.joinToString { it.name }} · ABI: ${pack.descriptor.supportedAbis.joinToString()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingToggle(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    Spacer(Modifier.height(8.dp))
}

private fun apiStateLabel(state: LocalApiState): String = when (state) {
    LocalApiState.Disabled -> "disabled"
    is LocalApiState.Running -> "${state.bindMode.name.lowercase()} ${state.host}:${state.port}"
    is LocalApiState.Failed -> "failed"
}

internal object AppSettingsStore {
    private const val SETTINGS_PREFERENCES = "androml-settings"
    private const val KEY_EXPERT_MODE = "expert_mode"
    private const val KEY_AUTO_OPTIMIZE = "auto_optimize"
    private const val KEY_BACKGROUND_DOWNLOADS = "background_downloads"
    private const val KEY_THERMAL_GUARD = "thermal_guard"

    fun load(context: Context): AppSettings {
        val preferences = context.getSharedPreferences(SETTINGS_PREFERENCES, Context.MODE_PRIVATE)
        return AppSettings(
            expertMode = preferences.getBoolean(KEY_EXPERT_MODE, true),
            autoOptimize = preferences.getBoolean(KEY_AUTO_OPTIMIZE, true),
            allowBackgroundDownloads = preferences.getBoolean(KEY_BACKGROUND_DOWNLOADS, true),
            thermalGuard = preferences.getBoolean(KEY_THERMAL_GUARD, true),
        )
    }

    fun save(context: Context, settings: AppSettings) {
        context.getSharedPreferences(SETTINGS_PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_EXPERT_MODE, settings.expertMode)
            .putBoolean(KEY_AUTO_OPTIMIZE, settings.autoOptimize)
            .putBoolean(KEY_BACKGROUND_DOWNLOADS, settings.allowBackgroundDownloads)
            .putBoolean(KEY_THERMAL_GUARD, settings.thermalGuard)
            .apply()
    }
}
