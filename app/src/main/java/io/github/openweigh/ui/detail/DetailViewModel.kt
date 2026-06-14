package io.github.openweigh.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.openweigh.ble.model.ScaleReading
import io.github.openweigh.data.db.UserProfileDao
import io.github.openweigh.data.repo.Measurement
import io.github.openweigh.data.repo.MeasurementRepository
import io.github.openweigh.health.HealthConnectManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the Detail screen for a single measurement: load, in-place edit of weight + body-fat,
 * delete, and a one-tap "export to Health Connect now". Sync status (Health Connect / Drive) is
 * surfaced as AssistChips driven by [Measurement.syncedHealthConnect] / [syncedDrive].
 *
 * The id is supplied by the screen via [load] (the NavHost owns the route arg).
 */
@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repository: MeasurementRepository,
    private val profileDao: UserProfileDao,
    private val healthConnectManager: HealthConnectManager,
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private var loadedId: String? = null

    /** Load the measurement for [id]; idempotent so it's safe from a LaunchedEffect. */
    fun load(id: String) {
        if (loadedId == id && _state.value.measurement != null) return
        loadedId = id
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val measurement = repository.getById(id)
            val heightCm = profileDao.observe().first()?.heightCm
            _state.update {
                it.copy(
                    loading = false,
                    measurement = measurement,
                    notFound = measurement == null,
                    heightCm = heightCm,
                    healthConnectAvailable = healthConnectManager.isAvailable(),
                )
            }
        }
    }

    fun beginEdit() {
        val m = _state.value.measurement ?: return
        _state.update {
            it.copy(
                editing = true,
                editWeightKg = oneDecimal(m.reading.weightKg),
                editBodyFat = m.reading.bodyFatPercent?.let { v -> oneDecimal(v) } ?: "",
            )
        }
    }

    fun cancelEdit() {
        _state.update { it.copy(editing = false) }
    }

    fun onEditWeightChange(value: String) = _state.update { it.copy(editWeightKg = value) }
    fun onEditBodyFatChange(value: String) = _state.update { it.copy(editBodyFat = value) }

    /** Validate + persist the edited fields. */
    fun saveEdit() {
        val m = _state.value.measurement ?: return
        val weight = _state.value.editWeightKg.trim().toDoubleOrNull()
        if (weight == null || weight <= 0.0) {
            _state.update { it.copy(message = "Enter a valid weight.") }
            return
        }
        val fat = _state.value.editBodyFat.trim().let { if (it.isEmpty()) null else it.toDoubleOrNull() }
        if (_state.value.editBodyFat.isNotBlank() && fat == null) {
            _state.update { it.copy(message = "Body fat must be a number or blank.") }
            return
        }

        // Edits invalidate prior syncs so downstream syncers re-export the new values.
        val updated = m.copy(
            reading = m.reading.copy(weightKg = weight, bodyFatPercent = fat),
            syncedHealthConnect = false,
            syncedDrive = false,
        )
        viewModelScope.launch {
            repository.upsert(updated)
            _state.update {
                it.copy(measurement = updated, editing = false, message = "Saved changes")
            }
        }
    }

    fun delete() {
        val id = _state.value.measurement?.id ?: return
        viewModelScope.launch {
            repository.delete(id)
            _state.update { it.copy(deleted = true) }
        }
    }

    /** Permissions the Health Connect launcher should request. */
    fun healthConnectPermissions(): Set<String> = healthConnectManager.permissions

    /** Contract the screen registers for the Health Connect permission request. */
    fun healthConnectContract() = healthConnectManager.permissionContract()

    /** Intent to open Health Connect's settings, where the user grants/revokes this app's access. */
    fun healthConnectSettingsIntent() = healthConnectManager.manageDataIntent()

    /** Intent to install/update the Health Connect provider. */
    fun healthConnectInstallIntent() = healthConnectManager.installOrUpdateIntent()

    /**
     * Export this measurement to Health Connect. If the provider isn't installed, send the user to
     * install it; if permissions aren't granted yet, drive them to the permission screen (rather
     * than just showing a message), then export once granted.
     */
    fun exportToHealthConnect() {
        val m = _state.value.measurement ?: return
        if (!healthConnectManager.isAvailable()) {
            _state.update { it.copy(launchInstall = true, message = "Install Health Connect to export.") }
            return
        }
        viewModelScope.launch {
            if (healthConnectManager.hasAllPermissions()) {
                doExport(m)
            } else {
                // Redirect to the Health Connect permission screen instead of just messaging.
                _state.update { it.copy(requestHealthPermissions = true) }
            }
        }
    }

    /** Result of the Health Connect permission request launched by the screen. */
    fun onHealthPermissionResult(granted: Set<String>) {
        _state.update { it.copy(requestHealthPermissions = false) }
        if (granted.containsAll(healthConnectManager.permissions)) {
            val m = _state.value.measurement ?: return
            viewModelScope.launch { doExport(m) }
        } else {
            // Still not granted (e.g. dismissed/denied) — send them to HC settings to grant it there.
            _state.update {
                it.copy(openHealthSettings = true, message = "Grant Health Connect access to export.")
            }
        }
    }

    private suspend fun doExport(m: Measurement) {
        _state.update { it.copy(syncing = true) }
        val result = runCatching {
            healthConnectManager.writeMeasurement(m)
            repository.markSyncedHealthConnect(listOf(m.id))
        }
        _state.update {
            it.copy(
                syncing = false,
                measurement = if (result.isSuccess) it.measurement?.copy(syncedHealthConnect = true) else it.measurement,
                message = if (result.isSuccess) "Exported to Health Connect"
                else result.exceptionOrNull()?.message ?: "Export failed",
            )
        }
    }

    fun consumeRequestHealthPermissions() = _state.update { it.copy(requestHealthPermissions = false) }
    fun consumeOpenHealthSettings() = _state.update { it.copy(openHealthSettings = false) }
    fun consumeLaunchInstall() = _state.update { it.copy(launchInstall = false) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun oneDecimal(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)
}

data class DetailUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val deleted: Boolean = false,
    val measurement: Measurement? = null,
    val heightCm: Double? = null,
    val healthConnectAvailable: Boolean = false,
    val syncing: Boolean = false,
    // One-shot redirects to the Health Connect permission grant / settings / install screens.
    val requestHealthPermissions: Boolean = false,
    val openHealthSettings: Boolean = false,
    val launchInstall: Boolean = false,
    // Edit mode
    val editing: Boolean = false,
    val editWeightKg: String = "",
    val editBodyFat: String = "",
    val message: String? = null,
) {
    val reading: ScaleReading? get() = measurement?.reading
}
