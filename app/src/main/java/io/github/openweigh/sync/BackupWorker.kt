package io.github.openweigh.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.openweigh.drive.CloudBackup
import java.util.concurrent.TimeUnit

/**
 * Periodic, network-constrained backup of the measurement store to the hidden Drive appDataFolder.
 *
 * This is the durable fallback for the in-app debounced backup ([SyncCoordinator]): it guarantees
 * the latest state reaches Drive even if the app was killed before a debounced backup ran, or the
 * device was offline at save time. It only runs when:
 * - the network is connected ([NetworkType.CONNECTED]), and
 * - the user has authorized Drive backup (otherwise [DriveBackupService] reports `NotAuthorized`
 *   and we simply succeed as a no-op — re-running on schedule is harmless).
 *
 * `@HiltWorker` so [DriveBackupService] is injected; relies on the [HiltWorkerFactory] wired in
 * `BleScaleApp`. All Drive work is best-effort: transient failures return [Result.retry], so
 * WorkManager backs off and tries again.
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val cloudBackup: CloudBackup
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return when (val result = cloudBackup.backupNow()) {
            is CloudBackup.Result.Success -> Result.success()
            // Not signed in / no Drive scope yet — nothing to do; don't churn retries.
            CloudBackup.Result.NotAuthorized -> Result.success()
            // Transient (network/Drive) error: let WorkManager back off and retry.
            is CloudBackup.Result.Failed -> Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "openweigh-drive-backup"

        /**
         * Enqueue (or keep) the periodic backup. Idempotent via [ExistingPeriodicWorkPolicy.KEEP];
         * call from app startup or when the user enables Drive backup. Network-constrained.
         */
        fun enqueuePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<BackupWorker>(
                REPEAT_INTERVAL_HOURS, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** Cancel the periodic backup (e.g. when the user disables Drive backup or signs out). */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        private const val REPEAT_INTERVAL_HOURS = 12L
    }
}
