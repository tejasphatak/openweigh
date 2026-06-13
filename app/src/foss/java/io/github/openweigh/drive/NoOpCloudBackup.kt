package io.github.openweigh.drive

import javax.inject.Inject
import javax.inject.Singleton

/**
 * `foss`-flavor [CloudBackup]: Google Drive backup is unavailable in the GMS-free build, so every
 * operation reports [CloudBackup.Result.NotAuthorized]. Local storage and Health Connect are
 * unaffected — only the optional Drive sync is absent.
 */
@Singleton
class NoOpCloudBackup @Inject constructor() : CloudBackup {
    override suspend fun backupNow(): CloudBackup.Result = CloudBackup.Result.NotAuthorized
    override suspend fun restoreLatest(): CloudBackup.Result = CloudBackup.Result.NotAuthorized
}
