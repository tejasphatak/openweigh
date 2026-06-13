package io.github.openweigh.data.repo

import io.github.openweigh.ble.model.ScaleReading
import io.github.openweigh.data.db.MeasurementDao
import io.github.openweigh.data.db.MeasurementEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first store for measurements. Wraps [MeasurementDao], exposes domain [Measurement]
 * flows, and converts between the [ScaleReading] domain type and [MeasurementEntity].
 */
@Singleton
class MeasurementRepository @Inject constructor(
    private val dao: MeasurementDao
) {

    /** All measurements, newest first, as a reactive stream of domain models. */
    fun observeAll(): Flow<List<Measurement>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /** One-shot fetch of a single measurement by id. */
    suspend fun getById(id: String): Measurement? = dao.getById(id)?.toDomain()

    /**
     * Persist a freshly captured [ScaleReading], generating a new UUID. Returns the stored
     * [Measurement] (including its new id) for callers that want to act on it immediately.
     */
    suspend fun saveReading(reading: ScaleReading): Measurement {
        val measurement = Measurement(
            id = UUID.randomUUID().toString(),
            epochMillis = reading.timestamp.toEpochMilli(),
            reading = reading
        )
        dao.insert(measurement.toEntity())
        return measurement
    }

    /** Insert-or-update an existing measurement (used for edits from the detail screen). */
    suspend fun upsert(measurement: Measurement) = dao.upsert(measurement.toEntity())

    suspend fun delete(id: String) = dao.deleteById(id)

    // --- Sync support ---------------------------------------------------------------------------

    /** Measurements not yet exported to Health Connect, oldest first. */
    suspend fun getUnsyncedHealthConnect(): List<Measurement> =
        dao.getUnsyncedHealthConnect().map { it.toDomain() }

    /** Measurements not yet backed up to Google Drive, oldest first. */
    suspend fun getUnsyncedDrive(): List<Measurement> =
        dao.getUnsyncedDrive().map { it.toDomain() }

    suspend fun markSyncedHealthConnect(ids: List<String>) =
        dao.markSyncedHealthConnect(ids)

    suspend fun markSyncedDrive(ids: List<String>) =
        dao.markSyncedDrive(ids)
}

// --- Mapping --------------------------------------------------------------------------------------

internal fun MeasurementEntity.toDomain(): Measurement =
    Measurement(
        id = id,
        epochMillis = epochMillis,
        reading = ScaleReading(
            timestamp = Instant.ofEpochMilli(epochMillis),
            weightKg = weightKg,
            bodyFatPercent = bodyFatPercent,
            leanMassKg = leanMassKg,
            bodyWaterMassKg = bodyWaterMassKg,
            boneMassKg = boneMassKg,
            basalMetabolismKcal = basalMetabolismKcal,
            impedanceOhm = impedanceOhm,
            sourceDevice = source,
            estimated = estimated
        ),
        syncedHealthConnect = syncedHealthConnect,
        syncedDrive = syncedDrive
    )

internal fun Measurement.toEntity(): MeasurementEntity =
    MeasurementEntity(
        id = id,
        epochMillis = epochMillis,
        weightKg = reading.weightKg,
        bodyFatPercent = reading.bodyFatPercent,
        leanMassKg = reading.leanMassKg,
        bodyWaterMassKg = reading.bodyWaterMassKg,
        boneMassKg = reading.boneMassKg,
        basalMetabolismKcal = reading.basalMetabolismKcal,
        impedanceOhm = reading.impedanceOhm,
        source = reading.sourceDevice,
        estimated = reading.estimated,
        syncedHealthConnect = syncedHealthConnect,
        syncedDrive = syncedDrive
    )
