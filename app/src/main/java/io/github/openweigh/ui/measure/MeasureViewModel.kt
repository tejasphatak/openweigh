package io.github.openweigh.ui.measure

import android.annotation.SuppressLint
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.openweigh.ble.BleScanner
import io.github.openweigh.ble.DiscoveredDevice
import io.github.openweigh.ble.ScaleConnection
import io.github.openweigh.ble.model.ScaleReading
import io.github.openweigh.ble.model.Sex
import io.github.openweigh.ble.model.UserProfile
import io.github.openweigh.data.db.UserProfileDao
import io.github.openweigh.data.repo.MeasurementRepository
import io.github.openweigh.sync.SyncCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.abs

/**
 * Drives the Measure screen: BLE device scanning, live GATT streaming, stability detection,
 * and auto-save of a settled reading.
 *
 * State machine (see [MeasurePhase]):
 *  - Idle            → nothing happening, prompt the user to scan.
 *  - Scanning        → BLE scan running; discovered devices accumulate in [MeasureUiState.devices].
 *  - Connecting      → a device was picked; GATT connect in flight.
 *  - Live            → readings streaming; [MeasureUiState.liveReading] updates; once the weight
 *                      holds steady for [STABLE_WINDOW_MS] we auto-save and surface a snackbar.
 *  - Saved           → a stable reading was persisted; connection torn down.
 *  - Error           → scan/connection failed; message in [MeasureUiState.errorMessage].
 */
@HiltViewModel
class MeasureViewModel @Inject constructor(
    private val scanner: BleScanner,
    private val connection: ScaleConnection,
    private val repository: MeasurementRepository,
    private val profileDao: UserProfileDao,
    private val syncCoordinator: SyncCoordinator,
) : ViewModel() {

    private val _state = MutableStateFlow(MeasureUiState())
    val state: StateFlow<MeasureUiState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var connectJob: Job? = null

    /** Rolling samples used to decide when a reading has stabilised. */
    private val recentWeights = ArrayDeque<TimedWeight>()

    init {
        _state.update {
            it.copy(
                bluetoothEnabled = scanner.isBluetoothEnabled,
                hasScanPermission = scanner.hasScanPermission,
            )
        }
    }

    /** Begin (or restart) a BLE scan and open the device picker sheet. */
    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!scanner.hasScanPermission) {
            _state.update {
                it.copy(
                    hasScanPermission = false,
                    errorMessage = "Bluetooth permission is required to scan for scales.",
                )
            }
            return
        }
        if (!scanner.isBluetoothEnabled) {
            _state.update {
                it.copy(
                    bluetoothEnabled = false,
                    errorMessage = "Turn on Bluetooth to scan for scales.",
                )
            }
            return
        }

        scanJob?.cancel()
        _state.update {
            it.copy(
                phase = MeasurePhase.Scanning,
                pickerVisible = true,
                devices = emptyList(),
                errorMessage = null,
                bluetoothEnabled = true,
                hasScanPermission = true,
            )
        }
        scanJob = viewModelScope.launch {
            scanner.scan(showAll = _state.value.showAllDevices)
                .catch { t ->
                    _state.update {
                        it.copy(
                            phase = MeasurePhase.Error,
                            pickerVisible = false,
                            errorMessage = t.message ?: "Scan failed.",
                        )
                    }
                }
                .collect { device ->
                    _state.update { s ->
                        if (s.devices.any { it.address == device.address }) {
                            s
                        } else {
                            s.copy(devices = (s.devices + device).sortedByDescending { it.rssi })
                        }
                    }
                }
        }
    }

    /** Stop scanning and close the picker without connecting. */
    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _state.update {
            it.copy(
                pickerVisible = false,
                phase = if (it.phase == MeasurePhase.Scanning) MeasurePhase.Idle else it.phase,
            )
        }
    }

    /** User dismissed the device-picker bottom sheet. */
    fun dismissPicker() = stopScan()

    /** Toggle showing all nearby devices vs. only recognized scales + named devices. Restarts scan. */
    @SuppressLint("MissingPermission")
    fun setShowAllDevices(show: Boolean) {
        if (_state.value.showAllDevices == show) return
        _state.update { it.copy(showAllDevices = show) }
        if (_state.value.pickerVisible) startScan()
    }

    /** Connect to a chosen device and start streaming readings. */
    @SuppressLint("MissingPermission")
    fun connectTo(device: DiscoveredDevice) {
        scanJob?.cancel()
        scanJob = null
        connectJob?.cancel()
        recentWeights.clear()

        _state.update {
            it.copy(
                phase = MeasurePhase.Connecting,
                pickerVisible = false,
                connectedDeviceName = device.name ?: device.address,
                liveReading = null,
                errorMessage = null,
            )
        }

        connectJob = viewModelScope.launch {
            val profile = loadProfile()
            connection.connect(device.address, profile)
                .catch { t ->
                    _state.update {
                        it.copy(
                            phase = MeasurePhase.Error,
                            errorMessage = t.message ?: "Connection lost.",
                        )
                    }
                }
                .collect { reading -> onReading(reading) }
        }
    }

    private fun onReading(reading: ScaleReading) {
        _state.update {
            it.copy(phase = MeasurePhase.Live, liveReading = reading, errorMessage = null)
        }

        val now = System.currentTimeMillis()
        recentWeights.addLast(TimedWeight(now, reading.weightKg))
        while (recentWeights.isNotEmpty() && now - recentWeights.first().timeMillis > STABLE_WINDOW_MS) {
            recentWeights.removeFirst()
        }

        val span = now - recentWeights.first().timeMillis
        val min = recentWeights.minOf { it.weightKg }
        val max = recentWeights.maxOf { it.weightKg }
        val stable = span >= STABLE_WINDOW_MS &&
            recentWeights.size >= MIN_STABLE_SAMPLES &&
            abs(max - min) <= STABLE_TOLERANCE_KG &&
            reading.weightKg > 0.0

        if (stable) {
            autoSave(reading)
        }
    }

    private fun autoSave(reading: ScaleReading) {
        connectJob?.cancel()
        connectJob = null
        recentWeights.clear()
        viewModelScope.launch {
            val measurement = repository.saveReading(reading)
            // Best-effort: push to Health Connect now (if connected) and debounce a Drive backup.
            syncCoordinator.onReadingSaved(measurement)
            _state.update {
                it.copy(
                    phase = MeasurePhase.Saved,
                    savedMeasurementId = measurement.id,
                    savedWeightKg = reading.weightKg,
                )
            }
        }
    }

    /** Manually persist the current live reading (e.g. a scale that never reports "stable"). */
    fun saveCurrentReading() {
        val reading = _state.value.liveReading ?: return
        autoSave(reading)
    }

    /** Tear down any active connection and return to idle (used when leaving the screen). */
    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        recentWeights.clear()
        _state.update {
            it.copy(phase = MeasurePhase.Idle, liveReading = null, connectedDeviceName = null)
        }
    }

    /** Acknowledge the auto-save snackbar so it isn't re-shown on recomposition. */
    fun consumeSavedEvent() {
        _state.update { it.copy(savedMeasurementId = null, savedWeightKg = null) }
    }

    fun consumeError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private suspend fun loadProfile(): UserProfile? {
        val entity = profileDao.observe().first() ?: return null
        return runCatching {
            UserProfile(
                heightCm = entity.heightCm,
                sex = Sex.valueOf(entity.sex),
                birthDate = LocalDate.ofEpochDay(entity.birthEpochDay),
            )
        }.getOrNull()
    }

    override fun onCleared() {
        scanJob?.cancel()
        connectJob?.cancel()
        super.onCleared()
    }

    private data class TimedWeight(val timeMillis: Long, val weightKg: Double)

    companion object {
        /** A weight must hold within tolerance for this long to count as stable. */
        private const val STABLE_WINDOW_MS = 2_500L
        private const val MIN_STABLE_SAMPLES = 3
        private const val STABLE_TOLERANCE_KG = 0.1
    }
}

/** The high-level phase the Measure screen is in. */
enum class MeasurePhase { Idle, Scanning, Connecting, Live, Saved, Error }

/** Immutable UI state for the Measure screen. */
data class MeasureUiState(
    val phase: MeasurePhase = MeasurePhase.Idle,
    val bluetoothEnabled: Boolean = false,
    val hasScanPermission: Boolean = false,
    val pickerVisible: Boolean = false,
    val showAllDevices: Boolean = false,
    val devices: List<DiscoveredDevice> = emptyList(),
    val connectedDeviceName: String? = null,
    val liveReading: ScaleReading? = null,
    /** Non-null exactly once after a successful auto-save — drives the snackbar + nav affordance. */
    val savedMeasurementId: String? = null,
    val savedWeightKg: Double? = null,
    val errorMessage: String? = null,
) {
    val isScanning: Boolean get() = phase == MeasurePhase.Scanning
    val isConnecting: Boolean get() = phase == MeasurePhase.Connecting
    val isLive: Boolean get() = phase == MeasurePhase.Live
}
