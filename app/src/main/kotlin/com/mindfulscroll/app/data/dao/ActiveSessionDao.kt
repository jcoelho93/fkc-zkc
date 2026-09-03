package com.mindfulscroll.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mindfulscroll.app.data.entity.ActiveSessionEntity

@Dao
interface ActiveSessionDao {

    @Query("SELECT * FROM active_sessions WHERE packageName = :packageName")
    suspend fun get(packageName: String): ActiveSessionEntity?

    @Upsert
    suspend fun upsert(session: ActiveSessionEntity)

    @Query("DELETE FROM active_sessions WHERE packageName = :packageName")
    suspend fun clear(packageName: String)

    @Query("DELETE FROM active_sessions")
    suspend fun clearAll()

    /** Safety net: drops sessions that never got cleared, e.g. the service process died mid-session. */
    @Query("DELETE FROM active_sessions WHERE sessionStartMillis < :cutoffMillis")
    suspend fun clearStale(cutoffMillis: Long)
}
