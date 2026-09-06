package com.mindfulscroll.app.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mindfulscroll.app.data.AppDatabase
import com.mindfulscroll.app.data.entity.IntentionEntity
import com.mindfulscroll.app.data.entity.IntentionKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class IntentionDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var intentionDao: IntentionDao

    private val packageName = "com.instagram.android"

    private fun prompt(
        sessionStartMillis: Long,
        promptedAtMillis: Long = sessionStartMillis,
        dateEpochDay: Long = 20_000,
        kind: IntentionKind? = null,
        note: String? = null,
        respondedAtMillis: Long? = null,
        pkg: String = packageName,
    ) = IntentionEntity(
        packageName = pkg,
        dateEpochDay = dateEpochDay,
        sessionStartMillis = sessionStartMillis,
        promptedAtMillis = promptedAtMillis,
        respondedAtMillis = respondedAtMillis,
        kind = kind,
        note = note,
    )

    @Before
    fun createDb() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        intentionDao = database.intentionDao()
    }

    @After
    fun closeDb() {
        database.close()
    }

    @Test
    fun unansweredPromptRoundTripsWithNullKind() = runTest {
        // "Shown and ignored" is a real answer the weekly report has to be able to count. If a
        // null kind could not be stored, every rate would silently be computed over the subset of
        // opens the user felt like explaining.
        val id = intentionDao.insert(prompt(sessionStartMillis = 1_000))

        val stored = intentionDao.get(id)

        assertThat(stored).isNotNull()
        assertThat(stored!!.kind).isNull()
        assertThat(stored.respondedAtMillis).isNull()
    }

    @Test
    fun answeringAPromptPreservesTheEnumConstant() = runTest {
        val id = intentionDao.insert(prompt(sessionStartMillis = 1_000))

        intentionDao.update(
            intentionDao.get(id)!!.copy(
                kind = IntentionKind.SOMETHING_SPECIFIC,
                note = "replying to Ana",
                respondedAtMillis = 2_000,
            ),
        )

        val answered = intentionDao.get(id)!!
        assertThat(answered.kind).isEqualTo(IntentionKind.SOMETHING_SPECIFIC)
        assertThat(answered.note).isEqualTo("replying to Ana")
        assertThat(answered.respondedAtMillis).isEqualTo(2_000)
    }

    @Test
    fun getForSession_returnsTheIntentionForThatVisitOnly() = runTest {
        // The pause screen asks "you said X - did you get that?" about the CURRENT visit. Picking
        // up an earlier session's answer would put words in the user's mouth.
        intentionDao.insert(prompt(sessionStartMillis = 1_000, kind = IntentionKind.HABIT))
        intentionDao.insert(prompt(sessionStartMillis = 9_000, kind = IntentionKind.CONNECTION))

        val forSecondVisit = intentionDao.getForSession(packageName, 9_000)

        assertThat(forSecondVisit!!.kind).isEqualTo(IntentionKind.CONNECTION)
    }

    @Test
    fun getForSession_doesNotLeakAcrossApps() = runTest {
        intentionDao.insert(prompt(sessionStartMillis = 1_000, kind = IntentionKind.HABIT))
        intentionDao.insert(
            prompt(sessionStartMillis = 1_000, kind = IntentionKind.CONNECTION, pkg = "com.reddit.frontpage"),
        )

        assertThat(intentionDao.getForSession(packageName, 1_000)!!.kind).isEqualTo(IntentionKind.HABIT)
        assertThat(intentionDao.getForSession("com.reddit.frontpage", 1_000)!!.kind)
            .isEqualTo(IntentionKind.CONNECTION)
    }

    @Test
    fun observeForDayRange_isBoundedAtBothEnds() = runTest {
        intentionDao.insert(prompt(sessionStartMillis = 1, dateEpochDay = 99))
        intentionDao.insert(prompt(sessionStartMillis = 2, dateEpochDay = 100))
        intentionDao.insert(prompt(sessionStartMillis = 3, dateEpochDay = 106))
        intentionDao.insert(prompt(sessionStartMillis = 4, dateEpochDay = 107))

        val week = intentionDao.observeForDayRange(100, 106).first()

        assertThat(week.map { it.dateEpochDay }).containsExactly(100L, 106L)
    }

    @Test
    fun deleteOlderThan_keepsTheCutoffDay() = runTest {
        intentionDao.insert(prompt(sessionStartMillis = 1, dateEpochDay = 99))
        intentionDao.insert(prompt(sessionStartMillis = 2, dateEpochDay = 100))

        intentionDao.deleteOlderThan(100)

        assertThat(intentionDao.getForDayRange(0, 1_000).map { it.dateEpochDay }).containsExactly(100L)
    }
}
