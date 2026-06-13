package io.github.openweigh.diag

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.openweigh.BuildConfig
import io.github.openweigh.data.repo.MeasurementRepository
import io.github.openweigh.drive.CloudAccountManager
import io.github.openweigh.health.HealthConnectManager
import kotlinx.coroutines.flow.first
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a human-readable Markdown diagnostic report for the bug-report screen.
 *
 * **Privacy:** the report deliberately excludes personally identifying information — no account
 * email, no measurement values, no exact location. Cloud/sign-in state is reported as booleans and
 * stored data only as a count. The report is shown to the user for review and only leaves the
 * device if they choose to share it.
 */
@Singleton
class DiagnosticsCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MeasurementRepository,
    private val healthConnectManager: HealthConnectManager,
    private val cloudAccountManager: CloudAccountManager,
) {

    suspend fun collect(): String {
        val hcAvailable = runCatching { healthConnectManager.isAvailable() }.getOrDefault(false)
        val hcGranted =
            if (hcAvailable) runCatching { healthConnectManager.hasAllPermissions() }.getOrDefault(false) else false
        val (btPresent, btEnabled) = bluetoothState()
        val measurementCount = runCatching { repository.observeAll().first().size }.getOrDefault(-1)
        val signedIn = cloudAccountManager.currentAccount.value != null

        return buildString {
            appendLine("# OpenWeigh diagnostics")
            appendLine()
            appendLine("- App: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
            appendLine("- Flavor / build type: ${BuildConfig.FLAVOR} / ${BuildConfig.BUILD_TYPE}")
            appendLine("- Application id: ${BuildConfig.APPLICATION_ID}")
            appendLine("- Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("- Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("- Locale: ${Locale.getDefault()}")
            appendLine()
            appendLine("## Subsystems")
            appendLine("- Health Connect available: $hcAvailable")
            appendLine("- Health Connect permissions granted: $hcGranted")
            appendLine("- Bluetooth present: $btPresent, enabled: $btEnabled")
            appendLine("- Cloud backup supported (flavor): ${cloudAccountManager.isSupported}")
            appendLine("- Signed in: $signedIn")
            appendLine("- Backup authorized: ${cloudAccountManager.isBackupAuthorized}")
            appendLine("- Stored measurements: $measurementCount")
            appendLine()
            readLastCrash()?.let { crash ->
                appendLine("## Last crash")
                appendLine("```")
                appendLine(crash.trim().take(MAX_CRASH_CHARS))
                appendLine("```")
                appendLine()
            }
            val logs = DiagLog.snapshot()
            appendLine("## Recent logs (${logs.size})")
            appendLine("```")
            if (logs.isEmpty()) appendLine("(no in-app log entries captured)") else logs.forEach { appendLine(it) }
            appendLine("```")
        }
    }

    fun readLastCrash(): String? = CrashReporter.readLastCrash(context)

    fun clearLastCrash() = CrashReporter.clear(context)

    private fun bluetoothState(): Pair<Boolean, Boolean> = runCatching {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        Pair(adapter != null, adapter?.isEnabled == true)
    }.getOrDefault(Pair(false, false))

    private companion object {
        const val MAX_CRASH_CHARS = 4000
    }
}
