package io.github.openweigh.sync

import io.github.openweigh.diag.DiagLog
import io.github.openweigh.data.repo.Measurement
import io.github.openweigh.data.repo.MeasurementRepository
import io.github.openweigh.drive.CloudBackup
import io.github.openweigh.health.HealthConnectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates the two best-effort sync sinks when a reading is saved:
 *
 * 1. **Health Connect** — push the new measurement immediately (idempotent upsert by id), marking
 *    the row `syncedHealthConnect` so the History/Detail UI can show the chip and the WorkManager
 *    backfill doesn't redo it.
 * 2. **Google Drive** — schedule a **debounced** appDataFolder backup. Rapid successive saves (e.g.
 *    a scale re-reporting) collapse into a single backup [DEBOUNCE_MS] after the last save, so we
 *    don't hammer Drive. The periodic [BackupWorker] is the durable fallback for missed/offline runs.
 *
 * Both steps swallow failures (logged only): a sync sink being unavailable must never break saving.
 * Inject this into the save path (ViewModel/repository caller) and call [onReadingSaved].
 */
@Singleton
class SyncCoordinator @Inject constructor(
    private val repository: MeasurementRepository,
    private val healthConnectManager: HealthConnectManager,
    private val cloudBackup: CloudBackup
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backupMutex = Mutex()
    @Volatile
    private var pendingBackup: Job? = null

    /**
     * Call right after a reading has been persisted. Pushes to Health Connect now and debounces a
     * Drive backup. Non-blocking: the work is launched on an internal IO scope.
     */
    fun onReadingSaved(measurement: Measurement) {
        scope.launch { pushToHealthConnect(measurement) }
        scheduleDebouncedBackup()
    }

    /** Health Connect export for a single measurement; best-effort. */
    private suspend fun pushToHealthConnect(measurement: Measurement) {
        if (!healthConnectManager.isAvailable()) return
        try {
            if (!healthConnectManager.hasAllPermissions()) return
            healthConnectManager.writeMeasurement(measurement)
            repository.markSyncedHealthConnect(listOf(measurement.id))
        } catch (t: Throwable) {
            DiagLog.w(TAG, "Health Connect push failed for ${measurement.id}", t)
        }
    }

    /**
     * (Re)start the debounce timer. The latest call wins: any in-flight pending timer is cancelled
     * and replaced, so the backup fires [DEBOUNCE_MS] after the most recent save.
     */
    private fun scheduleDebouncedBackup() {
        pendingBackup?.cancel()
        pendingBackup = scope.launch {
            delay(DEBOUNCE_MS)
            runBackup()
        }
    }

    /** Run an actual Drive backup, serialized so two never overlap. Best-effort. */
    private suspend fun runBackup() {
        backupMutex.withLock {
            try {
                when (val result = cloudBackup.backupNow()) {
                    is CloudBackup.Result.Failed ->
                        DiagLog.w(TAG, "Drive backup failed", result.cause)
                    else -> Unit
                }
            } catch (t: Throwable) {
                DiagLog.w(TAG, "Drive backup threw", t)
            }
        }
    }

    companion object {
        private const val TAG = "SyncCoordinator"
        /** Debounce window for collapsing rapid saves into one Drive backup. */
        const val DEBOUNCE_MS = 5_000L
    }
}
