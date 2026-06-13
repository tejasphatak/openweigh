package io.github.openweigh.data.repo

import io.github.openweigh.ble.model.ScaleReading
import java.time.Instant

/**
 * Domain model for a persisted measurement: a decoded [ScaleReading] plus the storage identity
 * and sync state the rest of the app cares about.
 *
 * Types are kept consistent with the health & drive agents: [id] is a stable UUID String and the
 * capture time is exposed both as [epochMillis] (Long) and the convenient [reading] timestamp.
 *
 * @property id stable UUID string; also the Health Connect clientRecordId.
 * @property epochMillis capture time as epoch milliseconds (UTC).
 * @property reading the decoded scale values.
 * @property syncedHealthConnect whether this row has been exported to Health Connect.
 * @property syncedDrive whether this row has been backed up to Google Drive.
 */
data class Measurement(
    val id: String,
    val epochMillis: Long,
    val reading: ScaleReading,
    val syncedHealthConnect: Boolean = false,
    val syncedDrive: Boolean = false
) {
    /** Capture time as an [Instant], derived from [epochMillis]. */
    val timestamp: Instant get() = Instant.ofEpochMilli(epochMillis)
}
