package com.mindfulscroll.app.data.repository

import com.mindfulscroll.app.data.dao.ActiveSessionDao
import com.mindfulscroll.app.data.dao.DailyAppStatDao
import com.mindfulscroll.app.data.dao.OverlayEventDao
import com.mindfulscroll.app.data.entity.ActiveSessionEntity
import com.mindfulscroll.app.data.entity.DailyAppStatEntity
import com.mindfulscroll.app.data.entity.OverlayChoice
import com.mindfulscroll.app.data.entity.OverlayEventEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Handles the two write paths that matter for correctness: live scroll counting and overlay outcomes. */
@Singleton
class ScrollStatsRepository @Inject constructor(
    private val dailyAppStatDao: DailyAppStatDao,
    private val activeSessionDao: ActiveSessionDao,
    private val overlayEventDao: OverlayEventDao,
) {
    fun todayEpochDay(): Long = epochDayFor(System.currentTimeMillis())

    fun epochDayFor(millis: Long): Long =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    suspend fun getActiveSession(packageName: String): ActiveSessionEntity? =
        activeSessionDao.get(packageName)

    /** Starts a fresh session clock for [packageName]; called when it newly becomes foreground. */
    suspend fun startSession(packageName: String, nowMillis: Long) {
        activeSessionDao.upsert(ActiveSessionEntity(packageName, nowMillis, scrollCountInSession = 0))
    }

    suspend fun clearSession(packageName: String) {
        activeSessionDao.clear(packageName)
    }

    /**
     * Records one TYPE_VIEW_SCROLLED event for [packageName]: bumps both the session counter
     * (for threshold evaluation) and today's running daily total (for the dashboard).
     * Returns the updated session so the caller can evaluate the threshold against it.
     */
    suspend fun recordScroll(packageName: String, nowMillis: Long): ActiveSessionEntity {
        val current = activeSessionDao.get(packageName)
            ?: ActiveSessionEntity(packageName, nowMillis, scrollCountInSession = 0)
        val updated = current.copy(scrollCountInSession = current.scrollCountInSession + 1)
        activeSessionDao.upsert(updated)

        val day = epochDayFor(nowMillis)
        val existingStat = dailyAppStatDao.get(packageName, day)
        val newStat = if (existingStat == null) {
            DailyAppStatEntity(packageName, day, scrollCount = 1, foregroundTimeMillis = 0, updatedAtMillis = nowMillis)
        } else {
            existingStat.copy(scrollCount = existingStat.scrollCount + 1, updatedAtMillis = nowMillis)
        }
        dailyAppStatDao.upsert(newStat)

        return updated
    }

    /** Adds foreground dwell time to today's running total for [packageName] (dashboard "time in app"). */
    suspend fun addForegroundTime(packageName: String, deltaMillis: Long, nowMillis: Long) {
        if (deltaMillis <= 0) return
        val day = epochDayFor(nowMillis)
        val existing = dailyAppStatDao.get(packageName, day)
        val updated = if (existing == null) {
            DailyAppStatEntity(packageName, day, scrollCount = 0, foregroundTimeMillis = deltaMillis, updatedAtMillis = nowMillis)
        } else {
            existing.copy(foregroundTimeMillis = existing.foregroundTimeMillis + deltaMillis, updatedAtMillis = nowMillis)
        }
        dailyAppStatDao.upsert(updated)
    }

    suspend fun recordOverlayShown(
        packageName: String,
        nowMillis: Long,
        scrollCountAtTrigger: Int,
        sessionTimeMillisAtTrigger: Long,
    ): Long = overlayEventDao.insert(
        OverlayEventEntity(
            packageName = packageName,
            dateEpochDay = epochDayFor(nowMillis),
            shownAtMillis = nowMillis,
            scrollCountAtTrigger = scrollCountAtTrigger,
            sessionTimeMillisAtTrigger = sessionTimeMillisAtTrigger,
            choice = OverlayChoice.PENDING,
            respondedAtMillis = null,
        ),
    )

    suspend fun recordOverlayChoice(eventId: Long, choice: OverlayChoice, nowMillis: Long) {
        val event = overlayEventDao.get(eventId) ?: return
        overlayEventDao.update(event.copy(choice = choice, respondedAtMillis = nowMillis))
    }

    fun observeStatsForRange(startEpochDay: Long, endEpochDay: Long): Flow<List<DailyAppStatEntity>> =
        dailyAppStatDao.observeForDayRange(startEpochDay, endEpochDay)

    fun observeOverlayEventsForRange(startEpochDay: Long, endEpochDay: Long): Flow<List<OverlayEventEntity>> =
        overlayEventDao.observeForDayRange(startEpochDay, endEpochDay)

    /** Drops session rows abandoned by a killed service process, so they don't linger forever. */
    suspend fun clearStaleSessions(maxAgeMillis: Long, nowMillis: Long) {
        activeSessionDao.clearStale(nowMillis - maxAgeMillis)
    }

    /** Daily rollup housekeeping: keeps on-device history bounded to [retentionDays]. */
    suspend fun pruneHistoryOlderThan(retentionDays: Long, nowMillis: Long) {
        val cutoffEpochDay = epochDayFor(nowMillis) - retentionDays
        dailyAppStatDao.deleteOlderThan(cutoffEpochDay)
        overlayEventDao.deleteOlderThan(cutoffEpochDay)
    }
}
