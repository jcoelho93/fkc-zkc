package com.mindfulscroll.app.ui.reflection

import com.google.common.truth.Truth.assertThat
import com.mindfulscroll.app.data.entity.IntentionKind
import com.mindfulscroll.app.reflection.AppReflection
import com.mindfulscroll.app.reflection.OutcomeTally
import com.mindfulscroll.app.reflection.ReflectionWindow
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class ReflectionCopyTest {

    private fun app(timesOpened: Int?, shown: Int = 12, unanswered: Int = 3) = AppReflection(
        packageName = "p",
        promptsShown = shown,
        promptsUnanswered = unanswered,
        byIntention = emptyList(),
        pausesAfterUnansweredPrompt = 0,
        pausesWithoutPrompt = 0,
        timesOpened = timesOpened,
    )

    @Test
    fun `intention labels read back the prompt's own chip words`() {
        assertThat(ReflectionCopy.kindLabel(IntentionKind.CONNECTION)).isEqualTo("Connection")
        assertThat(ReflectionCopy.kindLabel(IntentionKind.SOMETHING_SPECIFIC)).isEqualTo("Checking something specific")
        assertThat(ReflectionCopy.kindChartLabel(IntentionKind.SOMETHING_SPECIFIC)).isEqualTo("Specific")
        assertThat(ReflectionCopy.kindChartLabel(IntentionKind.HABIT)).isEqualTo("Habit")
        // Short enough for one line under a bar with all six columns showing.
        IntentionKind.entries.forEach { assertThat(ReflectionCopy.kindChartLabel(it).length).isAtMost(9) }
    }

    @Test
    fun `the app summary states counts and says nothing about opens it did not count`() {
        assertThat(ReflectionCopy.appSummary(app(timesOpened = 15)))
            .isEqualTo("Opened 15 times · asked 12 times · no answer 3 times")
        assertThat(ReflectionCopy.appSummary(app(timesOpened = null)))
            .isEqualTo("Asked 12 times · no answer 3 times")
        assertThat(ReflectionCopy.appSummary(app(timesOpened = 1, shown = 1, unanswered = 1)))
            .isEqualTo("Opened 1 time · asked 1 time · no answer 1 time")
    }

    @Test
    fun `every count carries the number it is out of`() {
        assertThat(ReflectionCopy.saidLine(3, 12)).isEqualTo("Your answer 3 of 12 times asked")
        assertThat(ReflectionCopy.saidLine(1, 1)).isEqualTo("Your answer 1 of 1 time asked")
    }

    @Test
    fun `the pause line lists every answer, including no answer, and no pause is said plainly`() {
        assertThat(ReflectionCopy.pauseLine(OutcomeTally(yes = 1, kindOf = 2, notReally = 0, noAnswer = 1)))
            .isEqualTo("At the pause, 4 times: yes 1 · kind of 2 · not really 0 · no answer 1")
        assertThat(ReflectionCopy.pauseLine(OutcomeTally())).isEqualTo("No pause on these visits.")
    }

    @Test
    fun `pauses without a question are counted in words`() {
        assertThat(ReflectionCopy.pausesWithoutPromptLine(1)).isEqualTo("1 pause with no question at opening.")
        assertThat(ReflectionCopy.pausesAfterUnansweredLine(2))
            .startsWith("2 pauses after a question left unanswered")
    }

    @Test
    fun `window labels`() {
        val sep = ReflectionWindow.weekEndingOn(LocalDate.of(2026, 9, 10).toEpochDay())
        assertThat(ReflectionCopy.windowLabel(sep, Locale.US)).isEqualTo("4 – 10 Sep")
        val acrossMonths = ReflectionWindow.weekEndingOn(LocalDate.of(2026, 9, 3).toEpochDay())
        assertThat(ReflectionCopy.windowLabel(acrossMonths, Locale.US)).isEqualTo("28 Aug – 3 Sep")
        val acrossYears = ReflectionWindow.weekEndingOn(LocalDate.of(2027, 1, 2).toEpochDay())
        assertThat(ReflectionCopy.windowLabel(acrossYears, Locale.US)).isEqualTo("27 Dec – 2 Jan")
    }

    @Test
    fun `week headings`() {
        assertThat(ReflectionCopy.weekHeading(0)).isEqualTo("Past 7 days")
        assertThat(ReflectionCopy.weekHeading(1)).isEqualTo("The 7 days before")
        assertThat(ReflectionCopy.weekHeading(3)).isEqualTo("3 weeks back")
    }
}
