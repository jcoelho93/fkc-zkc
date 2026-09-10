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

    // The live counters below are written as single-statement SQL increments rather than
    // read-modify-write through upsert(). Three independent writers now touch the same row (a
    // foreground-entry, every counted swipe, every banked stretch of foreground time), all from
    // concurrent coroutines. With read-then-upsert, whichever wrote last silently undid the
    // other's increment. Each statement here is atomic on its own, so no transaction is needed:
    // the insert is idempotent and every update is relative.

    /** Creates today's row with every counter at zero, unless it already exists. */
    @Query(
        "INSERT OR IGNORE INTO daily_app_stats " +
            "(packageName, dateEpochDay, scrollCount, foregroundTimeMillis, updatedAtMillis, openCount) " +
            "VALUES (:packageName, :dateEpochDay, 0, 0, :nowMillis, 0)",
    )
    suspend fun insertZeroRowIfAbsent(packageName: String, dateEpochDay: Long, nowMillis: Long)

    @Query(
        "UPDATE daily_app_stats SET scrollCount = scrollCount + 1, updatedAtMillis = :nowMillis " +
            "WHERE packageName = :packageName AND dateEpochDay = :dateEpochDay",
    )
    suspend fun incrementScrollCount(packageName: String, dateEpochDay: Long, nowMillis: Long)

    @Query(
        "UPDATE daily_app_stats SET foregroundTimeMillis = foregroundTimeMillis + :deltaMillis, " +
            "updatedAtMillis = :nowMillis WHERE packageName = :packageName AND dateEpochDay = :dateEpochDay",
    )
    suspend fun addForegroundTime(packageName: String, dateEpochDay: Long, deltaMillis: Long, nowMillis: Long)

    /**
     * NULL + 1 is NULL in SQL, which is exactly the rule wanted: a day recorded before opens were
     * counted stays "not counted" rather than showing a partial day's opens as if complete.
     */
    @Query(
        "UPDATE daily_app_stats SET openCount = openCount + 1, updatedAtMillis = :nowMillis " +
            "WHERE packageName = :packageName AND dateEpochDay = :dateEpochDay",
    )
    suspend fun incrementOpenCount(packageName: String, dateEpochDay: Long, nowMillis: Long)

    @Query("SELECT * FROM daily_app_stats WHERE dateEpochDay = :dateEpochDay")
    fun observeForDay(dateEpochDay: Long): Flow<List<DailyAppStatEntity>>

    @Query("SELECT * FROM daily_app_stats WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay")
    fun observeForDayRange(startEpochDay: Long, endEpochDay: Long): Flow<List<DailyAppStatEntity>>

    @Query("SELECT * FROM daily_app_stats WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay")
    suspend fun getForDayRange(startEpochDay: Long, endEpochDay: Long): List<DailyAppStatEntity>

    @Query("DELETE FROM daily_app_stats WHERE dateEpochDay < :cutoffEpochDay")
    suspend fun deleteOlderThan(cutoffEpochDay: Long)
}
