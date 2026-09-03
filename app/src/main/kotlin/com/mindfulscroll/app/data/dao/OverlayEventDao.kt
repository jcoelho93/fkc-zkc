package com.mindfulscroll.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.mindfulscroll.app.data.entity.OverlayEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OverlayEventDao {

    @Insert
    suspend fun insert(event: OverlayEventEntity): Long

    @Update
    suspend fun update(event: OverlayEventEntity)

    @Query("SELECT * FROM overlay_events WHERE id = :id")
    suspend fun get(id: Long): OverlayEventEntity?

    @Query("SELECT * FROM overlay_events WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay ORDER BY shownAtMillis DESC")
    fun observeForDayRange(startEpochDay: Long, endEpochDay: Long): Flow<List<OverlayEventEntity>>

    @Query("DELETE FROM overlay_events WHERE dateEpochDay < :cutoffEpochDay")
    suspend fun deleteOlderThan(cutoffEpochDay: Long)
}
