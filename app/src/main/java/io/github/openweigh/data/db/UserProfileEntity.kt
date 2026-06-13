package io.github.openweigh.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The user's single static profile (height, sex, birth date) used for derived metrics and Health
 * Connect records. Stored as a single row pinned to [SINGLETON_ID]; upserts overwrite it.
 *
 * @property heightCm height in centimeters.
 * @property sex biological sex, stored as the [io.github.openweigh.ble.model.Sex] enum name.
 * @property birthEpochDay date of birth as `LocalDate.toEpochDay()`.
 */
@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int = SINGLETON_ID,

    @ColumnInfo(name = "height_cm")
    val heightCm: Double,

    @ColumnInfo(name = "sex")
    val sex: String,

    @ColumnInfo(name = "birth_epoch_day")
    val birthEpochDay: Long
) {
    companion object {
        /** The fixed primary key for the one-and-only profile row. */
        const val SINGLETON_ID = 0
    }
}
