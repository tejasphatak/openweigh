package io.github.openweigh.drive

import com.google.api.client.http.ByteArrayContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import io.github.openweigh.data.repo.MeasurementRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hidden, automatic backup of the measurement store to the user's Drive **appDataFolder** (a
 * per-app folder invisible in the user's normal Drive UI; requires only the `drive.appdata` scope).
 *
 * Stores a single rolling JSON snapshot file ([SNAPSHOT_FILE_NAME]): each backup updates the
 * existing file in place (or creates it the first time), so there is exactly one current snapshot.
 * [restoreLatest] reads it back and upserts every measurement into the local store.
 *
 * All operations are best-effort and return a sealed [Result]; nothing here throws to callers.
 * Requires an OAuth access token from [GoogleAuthManager] with the `drive.appdata` scope.
 */
@Singleton
class DriveBackupService @Inject constructor(
    private val authManager: GoogleAuthManager,
    private val serializer: BackupSerializer,
    private val repository: MeasurementRepository,
    private val clientFactory: DriveClientFactory
) : CloudBackup {

    /**
     * Snapshot the entire local store and write it to the appDataFolder, replacing any existing
     * snapshot. On success, marks all rows as `syncedDrive`.
     */
    override suspend fun backupNow(): CloudBackup.Result = withContext(Dispatchers.IO) {
        val token = authManager.refreshAccessTokenOrNull() ?: return@withContext CloudBackup.Result.NotAuthorized
        try {
            val drive = driveClient(token)
            val measurements = repository.observeAll().first()
            val json = serializer.toJsonSnapshot(measurements)
            val bytes = json.toByteArray(Charsets.UTF_8)
            val content = ByteArrayContent(MIME_JSON, bytes)

            val existingId = findSnapshotFileId(drive)
            if (existingId == null) {
                val metadata = File().apply {
                    name = SNAPSHOT_FILE_NAME
                    parents = listOf(APP_DATA_FOLDER)
                    mimeType = MIME_JSON
                }
                drive.files().create(metadata, content).setFields("id").execute()
            } else {
                drive.files().update(existingId, File(), content).execute()
            }

            repository.markSyncedDrive(measurements.map { it.id })
            CloudBackup.Result.Success(measurements.size)
        } catch (t: Throwable) {
            CloudBackup.Result.Failed(t)
        }
    }

    /**
     * Download the latest appDataFolder snapshot and upsert its measurements into the local store.
     * Returns the number of measurements restored (0 if no snapshot exists).
     */
    override suspend fun restoreLatest(): CloudBackup.Result = withContext(Dispatchers.IO) {
        val token = authManager.refreshAccessTokenOrNull() ?: return@withContext CloudBackup.Result.NotAuthorized
        try {
            val drive = driveClient(token)
            val fileId = findSnapshotFileId(drive) ?: return@withContext CloudBackup.Result.Success(0)
            val out = ByteArrayOutputStream()
            drive.files().get(fileId).executeMediaAndDownloadTo(out)
            val json = out.toString(Charsets.UTF_8.name())
            val measurements = serializer.fromJsonSnapshot(json)
            measurements.forEach { repository.upsert(it) }
            CloudBackup.Result.Success(measurements.size)
        } catch (t: Throwable) {
            CloudBackup.Result.Failed(t)
        }
    }

    /** True if a backup snapshot currently exists in the appDataFolder. Best-effort (null on error). */
    suspend fun hasBackup(): Boolean? = withContext(Dispatchers.IO) {
        val token = authManager.refreshAccessTokenOrNull() ?: return@withContext null
        runCatching { findSnapshotFileId(driveClient(token)) != null }.getOrNull()
    }

    private fun findSnapshotFileId(drive: Drive): String? {
        val response = drive.files().list()
            .setSpaces(SPACE_APP_DATA)
            .setQ("name = '$SNAPSHOT_FILE_NAME'")
            .setFields("files(id, modifiedTime)")
            .setOrderBy("modifiedTime desc")
            .setPageSize(1)
            .execute()
        return response.files?.firstOrNull()?.id
    }

    private fun driveClient(accessToken: String): Drive =
        clientFactory.create(accessToken, DriveScopes.DRIVE_APPDATA)

    companion object {
        const val SNAPSHOT_FILE_NAME = "openweigh-backup.json"
        const val APP_DATA_FOLDER = "appDataFolder"
        const val SPACE_APP_DATA = "appDataFolder"
        const val MIME_JSON = "application/json"
    }
}

// --- Shared Drive client factory (used by both backup & export services) --------------------------

/**
 * Builds Drive v3 clients that authenticate every request with a supplied OAuth access token
 * (obtained from [GoogleAuthManager] via Google Identity Services). The `Authorization: Bearer`
 * header is set through an [com.google.api.client.http.HttpRequestInitializer] rather than the
 * deprecated `GoogleCredential`, since token acquisition is handled entirely by GIS.
 *
 * Reuses the singleton [com.google.api.client.http.HttpTransport] and
 * [com.google.api.client.json.JsonFactory] provided by [DriveModule] so we don't allocate a new
 * transport per call.
 */
@javax.inject.Singleton
class DriveClientFactory @Inject constructor(
    private val httpTransport: com.google.api.client.http.HttpTransport,
    private val jsonFactory: com.google.api.client.json.JsonFactory
) {
    /**
     * Create a Drive client bound to [accessToken]. The [scopes] vararg documents intent; actual
     * scope enforcement happens at the token level.
     */
    fun create(accessToken: String, vararg scopes: String): Drive {
        val initializer = com.google.api.client.http.HttpRequestInitializer { request ->
            request.headers.authorization = "Bearer $accessToken"
        }
        return Drive.Builder(httpTransport, jsonFactory, initializer)
            .setApplicationName(DRIVE_APP_NAME)
            .build()
    }
}

internal const val DRIVE_APP_NAME = "OpenWeigh"
