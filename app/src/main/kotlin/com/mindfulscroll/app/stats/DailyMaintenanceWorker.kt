package com.mindfulscroll.app.stats

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mindfulscroll.app.data.repository.ScrollStatsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs roughly once a day: clears any per-app "active session" row that never got cleared
 * (e.g. the accessibility service's process was killed mid-session) and prunes daily stats /
 * overlay-event history past the local retention window, so on-device storage stays bounded.
 * There is no server-side rollup - this is purely local housekeeping.
 */
@HiltWorker
class DailyMaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val scrollStatsRepository: ScrollStatsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        scrollStatsRepository.clearStaleSessions(maxAgeMillis = STALE_SESSION_MAX_AGE_MILLIS, nowMillis = now)
        scrollStatsRepository.pruneHistoryOlderThan(retentionDays = HISTORY_RETENTION_DAYS, nowMillis = now)
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "daily_maintenance"
        private const val STALE_SESSION_MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L
        private const val HISTORY_RETENTION_DAYS = 90L
    }
}
