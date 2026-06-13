package io.github.openweigh.health

import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import io.github.openweigh.data.repo.Measurement
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Converts a stored [Measurement] (id: String, epochMillis: Long, weight + optional body
 * composition fields) into the list of Health Connect [Record]s per the SPEC mapping.
 *
 * Mapping:
 * - weightKg            -> [WeightRecord]             (Mass.kilograms)
 * - bodyFatPercent      -> [BodyFatRecord]            (Percentage)
 * - leanMassKg          -> [LeanBodyMassRecord]       (Mass.kilograms)
 * - bodyWaterMassKg     -> [BodyWaterMassRecord]      (Mass.kilograms)
 * - boneMassKg          -> [BoneMassRecord]           (Mass.kilograms)
 * - basalMetabolismKcal -> [BasalMetabolicRateRecord] (Power; kcal/day -> kcal/day API unit)
 *
 * Rules:
 * - A null composition field produces NO record (only weight is guaranteed present).
 * - Every record carries a stable [Metadata.clientRecordId] derived from the measurement [id]
 *   so repeated writes are idempotent upserts in Health Connect.
 * - BMI is intentionally NOT mapped (Health Connect has no BMI record; derive in-app).
 */
@Singleton
class HealthConnectMapper @Inject constructor() {

    /** Convert a single stored [measurement] into its Health Connect records. */
    fun toRecords(measurement: Measurement): List<Record> {
        val reading = measurement.reading
        val instant = Instant.ofEpochMilli(measurement.epochMillis)
        // Records require a zone offset; we store epoch millis in UTC. Resolve the device's
        // offset for that instant so the record's local time is sensible to the user.
        val zoneOffset = resolveOffset(instant)

        val records = mutableListOf<Record>()

        records += WeightRecord(
            time = instant,
            zoneOffset = zoneOffset,
            weight = Mass.kilograms(reading.weightKg),
            metadata = metadataFor(measurement.id, RecordKind.WEIGHT)
        )

        reading.bodyFatPercent?.let { pct ->
            records += BodyFatRecord(
                time = instant,
                zoneOffset = zoneOffset,
                percentage = Percentage(pct),
                metadata = metadataFor(measurement.id, RecordKind.BODY_FAT)
            )
        }

        reading.leanMassKg?.let { kg ->
            records += LeanBodyMassRecord(
                time = instant,
                zoneOffset = zoneOffset,
                mass = Mass.kilograms(kg),
                metadata = metadataFor(measurement.id, RecordKind.LEAN_MASS)
            )
        }

        reading.bodyWaterMassKg?.let { kg ->
            records += BodyWaterMassRecord(
                time = instant,
                zoneOffset = zoneOffset,
                mass = Mass.kilograms(kg),
                metadata = metadataFor(measurement.id, RecordKind.BODY_WATER)
            )
        }

        reading.boneMassKg?.let { kg ->
            records += BoneMassRecord(
                time = instant,
                zoneOffset = zoneOffset,
                mass = Mass.kilograms(kg),
                metadata = metadataFor(measurement.id, RecordKind.BONE_MASS)
            )
        }

        reading.basalMetabolismKcal?.let { kcalPerDay ->
            // Health Connect models BMR as a Power (rate of energy expenditure). The Power unit
            // helper expects kilocalories-per-day, so we can pass the value through directly.
            records += BasalMetabolicRateRecord(
                time = instant,
                zoneOffset = zoneOffset,
                basalMetabolicRate = Power.kilocaloriesPerDay(kcalPerDay.toDouble()),
                metadata = metadataFor(measurement.id, RecordKind.BMR)
            )
        }

        return records
    }

    /** Convert many stored measurements (used for "backfill all"). */
    fun toRecords(measurements: Collection<Measurement>): List<Record> =
        measurements.flatMap { toRecords(it) }

    private fun resolveOffset(instant: Instant): ZoneOffset =
        try {
            ZoneId.systemDefault().rules.getOffset(instant)
        } catch (_: Exception) {
            ZoneOffset.UTC
        }

    private fun metadataFor(measurementId: String, kind: RecordKind): Metadata =
        Metadata(clientRecordId = clientRecordId(measurementId, kind))

    /** One measurement fans out into several record types; each needs its own stable id. */
    private enum class RecordKind(val suffix: String) {
        WEIGHT("weight"),
        BODY_FAT("bodyfat"),
        LEAN_MASS("leanmass"),
        BODY_WATER("bodywater"),
        BONE_MASS("bonemass"),
        BMR("bmr")
    }

    private fun clientRecordId(measurementId: String, kind: RecordKind): String =
        "$CLIENT_RECORD_ID_PREFIX$measurementId:${kind.suffix}"

    companion object {
        /** Namespacing prefix so our client record ids never collide with other apps' data. */
        const val CLIENT_RECORD_ID_PREFIX = "io.github.openweigh:"
    }
}
