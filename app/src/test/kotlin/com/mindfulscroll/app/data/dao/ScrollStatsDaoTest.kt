package com.mindfulscroll.app.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mindfulscroll.app.data.AppDatabase
import com.mindfulscroll.app.data.entity.ActiveSessionEntity
import com.mindfulscroll.app.data.entity.DailyAppStatEntity
import com.mindfulscroll.app.data.entity.OverlayChoice
import com.mindfulscroll.app.data.entity.OverlayEventEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ScrollStatsDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dailyAppStatDao: DailyAppStatDao
    private lateinit var activeSessionDao: ActiveSessionDao
    private lateinit var overlayEventDao: OverlayEventDao

    private val packageName = "com.instagram.android"

    @Before
    fun createDb() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dailyAppStatDao = database.dailyAppStatDao()
        activeSessionDao = database.activeSessionDao()
        overlayEventDao = database.overlayEventDao()
    }

    @After
    fun closeDb() {
        database.close()
    }

    // --- DailyAppStatDao ---

    @Test
    fun dailyStat_upsertIsKeyedByPackageAndDay() = runTest {
        dailyAppStatDao.upsert(DailyAppStatEntity(packageName, dateEpochDay = 100, scrollCount = 5, foregroundTimeMillis = 1_000, updatedAtMillis = 1))
        dailyAppStatDao.upsert(DailyAppStatEntity(packageName, dateEpochDay = 100, scrollCount = 8, foregroundTimeMillis = 2_000, updatedAtMillis = 2))
        dailyAppStatDao.upsert(DailyAppStatEntity(packageName, dateEpochDay = 101, scrollCount = 1, foregroundTimeMillis = 100, updatedAtMillis = 3))

        val day100 = dailyAppStatDao.get(packageName, 100)
        val day101 = dailyAppStatDao.get(packageName, 101)

        assertThat(day100!!.scrollCount).isEqualTo(8) // second upsert replaced the first, same key
        assertThat(day101!!.scrollCount).isEqualTo(1)
    }

    @Test
    fun dailyStat_observeForDayRange_filtersByRange() = runTest {
        (95L..105L).forEach { day ->
            dailyAppStatDao.upsert(DailyAppStatEntity(packageName, day, scrollCount = day.toInt(), foregroundTimeMillis = 0, updatedAtMillis = 0))
        }

        val inRange = dailyAppStatDao.observeForDayRange(98, 102).first()

        assertThat(inRange.map { it.dateEpochDay }).containsExactly(98L, 99L, 100L, 101L, 102L)
    }

    @Test
    fun dailyStat_deleteOlderThan_prunesOnlyOldRows() = runTest {
        dailyAppStatDao.upsert(DailyAppStatEntity(packageName, 100, 1, 0, 0))
        dailyAppStatDao.upsert(DailyAppStatEntity(packageName, 200, 1, 0, 0))

        dailyAppStatDao.deleteOlderThan(150)

        val remaining = dailyAppStatDao.getForDayRange(0, 1_000)
        assertThat(remaining.map { it.dateEpochDay }).containsExactly(200L)
    }

    // --- ActiveSessionDao ---

    @Test
    fun activeSession_upsertOverwritesByPackageName() = runTest {
        activeSessionDao.upsert(ActiveSessionEntity(packageName, sessionStartMillis = 10, scrollCountInSession = 1))
        activeSessionDao.upsert(ActiveSessionEntity(packageName, sessionStartMillis = 20, scrollCountInSession = 2))

        val session = activeSessionDao.get(packageName)

        assertThat(session!!.sessionStartMillis).isEqualTo(20)
        assertThat(session.scrollCountInSession).isEqualTo(2)
    }

    @Test
    fun activeSession_clear_removesOnlyThatPackage() = runTest {
        activeSessionDao.upsert(ActiveSessionEntity(packageName, 10, 1))
        activeSessionDao.upsert(ActiveSessionEntity("com.reddit.frontpage", 10, 1))

        activeSessionDao.clear(packageName)

        assertThat(activeSessionDao.get(packageName)).isNull()
        assertThat(activeSessionDao.get("com.reddit.frontpage")).isNotNull()
    }

    @Test
    fun activeSession_clearStale_removesOnlyOldSessions() = runTest {
        activeSessionDao.upsert(ActiveSessionEntity(packageName, sessionStartMillis = 1_000, scrollCountInSession = 1))
        activeSessionDao.upsert(ActiveSessionEntity("com.reddit.frontpage", sessionStartMillis = 9_000, scrollCountInSession = 1))

        activeSessionDao.clearStale(cutoffMillis = 5_000)

        assertThat(activeSessionDao.get(packageName)).isNull()
        assertThat(activeSessionDao.get("com.reddit.frontpage")).isNotNull()
    }

    // --- OverlayEventDao ---

    @Test
    fun overlayEvent_insertThenUpdateChoice_isReflectedInGet() = runTest {
        val id = overlayEventDao.insert(
            OverlayEventEntity(
                packageName = packageName,
                dateEpochDay = 100,
                shownAtMillis = 1_000,
                scrollCountAtTrigger = 40,
                sessionTimeMillisAtTrigger = 600_000,
                choice = OverlayChoice.PENDING,
                respondedAtMillis = null,
            ),
        )

        val stored = overlayEventDao.get(id)!!
        overlayEventDao.update(stored.copy(choice = OverlayChoice.CLOSE_APP, respondedAtMillis = 2_000))

        val updated = overlayEventDao.get(id)!!
        assertThat(updated.choice).isEqualTo(OverlayChoice.CLOSE_APP)
        assertThat(updated.respondedAtMillis).isEqualTo(2_000)
    }

    @Test
    fun overlayEvent_observeForDayRange_ordersMostRecentFirst() = runTest {
        overlayEventDao.insert(baseOverlayEvent(day = 100, shownAt = 1_000))
        overlayEventDao.insert(baseOverlayEvent(day = 100, shownAt = 5_000))
        overlayEventDao.insert(baseOverlayEvent(day = 100, shownAt = 3_000))

        val events = overlayEventDao.observeForDayRange(100, 100).first()

        assertThat(events.map { it.shownAtMillis }).containsExactly(5_000L, 3_000L, 1_000L).inOrder()
    }

    private fun baseOverlayEvent(day: Long, shownAt: Long) = OverlayEventEntity(
        packageName = packageName,
        dateEpochDay = day,
        shownAtMillis = shownAt,
        scrollCountAtTrigger = 40,
        sessionTimeMillisAtTrigger = 0,
        choice = OverlayChoice.PENDING,
        respondedAtMillis = null,
    )
}
