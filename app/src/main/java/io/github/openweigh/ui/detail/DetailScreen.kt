package io.github.openweigh.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.openweigh.data.repo.Measurement
import io.github.openweigh.ui.common.Formatting

/**
 * Detail / edit screen for a single measurement. Shows the full body-composition breakdown,
 * sync-status AssistChips (Health Connect, Drive), and supports inline edit + delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    id: String,
    onNavigateUp: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(id) { viewModel.load(id) }

    LaunchedEffect(state.deleted) { if (state.deleted) onNavigateUp() }

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.editing) "Edit measurement" else "Measurement") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.measurement != null && !state.editing) {
                        IconButton(onClick = viewModel::beginEdit) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit")
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            state.loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.notFound || state.measurement == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { Text("Measurement not found.", style = MaterialTheme.typography.bodyLarge) }

            else -> DetailContent(
                measurement = state.measurement!!,
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                onEditWeight = viewModel::onEditWeightChange,
                onEditBodyFat = viewModel::onEditBodyFatChange,
                onSaveEdit = viewModel::saveEdit,
                onCancelEdit = viewModel::cancelEdit,
                onExportHealth = viewModel::exportToHealthConnect,
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete measurement?") },
            text = { Text("This removes it from this device. Synced copies in Health Connect or Drive are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailContent(
    measurement: Measurement,
    state: DetailUiState,
    modifier: Modifier,
    onEditWeight: (String) -> Unit,
    onEditBodyFat: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onExportHealth: () -> Unit,
) {
    val reading = measurement.reading

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Hero: big weight + timestamp.
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = Formatting.dateTime(measurement.timestamp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (state.editing) {
                    OutlinedTextField(
                        value = state.editWeightKg,
                        onValueChange = onEditWeight,
                        label = { Text("Weight (kg)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = Formatting.weight(reading.weightKg),
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            "kg",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Formatting.bmi(reading.weightKg, state.heightCm)?.let {
                        Text(
                            text = "BMI ${Formatting.bmiString(reading, state.heightCm)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (reading.estimated) {
                    Spacer(Modifier.height(8.dp))
                    AssistChip(
                        onClick = {},
                        label = { Text("Some values estimated") },
                        enabled = false,
                    )
                }
            }
        }

        // Body composition breakdown.
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Body composition", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                if (state.editing) {
                    OutlinedTextField(
                        value = state.editBodyFat,
                        onValueChange = onEditBodyFat,
                        label = { Text("Body fat (%)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    MetricRow("Body fat", Formatting.percent(reading.bodyFatPercent))
                    MetricRow("Lean mass", Formatting.kgOrDash(reading.leanMassKg))
                    MetricRow("Body water", Formatting.kgOrDash(reading.bodyWaterMassKg))
                    MetricRow("Bone mass", Formatting.kgOrDash(reading.boneMassKg))
                    MetricRow("Basal metabolism", Formatting.kcalOrDash(reading.basalMetabolismKcal))
                    MetricRow("Impedance", Formatting.ohmsOrDash(reading.impedanceOhm))
                    reading.sourceDevice?.let { MetricRow("Source", it) }
                }
            }
        }

        // Sync status chips.
        if (!state.editing) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SyncChip(
                            label = "Health Connect",
                            synced = measurement.syncedHealthConnect,
                        )
                        SyncChip(
                            label = "Drive",
                            synced = measurement.syncedDrive,
                        )
                    }
                    if (!measurement.syncedHealthConnect && state.healthConnectAvailable) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onExportHealth,
                            enabled = !state.syncing,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (state.syncing) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(8.dp))
                            }
                            Text("Export to Health Connect now")
                        }
                    }
                }
            }
        }

        // Edit action bar.
        if (state.editing) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onCancelEdit, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(onClick = onSaveEdit, modifier = Modifier.weight(1f)) {
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SyncChip(label: String, synced: Boolean) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(if (synced) "$label · synced" else "$label · not synced") },
        leadingIcon = {
            Icon(
                imageVector = if (synced) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
    )
}
