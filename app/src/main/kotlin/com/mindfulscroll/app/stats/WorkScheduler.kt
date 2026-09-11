package com.mindfulscroll.app.stats

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mindfulscroll.app.reflection.WeeklyReflectionWorker
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

object WorkScheduler {
    fun scheduleDailyMaintenance(context: Context) {
        val request = PeriodicWorkRequestBuilder<DailyMaintenanceWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DailyMaintenanceWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Makes the weekly reflection job match the setting: enqueued while the prompt is on,
     * cancelled while it is off. Idempotent - KEEP leaves an existing schedule alone - so it is
     * safe to call on every app start and after every reboot.
     */
    fun syncWeeklyReflection(context: Context, enabled: Boolean, now: ZonedDateTime = ZonedDateTime.now()) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(WeeklyReflectionWorker.UNIQUE_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<WeeklyReflectionWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(delayUntilNextWeeklySlot(now).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            WeeklyReflectionWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Sunday evening, local time: a fixed, predictable slot at the end of the week rather than
     * "seven days after whenever the switch was flipped". Periodic work drifts a little after the
     * first run, which is fine for something that is meant to be unhurried.
     */
    internal fun delayUntilNextWeeklySlot(
        now: ZonedDateTime,
        day: DayOfWeek = DayOfWeek.SUNDAY,
        time: LocalTime = LocalTime.of(18, 0),
    ): Duration {
        var next = now.with(TemporalAdjusters.nextOrSame(day)).with(time)
        if (!next.isAfter(now)) next = next.plusWeeks(1)
        return Duration.between(now, next)
    }
}
