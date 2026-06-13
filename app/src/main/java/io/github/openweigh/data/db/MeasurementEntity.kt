package io.github.openweigh.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted weight / body-composition measurement.
 *
 * One row == one saved [io.github.openweigh.ble.model.ScaleReading]. Body-composition columns are
 * nullable because plain weight scales only report [weightKg]. The two `synced*` flags drive the
 * Health Connect and Google Drive sync pipelines (which read un-synced rows and mark them done).
 *
 * @property id stable UUID string, used as the Health Connect clientRecordId for idempotent upserts.
 * @property epochMillis capture/device time as epoch milliseconds (UTC).
 */
@Entity(tableName = "measurements")
data class MeasurementEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "epoch_millis")
    val epochMillis: Long,

    @ColumnInfo(name = "weight_kg")
    val weightKg: Double,

    @ColumnInfo(name = "body_fat_percent")
    val bodyFatPercent: Double? = null,

    @ColumnInfo(name = "lean_mass_kg")
    val leanMassKg: Double? = null,

    @ColumnInfo(name = "body_water_mass_kg")
    val bodyWaterMassKg: Double? = null,

    @ColumnInfo(name = "bone_mass_kg")
    val boneMassKg: Double? = null,

    @ColumnInfo(name = "basal_metabolism_kcal")
    val basalMetabolismKcal: Int? = null,

    @ColumnInfo(name = "impedance_ohm")
    val impedanceOhm: Double? = null,

    @ColumnInfo(name = "source")
    val source: String? = null,

    @ColumnInfo(name = "estimated")
    val estimated: Boolean = false,

    @ColumnInfo(name = "synced_health_connect")
    val syncedHealthConnect: Boolean = false,

    @ColumnInfo(name = "synced_drive")
    val syncedDrive: Boolean = false
)
