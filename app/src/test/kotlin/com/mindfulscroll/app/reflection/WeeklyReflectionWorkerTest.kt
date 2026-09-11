package com.mindfulscroll.app.reflection

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import com.mindfulscroll.app.data.AppDatabase
import com.mindfulscroll.app.data.AppSettings
import com.mindfulscroll.app.data.entity.IntentionEntity
import com.mindfulscroll.app.data.entity.IntentionKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * One weekly run, end to end against a real (in-memory) database: every path records what it did,
 * and none of them retries - a retry would be a second attempt at someone's attention (#33).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class WeeklyReflectionWorkerTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private lateinit var database: AppDatabase
    private lateinit var settings: AppSettings
    private lateinit var status: WeeklyReflectionStatus

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        app.getSharedPreferences("mindful_scroll_settings", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("weekly_reflection_status", Context.MODE_PRIVATE).edit().clear().commit()
        settings = AppSettings(app)
        status = WeeklyReflectionStatus(app)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun runWorker(): ListenableWorker.Result {
        val repository = ReflectionRepository(database.intentionDao(), database.overlayEventDao(), database.dailyAppStatDao())
        val worker = TestListenableWorkerBuilder<WeeklyReflectionWorker>(app)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(context: Context, name: String, params: WorkerParameters) =
                    WeeklyReflectionWorker(context, params, settings, repository, ReflectionNotifier(app), status)
            })
            .build()
        return worker.doWork()
    }

    private suspend fun recordAnsweredPromptToday() {
        database.intentionDao().insert(
            IntentionEntity(
                packageName = "com.instagram.android",
                dateEpochDay = LocalDate.now().toEpochDay(),
                sessionStartMillis = 0,
                promptedAtMillis = 0,
                respondedAtMillis = 1,
                kind = IntentionKind.CONNECTION,
                note = null,
            ),
        )
    }

    @Test
    fun `with the prompt off it posts nothing, even with data and permission`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        recordAnsweredPromptToday()

        assertThat(runWorker()).isEqualTo(ListenableWorker.Result.success())
        assertThat(manager.activeNotifications).isEmpty()
        assertThat(status.lastRun()?.result).isEqualTo(WeeklyPromptResult.SKIPPED_PROMPT_OFF)
    }

    @Test
    fun `an empty week is not worth a notification`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        settings.setWeeklyReflectionPromptEnabled(true)

        assertThat(runWorker()).isEqualTo(ListenableWorker.Result.success())
        assertThat(manager.activeNotifications).isEmpty()
        assertThat(status.lastRun()?.result).isEqualTo(WeeklyPromptResult.SKIPPED_NOTHING_RECORDED)
    }

    @Test
    fun `a denied permission is recorded as blocked, not retried and not re-requested`() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        settings.setWeeklyReflectionPromptEnabled(true)
        recordAnsweredPromptToday()

        // success(), not retry(): WorkManager must not come back and try again.
        assertThat(runWorker()).isEqualTo(ListenableWorker.Result.success())
        assertThat(manager.activeNotifications).isEmpty()
        val run = status.lastRun()!!
        assertThat(run.result).isEqualTo(WeeklyPromptResult.SKIPPED_BLOCKED)
        assertThat(run.detail).contains("POST_NOTIFICATIONS")
        // The prompt setting is left alone: the job never turns things off or asks for anything.
        assertThat(settings.weeklyReflectionPromptEnabledNow()).isTrue()
    }

    @Test
    fun `with data and permission it posts one notification and confirms it is showing`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        settings.setWeeklyReflectionPromptEnabled(true)
        recordAnsweredPromptToday()

        assertThat(runWorker()).isEqualTo(ListenableWorker.Result.success())
        assertThat(manager.activeNotifications.map { it.id }).containsExactly(ReflectionNotifier.NOTIFICATION_ID)
        assertThat(status.lastRun()?.result).isEqualTo(WeeklyPromptResult.POSTED_AND_SHOWING)

        // A week later with the first one never opened: replaced, not piled up.
        runWorker()
        assertThat(manager.activeNotifications.toList()).hasSize(1)
    }

    @Test
    fun `only unanswered prompts still count as something to reflect on`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        settings.setWeeklyReflectionPromptEnabled(true)
        database.intentionDao().insert(
            IntentionEntity(
                packageName = "com.instagram.android",
                dateEpochDay = LocalDate.now().toEpochDay(),
                sessionStartMillis = 0,
                promptedAtMillis = 0,
                respondedAtMillis = null,
                kind = null,
                note = null,
            ),
        )

        runWorker()
        assertThat(status.lastRun()?.result).isEqualTo(WeeklyPromptResult.POSTED_AND_SHOWING)
    }
}
