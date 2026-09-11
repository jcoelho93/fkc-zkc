package com.mindfulscroll.app.reflection

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mindfulscroll.app.data.AppSettings
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Instant
import java.time.ZoneId

/**
 * Runs once a week while the weekly prompt is on (see WorkScheduler.syncWeeklyReflection) and
 * posts the reflection notification if there is anything to reflect on.
 *
 * It never retries. Every path, including a blocked notification and an exception, ends in
 * Result.success(), because a retry is a second attempt at getting someone's attention, which is
 * the escalation #33 rules out. What happened is recorded in [WeeklyReflectionStatus] instead.
 */
@HiltWorker
class WeeklyReflectionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val appSettings: AppSettings,
    private val repository: ReflectionRepository,
    private val notifier: ReflectionNotifier,
    private val status: WeeklyReflectionStatus,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        runCatching { decideAndPost(now) }
            .onSuccess { (result, detail) -> status.record(result, detail, now) }
            .onFailure { error ->
                Log.e(TAG, "Weekly reflection job threw", error)
                status.record(WeeklyPromptResult.ERROR, error.toString(), now)
            }
        return Result.success()
    }

    private suspend fun decideAndPost(nowMillis: Long): Pair<WeeklyPromptResult, String?> {
        if (!appSettings.weeklyReflectionPromptEnabledNow()) return WeeklyPromptResult.SKIPPED_PROMPT_OFF to null

        val today = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
        val reflection = repository.load(ReflectionWindow.weekEndingOn(today))
        // An empty reflection is not worth a notification: "nothing to show" is noise.
        if (reflection.isEmpty) return WeeklyPromptResult.SKIPPED_NOTHING_RECORDED to null

        notifier.blockedReason()?.let { return WeeklyPromptResult.SKIPPED_BLOCKED to it }

        return if (notifier.post()) {
            WeeklyPromptResult.POSTED_AND_SHOWING to null
        } else {
            WeeklyPromptResult.POSTED_NOT_SHOWING to "notify() returned but the notification is not active"
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "weekly_reflection"
        private const val TAG = "WeeklyReflection"
    }
}
