package com.mindfulscroll.app.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mindfulscroll.app.data.AppDatabase
import com.mindfulscroll.app.data.entity.DailyAppStatEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The daily counters behind the dashboard and the weekly reflection: time in app, times opened
 * (#28), and scrolls. The core claim of #28 is that these are independent measures, and each test
 * here pins one way they could quietly stop being so.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ScrollStatsRepositoryTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: ScrollStatsRepository

    private val instagram = "com.instagram.android"
    private val reddit = "com.reddit.frontpage"

    /** A fixed instant, so every write below lands on the same local day. */
    private val now = 1_780_000_000_000L
    private val minute = 60_000L

    @Before
    fun createDb() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        repository = ScrollStatsRepository(
            database.dailyAppStatDao(),
            database.activeSessionDao(),
            database.overlayEventDao(),
        )
    }

    @After
    fun closeDb() {
        database.close()
    }

    private suspend fun today(packageName: String): DailyAppStatEntity? =
        database.dailyAppStatDao().get(packageName, repository.epochDayFor(now))

    /**
     * Mirrors what the service does on a foreground-entry followed, later, by leaving: one open,
     * and the dwell time banked when the app goes to the background.
     */
    private suspend fun visit(packageName: String, durationMillis: Long) {
        repository.recordOpen(packageName, now)
        repository.addForegroundTime(packageName, durationMillis, now)
    }

    @Test
    fun manyShortVisitsAndOneLongOneShareATotalDurationButNotAnOpenCount() = runTest {
        // The case #28 exists for. Ten minutes either way, and a completely different habit:
        // five quick checks against one sitting.
        repeat(5) { visit(instagram, durationMillis = 2 * minute) }
        visit(reddit, durationMillis = 10 * minute)

        val checks = today(instagram)!!
        val sitting = today(reddit)!!

        assertThat(checks.foregroundTimeMillis).isEqualTo(sitting.foregroundTimeMillis)
        assertThat(checks.openCount).isEqualTo(5)
        assertThat(sitting.openCount).isEqualTo(1)
    }

    @Test
    fun scrollsAndTimeNeverCountAsOpens() = runTest {
        repository.recordOpen(instagram, now)
        repeat(40) { repository.recordScroll(instagram, now) }
        repository.addForegroundTime(instagram, 30 * minute, now)

        val stat = today(instagram)!!
        assertThat(stat.openCount).isEqualTo(1)
        assertThat(stat.scrollCount).isEqualTo(40)
        assertThat(stat.foregroundTimeMillis).isEqualTo(30 * minute)
    }

    @Test
    fun aDayFirstTouchedByAScrollOrByTimeStartsAtZeroOpensNotNull() = runTest {
        // Rows created after the migration are counted days: 0 means "no opens yet", and the
        // next foreground-entry makes it 1.
        repository.recordScroll(instagram, now)
        assertThat(today(instagram)!!.openCount).isEqualTo(0)

        repository.recordOpen(instagram, now)
        assertThat(today(instagram)!!.openCount).isEqualTo(1)
    }

    @Test
    fun aDayRecordedBeforeOpensWereCountedStaysNotCounted() = runTest {
        // What MIGRATION_4_5 leaves behind for today, if the update landed mid-day.
        database.dailyAppStatDao().upsert(
            DailyAppStatEntity(
                packageName = instagram,
                dateEpochDay = repository.epochDayFor(now),
                scrollCount = 12,
                foregroundTimeMillis = 20 * minute,
                updatedAtMillis = now,
                openCount = null,
            ),
        )

        repository.recordOpen(instagram, now)
        repository.recordScroll(instagram, now)
        repository.addForegroundTime(instagram, minute, now)

        val stat = today(instagram)!!
        // A partial count for that day would be read as the whole day's. Null says what's true.
        assertThat(stat.openCount).isNull()
        // Everything else keeps counting as before.
        assertThat(stat.scrollCount).isEqualTo(13)
        assertThat(stat.foregroundTimeMillis).isEqualTo(21 * minute)
    }

    @Test
    fun concurrentWritersToTheSameDayLoseNothing() = runTest {
        // Three writers share one row, from concurrent coroutines in the service. With the old
        // read-then-upsert, whichever wrote last silently undid the others' increments.
        coroutineScope {
            repeat(50) {
                launch(Dispatchers.IO) { repository.recordOpen(instagram, now) }
                launch(Dispatchers.IO) { repository.recordScroll(instagram, now) }
                launch(Dispatchers.IO) { repository.addForegroundTime(instagram, 1_000L, now) }
            }
        }

        val stat = today(instagram)!!
        assertThat(stat.openCount).isEqualTo(50)
        assertThat(stat.scrollCount).isEqualTo(50)
        assertThat(stat.foregroundTimeMillis).isEqualTo(50_000L)
    }
}
