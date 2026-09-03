package com.mindfulscroll.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mindfulscroll.app.data.entity.DailyAppStatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyAppStatDao {

    @Query("SELECT * FROM daily_app_stats WHERE packageName = :packageName AND dateEpochDay = :dateEpochDay")
    suspend fun get(packageName: String, dateEpochDay: Long): DailyAppStatEntity?

    @Upsert
    suspend fun upsert(stat: DailyAppStatEntity)

    @Query("SELECT * FROM daily_app_stats WHERE dateEpochDay = :dateEpochDay")
    fun observeForDay(dateEpochDay: Long): Flow<List<DailyAppStatEntity>>

    @Query("SELECT * FROM daily_app_stats WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay")
    fun observeForDayRange(startEpochDay: Long, endEpochDay: Long): Flow<List<DailyAppStatEntity>>

    @Query("SELECT * FROM daily_app_stats WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay")
    suspend fun getForDayRange(startEpochDay: Long, endEpochDay: Long): List<DailyAppStatEntity>

    @Query("DELETE FROM daily_app_stats WHERE dateEpochDay < :cutoffEpochDay")
    suspend fun deleteOlderThan(cutoffEpochDay: Long)
}
