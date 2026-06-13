package io.github.openweigh.drive

/**
 * Flavor-agnostic contract for backing up / restoring the measurement store to the cloud.
 *
 * The `play` flavor binds the real Google Drive implementation ([DriveBackupService]); the `foss`
 * (GMS-free / F-Droid) flavor binds a no-op. Consumers — [io.github.openweigh.sync.SyncCoordinator],
 * [io.github.openweigh.sync.BackupWorker] and the Settings screen — depend only on this interface,
 * so nothing in `main` references Google Play Services.
 */
interface CloudBackup {

    sealed interface Result {
        data class Success(val measurementCount: Int) : Result

        /** No account / cloud scope yet, or cloud backup is unsupported in this build. */
        data object NotAuthorized : Result

        data class Failed(val cause: Throwable) : Result
    }

    /** Snapshot the local store to the cloud, replacing any prior snapshot. Best-effort. */
    suspend fun backupNow(): Result

    /** Restore the latest cloud snapshot into the local store. Best-effort. */
    suspend fun restoreLatest(): Result
}
