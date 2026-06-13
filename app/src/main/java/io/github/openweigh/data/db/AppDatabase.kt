package io.github.openweigh.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The app's single Room database. Offline-first local store for all measurements and the user
 * profile.
 */
@Database(
    entities = [
        MeasurementEntity::class,
        UserProfileEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun measurementDao(): MeasurementDao
    abstract fun userProfileDao(): UserProfileDao

    companion object {
        const val NAME = "openweigh.db"
    }
}
