package io.github.openweigh.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Room DAO for [MeasurementEntity]. */
@Dao
interface MeasurementDao {

    /** All measurements, newest first, as a reactive stream. */
    @Query("SELECT * FROM measurements ORDER BY epoch_millis DESC")
    fun observeAll(): Flow<List<MeasurementEntity>>

    /** One-shot read of a single measurement, or null if absent. */
    @Query("SELECT * FROM measurements WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MeasurementEntity?

    /** Insert, ignoring rows whose id already exists (idempotent on UUID). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: MeasurementEntity): Long

    /** Insert-or-update by primary key. Used for edits. */
    @Upsert
    suspend fun upsert(entity: MeasurementEntity)

    @androidx.room.Delete
    suspend fun delete(entity: MeasurementEntity)

    @Query("DELETE FROM measurements WHERE id = :id")
    suspend fun deleteById(id: String)

    // --- Sync helpers ---------------------------------------------------------------------------

    /** Rows not yet exported to Health Connect, oldest first. */
    @Query("SELECT * FROM measurements WHERE synced_health_connect = 0 ORDER BY epoch_millis ASC")
    suspend fun getUnsyncedHealthConnect(): List<MeasurementEntity>

    /** Rows not yet backed up to Google Drive, oldest first. */
    @Query("SELECT * FROM measurements WHERE synced_drive = 0 ORDER BY epoch_millis ASC")
    suspend fun getUnsyncedDrive(): List<MeasurementEntity>

    @Query("UPDATE measurements SET synced_health_connect = 1 WHERE id IN (:ids)")
    suspend fun markSyncedHealthConnect(ids: List<String>)

    @Query("UPDATE measurements SET synced_drive = 1 WHERE id IN (:ids)")
    suspend fun markSyncedDrive(ids: List<String>)
}
