package io.github.openweigh.drive

import com.google.api.client.http.ByteArrayContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import io.github.openweigh.data.repo.MeasurementRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Visible, user-initiated export of the measurement store into a normal Drive folder the user can
 * open and share. Uses only the `drive.file` scope: the app creates an "OpenWeigh" folder it owns
 * and writes timestamped CSV / JSON files into it; it has no access to the user's other files.
 *
 * Each export creates a **new** timestamped file (history is preserved), unlike the rolling
 * hidden backup. Returns the created file's id + a `webViewLink` the UI can offer to open.
 */
@Singleton
class DriveExportService @Inject constructor(
    private val authManager: GoogleAuthManager,
    private val serializer: BackupSerializer,
    private val repository: MeasurementRepository,
    private val clientFactory: DriveClientFactory
) {

    enum class Format(val mime: String, val extension: String) {
        CSV("text/csv", "csv"),
        JSON("application/json", "json")
    }

    sealed interface Result {
        data class Success(val fileId: String, val fileName: String, val webViewLink: String?) :
            Result
        data object NotAuthorized : Result
        data class Failed(val cause: Throwable) : Result
    }

    /** Export the whole store in [format] into the app's Drive folder. */
    suspend fun exportNow(format: Format): Result = withContext(Dispatchers.IO) {
        val token = authManager.authorizedAccessTokenOrNull()
            ?: authManager.refreshAccessTokenOrNull()
            ?: return@withContext Result.NotAuthorized
        try {
            val drive = driveClient(token)
            val measurements = repository.observeAll().first()
            val payload = when (format) {
                Format.CSV -> serializer.toCsv(measurements)
                Format.JSON -> serializer.toJsonSnapshot(measurements)
            }
            val folderId = ensureExportFolder(drive)
            val fileName = "openweigh-export-${timestampSuffix()}.${format.extension}"
            val metadata = File().apply {
                name = fileName
                parents = listOf(folderId)
                mimeType = format.mime
            }
            val content = ByteArrayContent(format.mime, payload.toByteArray(Charsets.UTF_8))
            val created = drive.files().create(metadata, content)
                .setFields("id, name, webViewLink")
                .execute()
            Result.Success(created.id, created.name ?: fileName, created.webViewLink)
        } catch (t: Throwable) {
            Result.Failed(t)
        }
    }

    /**
     * Find (or create) the app's visible export folder, returning its id. Because the `drive.file`
     * scope only sees app-created files, the lookup is scoped to folders this app already made.
     */
    private fun ensureExportFolder(drive: Drive): String {
        val existing = drive.files().list()
            .setQ(
                "mimeType = '$MIME_FOLDER' and name = '$EXPORT_FOLDER_NAME' and trashed = false"
            )
            .setFields("files(id)")
            .setPageSize(1)
            .execute()
        existing.files?.firstOrNull()?.id?.let { return it }

        val metadata = File().apply {
            name = EXPORT_FOLDER_NAME
            mimeType = MIME_FOLDER
        }
        return drive.files().create(metadata).setFields("id").execute().id
    }

    private fun timestampSuffix(): String =
        FILE_TS_FORMAT.withZone(ZoneId.systemDefault()).format(java.time.Instant.now())

    private fun driveClient(accessToken: String): Drive =
        clientFactory.create(accessToken, DriveScopes.DRIVE_FILE)

    companion object {
        const val EXPORT_FOLDER_NAME = "OpenWeigh"
        const val MIME_FOLDER = "application/vnd.google-apps.folder"
        private val FILE_TS_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")
    }
}
