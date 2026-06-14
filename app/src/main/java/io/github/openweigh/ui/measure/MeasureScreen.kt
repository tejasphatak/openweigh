package io.github.openweigh.ui.measure

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.openweigh.ble.DiscoveredDevice
import io.github.openweigh.ble.model.ScaleReading
import io.github.openweigh.ui.common.Formatting
import io.github.openweigh.ui.theme.WeightReadoutStyle

/**
 * Home / Measure screen. A scan FAB opens a device-picker [ModalBottomSheet]; once connected the
 * live weight animates in a very large readout, and a stable reading auto-saves with a Snackbar
 * offering "View" to jump to its detail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasureScreen(
    onReadingClick: (String) -> Unit,
    viewModel: MeasureViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Auto-save snackbar with a "View" action that navigates to the new measurement.
    LaunchedEffect(state.savedMeasurementId) {
        val id = state.savedMeasurementId ?: return@LaunchedEffect
        val weight = state.savedWeightKg
        val result = snackbarHostState.showSnackbar(
            message = "Saved ${weight?.let { Formatting.weight(it) } ?: ""} kg",
            actionLabel = "View",
        )
        if (result == SnackbarResult.ActionPerformed) onReadingClick(id)
        viewModel.consumeSavedEvent()
    }

    LaunchedEffect(state.errorMessage) {
        val msg = state.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeError()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("OpenWeigh") })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.startScan() },
                icon = { Icon(Icons.Filled.BluetoothSearching, contentDescription = null) },
                text = { Text(if (state.isLive || state.isConnecting) "Rescan" else "Scan") },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = state.phase,
                transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
                label = "measure-phase",
            ) { phase ->
                when (phase) {
                    MeasurePhase.Connecting -> ConnectingContent(state.connectedDeviceName)
                    MeasurePhase.Live -> LiveReadoutContent(
                        reading = state.liveReading,
                        deviceName = state.connectedDeviceName,
                        onSave = viewModel::saveCurrentReading,
                    )
                    MeasurePhase.Saved -> SavedContent(state.savedWeightKg)
                    else -> IdleContent(
                        scanning = phase == MeasurePhase.Scanning,
                        onScan = viewModel::startScan,
                    )
                }
            }
        }

        if (state.pickerVisible) {
            DevicePickerSheet(
                sheetState = sheetState,
                scanning = state.isScanning,
                devices = state.devices,
                showAll = state.showAllDevices,
                onShowAllChange = viewModel::setShowAllDevices,
                onSelect = viewModel::connectTo,
                onDismiss = viewModel::dismissPicker,
            )
        }
    }
}

@Composable
private fun IdleContent(scanning: Boolean, onScan: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Outlined.MonitorWeight,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Step on your scale",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Tap Scan to find a nearby Bluetooth scale and start measuring.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onScan) {
            Icon(Icons.Filled.Bluetooth, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (scanning) "Scanning…" else "Scan for scale")
        }
    }
}

@Composable
private fun ConnectingContent(deviceName: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Connecting to ${deviceName ?: "scale"}…",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun LiveReadoutContent(
    reading: ScaleReading?,
    deviceName: String?,
    onSave: () -> Unit,
) {
    // Gently pulse the readout so it reads as "live".
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-scale",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (deviceName != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Bluetooth,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        AnimatedContent(
            targetState = reading?.weightKg,
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
            label = "weight",
        ) { kg ->
            Text(
                text = kg?.let { Formatting.weight(it) } ?: "—",
                style = WeightReadoutStyle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.scale(pulse),
            )
        }
        Text(
            text = "kg",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        Text(
            text = "Hold still — saving when the reading settles…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val fat = reading?.bodyFatPercent
        if (reading != null && fat != null) {
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                LiveMetric("Body fat", Formatting.percent(fat))
                reading.leanMassKg?.let { LiveMetric("Lean", Formatting.kgOrDash(it)) }
                reading.bodyWaterMassKg?.let { LiveMetric("Water", Formatting.kgOrDash(it)) }
            }
        }

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onSave) { Text("Save now") }
    }
}

@Composable
private fun LiveMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SavedContent(weightKg: Double?) {
    val scale by animateFloatAsState(targetValue = 1f, label = "saved-pop")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.scale(scale)) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = weightKg?.let { "${Formatting.weight(it)} kg saved" } ?: "Saved",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Your measurement is stored on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicePickerSheet(
    sheetState: androidx.compose.material3.SheetState,
    scanning: Boolean,
    devices: List<DiscoveredDevice>,
    showAll: Boolean,
    onShowAllChange: (Boolean) -> Unit,
    onSelect: (DiscoveredDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    // Recognized scales first, then by signal strength.
    val sorted = devices.sortedWith(
        compareByDescending<DiscoveredDevice> { it.isLikelyScale }.thenByDescending { it.rssi }
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (showAll) "Nearby devices" else "Nearby scales",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (scanning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            if (scanning) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Escape hatch for a scale that isn't recognized from its advertisement.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onShowAllChange(!showAll) }
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Show all devices", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Off shows only weight scales and named devices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = showAll, onCheckedChange = onShowAllChange)
            }
            HorizontalDivider()

            if (sorted.isEmpty()) {
                Text(
                    text = when {
                        scanning -> "Searching for scales…"
                        showAll -> "No devices found."
                        else -> "No scales found. Step on your scale to wake it, or turn on “Show all devices”."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                )
            } else {
                LazyColumn {
                    items(sorted, key = { it.address }) { device ->
                        ListItem(
                            headlineContent = { Text(device.name ?: "Unknown device") },
                            supportingContent = {
                                Text(
                                    if (device.isLikelyScale) "Weight scale · ${device.address}"
                                    else device.address
                                )
                            },
                            trailingContent = { Text("${device.rssi} dBm") },
                            leadingContent = {
                                Icon(
                                    imageVector = if (device.isLikelyScale) Icons.Filled.MonitorWeight else Icons.Filled.Bluetooth,
                                    contentDescription = null,
                                    tint = if (device.isLikelyScale) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            colors = ListItemDefaults.colors(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(device) },
                        )
                    }
                }
            }
        }
    }
}
