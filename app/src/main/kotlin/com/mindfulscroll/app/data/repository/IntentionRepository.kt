package com.mindfulscroll.app.data.repository

import com.mindfulscroll.app.data.dao.IntentionDao
import com.mindfulscroll.app.data.entity.IntentionEntity
import com.mindfulscroll.app.data.entity.IntentionKind
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntentionRepository @Inject constructor(
    private val intentionDao: IntentionDao,
) {
    private fun epochDayFor(millis: Long): Long =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    /**
     * Records that the prompt was shown, before the user has answered anything. Writing the row up
     * front is what makes "opened it and had nothing in mind" countable: if rows were only created
     * on an answer, every rate the weekly report computes would silently be over the subset of
     * opens the user felt like explaining.
     */
    suspend fun recordPromptShown(
        packageName: String,
        sessionStartMillis: Long,
        nowMillis: Long,
    ): Long = intentionDao.insert(
        IntentionEntity(
            packageName = packageName,
            dateEpochDay = epochDayFor(nowMillis),
            sessionStartMillis = sessionStartMillis,
            promptedAtMillis = nowMillis,
            respondedAtMillis = null,
            kind = null,
            note = null,
        ),
    )

    /** @return true if the row still existed and was updated. */
    suspend fun recordAnswer(
        intentionId: Long,
        kind: IntentionKind,
        note: String?,
        nowMillis: Long,
    ): Boolean {
        val existing = intentionDao.get(intentionId) ?: return false
        intentionDao.update(
            existing.copy(
                kind = kind,
                note = note?.takeIf { it.isNotBlank() },
                respondedAtMillis = nowMillis,
            ),
        )
        return true
    }

    /** The intention captured at the start of this exact visit - see IntentionDao.getForSession. */
    suspend fun getForSession(packageName: String, sessionStartMillis: Long): IntentionEntity? =
        intentionDao.getForSession(packageName, sessionStartMillis)

    fun observeForRange(startEpochDay: Long, endEpochDay: Long): Flow<List<IntentionEntity>> =
        intentionDao.observeForDayRange(startEpochDay, endEpochDay)

    suspend fun getForRange(startEpochDay: Long, endEpochDay: Long): List<IntentionEntity> =
        intentionDao.getForDayRange(startEpochDay, endEpochDay)

    /** Same retention window as the rest of the on-device history; see DailyMaintenanceWorker. */
    suspend fun pruneOlderThan(retentionDays: Long, nowMillis: Long) {
        intentionDao.deleteOlderThan(epochDayFor(nowMillis) - retentionDays)
    }
}
