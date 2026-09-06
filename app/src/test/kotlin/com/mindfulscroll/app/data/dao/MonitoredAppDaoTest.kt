package com.mindfulscroll.app.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mindfulscroll.app.data.AppDatabase
import com.mindfulscroll.app.data.entity.MonitoredAppEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class MonitoredAppDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: MonitoredAppDao

    @Before
    fun createDb() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.monitoredAppDao()
    }

    @After
    fun closeDb() {
        database.close()
    }

    private fun app(
        packageName: String,
        monitored: Boolean = true,
        scrollThreshold: Int = 40,
        timeThresholdMinutes: Int = 10,
    ) = MonitoredAppEntity(
        packageName = packageName,
        appLabel = packageName.substringAfterLast('.'),
        isMonitored = monitored,
        scrollThreshold = scrollThreshold,
        timeThresholdMinutes = timeThresholdMinutes,
        addedAtMillis = 0L,
    )

    @Test
    fun upsertAndGet_roundTripsAllFields() = runTest {
        dao.upsert(app("com.instagram.android", scrollThreshold = 25, timeThresholdMinutes = 7))

        val loaded = dao.get("com.instagram.android")

        assertThat(loaded).isNotNull()
        assertThat(loaded!!.scrollThreshold).isEqualTo(25)
        assertThat(loaded.timeThresholdMinutes).isEqualTo(7)
    }

    @Test
    fun getMonitoredApps_excludesUnmonitoredApps() = runTest {
        dao.upsertAll(
            listOf(
                app("com.instagram.android", monitored = true),
                app("com.reddit.frontpage", monitored = false),
            ),
        )

        val monitored = dao.getMonitoredApps()

        assertThat(monitored.map { it.packageName }).containsExactly("com.instagram.android")
    }

    @Test
    fun observeAll_emitsUpdatesAfterInsert() = runTest {
        assertThat(dao.observeAll().first()).isEmpty()

        dao.upsert(app("com.facebook.katana"))

        assertThat(dao.observeAll().first().map { it.packageName })
            .containsExactly("com.facebook.katana")
    }

    @Test
    fun update_changesThresholdWithoutTouchingOtherApps() = runTest {
        dao.upsertAll(listOf(app("com.instagram.android"), app("com.reddit.frontpage")))
        val instagram = dao.get("com.instagram.android")!!

        dao.update(instagram.copy(scrollThreshold = 100, timeThresholdMinutes = 25))

        val updated = dao.get("com.instagram.android")!!
        assertThat(updated.scrollThreshold).isEqualTo(100)
        assertThat(updated.timeThresholdMinutes).isEqualTo(25)
        assertThat(dao.get("com.reddit.frontpage")!!.scrollThreshold).isEqualTo(40)
    }

    @Test
    fun count_reflectsNumberOfRows() = runTest {
        assertThat(dao.count()).isEqualTo(0)

        dao.upsertAll(listOf(app("com.instagram.android"), app("com.reddit.frontpage")))

        assertThat(dao.count()).isEqualTo(2)
    }
}
