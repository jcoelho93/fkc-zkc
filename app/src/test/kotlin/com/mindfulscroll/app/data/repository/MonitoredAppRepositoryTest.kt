package com.mindfulscroll.app.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mindfulscroll.app.data.AppDatabase
import com.mindfulscroll.app.data.entity.MonitoredAppEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * `applySelection` is the write path behind the app picker, and it became load-bearing the moment
 * the picker stopped being a once-per-install onboarding step (#19).
 *
 * Both behaviours asserted here are silent when wrong: the user opens the picker to add one app
 * and either loses another one's tuned thresholds, or fails to remove the app they just unticked.
 * Neither produces an error, and the second looks exactly like the feature working.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class MonitoredAppRepositoryTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: MonitoredAppRepository

    @Before
    fun createDb() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = MonitoredAppRepository(database.monitoredAppDao())
    }

    @After
    fun closeDb() {
        database.close()
    }

    /** What the picker builds for an app it has never seen: defaults, monitored, "now". */
    private fun freshPick(packageName: String, label: String = packageName.substringAfterLast('.')) =
        MonitoredAppEntity(
            packageName = packageName,
            appLabel = label,
            isMonitored = true,
            addedAtMillis = 9999,
        )

    @Test
    fun applySelection_addsNewApps() = runTest {
        repository.applySelection(listOf(freshPick("com.instagram.android")))

        assertThat(repository.getAllPackageNames()).containsExactly("com.instagram.android")
    }

    @Test
    fun applySelection_deletesAppsTakenOffTheList() = runTest {
        repository.applySelection(
            listOf(freshPick("com.instagram.android"), freshPick("com.reddit.frontpage")),
        )

        repository.applySelection(listOf(freshPick("com.instagram.android")))

        // upsertAll alone cannot express removal - it only ever adds. An app unticked in the
        // picker that stays monitored anyway is the whole feature silently not working.
        assertThat(repository.getAllPackageNames()).containsExactly("com.instagram.android")
    }

    @Test
    fun applySelection_keepsTunedThresholdsOfAppsAlreadyOnTheList() = runTest {
        repository.applySelection(listOf(freshPick("com.instagram.android")))
        repository.updateThresholds("com.instagram.android", scrollThreshold = 5, timeThresholdMinutes = 2)

        // Re-running the picker to add an unrelated app must not touch Instagram's row. The
        // entities the picker constructs carry DEFAULT thresholds, so a plain REPLACE upsert
        // would quietly undo every threshold the user had tuned.
        repository.applySelection(
            listOf(freshPick("com.instagram.android"), freshPick("com.reddit.frontpage")),
        )

        val instagram = repository.get("com.instagram.android")!!
        assertThat(instagram.scrollThreshold).isEqualTo(5)
        assertThat(instagram.timeThresholdMinutes).isEqualTo(2)
    }

    @Test
    fun applySelection_keepsAPausedAppPaused() = runTest {
        repository.applySelection(listOf(freshPick("com.instagram.android")))
        val paused = repository.get("com.instagram.android")!!
        repository.setMonitored(paused, monitored = false)

        repository.applySelection(
            listOf(freshPick("com.instagram.android"), freshPick("com.reddit.frontpage")),
        )

        // Being on the list and being switched on are different states. Opening the picker must
        // not silently re-enable an app the user deliberately paused in Settings.
        assertThat(repository.get("com.instagram.android")!!.isMonitored).isFalse()
        assertThat(repository.get("com.reddit.frontpage")!!.isMonitored).isTrue()
    }

    @Test
    fun applySelection_refreshesTheLabelOfAnAppAlreadyOnTheList() = runTest {
        repository.applySelection(listOf(freshPick("com.instagram.android", label = "Instagram")))

        repository.applySelection(listOf(freshPick("com.instagram.android", label = "Instagram Lite")))

        // The label is the one field worth taking from the fresh pick: an app can be renamed by
        // an update, and a stale label in Settings is confusing with no upside.
        assertThat(repository.get("com.instagram.android")!!.appLabel).isEqualTo("Instagram Lite")
    }

    @Test
    fun applySelection_withNothingSelectedClearsTheList() = runTest {
        repository.applySelection(listOf(freshPick("com.instagram.android")))

        repository.applySelection(emptyList())

        // "Monitor no apps" is a legitimate choice the picker offers explicitly, so it has to
        // actually take effect rather than being read as "no changes".
        assertThat(repository.getAllPackageNames()).isEmpty()
    }
}
