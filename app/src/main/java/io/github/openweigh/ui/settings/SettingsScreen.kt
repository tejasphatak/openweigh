package io.github.openweigh.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.openweigh.ble.model.Sex
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Settings screen. Sections: profile editor, paired scale / onboarding shortcut, Health Connect,
 * and Google account + Drive backup. All Google/Health features are optional — the app is fully
 * functional offline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenOnboarding: () -> Unit,
    onOpenBugReport: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Health Connect permission launcher.
    val healthLauncher = rememberLauncherForActivityResult(viewModel.healthConnectContract()) { granted ->
        viewModel.onHealthConnectPermissionResult(granted)
    }

    // Drive incremental-consent launcher.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        viewModel.completeScopeConsent(result.data)
    }

    LaunchedEffect(state.pendingConsent) {
        val pending = state.pendingConsent ?: return@LaunchedEffect
        consentLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
        viewModel.consumePendingConsent()
    }

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeMessage()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProfileCard(
                state = state,
                onHeightChange = viewModel::onHeightChange,
                onSexChange = viewModel::onSexChange,
                onBirthDateChange = viewModel::onBirthDateChange,
                onSave = viewModel::saveProfile,
            )

            ScaleCard(onOpenOnboarding = onOpenOnboarding)

            HealthConnectCard(
                state = state,
                onConnect = { healthLauncher.launch(viewModel.healthConnectPermissions()) },
                onInstall = viewModel::installHealthConnect,
                onBackfill = viewModel::backfillHealthConnect,
            )

            GoogleDriveCard(
                state = state,
                onSignIn = { viewModel.signInGoogle(context) },
                onSignOut = viewModel::signOutGoogle,
                onBackupToggle = viewModel::setBackupEnabled,
                onExportNow = viewModel::exportToDriveNow,
                onRestore = viewModel::restoreFromDrive,
            )

            SupportCard(onReportProblem = onOpenBugReport)

            Text(
                text = "OpenWeigh keeps your data on this device. Health Connect and Drive are optional.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

// --- Profile -------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileCard(
    state: SettingsUiState,
    onHeightChange: (String) -> Unit,
    onSexChange: (Sex) -> Unit,
    onBirthDateChange: (LocalDate) -> Unit,
    onSave: () -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }

    SectionCard(icon = { Icon(Icons.Outlined.Person, null) }, title = "Profile") {
        Text(
            "Used to derive metrics (BMI, body fat) and to label Health Connect records.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.heightCm,
            onValueChange = onHeightChange,
            label = { Text("Height (cm)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        Text("Sex", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            Sex.entries.forEachIndexed { index, sex ->
                SegmentedButton(
                    selected = state.sex == sex,
                    onClick = { onSexChange(sex) },
                    shape = SegmentedButtonDefaults.itemShape(index, Sex.entries.size),
                ) {
                    Text(if (sex == Sex.MALE) "Male" else "Female")
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text(state.birthDate?.let { "Birth date: ${dateFormat.format(it)}" } ?: "Set birth date")
        }
        Spacer(Modifier.height(12.dp))

        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.profileSaved) "Update profile" else "Save profile")
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.birthDate
                ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onBirthDateChange(
                            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate(),
                        )
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

// --- Paired scale --------------------------------------------------------------------------------

@Composable
private fun ScaleCard(onOpenOnboarding: () -> Unit) {
    SectionCard(icon = { Icon(Icons.Outlined.Bluetooth, null) }, title = "Scale") {
        Text(
            "Connect to a scale from the Measure tab. Re-run the setup walkthrough if you need to grant permissions again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onOpenOnboarding, modifier = Modifier.fillMaxWidth()) {
            Text("Open setup walkthrough")
        }
    }
}

// --- Help & feedback -----------------------------------------------------------------------------

@Composable
private fun SupportCard(onReportProblem: () -> Unit) {
    SectionCard(icon = { Icon(Icons.Outlined.BugReport, null) }, title = "Help & feedback") {
        Text(
            "Something not working? Generate a diagnostic report (app/device info + recent logs, no " +
                "personal data) that you can share to help get it fixed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onReportProblem, modifier = Modifier.fillMaxWidth()) {
            Text("Report a problem")
        }
    }
}

// --- Health Connect ------------------------------------------------------------------------------

@Composable
private fun HealthConnectCard(
    state: SettingsUiState,
    onConnect: () -> Unit,
    onInstall: () -> Unit,
    onBackfill: () -> Unit,
) {
    SectionCard(icon = { Icon(Icons.Outlined.FavoriteBorder, null) }, title = "Health Connect") {
        when {
            !state.healthConnectAvailable -> {
                Text(
                    "Health Connect isn't installed or needs an update.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                    Text("Install / update Health Connect")
                }
            }

            state.healthConnectConnected -> {
                StatusRow(connected = true, text = "Connected")
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onBackfill,
                    enabled = !state.healthBackfilling,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.healthBackfilling) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text("Backfill all measurements")
                }
            }

            else -> {
                Text(
                    "Export weight & body-composition records to Health Connect.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                    Text("Connect Health Connect")
                }
            }
        }
    }
}

// --- Google / Drive ------------------------------------------------------------------------------

@Composable
private fun GoogleDriveCard(
    state: SettingsUiState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onBackupToggle: (Boolean) -> Unit,
    onExportNow: () -> Unit,
    onRestore: () -> Unit,
) {
    SectionCard(icon = { Icon(Icons.Outlined.CloudUpload, null) }, title = "Google Drive backup") {
        if (!state.googleSignedIn) {
            Text(
                "Optionally back up to your own Google Drive. No app server is involved — data goes only to your account.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.AccountCircle, null)
                Spacer(Modifier.size(8.dp))
                Text("Sign in with Google")
            }
        } else {
            StatusRow(connected = true, text = state.googleEmail ?: "Signed in")
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Automatic backup", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Sync a hidden snapshot to your appdata folder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.backupEnabled || state.backupAuthorized,
                    onCheckedChange = onBackupToggle,
                )
            }
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onExportNow,
                    enabled = !state.driveBusy,
                    modifier = Modifier.weight(1f),
                ) { Text("Export now") }
                OutlinedButton(
                    onClick = onRestore,
                    enabled = !state.driveBusy,
                    modifier = Modifier.weight(1f),
                ) { Text("Restore") }
            }
            if (state.driveBusy) {
                Spacer(Modifier.height(8.dp))
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSignOut) { Text("Sign out") }
        }
    }
}

// --- Shared section scaffolding ------------------------------------------------------------------

@Composable
private fun SectionCard(
    icon: @Composable () -> Unit,
    title: String,
    content: @Composable () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.size(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun StatusRow(connected: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (connected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(8.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
