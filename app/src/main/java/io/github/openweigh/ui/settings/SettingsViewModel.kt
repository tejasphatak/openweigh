package io.github.openweigh.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.openweigh.ble.model.Sex
import io.github.openweigh.data.db.UserProfileDao
import io.github.openweigh.data.db.UserProfileEntity
import io.github.openweigh.data.repo.MeasurementRepository
import io.github.openweigh.drive.CloudAccountManager
import io.github.openweigh.drive.CloudBackup
import io.github.openweigh.health.HealthConnectManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * Backs the Settings screen: user-profile editor (height / sex / birthdate), Health Connect
 * connect + "Backfill all", Google account + Drive backup/export/restore.
 *
 * Drive incremental-consent round-trips need an Activity launcher the screen owns; the VM exposes
 * the pending [GoogleAuthManager.ScopeRequest] and a [completeScopeConsent] hook for the result.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val profileDao: UserProfileDao,
    private val repository: MeasurementRepository,
    private val healthConnectManager: HealthConnectManager,
    private val authManager: CloudAccountManager,
    private val backupService: CloudBackup,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val profile = profileDao.observe().first()
            _state.update {
                it.copy(
                    heightCm = profile?.heightCm?.let { h -> h.toInt().toString() } ?: "",
                    sex = profile?.let { p -> runCatching { Sex.valueOf(p.sex) }.getOrNull() } ?: Sex.MALE,
                    birthDate = profile?.birthEpochDay?.let(LocalDate::ofEpochDay),
                    profileSaved = profile != null,
                    healthConnectAvailable = healthConnectManager.isAvailable(),
                )
            }
            refreshHealthConnectStatus()
        }
        viewModelScope.launch {
            authManager.currentAccount.collect { account ->
                _state.update {
                    it.copy(
                        cloudSupported = authManager.isSupported,
                        googleEmail = account?.email,
                        googleSignedIn = account != null,
                        backupAuthorized = authManager.isBackupAuthorized,
                    )
                }
            }
        }
    }

    // ---- Profile ----------------------------------------------------------------------------

    fun onHeightChange(value: String) = _state.update { it.copy(heightCm = value.filter { c -> c.isDigit() }) }
    fun onSexChange(sex: Sex) = _state.update { it.copy(sex = sex) }
    fun onBirthDateChange(date: LocalDate) = _state.update { it.copy(birthDate = date) }

    fun saveProfile() {
        val height = _state.value.heightCm.toDoubleOrNull()
        val birth = _state.value.birthDate
        if (height == null || height !in 50.0..272.0) {
            _state.update { it.copy(message = "Enter a height between 50 and 272 cm.") }
            return
        }
        if (birth == null || birth.isAfter(LocalDate.now())) {
            _state.update { it.copy(message = "Pick a valid birth date.") }
            return
        }
        viewModelScope.launch {
            profileDao.upsert(
                UserProfileEntity(
                    heightCm = height,
                    sex = _state.value.sex.name,
                    birthEpochDay = birth.toEpochDay(),
                ),
            )
            _state.update { it.copy(profileSaved = true, message = "Profile saved") }
        }
    }

    // ---- Health Connect ---------------------------------------------------------------------

    private fun refreshHealthConnectStatus() {
        viewModelScope.launch {
            val available = healthConnectManager.isAvailable()
            _state.update {
                it.copy(
                    healthConnectAvailable = available,
                    healthConnectConnected = available && healthConnectManager.hasAllPermissions(),
                )
            }
        }
    }

    /** Permissions needed by the Health Connect permission launcher. */
    fun healthConnectPermissions(): Set<String> = healthConnectManager.permissions

    /** Build the ActivityResultContract the screen registers for the HC permission flow. */
    fun healthConnectContract() = healthConnectManager.permissionContract()

    fun onHealthConnectPermissionResult(granted: Set<String>) {
        val ok = granted.containsAll(healthConnectManager.permissions)
        _state.update {
            it.copy(
                healthConnectConnected = ok,
                message = if (ok) "Health Connect connected" else "Some permissions were not granted",
            )
        }
    }

    /** Open the Play Store to install/update Health Connect (API < 34). */
    fun installHealthConnect() = healthConnectManager.launchInstallOrUpdate()

    /** Export every stored measurement to Health Connect. */
    fun backfillHealthConnect() {
        viewModelScope.launch {
            _state.update { it.copy(healthBackfilling = true) }
            val result = runCatching {
                if (!healthConnectManager.isAvailable()) error("Health Connect not available.")
                if (!healthConnectManager.hasAllPermissions()) error("Connect Health Connect first.")
                val all = repository.observeAll().first()
                healthConnectManager.writeAll(all)
                repository.markSyncedHealthConnect(all.map { it.id })
                all.size
            }
            _state.update {
                it.copy(
                    healthBackfilling = false,
                    message = result.fold(
                        onSuccess = { n -> "Backfilled $n measurements to Health Connect" },
                        onFailure = { e -> e.message ?: "Backfill failed" },
                    ),
                )
            }
        }
    }

    // ---- Google / Drive ---------------------------------------------------------------------

    fun signInGoogle(activityContext: Context) {
        viewModelScope.launch {
            val result = runCatching { authManager.signIn(activityContext) }
            _state.update {
                it.copy(
                    message = result.fold(
                        onSuccess = { acct -> "Signed in as ${acct.email ?: acct.displayName ?: "Google account"}" },
                        onFailure = { e -> e.message ?: "Sign-in failed" },
                    ),
                )
            }
        }
    }

    fun signOutGoogle() {
        viewModelScope.launch {
            authManager.signOut()
            _state.update { it.copy(backupEnabled = false, message = "Signed out") }
        }
    }

    /**
     * Toggle automatic Drive backup. Enabling requests the Drive scopes; if consent UI is needed
     * the resulting [GoogleAuthManager.ScopeRequest.NeedsConsent] is surfaced via [pendingConsent]
     * for the screen to launch.
     */
    fun setBackupEnabled(enabled: Boolean) {
        if (!enabled) {
            _state.update { it.copy(backupEnabled = false) }
            return
        }
        viewModelScope.launch {
            when (val req = authManager.requestBackupScopes()) {
                is CloudAccountManager.ScopeRequest.Granted ->
                    _state.update {
                        it.copy(backupEnabled = true, backupAuthorized = true, message = "Backup enabled")
                    }
                is CloudAccountManager.ScopeRequest.NeedsConsent ->
                    _state.update { it.copy(pendingConsent = req) }
                is CloudAccountManager.ScopeRequest.Failed ->
                    _state.update { it.copy(message = req.cause.message ?: "Couldn't enable backup") }
            }
        }
    }

    /** Feed back the consent screen result. */
    fun completeScopeConsent(data: android.content.Intent?) {
        val token = authManager.completeScopeRequest(data)
        _state.update {
            it.copy(
                pendingConsent = null,
                backupEnabled = token != null,
                backupAuthorized = authManager.isBackupAuthorized,
                message = if (token != null) "Backup enabled" else "Drive authorization cancelled",
            )
        }
    }

    fun consumePendingConsent() = _state.update { it.copy(pendingConsent = null) }

    fun exportToDriveNow() {
        viewModelScope.launch {
            _state.update { it.copy(driveBusy = true) }
            val result = backupService.backupNow()
            _state.update { it.copy(driveBusy = false, message = driveMessage(result, "Exported")) }
        }
    }

    fun restoreFromDrive() {
        viewModelScope.launch {
            _state.update { it.copy(driveBusy = true) }
            val result = backupService.restoreLatest()
            _state.update { it.copy(driveBusy = false, message = driveMessage(result, "Restored")) }
        }
    }

    private fun driveMessage(result: CloudBackup.Result, verb: String): String = when (result) {
        is CloudBackup.Result.Success -> "$verb ${result.measurementCount} measurements"
        CloudBackup.Result.NotAuthorized -> "Enable Drive backup first"
        is CloudBackup.Result.Failed -> result.cause.message ?: "Drive operation failed"
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}

data class SettingsUiState(
    // Profile
    val heightCm: String = "",
    val sex: Sex = Sex.MALE,
    val birthDate: LocalDate? = null,
    val profileSaved: Boolean = false,
    // Health Connect
    val healthConnectAvailable: Boolean = false,
    val healthConnectConnected: Boolean = false,
    val healthBackfilling: Boolean = false,
    // Google / Drive
    val cloudSupported: Boolean = true,
    val googleSignedIn: Boolean = false,
    val googleEmail: String? = null,
    val backupAuthorized: Boolean = false,
    val backupEnabled: Boolean = false,
    val driveBusy: Boolean = false,
    val pendingConsent: CloudAccountManager.ScopeRequest.NeedsConsent? = null,
    val message: String? = null,
)
