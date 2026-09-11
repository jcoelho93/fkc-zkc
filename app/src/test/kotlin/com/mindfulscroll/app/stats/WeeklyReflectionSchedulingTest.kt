package com.mindfulscroll.app.stats

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.mindfulscroll.app.data.AppSettings
import com.mindfulscroll.app.reflection.WeeklyReflectionWorker
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The weekly job exists exactly while the prompt is on, survives a reboot, and first fires on
 * Sunday evening. A plain Application, not the Hilt one: these tests need WorkManager, not the
 * graph.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class WeeklyReflectionSchedulingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .build(),
        )
        workManager = WorkManager.getInstance(context)
        context.getSharedPreferences("mindful_scroll_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun weeklyWork(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(WeeklyReflectionWorker.UNIQUE_WORK_NAME).get()

    private fun activeWeeklyWork() = weeklyWork().filter { it.state == WorkInfo.State.ENQUEUED }

    @Test
    fun `turning the prompt on enqueues exactly one weekly job, and repeating it changes nothing`() {
        WorkScheduler.syncWeeklyReflection(context, enabled = true)
        val first = activeWeeklyWork().single()

        // App start and reboot both call this again; KEEP must not replace or duplicate it.
        WorkScheduler.syncWeeklyReflection(context, enabled = true)
        val second = activeWeeklyWork().single()

        assertThat(second.id).isEqualTo(first.id)
        assertThat(second.periodicityInfo?.repeatIntervalMillis).isEqualTo(Duration.ofDays(7).toMillis())
    }

    @Test
    fun `turning the prompt off cancels the job`() {
        WorkScheduler.syncWeeklyReflection(context, enabled = true)
        WorkScheduler.syncWeeklyReflection(context, enabled = false)

        assertThat(activeWeeklyWork()).isEmpty()
        assertThat(weeklyWork().map { it.state }).containsExactly(WorkInfo.State.CANCELLED)
    }

    @Test
    fun `with the prompt off nothing is ever scheduled`() {
        WorkScheduler.syncWeeklyReflection(context, enabled = false)
        assertThat(weeklyWork()).isEmpty()
    }

    @Test
    fun `after a reboot the weekly job is re-armed while the prompt is on`() {
        AppSettings(context).setWeeklyReflectionPromptEnabled(true)

        BootRescheduleReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertThat(activeWeeklyWork()).hasSize(1)
    }

    @Test
    fun `after a reboot nothing is scheduled while the prompt is off`() {
        AppSettings(context).setWeeklyReflectionPromptEnabled(false)

        BootRescheduleReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertThat(weeklyWork()).isEmpty()
    }

    @Test
    fun `other broadcasts are ignored`() {
        AppSettings(context).setWeeklyReflectionPromptEnabled(true)
        BootRescheduleReceiver().onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))
        assertThat(weeklyWork()).isEmpty()
    }

    private val zone = ZoneId.of("Europe/Lisbon")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)

    @Test
    fun `the first run is the next Sunday at 18h local time`() {
        // Friday 11 Sep 2026, 10:00 -> Sunday 13 Sep, 18:00.
        assertThat(WorkScheduler.delayUntilNextWeeklySlot(at(2026, 9, 11, 10)))
            .isEqualTo(Duration.ofDays(2).plusHours(8))
        // Sunday before 18:00 -> the same evening.
        assertThat(WorkScheduler.delayUntilNextWeeklySlot(at(2026, 9, 13, 17, 30)))
            .isEqualTo(Duration.ofMinutes(30))
        // Sunday at exactly 18:00, or after -> the following Sunday, never "now".
        assertThat(WorkScheduler.delayUntilNextWeeklySlot(at(2026, 9, 13, 18)))
            .isEqualTo(Duration.ofDays(7))
        assertThat(WorkScheduler.delayUntilNextWeeklySlot(at(2026, 9, 13, 21)))
            .isEqualTo(Duration.ofDays(6).plusHours(21))
    }
}
