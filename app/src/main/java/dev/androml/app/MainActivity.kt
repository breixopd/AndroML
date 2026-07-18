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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.androml.core.device.AndroidDeviceProfileCollector
import dev.androml.core.model.DeviceProfile
import dev.androml.core.model.ReleasePolicy

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
                detail = "Hugging Face discovery and verified downloads are next.",
            )
        }
        item {
            StatusCard(
                title = "Runtime packs",
                value = "Foundation build",
                detail = "Runtime adapters will be added behind the isolated inference boundary.",
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
