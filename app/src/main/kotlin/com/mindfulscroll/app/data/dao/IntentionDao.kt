package com.mindfulscroll.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.mindfulscroll.app.data.entity.IntentionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IntentionDao {

    @Insert
    suspend fun insert(intention: IntentionEntity): Long

    @Update
    suspend fun update(intention: IntentionEntity)

    @Query("SELECT * FROM intentions WHERE id = :id")
    suspend fun get(id: Long): IntentionEntity?

    /**
     * The intention captured at the start of this exact visit, which is what the pause screen
     * needs in order to say "you said X" about the session the user is actually in.
     */
    @Query(
        "SELECT * FROM intentions WHERE packageName = :packageName " +
            "AND sessionStartMillis = :sessionStartMillis ORDER BY promptedAtMillis DESC LIMIT 1",
    )
    suspend fun getForSession(packageName: String, sessionStartMillis: Long): IntentionEntity?

    @Query("SELECT * FROM intentions WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay ORDER BY promptedAtMillis DESC")
    fun observeForDayRange(startEpochDay: Long, endEpochDay: Long): Flow<List<IntentionEntity>>

    @Query("SELECT * FROM intentions WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay")
    suspend fun getForDayRange(startEpochDay: Long, endEpochDay: Long): List<IntentionEntity>

    @Query("DELETE FROM intentions WHERE dateEpochDay < :cutoffEpochDay")
    suspend fun deleteOlderThan(cutoffEpochDay: Long)
}
