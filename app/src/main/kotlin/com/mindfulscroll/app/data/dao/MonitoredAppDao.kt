package com.mindfulscroll.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mindfulscroll.app.data.entity.MonitoredAppEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MonitoredAppDao {

    @Query("SELECT * FROM monitored_apps ORDER BY appLabel COLLATE NOCASE")
    fun observeAll(): Flow<List<MonitoredAppEntity>>

    @Query("SELECT * FROM monitored_apps WHERE isMonitored = 1")
    suspend fun getMonitoredApps(): List<MonitoredAppEntity>

    @Query("SELECT * FROM monitored_apps WHERE isMonitored = 1")
    fun observeMonitoredApps(): Flow<List<MonitoredAppEntity>>

    @Query("SELECT * FROM monitored_apps WHERE packageName = :packageName")
    suspend fun get(packageName: String): MonitoredAppEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: MonitoredAppEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(apps: List<MonitoredAppEntity>)

    @Update
    suspend fun update(app: MonitoredAppEntity)

    @Query("SELECT COUNT(*) FROM monitored_apps")
    suspend fun count(): Int
}
