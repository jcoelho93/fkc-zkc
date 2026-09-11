package com.mindfulscroll.app.reflection

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.mindfulscroll.app.data.AppDatabase
import com.mindfulscroll.app.data.entity.IntentionEntity
import com.mindfulscroll.app.data.entity.IntentionKind
import com.mindfulscroll.app.data.entity.OverlayChoice
import com.mindfulscroll.app.data.entity.OverlayEventEntity
import com.mindfulscroll.app.data.entity.PauseOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The SQL side of the window: the DAOs' BETWEEN is inclusive on both ends, the window is
 * end-exclusive, and the two reads (one-shot for the worker, observed for the screen) must agree.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ReflectionRepositoryTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: ReflectionRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = ReflectionRepository(database.intentionDao(), database.overlayEventDao(), database.dailyAppStatDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seed(day: Long, kind: IntentionKind?) {
        val id = database.intentionDao().insert(
            IntentionEntity(
                packageName = "com.reddit.frontpage",
                dateEpochDay = day,
                sessionStartMillis = day,
                promptedAtMillis = day,
                respondedAtMillis = null,
                kind = kind,
                note = null,
            ),
        )
        database.overlayEventDao().insert(
            OverlayEventEntity(
                packageName = "com.reddit.frontpage",
                dateEpochDay = day,
                shownAtMillis = day,
                scrollCountAtTrigger = 0,
                sessionTimeMillisAtTrigger = 0,
                choice = OverlayChoice.CONTINUE,
                respondedAtMillis = null,
                intentionId = id,
                intentionKind = kind,
                outcome = if (kind == null) null else PauseOutcome.KIND_OF,
            ),
        )
    }

    @Test
    fun `load and observe read exactly the window's days and agree`() = runTest {
        val window = ReflectionWindow.weekEndingOn(20_006)
        seed(19_999, IntentionKind.HABIT) // outside, before
        seed(20_000, IntentionKind.HABIT) // first day
        seed(20_006, null) // last day, unanswered
        seed(20_007, IntentionKind.HABIT) // outside, after

        val loaded = repository.load(window)
        val observed = repository.observe(window).first()

        assertThat(observed).isEqualTo(loaded)
        val app = loaded.apps.single()
        assertThat(app.promptsShown).isEqualTo(2)
        assertThat(app.promptsUnanswered).isEqualTo(1)
        assertThat(app.byIntention.single().atPause.kindOf).isEqualTo(1)
        assertThat(app.pausesAfterUnansweredPrompt).isEqualTo(1)
    }
}
