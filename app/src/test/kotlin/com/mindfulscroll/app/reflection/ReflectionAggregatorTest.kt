package com.mindfulscroll.app.reflection

import com.google.common.truth.Truth.assertThat
import com.mindfulscroll.app.data.entity.DailyAppStatEntity
import com.mindfulscroll.app.data.entity.IntentionEntity
import com.mindfulscroll.app.data.entity.IntentionKind
import com.mindfulscroll.app.data.entity.OverlayChoice
import com.mindfulscroll.app.data.entity.OverlayEventEntity
import com.mindfulscroll.app.data.entity.PauseOutcome
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The counting rules of the weekly reflection (#6). The one that matters most: a prompt shown and
 * left unanswered is data, and every "said X on N of M" is out of all M prompts shown, not out of
 * the ones the user happened to answer.
 */
class ReflectionAggregatorTest {

    private val instagram = "com.instagram.android"
    private val reddit = "com.reddit.frontpage"
    private val week = ReflectionWindow.weekEndingOn(20_006) // [20_000, 20_007)

    private var nextId = 1L

    private fun prompt(kind: IntentionKind?, day: Long = 20_003, pkg: String = instagram) = IntentionEntity(
        id = nextId++,
        packageName = pkg,
        dateEpochDay = day,
        sessionStartMillis = 0,
        promptedAtMillis = 0,
        respondedAtMillis = if (kind == null) null else 1,
        kind = kind,
        note = null,
    )

    /**
     * A pause. [intentionId] null is "no prompt this visit"; non-null with a null [kind] is
     * "prompted, ignored".
     */
    private fun pause(
        kind: IntentionKind?,
        outcome: PauseOutcome?,
        intentionId: Long? = if (kind == null) null else 99,
        day: Long = 20_003,
        pkg: String = instagram,
    ) = OverlayEventEntity(
        packageName = pkg,
        dateEpochDay = day,
        shownAtMillis = 0,
        scrollCountAtTrigger = 0,
        sessionTimeMillisAtTrigger = 0,
        choice = OverlayChoice.CLOSE_APP,
        respondedAtMillis = 1,
        intentionId = intentionId,
        intentionKind = kind,
        outcome = outcome,
    )

    private fun stat(openCount: Int?, day: Long = 20_003, pkg: String = instagram) = DailyAppStatEntity(
        packageName = pkg,
        dateEpochDay = day,
        scrollCount = 0,
        foregroundTimeMillis = 0,
        updatedAtMillis = 0,
        openCount = openCount,
    )

    private fun aggregate(
        intentions: List<IntentionEntity> = emptyList(),
        events: List<OverlayEventEntity> = emptyList(),
        stats: List<DailyAppStatEntity> = emptyList(),
        window: ReflectionWindow = week,
    ) = ReflectionAggregator.aggregate(window, intentions, events, stats)

    @Test
    fun `an empty week is empty, not an error`() {
        val result = aggregate()
        assertThat(result.isEmpty).isTrue()
        assertThat(result.window).isEqualTo(week)
        assertThat(result.promptsUnanswered).isEqualTo(0)
        IntentionKind.entries.forEach { assertThat(result.saidAtOpening(it)).isEqualTo(0) }
    }

    @Test
    fun `opens with only daily stats and no prompt or pause are not a reflection`() {
        // The dashboard covers opens. With nothing asked and nothing paused there is nothing to
        // set side by side, and an app card of zeros would be noise.
        assertThat(aggregate(stats = listOf(stat(openCount = 12))).isEmpty).isTrue()
    }

    @Test
    fun `unanswered prompts are counted in the denominator and on their own`() {
        val app = aggregate(
            intentions = listOf(
                prompt(IntentionKind.CONNECTION),
                prompt(IntentionKind.CONNECTION),
                prompt(IntentionKind.HABIT),
                prompt(null),
                prompt(null),
            ),
        ).apps.single()

        assertThat(app.promptsShown).isEqualTo(5)
        assertThat(app.promptsUnanswered).isEqualTo(2)
        assertThat(app.byIntention.map { it.kind to it.saidAtOpening }).containsExactly(
            IntentionKind.CONNECTION to 2,
            IntentionKind.HABIT to 1,
        ).inOrder()
    }

    @Test
    fun `a week of only unanswered prompts is still a reflection`() {
        val result = aggregate(intentions = listOf(prompt(null), prompt(null), prompt(null)))

        assertThat(result.isEmpty).isFalse()
        val app = result.apps.single()
        assertThat(app.promptsShown).isEqualTo(3)
        assertThat(app.promptsUnanswered).isEqualTo(3)
        assertThat(app.byIntention).isEmpty()
        assertThat(result.promptsUnanswered).isEqualTo(3)
    }

    @Test
    fun `pause outcomes are tallied under the intention given at opening`() {
        val app = aggregate(
            intentions = List(4) { prompt(IntentionKind.CONNECTION) },
            events = listOf(
                pause(IntentionKind.CONNECTION, PauseOutcome.YES),
                pause(IntentionKind.CONNECTION, PauseOutcome.NOT_REALLY),
                pause(IntentionKind.CONNECTION, PauseOutcome.NOT_REALLY),
                pause(IntentionKind.CONNECTION, PauseOutcome.KIND_OF),
                // Question on screen, user left without answering: counted as no answer.
                pause(IntentionKind.CONNECTION, outcome = null),
            ),
        ).apps.single()

        val connection = app.byIntention.single()
        assertThat(connection.kind).isEqualTo(IntentionKind.CONNECTION)
        assertThat(connection.saidAtOpening).isEqualTo(4)
        assertThat(connection.atPause).isEqualTo(OutcomeTally(yes = 1, kindOf = 1, notReally = 2, noAnswer = 1))
        assertThat(connection.atPause.total).isEqualTo(5)
    }

    @Test
    fun `a pause after an ignored prompt and a pause with no prompt are told apart`() {
        val app = aggregate(
            intentions = listOf(prompt(null)),
            events = listOf(
                pause(kind = null, outcome = null, intentionId = 1), // prompted, ignored
                pause(kind = null, outcome = null, intentionId = 1),
                pause(kind = null, outcome = null, intentionId = null), // never prompted
            ),
        ).apps.single()

        assertThat(app.pausesAfterUnansweredPrompt).isEqualTo(2)
        assertThat(app.pausesWithoutPrompt).isEqualTo(1)
        assertThat(app.byIntention).isEmpty()
        assertThat(app.pausesShown).isEqualTo(3)
    }

    @Test
    fun `an intention paused on but not prompted inside the window still appears`() {
        // The prompt was on the last day of the previous week; the pause landed inside this one.
        val app = aggregate(
            intentions = listOf(prompt(IntentionKind.DISTRACTION, day = 19_999)),
            events = listOf(pause(IntentionKind.DISTRACTION, PauseOutcome.YES)),
        ).apps.single()

        val row = app.byIntention.single()
        assertThat(row.saidAtOpening).isEqualTo(0)
        assertThat(row.atPause.yes).isEqualTo(1)
    }

    @Test
    fun `rows outside the window are ignored, and its edges are start-inclusive, end-exclusive`() {
        val result = aggregate(
            intentions = listOf(
                prompt(IntentionKind.HABIT, day = 19_999), // day before
                prompt(IntentionKind.HABIT, day = 20_000), // first day
                prompt(IntentionKind.HABIT, day = 20_006), // last day
                prompt(IntentionKind.HABIT, day = 20_007), // day after
            ),
            events = listOf(pause(IntentionKind.HABIT, PauseOutcome.YES, day = 20_007)),
            stats = listOf(stat(openCount = 3, day = 19_999), stat(openCount = 2, day = 20_000)),
        )

        val app = result.apps.single()
        assertThat(app.promptsShown).isEqualTo(2)
        assertThat(app.byIntention.single().atPause.total).isEqualTo(0)
        assertThat(app.timesOpened).isEqualTo(2)
    }

    @Test
    fun `the same rules serve a partial week`() {
        // #31's mid-week check-in reuses this against three days rather than seven.
        val firstThreeDays = ReflectionWindow(20_000, 20_003)
        val result = aggregate(
            intentions = listOf(
                prompt(IntentionKind.ENTERTAINMENT, day = 20_001),
                prompt(IntentionKind.ENTERTAINMENT, day = 20_004),
            ),
            window = firstThreeDays,
        )
        assertThat(result.window).isEqualTo(firstThreeDays)
        assertThat(result.saidAtOpening(IntentionKind.ENTERTAINMENT)).isEqualTo(1)
    }

    @Test
    fun `times opened is null when any day in the window predates open counting`() {
        val intentions = listOf(prompt(IntentionKind.HABIT))
        assertThat(aggregate(intentions, stats = listOf(stat(4, day = 20_001), stat(null, day = 20_002))).apps.single().timesOpened)
            .isNull()
        assertThat(aggregate(intentions, stats = listOf(stat(4, day = 20_001), stat(5, day = 20_002))).apps.single().timesOpened)
            .isEqualTo(9)
        assertThat(aggregate(intentions).apps.single().timesOpened).isEqualTo(0)
    }

    @Test
    fun `intentions keep the prompt's order rather than being ranked`() {
        val app = aggregate(
            intentions = listOf(
                prompt(IntentionKind.SOMETHING_SPECIFIC),
                prompt(IntentionKind.HABIT),
                prompt(IntentionKind.HABIT),
                prompt(IntentionKind.HABIT),
                prompt(IntentionKind.CONNECTION),
            ),
        ).apps.single()

        assertThat(app.byIntention.map { it.kind }).containsExactly(
            IntentionKind.CONNECTION,
            IntentionKind.HABIT,
            IntentionKind.SOMETHING_SPECIFIC,
        ).inOrder()
    }

    @Test
    fun `apps are separated, and ordered by how often the question appeared`() {
        val result = aggregate(
            intentions = listOf(
                prompt(IntentionKind.HABIT, pkg = instagram),
                prompt(IntentionKind.CONNECTION, pkg = reddit),
                prompt(null, pkg = reddit),
            ),
            events = listOf(pause(IntentionKind.CONNECTION, PauseOutcome.KIND_OF, pkg = reddit)),
        )

        assertThat(result.apps.map { it.packageName }).containsExactly(reddit, instagram).inOrder()
        assertThat(result.saidAtOpening(IntentionKind.HABIT)).isEqualTo(1)
        assertThat(result.saidAtOpening(IntentionKind.CONNECTION)).isEqualTo(1)
        assertThat(result.promptsUnanswered).isEqualTo(1)
        assertThat(result.apps.first().byIntention.single().atPause.kindOf).isEqualTo(1)
    }

    @Test
    fun `a window must contain at least one day`() {
        assertThrows(IllegalArgumentException::class.java) { ReflectionWindow(10, 10) }
        val w = ReflectionWindow.weekEndingOn(100)
        assertThat(w.startEpochDay).isEqualTo(94)
        assertThat(w.lastEpochDay).isEqualTo(100)
        assertThat(w.endEpochDayExclusive - w.startEpochDay).isEqualTo(7)
        assertThat(93L in w).isFalse()
        assertThat(94L in w).isTrue()
        assertThat(100L in w).isTrue()
        assertThat(101L in w).isFalse()
    }
}
