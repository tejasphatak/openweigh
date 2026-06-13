package io.github.openweigh.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Room DAO for the single-row [UserProfileEntity]. */
@Dao
interface UserProfileDao {

    /** The profile as a reactive stream; emits null until one is saved. */
    @Query("SELECT * FROM user_profile WHERE id = :id LIMIT 1")
    fun observe(id: Int = UserProfileEntity.SINGLETON_ID): Flow<UserProfileEntity?>

    /** One-shot read of the profile, or null if never set. */
    @Query("SELECT * FROM user_profile WHERE id = :id LIMIT 1")
    suspend fun get(id: Int = UserProfileEntity.SINGLETON_ID): UserProfileEntity?

    /** Insert-or-replace the single profile row. */
    @Upsert
    suspend fun upsert(entity: UserProfileEntity)
}
